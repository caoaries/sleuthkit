/*
 * Autopsy Forensic Browser
 *
 * Copyright 2013-18 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.datamodel;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.joda.time.DateTimeZone;
import org.joda.time.Interval;
import org.joda.time.Period;
import org.sleuthkit.datamodel.timeline.BaseTypes;
import org.sleuthkit.datamodel.timeline.CombinedEvent;
import org.sleuthkit.datamodel.timeline.DescriptionLoD;
import org.sleuthkit.datamodel.timeline.EventCluster;
import org.sleuthkit.datamodel.timeline.EventStripe;
import org.sleuthkit.datamodel.timeline.EventType;
import org.sleuthkit.datamodel.timeline.EventTypeZoomLevel;
import org.sleuthkit.datamodel.timeline.RangeDivisionInfo;
import org.sleuthkit.datamodel.timeline.RootEventType;
import org.sleuthkit.datamodel.timeline.SingleEvent;
import org.sleuthkit.datamodel.timeline.TimeUnits;
import org.sleuthkit.datamodel.timeline.ZoomParams;
import org.sleuthkit.datamodel.timeline.filters.AbstractFilter;
import org.sleuthkit.datamodel.timeline.filters.DataSourceFilter;
import org.sleuthkit.datamodel.timeline.filters.DataSourcesFilter;
import org.sleuthkit.datamodel.timeline.filters.DescriptionFilter;
import org.sleuthkit.datamodel.timeline.filters.Filter;
import org.sleuthkit.datamodel.timeline.filters.HashHitsFilter;
import org.sleuthkit.datamodel.timeline.filters.HashSetFilter;
import org.sleuthkit.datamodel.timeline.filters.HideKnownFilter;
import org.sleuthkit.datamodel.timeline.filters.IntersectionFilter;
import org.sleuthkit.datamodel.timeline.filters.RootFilter;
import org.sleuthkit.datamodel.timeline.filters.TagNameFilter;
import org.sleuthkit.datamodel.timeline.filters.TagsFilter;
import org.sleuthkit.datamodel.timeline.filters.TextFilter;
import org.sleuthkit.datamodel.timeline.filters.TypeFilter;
import org.sleuthkit.datamodel.timeline.filters.UnionFilter;

import org.sleuthkit.datamodel.SleuthkitCase.CaseDbConnection;

/**
 * Provides access to the Timeline features of SleuthkitCase
 */
public class TimelineManager {

	private static final java.util.logging.Logger LOGGER = Logger.getLogger(TimelineManager.class.getName());

	private final Set<PreparedStatement> preparedStatements = new HashSet<>();

	private final SleuthkitCase sleuthkitCase;
	private final String primaryKeyType;
	private final String csvFunction;

	public String csvAggFunction(String args) {
		return csvAggFunction(args, ",");
	}

	public String csvAggFunction(String args, String seperator) {
		return csvFunction + "(" + args + ", '" + seperator + "')";
	}

	TimelineManager(SleuthkitCase tskCase) throws TskCoreException {
		sleuthkitCase = tskCase;
		primaryKeyType = sleuthkitCase.getDatabaseType() == TskData.DbType.POSTGRESQL ? "BIGSERIAL" : "INTEGER";
		csvFunction = sleuthkitCase.getDatabaseType() == TskData.DbType.POSTGRESQL ? "string_agg" : "group_concat";

		initializeDB();
	}

	public Interval getSpanningInterval(Collection<Long> eventIDs) throws TskCoreException {
		if (eventIDs.isEmpty()) {
			return null;
		}
		final String query = "SELECT Min(time), Max(time) FROM events WHERE event_id IN (" + StringUtils.joinAsStrings(eventIDs, ", ") + ")";
		sleuthkitCase.acquireSingleUserCaseReadLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stmt = con.createStatement();
				ResultSet rs = stmt.executeQuery(query);) {
			while (rs.next()) {
				return new Interval(rs.getLong("Min(time)") * 1000, (rs.getLong("Max(time)") + 1) * 1000, DateTimeZone.UTC); // NON-NLS
			}

		} catch (SQLException ex) {
			throw new TskCoreException("Error executing get spanning interval query: " + query, ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}
		return null;
	}

	public SleuthkitCase.CaseDbTransaction beginTransaction() throws TskCoreException {
		return sleuthkitCase.beginTransaction();
	}

	public void commitTransaction(SleuthkitCase.CaseDbTransaction tr) throws TskCoreException {
		tr.commit();
	}

