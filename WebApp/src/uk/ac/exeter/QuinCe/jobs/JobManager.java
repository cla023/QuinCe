package uk.ac.exeter.QuinCe.jobs;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import javax.sql.DataSource;

import uk.ac.exeter.QuinCe.data.User;
import uk.ac.exeter.QuinCe.database.DatabaseException;
import uk.ac.exeter.QuinCe.database.DatabaseUtils;
import uk.ac.exeter.QuinCe.database.RecordNotFoundException;
import uk.ac.exeter.QuinCe.database.User.NoSuchUserException;
import uk.ac.exeter.QuinCe.database.User.UserDB;
import uk.ac.exeter.QuinCe.database.files.DataFileDB;
import uk.ac.exeter.QuinCe.jobs.files.FileJob;
import uk.ac.exeter.QuinCe.utils.MissingParam;
import uk.ac.exeter.QuinCe.utils.MissingParamException;
import uk.ac.exeter.QuinCe.utils.StringFormatException;
import uk.ac.exeter.QuinCe.utils.StringUtils;
import uk.ac.exeter.QuinCe.web.system.ResourceManager;

/**
 * This class provides methods to manage the job queue
 * 
 * @author Steve Jones
 *
 */
public class JobManager {

	/**
	 * Indicates that a supplied job class passes all tests to ensure it is a valid job class
	 */
	protected static final int CLASS_CHECK_OK = 0;
	
	/**
	 * Indicates that a supplied job class cannot be found
	 */
	protected static final int CLASS_CHECK_NO_SUCH_CLASS = 1;
	
	/**
	 * Indicates that a supplied job class does not extend the root {@link Job} class
	 */
	protected static final int CLASS_CHECK_NOT_JOB_CLASS = 2;
	
	/**
	 * Indicates that the supplied job class does not have a valid constructor
	 */
	protected static final int CLASS_CHECK_INVALID_CONSTRUCTOR = 3;
	
	/**
	 * Indicates that a job has no owner
	 */
	private static final int NO_OWNER = -999;
	
	/**
	 * SQL statement to create a job record
	 */
	private static final String CREATE_JOB_STATEMENT = "INSERT INTO job (owner, submitted, class, parameters) VALUES (?, ?, ?, ?)";
	
	/**
	 * SQL statement to see if a job with a given ID exists
	 */
	private static final String FIND_JOB_QUERY = "SELECT COUNT(*) FROM job WHERE id = ?";
	
	/**
	 * SQL statement for setting a job's status
	 */
	private static final String SET_STATUS_STATEMENT = "UPDATE job SET status = ? WHERE id = ?";
	
	/**
	 * SQL statement for setting a job's progress
	 */
	private static final String SET_PROGRESS_STATEMENT = "UPDATE job SET progress = ? WHERE id = ?";
	
	/**
	 * SQL statement for recording that a job has started
	 */
	private static final String START_JOB_STATEMENT = "UPDATE job SET status = '" + Job.RUNNING_STATUS + "', started = ?, thread_name = ? WHERE id = ?";
	
	/**
	 * SQL statement for recording that a job has completed
	 */
	private static final String END_JOB_STATEMENT = "UPDATE job SET status = '" + Job.FINISHED_STATUS + "', ended = ?, progress = 100, thread_name = NULL WHERE id = ?";
	
	/**
	 * SQL statement for recording that a job has been killed
	 */
	private static final String KILL_JOB_STATEMENT = "UPDATE job SET status = '" + Job.KILLED_STATUS + "', ended = ? WHERE id = ?";
	
	/**
	 * SQL statement for recording that a job has failed with an error
	 */
	private static final String ERROR_JOB_STATEMENT = "UPDATE job SET status = '" + Job.ERROR_STATUS + "', ended = ?, stack_trace = ? WHERE id = ?";
		
	/**
	 * SQL statement to retrieve a job's class and paremeters
	 */
	private static final String GET_JOB_QUERY = "SELECT id, class, parameters FROM job WHERE id = ?";
	
	/**
	 * SQL statement to retrieve the next queued job
	 */
	private static final String GET_NEXT_JOB_QUERY = "SELECT id, class, parameters FROM job WHERE status='WAITING' ORDER BY submitted ASC LIMIT 1";
	
	/**
	 * Statement to get the number of jobs of each status
	 */
	private static final String GET_JOB_COUNTS_QUERY = "SELECT status, COUNT(status) FROM job GROUP BY status";
	
	private static final String JOB_LIST_QUERY = "SELECT id, owner, class, submitted, status, started, ended, progress, stack_trace FROM job ORDER BY submitted DESC";
	
	private static final String GET_JOB_STATUS_QUERY = "SELECT status FROM job WHERE id = ?";
	
	private static final String GET_JOB_OWNER_QUERY = "SELECT owner FROM job WHERE id = ?";

	private static final String REQUEUE_JOB_STATEMENT = "UPDATE job SET "
			+ "status = 'WAITING', started = NULL, ended = NULL, thread_name = NULL, progress = 0, "
			+ "stack_trace = NULL WHERE id = ?";
	
	private static final String GET_RUNNING_THREAD_NAMES_STATEMENT = "SELECT id, thread_name FROM job WHERE status = 'RUNNING'";
	
	private static final String GET_QUEUED_RUNNING_JOBS_QUERY = "SELECT id, class, parameters FROM job WHERE status = 'WAITING' OR status = 'RUNNING'";
	
	private static final String DELETE_OLD_FINISHED_JOBS_STATEMENT = "DELETE FROM job WHERE status = 'FINISHED' AND ended < (NOW() - INTERVAL ? DAY)";
	
