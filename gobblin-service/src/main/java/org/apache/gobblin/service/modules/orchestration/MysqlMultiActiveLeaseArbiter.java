/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.service.modules.orchestration;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransientException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import com.typesafe.config.Config;

import javax.sql.DataSource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.apache.gobblin.broker.SharedResourcesBrokerFactory;
import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.metastore.MysqlDataSourceFactory;
import org.apache.gobblin.service.ServiceConfigKeys;
import org.apache.gobblin.util.ConfigUtils;
import org.apache.gobblin.util.DBStatementExecutor;
import org.apache.gobblin.util.ExponentialBackoff;


/**
 * MySQL based implementation of the {@link MultiActiveLeaseArbiter} which uses a MySQL store to resolve ownership of
 * a dag action event amongst multiple competing participants. A MySQL table is used to store flow and job identifying
 * information as well as the dag action associated with it. It uses two additional values of the `event_timestamp` and
 * `lease_acquisition_timestamp` to indicate an active lease, expired lease, and state of no longer leasing. The table
 * schema is as follows:
 * [flow_group | flow_name | job_name | dag_action | event_timestamp | lease_acquisition_timestamp]
 * (----------------------primary key------------------------)
 * We also maintain another table in the database with two constants that allow us to coordinate between participants
 * and ensure they are using the same values to base their coordination off of.
 * [epsilon | linger]
 * `epsilon` - time within we consider two event timestamps to be overlapping and can consolidate
 * `linger` - minimum time to occur before another host may attempt a lease on a flow event. It should be much greater
 *            than epsilon and encapsulate executor communication latency including retry attempts
 *
 * The `event_timestamp` is the time of the dag_action event request.
 * --- Database event_timestamp laundering ---
 * We only use the participant's local event_timestamp internally to identify the particular dag_action event, but
 * after interacting with the database utilize the CURRENT_TIMESTAMP of the database to insert or keep
 * track of our event, "laundering" or replacing the local timestamp with the database one. This is to avoid any
 * discrepancies due to clock drift between participants as well as variation in local time and database time for
 * future comparisons.
 * --- Event consolidation ---
 * Note that for the sake of simplification, we only allow one event associated with a particular flow's dag_action
 * (ie: only one LAUNCH for example of flow FOO, but there can be a LAUNCH, KILL, & RESUME for flow FOO at once) during
 * the time it takes to execute the dag action. In most cases, the execution time should be so negligible that this
 * event consolidation of duplicate dag action requests is not noticed and even during executor downtime this behavior
 * is acceptable as the user generally expects a timely execution of the most recent request rather than one execution
 * per request.
 *
 * The `lease_acquisition_timestamp` is the time a host acquired ownership of this dag action, and it is valid for
 * `linger` period of time after which it expires and any host can re-attempt ownership. In most cases, the original
 * host should actually complete its work while having the lease and then mark the dag action as NULL to indicate no
 * further leasing should be done for the event.
 */
@Slf4j
public class MysqlMultiActiveLeaseArbiter implements MultiActiveLeaseArbiter {

  protected final DataSource dataSource;
  private final DBStatementExecutor dbStatementExecutor;
  private final String leaseArbiterTableName;
  private final String constantsTableName;
  private final int epsilonMillis;
  private final int lingerMillis;
  private final long retentionPeriodMillis;
  private String thisTableRetentionStatement;
  private final String thisTableGetInfoStatement;
  private final String thisTableGetInfoStatementForReminder;
  private final String thisTableSelectAfterInsertStatement;
  private final String thisTableAcquireLeaseIfMatchingAllStatement;
  private final String thisTableAcquireLeaseIfFinishedStatement;

