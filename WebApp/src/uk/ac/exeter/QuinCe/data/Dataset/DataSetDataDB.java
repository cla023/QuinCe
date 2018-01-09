package uk.ac.exeter.QuinCe.data.Dataset;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import uk.ac.exeter.QuinCe.data.Calculation.CalculationDB;
import uk.ac.exeter.QuinCe.data.Calculation.CalculationDBFactory;
import uk.ac.exeter.QuinCe.data.Instrument.Instrument;
import uk.ac.exeter.QuinCe.data.Instrument.InstrumentDB;
import uk.ac.exeter.QuinCe.data.Instrument.InstrumentException;
import uk.ac.exeter.QuinCe.data.Instrument.RunTypes.NoSuchCategoryException;
import uk.ac.exeter.QuinCe.data.Instrument.RunTypes.RunTypeCategory;
import uk.ac.exeter.QuinCe.data.Instrument.SensorDefinition.SensorAssignments;
import uk.ac.exeter.QuinCe.data.Instrument.SensorDefinition.SensorType;
import uk.ac.exeter.QuinCe.data.Instrument.SensorDefinition.SensorsConfiguration;
import uk.ac.exeter.QuinCe.utils.DatabaseException;
import uk.ac.exeter.QuinCe.utils.DatabaseUtils;
import uk.ac.exeter.QuinCe.utils.DateTimeUtils;
import uk.ac.exeter.QuinCe.utils.MissingParam;
import uk.ac.exeter.QuinCe.utils.MissingParamException;
import uk.ac.exeter.QuinCe.utils.RecordNotFoundException;
import uk.ac.exeter.QuinCe.web.system.ResourceManager;

/**
 * Class for handling database queries related to the
 * {@code dataset_data} table
 * @author Steve Jones
 *
 */
public class DataSetDataDB {

	/**
	 * Query to get all measurements for a data set
	 */
	private static final String GET_ALL_MEASUREMENTS_QUERY = "SELECT * FROM dataset_data WHERE dataset_id = ? ORDER BY date ASC";
	
	/**
	 * Query to get all measurements IDs for a data set
	 */
	private static final String GET_ALL_MEASUREMENT_IDS_QUERY = "SELECT id FROM dataset_data WHERE dataset_id = ? ORDER BY date ASC";
	
	/**
	 * Query to get a single measurement
	 */
	private static final String GET_MEASUREMENT_QUERY = "SELECT * FROM dataset_data WHERE id = ?";
	
	/**
	 * The name of the ID column
	 */
	private static final String ID_COL = "id";
	
	/**
	 * The name of the date column
	 */
	private static final String DATE_COL = "date";
	
	/**
	 * The name of the longitude column
	 */
	private static final String LON_COL = "longitude";
	
	/**
	 * The name of the latitude column
	 */
	private static final String LAT_COL = "latitude";
	
	/**
	 * The name of the run type column
	 */
	private static final String RUN_TYPE_COL = "run_type";
	
	/**
	 * The name of the diagnostic values column
	 */
	private static final String DIAGNOSTIC_COL = "diagnostic_values";
	
	/**
	 * The name of the dataset ID column
	 */
	private static final String DATASET_COL = "dataset_id";
	