	/**
	 * Adds a job to the database
	 * @param dataSource A data source
	 * @param owner The job's owner (can be {@code null}
	 * @param jobClass The class name of the job to be run
	 * @param parameters The parameters of the job
	 * @throws Exception 
	 * @throws DatabaseException If a database error occurs
	 * @throws MissingParamException Generated by internal checks - should never be thrown
	 * @throws NoSuchUserException If the supplied user does not exist in the database
	 * @throws JobClassNotFoundException If the specified job class does not exist
	 * @throws InvalidJobClassTypeException If the specified job class is not of the correct type
	 * @throws InvalidJobConstructorException If the specified job class does not have the correct constructor
	 * @throws JobException If an unknown problem is found with the specified job class
	 */
	public static long addJob(DataSource dataSource, User owner, String jobClass, Map<String,String> parameters) throws DatabaseException, MissingParamException, NoSuchUserException, JobClassNotFoundException, InvalidJobClassTypeException, InvalidJobConstructorException, JobException {
		
		long result = -1;
		Connection conn = null;
		
		try {
			conn = dataSource.getConnection();
			result = addJob(conn, owner, jobClass, parameters);
		} catch (SQLException e) {
			throw new DatabaseException("An error occurred while adding the job", e);
		} catch (DatabaseException|MissingParamException|NoSuchUserException|JobClassNotFoundException|InvalidJobClassTypeException|InvalidJobConstructorException|JobException e) {
			throw e;
		} finally {
			DatabaseUtils.closeConnection(conn);
		}
		
		return result;
	}
	
	/**
	 * Adds a job to the database
	 * @param conn A database connection
	 * @param owner The job's owner (can be {@code null}
	 * @param jobClass The class name of the job to be run
	 * @param parameters The parameters of the job
	 * @throws DatabaseException If a database error occurs
	 * @throws MissingParamException Generated by internal checks - should never be thrown
	 * @throws NoSuchUserException If the supplied user does not exist in the database
	 * @throws JobClassNotFoundException If the specified job class does not exist
	 * @throws InvalidJobClassTypeException If the specified job class is not of the correct type
	 * @throws InvalidJobConstructorException If the specified job class does not have the correct constructor
	 * @throws JobException If an unknown problem is found with the specified job class
	 */
	public static long addJob(Connection conn, User owner, String jobClass, Map<String,String> parameters) throws DatabaseException, MissingParamException, NoSuchUserException, JobClassNotFoundException, InvalidJobClassTypeException, InvalidJobConstructorException, JobException {
		
		MissingParam.checkMissing(conn, "conn");
		MissingParam.checkMissing(owner, "owner");
		MissingParam.checkMissing(jobClass, "jobClass");
		MissingParam.checkMissing(parameters, "parameters");

		long addedID = DatabaseUtils.NO_DATABASE_RECORD;
		
		// Get the user's ID
		int ownerID = NO_OWNER;

		if (null != owner) {
			// Check that the user exists
			if (null == UserDB.getUser(conn, owner.getEmailAddress())) {
				throw new NoSuchUserException(owner);
			}
			ownerID = owner.getDatabaseID();
		}
		
		// Check that the job class exists
		int classCheck = checkJobClass(jobClass);
		
		switch (classCheck) {
		case CLASS_CHECK_OK: {
			
			// If this is a File Job, check that the file (a) exists and (b)
			// hasn't been marked for deletion
			long fileId = -1;
			try {
				if (isFileJob(jobClass)) {
					fileId = Long.parseLong(parameters.get(FileJob.FILE_ID_KEY));
					if (!DataFileDB.fileExists(conn, fileId) || DataFileDB.getDeleteFlag(conn, fileId)) {
						throw new JobException("Data file with ID " + fileId + " does not exist or is marked for deletion. Job cannot be queued.");
					}
				}
			} catch (RecordNotFoundException e) {
				throw new JobException("Data file with ID " + fileId + " does not exist or is marked for deletion. Job cannot be queued.");
			}
			
			
			PreparedStatement stmt = null;
			ResultSet generatedKeys = null;

			try {
				stmt = conn.prepareStatement(CREATE_JOB_STATEMENT, Statement.RETURN_GENERATED_KEYS);
				if (NO_OWNER == ownerID) {
					stmt.setNull(1, java.sql.Types.INTEGER);
				} else {
					stmt.setInt(1, ownerID);
				}
				
				stmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
				stmt.setString(3, jobClass);
				stmt.setString(4, StringUtils.mapToDelimited(parameters));
				
				stmt.execute();
				
				generatedKeys = stmt.getGeneratedKeys();
				if (generatedKeys.next()) {
					addedID = generatedKeys.getLong(1);
				}
			} catch(SQLException e) {
				throw new DatabaseException("An error occurred while storing the job", e);
			} finally {
				DatabaseUtils.closeResultSets(generatedKeys);
				DatabaseUtils.closeStatements(stmt);
			}
			
			break;
		}
		case CLASS_CHECK_NO_SUCH_CLASS: {
			throw new JobClassNotFoundException(jobClass);
		}
		case CLASS_CHECK_NOT_JOB_CLASS: {
			throw new InvalidJobClassTypeException(jobClass);
		}
		case CLASS_CHECK_INVALID_CONSTRUCTOR: {
			throw new InvalidJobConstructorException(jobClass);
		}
		default: {
			throw new JobException("Unknown fault with job class '" + jobClass);
		}
		}
		
		return addedID;
	}
	