  /*
    Notes:
    - Set `event_timestamp` default value to turn off timestamp auto-updates for row modifications which alters this col
      in an unexpected way upon completing the lease
    - MySQL converts TIMESTAMP values from the current time zone to UTC for storage, and back from UTC to the current
      time zone for retrieval. https://dev.mysql.com/doc/refman/8.0/en/datetime.html
        - Thus, for reading any timestamps from MySQL we convert the timezone from session (default) to UTC to always
          use epoch-millis in UTC locally
        - Similarly, for inserting/updating any timestamps we convert the timezone from UTC to session (as it will be
          (interpreted automatically as session time zone) and explicitly set all timestamp columns to avoid using the
          default auto-update/initialization values
    - We desire millisecond level precision and denote that with `(3)` for the TIMESTAMP types
   */
  private static final String CREATE_LEASE_ARBITER_TABLE_STATEMENT = "CREATE TABLE IF NOT EXISTS %s ("
      + "flow_group varchar(" + ServiceConfigKeys.MAX_FLOW_GROUP_LENGTH + ") NOT NULL, flow_name varchar("
      + ServiceConfigKeys.MAX_FLOW_GROUP_LENGTH + ") NOT NULL, " + "job_name varchar("
      + ServiceConfigKeys.MAX_JOB_NAME_LENGTH + ") NOT NULL, dag_action varchar(100) NOT NULL, "
      + "event_timestamp TIMESTAMP(3) NOT NULL, "
      + "lease_acquisition_timestamp TIMESTAMP(3) NULL, "
      + "PRIMARY KEY (flow_group,flow_name,job_name,dag_action))";
  // Deletes rows older than retention time period regardless of lease status as they should all be invalid or completed
  // since retention >> linger
  private static final String LEASE_ARBITER_TABLE_RETENTION_STATEMENT = "DELETE FROM %s WHERE event_timestamp < "
      + "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL %s * 1000 MICROSECOND)";
  private static final String CREATE_CONSTANTS_TABLE_STATEMENT = "CREATE TABLE IF NOT EXISTS %s "
      + "(primary_key INT, epsilon INT, linger INT, PRIMARY KEY (primary_key))";
  // Only insert epsilon and linger values from config if this table does not contain a pre-existing values already.
  private static final String UPSERT_CONSTANTS_TABLE_STATEMENT = "INSERT INTO %s (primary_key, epsilon, linger) "
      + "VALUES(1, ?, ?) ON DUPLICATE KEY UPDATE epsilon=VALUES(epsilon), linger=VALUES(linger)";
  protected static final String WHERE_CLAUSE_TO_MATCH_KEY = "WHERE flow_group=? AND flow_name=? AND job_name=? AND dag_action=?";
  protected static final String WHERE_CLAUSE_TO_MATCH_ROW = WHERE_CLAUSE_TO_MATCH_KEY
      + " AND event_timestamp=CONVERT_TZ(?, '+00:00', @@session.time_zone)"
      + " AND lease_acquisition_timestamp=CONVERT_TZ(?, '+00:00', @@session.time_zone)";
  protected static final String SELECT_AFTER_INSERT_STATEMENT = "SELECT "
      + "CONVERT_TZ(`event_timestamp`, @@session.time_zone, '+00:00') as utc_event_timestamp, "
      + "CONVERT_TZ(`lease_acquisition_timestamp`, @@session.time_zone, '+00:00') as utc_lease_acquisition_timestamp, "
      + "linger FROM %s, %s " + WHERE_CLAUSE_TO_MATCH_KEY;
  // Does a cross join between the two tables to have epsilon and linger values available. Returns the following values:
  // event_timestamp, lease_acquisition_timestamp, isWithinEpsilon (boolean if new event timestamp (current timestamp in
  // db) is within epsilon of event_timestamp in the table), leaseValidityStatus (1 if lease has not expired, 2 if
  // expired, 3 if column is NULL or no longer leasing)
  protected static final String GET_EVENT_INFO_STATEMENT = "SELECT "
      + "CONVERT_TZ(`event_timestamp`, @@session.time_zone, '+00:00') as utc_event_timestamp, "
      + "CONVERT_TZ(`lease_acquisition_timestamp`, @@session.time_zone, '+00:00') as utc_lease_acquisition_timestamp, "
      + "ABS(TIMESTAMPDIFF(microsecond, event_timestamp, CURRENT_TIMESTAMP(3))) / 1000 <= epsilon as is_within_epsilon, CASE "
      + "WHEN CURRENT_TIMESTAMP(3) < DATE_ADD(lease_acquisition_timestamp, INTERVAL linger*1000 MICROSECOND) then 1 "
      + "WHEN CURRENT_TIMESTAMP(3) >= DATE_ADD(lease_acquisition_timestamp, INTERVAL linger*1000 MICROSECOND) then 2 "
      + "ELSE 3 END as lease_validity_status, linger, "
      + "CONVERT_TZ(CURRENT_TIMESTAMP(3), @@session.time_zone, '+00:00') as utc_current_timestamp FROM %s, %s "
      + WHERE_CLAUSE_TO_MATCH_KEY;
  // Same as query above, except that isWithinEpsilon is True if the reminder event timestamp (provided by caller) is
  // OLDER than or equal to the db event_timestamp and within epsilon away from it.
  protected static final String GET_EVENT_INFO_STATEMENT_FOR_REMINDER = "SELECT "
      + "CONVERT_TZ(`event_timestamp`, @@session.time_zone, '+00:00') as utc_event_timestamp, "
      + "CONVERT_TZ(`lease_acquisition_timestamp`, @@session.time_zone, '+00:00') as utc_lease_acquisition_timestamp, "
      + "TIMESTAMPDIFF(microsecond, event_timestamp, CONVERT_TZ(?, '+00:00', @@session.time_zone)) / 1000 <= epsilon as is_within_epsilon, CASE "
      + "WHEN CURRENT_TIMESTAMP(3) < DATE_ADD(lease_acquisition_timestamp, INTERVAL linger*1000 MICROSECOND) then 1 "
      + "WHEN CURRENT_TIMESTAMP(3) >= DATE_ADD(lease_acquisition_timestamp, INTERVAL linger*1000 MICROSECOND) then 2 "
      + "ELSE 3 END as lease_validity_status, linger, "
      + "CONVERT_TZ(CURRENT_TIMESTAMP(3), @@session.time_zone, '+00:00') as utc_current_timestamp FROM %s, %s "
      + WHERE_CLAUSE_TO_MATCH_KEY;
  // Insert or update row to acquire lease if values have not changed since the previous read
  // Need to define three separate statements to handle cases where row does not exist or has null values to check
  protected static final String ACQUIRE_LEASE_IF_NEW_ROW_STATEMENT = "INSERT INTO %s (flow_group, flow_name, job_name, "
      + "dag_action, event_timestamp, lease_acquisition_timestamp) VALUES(?, ?, ?, ?, CURRENT_TIMESTAMP(3), "
      + "CURRENT_TIMESTAMP(3))";
  protected static final String CONDITIONALLY_ACQUIRE_LEASE_IF_FINISHED_LEASING_STATEMENT = "UPDATE %s "
      + "SET event_timestamp=CURRENT_TIMESTAMP(3), lease_acquisition_timestamp=CURRENT_TIMESTAMP(3) "
      + WHERE_CLAUSE_TO_MATCH_KEY + " AND event_timestamp=CONVERT_TZ(?, '+00:00', @@session.time_zone) AND "
      + "lease_acquisition_timestamp is NULL";
  protected static final String CONDITIONALLY_ACQUIRE_LEASE_IF_MATCHING_ALL_COLS_STATEMENT = "UPDATE %s "
      + "SET event_timestamp=CURRENT_TIMESTAMP(3), lease_acquisition_timestamp=CURRENT_TIMESTAMP(3) "
      + WHERE_CLAUSE_TO_MATCH_ROW;
  // Complete lease acquisition if values have not changed since lease was acquired
  protected static final String CONDITIONALLY_COMPLETE_LEASE_STATEMENT = "UPDATE %s SET "
      + "event_timestamp=event_timestamp, lease_acquisition_timestamp = NULL " + WHERE_CLAUSE_TO_MATCH_ROW;
  protected static final int MAX_RETRIES = 3;
  protected static final long MIN_INITIAL_DELAY_MILLIS = 20L;
  protected static final long DELAY_FOR_RETRY_RANGE_MILLIS = 200L;
  private static final ThreadLocal<Calendar> UTC_CAL =
      ThreadLocal.withInitial(() -> Calendar.getInstance(TimeZone.getTimeZone("UTC")));