	/**
	 * @return the total number of events in the database or, -1 if there is an
	 *         error.
	 */
	public int countAllEvents() throws TskCoreException {
		sleuthkitCase.acquireSingleUserCaseReadLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement statement = con.createStatement();
				ResultSet rs = statement.executeQuery(PREPARED_STATEMENT.COUNT_ALL_EVENTS.getSQL());) {
			while (rs.next()) {
				return rs.getInt("count"); // NON-NLS
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error counting all events", ex); //NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}
		return -1;
	}

	/**
	 * get the count of all events that fit the given zoom params organized by
	 * the EvenType of the level spcified in the ZoomParams
	 *
	 * @param params the params that control what events to count and how to
	 *               organize the returned map
	 *
	 * @return a map from event type( of the requested level) to event counts
	 */
	public Map<EventType, Long> countEventsByType(ZoomParams params) {
		if (params.getTimeRange() != null) {
			return countEventsByType(params.getTimeRange().getStartMillis() / 1000,
					params.getTimeRange().getEndMillis() / 1000,
					params.getFilter(), params.getTypeZoomLevel());
		} else {
			return Collections.emptyMap();
		}
	}

	/**
	 * get a count of tagnames applied to the given event ids as a map from
	 * tagname displayname to count of tag applications
	 *
	 * @param eventIDsWithTags the event ids to get the tag counts map for
	 *
	 * @return a map from tagname displayname to count of applications
	 */
	public Map<String, Long> getTagCountsByTagName(Set<Long> eventIDsWithTags) throws TskCoreException {
		sleuthkitCase.acquireSingleUserCaseReadLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement statement = con.createStatement();
				ResultSet resultSet = statement.executeQuery("SELECT tag_name_display_name, COUNT(DISTINCT tag_id) AS count FROM tags" //NON-NLS
						+ " WHERE event_id IN (" + StringUtils.joinAsStrings(eventIDsWithTags, ", ") + ")" //NON-NLS
						+ " GROUP BY tag_name_id" //NON-NLS
						+ " ORDER BY tag_name_display_name");) {
			HashMap<String, Long> counts = new HashMap<>();
			while (resultSet.next()) {
				counts.put(resultSet.getString("tag_name_display_name"), resultSet.getLong("count")); //NON-NLS
			}
			return counts;
		} catch (SQLException ex) {
			throw new TskCoreException("Failed to get tag counts by tag name.", ex); //NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}
	}

	/**
	 * drop the tables from this database and recreate them in order to start
	 * over.
	 *
	 * @throws org.sleuthkit.datamodel.TskCoreException
	 */
	public void reInitializeDB() throws TskCoreException {
		sleuthkitCase.acquireSingleUserCaseWriteLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement statement = con.createStatement();) {
			statement.execute(PREPARED_STATEMENT.DROP_DB_INFO_TABLE.getSQL());
			statement.execute(PREPARED_STATEMENT.DROP_TAGS_TABLE.getSQL());
			statement.execute(PREPARED_STATEMENT.DROP_HASH_SET_HITS_TABLE.getSQL());
			statement.execute(PREPARED_STATEMENT.DROP_HASH_SETS_TABLE.getSQL());
			statement.execute(PREPARED_STATEMENT.DROP_EVENTS_TABLE.getSQL());

			initializeDB();
		} catch (SQLException ex) {
			throw new TskCoreException("Error dropping old tables", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseWriteLock();
		}
	}

	/**
	 * drop only the tags table and rebuild it incase the tags have changed
	 * while TL was not listening,
	 */
	public void reInitializeTags() throws TskCoreException {
		sleuthkitCase.acquireSingleUserCaseWriteLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement statement = con.createStatement();) {
			statement.execute(PREPARED_STATEMENT.DROP_TAGS_TABLE.getSQL());
			initializeTagsTable();
		} catch (SQLException ex) {
			throw new TskCoreException("could not drop old tags table", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseWriteLock();
		}
	}

	public Interval getBoundingEventsInterval(Interval timeRange, RootFilter filter, DateTimeZone tz) throws TskCoreException {
		long start = timeRange.getStartMillis() / 1000;
		long end = timeRange.getEndMillis() / 1000;
		final String sqlWhere = getSQLWhere(filter);
		sleuthkitCase.acquireSingleUserCaseReadLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stmt = con.createStatement(); //can't use prepared statement because of complex where clause
				ResultSet rs = stmt.executeQuery(" SELECT (SELECT Max(time) FROM events " + useHashHitTablesHelper(filter) + useTagTablesHelper(filter) + " WHERE time <=" + start + " AND " + sqlWhere + ") AS start," //NON-NLS
						+ "(SELECT Min(time)  FROM events" + useHashHitTablesHelper(filter) + useTagTablesHelper(filter) + " WHERE time >= " + end + " AND " + sqlWhere + ") AS end");) {

			while (rs.next()) {

				long start2 = rs.getLong("start"); // NON-NLS
				long end2 = rs.getLong("end"); // NON-NLS

				if (end2 == 0) {
					end2 = getMaxTime();
				}
				return new Interval(start2 * 1000, (end2 + 1) * 1000, tz);
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Failed to get MIN time.", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}
		return null;
	}

	public SingleEvent getEventById(Long eventID) throws TskCoreException {
		sleuthkitCase.acquireSingleUserCaseReadLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				PreparedStatement stmt = con.prepareStatement(PREPARED_STATEMENT.GET_EVENT_BY_ID.getSQL(), 0);) {
			stmt.setLong(1, eventID);
			try (ResultSet rs = stmt.executeQuery();) {
				while (rs.next()) {
					return constructTimeLineEvent(rs);
				}
			}
		} catch (SQLException sqlEx) {
			throw new TskCoreException("exception while querying for event with id = " + eventID, sqlEx); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}
		return null;
	}

	/**
	 * Get the IDs of all the events within the given time range that pass the
	 * given filter.
	 *
	 * @param timeRange The Interval that all returned events must be within.
	 * @param filter    The Filter that all returned events must pass.
	 *
	 * @return A List of event ids, sorted by timestamp of the corresponding
	 *         event..
	 */
	public List<Long> getEventIDs(Interval timeRange, RootFilter filter) throws TskCoreException {
		Long startTime = timeRange.getStartMillis() / 1000;
		Long endTime = timeRange.getEndMillis() / 1000;

		if (Objects.equals(startTime, endTime)) {
			endTime++; //make sure end is at least 1 millisecond after start
		}

		ArrayList<Long> resultIDs = new ArrayList<Long>();

		sleuthkitCase.acquireSingleUserCaseReadLock();
		final String query = "SELECT events.event_id AS event_id FROM events" + useHashHitTablesHelper(filter) + useTagTablesHelper(filter)
				+ " WHERE time >=  " + startTime + " AND time <" + endTime + " AND " + getSQLWhere(filter) + " ORDER BY time ASC"; // NON-NLS
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stmt = con.createStatement();
				ResultSet rs = stmt.executeQuery(query);) {
			while (rs.next()) {
				resultIDs.add(rs.getLong("event_id")); //NON-NLS
			}

		} catch (SQLException sqlEx) {
			throw new TskCoreException("failed to execute query for event ids in range", sqlEx); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}

		return resultIDs;
	}

	/**
	 * Get a representation of all the events, within the given time range, that
	 * pass the given filter, grouped by time and description such that file
	 * system events for the same file, with the same timestamp, are combined
	 * together.
	 *
	 * @param timeRange The Interval that all returned events must be within.
	 * @param filter    The Filter that all returned events must pass.
	 *
	 * @return A List of combined events, sorted by timestamp.
	 */
	public List<CombinedEvent> getCombinedEvents(Interval timeRange, RootFilter filter) throws TskCoreException {
		Long startTime = timeRange.getStartMillis() / 1000;
		Long endTime = timeRange.getEndMillis() / 1000;

		if (Objects.equals(startTime, endTime)) {
			endTime++; //make sure end is at least 1 millisecond after start
		}

		ArrayList<CombinedEvent> results = new ArrayList<>();
		final String query = "SELECT full_description, time, file_id, "
				+ csvAggFunction("CAST(events.event_id AS VARCHAR)") + " AS eventIDs, "
				+ csvAggFunction("CAST(sub_type AS VARCHAR)") + " AS eventTypes"
				+ " FROM events " + useHashHitTablesHelper(filter) + useTagTablesHelper(filter)
				+ " WHERE time >= " + startTime + " AND time <" + endTime + " AND " + getSQLWhere(filter)
				+ " GROUP BY time, full_description, file_id ORDER BY time ASC, full_description";

		sleuthkitCase.acquireSingleUserCaseReadLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stmt = con.createStatement();
				ResultSet rs = stmt.executeQuery(query);) {

			while (rs.next()) {

				//make a map from event type to event ID
				List<Long> eventIDs = unGroupConcat(rs.getString("eventIDs"), Long::valueOf);
				List<EventType> eventTypes = unGroupConcat(rs.getString("eventTypes"), (String s) -> RootEventType.allTypes.get(Integer.valueOf(s)));
				Map<EventType, Long> eventMap = new HashMap<>();
				for (int i = 0; i < eventIDs.size(); i++) {
					eventMap.put(eventTypes.get(i), eventIDs.get(i));
				}
				results.add(new CombinedEvent(rs.getLong("time") * 1000, rs.getString("full_description"), rs.getLong("file_id"), eventMap));
			}

		} catch (SQLException sqlEx) {
			throw new TskCoreException("failed to execute query for combined events", sqlEx); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}