	/**
	 * Adds a job to the database, and instantly runs it
	 * @param conn A database connection
	 * @param owner The job's owner (can be {@code null}
	 * @param jobClass The class name of the job to be run
	 * @param parameters The parameters of the job
	 * @throws DatabaseException If a database error occurs
	 * @throws MissingParamException Generated by internal checks - should never be thrown
	 * @throws NoSuchUserException If the supplied user does not exist in the database
	 * @throws JobClassNotFoundException If the specified job class does not exist
	 * @throws InvalidJobClassTypeException If the specified job class is not of the correct type
	 * @throws InvalidJobConstructorException If the specified job class does not have the correct constructor
	 * @throws JobException If an unknown problem is found with the specified job class
	 * @throws JobThreadPoolNotInitialisedException If the job thread pool has not been initialised
	 * @throws NoSuchJobException If the job mysteriously vanishes between being created and run
	 * @throws StringFormatException 
	 * @throws SecurityException 
	 */
	public static void addInstantJob(ResourceManager resourceManager, Properties config, User owner, String jobClass, Map<String,String> parameters) throws DatabaseException, MissingParamException, NoSuchUserException, JobClassNotFoundException, InvalidJobClassTypeException, InvalidJobConstructorException, JobException, JobThreadPoolNotInitialisedException, NoSuchJobException, SecurityException, StringFormatException {
		DataSource dataSource = resourceManager.getDBDataSource();
		long jobID = addJob(dataSource, owner, jobClass, parameters);
		JobThread jobThread = JobThreadPool.getInstance().getInstantJobThread(JobManager.getJob(resourceManager, config, jobID));
		try {
			logJobStarted(dataSource.getConnection(), jobID, jobThread.getName());
		} catch (SQLException e) {
			throw new DatabaseException("An error occurred while updating the job status", e);
		}
		jobThread.start();
	}

	/**
	 * Sets the status of a job
	 * @param dataSource A data source
	 * @param jobID The ID of the job whose status is to be set
	 * @param status The status to be set
	 * @throws UnrecognisedStatusException If the supplied status is invalid
	 * @throws NoSuchJobException If the specified job does not exist
	 * @throws DatabaseException If an error occurs while updating the database
	 */
	public static void setStatus(DataSource dataSource, long jobID, String status) throws MissingParamException, UnrecognisedStatusException, NoSuchJobException, DatabaseException {

		MissingParam.checkMissing(dataSource, "dataSource");
		
		try {
			Connection conn = dataSource.getConnection();
			setStatus(conn, jobID, status);
		} catch (SQLException e) {
			throw new DatabaseException("An error occurred while obtaining a database connection", e);
		}
	}

	/**
	 * Sets the status of a job
	 * @param conn A database connection
	 * @param jobID The ID of the job whose status is to be set
	 * @param status The status to be set
	 * @throws UnrecognisedStatusException If the supplied status is invalid
	 * @throws NoSuchJobException If the specified job does not exist
	 * @throws DatabaseException If an error occurs while updating the database
	 */
	private static void setStatus(Connection conn, long jobID, String status) throws MissingParamException, UnrecognisedStatusException, DatabaseException, NoSuchJobException {
		MissingParam.checkMissing(conn, "conn");
		
		if (!checkJobStatus(status)) {
			throw new UnrecognisedStatusException(status);
		}
		
		if (!jobExists(conn, jobID)) {
			throw new NoSuchJobException(jobID);
		}
		
		PreparedStatement stmt = null;
		
		try {
			stmt = conn.prepareStatement(SET_STATUS_STATEMENT);
			stmt.setString(1, status);
			stmt.setLong(2, jobID);
			stmt.execute();
		} catch (SQLException e) {
			throw new DatabaseException("An error occurred while setting the status", e);
		} finally {
			DatabaseUtils.closeStatements(stmt);
		}
	}
	
	/**
	 * Update a job record with the necessary details when it's started. The {@code status} is set to
	 * {@link Job.RUNNING_STATE}, and the {@code started} field is given the current time.
	 * @param conn A database connection
	 * @param jobID The job that has been started
	 * @throws DatabaseException If an error occurs while updating the record
	 * @throws NoSuchJobException If the specified job doesn't exist
	 */
	public static void logJobStarted(Connection conn, long jobID, String threadName) throws MissingParamException, DatabaseException, NoSuchJobException {
		
		MissingParam.checkMissing(conn, "conn");
		
		if (!jobExists(conn, jobID)) {
			throw new NoSuchJobException(jobID);
		}

		PreparedStatement stmt = null;
		
		try {
			stmt = conn.prepareStatement(START_JOB_STATEMENT);
			stmt.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
			stmt.setString(2, threadName);
			stmt.setLong(3, jobID);
			stmt.execute();
		} catch (SQLException e) {
			throw new DatabaseException("An error occurred while setting the job to 'started' state", e);
		} finally {
			DatabaseUtils.closeStatements(stmt);
		}
	}
	
	/**
	 * Retrieve a Job object from the database
	 * @param conn A database connection
	 * @param jobID The job ID
	 * @return A Job object that can be used to run the job
	 * @throws DatabaseException If any errors occurred retrieving the Job object
	 * @throws NoSuchJobException If the specified job does not exist
	 * @throws StringFormatException 
	 * @throws SecurityException 
	 */
	public static Job getJob(ResourceManager resourceManager, Properties config, long jobID) throws MissingParamException, DatabaseException, NoSuchJobException, SecurityException, StringFormatException {
		
		MissingParam.checkMissing(resourceManager, "resourceManager");

		Job job = null;
		Connection connection = null;
		PreparedStatement stmt = null;
		ResultSet result = null;
		
		try {
			DataSource dataSource = resourceManager.getDBDataSource();
			connection = dataSource.getConnection();
			stmt = connection.prepareStatement(GET_JOB_QUERY);
			stmt.setLong(1, jobID);
			
			result = stmt.executeQuery();
			if (!result.next()) {
				throw new NoSuchJobException(jobID);
			} else {
				job = getJobFromResultSet(result, resourceManager, config);
			}
		} catch (SQLException|ClassNotFoundException|NoSuchMethodException|InstantiationException|IllegalAccessException|IllegalArgumentException|InvocationTargetException e) {
			// We handle all exceptions as DatabaseExceptions.
			// The fact is that invalid jobs should never get into the database in the first place.
			throw new DatabaseException("Error while retrieving details for job " + jobID, e);
		} finally {
			DatabaseUtils.closeResultSets(result);
			DatabaseUtils.closeStatements(stmt);
			DatabaseUtils.closeConnection(connection);
		}
		
		return job;
	}
	