	/**
	 * Store a data set record in the database.
	 * 
	 * Measurement and calibration records are automatically detected
	 * and stored in the appropriate table.
	 * 
	 * @param conn A database connection
	 * @param record The record to be stored
	 * @param datasetDataStatement A previously generated statement for inserting a record. Can be null.
	 * @return A {@link PreparedStatement} that can be used for storing subsequent records
	 * @throws MissingParamException If any required parameters are missing
	 * @throws DataSetException If a non-measurement record is supplied
	 * @throws DatabaseException If a database error occurs
	 * @throws NoSuchCategoryException If the record's Run Type is not recognised
	 */
	public static PreparedStatement storeRecord(Connection conn, DataSetRawDataRecord record, PreparedStatement datasetDataStatement) throws MissingParamException, DataSetException, DatabaseException, NoSuchCategoryException {
		
		MissingParam.checkMissing(conn, "conn");
		MissingParam.checkMissing(record, "record");

		if (!record.isMeasurement()) {
			throw new DataSetException("Record is not a measurement");
		}
		
		ResultSet createdKeys = null;
		
		try {
			if (null == datasetDataStatement) {
				datasetDataStatement = createInsertRecordStatement(conn, record);
			}
			
			datasetDataStatement.setLong(1, record.getDatasetId());
			datasetDataStatement.setLong(2, DateTimeUtils.dateToLong(record.getDate()));
			datasetDataStatement.setDouble(3, record.getLongitude());
			datasetDataStatement.setDouble(4, record.getLatitude());
			datasetDataStatement.setString(5, record.getRunType());
			datasetDataStatement.setString(6, record.getDiagnosticValuesString());
			
			int currentField = 6;
			SensorsConfiguration sensorConfig = ResourceManager.getInstance().getSensorsConfiguration();
			for (SensorType sensorType : sensorConfig.getSensorTypes()) {
				if (sensorType.isUsedInCalculation()) {
					currentField++;
					Double sensorValue = record.getSensorValue(sensorType.getName());
					if (null == sensorValue) {
						datasetDataStatement.setNull(currentField, Types.DOUBLE);
					} else {
						datasetDataStatement.setDouble(currentField, sensorValue);
					}
				}
			}
			
			datasetDataStatement.execute();
			
			CalculationDB calculationDB = CalculationDBFactory.getCalculationDB();
			
			createdKeys = datasetDataStatement.getGeneratedKeys();
			while (createdKeys.next()) {
				calculationDB.createCalculationRecord(conn, createdKeys.getLong(1));
			}
			
		} catch (SQLException e) {
			throw new DatabaseException("Error storing dataset record", e);
		} finally {
			DatabaseUtils.closeResultSets(createdKeys);
		}
		
		return datasetDataStatement;
	}

	/**
	 * Get a single measurement from the database
	 * @param conn A database connection
	 * @param dataSet The data set to which the measurement belongs
	 * @param measurementId The measurement's database ID
	 * @return The record
     * @throws DatabaseException If a database error occurs
     * @throws MissingParamException If any required parameters are missing
	 * @throws RecordNotFoundException If the measurement does not exist
	 */
	public static DataSetRawDataRecord getMeasurement(Connection conn, DataSet dataSet, long measurementId) throws DatabaseException, MissingParamException, RecordNotFoundException {
		
		MissingParam.checkMissing(conn, "conn");
		MissingParam.checkZeroPositive(measurementId, "measurementId");
		
		PreparedStatement stmt = null;
		ResultSet records = null;

		DataSetRawDataRecord result = null;
		Map<String, Integer> baseColumns = new HashMap<String, Integer>();
		Map<Integer, String> sensorColumns = new HashMap<Integer, String>();

		try {
			stmt = conn.prepareStatement(GET_MEASUREMENT_QUERY);
			stmt.setLong(1, measurementId);
			records = stmt.executeQuery();
		
			if (!records.next()) {
				throw new RecordNotFoundException("Measurement data not found", "dataset_data", measurementId);
			} else {
				result = getRecordFromResultSet(conn, dataSet, records, baseColumns, sensorColumns);
			}
		} catch (Exception e) {
			throw new DatabaseException("Error while retrieving measurement data", e);
		} finally {
			DatabaseUtils.closeResultSets(records);
			DatabaseUtils.closeStatements(stmt);
		}

		return result;
	}
	
	
	/**
	 * Get measurement records for a dataset
	 * @param dataSource A data source
	 * @param dataSet The data set
	 * @param start The first record to retrieve
	 * @param length The number of records to retrieve
	 * @return The measurement records
     * @throws DatabaseException If a database error occurs
     * @throws MissingParamException If any required parameters are missing
	 */
	public static List<DataSetRawDataRecord> getMeasurements(DataSource dataSource, DataSet dataSet, int start, int length) throws DatabaseException, MissingParamException {
		MissingParam.checkMissing(dataSource, "dataSource");
		MissingParam.checkMissing(dataSet, "dataSet");
		
		Connection conn = null;
		
		try {
			conn = dataSource.getConnection();
			return getMeasurements(conn, dataSet, start, length);
		} catch (SQLException e) {
			throw new DatabaseException("Error while getting measurement IDs", e);
		} finally {
			DatabaseUtils.closeConnection(conn);
		}
	}	
		
		
	/**
	 * Get all measurement records for a dataset
	 * @param conn A database connection
	 * @param dataSet The data set
	 * @return The measurement records
     * @throws DatabaseException If a database error occurs
     * @throws MissingParamException If any required parameters are missing
	 */
	public static List<DataSetRawDataRecord> getMeasurements(Connection conn, DataSet dataSet) throws DatabaseException, MissingParamException {
		return getMeasurements(conn, dataSet, -1, -1);
	}
	
