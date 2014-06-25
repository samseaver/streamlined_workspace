package us.kbase.typedobj.core;

import us.kbase.typedobj.idref.IdReference;
import us.kbase.typedobj.idref.IdReferenceHandlers.IdReferenceHandlerException;
import us.kbase.typedobj.idref.IdReferenceHandlers.TooManyIdsException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Json token validation callback.
 * @author rsutormin
 */
public interface JsonTokenValidationListener {
	/**
	 * Method is for adding new error message.
	 * @param message error message
	 * @throws JsonTokenValidationException
	 */
	public void addError(String message) throws JsonTokenValidationException;
	
	/**
	 * Method is for adding id-reference into flat list which will be used to 
	 * extract resolved values from workspace db.
	 * @param ref description of id-reference
	 * @throws IdReferenceHandlerException if an ID could not be handled
	 * appropriately due to a syntax error or other issue. 
	 * @throws TooManyIdsException if the object undergoing validation
	 * contains too many IDs.
	 */
	public void addIdRefMessage(IdReference ref)
			throws TooManyIdsException, IdReferenceHandlerException;
	
	/**
	 * Method is for registering searchable ws-subset object.
	 * @param searchData
	 */
	public void addSearchableWsSubsetMessage(JsonNode selection);
	
	/**
	 * Method for registering the selection of metadata extraction.
	 * @param selection
	 */
	public void addMetadataWsMessage(JsonNode selection);
}