	private static Job getJobFromResultSet(ResultSet result, ResourceManager resourceManager, Properties config) throws ClassNotFoundException, SQLException, NoSuchMethodException, SecurityException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, StringFormatException {
		Class<?> jobClazz = Class.forName(result.getString(2));
		Constructor<?> jobConstructor = jobClazz.getConstructor(ResourceManager.class, Properties.class, long.class, Map.class);
		return (Job) jobConstructor.newInstance(resourceManager, config, result.getLong(1), StringUtils.delimitedToMap(result.getString(3)));
	}
	
	/**
	 * Update a job record with the necessary details when it's successfully finshed running. The {@code status} is set to
	 * {@link Job.FINISHED_STATE}, and the {@code ended} field is given the current time.
	 * @param conn A database connection
	 * @param jobID The job that has been started
	 * @throws DatabaseException If an error occurs while updating the record
	 * @throws NoSuchJobException If the specified job doesn't exist
	 */
	public static void logJobFinished(Connection conn, long jobID) throws MissingParamException, DatabaseException, NoSuchJobException {
		
		MissingParam.checkMissing(conn, "conn");
		
		if (!jobExists(conn, jobID)) {
			throw new NoSuchJobException(jobID);
		}

		PreparedStatement stmt = null;
		
		try {
			stmt = conn.prepareStatement(END_JOB_STATEMENT);
			stmt.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
			stmt.setLong(2, jobID);
			stmt.execute();
		} catch (SQLException e) {
			throw new DatabaseException("An error occurred while setting the job to 'finished' state", e);
		} finally {
			DatabaseUtils.closeStatements(stmt);
		}
	}
	
	/**
	 * Update a job record with the necessary details when it's been killed. The {@code status} is set to
	 * {@link Job#KILLED_STATE}, and the {@code ended} field is given the current time.
	 * @param conn A database connection
	 * @param jobID The job that has been started
	 * @throws DatabaseException If an error occurs while updating the record
	 * @throws NoSuchJobException If the specified job doesn't exist
	 */
	public static void logJobKilled(Connection conn, long jobID) throws MissingParamException, DatabaseException, NoSuchJobException {
		
		MissingParam.checkMissing(conn, "conn");
		
		if (!jobExists(conn, jobID)) {
			throw new NoSuchJobException(jobID);
		}

		PreparedStatement stmt = null;
		
		try {
			stmt = conn.prepareStatement(KILL_JOB_STATEMENT);
			stmt.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
			stmt.setLong(2, jobID);
			stmt.execute();
		} catch (SQLException e) {
			throw new DatabaseException("An error occurred while setting the job to 'finished' state", e);
		} finally {
			DatabaseUtils.closeStatements(stmt);
		}
	}
	
	/**
	 * Update a job record indicating that the job failed due to an error
	 * @param conn A database connection
	 * @param jobID The ID of the job
	 * @param error The error that caused the job to fail
	 * @throws DatabaseException If an error occurs while updating the database
	 * @throws NoSuchJobException If the specified job does not exist
	 */
	public static void logJobError(Connection conn, long jobID, Throwable error) throws MissingParamException, DatabaseException, NoSuchJobException {
		
		MissingParam.checkMissing(conn, "conn");
		
		if (!jobExists(conn, jobID)) {
			throw new NoSuchJobException(jobID);
		}

		PreparedStatement stmt = null;
		
		try {
			stmt = conn.prepareStatement(ERROR_JOB_STATEMENT);
			stmt.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
			stmt.setString(2, StringUtils.stackTraceToString(error));
			stmt.setLong(3, jobID);
			stmt.execute();
		} catch (SQLException e) {
			throw new DatabaseException("An error occurred while setting the error state of the job", e);
		} finally {
			DatabaseUtils.closeStatements(stmt);
		}
	}

	/**
	 * Set the progress for a job. The progress must be a percentage (between 0 and 100 inclusive)
	 * @param conn A database connection
	 * @param jobID The ID of the job
	 * @param progress The progress
	 * @throws BadProgressException If the progress value is invalid
	 * @throws NoSuchJobException If the specified job does not exist
	 * @throws DatabaseException If an error occurs while storing the progress in the database
	 */
	public static void setProgress(Connection conn, long jobID, double progress) throws MissingParamException, BadProgressException, NoSuchJobException, DatabaseException {

		MissingParam.checkMissing(conn, "conn");
		MissingParam.checkPositive(jobID, "jobID");
		MissingParam.checkZeroPositive(progress, "progress");
		
		if (progress < 0 || progress > 100) {
			throw new BadProgressException();
		}
		
		if (!jobExists(conn, jobID)) {
			throw new NoSuchJobException(jobID);
		}
		
		PreparedStatement stmt = null;
		
		try {
			stmt = conn.prepareStatement(SET_PROGRESS_STATEMENT);
			stmt.setDouble(1, progress);
			stmt.setLong(2, jobID);
			stmt.execute();
		} catch (SQLException e) {
			throw new DatabaseException("An error occurred while setting the status", e);
		} finally {
			DatabaseUtils.closeStatements(stmt);
		}
	}
	