	/**
	 * Get measurement records for a dataset
	 * @param conn A database connection
	 * @param dataSet The data set
	 * @param start The first record to retrieve
	 * @param length The number of records to retrieve
	 * @return The measurement records
     * @throws DatabaseException If a database error occurs
     * @throws MissingParamException If any required parameters are missing
	 */
	public static List<DataSetRawDataRecord> getMeasurements(Connection conn, DataSet dataSet, int start, int length) throws DatabaseException, MissingParamException {
		
		MissingParam.checkMissing(conn, "conn");
		MissingParam.checkMissing(dataSet, "dataSet");
		
		PreparedStatement stmt = null;
		ResultSet records = null;
		
		List<DataSetRawDataRecord> result = new ArrayList<DataSetRawDataRecord>();
		
		Map<String, Integer> baseColumns = new HashMap<String, Integer>();
		Map<Integer, String> sensorColumns = new HashMap<Integer, String>();
		
		try {
			StringBuilder query = new StringBuilder(GET_ALL_MEASUREMENTS_QUERY);
			if (length > 0) {
				query.append(" LIMIT ");
				query.append(start);
				query.append(',');
				query.append(length);
			}
			
			stmt = conn.prepareStatement(query.toString());
			stmt.setLong(1, dataSet.getId());
			
			records = stmt.executeQuery();
			
			while (records.next()) {
				result.add(getRecordFromResultSet(conn, dataSet, records, baseColumns, sensorColumns));
			}
			
			return result;
			
		} catch (Exception e) {
			throw new DatabaseException("Error while retrieving measurements", e);
		} finally {
			DatabaseUtils.closeResultSets(records);
			DatabaseUtils.closeStatements(stmt);
		}
	}
	
	/**
	 * Read a record from a ResultSet
	 * @param dataSet The data set to which the record belongs
	 * @param records The result set
	 * @param baseColumns The column indices for the base columns
	 * @param sensorColumns The column indices for the sensor columns
	 * @return The record
     * @throws MissingParamException If any required parameters are missing
     * @throws SQLException If the record details cannot be extracted
	 * @throws DataSetException If the diagnostic values cannot be read
	 * @throws InstrumentException If the instrument details cannot be retrieved
	 * @throws RecordNotFoundException If the instrument does not exist
	 * @throws DatabaseException If a database error occurs
     * 
	 */
	private static DataSetRawDataRecord getRecordFromResultSet(Connection conn, DataSet dataSet, ResultSet records, Map<String, Integer> baseColumns, Map<Integer, String> sensorColumns) throws MissingParamException, SQLException, DataSetException, DatabaseException, RecordNotFoundException, InstrumentException {
		
		MissingParam.checkMissing(records, "records");
		MissingParam.checkMissing(baseColumns, "baseColumns", true);
		MissingParam.checkMissing(sensorColumns, "sensorColumns", true);
		
		DataSetRawDataRecord result = null;
		
		// Get the column indices if we haven't already got them
		if (baseColumns.size() == 0) {
			ResultSetMetaData rsmd = records.getMetaData();
			ResourceManager resourceManager = ResourceManager.getInstance();
			Instrument instrument = InstrumentDB.getInstrument(conn, dataSet.getInstrumentId(), resourceManager.getSensorsConfiguration(), resourceManager.getRunTypeCategoryConfiguration());
			SensorAssignments sensorAssignments = instrument.getSensorAssignments();
			calculateColumnIndices(rsmd, sensorAssignments, baseColumns, sensorColumns);
		}
		
		long id = records.getLong(baseColumns.get(ID_COL));
		LocalDateTime date = DateTimeUtils.longToDate(records.getLong(baseColumns.get(DATE_COL)));
		double longitude = records.getDouble(baseColumns.get(LON_COL));
		double latitude = records.getDouble(baseColumns.get(LAT_COL));
		String runType = records.getString(baseColumns.get(RUN_TYPE_COL));
		RunTypeCategory runTypeCategory = ResourceManager.getInstance().getRunTypeCategoryConfiguration().getCategory(runType);
		String diagnosticValues = records.getString(baseColumns.get(DIAGNOSTIC_COL));
		
		result = new DataSetRawDataRecord(dataSet, id, date, longitude, latitude, runType, runTypeCategory);
		result.setDiagnosticValues(diagnosticValues);
		
		for (Map.Entry<Integer, String> entry : sensorColumns.entrySet()) {
			Double value = records.getDouble(entry.getKey());
			if (records.wasNull()) {
			  value = null;
			}
			
			result.setSensorValue(entry.getValue(), value);
		}
		
		return result;
	}
	