  public MysqlMultiActiveLeaseArbiter(Config config) throws IOException {
    if (config.hasPath(ConfigurationKeys.MYSQL_LEASE_ARBITER_PREFIX)) {
      config = config.getConfig(ConfigurationKeys.MYSQL_LEASE_ARBITER_PREFIX).withFallback(config);
    } else {
      throw new IOException(String.format("Please specify the config for MysqlMultiActiveLeaseArbiter using prefix %s "
          + "before all properties", ConfigurationKeys.MYSQL_LEASE_ARBITER_PREFIX));
    }

    if (!config.hasPath(ConfigurationKeys.LEASE_DETERMINATION_STORE_DB_TABLE_KEY)
        || !config.hasPath(ConfigurationKeys.MULTI_ACTIVE_CONSTANTS_DB_TABLE_KEY)) {
      throw new RuntimeException(String.format("Table names %s and %s are required to be configured so multiple instances do not "
          + "utilize the same table name", ConfigurationKeys.LEASE_DETERMINATION_STORE_DB_TABLE_KEY,
          ConfigurationKeys.MULTI_ACTIVE_CONSTANTS_DB_TABLE_KEY));
    }
    this.leaseArbiterTableName = config.getString(ConfigurationKeys.LEASE_DETERMINATION_STORE_DB_TABLE_KEY);
    this.constantsTableName = config.getString(ConfigurationKeys.MULTI_ACTIVE_CONSTANTS_DB_TABLE_KEY);
    this.epsilonMillis = ConfigUtils.getInt(config, ConfigurationKeys.SCHEDULER_EVENT_EPSILON_MILLIS_KEY,
        ConfigurationKeys.DEFAULT_SCHEDULER_EVENT_EPSILON_MILLIS);
    this.lingerMillis = ConfigUtils.getInt(config, ConfigurationKeys.SCHEDULER_EVENT_LINGER_MILLIS_KEY,
        ConfigurationKeys.DEFAULT_SCHEDULER_EVENT_LINGER_MILLIS);
    this.retentionPeriodMillis = ConfigUtils.getLong(config, ConfigurationKeys.LEASE_DETERMINATION_TABLE_RETENTION_PERIOD_MILLIS_KEY,
        ConfigurationKeys.DEFAULT_LEASE_DETERMINATION_TABLE_RETENTION_PERIOD_MILLIS);
    this.thisTableRetentionStatement = String.format(LEASE_ARBITER_TABLE_RETENTION_STATEMENT, this.leaseArbiterTableName,
        retentionPeriodMillis);
    this.thisTableGetInfoStatement = String.format(GET_EVENT_INFO_STATEMENT, this.leaseArbiterTableName,
        this.constantsTableName);
    this.thisTableGetInfoStatementForReminder = String.format(GET_EVENT_INFO_STATEMENT_FOR_REMINDER,
        this.leaseArbiterTableName, this.constantsTableName);
    this.thisTableSelectAfterInsertStatement = String.format(SELECT_AFTER_INSERT_STATEMENT, this.leaseArbiterTableName,
        this.constantsTableName);
    this.thisTableAcquireLeaseIfMatchingAllStatement =
        String.format(CONDITIONALLY_ACQUIRE_LEASE_IF_MATCHING_ALL_COLS_STATEMENT, this.leaseArbiterTableName);
    this.thisTableAcquireLeaseIfFinishedStatement =
        String.format(CONDITIONALLY_ACQUIRE_LEASE_IF_FINISHED_LEASING_STATEMENT, this.leaseArbiterTableName);
    this.dataSource = MysqlDataSourceFactory.get(config, SharedResourcesBrokerFactory.getImplicitBroker());
    this.dbStatementExecutor = new DBStatementExecutor(this.dataSource, log);
    String createArbiterStatement = String.format(
        CREATE_LEASE_ARBITER_TABLE_STATEMENT, leaseArbiterTableName);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement createStatement = connection.prepareStatement(createArbiterStatement)) {
      createStatement.executeUpdate();
      connection.commit();
    } catch (SQLException e) {
      throw new IOException("Table creation failure for " + leaseArbiterTableName, e);
    }
    initializeConstantsTable();