	/**
	 * Determines whether or not a job with the given ID exists in the database
	 * @param conn A database connection
	 * @param jobID The job ID
	 * @return {@code true} if the job exists; {@code false} otherwise
	 * @throws DatabaseException If an error occurs while searching the database
	 */
	private static boolean jobExists(Connection conn, long jobID) throws MissingParamException, DatabaseException {

		MissingParam.checkMissing(conn, "conn");
		
		boolean jobExists = false;
		
		PreparedStatement stmt = null;
		ResultSet result = null;
		
		try {
			stmt = conn.prepareStatement(FIND_JOB_QUERY);
			stmt.setLong(1, jobID);
			
			result = stmt.executeQuery();
			if (result.next()) {
				if (result.getInt(1) > 0) {
					jobExists = true;
				}
			}
		} catch (SQLException e) {
			throw new DatabaseException("An error occurred while checking for a job's existence", e);
		} finally {
			DatabaseUtils.closeResultSets(result);
			DatabaseUtils.closeStatements(stmt);
		}
		
		return jobExists;
	}
	
	/**
	 * Retrieve the next queued job (i.e. the job with the oldest submission date)
	 * from the database 
	 * @param dataSource A data source
	 * @return The next queued job, or {@code null} if there are no jobs.
	 * @throws MissingParamException If the data source is not supplied
	 * @throws DatabaseException If an error occurs while retrieving details from the database.
	 * @throws StringFormatException 
	 * @throws SecurityException 
	 */
	public static Job getNextJob(ResourceManager resourceManager, Properties config) throws MissingParamException, DatabaseException, SecurityException, StringFormatException {
		
		MissingParam.checkMissing(resourceManager, "resourceManager");

		Job job = null;
		Connection connection = null;
		PreparedStatement stmt = null;
		ResultSet result = null;
		
		try {
			DataSource dataSource = resourceManager.getDBDataSource();
			connection = dataSource.getConnection();
			stmt = connection.prepareStatement(GET_NEXT_JOB_QUERY);
			
			result = stmt.executeQuery();
			if (result.next()) {
				job = getJobFromResultSet(result, resourceManager, config);
			}
		} catch (SQLException|ClassNotFoundException|NoSuchMethodException|InstantiationException|IllegalAccessException|IllegalArgumentException|InvocationTargetException e) {
			// We handle all exceptions as DatabaseExceptions.
			// The fact is that invalid jobs should never get into the database in the first place.
			throw new DatabaseException("Error while retrieving details for next queued job", e);
		} finally {
			DatabaseUtils.closeResultSets(result);
			DatabaseUtils.closeStatements(stmt);
			DatabaseUtils.closeConnection(connection);
		}
		
		return job;
	}
	
	/**
	 * Checks a class name to see if it a valid {@link Job} class
	 * @param jobClass The class name
	 * @return An integer flag containing the result of the check. See {@code CLASS_CHECK_*} fields.
	 */
	protected static int checkJobClass(String jobClass) {
		
		int checkResult = CLASS_CHECK_OK;
		
		try {
			Class<?> jobClazz = Class.forName(jobClass);
			
			// Does it inherit from the job class?
			if (!(Job.class.isAssignableFrom(jobClazz))) {
				checkResult = CLASS_CHECK_NOT_JOB_CLASS;
			} else {
				// Is there a constructor that takes the right parameters?
				// We also check that the List is designated to contain String objects
				Constructor<?> jobConstructor = jobClazz.getConstructor(ResourceManager.class, Properties.class, long.class, Map.class);
				Type[] constructorGenericTypes = jobConstructor.getGenericParameterTypes();
				if (constructorGenericTypes.length != 4) {
					checkResult = CLASS_CHECK_INVALID_CONSTRUCTOR;
				} else {
					if (!(constructorGenericTypes[3] instanceof ParameterizedType)) {
						checkResult = CLASS_CHECK_INVALID_CONSTRUCTOR;
					} else {
						Type[] actualTypeArguments = ((ParameterizedType) constructorGenericTypes[3]).getActualTypeArguments();
						if (actualTypeArguments.length != 2) {
							checkResult = CLASS_CHECK_INVALID_CONSTRUCTOR;
						} else {
							for (int i = 0; i < actualTypeArguments.length; i++) {
								Class<?> typeArgumentClass = (Class<?>) actualTypeArguments[i];
								if (!typeArgumentClass.equals(String.class)) {
									checkResult = CLASS_CHECK_INVALID_CONSTRUCTOR;
								}
							}
						}
					}
				}
			}
		} catch (ClassNotFoundException e) {
			checkResult = CLASS_CHECK_NO_SUCH_CLASS;
		} catch (NoSuchMethodException e) {
			checkResult = CLASS_CHECK_INVALID_CONSTRUCTOR;
		}
		
		return checkResult;
	}
	
	/**
	 * Checks a job status string to make sure it's valid
	 * @param status The status string to be checked
	 * @return {@code true} if the status string is valid; {@code false} otherwise
	 */
	private static boolean checkJobStatus(String status) {
		
		boolean statusOK = false;
		
		if (status.equals(Job.WAITING_STATUS) ||
				status.equals(Job.RUNNING_STATUS) ||
				status.equals(Job.FINISHED_STATUS) ||
				status.equals(Job.ERROR_STATUS) ||
				status.equals(Job.KILLED_STATUS)) {

			statusOK = true;
		}
		
		return statusOK;
	}
	
