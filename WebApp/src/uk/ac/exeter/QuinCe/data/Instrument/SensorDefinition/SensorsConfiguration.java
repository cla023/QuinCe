package uk.ac.exeter.QuinCe.data.Instrument.SensorDefinition;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import uk.ac.exeter.QuinCe.utils.FileUtils;
import uk.ac.exeter.QuinCe.utils.StringFormatException;
import uk.ac.exeter.QuinCe.utils.StringUtils;

/**
 * 
 * @author Steve Jones
 *
 */
public class SensorsConfiguration {

	/**
	 * The number of columns in the configuration file
	 */
	private static final int COL_COUNT = 5;
	
	/**
	 * The column containing the sensor type name
	 */
	private static final int COL_NAME = 0;
	
	/**
	 * The column defining whether or not the sensor type is required
	 */
	private static final int COL_REQUIRED = 1;
	
	/**
	 * The column containing the Required Group for the sensor
	 */
	private static final int COL_REQUIRED_GROUP = 2;
	
	/**
	 * The column naming another sensor type that this sensor relies on
	 */
	private static final int COL_DEPENDS_ON = 3;
	
	/**
	 * The column specifying whether or not multiple sensors of this type are permitted
	 */
	private static final int COL_MULTIPLE = 4;
	
	/**
	 * The set of sensors defined for the instrument with
	 * the data file columns assigned to them
	 */
	private List<SensorType> sensorTypes;
	
	/**
	 * Create an empty sensor configuration (with no assigned columns)
	 * based on the specified configuration file
	 * @param configFile The configuration file
	 * @throws SensorConfigurationException If the configuration is invalid
	 */
	public SensorsConfiguration(File configFile) throws SensorConfigurationException {
		
		if (!FileUtils.canAccessFile(configFile)) {
			throw new SensorConfigurationException("Cannot access config file '" + configFile.getAbsolutePath() + "'");
		}
		
		buildSensorTypes(configFile);
	}
	
	/**
	 * Get an empty map of sensor types ready to have columns assigned 
	 * @return An empty sensor types/assignments map
	 */
	public SensorAssignments getNewSensorAssigments() {
		return new SensorAssignments(sensorTypes);
	}
	
	/**
	 * Build the map of sensor configurations from the supplied
	 * configuration file. All map entries will contain {@code null}
	 * to indicate that no assignments have been made.
	 * @param configFile The configuration file
	 * @throws SensorConfigurationException If the configuration is invalid
	 */
	private void buildSensorTypes(File configFile) throws SensorConfigurationException {
		
		sensorTypes = new ArrayList<SensorType>();
		BufferedReader reader = null; 
		try {
			reader = new BufferedReader(new FileReader(configFile));
			String line = reader.readLine();
			int lineCount = 1;
			
			while (null != line) {
				if (!StringUtils.isComment(line)) {
					List<String> fields = StringUtils.trimList(Arrays.asList(line.split(",")));
					
					if (fields.size() != COL_COUNT) {
						throw new SensorConfigurationException(lineCount, "Incorrect number of columns");
					} else {
						try {
							String sensorName = fields.get(COL_NAME);
							if (sensorTypeDefined(sensorName)) {
								throw new SensorConfigurationException(lineCount, "Sensor name '" + sensorName + "' is already defined");
							}
							
							boolean required = StringUtils.parseYNBoolean(fields.get(COL_REQUIRED));
							
							String requiredGroup = fields.get(COL_REQUIRED_GROUP);
							if (requiredGroup.length() == 0) {
								requiredGroup = null;
							}
							
							String dependsOn = fields.get(COL_DEPENDS_ON);
							if (dependsOn.length() == 0) {
								dependsOn = null;
							}
							
							boolean multipleAllowed = StringUtils.parseYNBoolean(fields.get(COL_MULTIPLE));
							
							SensorType sensor = new SensorType(sensorName, required, requiredGroup, dependsOn, multipleAllowed);
							
							sensorTypes.add(sensor);
							
						} catch (StringFormatException e) {
							throw new SensorConfigurationException(lineCount, e.getMessage());
						}
					}
				}
				
				line = reader.readLine();
				lineCount++;
			}
			
			checkDependsOnConfiguration();
		} catch (IOException e) {
			throw new SensorConfigurationException("Error while reading config file", e);
		} finally {
			if (null != reader) {
				try {
					reader.close();
				} catch (IOException e) {
					// We tried.
				}
			}
		}
	}
	
	/**
	 * Check the sensor types to ensure that all "Depends On" configurations reference
	 * sensor types that exist
	 * @throws SensorConfigurationException If a "Depends On" references a sensor type that doesn't exist
	 */
	private void checkDependsOnConfiguration() throws SensorConfigurationException {
		for (SensorType type: sensorTypes) {
			String dependsOn = type.getDependsOn();
			if (null != dependsOn && !sensorTypeDefined(dependsOn)) {
				throw new SensorConfigurationException("Sensor type '" + type.getName() + "' depends on '" + dependsOn + "', but that sensor type is not configured");
			}
		}
	}
	
	/**
	 * Determine whether or not a sensor with a given name has
	 * already been added to the map. The comparison is case insensitive.
	 * @param name The sensor name
	 * @return {@code true} if the sensor name already exists in the map; {@code false} if it does not.
	 */
	private boolean sensorTypeDefined(String name) {
		boolean foundSensor = false;
		
		for (SensorType sensorType : sensorTypes) {
			if (sensorType.getName().equalsIgnoreCase(name)) {
				foundSensor = true;
				break;
			}
		}
		
		return foundSensor;
	}
}