	/**
	 * Calculate all the required column indices for extracting a data set record
	 * @param rsmd The metadata of the query that's been run
	 * @param baseColumns The mapping of base columns
	 * @param sensorColumns The mapping of sensor columns
	 * @throws SQLException If the column details cannot be read
	 */
	private static void calculateColumnIndices(ResultSetMetaData rsmd, SensorAssignments sensorAssignments, Map<String, Integer> baseColumns, Map<Integer, String> sensorColumns) throws SQLException {
		SensorsConfiguration sensorConfig = ResourceManager.getInstance().getSensorsConfiguration();

		for (int i = 1; i <= rsmd.getColumnCount(); i++) {
			String columnName = rsmd.getColumnName(i);
			switch (columnName) {
			case ID_COL: {
				baseColumns.put(ID_COL, i);
				break;
			}
			case DATE_COL: {
				baseColumns.put(DATE_COL, i);
				break;
			}
			case LON_COL: {
				baseColumns.put(LON_COL, i);
				break;
			}
			case LAT_COL: {
				baseColumns.put(LAT_COL, i);
				break;
			}
			case RUN_TYPE_COL: {
				baseColumns.put(RUN_TYPE_COL, i);
				break;
			}
			case DIAGNOSTIC_COL: {
				baseColumns.put(DIAGNOSTIC_COL, i);
				break;
			}
			case DATASET_COL: {
				// Do nothing
				break;
			}
			default: {
				// This is a sensor field. Get the sensor name from the sensors configuration
				for (SensorType sensorType : sensorConfig.getSensorTypes()) {
					if (sensorType.isUsedInCalculation() && sensorAssignments.get(sensorType).size() > 0) {
						if (columnName.equals(sensorType.getDatabaseFieldName())) {
							sensorColumns.put(i, sensorType.getName());
							break;
						}
					}
				}
			}
			}
		}
	}

	/**
	 * Create a statement to insert a new dataset record in the database
	 * @param conn A database connection
	 * @param record A dataset record
	 * @return The statement
	 * @throws MissingParamException If any required parameters are missing
	 * @throws SQLException If the statement cannot be created
	 */
	private static PreparedStatement createInsertRecordStatement(Connection conn, DataSetRawDataRecord record) throws MissingParamException, SQLException {
		
		List<String> fieldNames = new ArrayList<String>();
				
		fieldNames.add("dataset_id");
		fieldNames.add("date");
		fieldNames.add("longitude");
		fieldNames.add("latitude");
		fieldNames.add("run_type");
		fieldNames.add("diagnostic_values");

		SensorsConfiguration sensorConfig = ResourceManager.getInstance().getSensorsConfiguration();
		for (SensorType sensorType : sensorConfig.getSensorTypes()) {
			if (sensorType.isUsedInCalculation()) {
				fieldNames.add(sensorType.getDatabaseFieldName());
			}
		}
		
		return DatabaseUtils.createInsertStatement(conn, "dataset_data", fieldNames, Statement.RETURN_GENERATED_KEYS);
	}
	