	/**
	 * Returns a list of all the job statuses in the database, and the number
	 * of jobs with each of those statuses
	 * @param dataSource A data source
	 * @return The list of job statuses and counts
	 * @throws MissingParamException If the dataSource is null
	 * @throws DatabaseException If an error occurs while retrieving the counts
	 */
	public static Map<String,Integer> getJobCounts(DataSource dataSource) throws MissingParamException, DatabaseException {
		
		MissingParam.checkMissing(dataSource, "dataSource");
		
		Map<String,Integer> result = new HashMap<String,Integer>();
		
		Connection conn = null;
		PreparedStatement stmt = null; 
		ResultSet records = null;
		
		try {
			
			conn = dataSource.getConnection();
			stmt = conn.prepareStatement(GET_JOB_COUNTS_QUERY);

			records = stmt.executeQuery();
			while (records.next()) {
				result.put(records.getString(1), records.getInt(2));
			}
			
		} catch (SQLException e) {
			throw new DatabaseException("Exception while retrieving job statistics", e);
		} finally {
			DatabaseUtils.closeResultSets(records);
			DatabaseUtils.closeStatements(stmt);
			DatabaseUtils.closeConnection(conn);
		}
		
		return result;	
	}
	
	public static List<JobSummary> getJobList(DataSource dataSource) throws DatabaseException, MissingParamException {
		
		MissingParam.checkMissing(dataSource, "dataSource");
		
		List<JobSummary> result = new ArrayList<JobSummary>();
		
		Connection conn = null;
		PreparedStatement stmt = null;
		ResultSet records = null;
		
		try {

			conn = dataSource.getConnection();
			stmt = conn.prepareStatement(JOB_LIST_QUERY);
			
			records = stmt.executeQuery();
			while (records.next()) {
				long id = records.getLong(1);
				long userID = records.getLong(2);
				User owner = UserDB.getUser(dataSource, userID);
				String className = records.getString(3);
				Date submitted = new Date(records.getTimestamp(4).getTime());
				String status = records.getString(5);
				Date started = null;
				
				if (null != records.getTimestamp(6)) {
					started = new Date(records.getTimestamp(6).getTime());
				}
				
				Date ended = null;
				
				if (null != records.getTimestamp(7)) {
					ended = new Date(records.getTimestamp(7).getTime());
				}
				
				double progress = records.getDouble(8);
				String stackTrace = records.getString(9);
				
				result.add(new JobSummary(id, owner, className, submitted, status, started, ended, progress, stackTrace));
			}
		
		} catch (SQLException e) {
			throw new DatabaseException("Error while retrieving job list", e);
		} finally {
			DatabaseUtils.closeResultSets(records);
			DatabaseUtils.closeStatements(stmt);
			DatabaseUtils.closeConnection(conn);
		}
		
		return result;
	}

	public static boolean startNextJob(ResourceManager resourceManager, Properties config) throws MissingParamException, DatabaseException, NoSuchJobException, JobThreadPoolNotInitialisedException, SecurityException, StringFormatException {
		boolean jobStarted = false;
		Job nextJob = getNextJob(resourceManager, config);
		if (null != nextJob) {
			JobThread thread = JobThreadPool.getInstance().getJobThread(nextJob);
			if (null != thread) {
	 			thread.start();
	 			
	 			// Wait until the job's status is updated in the database
	 			boolean jobRunning = false;
	 			while (!jobRunning) {
	 				if (!getJobStatus(resourceManager.getDBDataSource(), nextJob.getID()).equals(Job.WAITING_STATUS)) {
	 					jobRunning = true;
	 				} else {
	 					try {
	 						Thread.sleep(250);
	 					} catch (InterruptedException e) {
	 						// Do nothing
	 					}
	 				}
	 			}
	 			
	 			jobStarted = true;
			}
		}
		return jobStarted;
	}
	
	public static void resetInterruptedJobs(ResourceManager resourceManager) throws MissingParamException, DatabaseException {
		
		MissingParam.checkMissing(resourceManager, "resourceManager");
		
		DataSource dataSource = resourceManager.getDBDataSource();
		Connection conn = null;
		PreparedStatement threadNamesStmt = null;
		ResultSet runningThreadNames = null;
		
		try {
			
			conn = dataSource.getConnection();
			conn.setAutoCommit(false);
			threadNamesStmt = conn.prepareStatement(GET_RUNNING_THREAD_NAMES_STATEMENT);
			runningThreadNames = threadNamesStmt.executeQuery();
			
			List<Long> jobsToRequeue = new ArrayList<Long>();
			
			while (runningThreadNames.next()) {
				long jobId = runningThreadNames.getLong(1);
				String threadName = runningThreadNames.getString(2);
				
				JobThreadPool threadPool = JobThreadPool.getInstance();
				if (!threadPool.isThreadRunning(threadName)) {
					jobsToRequeue.add(jobId);
				}
			}
			
			requeueJobs(conn, jobsToRequeue);
			
			conn.commit();
		} catch (SQLException e) {
			DatabaseUtils.rollBack(conn);
			throw new DatabaseException("Error while resetting interrupted jobs");
		} catch (JobThreadPoolNotInitialisedException e) {
			DatabaseUtils.rollBack(conn);
			// Not much we can do about that.
		} finally {
			DatabaseUtils.closeResultSets(runningThreadNames);
			DatabaseUtils.closeStatements(threadNamesStmt);
			DatabaseUtils.closeConnection(conn);
		}
		
	}
	
	private static String getJobStatus(DataSource dataSource, long jobId) throws MissingParamException, NoSuchJobException, DatabaseException {
		MissingParam.checkMissing(dataSource, "dataSource");
		MissingParam.checkPositive(jobId, "jobId");

		String result = null;
		Connection conn = null;

		try {
			conn = dataSource.getConnection();
			result = getJobStatus(conn, jobId);
		} catch (SQLException e) {
			throw new DatabaseException("Error while retrieving job status", e);
		} finally {
			DatabaseUtils.closeConnection(conn);
		}
		
		return result;
	}
	