    // Periodically deletes all rows in the table with event_timestamp older than the retention period defined by config.
    dbStatementExecutor.repeatSqlCommandExecutionAtInterval(thisTableRetentionStatement, 4, TimeUnit.HOURS);

    log.info("MysqlMultiActiveLeaseArbiter initialized - {}", this.leaseArbiterTableName);
  }

  // Initialize Constants table if needed and insert row into it if one does not exist
  private void initializeConstantsTable() throws IOException {
    String createConstantsStatement = String.format(CREATE_CONSTANTS_TABLE_STATEMENT, this.constantsTableName);
    dbStatementExecutor.withPreparedStatement(createConstantsStatement, createStatement -> createStatement.executeUpdate(),
        true);

    String insertConstantsStatement = String.format(UPSERT_CONSTANTS_TABLE_STATEMENT, this.constantsTableName);
    dbStatementExecutor.withPreparedStatement(insertConstantsStatement, insertStatement -> {
      int i = 0;
      insertStatement.setInt(++i, epsilonMillis);
      insertStatement.setInt(++i, lingerMillis);
      return insertStatement.executeUpdate();
    }, true);
  }

  @Override
  public LeaseAttemptStatus tryAcquireLease(DagActionStore.LeaseParams leaseParams, boolean adoptConsensusFlowExecutionId) throws IOException {
    log.info("Multi-active arbitration for {}", contextualizeLeasing(leaseParams));
    LeaseAttemptStatus leaseAttemptStatus = doTryAcquireLease(leaseParams, adoptConsensusFlowExecutionId);
    // IMPORTANT: in case the flowExecutionId/eventTime was updated within, when available, prefer logging the `getConsensusLeaseParams()` over the original
    log.info("Multi-active lease status [{}] for {}", leaseAttemptStatus.getClass().getSimpleName(),
        contextualizeLeasing(Optional.ofNullable(leaseAttemptStatus.getConsensusLeaseParams()).orElse(leaseParams)));
    return leaseAttemptStatus;
  }

  /** helper method to bound multiple `return`s, so easier for the caller to uniformly log every kind of result */
  private LeaseAttemptStatus doTryAcquireLease(DagActionStore.LeaseParams leaseParams, boolean adoptConsensusFlowExecutionId) throws IOException {
    // Query lease arbiter table about this dag action
    Optional<GetEventInfoResult> getResult = getExistingEventInfo(leaseParams);

    try {
      if (!getResult.isPresent()) {
        log.debug("tryAcquireLease for {} - CASE 1: DagAction has no row yet - create now", contextualizeLeasing(leaseParams));
        int numRowsUpdated = attemptLeaseIfNewRow(leaseParams.getDagAction(),
            ExponentialBackoff.builder().maxRetries(MAX_RETRIES)
                .initialDelay(MIN_INITIAL_DELAY_MILLIS + (long) (Math.random() * DELAY_FOR_RETRY_RANGE_MILLIS))
                .build());
       return evaluateStatusAfterLeaseAttempt(numRowsUpdated, leaseParams, Optional.empty(),
           adoptConsensusFlowExecutionId);
      }

      // Extract values from result set
      Timestamp dbEventTimestamp = getResult.get().getDbEventTimestamp();
      Timestamp dbLeaseAcquisitionTimestamp = getResult.get().getDbLeaseAcquisitionTimestamp();
      boolean isWithinEpsilon = getResult.get().isWithinEpsilon();
      int leaseValidityStatus = getResult.get().getLeaseValidityStatus();
      // Used to calculate minimum amount of time until a participant should check whether a lease expired
      int dbLinger = getResult.get().getDbLinger();
      Timestamp dbCurrentTimestamp = getResult.get().getDbCurrentTimestamp();

      // stop early with reminder events when the reminder's eventTimeMillis is older than that of the current DB event; DB laundering should
      // guarantee that the current DB event is truly a distinct newer event (vs. clock drift) and thus should have separate reminders of its own
      if (leaseParams.isReminder()) {
        if (leaseParams.getEventTimeMillis() < dbEventTimestamp.getTime()) {
          log.info("tryAcquireLease for {} (dbEventTimeMillis: {}) - Newer DB time, so discarding out-of-date reminder",
              contextualizeLeasing(leaseParams), dbEventTimestamp);
          return new LeaseAttemptStatus.NoLongerLeasingStatus();
        }
        if (leaseParams.getEventTimeMillis() > dbEventTimestamp.getTime()) {
          // TODO: emit metric here to capture this unexpected behavior
          log.warn("tryAcquireLease for {} (dbEventTimeMillis: {}) - Severe constraint violation: DB time OLDER than reminder event, when DB laundering "
              + "ought to ensure monotonically increasing (laundered) event times.", contextualizeLeasing(leaseParams), dbEventTimestamp.getTime());
        }
        if (leaseParams.getEventTimeMillis() == dbEventTimestamp.getTime()) {
          log.debug("tryAcquireLease for {} (dbEventTimeMillis: {}) - DB time matches reminder", contextualizeLeasing(leaseParams), dbEventTimestamp);
        }
      }

      // TODO: check whether reminder event before replacing flowExecutionId
      if (adoptConsensusFlowExecutionId) {
        log.info("Multi-active will use DB time ({}) to launder {}", dbCurrentTimestamp.getTime(), contextualizeLeasing(leaseParams));
      }
      /* Note that we use `adoptConsensusFlowExecutionId` parameter's value to determine whether we should use the db
      laundered event timestamp as the flowExecutionId or maintain the original one
       */

      // Lease is valid
      if (leaseValidityStatus == 1) {
        if (isWithinEpsilon) {
         DagActionStore.DagAction updatedDagAction =
              adoptConsensusFlowExecutionId ? leaseParams.updateDagActionFlowExecutionId(dbEventTimestamp.getTime()) : leaseParams.getDagAction();
         DagActionStore.LeaseParams updatedLeaseParams = new DagActionStore.LeaseParams(updatedDagAction,
             dbEventTimestamp.getTime());
          log.debug("tryAcquireLease for {} - CASE 2: Same event, lease is valid", contextualizeLeasing(updatedLeaseParams));
          // Utilize db timestamp for reminder
          return new LeaseAttemptStatus.LeasedToAnotherStatus(updatedLeaseParams,
              dbLeaseAcquisitionTimestamp.getTime() + dbLinger - dbCurrentTimestamp.getTime());
        }
        DagActionStore.DagAction updatedDagAction =
            adoptConsensusFlowExecutionId ? leaseParams.getDagAction().updateFlowExecutionId(dbCurrentTimestamp.getTime()) : leaseParams.getDagAction();
        DagActionStore.LeaseParams updatedLeaseParams = new DagActionStore.LeaseParams(updatedDagAction,
            dbCurrentTimestamp.getTime());
        log.debug("tryAcquireLease for {} - CASE 3: Distinct event, lease is valid", contextualizeLeasing(updatedLeaseParams));
        // Utilize db lease acquisition timestamp for wait time and currentTimestamp as the new eventTimestamp
        return new LeaseAttemptStatus.LeasedToAnotherStatus(updatedLeaseParams,
            dbLeaseAcquisitionTimestamp.getTime() + dbLinger  - dbCurrentTimestamp.getTime());
      } // Lease is invalid
      else if (leaseValidityStatus == 2) {
        log.debug("tryAcquireLease for {} (DB current time: {}) - CASE 4: Lease out-of-date (regardless of "
            + "whether same or distinct event)", contextualizeLeasing(leaseParams), dbCurrentTimestamp.getTime());
        if (isWithinEpsilon && !leaseParams.isReminder) {
          log.warn("Lease should not be out-of-date for the same trigger event, if epsilon << linger for {} "
                  + "(DB eventTimestamp: {}; DB leaseAcquisitionTimestamp: {}; DB linger: {})",
              contextualizeLeasing(leaseParams), dbEventTimestamp, dbLeaseAcquisitionTimestamp, dbLinger);
        }
        // Use our event to acquire lease, check for previous DB eventTimestamp and leaseAcquisitionTimestamp
        int numRowsUpdated = attemptLeaseIfExistingRow(thisTableAcquireLeaseIfMatchingAllStatement,
            leaseParams.getDagAction(), true,true, dbEventTimestamp,
            dbLeaseAcquisitionTimestamp);
        return evaluateStatusAfterLeaseAttempt(numRowsUpdated, leaseParams, Optional.of(dbCurrentTimestamp),
            adoptConsensusFlowExecutionId);
      } // No longer leasing this event
        if (isWithinEpsilon) {
          log.debug("tryAcquireLease for {} (DB current time: {}) - CASE 5: Same event, no longer leasing event in DB",
              contextualizeLeasing(leaseParams), dbCurrentTimestamp.getTime());
          return new LeaseAttemptStatus.NoLongerLeasingStatus();
        }
        log.debug("tryAcquireLease for {} (DB current time: {}) - CASE 6: Distinct event, no longer leasing event in DB",
            contextualizeLeasing(leaseParams), dbCurrentTimestamp.getTime());
        // Use our event to acquire lease, check for previous DB eventTimestamp and NULL leaseAcquisitionTimestamp
        int numRowsUpdated = attemptLeaseIfExistingRow(thisTableAcquireLeaseIfFinishedStatement,
            leaseParams.getDagAction(), true, false, dbEventTimestamp,
            null);
        return evaluateStatusAfterLeaseAttempt(numRowsUpdated, leaseParams, Optional.of(dbCurrentTimestamp),
            adoptConsensusFlowExecutionId);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean existsSimilarLeaseWithinConsolidationPeriod(DagActionStore.LeaseParams leaseParams) throws IOException {
    Optional<GetEventInfoResult> infoResult = getExistingEventInfo(leaseParams);
    return infoResult.isPresent() ? infoResult.get().isWithinEpsilon() : false;
  }

  /**
   * Checks leaseArbiterTable for an existing entry for this dag action and event time
   */
  protected Optional<GetEventInfoResult> getExistingEventInfo(DagActionStore.LeaseParams leaseParams)
      throws IOException {
    DagActionStore.DagAction dagAction = leaseParams.getDagAction();
    return dbStatementExecutor.withPreparedStatement(
        leaseParams.isReminder ? thisTableGetInfoStatementForReminder : thisTableGetInfoStatement,
        getInfoStatement -> {
          int i = 0;
          if (leaseParams.isReminder) {
            getInfoStatement.setTimestamp(++i, new Timestamp(leaseParams.getEventTimeMillis()), UTC_CAL.get());
          }
          getInfoStatement.setString(++i, dagAction.getFlowGroup());
          getInfoStatement.setString(++i, dagAction.getFlowName());
          getInfoStatement.setString(++i, dagAction.getJobName());
          getInfoStatement.setString(++i, dagAction.getDagActionType().toString());
          ResultSet resultSet = getInfoStatement.executeQuery();
          try {
            if (!resultSet.next()) {
              return Optional.<GetEventInfoResult>empty();
            }
            return Optional.of(createGetInfoResult(resultSet));
          } finally {
            if (resultSet !=  null) {
              resultSet.close();
            }
          }
        }, true);
  }

  protected GetEventInfoResult createGetInfoResult(ResultSet resultSet) throws IOException {
    try {
      // Extract values from result set
      Timestamp dbEventTimestamp = resultSet.getTimestamp("utc_event_timestamp", UTC_CAL.get());
      Timestamp dbLeaseAcquisitionTimestamp = resultSet.getTimestamp("utc_lease_acquisition_timestamp",
          UTC_CAL.get());
      boolean withinEpsilon = resultSet.getBoolean("is_within_epsilon");
      int leaseValidityStatus = resultSet.getInt("lease_validity_status");
      int dbLinger = resultSet.getInt("linger");
      Timestamp dbCurrentTimestamp = resultSet.getTimestamp("utc_current_timestamp", UTC_CAL.get());
      return new GetEventInfoResult(dbEventTimestamp, dbLeaseAcquisitionTimestamp, withinEpsilon, leaseValidityStatus,
          dbLinger, dbCurrentTimestamp);
    } catch (SQLException e) {
      throw new IOException(e);
    } finally {
      if (resultSet != null) {
        try {
          resultSet.close();
        } catch (SQLException e) {
          throw new IOException(e);
        }
      }
    }
  }

  /**
   * Called by participant to try to acquire lease for a dag action that does not have an attempt in progress or in
   * near past for it.
   * @return int corresponding to number of rows updated by INSERT statement to acquire lease
   */
  protected int attemptLeaseIfNewRow(DagActionStore.DagAction dagAction, ExponentialBackoff exponentialBackoff) throws IOException {
    String formattedAcquireLeaseNewRowStatement =
        String.format(ACQUIRE_LEASE_IF_NEW_ROW_STATEMENT, this.leaseArbiterTableName);
    return dbStatementExecutor.withPreparedStatement(formattedAcquireLeaseNewRowStatement,
        insertStatement -> {
          completeInsertPreparedStatement(insertStatement, dagAction);
          try {
            return insertStatement.executeUpdate();
          } catch (SQLTransientException e) {
            try {
              if (exponentialBackoff.awaitNextRetryIfAvailable()) {
                return attemptLeaseIfNewRow(dagAction, exponentialBackoff);
              }
            } catch (InterruptedException e2) {
              throw new IOException(e2);
            }
            throw e;
          }
          catch (SQLIntegrityConstraintViolationException e) {
            if (!e.getMessage().contains("Duplicate entry")) {
              throw e;
            }
            return 0;
          }
        }, true);
  }

  /**
   * Called by participant to try to acquire lease for a dag action that has an existing, completed, or expired lease
   * attempt for the dag action in the table.
   * @return int corresponding to number of rows updated by INSERT statement to acquire lease
   */
  protected int attemptLeaseIfExistingRow(String acquireLeaseStatement, DagActionStore.DagAction dagAction,
      boolean needEventTimeCheck, boolean needLeaseAcquisition, Timestamp dbEventTimestamp,
      Timestamp dbLeaseAcquisitionTimestamp) throws IOException {
    return dbStatementExecutor.withPreparedStatement(acquireLeaseStatement,
        insertStatement -> {
          completeUpdatePreparedStatement(insertStatement, dagAction, needEventTimeCheck, needLeaseAcquisition,
              dbEventTimestamp, dbLeaseAcquisitionTimestamp);
          return insertStatement.executeUpdate();
        }, true);
  }

  /**
   * Checks leaseArbiter table for a row corresponding to this dag action to determine if the lease acquisition attempt
   * was successful or not.
   */
  protected SelectInfoResult getRowInfo(DagActionStore.DagAction dagAction) throws IOException {
    return dbStatementExecutor.withPreparedStatement(thisTableSelectAfterInsertStatement,
        selectStatement -> {
          completeWhereClauseMatchingKeyPreparedStatement(selectStatement, dagAction);
          ResultSet resultSet = selectStatement.executeQuery();
          try {
            return createSelectInfoResult(resultSet);
          } finally {
            if (resultSet !=  null) {
              resultSet.close();
            }
          }
        }, true);
  }
  protected static SelectInfoResult createSelectInfoResult(ResultSet resultSet) throws IOException {
      try {
        if (!resultSet.next()) {
          throw new IOException("Expected resultSet containing row information for the lease that was attempted but "
              + "received nothing.");
        }
        if (resultSet.getTimestamp("utc_event_timestamp", UTC_CAL.get()) == null) {
          throw new IOException("event_timestamp should never be null (it is always set to current timestamp)");
        }
        long eventTimeMillis = resultSet.getTimestamp("utc_event_timestamp", UTC_CAL.get()).getTime();
        // Lease acquisition timestamp is null if another participant has completed the lease
        Optional<Long> leaseAcquisitionTimeMillis =
            resultSet.getTimestamp("utc_lease_acquisition_timestamp", UTC_CAL.get()) == null ? Optional.empty() :
            Optional.of(resultSet.getTimestamp("utc_lease_acquisition_timestamp", UTC_CAL.get()).getTime());
        int dbLinger = resultSet.getInt("linger");
        return new SelectInfoResult(eventTimeMillis, leaseAcquisitionTimeMillis, dbLinger);
      } catch (SQLException e) {
        throw new IOException(e);
      } finally {
        if (resultSet != null) {
          try {
            resultSet.close();
          } catch (SQLException e) {
            throw new IOException(e);
          }
        }
      }
  }

  /**
   * Parse result of attempted insert/update to obtain a lease for a
   * {@linkleaseObject.getDagAction()Store.DagAction} event by selecting values corresponding to that
   * event from the table to return the corresponding status based on successful insert/update or not.
   * @throws SQLException
   * @throws IOException
   */
  protected LeaseAttemptStatus evaluateStatusAfterLeaseAttempt(int numRowsUpdated, DagActionStore.LeaseParams leaseParams,
      Optional<Timestamp> dbCurrentTimestamp, boolean adoptConsensusFlowExecutionId)
      throws SQLException, IOException {
    // Fetch values in row after attempted insert
    SelectInfoResult selectInfoResult = getRowInfo(leaseParams.dagAction);
    // Another participant won the lease in between
    if (!selectInfoResult.getLeaseAcquisitionTimeMillis().isPresent()) {
      return new LeaseAttemptStatus.NoLongerLeasingStatus();
    }
    DagActionStore.DagAction updatedDagAction =
        adoptConsensusFlowExecutionId ? leaseParams.updateDagActionFlowExecutionId(selectInfoResult.eventTimeMillis) : leaseParams.dagAction;
    DagActionStore.LeaseParams consensusLeaseParams =
        new DagActionStore.LeaseParams(updatedDagAction, selectInfoResult.getEventTimeMillis());
    // If no db current timestamp is present, then use the full db linger value for duration
    long minimumLingerDurationMillis = dbCurrentTimestamp.isPresent() ?
        selectInfoResult.getLeaseAcquisitionTimeMillis().get() + selectInfoResult.getDbLinger()
            - dbCurrentTimestamp.get().getTime() : selectInfoResult.getDbLinger();
    if (numRowsUpdated == 1) {
      log.info("Successfully leased {}", contextualizeLeasing(consensusLeaseParams));
      return new LeaseAttemptStatus.LeaseObtainedStatus(consensusLeaseParams,
          selectInfoResult.getLeaseAcquisitionTimeMillis().get(), minimumLingerDurationMillis, this);
    } else {  // Another participant acquired lease in between
      log.info("Another acquired lease faster {} - num rows updated: {}", contextualizeLeasing(consensusLeaseParams),
          numRowsUpdated);
      return new LeaseAttemptStatus.LeasedToAnotherStatus(consensusLeaseParams, minimumLingerDurationMillis);
    }
  }

  /**
   * Complete the INSERT statement for a new dag action lease where the dag action is not present in the table
   * @param statement
   * @param dagAction
   * @throws SQLException
   */
  protected static void completeInsertPreparedStatement(PreparedStatement statement,
      DagActionStore.DagAction dagAction) throws SQLException {
    int i = 0;
    // Values to set in new row
    statement.setString(++i, dagAction.getFlowGroup());
    statement.setString(++i, dagAction.getFlowName());
    statement.setString(++i, dagAction.getJobName());
    statement.setString(++i, dagAction.getDagActionType().toString());
  }

  /**
   * Complete the WHERE clause to match a dag action in a select statement
   * @param statement
   * @param dagAction
   * @throws SQLException
   */
  protected static void completeWhereClauseMatchingKeyPreparedStatement(PreparedStatement statement,
     DagActionStore.DagAction dagAction) throws SQLException {
    int i = 0;
    statement.setString(++i, dagAction.getFlowGroup());
    statement.setString(++i, dagAction.getFlowName());
    statement.setString(++i, dagAction.getJobName());
    statement.setString(++i, dagAction.getDagActionType().toString());
  }

  /**
   * Complete the UPDATE prepared statements for a dag action that already exists in the table that needs to be
   * updated.
   * @param statement
   * @param dagAction
   * @param needEventTimeCheck true if need to compare `originalEventTimestamp` with db event_timestamp
   * @param needLeaseAcquisitionTimeCheck true if need to compare `originalLeaseAcquisitionTimestamp` with db one
   * @param originalEventTimestamp value to compare to db one, null if not needed
   * @param originalLeaseAcquisitionTimestamp value to compare to db one, null if not needed
   * @throws SQLException
   */
  protected static void completeUpdatePreparedStatement(PreparedStatement statement, DagActionStore.DagAction dagAction,
      boolean needEventTimeCheck, boolean needLeaseAcquisitionTimeCheck,
      Timestamp originalEventTimestamp, Timestamp originalLeaseAcquisitionTimestamp) throws SQLException {
    int i = 0;
    // Values to check if existing row matches previous read
    statement.setString(++i, dagAction.getFlowGroup());
    statement.setString(++i, dagAction.getFlowName());
    statement.setString(++i, dagAction.getJobName());
    statement.setString(++i, dagAction.getDagActionType().toString());
    // Values that may be needed depending on the insert statement
    if (needEventTimeCheck) {
      statement.setTimestamp(++i, originalEventTimestamp, UTC_CAL.get());
    }
    if (needLeaseAcquisitionTimeCheck) {
      statement.setTimestamp(++i, originalLeaseAcquisitionTimestamp, UTC_CAL.get());
    }
  }

  @Override
  public boolean recordLeaseSuccess(LeaseAttemptStatus.LeaseObtainedStatus status)
      throws IOException {
    DagActionStore.LeaseParams leaseParams = status.getConsensusLeaseParams();
    return dbStatementExecutor.withPreparedStatement(String.format(CONDITIONALLY_COMPLETE_LEASE_STATEMENT, leaseArbiterTableName),
        updateStatement -> {
          int i = 0;
          DagActionStore.DagAction dagAction = leaseParams.getDagAction();
          updateStatement.setString(++i, dagAction.getFlowGroup());
          updateStatement.setString(++i, dagAction.getFlowName());
          updateStatement.setString(++i, dagAction.getJobName());
          updateStatement.setString(++i, dagAction.getDagActionType().toString());
          updateStatement.setTimestamp(++i, new Timestamp(leaseParams.getEventTimeMillis()), UTC_CAL.get());
          updateStatement.setTimestamp(++i, new Timestamp(status.getLeaseAcquisitionTimestamp()), UTC_CAL.get());
          int numRowsUpdated = updateStatement.executeUpdate();
          if (numRowsUpdated == 0) {
            log.info("Multi-active lease completion FAILED {} - lease expired or event cleaned up before this attempt", contextualizeLeasing(leaseParams));
            return false;
          } else if (numRowsUpdated == 1) {
            log.info("Multi-active lease COMPLETED {} - no longer leasing this event", contextualizeLeasing(leaseParams));
            return true;
          }
          throw new IOException(String.format("Multi-active lease completion ERROR %s - updated too many rows", contextualizeLeasing(leaseParams)));
        }, true);
  }

  /** @return {@link DagActionStore.LeaseParams#toString}, qualified by {@link #leaseArbiterTableName}, for clearer logging */
  protected String contextualizeLeasing(DagActionStore.LeaseParams leaseParams) {
    return String.format("[%s] %s", this.leaseArbiterTableName, leaseParams);
  }

  /**
   * DTO for arbiter's current lease state for a leaseObject.getDagAction()Event
  */
  @Data
  static class GetEventInfoResult {
    private final Timestamp dbEventTimestamp;
    private final Timestamp dbLeaseAcquisitionTimestamp;
    private final boolean withinEpsilon;
    private final int leaseValidityStatus;
    private final int dbLinger;
    private final Timestamp dbCurrentTimestamp;
  }

  /**
   DTO for result of SELECT query used to determine status of lease acquisition attempt
  */
  @Data
  static class SelectInfoResult {
    private final long eventTimeMillis;
    private final Optional<Long> leaseAcquisitionTimeMillis;
    private final int dbLinger;
  }
}