		return results;
	}

	/**
	 * this relies on the fact that no tskObj has ID 0 but 0 is the default
	 * value for the datasource_id column in the events table.
	 */
	public boolean hasNewColumns() throws TskCoreException {
		return hasHashHitColumn() && hasDataSourceIDColumn() && hasTaggedColumn()
				&& (getDataSourceIDs().isEmpty() == false);
	}

	public Set<Long> getDataSourceIDs() throws TskCoreException {
		sleuthkitCase.acquireSingleUserCaseReadLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stmt = con.createStatement();
				ResultSet rs = stmt.executeQuery(PREPARED_STATEMENT.GET_DATASOURCE_IDS.getSQL());) {
			HashSet<Long> hashSet = new HashSet<>();
			while (rs.next()) {
				long datasourceID = rs.getLong("datasource_id"); //NON-NLS
				hashSet.add(datasourceID);
			}
			return hashSet;
		} catch (SQLException ex) {
			throw new TskCoreException("Failed to get MAX time.", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}
	}

	public Map<Long, String> getHashSetNames() throws TskCoreException {
		//TODO: get from main tables
		Map<Long, String> hashSets = new HashMap<>();
		sleuthkitCase.acquireSingleUserCaseReadLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stms = con.createStatement();
				ResultSet rs = stms.executeQuery(PREPARED_STATEMENT.GET_HASH_SET_NAMES.getSQL());) {
			while (rs.next()) {
				long hashSetID = rs.getLong("hash_set_id"); //NON-NLS
				String hashSetName = rs.getString("hash_set_name"); //NON-NLS
				hashSets.put(hashSetID, hashSetName);
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Failed to get hash sets.", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}
		return Collections.unmodifiableMap(hashSets);
	}

	public void analyze() throws TskCoreException {
		sleuthkitCase.acquireSingleUserCaseWriteLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stmt = con.createStatement();) {

			stmt.execute("ANALYZE;"); //NON-NLS
			if (sleuthkitCase.getDatabaseType() == TskData.DbType.SQLITE) {
				stmt.execute("analyze sqlite_master;"); //NON-NLS
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Failed to analyze events db.", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseWriteLock();
		}
	}

	/**
	 * @return maximum time in seconds from unix epoch
	 */
	public Long getMaxTime() throws TskCoreException {
		sleuthkitCase.acquireSingleUserCaseReadLock();

		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stms = con.createStatement();
				ResultSet rs = stms.executeQuery(PREPARED_STATEMENT.GET_MAX_TIME.getSQL());) {
			while (rs.next()) {
				return rs.getLong("max"); // NON-NLS
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Failed to get MAX time.", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}
		return -1l;
	}

	/**
	 * @return maximum time in seconds from unix epoch
	 */
	public Long getMinTime() throws TskCoreException {
		sleuthkitCase.acquireSingleUserCaseReadLock();

		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stms = con.createStatement();
				ResultSet rs = stms.executeQuery(PREPARED_STATEMENT.GET_MIN_TIME.getSQL());) {
			while (rs.next()) {
				return rs.getLong("min"); // NON-NLS
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Failed to get MIN time.", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}
		return -1l;
	}

	/**
	 * create the table and indices if they don't already exist
	 *
	 * @return the number of rows in the table , count > 0 indicating an
	 *         existing table
	 */
	final synchronized void initializeDB() throws TskCoreException {
		///TODO: Move to SleuthkitCase?
		sleuthkitCase.acquireSingleUserCaseWriteLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stmt = con.createStatement();) {

			try {
				stmt.execute("CREATE TABLE if not exists db_info ( key TEXT,  value INTEGER, PRIMARY KEY (key))");// NON-NLS
			} catch (SQLException ex) {
				throw new TskCoreException("problem creating db_info table", ex); // NON-NLS
			}

			try {
				stmt.execute("CREATE TABLE if not exists events " // NON-NLS
						+ " (event_id " + primaryKeyType + " PRIMARY KEY, " // NON-NLS
						+ " datasource_id BIGINT, " // NON-NLS
						+ " file_id BIGINT, " // NON-NLS
						+ " artifact_id BIGINT, " // NON-NLS
						+ " time INTEGER, " // NON-NLS
						+ " sub_type INTEGER, " // NON-NLS
						+ " base_type INTEGER, " // NON-NLS
						+ " full_description TEXT, " // NON-NLS
						+ " med_description TEXT, " // NON-NLS
						+ " short_description TEXT, " // NON-NLS
						+ " known_state INTEGER," //boolean // NON-NLS
						+ " hash_hit INTEGER," //boolean // NON-NLS
						+ " tagged INTEGER)");//boolean // NON-NLS
			} catch (SQLException ex) {
				throw new TskCoreException("problem creating  database table", ex); // NON-NLS
			}

			if (hasDataSourceIDColumn() == false) {
				try {
					stmt.execute("ALTER TABLE events ADD COLUMN datasource_id INTEGER");	// NON-NLS
				} catch (SQLException ex) {
					throw new TskCoreException("problem upgrading events table", ex); // NON-NLS
				}
			}
			if (hasTaggedColumn() == false) {
				try {
					// NON-NLS
					stmt.execute("ALTER TABLE events ADD COLUMN tagged INTEGER");
				} catch (SQLException ex) {
					throw new TskCoreException("problem upgrading events table", ex); // NON-NLS
				}
			}

			if (hasHashHitColumn() == false) {
				try {
					stmt.execute("ALTER TABLE events ADD COLUMN hash_hit INTEGER");	// NON-NLS
				} catch (SQLException ex) {
					throw new TskCoreException("problem upgrading events table", ex); // NON-NLS
				}
			}

			try {
				stmt.execute("CREATE TABLE IF NOT EXISTS hash_sets " //NON-NLS
						+ "( hash_set_id " + primaryKeyType + " primary key," //NON-NLS
						+ " hash_set_name VARCHAR(255) UNIQUE NOT NULL)");	//NON-NLS
			} catch (SQLException ex) {
				throw new TskCoreException("problem creating hash_sets table", ex); //NON-NLS
			}

			try {
				stmt.execute("CREATE TABLE  if not exists hash_set_hits " //NON-NLS
						+ "(hash_set_id INTEGER REFERENCES hash_sets(hash_set_id) not null, " //NON-NLS
						+ " event_id INTEGER REFERENCES events(event_id) not null, " //NON-NLS
						+ " PRIMARY KEY (hash_set_id, event_id))");			//NON-NLS
			} catch (SQLException ex) {
				throw new TskCoreException("problem creating hash_set_hits table", ex); //NON-NLS
			}

			initializeTagsTable();

			createIndex("events", Arrays.asList("datasource_id")); //NON-NLS
			createIndex("events", Arrays.asList("event_id", "hash_hit")); //NON-NLS
			createIndex("events", Arrays.asList("event_id", "tagged")); //NON-NLS
			createIndex("events", Arrays.asList("file_id")); //NON-NLS
			createIndex("events", Arrays.asList("artifact_id")); //NON-NLS
			createIndex("events", Arrays.asList("sub_type", "short_description", "time")); //NON-NLS
			createIndex("events", Arrays.asList("base_type", "short_description", "time")); //NON-NLS
			createIndex("events", Arrays.asList("time")); //NON-NLS
			createIndex("events", Arrays.asList("known_state")); //NON-NLS
		} catch (SQLException ex) {
			throw new TskCoreException("Error initializing event tables", ex);
		} finally {
			sleuthkitCase.releaseSingleUserCaseWriteLock();
		}
	}

	private enum PREPARED_STATEMENT {
		INSERT_ROW("INSERT INTO events ("
				+ "datasource_id,"
				+ "file_id ,"
				+ "artifact_id, "
				+ "time, "
				+ "sub_type,"
				+ " base_type,"
				+ " full_description,"
				+ " med_description, "
				+ "short_description, "
				+ "known_state,"
				+ " hash_hit,"
				+ " tagged) " // NON-NLS
				+ "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"), // NON-NLS
		GET_HASH_SET_NAMES("SELECT hash_set_id, hash_set_name FROM hash_sets"), // NON-NLS
		GET_DATASOURCE_IDS("SELECT DISTINCT datasource_id FROM events WHERE datasource_id != 0"),// NON-NLS
		GET_MAX_TIME("SELECT Max(time) AS max FROM events"), // NON-NLS
		GET_MIN_TIME("SELECT Min(time) AS min FROM events"), // NON-NLS
		GET_EVENT_BY_ID("SELECT * FROM events WHERE event_id =  ?"), // NON-NLS
		INSERT_HASH_SET("INSERT OR IGNORE INTO hash_sets (hash_set_name)  values (?)"), //NON-NLS
		GET_HASH_SET_NAME_BY_ID("SELECT hash_set_id FROM hash_sets WHERE hash_set_name = ?"), //NON-NLS
		INSERT_HASH_HIT("INSERT OR IGNORE INTO hash_set_hits (hash_set_id, event_id) values (?,?)"), //NON-NLS
		INSERT_TAG("INSERT OR IGNORE INTO tags (tag_id, tag_name_id,tag_name_display_name, event_id) values (?,?,?,?)"), //NON-NLS
		DELETE_TAG("DELETE FROM tags WHERE tag_id = ?"), //NON-NLS

		/*
		 * This SQL query is really just a select count(*), but that has
		 * performance problems on very large tables unless you include a where
		 * clause see http://stackoverflow.com/a/9338276/4004683 for more.
		 */
		COUNT_ALL_EVENTS("SELECT count(event_id) AS count FROM events WHERE event_id IS NOT null"), //NON-NLS
		DROP_EVENTS_TABLE("DROP TABLE IF EXISTS events"), //NON-NLS
		DROP_HASH_SET_HITS_TABLE("DROP TABLE IF EXISTS hash_set_hits"), //NON-NLS
		DROP_HASH_SETS_TABLE("DROP TABLE IF EXISTS hash_sets"), //NON-NLS
		DROP_TAGS_TABLE("DROP TABLE IF EXISTS tags"), //NON-NLS
		DROP_DB_INFO_TABLE("DROP TABLE IF EXISTS db_ino"), //NON-NLS
		SELECT_NON_ARTIFACT_EVENT_IDS_BY_OBJECT_ID("SELECT event_id FROM events WHERE file_id == ? AND artifact_id IS NULL"), //NON-NLS
		SELECT_EVENT_IDS_BY_OBJECT_ID_AND_ARTIFACT_ID("SELECT event_id FROM events WHERE file_id == ? AND artifact_id = ?"); //NON-NLS

		private final String sql;

		private PREPARED_STATEMENT(String sql) {
			this.sql = sql;
		}

		String getSQL() {
			return sql;
		}
	}

	/**
	 * Get a List of event IDs for the events that are derived from the given
	 * artifact.
	 *
	 * @param artifact The BlackboardArtifact to get derived event IDs for.
	 *
	 * @return A List of event IDs for the events that are derived from the
	 *         given artifact.
	 */
	public List<Long> getEventIDsForArtifact(BlackboardArtifact artifact) throws TskCoreException {
		ArrayList<Long> results = new ArrayList<Long>();

		String query = "SELECT event_id FROM events WHERE artifact_id == " + artifact.getArtifactID();
		sleuthkitCase.acquireSingleUserCaseReadLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stmt = con.createStatement();
				ResultSet rs = stmt.executeQuery(query);) {
			while (rs.next()) {
				results.add(rs.getLong("event_id"));
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error executing getEventIDsForArtifact query.", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}
		return results;
	}

	/**
	 * Get a List of event IDs for the events that are derived from the given
	 * file.
	 *
	 * @param file                    The AbstractFile to get derived event IDs
	 *                                for.
	 * @param includeDerivedArtifacts If true, also get event IDs for events
	 *                                derived from artifacts derived form this
	 *                                file. If false, only gets events derived
	 *                                directly from this file (file system
	 *                                timestamps).
	 *
	 * @return A List of event IDs for the events that are derived from the
	 *         given file.
	 */
	public List<Long> getEventIDsForFile(AbstractFile file, boolean includeDerivedArtifacts) throws TskCoreException {
		ArrayList<Long> results = new ArrayList<>();

		String query = "SELECT event_id FROM events WHERE file_id == " + file.getId()
				+ (includeDerivedArtifacts ? "" : " AND artifact_id IS NULL");
		sleuthkitCase.acquireSingleUserCaseReadLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stmt = con.createStatement();
				ResultSet rs = stmt.executeQuery(query);) {
			while (rs.next()) {
				results.add(rs.getLong("event_id"));
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error executing getEventIDsForFile query.", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}
		return results;
	}

	/**
	 * create the tags table if it doesn't already exist. This is broken out as
	 * a separate method so it can be used by reInitializeTags()
	 *
	 * NOTE: does not lock the db, must be called form inside a
	 * DBLock.lock/unlock pair
	 *
	 */
	private void initializeTagsTable() throws TskCoreException {
		String sql = "CREATE TABLE IF NOT EXISTS tags " //NON-NLS
				+ "(tag_id INTEGER NOT NULL," //NON-NLS
				+ " tag_name_id INTEGER NOT NULL, " //NON-NLS
				+ " tag_name_display_name TEXT NOT NULL, " //NON-NLS
				+ " event_id INTEGER REFERENCES events(event_id) NOT NULL, " //NON-NLS
				+ " PRIMARY KEY (event_id, tag_name_id))"; //NON-NLS
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stmt = con.createStatement();) {
			stmt.execute(sql);
		} catch (SQLException ex) {
			throw new TskCoreException("problem creating tags table", ex); //NON-NLS
		}
	}

	/**
	 * NOTE: does not lock the db, must be called form inside a
	 * DBLock.lock/unlock pair
	 *
	 * @param tableName  the value of tableName
	 * @param columnList the value of columnList
	 */
	private void createIndex(final String tableName, final List<String> columnList) throws TskCoreException {
		String indexColumns = columnList.stream().collect(Collectors.joining(",", "(", ")"));
		String indexName = tableName + "_" + StringUtils.joinAsStrings(columnList, "_") + "_idx"; //NON-NLS
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stmt = con.createStatement();) {
			String sql = "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + indexColumns; // NON-NLS
			stmt.execute(sql);
		} catch (SQLException ex) {
			throw new TskCoreException("problem creating index " + indexName, ex); // NON-NLS
		}
	}

	/**
	 * @param dbColumn the value of dbColumn
	 *
	 * @return the boolean
	 */
	private boolean hasDBColumn(final String dbColumn) throws TskCoreException {

		String query = sleuthkitCase.getDatabaseType() == TskData.DbType.POSTGRESQL
				? "SELECT column_name as name  FROM information_schema.columns  WHERE  table_name='events';" //NON-NLS  //Postgres
				: "PRAGMA table_info(events)";	//NON-NLS //SQLite
		sleuthkitCase.acquireSingleUserCaseReadLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement statement = con.createStatement();) {
			statement.execute(query);
			try (ResultSet results = statement.getResultSet();) {
				while (results.next()) {
					if (dbColumn.equals(results.getString("name"))) {	//NON-NLS
						return true;
					}
				}
			}
		} catch (SQLException ex) {
			throw new TskCoreException("Error querying for events table column names", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}
		return false;
	}

	private boolean hasDataSourceIDColumn() throws TskCoreException {
		return hasDBColumn("datasource_id"); //NON-NLS
	}

	private boolean hasTaggedColumn() throws TskCoreException {
		return hasDBColumn("tagged"); //NON-NLS
	}

	private boolean hasHashHitColumn() throws TskCoreException {
		return hasDBColumn("hash_hit"); //NON-NLS
	}

	public void insertEvent(long time, EventType type, long datasourceID, long objID,
			Long artifactID, String fullDescription, String medDescription,
			String shortDescription, TskData.FileKnown known, Set<String> hashSetNames, List<? extends Tag> tags) throws TskCoreException {

		int typeNum = RootEventType.allTypes.indexOf(type);
		int superTypeNum = type.getSuperType().ordinal();

		sleuthkitCase.acquireSingleUserCaseWriteLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				PreparedStatement insertRowStmt = con.prepareStatement(PREPARED_STATEMENT.INSERT_ROW.getSQL(), 1);) {
			//"INSERT INTO events (datasource_id,file_id ,artifact_id, time, sub_type, base_type, full_description, med_description, short_description, known_state, hashHit, tagged) " 
			insertRowStmt.clearParameters();
			insertRowStmt.setLong(1, datasourceID);
			insertRowStmt.setLong(2, objID);
			if (artifactID != null) {
				insertRowStmt.setLong(3, artifactID);
			} else {
				insertRowStmt.setNull(3, Types.NULL);
			}
			insertRowStmt.setLong(4, time);

			if (typeNum != -1) {
				insertRowStmt.setInt(5, typeNum);
			} else {
				insertRowStmt.setNull(5, Types.INTEGER);
			}

			insertRowStmt.setInt(6, superTypeNum);
			insertRowStmt.setString(7, fullDescription);
			insertRowStmt.setString(8, medDescription);
			insertRowStmt.setString(9, shortDescription);

			insertRowStmt.setByte(10, known == null ? TskData.FileKnown.UNKNOWN.getFileKnownValue() : known.getFileKnownValue());

			insertRowStmt.setInt(11, hashSetNames.isEmpty() ? 0 : 1);
			insertRowStmt.setInt(12, tags.isEmpty() ? 0 : 1);

			insertRowStmt.executeUpdate();

			try (ResultSet generatedKeys = insertRowStmt.getGeneratedKeys();
					PreparedStatement insertHashSetStmt = con.prepareStatement(PREPARED_STATEMENT.INSERT_HASH_SET.getSQL(), 0);
					PreparedStatement selectHashSetStmt = con.prepareStatement(PREPARED_STATEMENT.GET_HASH_SET_NAME_BY_ID.getSQL(), 0);) {

				while (generatedKeys.next()) {
					long eventID = generatedKeys.getLong("last_insert_rowid()"); //NON-NLS
					for (String name : hashSetNames) {

						// "insert or ignore into hash_sets (hash_set_name)  values (?)"
						insertHashSetStmt.setString(1, name);
						insertHashSetStmt.executeUpdate();

						//TODO: use nested select to get hash_set_id rather than seperate statement/query ?
						//"select hash_set_id from hash_sets where hash_set_name = ?"
						selectHashSetStmt.setString(1, name);

						try (PreparedStatement insertHashHitStmt = con.prepareStatement(PREPARED_STATEMENT.INSERT_HASH_HIT.getSQL(), 0);
								ResultSet rs = selectHashSetStmt.executeQuery();) {
							while (rs.next()) {
								int hashsetID = rs.getInt("hash_set_id"); //NON-NLS
								//"insert or ignore into hash_set_hits (hash_set_id, obj_id) values (?,?)";
								insertHashHitStmt.setInt(1, hashsetID);
								insertHashHitStmt.setLong(2, eventID);
								insertHashHitStmt.executeUpdate();
								break;
							}
						}
					}
					for (Tag tag : tags) {
						//could this be one insert?  is there a performance win?
						insertTag(tag, eventID);
					}
					break;
				}
			}
		} catch (SQLException ex) {
			throw new TskCoreException("failed to insert event", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseWriteLock();
		}
	}

	/**
	 * mark any events with the given object and artifact ids as tagged, and
	 * record the tag it self.
	 *
	 * @param objectID    the obj_id that this tag applies to, the id of the
	 *                    content that the artifact is derived from for artifact
	 *                    tags
	 * @param artifactID  the artifact_id that this tag applies to, or null if
	 *                    this is a content tag
	 * @param tag         the tag that should be inserted
	 * @param transaction
	 *
	 * @return the event ids that match the object/artifact pair
	 */
	public Set<Long> addTag(long objectID, Long artifactID, Tag tag) throws TskCoreException {

		sleuthkitCase.acquireSingleUserCaseWriteLock();
		try {
			Set<Long> eventIDs = markEventsTagged(objectID, artifactID, true);
			for (Long eventID : eventIDs) {
				insertTag(tag, eventID);
			}
			return eventIDs;
		} catch (SQLException ex) {
			LOGGER.log(Level.SEVERE, "failed to add tag to event", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseWriteLock();
		}
		return Collections.emptySet();
	}

	/**
	 * insert the given tag into the db * @param tag the tag to insert
	 *
	 * @param eventID the event id that this tag is applied to.
	 *
	 */
	private void insertTag(Tag tag, long eventID) throws TskCoreException {
		sleuthkitCase.acquireSingleUserCaseWriteLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				PreparedStatement insertTagStmt = con.prepareStatement(PREPARED_STATEMENT.INSERT_TAG.getSQL(), 0);) {
			//"INSERT OR IGNORE INTO tags (tag_id, tag_name_id,tag_name_display_name, event_id) values (?,?,?,?)"
			insertTagStmt.setLong(1, tag.getId());
			insertTagStmt.setLong(2, tag.getName().getId());
			insertTagStmt.setString(3, tag.getName().getDisplayName());
			insertTagStmt.setLong(4, eventID);
			insertTagStmt.executeUpdate();
		} catch (SQLException ex) {
			throw new TskCoreException("Error inserting tag into events db.", ex);
		} finally {
			sleuthkitCase.releaseSingleUserCaseWriteLock();
		}
	}

	/**
	 * mark any events with the given object and artifact ids as tagged, and
	 * record the tag it self.
	 *
	 * @param objectID    the obj_id that this tag applies to, the id of the
	 *                    content that the artifact is derived from for artifact
	 *                    tags
	 * @param artifactID  the artifact_id that this tag applies to, or null if
	 *                    this is a content tag
	 * @param tagID
	 * @param stillTagged true if there are other tags still applied to this
	 *                    event in autopsy
	 *
	 * @return the event ids that match the object/artifact pair
	 *
	 * @throws org.sleuthkit.datamodel.TskCoreException
	 */
	public Set<Long> deleteTag(long objectID, Long artifactID, long tagID, boolean stillTagged) throws TskCoreException {
		sleuthkitCase.acquireSingleUserCaseWriteLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				PreparedStatement deleteTagStmt = con.prepareStatement(PREPARED_STATEMENT.DELETE_TAG.getSQL(), 0);) {
			//"DELETE FROM tags WHERE tag_id = ?
			deleteTagStmt.setLong(1, tagID);
			deleteTagStmt.executeUpdate();

			return markEventsTagged(objectID, artifactID, stillTagged);
		} catch (SQLException ex) {
			throw new TskCoreException("failed to delete tag from event", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseWriteLock();
		}
	}

	private HashSet<Long> getEventIDs(long objectID) throws TskCoreException {
//TODO: inline this
		HashSet<Long> eventIDs = new HashSet<>();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				PreparedStatement selectStmt = con.prepareStatement(PREPARED_STATEMENT.SELECT_NON_ARTIFACT_EVENT_IDS_BY_OBJECT_ID.getSQL(), 0);) {
			//"SELECT event_id FROM events WHERE file_id == ? AND artifact_id IS NULL"
			selectStmt.setLong(1, objectID);
			try (ResultSet executeQuery = selectStmt.executeQuery();) {

				while (executeQuery.next()) {
					eventIDs.add(executeQuery.getLong("event_id")); //NON-NLS
				}
			}
		} catch (SQLException ex) {
			Logger.getLogger(TimelineManager.class.getName()).log(Level.SEVERE, null, ex);
		}
		return eventIDs;
	}

	private HashSet<Long> getEventIDs(long objectID, Long artifactID) throws TskCoreException {
		//TODO: inline this
		HashSet<Long> eventIDs = new HashSet<>();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				PreparedStatement selectStmt = con.prepareStatement(PREPARED_STATEMENT.SELECT_EVENT_IDS_BY_OBJECT_ID_AND_ARTIFACT_ID.getSQL(), 0);) {
			//"SELECT event_id FROM events WHERE file_id == ? AND artifact_id = ?"
			selectStmt.setLong(1, objectID);
			selectStmt.setLong(2, artifactID);
			try (ResultSet executeQuery = selectStmt.executeQuery();) {

				while (executeQuery.next()) {
					eventIDs.add(executeQuery.getLong("event_id")); //NON-NLS
				}
			}
		} catch (SQLException ex) {
			Logger.getLogger(TimelineManager.class.getName()).log(Level.SEVERE, null, ex);
		}
		return eventIDs;
	}

	/**
	 * mark any events with the given object and artifact ids as tagged, and
	 * record the tag it self.
	 * <p>
	 * NOTE: does not lock the db, must be called form inside a
	 * DBLock.lock/unlock pair
	 *
	 * @param objectID   the obj_id that this tag applies to, the id of the
	 *                   content that the artifact is derived from for artifact
	 *                   tags
	 * @param artifactID the artifact_id that this tag applies to, or null if
	 *                   this is a content tag
	 * @param tagged     true to mark the matching events tagged, false to mark
	 *                   them as untagged
	 *
	 * @return the event ids that match the object/artifact pair
	 *
	 * @throws SQLException if there is an error marking the events as
	 *                      (un)taggedS
	 */
	private Set<Long> markEventsTagged(long objectID, Long artifactID, boolean tagged) throws SQLException, TskCoreException {
		HashSet<Long> eventIDs = new HashSet<>();;
		if (Objects.isNull(artifactID)) {

			eventIDs = getEventIDs(objectID);

		} else {
			eventIDs = getEventIDs(objectID, artifactID);

		}

//update tagged state for all event with selected ids
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement updateStatement = con.createStatement();) {
			updateStatement.executeUpdate("UPDATE events SET tagged = " + (tagged ? 1 : 0) //NON-NLS
					+ " WHERE event_id IN (" + StringUtils.joinAsStrings(eventIDs, ",") + ")"); //NON-NLS
		}

		return eventIDs;
	}

	void rollBackTransaction(SleuthkitCase.CaseDbTransaction trans) throws TskCoreException {
		trans.rollback();
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			closeStatements();
		} finally {
			super.finalize();
		}
	}

	private void closeStatements() throws SQLException {
		for (PreparedStatement pStmt : preparedStatements) {
			pStmt.close();
		}
	}

	private SingleEvent constructTimeLineEvent(ResultSet rs) throws SQLException {
		return new SingleEvent(rs.getLong("event_id"), //NON-NLS
				rs.getLong("datasource_id"), //NON-NLS
				rs.getLong("file_id"), //NON-NLS
				rs.getLong("artifact_id"), //NON-NLS
				rs.getLong("time"), RootEventType.allTypes.get(rs.getInt("sub_type")), //NON-NLS
				rs.getString("full_description"), //NON-NLS
				rs.getString("med_description"), //NON-NLS
				rs.getString("short_description"), //NON-NLS
				TskData.FileKnown.valueOf(rs.getByte("known_state")), //NON-NLS
				rs.getInt("hash_hit") != 0, //NON-NLS
				rs.getInt("tagged") != 0); //NON-NLS
	}

	/**
	 * count all the events with the given options and return a map organizing
	 * the counts in a hierarchy from date > eventtype> count
	 *
	 * @param startTime events before this time will be excluded (seconds from
	 *                  unix epoch)
	 * @param endTime   events at or after this time will be excluded (seconds
	 *                  from unix epoch)
	 * @param filter    only events that pass this filter will be counted
	 * @param zoomLevel only events of this type or a subtype will be counted
	 *                  and the counts will be organized into bins for each of
	 *                  the subtypes of the given event type
	 *
	 * @return a map organizing the counts in a hierarchy from date > eventtype>
	 *         count
	 */
	private Map<EventType, Long> countEventsByType(Long startTime, Long endTime, RootFilter filter, EventTypeZoomLevel zoomLevel) {
		if (Objects.equals(startTime, endTime)) {
			endTime++;
		}

		Map<EventType, Long> typeMap = new HashMap<>();

		//do we want the root or subtype column of the databse
		final boolean useSubTypes = (zoomLevel == EventTypeZoomLevel.SUB_TYPE);

		//get some info about the range of dates requested
		final String queryString = "SELECT count(DISTINCT events.event_id) AS count, " + typeColumnHelper(useSubTypes) //NON-NLS
				+ " FROM events" + useHashHitTablesHelper(filter) + useTagTablesHelper(filter) + " WHERE time >= " + startTime + " AND time < " + endTime + " AND " + getSQLWhere(filter) // NON-NLS
				+ " GROUP BY " + typeColumnHelper(useSubTypes); // NON-NLS

		sleuthkitCase.acquireSingleUserCaseReadLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement stmt = con.createStatement();
				ResultSet rs = stmt.executeQuery(queryString);) {
			while (rs.next()) {
				EventType type = useSubTypes
						? RootEventType.allTypes.get(rs.getInt("sub_type")) //NON-NLS
						: BaseTypes.values()[rs.getInt("base_type")]; //NON-NLS

				typeMap.put(type, rs.getLong("count")); // NON-NLS
			}

		} catch (Exception ex) {
			LOGGER.log(Level.SEVERE, queryString);
			LOGGER.log(Level.SEVERE, "Error getting count of events from db.", ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}
		return typeMap;
	}

	/**
	 * get a list of {@link EventStripe}s, clustered according to the given zoom
	 * paramaters.
	 *
	 * @param params the {@link ZoomParams} that determine the zooming,
	 *               filtering and clustering.
	 *
	 * @return a list of aggregate events within the given timerange, that pass
	 *         the supplied filter, aggregated according to the given event type
	 *         and description zoom levels
	 */
	public List<EventStripe> getEventStripes(ZoomParams params, DateTimeZone tz) throws TskCoreException {
		//unpack params
		Interval timeRange = params.getTimeRange();
		RootFilter filter = params.getFilter();
		DescriptionLoD descriptionLOD = params.getDescriptionLOD();
		EventTypeZoomLevel typeZoomLevel = params.getTypeZoomLevel();

		long start = timeRange.getStartMillis() / 1000;
		long end = timeRange.getEndMillis() / 1000;

		//ensure length of querried interval is not 0
		end = Math.max(end, start + 1);

		//get some info about the time range requested
		RangeDivisionInfo rangeInfo = RangeDivisionInfo.getRangeDivisionInfo(timeRange, tz);

		//build dynamic parts of query
		String strfTimeFormat = getStrfTimeFormat(rangeInfo.getPeriodSize());
		String descriptionColumn = getDescriptionColumn(descriptionLOD);
		final boolean useSubTypes = typeZoomLevel.equals(EventTypeZoomLevel.SUB_TYPE);
		String timeZone = tz.equals(DateTimeZone.getDefault()) ? ", 'localtime'" : "";  // NON-NLS
		String typeColumn = typeColumnHelper(useSubTypes);

		//compose query string, the new-lines are only for nicer formatting if printing the entire query
		String query = "SELECT strftime('" + strfTimeFormat + "',time , 'unixepoch'" + timeZone + ") AS interval, " // NON-NLS
				+ csvFunction + "(events.event_id) as event_ids, " //NON-NLS
				+ csvFunction + "(CASE WHEN hash_hit = 1 THEN events.event_id ELSE NULL END) as hash_hits, " //NON-NLS
				+ csvFunction + "(CASE WHEN tagged = 1 THEN events.event_id ELSE NULL END) as taggeds, " //NON-NLS
				+ " min(time), max(time),  " + typeColumn + ", " + descriptionColumn // NON-NLS
				+ " FROM events" + useHashHitTablesHelper(filter) + useTagTablesHelper(filter) // NON-NLS
				+ " WHERE time >= " + start + " AND time < " + end + " AND " + getSQLWhere(filter) // NON-NLS
				+ " GROUP BY interval, " + typeColumn + " , " + descriptionColumn // NON-NLS
				+ " ORDER BY min(time)"; // NON-NLS

		// perform query and map results to AggregateEvent objects
		List<EventCluster> events = new ArrayList<>();

		sleuthkitCase.acquireSingleUserCaseReadLock();
		try (CaseDbConnection con = sleuthkitCase.getConnection();
				Statement createStatement = con.createStatement();
				ResultSet rs = createStatement.executeQuery(query)) {
			while (rs.next()) {
				events.add(eventClusterHelper(rs, useSubTypes, descriptionLOD, filter.getTagsFilter(), tz));
			}
		} catch (SQLException ex) {
			LOGGER.log(Level.SEVERE, "Failed to get events with query: " + query, ex); // NON-NLS
		} finally {
			sleuthkitCase.releaseSingleUserCaseReadLock();
		}

		return mergeClustersToStripes(rangeInfo.getPeriodSize().getPeriod(), events);
	}

	/**
	 * map a single row in a ResultSet to an EventCluster
	 *
	 * @param rs             the result set whose current row should be mapped
	 * @param useSubTypes    use the sub_type column if true, else use the
	 *                       base_type column
	 * @param descriptionLOD the description level of detail for this event
	 * @param filter
	 *
	 * @return an AggregateEvent corresponding to the current row in the given
	 *         result set
	 *
	 * @throws SQLException
	 */
	private EventCluster eventClusterHelper(ResultSet rs, boolean useSubTypes, DescriptionLoD descriptionLOD, TagsFilter filter, DateTimeZone tz) throws SQLException {
		Interval interval = new Interval(rs.getLong("min(time)") * 1000, rs.getLong("max(time)") * 1000, tz);// NON-NLS
		String eventIDsString = rs.getString("event_ids");// NON-NLS
		List<Long> eventIDs = unGroupConcat(eventIDsString, Long::valueOf);
		String description = rs.getString(getDescriptionColumn(descriptionLOD));
		EventType type = useSubTypes ? RootEventType.allTypes.get(rs.getInt("sub_type")) : BaseTypes.values()[rs.getInt("base_type")];// NON-NLS

		List<Long> hashHits = unGroupConcat(rs.getString("hash_hits"), Long::valueOf); //NON-NLS
		List<Long> tagged = unGroupConcat(rs.getString("taggeds"), Long::valueOf); //NON-NLS

		return new EventCluster(interval, type, eventIDs, hashHits, tagged, description, descriptionLOD);
	}

	/**
	 * merge the events in the given list if they are within the same period
	 * General algorithm is as follows:
	 *
	 * 1) sort them into a map from (type, description)-> List<aggevent>
	 * 2) for each key in map, merge the events and accumulate them in a list to
	 * return
	 *
	 * @param timeUnitLength
	 * @param preMergedEvents
	 *
	 * @return
	 */
	static private List<EventStripe> mergeClustersToStripes(Period timeUnitLength, List<EventCluster> preMergedEvents) {

		//effectively map from type to (map from description to events)
		Map<EventType, SetMultimap< String, EventCluster>> typeMap = new HashMap<>();

		for (EventCluster aggregateEvent : preMergedEvents) {
			typeMap.computeIfAbsent(aggregateEvent.getEventType(), eventType -> HashMultimap.create())
					.put(aggregateEvent.getDescription(), aggregateEvent);
		}
		//result list to return
		ArrayList<EventCluster> aggEvents = new ArrayList<>();

		//For each (type, description) key, merge agg events
		for (SetMultimap<String, EventCluster> descrMap : typeMap.values()) {
			//for each description ...
			for (String descr : descrMap.keySet()) {
				//run through the sorted events, merging together adjacent events
				Iterator<EventCluster> iterator = descrMap.get(descr).stream()
						.sorted(Comparator.comparing(event -> event.getSpan().getStartMillis()))
						.iterator();
				EventCluster current = iterator.next();
				while (iterator.hasNext()) {
					EventCluster next = iterator.next();
					Interval gap = current.getSpan().gap(next.getSpan());

					//if they overlap or gap is less one quarter timeUnitLength
					//TODO: 1/4 factor is arbitrary. review! -jm
					if (gap == null || gap.toDuration().getMillis() <= timeUnitLength.toDurationFrom(gap.getStart()).getMillis() / 4) {
						//merge them
						current = EventCluster.merge(current, next);
					} else {
						//done merging into current, set next as new current
						aggEvents.add(current);
						current = next;
					}
				}
				aggEvents.add(current);
			}
		}

		//merge clusters to stripes
		Map<ImmutablePair<EventType, String>, EventStripe> stripeDescMap = new HashMap<>();

		for (EventCluster eventCluster : aggEvents) {
			stripeDescMap.merge(ImmutablePair.of(eventCluster.getEventType(), eventCluster.getDescription()),
					new EventStripe(eventCluster), EventStripe::merge);
		}

		return stripeDescMap.values().stream().sorted(Comparator.comparing(EventStripe::getStartMillis)).collect(Collectors.toList());
	}

	private PreparedStatement prepareStatement(CaseDbConnection con, String queryString) throws SQLException {
		PreparedStatement prepareStatement = con.prepareStatement(queryString, 0);
		preparedStatements.add(prepareStatement);
		return prepareStatement;
	}

	private static String typeColumnHelper(final boolean useSubTypes) {
		return useSubTypes ? "sub_type" : "base_type"; //NON-NLS
	}

	/**
	 * Static helper methods for converting between java "data model" objects
	 * and sqlite queries.
	 */
	String useHashHitTablesHelper(RootFilter filter) {
		HashHitsFilter hashHitFilter = filter.getHashHitsFilter();
		return hashHitFilter.isActive() ? " LEFT JOIN hash_set_hits " : " "; //NON-NLS
	}

	String useTagTablesHelper(RootFilter filter) {
		TagsFilter tagsFilter = filter.getTagsFilter();
		return tagsFilter.isActive() ? " LEFT JOIN tags " : " "; //NON-NLS
	}

	/**
	 * take the result of a group_concat SQLite operation and split it into a
	 * set of X using the mapper to to convert from string to X
	 *
	 * @param <X>         the type of elements to return
	 * @param groupConcat a string containing the group_concat result ( a comma
	 *                    separated list)
	 * @param mapper      a function from String to X
	 *
	 * @return a Set of X, each element mapped from one element of the original
	 *         comma delimited string
	 */
	<X> List<X> unGroupConcat(String groupConcat, Function<String, X> mapper) {
		List<X> result = new ArrayList<X>();
		String[] split = groupConcat.split(",");
		for (String s : split) {
			result.add(mapper.apply(s));
		}
		return result;
	}

	/**
	 * get the SQL where clause corresponding to an intersection filter ie
	 * (sub-clause1 and sub-clause2 and ... and sub-clauseN)
	 *
	 * @param filter the filter get the where clause for
	 *
	 * @return an SQL where clause (without the "where") corresponding to the
	 *         filter
	 */
	private String getSQLWhere(IntersectionFilter<?> filter) {
		String join = String.join(" and ", filter.getSubFilters().stream()
				.filter(Filter::isActive)
				.map(this::getSQLWhere)
				.collect(Collectors.toList()));
		return "(" + org.apache.commons.lang3.StringUtils.defaultIfBlank(join, getTrueLiteral()) + ")";
	}

	/**
	 * get the SQL where clause corresponding to a union filter ie (sub-clause1
	 * or sub-clause2 or ... or sub-clauseN)
	 *
	 * @param filter the filter get the where clause for
	 *
	 * @return an SQL where clause (without the "where") corresponding to the
	 *         filter
	 */
	private String getSQLWhere(UnionFilter<?> filter) {
		String join = String.join(" or ", filter.getSubFilters().stream()
				.filter(Filter::isActive)
				.map(this::getSQLWhere)
				.collect(Collectors.toList()));
		return "(" + org.apache.commons.lang3.StringUtils.defaultIfBlank(join, getTrueLiteral()) + ")";
	}

	public String getSQLWhere(RootFilter filter) {
		return getSQLWhere((Filter) filter);
	}

	/**
	 * get the SQL where clause corresponding to the given filter
	 *
	 * uses instance of to dispatch to the correct method for each filter type.
	 * NOTE: I don't like this if-else instance of chain, but I can't decide
	 * what to do instead -jm
	 *
	 * @param filter a filter to generate the SQL where clause for
	 *
	 * @return an SQL where clause (without the "where") corresponding to the
	 *         filter
	 */
	private String getSQLWhere(Filter filter) {
		String result = "";
		if (filter == null) {
			return getTrueLiteral();
		} else if (filter instanceof DescriptionFilter) {
			result = getSQLWhere((DescriptionFilter) filter);
		} else if (filter instanceof TagsFilter) {
			result = getSQLWhere((TagsFilter) filter);
		} else if (filter instanceof HashHitsFilter) {
			result = getSQLWhere((HashHitsFilter) filter);
		} else if (filter instanceof DataSourceFilter) {
			result = getSQLWhere((DataSourceFilter) filter);
		} else if (filter instanceof DataSourcesFilter) {
			result = getSQLWhere((DataSourcesFilter) filter);
		} else if (filter instanceof HideKnownFilter) {
			result = getSQLWhere((HideKnownFilter) filter);
		} else if (filter instanceof HashHitsFilter) {
			result = getSQLWhere((HashHitsFilter) filter);
		} else if (filter instanceof TextFilter) {
			result = getSQLWhere((TextFilter) filter);
		} else if (filter instanceof TypeFilter) {
			result = getSQLWhere((TypeFilter) filter);
		} else if (filter instanceof IntersectionFilter) {
			result = getSQLWhere((IntersectionFilter) filter);
		} else if (filter instanceof UnionFilter) {
			result = getSQLWhere((UnionFilter) filter);
		} else {
			throw new IllegalArgumentException("getSQLWhere not defined for " + filter.getClass().getCanonicalName());
		}
		result = org.apache.commons.lang3.StringUtils.deleteWhitespace(result).equals("(1and1and1)") ? getTrueLiteral() : result; //NON-NLS
		result = org.apache.commons.lang3.StringUtils.deleteWhitespace(result).equals("()") ? getTrueLiteral() : result;
		return result;
	}

	private String getSQLWhere(HideKnownFilter filter) {
		if (filter.isActive()) {
			return "(known_state IS NOT '" + TskData.FileKnown.KNOWN.getFileKnownValue() + "')"; // NON-NLS
		} else {
			return getTrueLiteral();
		}
	}

	private String getSQLWhere(DescriptionFilter filter) {
		if (filter.isActive()) {
			String likeOrNotLike = (filter.getFilterMode() == DescriptionFilter.FilterMode.INCLUDE ? "" : " NOT") + " LIKE '"; //NON-NLS
			return "(" + getDescriptionColumn(filter.getDescriptionLoD()) + likeOrNotLike + filter.getDescription() + "'  )"; // NON-NLS
		} else {
			return getTrueLiteral();
		}
	}

	private String getSQLWhere(TagsFilter filter) {
		if (filter.isActive()
				&& (filter.getSubFilters().isEmpty() == false)) {
			String tagNameIDs = filter.getSubFilters().stream()
					.filter((TagNameFilter t) -> t.isSelected() && !t.isDisabled())
					.map((TagNameFilter t) -> String.valueOf(t.getTagName().getId()))
					.collect(Collectors.joining(", ", "(", ")"));
			return "(events.event_id == tags.event_id AND " //NON-NLS
					+ "tags.tag_name_id IN " + tagNameIDs + ") "; //NON-NLS
		} else {
			return getTrueLiteral();
		}

	}

	private String getSQLWhere(HashHitsFilter filter) {
		if (filter.isActive()
				&& (filter.getSubFilters().isEmpty() == false)) {
			String hashSetIDs = filter.getSubFilters().stream()
					.filter((HashSetFilter t) -> t.isSelected() && !t.isDisabled())
					.map((HashSetFilter t) -> String.valueOf(t.getHashSetID()))
					.collect(Collectors.joining(", ", "(", ")"));
			return "(hash_set_hits.hash_set_id IN " + hashSetIDs + " AND hash_set_hits.event_id == events.event_id)"; //NON-NLS
		} else {
			return getTrueLiteral();
		}
	}

	private String getSQLWhere(DataSourceFilter filter) {
		if (filter.isActive()) {
			return "(datasource_id = '" + filter.getDataSourceID() + "')"; //NON-NLS
		} else {
			return getTrueLiteral();
		}
	}

	private String getSQLWhere(DataSourcesFilter filter) {
		return (filter.isActive()) ? "(datasource_id in (" //NON-NLS
				+ filter.getSubFilters().stream()
						.filter(AbstractFilter::isActive)
						.map((dataSourceFilter) -> String.valueOf(dataSourceFilter.getDataSourceID()))
						.collect(Collectors.joining(", ")) + "))" : getTrueLiteral();
	}

	private String getSQLWhere(TextFilter filter) {
		if (filter.isActive()) {
			if (org.apache.commons.lang3.StringUtils.isBlank(filter.getText())) {
				return getTrueLiteral();
			}
			String strippedFilterText = org.apache.commons.lang3.StringUtils.strip(filter.getText());
			return "((med_description like '%" + strippedFilterText + "%')" //NON-NLS
					+ " or (full_description like '%" + strippedFilterText + "%')" //NON-NLS
					+ " or (short_description like '%" + strippedFilterText + "%'))"; //NON-NLS
		} else {
			return getTrueLiteral();
		}
	}

	/**
	 * generate a sql where clause for the given type filter, while trying to be
	 * as simple as possible to improve performance.
	 *
	 * @param typeFilter
	 *
	 * @return
	 */
	private String getSQLWhere(TypeFilter typeFilter) {
		if (typeFilter.isSelected() == false) {
			return getFalseLiteral();
		} else if (typeFilter.getEventType() instanceof RootEventType) {
			if (typeFilter.getSubFilters().stream()
					.allMatch(subFilter -> subFilter.isActive() && subFilter.getSubFilters().stream().allMatch(Filter::isActive))) {
				return getTrueLiteral(); //then collapse clause to true
			}
		}
		return "(sub_type IN (" + org.apache.commons.lang3.StringUtils.join(getActiveSubTypes(typeFilter), ",") + "))"; //NON-NLS
	}

	private List<Integer> getActiveSubTypes(TypeFilter filter) {
		if (filter.isActive()) {
			if (filter.getSubFilters().isEmpty()) {
				return Collections.singletonList(RootEventType.allTypes.indexOf(filter.getEventType()));
			} else {
				return filter.getSubFilters().stream().flatMap((Filter t) -> getActiveSubTypes((TypeFilter) t).stream()).collect(Collectors.toList());
			}
		} else {
			return Collections.emptyList();
		}
	}

	/**
	 * get a sqlite strftime format string that will allow us to group by the
	 * requested period size. That is, with all info more granular than that
	 * requested dropped (replaced with zeros).
	 *
	 * @param timeUnit the {@link TimeUnits} instance describing what
	 *                 granularity to build a strftime string for
	 *
	 * @return a String formatted according to the sqlite strftime spec
	 *
	 * @see https://www.sqlite.org/lang_datefunc.html
	 */
	String getStrfTimeFormat(TimeUnits timeUnit) {
		switch (timeUnit) {
			case YEARS:
				return "%Y-01-01T00:00:00"; // NON-NLS
			case MONTHS:
				return "%Y-%m-01T00:00:00"; // NON-NLS
			case DAYS:
				return "%Y-%m-%dT00:00:00"; // NON-NLS
			case HOURS:
				return "%Y-%m-%dT%H:00:00"; // NON-NLS
			case MINUTES:
				return "%Y-%m-%dT%H:%M:00"; // NON-NLS
			case SECONDS:
			default:    //seconds - should never happen
				return "%Y-%m-%dT%H:%M:%S"; // NON-NLS  
			}
	}

	String getDescriptionColumn(DescriptionLoD lod) {
		switch (lod) {
			case FULL:
				return "full_description"; //NON-NLS
			case MEDIUM:
				return "med_description"; //NON-NLS
			case SHORT:
			default:
				return "short_description"; //NON-NLS
			}
	}

	private String getFalseLiteral() {
		return sleuthkitCase.getDatabaseType() == TskData.DbType.POSTGRESQL ? "FALSE" : "0";
	}

	private String getTrueLiteral() {
		return sleuthkitCase.getDatabaseType() == TskData.DbType.POSTGRESQL ? "TRUE" : "1";
	}
}