	private static String getJobStatus(Connection conn, long jobId) throws NoSuchJobException, MissingParamException, DatabaseException {
		
		String result = null;
		
		MissingParam.checkMissing(conn, "conn");
		MissingParam.checkPositive(jobId, "jobId");
		
		PreparedStatement stmt = null;
		ResultSet record = null;
		
		try {
			stmt = conn.prepareStatement(GET_JOB_STATUS_QUERY);
			stmt.setLong(1, jobId);
			
			record = stmt.executeQuery();
			if (!record.next()) {
				throw new NoSuchJobException(jobId);
			} else {
				result = record.getString(1);
			}
			
			
		} catch (SQLException e) {
			throw new DatabaseException("Error while retrieving job status", e);
		} finally {
			DatabaseUtils.closeResultSets(record);
			DatabaseUtils.closeStatements(stmt);
			DatabaseUtils.closeConnection(conn);
		}
		
		return result;
	}

	public static User getJobOwner(DataSource dataSource, long jobId) throws MissingParamException, DatabaseException, RecordNotFoundException, SQLException {
		MissingParam.checkMissing(dataSource, "dataSource");
		MissingParam.checkPositive(jobId, "jobId");
		
		User owner = null;
		Connection conn = null;
		
		try {
			conn = dataSource.getConnection();
			owner = getJobOwner(conn, jobId);
		} catch (SQLException e) {
			throw new DatabaseException("An error occured while finding the job owner", e);
		} finally {
			DatabaseUtils.closeConnection(conn);
		}
		
		return owner;
	}
	
		
	public static User getJobOwner(Connection conn, long jobId) throws MissingParamException, RecordNotFoundException, DatabaseException {
		MissingParam.checkMissing(conn, "conn");
		MissingParam.checkPositive(jobId, "jobId");

		User owner = null;
		
		PreparedStatement stmt = null;
		ResultSet record = null;
		
		try {
			stmt = conn.prepareStatement(GET_JOB_OWNER_QUERY);
			stmt.setLong(1, jobId);
			
			record = stmt.executeQuery();
			
			if (!record.next()) {
				throw new RecordNotFoundException("Job with id " + jobId + " does not exist");
			} else {
				long ownerId = record.getLong(1);
				if (ownerId != NO_OWNER) {
					owner = UserDB.getUser(conn, ownerId);
				}
			}	
		} catch (SQLException|DatabaseException e) {
			throw new DatabaseException("An error occured while finding the job owner", e);
		} finally {
			DatabaseUtils.closeResultSets(record);
			DatabaseUtils.closeStatements(stmt);
		}
		
		return owner;
	}

	public static void requeueJobs(Connection conn, List<Long> jobIds) throws MissingParamException, DatabaseException {
		
		// TODO We can make a new REQUEUE statement that takes multiple job ids.
		for (long jobId : jobIds) {
			requeueJob(conn, jobId);
		}
	}
	
	public static void requeueJob(DataSource dataSource, long jobId) throws MissingParamException, DatabaseException {

		MissingParam.checkMissing(dataSource, "dataSource");
		MissingParam.checkPositive(jobId, "jobId");

		Connection conn = null;
		try {
			conn = dataSource.getConnection();
			requeueJob(conn, jobId);
		} catch (SQLException e) {
			throw new DatabaseException("An error occurred while requeuing job " + jobId, e);
		} finally {
			DatabaseUtils.closeConnection(conn);
		}
	}

	public static void requeueJob(Connection conn, long jobId) throws MissingParamException, DatabaseException {

		MissingParam.checkMissing(conn, "conn");
		MissingParam.checkPositive(jobId, "jobId");
		
		PreparedStatement stmt = null;

		try {
			stmt = conn.prepareStatement(REQUEUE_JOB_STATEMENT);
			stmt.setLong(1, jobId);
			stmt.execute();
			
		} catch (SQLException e) {
			throw new DatabaseException("An error occurred while requeuing job " + jobId, e);
		} finally {
			DatabaseUtils.closeStatements(stmt);
		}
	}
	
	/**
	 * Kill a job specified by its database ID.
	 * 
	 * <p>
	 *   If the job is waiting, then it is marked as killed and no further action is taken.
	 * </p>
	 * <p>
	 *   If the job is running, then its thread is interrupted. The job will be responsible for
	 *   shutting down and updating its status.
	 * </p>
	 * <p>
	 *   If the job has already finished then no action is taken.
	 * </p>
	 * 
	 * @param dataSource A data source
	 * @param jobId The database ID of the job
	 * @throws MissingParamException If any required parameters are missing
	 * @throws DatabaseException If a database error occurs
	 * @throws JobThreadPoolNotInitialisedException If the job thread pool is not initialised
	 * @throws NoSuchJobException If the specified job is not in the database
	 * @throws UnrecognisedStatusException If an unrecognised status is set on the job
	 */
	public static void killJob(DataSource dataSource, long jobId) throws MissingParamException, DatabaseException, UnrecognisedStatusException, NoSuchJobException, JobThreadPoolNotInitialisedException {
		MissingParam.checkMissing(dataSource, "dataSource");
		MissingParam.checkPositive(jobId, "jobId");
		
		Connection conn = null;
		
		try {
			conn = dataSource.getConnection();
			conn.setAutoCommit(false);
			
			killJob(conn, jobId);
			
			conn.commit();
		} catch (SQLException e) {
			throw new DatabaseException("An error occurred while killing job '" + jobId + "'");
		} finally {
			DatabaseUtils.rollBack(conn);
			DatabaseUtils.closeConnection(conn);
		}
	}
	