	/**
	 * Get the IDs of all the measurements for a given data set
	 * @param dataSource A data source
	 * @param datasetId The dataset ID
	 * @return The measurement IDs
     * @throws DatabaseException If a database error occurs
     * @throws MissingParamException If any required parameters are missing
	 * @throws RecordNotFoundException If no measurements are found
	 */
	public static List<Long> getMeasurementIds(DataSource dataSource, long datasetId) throws MissingParamException, DatabaseException, RecordNotFoundException {
		MissingParam.checkMissing(dataSource, "dataSource");
		MissingParam.checkZeroPositive(datasetId, "datasetId");
		
		Connection conn = null;
		
		try {
			conn = dataSource.getConnection();
			return getMeasurementIds(conn, datasetId);
		} catch (SQLException e) {
			throw new DatabaseException("Error while getting measurement IDs", e);
		} finally {
			DatabaseUtils.closeConnection(conn);
		}
	}
	
	/**
	 * Get the IDs of all the measurements for a given data set
	 * @param conn A database connection
	 * @param datasetId The dataset ID
	 * @return The measurement IDs
     * @throws DatabaseException If a database error occurs
     * @throws MissingParamException If any required parameters are missing
	 * @throws RecordNotFoundException If no measurements are found
	 */
	public static List<Long> getMeasurementIds(Connection conn, long datasetId) throws MissingParamException, DatabaseException, RecordNotFoundException {
		
		MissingParam.checkMissing(conn, "conn");
		MissingParam.checkZeroPositive(datasetId, "datasetId");
		
		PreparedStatement stmt = null;
		ResultSet records = null;
		
		List<Long> ids = new ArrayList<Long>();
		
		try {
			stmt = conn.prepareStatement(GET_ALL_MEASUREMENT_IDS_QUERY);
			stmt.setLong(1, datasetId);
			
			records = stmt.executeQuery();
			
			while(records.next()) {
				ids.add(records.getLong(1));
			}
			
		} catch (SQLException e) {
			throw new DatabaseException("Error while getting measurement IDs", e);
		} finally {
			DatabaseUtils.closeResultSets(records);
			DatabaseUtils.closeStatements(stmt);
		}
		
		if (ids.size() == 0) {
			throw new RecordNotFoundException("No records found for dataset " + datasetId);
		}
		
		return ids;
	}
	
	/**
	 * Get the list of columns names for a raw dataset record
	 * @param dataSource A data source
	 * @param dataSet The data set
	 * @return The column names
     * @throws DatabaseException If a database error occurs
     * @throws MissingParamException If any required parameters are missing
     * @throws InstrumentException If the instrument details cannot be retrieved
     * @throws RecordNotFoundException If the instrument for the data set does not exist
 	 */
	public static List<String> getDatasetDataColumnNames(DataSource dataSource, DataSet dataSet) throws MissingParamException, DatabaseException, InstrumentException, RecordNotFoundException {
		MissingParam.checkMissing(dataSource, "dataSource");
		
		List<String> result = new ArrayList<String>();
		
		SensorsConfiguration sensorConfig = ResourceManager.getInstance().getSensorsConfiguration();
		Connection conn = null;
		ResultSet columns = null;
		
		try {
			conn = dataSource.getConnection();

			ResourceManager resourceManager = ResourceManager.getInstance();
			Instrument instrument = InstrumentDB.getInstrument(conn, dataSet.getInstrumentId(), resourceManager.getSensorsConfiguration(), resourceManager.getRunTypeCategoryConfiguration());
			SensorAssignments sensorAssignments = instrument.getSensorAssignments();
			
			DatabaseMetaData metadata = conn.getMetaData();
			columns = metadata.getColumns(null, null, "dataset_data", null);
			
			while (columns.next()) {
				String columnName = columns.getString(4);
				
				switch (columnName) {
				case "id": {
					result.add("ID");
					break;
				}
				case "date": {
					result.add("Date");
					break;
				}
				case "longitude": {
					result.add("Longitude");
					break;
				}
				case "latitude": {
					result.add("Latitude");
					break;
				}
				case "dataset_id":
				case "run_type": {
					// Ignored
					break;
				}
				case "diagnostic_values": {
					// TODO Add these
					break;
				}
				default: {
					// Sensor value columns
					for (SensorType sensorType : sensorConfig.getSensorTypes()) {
						if (sensorAssignments.get(sensorType).size() > 0) {
							if (columnName.equals(sensorType.getDatabaseFieldName())) {
								result.add(sensorType.getName());
								break;
							}
						}
					}
					
					break;
				}
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("Error while getting column names", e);
		} finally {
			DatabaseUtils.closeConnection(conn);
		}
		
		return result;
	}
}
