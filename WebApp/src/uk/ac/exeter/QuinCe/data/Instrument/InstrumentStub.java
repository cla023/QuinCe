package uk.ac.exeter.QuinCe.data.Instrument;

import java.io.Serializable;

import uk.ac.exeter.QuinCe.utils.DatabaseException;
import uk.ac.exeter.QuinCe.utils.MissingParamException;
import uk.ac.exeter.QuinCe.utils.RecordNotFoundException;
import uk.ac.exeter.QuinCe.web.system.ResourceException;
import uk.ac.exeter.QuinCe.web.system.ServletUtils;

/**
 * A cut-down Instrument object, containing just a name and
 * database ID. These objects are returned from the results
 * of several searches and queries, and provide the minimum
 * information required to obtain the full details of an instrument.
 * This is done for performance reasons, since there are many instances
 * where only the instrument names are required for certain search results,
 * after which a single instrument is examined in more detail. At this point
 * the full instrument details can be loaded using {@link #getFullInstrument()}.
 * 
 * @author Steve Jones
 *
 */
public class InstrumentStub implements Serializable {

	/**
	 * The Serial Version UID
	 */
	private static final long serialVersionUID = 5898379713476853550L;

	/**
	 * The instrument's database ID
	 */
	private long id;
	
	/**
	 * The instrument's name
	 */
	private String name;
	
	/**
	 * Simple constructor
	 * @param id The instrument's database ID
	 * @param name The instrument's name
	 */
	public InstrumentStub(long id, String name) {
		this.id = id;
		this.name = name;
	}

	/**
	 * Returns the complete Instrument object for this stub
	 * @return The complete Instrument object
	 * @throws MissingParamException If the call to the database routine is incorrect (should never happen!)
	 * @throws DatabaseException If an error occurs while retrieving the data from the database
	 * @throws RecordNotFoundException If the instrument record cannot be found in the database
	 * @throws ResourceException If the data source cannot be retrieved
	 */
	public Instrument getFullInstrument() throws MissingParamException, DatabaseException, RecordNotFoundException, ResourceException {
		return InstrumentDB.getInstrument(ServletUtils.getDBDataSource(), id);
	}
	
	///////// *** GETTERS AND SETTERS *** ///////////
	/**
	 * Returns the instrument's database ID
	 * @return The instrument's database ID
	 */
	public long getId() {
		return id;
	}
	
	/**
	 * Return the instrument's name
	 * @return The instrument's name
	 */
	public String getName() {
		return name;
	}
}