	/**
	 * Kill a job specified by its database ID.
	 * 
	 * <p>
	 *   If the job is waiting, then it is marked as killed and no further action is taken.
	 * </p>
	 * <p>
	 *   If the job is running, then its thread is interrupted. The job will be responsible for
	 *   shutting down and updating its status.
	 * </p>
	 * <p>
	 *   If the job has already finished then no action is taken.
	 * </p>
	 * 
	 * @param conn A database connection
	 * @param jobId The database ID of the job
	 * @throws MissingParamException If any required parameters are missing
	 * @throws DatabaseException If a database error occurs
	 * @throws JobThreadPoolNotInitialisedException If the job thread pool is not initialised
	 * @throws NoSuchJobException If the specified job is not in the database
	 * @throws UnrecognisedStatusException If an unrecognised status is set on the job
	 */
	public static void killJob(Connection conn, long jobId) throws MissingParamException, UnrecognisedStatusException, DatabaseException, NoSuchJobException, JobThreadPoolNotInitialisedException {
		MissingParam.checkMissing(conn, "conn");
		MissingParam.checkPositive(jobId, "jobId");
		
		// Find the job in the Thread Pool
		int jobKilled = JobThreadPool.getInstance().killJob(jobId);
		
		// No running thread was found, so we update the job's status
		// according to its current status
		if (jobKilled == JobThreadPool.THREAD_NOT_RUNNING) {
			if (getJobStatus(conn, jobId).equals(Job.WAITING_STATUS)) {
				setStatus(conn, jobId, Job.KILLED_STATUS);
			}
		}
	}
	
	private static boolean isFileJob(String jobClass) throws JobClassNotFoundException {
		try {
			return FileJob.class.isAssignableFrom(Class.forName(jobClass));
		} catch (ClassNotFoundException e) {
			throw new JobClassNotFoundException(jobClass);
		}
	}
	
	/**
	 * Kill any jobs associated with a given data file
	 * @param dataSource A data source
	 * @param fileId The data file's database ID
	 * @return {@code true} if any jobs were found and killed; {@code false} otherwise.
	 * @throws DatabaseException 
	 * @throws MissingParamException 
	 * @throws JobClassNotFoundException 
	 * @throws JobThreadPoolNotInitialisedException 
	 * @throws NoSuchJobException 
	 * @throws UnrecognisedStatusException 
	 */
	public static TreeSet<Long> killFileJobs(DataSource dataSource, List<Long> fileIds) throws MissingParamException, DatabaseException, JobClassNotFoundException, UnrecognisedStatusException, NoSuchJobException, JobThreadPoolNotInitialisedException {
		
		MissingParam.checkMissing(dataSource, "dataSource");
		MissingParam.checkMissing(fileIds, "fileIds");
		
		Connection conn = null;
		PreparedStatement stmt = null;
		ResultSet jobs = null;

		try {
			TreeSet<Long> fileJobsKilled = new TreeSet<Long>();
			
			conn = dataSource.getConnection();
			stmt = conn.prepareStatement(GET_QUEUED_RUNNING_JOBS_QUERY);
			jobs = stmt.executeQuery();
			
			while (jobs.next()) {
				String jobClass = jobs.getString(2);
				if (isFileJob(jobClass)) {
					Map<String, String> parameters = StringUtils.delimitedToMap(jobs.getString(3));
					long jobFileId = Long.parseLong(parameters.get(FileJob.FILE_ID_KEY));

					int fileListIndex = fileIds.indexOf(jobFileId);
					if (fileListIndex > -1) {
						killJob(dataSource, jobs.getLong(1));
						fileJobsKilled.add(jobFileId);
					}
				}
			}

			return fileJobsKilled;
		} catch (Exception e) {
			throw new DatabaseException("Error while killing jobs for data files", e);
		} finally {
			DatabaseUtils.closeResultSets(jobs);
			DatabaseUtils.closeStatements(stmt);
			DatabaseUtils.closeConnection(conn);
		}
	}
	
	/**
	 * Delete old jobs from the system.
	 * 
	 * <p>
	 *   This method will delete jobs from the system that are no longer required. It will
	 *   only delete jobs that successfully finished (i.e. have the status {@link Job#FINISHED_STATUS}).
	 *   Jobs that finished with an error will be left in the system so that they can be invetigated.
	 *   These must be deleted manually.
	 * </p>
	 * 
	 * <p>
	 *   The {@code age} parameter indicates how old (in days) jobs must be before they are deleted.
	 *   The age will be taken from the time that the job finished. Jobs that finished more recently
	 *   than this threshold will be left alone.
	 * </p>
	 * 
	 * @param dataSource A data source
	 * @param age The age of the jobs to delete, in days
	 * @throws DatabaseException If a database error occurs
	 * @throws MissingParamException If any parameters are missing or invalid
	 */
	public static void deleteFinishedJobs(DataSource dataSource, int age) throws DatabaseException, MissingParamException {
		
		MissingParam.checkMissing(dataSource, "dataSource");
		MissingParam.checkPositive(age, "age");
		
		Connection conn = null;
		PreparedStatement stmt = null;
		
		try {
			conn = dataSource.getConnection();
			stmt = conn.prepareStatement(DELETE_OLD_FINISHED_JOBS_STATEMENT);
			stmt.setInt(1, age);
			stmt.execute();
			
		} catch (SQLException e) {
			throw new DatabaseException("Error while deleting old jobs", e);
		} finally {
			DatabaseUtils.closeStatements(stmt);
			DatabaseUtils.closeConnection(conn);
		}
	}
}
