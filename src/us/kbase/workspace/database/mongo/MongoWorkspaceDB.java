package us.kbase.workspace.database.mongo;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static us.kbase.workspace.database.Util.checkSize;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.jongo.FindAndModify;
import org.jongo.Jongo;
import org.jongo.MongoCollection;
import org.jongo.marshall.MarshallingException;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import us.kbase.common.mongo.GetMongoDB;
import us.kbase.common.mongo.exceptions.InvalidHostException;
import us.kbase.common.mongo.exceptions.MongoAuthException;
import us.kbase.common.service.UObject;
import us.kbase.common.utils.CountingOutputStream;
import us.kbase.typedobj.core.AbsoluteTypeDefId;
import us.kbase.typedobj.core.ExtractedSubsetAndMetadata;
import us.kbase.typedobj.core.MD5;
import us.kbase.typedobj.core.ObjectPaths;
import us.kbase.typedobj.core.TempFilesManager;
import us.kbase.typedobj.core.TypeDefId;
import us.kbase.typedobj.core.TypeDefName;
import us.kbase.typedobj.core.TypedObjectValidator;
import us.kbase.typedobj.core.Writable;
import us.kbase.typedobj.db.MongoTypeStorage;
import us.kbase.typedobj.db.TypeDefinitionDB;
import us.kbase.typedobj.exceptions.TypeStorageException;
import us.kbase.typedobj.exceptions.TypedObjectExtractionException;
import us.kbase.typedobj.test.DummyTypedObjectValidationReport;
import us.kbase.workspace.database.AllUsers;
import us.kbase.workspace.database.ByteArrayFileCacheManager.ByteArrayFileCache;
import us.kbase.workspace.database.ResourceUsageConfigurationBuilder.ResourceUsageConfiguration;
import us.kbase.workspace.database.ByteArrayFileCacheManager;
import us.kbase.workspace.database.ObjectChainResolvedWS;
import us.kbase.workspace.database.ObjectIDNoWSNoVer;
import us.kbase.workspace.database.ObjectIDResolvedWS;
import us.kbase.workspace.database.ObjectInformation;
import us.kbase.workspace.database.Permission;
import us.kbase.workspace.database.PermissionSet;
import us.kbase.workspace.database.Provenance;
import us.kbase.workspace.database.Reference;
import us.kbase.workspace.database.ResolvedWorkspaceID;
import us.kbase.workspace.database.ResourceUsageConfigurationBuilder;
import us.kbase.workspace.database.TypeAndReference;
import us.kbase.workspace.database.User;
import us.kbase.workspace.database.WorkspaceDatabase;
import us.kbase.workspace.database.WorkspaceIdentifier;
import us.kbase.workspace.database.WorkspaceInformation;
import us.kbase.workspace.database.WorkspaceObjectData;
import us.kbase.workspace.database.WorkspaceObjectInformation;
import us.kbase.workspace.database.WorkspaceUser;
import us.kbase.workspace.database.exceptions.CorruptWorkspaceDBException;
import us.kbase.workspace.database.exceptions.DBAuthorizationException;
import us.kbase.workspace.database.exceptions.FileCacheIOException;
import us.kbase.workspace.database.exceptions.FileCacheLimitExceededException;
import us.kbase.workspace.database.exceptions.NoSuchObjectException;
import us.kbase.workspace.database.exceptions.NoSuchReferenceException;
import us.kbase.workspace.database.exceptions.NoSuchWorkspaceException;
import us.kbase.workspace.database.exceptions.PreExistingWorkspaceException;
import us.kbase.workspace.database.exceptions.UninitializedWorkspaceDBException;
import us.kbase.workspace.database.exceptions.WorkspaceCommunicationException;
import us.kbase.workspace.database.exceptions.WorkspaceDBException;
import us.kbase.workspace.database.exceptions.WorkspaceDBInitializationException;
import us.kbase.workspace.database.mongo.exceptions.BlobStoreAuthorizationException;
import us.kbase.workspace.database.mongo.exceptions.BlobStoreCommunicationException;
import us.kbase.workspace.database.mongo.exceptions.BlobStoreException;
import us.kbase.workspace.database.mongo.exceptions.NoSuchBlobException;
import us.kbase.workspace.kbase.Util;
import us.kbase.workspace.lib.ResolvedSaveObject;
import us.kbase.workspace.lib.WorkspaceSaveObject;
import us.kbase.workspace.test.WorkspaceTestCommon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoException;
import com.mongodb.WriteResult;

@RunWith(Enclosed.class)
public class MongoWorkspaceDB implements WorkspaceDatabase {

	//TODO save set of objects with same provenance if they were generated by same fn
	
	private static final String COL_ADMINS = "admins";
	private static final String COL_SETTINGS = "settings";
	private static final String COL_WS_CNT = "workspaceCounter";
	private static final String COL_WORKSPACES = "workspaces";
	private static final String COL_WS_ACLS = "workspaceACLs";
	private static final String COL_WORKSPACE_OBJS = "workspaceObjects";
	private static final String COL_WORKSPACE_VERS = "workspaceObjVersions";
	private static final String COL_PROVENANCE = "provenance";
	private static final String COL_SHOCK_PREFIX = "shock_";
	private static final User ALL_USERS = new AllUsers('*');
	
	private ResourceUsageConfiguration rescfg;

	private static final long MAX_SUBDATA_SIZE = 15000000;
	private static final long MAX_PROV_SIZE = 1000000;
	private static final int MAX_WS_META_SIZE = 16000;
	
	private final DB wsmongo;
	private final Jongo wsjongo;
	private final BlobStore blob;
	private final QueryMethods query;
	private final FindAndModify updateWScounter;
	private final TypedObjectValidator typeValidator;
	
	private final Set<String> typeIndexEnsured = new HashSet<String>();
	private final TempFilesManager tfm;
	
	//TODO constants class

	private static final Map<String, Map<List<String>, List<String>>> INDEXES;
	private static final String IDX_UNIQ = "unique";
	private static final String IDX_SPARSE = "sparse";
	static {
		//hardcoded indexes
		INDEXES = new HashMap<String, Map<List<String>, List<String>>>();
		
		//workspaces indexes
		Map<List<String>, List<String>> ws = new HashMap<List<String>, List<String>>();
		//find workspaces you own
		ws.put(Arrays.asList(Fields.WS_OWNER), Arrays.asList(""));
		//find workspaces by permanent id
		ws.put(Arrays.asList(Fields.WS_ID), Arrays.asList(IDX_UNIQ));
		//find workspaces by mutable name
		ws.put(Arrays.asList(Fields.WS_NAME), Arrays.asList(IDX_UNIQ));
		//find workspaces by metadata
		ws.put(Arrays.asList(Fields.WS_META), Arrays.asList(IDX_SPARSE));
		INDEXES.put(COL_WORKSPACES, ws);
		
		//workspace acl indexes
		Map<List<String>, List<String>> wsACL = new HashMap<List<String>, List<String>>();
		//get a user's permission for a workspace, index covers queries
		wsACL.put(Arrays.asList(Fields.ACL_WSID, Fields.ACL_USER, Fields.ACL_PERM), Arrays.asList(IDX_UNIQ));
		//find workspaces to which a user has some level of permission, index coves queries
		wsACL.put(Arrays.asList(Fields.ACL_USER, Fields.ACL_PERM, Fields.ACL_WSID), Arrays.asList(""));
		INDEXES.put(COL_WS_ACLS, wsACL);
		
		//workspace object indexes
		Map<List<String>, List<String>> wsObj = new HashMap<List<String>, List<String>>();
		//find objects by workspace id & name
		wsObj.put(Arrays.asList(Fields.OBJ_WS_ID, Fields.OBJ_NAME), Arrays.asList(IDX_UNIQ));
		//find object by workspace id & object id
		wsObj.put(Arrays.asList(Fields.OBJ_WS_ID, Fields.OBJ_ID), Arrays.asList(IDX_UNIQ));
		//find recently modified objects
		wsObj.put(Arrays.asList(Fields.OBJ_MODDATE), Arrays.asList(""));
		//find object to garbage collect
		wsObj.put(Arrays.asList(Fields.OBJ_DEL, Fields.OBJ_REFCOUNTS), Arrays.asList(""));
		INDEXES.put(COL_WORKSPACE_OBJS, wsObj);

		//workspace object version indexes
		Map<List<String>, List<String>> wsVer = new HashMap<List<String>, List<String>>();
		//find versions
		wsVer.put(Arrays.asList(Fields.VER_WS_ID, Fields.VER_ID,
				Fields.VER_VER), Arrays.asList(IDX_UNIQ));
		//find versions by data object
		wsVer.put(Arrays.asList(Fields.VER_TYPE, Fields.VER_CHKSUM), Arrays.asList(""));
		//determine whether a particular object is referenced by this object
		wsVer.put(Arrays.asList(Fields.VER_REF), Arrays.asList(IDX_SPARSE));
		//determine whether a particular object is included in this object's provenance
		wsVer.put(Arrays.asList(Fields.VER_PROVREF), Arrays.asList(IDX_SPARSE));
		//find objects that have the same provenance
		wsVer.put(Arrays.asList(Fields.VER_PROV), Arrays.asList(""));
		//find objects by saved date
		wsVer.put(Arrays.asList(Fields.VER_SAVEDATE), Arrays.asList(""));
		//find objects by metadata
		wsVer.put(Arrays.asList(Fields.VER_META), Arrays.asList(IDX_SPARSE));
		INDEXES.put(COL_WORKSPACE_VERS, wsVer);
		
		//no indexes needed for provenance since all lookups are by _id
		
		//admin indexes
		Map<List<String>, List<String>> admin = new HashMap<List<String>, List<String>>();
		//find admins by name
		admin.put(Arrays.asList(Fields.ADMIN_NAME), Arrays.asList(IDX_UNIQ));
		INDEXES.put(COL_ADMINS, admin);
	}

	public MongoWorkspaceDB(final String host, final String database,
			final String backendSecret, final TempFilesManager tfm, 
			final int mongoRetryCount)
			throws UnknownHostException, IOException, InvalidHostException,
			WorkspaceDBException, TypeStorageException, InterruptedException {
		rescfg = new ResourceUsageConfigurationBuilder().build();
		this.tfm = tfm;
		wsmongo = GetMongoDB.getDB(host, database, mongoRetryCount, 10);
		wsjongo = new Jongo(wsmongo);
		query = new QueryMethods(wsmongo, (AllUsers) ALL_USERS, COL_WORKSPACES,
				COL_WORKSPACE_OBJS, COL_WORKSPACE_VERS, COL_WS_ACLS);
		final Settings settings = getSettings();
		blob = setupBlobStore(settings, backendSecret);
		updateWScounter = buildCounterQuery(wsjongo);
		//TODO check a few random types and make sure they exist
		this.typeValidator = new TypedObjectValidator(
				new TypeDefinitionDB(
						new MongoTypeStorage(
								GetMongoDB.getDB(host, settings.getTypeDatabase()))));
		ensureIndexes();
		ensureTypeIndexes();
	}
	
	public MongoWorkspaceDB(final String host, final String database,
			final String backendSecret, final String user,
			final String password, final TempFilesManager tfm,
			final int mongoRetryCount)
			throws UnknownHostException, WorkspaceDBException,
			TypeStorageException, IOException, InvalidHostException,
			MongoAuthException, InterruptedException {
		rescfg = new ResourceUsageConfigurationBuilder().build();
		this.tfm = tfm;
		wsmongo = GetMongoDB.getDB(host, database, user, password,
				mongoRetryCount, 10);
		wsjongo = new Jongo(wsmongo);
		query = new QueryMethods(wsmongo, (AllUsers) ALL_USERS, COL_WORKSPACES,
				COL_WORKSPACE_OBJS, COL_WORKSPACE_VERS, COL_WS_ACLS);
		final Settings settings = getSettings();
		blob = setupBlobStore(settings, backendSecret);
		updateWScounter = buildCounterQuery(wsjongo);
		this.typeValidator = new TypedObjectValidator(
				new TypeDefinitionDB(
						new MongoTypeStorage(
								GetMongoDB.getDB(host, settings.getTypeDatabase(),
										user, password))));
		ensureIndexes();
		ensureTypeIndexes();
	}
	
	//test constructor - runs both the java and perl type compilers
	public MongoWorkspaceDB(final String host, final String database,
			final String backendSecret, final String user,
			final String password, final String kidlpath,
			final String typeDBdir, final TempFilesManager tfm)
			throws UnknownHostException, IOException,
			WorkspaceDBException, InvalidHostException, MongoAuthException,
			TypeStorageException, InterruptedException {
		rescfg = new ResourceUsageConfigurationBuilder().build();
		this.tfm = tfm;
		wsmongo = GetMongoDB.getDB(host, database, user, password, 0, 0);
		wsjongo = new Jongo(wsmongo);
		query = new QueryMethods(wsmongo, (AllUsers) ALL_USERS, COL_WORKSPACES,
				COL_WORKSPACE_OBJS, COL_WORKSPACE_VERS, COL_WS_ACLS);
		final Settings settings = getSettings();
		//TODO 1 factor blob store creation out, BlobStore should be passed into the constructor
		blob = setupBlobStore(settings, backendSecret);
		updateWScounter = buildCounterQuery(wsjongo);
		this.typeValidator = new TypedObjectValidator(
				new TypeDefinitionDB(
						new MongoTypeStorage(
								GetMongoDB.getDB(host, settings.getTypeDatabase(),
										user, password)),
								typeDBdir == null ? null : new File(typeDBdir), kidlpath, "both"));
		ensureIndexes();
		ensureTypeIndexes();
	}
	
	@Override
	public void setResourceUsageConfiguration(ResourceUsageConfiguration rescfg) {
		this.rescfg = rescfg;
	}
	
	@Override
	public TempFilesManager getTempFilesManager() {
		return tfm;
	}
	
	private void ensureIndexes() {
		for (String col: INDEXES.keySet()) {
			wsmongo.getCollection(col).resetIndexCache();
			for (List<String> idx: INDEXES.get(col).keySet()) {
				final DBObject index = new BasicDBObject();
				final DBObject opts = new BasicDBObject();
				for (String field: idx) {
					index.put(field, 1);
				}
				for (String option: INDEXES.get(col).get(idx)) {
					if (!option.equals("")) {
						opts.put(option, 1);
					}
				}
				wsmongo.getCollection(col).ensureIndex(index, opts);
			}
		}
	}
	
	private void ensureTypeIndexes() {
		for (final String col: wsmongo.getCollectionNames()) {
			if (col.startsWith(TypeData.TYPE_COL_PREFIX)) {
				ensureTypeIndex(col);
			}
		}
	}
	
	private void ensureTypeIndex(final TypeDefId type) {
		ensureTypeIndex(TypeData.getTypeCollection(type));
	}

	private void ensureTypeIndex(String col) {
		if (typeIndexEnsured.contains(col)) {
			return;
		}
		final DBObject chksum = new BasicDBObject();
		chksum.put(Fields.TYPE_CHKSUM, 1);
		final DBObject unique = new BasicDBObject();
		unique.put(IDX_UNIQ, 1);
		wsmongo.getCollection(col).resetIndexCache();
		wsmongo.getCollection(col).ensureIndex(chksum, unique);
		typeIndexEnsured.add(col);
	}
	
	private static FindAndModify buildCounterQuery(final Jongo j) {
		return j.getCollection(COL_WS_CNT)
				.findAndModify(String.format("{%s: #}",
						Fields.CNT_ID), Fields.CNT_ID_VAL)
				.upsert().returnNew()
				.with("{$inc: {" + Fields.CNT_NUM + ": #}}", 1L)
				.projection(String.format("{%s: 1, %s: 0}",
						Fields.CNT_NUM, Fields.MONGO_ID));
	}

	private Settings getSettings() throws UninitializedWorkspaceDBException,
			CorruptWorkspaceDBException {
		if (!wsmongo.collectionExists(COL_SETTINGS)) {
			throw new UninitializedWorkspaceDBException(
					"No settings collection exists");
		}
		MongoCollection settings = wsjongo.getCollection(COL_SETTINGS);
		if (settings.count() != 1) {
			throw new CorruptWorkspaceDBException(
					"More than one settings document exists");
		}
		Settings wsSettings = null;
		try {
			wsSettings = settings.findOne().as(Settings.class);
		} catch (MarshallingException me) {
			Throwable ex = me.getCause();
			if (ex == null) {
				throw new CorruptWorkspaceDBException(
						"Unable to unmarshal settings document", me);
			}
			ex = ex.getCause();
			if (ex == null || !(ex instanceof CorruptWorkspaceDBException)) {
				throw new CorruptWorkspaceDBException(
						"Unable to unmarshal settings document", me);
			}
			throw (CorruptWorkspaceDBException) ex;
		}
		if (wsmongo.getName().equals(wsSettings.getTypeDatabase())) {
			throw new CorruptWorkspaceDBException(
					"The type database name is the same as the workspace database name: "
					+ wsmongo.getName());
		}
		return wsSettings;
	}

	private BlobStore setupBlobStore(final Settings settings,
			final String backendSecret) throws CorruptWorkspaceDBException,
			DBAuthorizationException, WorkspaceDBInitializationException {
		if (settings.isGridFSBackend()) {
			return new GridFSBackend(wsmongo);
		}
		if (settings.isShockBackend()) {
			URL shockurl = null;
			try {
				shockurl = new URL(settings.getShockUrl());
			} catch (MalformedURLException mue) {
				throw new CorruptWorkspaceDBException(
						"Settings has bad shock url: "
								+ settings.getShockUrl(), mue);
			}
			BlobStore bs;
			try {
				bs = new ShockBackend(wsmongo, COL_SHOCK_PREFIX,
						shockurl, settings.getShockUser(), backendSecret);
			} catch (BlobStoreAuthorizationException e) {
				throw new DBAuthorizationException(
						"Not authorized to access the blob store database: "
						+ e.getLocalizedMessage(), e);
			} catch (BlobStoreException e) {
				throw new WorkspaceDBInitializationException(
						"The database could not be initialized: " +
						e.getLocalizedMessage(), e);
			}
			// TODO if shock, check a few random nodes to make sure they match
			// the internal representation, die otherwise
			return bs;
		}
		throw new RuntimeException("Something's real broke y'all");
	}
	
	@Override
	public TypedObjectValidator getTypeValidator() {
		return typeValidator;
	}

	@Override
	public String getBackendType() {
		return blob.getStoreType();
	}

	private final static String M_WS_DATE_WTH = String.format(
			"{$set: {%s: #}}", Fields.WS_MODDATE);
	
	private void updateWorkspaceModifiedDate(final ResolvedMongoWSID rwsi)
			throws WorkspaceCommunicationException {
		try {
			wsjongo.getCollection(COL_WORKSPACES)
				.update(M_WS_ID_QRY, rwsi.getID())
				.with(M_WS_DATE_WTH, new Date());
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
	}

	private static final Set<String> FLDS_CREATE_WS =
			newHashSet(Fields.WS_DEL, Fields.WS_OWNER);
	
	@Override
	public WorkspaceInformation createWorkspace(final WorkspaceUser user,
			final String wsname, final boolean globalRead,
			final String description, final Map<String, String> meta)
			throws PreExistingWorkspaceException,
			WorkspaceCommunicationException, CorruptWorkspaceDBException {
		checkSize(meta, "Metadata", MAX_WS_META_SIZE);
		//avoid incrementing the counter if we don't have to
		try {
			final List<Map<String, Object>> ws = query.queryCollection(
					COL_WORKSPACES, new BasicDBObject(Fields.WS_NAME, wsname),
					FLDS_CREATE_WS);
			if (ws.size() == 1) {
				final boolean del = (Boolean) ws.get(0).get(Fields.WS_DEL);
				final String owner = (String) ws.get(0).get(Fields.WS_OWNER);
				String err = String.format(
						"Workspace name %s is already in use", wsname);
				if (del && owner.equals(user.getUser())) {
					err += " by a deleted workspace";
				}
				throw new PreExistingWorkspaceException(err);
			} else if (ws.size() > 1) { //should be impossible
				throw new CorruptWorkspaceDBException(String.format(
						"There is more than one workspace with the name %s",
						wsname));
			}
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		final long count; 
		try {
			count = ((Number) updateWScounter.as(DBObject.class)
					.get(Fields.CNT_NUM)).longValue();
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		final DBObject ws = new BasicDBObject();
		ws.put(Fields.WS_OWNER, user.getUser());
		ws.put(Fields.WS_ID, count);
		Date moddate = new Date();
		ws.put(Fields.WS_MODDATE, moddate);
		ws.put(Fields.WS_NAME, wsname);
		ws.put(Fields.WS_DEL, false);
		ws.put(Fields.WS_NUMOBJ, 0L);
		ws.put(Fields.WS_DESC, description);
		ws.put(Fields.WS_LOCKED, false);
		if (meta != null) {
			ws.put(Fields.WS_META, metaHashToMongoArray(meta));
		}
		try {
			wsmongo.getCollection(COL_WORKSPACES).insert(ws);
		} catch (MongoException.DuplicateKey mdk) {
			//this is almost impossible to test and will probably almost never happen
			throw new PreExistingWorkspaceException(String.format(
					"Workspace name %s is already in use", wsname));
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		setPermissionsForWorkspaceUsers(
				new ResolvedMongoWSID(wsname, count, false, false),
				Arrays.asList(user), Permission.OWNER, false);
		if (globalRead) {
			setPermissions(new ResolvedMongoWSID(wsname, count, false, false),
					Arrays.asList(ALL_USERS), Permission.READ, false);
		}
		return new MongoWSInfo(count, wsname, user, moddate, 0L,
				Permission.OWNER, globalRead, false,
				meta == null ? new HashMap<String, String>() : meta);
	}
	
	private static final Set<String> FLDS_WS_META = newHashSet(Fields.WS_META);
	
	private final static String M_WS_META_QRY = String.format(
			"{%s: #, \"%s.%s\": #}", Fields.WS_ID, Fields.WS_META,
			Fields.META_KEY);
	private final static String M_SET_WS_META_WTH = String.format(
			"{$set: {\"%s.$.%s\": #, %s: #}}",
			Fields.WS_META, Fields.META_VALUE, Fields.WS_MODDATE); 
	
	private final static String M_SET_WS_META_NOT_QRY = String.format(
			"{%s: #, \"%s.%s\": {$nin: [#]}}", Fields.WS_ID, Fields.WS_META,
			Fields.META_KEY);
	private final static String M_SET_WS_META_NOT_WTH = String.format(
			"{$push: {%s: {%s: #, %s: #}}, $set: {%s: #}}",
			Fields.WS_META, Fields.META_KEY, Fields.META_VALUE,
			Fields.WS_MODDATE); 
	
	@Override
	public void setWorkspaceMetaKey(final ResolvedWorkspaceID rwsi,
			final Map<String, String> meta)
			throws WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		
		if (meta == null || meta.isEmpty()) {
			throw new IllegalArgumentException(
					"Metadata cannot be null or empty");
		}
		final Map<String, Object> ws = query.queryWorkspace(
				query.convertResolvedWSID(rwsi), FLDS_WS_META);
		@SuppressWarnings("unchecked")
		Map<String, String> currMeta = metaMongoArrayToHash(
				(List<Object>) ws.get(Fields.WS_META));
		currMeta.putAll(meta);
		checkSize(currMeta, "Updated metadata", MAX_WS_META_SIZE);
		
		for (final Entry<String, String> e: meta.entrySet()) {
			final String key = e.getKey();
			final String value = e.getValue();
			boolean success = false;
			while (!success) { //Danger, Will Robinson! Danger!
				//replace the value if it exists already
				WriteResult wr;
				try {
					wr = wsjongo.getCollection(COL_WORKSPACES)
							.update(M_WS_META_QRY, rwsi.getID(), key)
							.with(M_SET_WS_META_WTH, value, new Date());
				} catch (MongoException me) {
					throw new WorkspaceCommunicationException(
							"There was a problem communicating with the database",
							me);
				}
				if (wr.getN() == 1) { //ok, it worked
					success = true;
					continue;
				}
				//add the key/value pair to the array
				try {
					wr = wsjongo.getCollection(COL_WORKSPACES)
							.update(M_SET_WS_META_NOT_QRY, rwsi.getID(), key)
							.with(M_SET_WS_META_NOT_WTH, key, value,
									new Date());
				} catch (MongoException me) {
					throw new WorkspaceCommunicationException(
							"There was a problem communicating with the database",
							me);
				}
				if (wr.getN() == 1) { //ok, it worked
					success = true;
				}
				/* amazingly, someone added that key to the metadata between the
				   two calls above, so here we go again on our own
				   Should be impossible to get stuck in a loop, but if so add
				   counter and throw error if > 3 or something
				 */
			}
		}
	}
	
	
	private static final String M_REM_META_WTH = String.format(
			"{$pull: {%s: {%s: #}}, $set: {%s: #}}",
			Fields.WS_META, Fields.META_KEY, Fields.WS_MODDATE);
	
	@Override
	public void removeWorkspaceMetaKey(final ResolvedWorkspaceID rwsi,
			final String key) throws WorkspaceCommunicationException {
		
		try {
			wsjongo.getCollection(COL_WORKSPACES)
					.update(M_WS_META_QRY, rwsi.getID(), key)
					.with(M_REM_META_WTH, key, new Date());
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
	}
	
	private static final Set<String> FLDS_CLONE_WS =
			newHashSet(Fields.OBJ_ID, Fields.OBJ_NAME, Fields.OBJ_DEL,
					Fields.OBJ_HIDE);
	
	@Override
	public WorkspaceInformation cloneWorkspace(final WorkspaceUser user,
			final ResolvedWorkspaceID wsid, final String newname,
			final boolean globalRead, final String description,
			final Map<String, String> meta)
			throws PreExistingWorkspaceException,
			WorkspaceCommunicationException, CorruptWorkspaceDBException {
		
		// looked at using copyObject to do this but was too messy
		final ResolvedMongoWSID fromWS = query.convertResolvedWSID(wsid);
		final WorkspaceInformation wsinfo =
				createWorkspace(user, newname, globalRead, description, meta);
		final ResolvedMongoWSID toWS = new ResolvedMongoWSID(wsinfo.getName(),
				wsinfo.getId(), wsinfo.isLocked(), false); //assume it's not deleted already
		final DBObject q = new BasicDBObject(Fields.OBJ_WS_ID, fromWS.getID());
		final List<Map<String, Object>> wsobjects =
				query.queryCollection(COL_WORKSPACE_OBJS, q, FLDS_CLONE_WS);
		for (Map<String, Object> o: wsobjects) {
			if ((Boolean) o.get(Fields.OBJ_DEL)) {
				continue;
			}
			final long oldid = (Long) o.get(Fields.OBJ_ID);
			final String name = (String) o.get(Fields.OBJ_NAME);
			final boolean hidden = (Boolean) o.get(Fields.OBJ_HIDE);
			final ResolvedMongoObjectIDNoVer roi = 
					new ResolvedMongoObjectIDNoVer(fromWS, name, oldid);
			final List<Map<String, Object>> versions = query.queryAllVersions(
					new HashSet<ResolvedMongoObjectIDNoVer>(Arrays.asList(roi)),
					FLDS_VER_COPYOBJ).get(roi);
			for (final Map<String, Object> v: versions) {
				final int ver = (Integer) v.get(Fields.VER_VER);
				v.remove(Fields.MONGO_ID);
				v.put(Fields.VER_SAVEDBY, user.getUser());
				v.put(Fields.VER_RVRT, null);
				v.put(Fields.VER_COPIED, new MongoReference(
						fromWS.getID(), oldid, ver).toString());
			}
			updateReferenceCountsForVersions(versions);
			final long newid = incrementWorkspaceCounter(toWS, 1);
			final long objid = saveWorkspaceObject(toWS, newid, name).id;
			saveObjectVersions(user, toWS, objid, versions, hidden);
		}
		return getWorkspaceInformation(user, toWS);
	}
	
	private final static String M_LOCK_WS_WTH = String.format("{$set: {%s: #}}",
			Fields.WS_LOCKED);
	
	@Override
	public WorkspaceInformation lockWorkspace(final WorkspaceUser user,
			final ResolvedWorkspaceID rwsi)
			throws WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		try {
			wsjongo.getCollection(COL_WORKSPACES)
				.update(M_WS_ID_QRY, rwsi.getID())
				.with(M_LOCK_WS_WTH, true);
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		return getWorkspaceInformation(user, rwsi);
	}
	
	private static final Set<String> FLDS_VER_COPYOBJ = newHashSet(
			Fields.VER_WS_ID, Fields.VER_ID, Fields.VER_VER,
			Fields.VER_TYPE, Fields.VER_CHKSUM, Fields.VER_SIZE,
			Fields.VER_PROV, Fields.VER_REF, Fields.VER_PROVREF,
			Fields.VER_COPIED, Fields.VER_META);
	
	@Override
	public ObjectInformation copyObject(final WorkspaceUser user,
			final ObjectIDResolvedWS from, final ObjectIDResolvedWS to)
			throws NoSuchObjectException, WorkspaceCommunicationException {
		return copyOrRevert(user, from, to, false);
	}
	
	@Override
	public ObjectInformation revertObject(final WorkspaceUser user,
			final ObjectIDResolvedWS oi)
			throws NoSuchObjectException, WorkspaceCommunicationException {
		return copyOrRevert(user, oi, null, true);
	}
		
	private ObjectInformation copyOrRevert(final WorkspaceUser user,
			final ObjectIDResolvedWS from, ObjectIDResolvedWS to,
			final boolean revert)
			throws NoSuchObjectException, WorkspaceCommunicationException {
		final ResolvedMongoObjectID rfrom = resolveObjectIDs(
				new HashSet<ObjectIDResolvedWS>(Arrays.asList(from))).get(from);
		final ResolvedMongoObjectID rto;
		if (revert) {
			to = from;
			rto = rfrom;
		} else {
			rto = resolveObjectIDs(
					new HashSet<ObjectIDResolvedWS>(Arrays.asList(to)),
					false, false, true).get(to); //don't except if there's no object
		}
		if (rto == null && to.getId() != null) {
			throw new NoSuchObjectException(String.format(
					"Copy destination is specified as object id %s in workspace %s which does not exist.",
					to.getId(), to.getWorkspaceIdentifier().getID()));
		}
		final List<Map<String, Object>> versions;
		if (rto == null && from.getVersion() == null) {
			final ResolvedMongoObjectIDNoVer o =
					new ResolvedMongoObjectIDNoVer(rfrom);
			versions = query.queryAllVersions(
					new HashSet<ResolvedMongoObjectIDNoVer>(Arrays.asList(o)),
					FLDS_VER_COPYOBJ).get(o);
		} else {
			versions = Arrays.asList(query.queryVersions(
					new HashSet<ResolvedMongoObjectID>(Arrays.asList(rfrom)),
					FLDS_VER_COPYOBJ).get(rfrom));
		}
		for (final Map<String, Object> v: versions) {
			int ver = (Integer) v.get(Fields.VER_VER);
			v.remove(Fields.MONGO_ID);
			v.put(Fields.VER_SAVEDBY, user.getUser());
			if (revert) {
				v.put(Fields.VER_RVRT, ver);
			} else {
				v.put(Fields.VER_RVRT, null);
				v.put(Fields.VER_COPIED, new MongoReference(
						rfrom.getWorkspaceIdentifier().getID(), rfrom.getId(),
						ver).toString());
			}
		}
		updateReferenceCountsForVersions(versions);
		final ResolvedMongoWSID toWS = query.convertResolvedWSID(
				to.getWorkspaceIdentifier());
		final long objid;
		if (rto == null) { //need to make a new object
			final long id = incrementWorkspaceCounter(toWS, 1);
			objid = saveWorkspaceObject(toWS, id, to.getName()).id;
		} else {
			objid = rto.getId();
		}
		saveObjectVersions(user, toWS, objid, versions, null);
		final Map<String, Object> info = versions.get(versions.size() - 1);
		updateWorkspaceModifiedDate(toWS);
		return generateObjectInfo(toWS, objid, rto == null ? to.getName() :
				rto.getName(), info);
	}
	
	final private static String M_RENAME_WS_WTH = String.format(
			"{$set: {%s: #, %s: #}}", Fields.WS_NAME, Fields.WS_MODDATE);
	
	@Override
	public WorkspaceInformation renameWorkspace(final WorkspaceUser user,
			final ResolvedWorkspaceID rwsi, final String newname)
			throws WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		if (newname.equals(rwsi.getName())) {
			throw new IllegalArgumentException("Workspace is already named " +
					newname);
		}
		try {
			wsjongo.getCollection(COL_WORKSPACES)
					.update(M_WS_ID_QRY, rwsi.getID())
					.with(M_RENAME_WS_WTH, newname, new Date());
		} catch (MongoException.DuplicateKey medk) {
			throw new IllegalArgumentException(
					"There is already a workspace named " + newname);
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		return getWorkspaceInformation(user, rwsi);
	}
	
	final private static String M_RENAME_OBJ_QRY = String.format(
			"{%s: #, %s: #}", Fields.OBJ_WS_ID, Fields.OBJ_ID);
	final private static String M_RENAME_OBJ_WTH = String.format(
			"{$set: {%s: #, %s: #}}", Fields.OBJ_NAME, Fields.OBJ_MODDATE);
	
	@Override
	public ObjectInformation renameObject(final ObjectIDResolvedWS oi,
			final String newname)
			throws NoSuchObjectException, WorkspaceCommunicationException {
		Set<ObjectIDResolvedWS> input = new HashSet<ObjectIDResolvedWS>(
				Arrays.asList(oi));
		final ResolvedMongoObjectID roi = resolveObjectIDs(input).get(oi);
		if (newname.equals(roi.getName())) {
			throw new IllegalArgumentException("Object is already named " +
					newname);
		}
		try {
			wsjongo.getCollection(COL_WORKSPACE_OBJS)
					.update(M_RENAME_OBJ_QRY,
							roi.getWorkspaceIdentifier().getID(), roi.getId())
					.with(M_RENAME_OBJ_WTH, newname, new Date());
		} catch (MongoException.DuplicateKey medk) {
			throw new IllegalArgumentException(
					"There is already an object in the workspace named " +
							newname);
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		final ObjectIDResolvedWS oid = new ObjectIDResolvedWS(
				roi.getWorkspaceIdentifier(), roi.getId(), roi.getVersion());
		input = new HashSet<ObjectIDResolvedWS>(Arrays.asList(oid));
		
		final ObjectInformation oinf =
				getObjectInformation(input, false, false).get(oid);
		updateWorkspaceModifiedDate(roi.getWorkspaceIdentifier());
		return oinf;
	}
	
	//projection lists
	private static final Set<String> FLDS_WS_DESC = newHashSet(Fields.WS_DESC);
	private static final Set<String> FLDS_WS_OWNER = newHashSet(Fields.WS_OWNER);
	
	//http://stackoverflow.com/questions/2041778/initialize-java-hashset-values-by-construction
	@SafeVarargs
	private static <T> Set<T> newHashSet(T... objs) {
		Set<T> set = new HashSet<T>();
		for (T o : objs) {
			set.add(o);
		}
		return set;
	}
	
	@Override
	public String getWorkspaceDescription(final ResolvedWorkspaceID rwsi) throws
			CorruptWorkspaceDBException, WorkspaceCommunicationException {
		return (String) query.queryWorkspace(query.convertResolvedWSID(rwsi),
				FLDS_WS_DESC).get(Fields.WS_DESC);
	}
	
	private final static String M_WS_ID_QRY = String.format("{%s: #}",
			Fields.WS_ID);
	private final static String M_DESC_WTH = String.format(
			"{$set: {%s: #, %s: #}}", Fields.WS_DESC, Fields.WS_MODDATE);
	
	@Override
	public void setWorkspaceDescription(final ResolvedWorkspaceID rwsi,
			final String description) throws WorkspaceCommunicationException {
		//TODO generalized method for setting fields?
		try {
			wsjongo.getCollection(COL_WORKSPACES)
				.update(M_WS_ID_QRY, rwsi.getID())
				.with(M_DESC_WTH, description, new Date());
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
	}
	
	@Override
	public ResolvedWorkspaceID resolveWorkspace(final WorkspaceIdentifier wsi)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException {
		return resolveWorkspace(wsi, false);
	}
	
	@Override
	public ResolvedWorkspaceID resolveWorkspace(final WorkspaceIdentifier wsi,
			final boolean allowDeleted)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException {
		Set<WorkspaceIdentifier> wsiset = new HashSet<WorkspaceIdentifier>();
		wsiset.add(wsi);
		return resolveWorkspaces(wsiset, allowDeleted).get(wsi);
				
	}
	
	@Override
	public Map<WorkspaceIdentifier, ResolvedWorkspaceID> resolveWorkspaces(
			final Set<WorkspaceIdentifier> wsis) throws NoSuchWorkspaceException,
			WorkspaceCommunicationException {
		return resolveWorkspaces(wsis, false);
	}
	
	private static final Set<String> FLDS_WS_ID_NAME_DEL =
			newHashSet(Fields.WS_ID, Fields.WS_NAME, Fields.WS_DEL,
					Fields.WS_LOCKED);
	
	private Map<WorkspaceIdentifier, ResolvedWorkspaceID> resolveWorkspaces(
			final Set<WorkspaceIdentifier> wsis, final boolean allowDeleted)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException {
		return resolveWorkspaces(wsis, allowDeleted, false);
	}
	
	@Override
	public Map<WorkspaceIdentifier, ResolvedWorkspaceID> resolveWorkspaces(
			final Set<WorkspaceIdentifier> wsis, final boolean allowDeleted,
			final boolean allowMissing)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException {
		final Map<WorkspaceIdentifier, ResolvedWorkspaceID> ret =
				new HashMap<WorkspaceIdentifier, ResolvedWorkspaceID>();
		if (wsis.isEmpty()) {
			return ret;
		}
		final Map<WorkspaceIdentifier, Map<String, Object>> res =
				query.queryWorkspacesByIdentifier(wsis, FLDS_WS_ID_NAME_DEL);
		for (final WorkspaceIdentifier wsi: wsis) {
			if (!res.containsKey(wsi)) {
				if (!allowMissing) {
					throw new NoSuchWorkspaceException(String.format(
							"No workspace with %s exists", getWSErrorId(wsi)),
							wsi);
				}
			} else {
				if (!allowDeleted &&
						(Boolean) res.get(wsi).get(Fields.WS_DEL)) {
					throw new NoSuchWorkspaceException("Workspace " +
							wsi.getIdentifierString() + " is deleted", wsi);
				}
				ResolvedMongoWSID r = new ResolvedMongoWSID(
						(String) res.get(wsi).get(Fields.WS_NAME),
						(Long) res.get(wsi).get(Fields.WS_ID),
						(Boolean) res.get(wsi).get(Fields.WS_LOCKED), 
						(Boolean) res.get(wsi).get(Fields.WS_DEL));
				ret.put(wsi, r);
			}
		}
		return ret;
	}
	
	@Override
	public PermissionSet getPermissions(
			final WorkspaceUser user, final Permission perm,
			final boolean excludeGlobalRead)
			throws WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		return getPermissions(user,
				new HashSet<ResolvedWorkspaceID>(), perm, excludeGlobalRead);
	}
	
	@Override
	public PermissionSet getPermissions(
			final WorkspaceUser user, final Set<ResolvedWorkspaceID> rwsis,
			final Permission perm, final boolean excludeGlobalRead)
			throws WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		if (perm == null || Permission.NONE.equals(perm)) {
			throw new IllegalArgumentException(
					"Permission cannot be null or NONE");
		}
		Set<ResolvedMongoWSID> rmwsis = query.convertResolvedWSID(rwsis);
		final Map<ResolvedMongoWSID, Map<User, Permission>> userperms;
		if (user != null) {
			userperms = query.queryPermissions(rmwsis, 
					new HashSet<User>(Arrays.asList(user)), perm);
		} else {
			userperms = new HashMap<ResolvedMongoWSID, Map<User,Permission>>();
		}
		final Set<User> allusers = new HashSet<User>(Arrays.asList(ALL_USERS));
		final Map<ResolvedMongoWSID, Map<User, Permission>> globalperms;
		if (excludeGlobalRead || perm.compareTo(Permission.WRITE) >= 0) {
			if (userperms.isEmpty()) {
				globalperms =
						new HashMap<ResolvedMongoWSID, Map<User,Permission>>();
			} else {
				globalperms = query.queryPermissions(userperms.keySet(),
						allusers);
			}
		} else {
			globalperms = query.queryPermissions(rmwsis, allusers,
					Permission.READ);
		}
		final MongoPermissionSet pset = new MongoPermissionSet(user, ALL_USERS);
		for (final ResolvedMongoWSID rwsi: userperms.keySet()) {
			Permission gl = globalperms.get(rwsi) == null ? Permission.NONE :
				globalperms.get(rwsi).get(ALL_USERS);
			gl = gl == null ? Permission.NONE : gl;
			Permission p = userperms.get(rwsi).get(user);
			p = p == null ? Permission.NONE : p;
			if (!p.equals(Permission.NONE) || !gl.equals(Permission.NONE)) {
				pset.setPermission(rwsi, p, gl);
			}
			globalperms.remove(rwsi);
		}
		for (final ResolvedMongoWSID rwsi: globalperms.keySet()) {
			final Permission gl = globalperms.get(rwsi).get(ALL_USERS);
			if (gl != null && !gl.equals(Permission.NONE)) {
				pset.setPermission(rwsi, Permission.NONE, gl);
			}
		}
		return pset;
	}
	
	private static String getWSErrorId(final WorkspaceIdentifier wsi) {
		if (wsi.getId() == null) {
			return "name " + wsi.getName();
		}
		return "id " + wsi.getId();
	}
	
	@Override
	public void setPermissions(final ResolvedWorkspaceID rwsi,
			final List<WorkspaceUser> users, final Permission perm) throws
			WorkspaceCommunicationException, CorruptWorkspaceDBException {
		setPermissionsForWorkspaceUsers(query.convertResolvedWSID(rwsi),
				users, perm, true);
	}
	
	@Override
	public void setGlobalPermission(final ResolvedWorkspaceID rwsi,
			final Permission perm)
			throws WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		setPermissions(query.convertResolvedWSID(rwsi),
				Arrays.asList(ALL_USERS), perm, false);
	}
	
	//wsid must exist as a workspace
	private void setPermissionsForWorkspaceUsers(final ResolvedMongoWSID wsid,
			final List<WorkspaceUser> users, final Permission perm, 
			final boolean checkowner) throws WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		List<User> u = new ArrayList<User>();
		for (User user: users) {
			u.add(user);
		}
		setPermissions(wsid, u, perm, checkowner);
		
	}
	
	private static final String M_PERMS_QRY = String.format("{%s: #, %s: #}",
			Fields.ACL_WSID, Fields.ACL_USER);
	private static final String M_PERMS_UPD = String.format("{$set: {%s: #}}",
			Fields.ACL_PERM);
	
	private void setPermissions(final ResolvedMongoWSID wsid, final List<User> users,
			final Permission perm, final boolean checkowner) throws
			WorkspaceCommunicationException, CorruptWorkspaceDBException {
		final WorkspaceUser owner;
		if (checkowner) {
			final Map<String, Object> ws =
					query.queryWorkspace(wsid, FLDS_WS_OWNER);
			if (ws == null) {
				throw new CorruptWorkspaceDBException(String.format(
						"Workspace %s was unexpectedly deleted from the database",
						wsid.getID()));
			}
			owner = new WorkspaceUser((String) ws.get(Fields.WS_OWNER));
		} else {
			owner = null;
		}
		for (User user: users) {
			if (owner != null && owner.getUser().equals(user.getUser())) {
				continue; // can't change owner permissions
			}
			try {
				if (perm.equals(Permission.NONE)) {
					wsjongo.getCollection(COL_WS_ACLS).remove(
							M_PERMS_QRY, wsid.getID(), user.getUser());
				} else {
					wsjongo.getCollection(COL_WS_ACLS).update(
							M_PERMS_QRY, wsid.getID(), user.getUser())
							.upsert().with(M_PERMS_UPD, perm.getPermission());
				}
			} catch (MongoException me) {
				throw new WorkspaceCommunicationException(
						"There was a problem communicating with the database", me);
			}
		}
	}
	
	@Override
	public Permission getPermission(final WorkspaceUser user,
			final ResolvedWorkspaceID wsi) throws 
			WorkspaceCommunicationException, CorruptWorkspaceDBException {
		return getPermissions(user, wsi).getPermission(wsi, true);
	}
	
	public PermissionSet getPermissions(final WorkspaceUser user,
			final ResolvedWorkspaceID rwsi) throws 
			WorkspaceCommunicationException, CorruptWorkspaceDBException {
		final Set<ResolvedWorkspaceID> wsis =
				new HashSet<ResolvedWorkspaceID>();
		wsis.add(rwsi);
		return getPermissions(user, wsis);
	}
	
	@Override
	public PermissionSet getPermissions(
			final WorkspaceUser user, final Set<ResolvedWorkspaceID> rwsis)
			throws WorkspaceCommunicationException, 
			CorruptWorkspaceDBException {
		return getPermissions(user, rwsis, Permission.READ, false);
	}
	
	@Override
	public Map<User, Permission> getAllPermissions(
			final ResolvedWorkspaceID rwsi) throws
			WorkspaceCommunicationException, CorruptWorkspaceDBException {
		return query.queryPermissions(query.convertResolvedWSID(rwsi));
	}

	private static final Set<String> FLDS_WS_NO_DESC = 
			newHashSet(Fields.WS_ID, Fields.WS_NAME, Fields.WS_OWNER,
					Fields.WS_MODDATE, Fields.WS_NUMOBJ, Fields.WS_DEL,
					Fields.WS_LOCKED, Fields.WS_META);
	
	@Override
	public List<WorkspaceInformation> getWorkspaceInformation(
			final PermissionSet pset, final List<WorkspaceUser> owners,
			final Map<String, String> meta, final Date after,
			final Date before, final boolean showDeleted, 
			final boolean showOnlyDeleted)
			throws WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		if (!(pset instanceof MongoPermissionSet)) {
			throw new IllegalArgumentException(
					"Illegal implementation of PermissionSet: " +
					pset.getClass().getName());
		}
		final Map<Long, ResolvedMongoWSID> rwsis =
				new HashMap<Long, ResolvedMongoWSID>();
		for (final ResolvedWorkspaceID rwsi: pset.getWorkspaces()) {
			rwsis.put(rwsi.getID(), query.convertResolvedWSID(rwsi));
		}
		final DBObject q = new BasicDBObject(Fields.WS_ID,
				new BasicDBObject("$in", rwsis.keySet()));
		if (owners != null && !owners.isEmpty()) {
			q.put(Fields.WS_OWNER, new BasicDBObject("$in",
					convertWorkspaceUsers(owners)));
		}
		if (meta != null && !meta.isEmpty()) {
			final List<DBObject> andmetaq = new LinkedList<DBObject>();
			for (final Entry<String, String> e: meta.entrySet()) {
				final DBObject mentry = new BasicDBObject();
				mentry.put(Fields.META_KEY, e.getKey());
				mentry.put(Fields.META_VALUE, e.getValue());
				andmetaq.add(new BasicDBObject(Fields.WS_META, mentry));
			}
			q.put("$and", andmetaq); //note more than one entry is untested
		}
		if (before != null || after != null) {
			final DBObject d = new BasicDBObject();
			if (before != null) {
				d.put("$lt", before);
			}
			if (after != null) {
				d.put("$gt", after);
			}
			q.put(Fields.WS_MODDATE, d);
		}
		final List<Map<String, Object>> ws = query.queryCollection(
				COL_WORKSPACES, q, FLDS_WS_NO_DESC);
		
		final List<WorkspaceInformation> ret =
				new LinkedList<WorkspaceInformation>();
		for (final Map<String, Object> w: ws) {
			final ResolvedWorkspaceID rwsi =
					rwsis.get((Long) w.get(Fields.WS_ID));
			final boolean isDeleted = (Boolean) w.get(Fields.WS_DEL);
			if (showOnlyDeleted) {
				if (isDeleted &&
						pset.hasUserPermission(rwsi, Permission.OWNER)) {
					ret.add(generateWSInfo(rwsi, pset, w));
				}
			} else if (!isDeleted || (showDeleted &&
					pset.hasUserPermission(rwsi, Permission.OWNER))) {
				ret.add(generateWSInfo(rwsi, pset, w));
			}
		}
		return ret;
	}

	private List<String> convertWorkspaceUsers(final List<WorkspaceUser> owners) {
		final List<String> own = new ArrayList<String>();
		for (final WorkspaceUser wu: owners) {
			own.add(wu.getUser());
		}
		return own;
	}
	
	@Override
	public WorkspaceInformation getWorkspaceInformation(
			final WorkspaceUser user, final ResolvedWorkspaceID rwsi)
			throws WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		final ResolvedMongoWSID m = query.convertResolvedWSID(rwsi);
		final Map<String, Object> ws = query.queryWorkspace(m,
				FLDS_WS_NO_DESC);
		final PermissionSet perms = getPermissions(user, m);
		return generateWSInfo(rwsi, perms, ws);
	}

	private WorkspaceInformation generateWSInfo(final ResolvedWorkspaceID rwsi,
			final PermissionSet perms, final Map<String, Object> wsdata) {
		
		@SuppressWarnings("unchecked")
		final List<Map<String, String>> meta =
				(List<Map<String, String>>) wsdata.get(Fields.WS_META);
		return new MongoWSInfo((Long) wsdata.get(Fields.WS_ID),
				(String) wsdata.get(Fields.WS_NAME),
				new WorkspaceUser((String) wsdata.get(Fields.WS_OWNER)),
				(Date) wsdata.get(Fields.WS_MODDATE),
				(Long) wsdata.get(Fields.WS_NUMOBJ),
				perms.getUserPermission(rwsi),
				perms.isWorldReadable(rwsi),
				(Boolean) wsdata.get(Fields.WS_LOCKED),
				metaMongoArrayToHash(meta));
	}
	
	private Map<ObjectIDNoWSNoVer, ResolvedMongoObjectID> resolveObjectIDs(
			final ResolvedMongoWSID workspaceID,
			final Set<ObjectIDNoWSNoVer> objects) throws
			WorkspaceCommunicationException {
		
		final Map<ObjectIDNoWSNoVer, ObjectIDResolvedWS> queryobjs = 
				new HashMap<ObjectIDNoWSNoVer, ObjectIDResolvedWS>();
		for (final ObjectIDNoWSNoVer o: objects) {
			queryobjs.put(o, new ObjectIDResolvedWS(workspaceID, o));
		}
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> res;
		try {
			res = resolveObjectIDs(
					new HashSet<ObjectIDResolvedWS>(queryobjs.values()),
					false, false);
		} catch (NoSuchObjectException nsoe) {
			throw new RuntimeException(
					"Threw a NoSuchObjectException when explicitly told not to");
		}
		final Map<ObjectIDNoWSNoVer, ResolvedMongoObjectID> ret = 
				new HashMap<ObjectIDNoWSNoVer, ResolvedMongoObjectID>();
		for (final ObjectIDNoWSNoVer o: objects) {
			if (res.containsKey(queryobjs.get(o))) {
				ret.put(o, res.get(queryobjs.get(o)));
			}
		}
		return ret;
	}
	
	// save object in preexisting object container
	private ObjectInformation saveObjectVersion(final WorkspaceUser user,
			final ResolvedMongoWSID wsid, final long objectid,
			final ObjectSavePackage pkg)
			throws WorkspaceCommunicationException {
		final Map<String, Object> version = new HashMap<String, Object>();
		version.put(Fields.VER_SAVEDBY, user.getUser());
		version.put(Fields.VER_CHKSUM, pkg.td.getChksum());
		version.put(Fields.VER_META, metaHashToMongoArray(
				pkg.wo.getUserMeta()));
		version.put(Fields.VER_REF, pkg.refs);
		version.put(Fields.VER_PROVREF, pkg.provrefs);
		version.put(Fields.VER_PROV, pkg.mprov.getMongoId());
		version.put(Fields.VER_TYPE, pkg.wo.getRep().getValidationTypeDefId()
				.getTypeString());
		version.put(Fields.VER_SIZE, pkg.td.getSize());
		version.put(Fields.VER_RVRT, null);
		version.put(Fields.VER_COPIED, null);
		saveObjectVersions(user, wsid, objectid, Arrays.asList(version),
				pkg.wo.isHidden());
		
		return new MongoObjectInfo(objectid, pkg.name,
				pkg.wo.getRep().getValidationTypeDefId().getTypeString(),
				(Date) version.get(Fields.VER_SAVEDATE),
				(Integer) version.get(Fields.VER_VER),
				user, wsid, pkg.td.getChksum(), pkg.td.getSize(),
				pkg.wo.getUserMeta() == null ? new HashMap<String, String>() :
						pkg.wo.getUserMeta());
	}

	private List<Map<String, String>> metaHashToMongoArray(
			final Map<String, String> usermeta) {
		final List<Map<String, String>> meta = 
				new ArrayList<Map<String, String>>();
		if (usermeta != null) {
			for (String key: usermeta.keySet()) {
				Map<String, String> m = new LinkedHashMap<String, String>(2);
				m.put(Fields.META_KEY, key);
				m.put(Fields.META_VALUE, usermeta.get(key));
				meta.add(m);
			}
		}
		return meta;
	}
	

	private static final String M_SAVEINS_QRY = String.format("{%s: #, %s: #}",
			Fields.OBJ_WS_ID, Fields.OBJ_ID);
	private static final String M_SAVEINS_PROJ = String.format("{%s: 1, %s: 0}",
			Fields.OBJ_VCNT, Fields.MONGO_ID);
	private static final String M_SAVEINS_WTH = String.format(
			"{$inc: {%s: #}, $set: {%s: false, %s: #, %s: null, %s: #}, $push: {%s: {$each: #}}}",
			Fields.OBJ_VCNT, Fields.OBJ_DEL, Fields.OBJ_MODDATE,
			Fields.OBJ_LATEST, Fields.OBJ_HIDE, Fields.OBJ_REFCOUNTS);
	private static final String M_SAVEINS_NO_HIDE_WTH = String.format(
			"{$inc: {%s: #}, $set: {%s: false, %s: #, %s: null}, $push: {%s: {$each: #}}}",
			Fields.OBJ_VCNT, Fields.OBJ_DEL, Fields.OBJ_MODDATE,
			Fields.OBJ_LATEST, Fields.OBJ_REFCOUNTS);
	
	private void saveObjectVersions(final WorkspaceUser user,
			final ResolvedMongoWSID wsid, final long objectid,
			final List<Map<String, Object>> versions, final Boolean hidden)
			throws WorkspaceCommunicationException {
		// collection objects might be batchable if saves are slow
		/* TODO deal with rare failure modes below as much as possible at some point. Not high prio since rare
		 * 1) save an object, crash w/ 0 versions. 2) increment versions, crash w/o saving
		 * check all places counter incremented (ws, obj, ver) to see if any other problems
		 * known issues in resolveObjects and listObjects
		 * can't necc count on the fact that vercount or latestVersion is accurate
		 * ignore listObjs for now, in resolveObjs mark vers with class and
		 * have queryVersions pull the right version if it's missing. Make a test for this.
		 * Have queryVersions revert to the newest version if the latest is missing, autorevert
		 * 
		 * None of the above addresses the object w/ 0 versions failure. Not sure what to do about that.
		 * 
		*/
		int ver;
		final List<Integer> zeros = new LinkedList<Integer>();
		for (int i = 0; i < versions.size(); i++) {
			zeros.add(0);
		}
		final Date saved = new Date();
		try {
			FindAndModify q = wsjongo.getCollection(COL_WORKSPACE_OBJS)
					.findAndModify(M_SAVEINS_QRY, wsid.getID(), objectid)
					.returnNew();
			if (hidden == null) {
				q = q.with(M_SAVEINS_NO_HIDE_WTH, versions.size(),
						saved, zeros);
			} else {
				q = q.with(M_SAVEINS_WTH, versions.size(), saved,
						hidden, zeros);
			}
			ver = (Integer) q
					.projection(M_SAVEINS_PROJ).as(DBObject.class)
					.get(Fields.OBJ_VCNT)
					- versions.size() + 1;
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		//TODO look into why saving array of maps via List.ToArray() /w Jongo makes Lazy?Objects return, which screw up everything
		final List<DBObject> dbo = new LinkedList<DBObject>();
		for (final Map<String, Object> v: versions) {
			v.put(Fields.VER_SAVEDATE, saved);
			v.put(Fields.VER_WS_ID, wsid.getID());
			v.put(Fields.VER_ID, objectid);
			v.put(Fields.VER_VER, ver++);
			final DBObject d = new BasicDBObject();
			for (final Entry<String, Object> e: v.entrySet()) {
				d.put(e.getKey(), e.getValue());
			}
			dbo.add(d);
		}

		try {
			wsmongo.getCollection(COL_WORKSPACE_VERS).insert(dbo);
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
	}
	
	//TODO make all projections not include _id unless specified
	
	private static final String M_UNIQ_NAME_QRY = String.format(
			"{%s: #, %s: {$regex: '^#(-\\\\d+)?$'}}", Fields.OBJ_WS_ID,
			Fields.OBJ_NAME);
	private static final String M_UNIQ_NAME_PROJ = String.format(
			"{%s: 1, %s: 0}", Fields.OBJ_NAME, Fields.MONGO_ID);
	
	private String generateUniqueNameForObject(final ResolvedWorkspaceID wsid,
			final long objectid) throws WorkspaceCommunicationException {
		final String prefix = "auto" + objectid;
		@SuppressWarnings("rawtypes")
		final Iterable<Map> ids;
		boolean exact = false;
		final Set<Long> suffixes = new HashSet<Long>();
		try {
			ids = wsjongo.getCollection(COL_WORKSPACE_OBJS)
					.find(M_UNIQ_NAME_QRY, wsid.getID(), prefix)
					.projection(M_UNIQ_NAME_PROJ).as(Map.class);
			for (@SuppressWarnings("rawtypes") Map m: ids) {
				final String[] id = ((String) m.get(Fields.OBJ_NAME))
						.split("-");
				if (id.length == 2) {
					try {
						suffixes.add(Long.parseLong(id[1]));
					} catch (NumberFormatException e) {
						// do nothing
					}
				} else if (id.length == 1) {
					try {
						exact = exact || prefix.equals(id[0]);
					} catch (NumberFormatException e) {
						// do nothing
					}
				}
			}
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		if (!exact) {
			return prefix;
		}
		long counter = 1;
		while (suffixes.contains(counter)) {
			counter++;
		}
		return prefix + "-" + counter;
	}
	
	//save brand new object - create container
	//objectid *must not exist* in the workspace otherwise this method will recurse indefinitely
	//the workspace must exist
	private IDName saveWorkspaceObject(
			final ResolvedMongoWSID wsid, final long objectid,
			final String name)
			throws WorkspaceCommunicationException {
		String newName = name;
		if (name == null) {
			newName = generateUniqueNameForObject(wsid, objectid);
		}
		final DBObject dbo = new BasicDBObject();
		dbo.put(Fields.OBJ_WS_ID, wsid.getID());
		dbo.put(Fields.OBJ_ID, objectid);
		dbo.put(Fields.OBJ_VCNT, 0); //Integer
		dbo.put(Fields.OBJ_REFCOUNTS, new LinkedList<Integer>());
		dbo.put(Fields.OBJ_NAME, newName);
		dbo.put(Fields.OBJ_LATEST, null);
		dbo.put(Fields.OBJ_DEL, false);
		dbo.put(Fields.OBJ_HIDE, false);
		try {
			//maybe could speed things up with batch inserts but dealing with
			//errors would really suck
			//do this later if it becomes a bottleneck
			wsmongo.getCollection(COL_WORKSPACE_OBJS).insert(dbo);
		} catch (MongoException.DuplicateKey dk) {
			//ok, someone must've just this second added this name to an object
			//asshole
			//this should be a rare event
			//TODO is this a name or id clash? if the latter, something is broken
			if (name == null) {
				//not much chance of this happening again, let's just recurse
				//and make a new name again
				return saveWorkspaceObject(wsid, objectid, name);
			}
			final ObjectIDNoWSNoVer o = new ObjectIDNoWSNoVer(name);
			final Map<ObjectIDNoWSNoVer, ResolvedMongoObjectID> objID =
					resolveObjectIDs(wsid,
							new HashSet<ObjectIDNoWSNoVer>(Arrays.asList(o)));
			if (objID.isEmpty()) {
				//oh ffs, name deleted again, try again
				return saveWorkspaceObject(wsid, objectid, name);
			}
			//save version via the id associated with our name which already exists
			return new IDName(objID.get(o).getId(), objID.get(o).getName());
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		return new IDName(objectid, newName);
	}
	
	private class IDName {
		
		public long id;
		public String name;
		
		public IDName(long id, String name) {
			super();
			this.id = id;
			this.name = name;
		}

		@Override
		public String toString() {
			return "IDName [id=" + id + ", name=" + name + "]";
		}
	}
	
	private static class ObjectSavePackage {
		
		public ResolvedSaveObject wo;
		public String name;
		public TypeData td;
		public Set<String> refs;
		public List<String> provrefs;
		public MongoProvenance mprov;
		
		@Override
		public String toString() {
			return "ObjectSavePackage [wo=" + wo + ", name=" + name + ", td="
					+ td + ", mprov =" + mprov +  "]";
		}
	}
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	private static String getObjectErrorId(final ObjectIDNoWSNoVer oi,
			final int objcount) {
		String objErrId = "#" + objcount;
		objErrId += oi == null ? "" : ", " + oi.getIdentifierString();
		return objErrId;
	}
	
	//at this point the objects are expected to be validated and references rewritten
	private List<ObjectSavePackage> saveObjectsBuildPackages(
			final List<ResolvedSaveObject> objects) {
		//this method must maintain the order of the objects
		int objnum = 1;
		final List<ObjectSavePackage> ret = new LinkedList<ObjectSavePackage>();
		for (ResolvedSaveObject o: objects) {
			if (o.getRep().getValidationTypeDefId().getMd5() != null) {
				throw new RuntimeException("MD5 types are not accepted");
			}
			final ObjectSavePackage pkg = new ObjectSavePackage();
			pkg.refs = checkRefsAreMongo(o.getRefs());
			//cannot do by combining in one set since a non-MongoReference
			//could be overwritten by a MongoReference if they have the same
			//hash
			pkg.provrefs = checkRefsAreMongo(o.getProvRefs());
			pkg.wo = o;
			checkObjectLength(o.getProvenance(), MAX_PROV_SIZE,
					o.getObjectIdentifier(), objnum, "provenance");
			
			final Map<String, Object> subdata;
			try {
				ExtractedSubsetAndMetadata extract = o.getRep().extractSearchableWsSubsetAndMetadata(MAX_SUBDATA_SIZE);
				@SuppressWarnings("unchecked")
				final Map<String, Object> subdata2 = (Map<String, Object>)
						MAPPER.treeToValue(
								extract.getWsSearchableSubset(),
								Map.class);
				subdata = subdata2;
				pkg.wo.addUserMeta(extract.getMetadataAsMap());
			} catch (JsonProcessingException jpe) {
				throw new RuntimeException(
						"Should never get a JSON exception here", jpe);
			} catch (IllegalArgumentException e) {
				if (e.getMessage().contains("" + MAX_SUBDATA_SIZE))
					throw new IllegalArgumentException(String.format(
							"Object %s %s size exceeds limit of %s",
							getObjectErrorId(o.getObjectIdentifier(), objnum),
							"subdata", MAX_SUBDATA_SIZE));
				throw e;
			}
			
			
			escapeSubdata(subdata);
//			checkObjectLength(subdata, MAX_SUBDATA_SIZE,
//					o.getObjectIdentifier(), objnum, "subdata");
			//could save time by making type->data->TypeData map and reusing
			//already calced TDs, but hardly seems worth it - unlikely event
			pkg.td = new TypeData(o.getRep().createJsonWritable(),
					o.getRep().getValidationTypeDefId(), subdata);
			if (pkg.td.getSize() > rescfg.getMaxObjectSize()) {
				throw new IllegalArgumentException(String.format(
						"Object %s data size %s exceeds limit of %s",
						getObjectErrorId(o.getObjectIdentifier(), objnum),
						pkg.td.getSize(), rescfg.getMaxObjectSize()));
			}
			ret.add(pkg);
			objnum++;
		}
		return ret;
	}
	
	//is there some way to combine these with generics?
	private Set<String> checkRefsAreMongo(final Set<Reference> refs) {
		final Set<String> newrefs = new HashSet<String>();
		checkRefsAreMongoInternal(refs, newrefs);
		return newrefs;
	}
	
	//order must be maintained
	private List<String> checkRefsAreMongo(final List<Reference> refs) {
		final List<String> newrefs = new LinkedList<String>();
		checkRefsAreMongoInternal(refs, newrefs);
		return newrefs;
	}

	private void checkRefsAreMongoInternal(final Collection<Reference> refs,
			final Collection<String> newrefs) {
		for (final Reference r: refs) {
			if (!(r instanceof MongoReference)) {
				throw new RuntimeException(
						"Improper reference implementation: " +
						(r == null ? null : r.getClass()));
			}
			newrefs.add(r.toString());
		}
	}

	private void checkObjectLength(final Object o, final long max,
			final ObjectIDNoWSNoVer oi, final int objnum,
			final String objtype) {
		final CountingOutputStream cos = new CountingOutputStream();
		try {
			//writes in UTF8
			MAPPER.writeValue(cos, o);
		} catch (IOException ioe) {
			throw new RuntimeException("something's broken", ioe);
		} finally {
			try {
				cos.close();
			} catch (IOException ioe) {
				throw new RuntimeException("something's broken", ioe);
			}
		}
		if (cos.getSize() > max) {
			throw new IllegalArgumentException(String.format(
					"Object %s %s size %s exceeds limit of %s",
					getObjectErrorId(oi, objnum), objtype, cos.getSize(), max));
		}
	}
	
	private void escapeSubdata(final Map<String, Object> subdata) {
		escapeSubdataInternal(subdata);
	}

	//rewrite w/o recursion?
	private Object escapeSubdataInternal(final Object o) {
		if (o instanceof String || o instanceof Number ||
				o instanceof Boolean || o == null) {
			return o;
		} else if (o instanceof List) {
			@SuppressWarnings("unchecked")
			final List<Object> l = (List<Object>)o;
			for (Object lo: l) {
				escapeSubdataInternal(lo);
			}
			return o;
		} else if (o instanceof Map) {
			@SuppressWarnings("unchecked")
			final Map<String, Object> m = (Map<String, Object>)o;
			//save updated keys in separate map so we don't overwrite
			//keys before they're escaped
			final Map<String, Object> newm = new HashMap<String, Object>();
			final Iterator<Entry<String, Object>> iter = m.entrySet().iterator();
			while (iter.hasNext()) {
				final Entry<String, Object> e = iter.next();
				final String key = e.getKey();
				//need side effect
				final Object value = escapeSubdataInternal(e.getValue());
				final String newkey = mongoHTMLEscape(key);
				//works since mongoHTMLEscape returns same string object if no change
				if (key != newkey) {
					iter.remove();
					newm.put(newkey, value);
				}
			}
			m.putAll(newm);
			return o;
		} else {
			throw new RuntimeException("Unsupported class: " + o.getClass());
		}
	}
	
	private static final int CODEPOINT_PERC = new String("%").codePointAt(0);
	private static final int CODEPOINT_DLR = new String("$").codePointAt(0);
	private static final int CODEPOINT_PNT = new String(".").codePointAt(0);
	
	//might be faster just using std string replace() method
	private String mongoHTMLEscape(final String s) {
		final StringBuilder ret = new StringBuilder();
		boolean mod = false;
		for (int offset = 0; offset < s.length(); ) {
			final int codepoint = s.codePointAt(offset);
			if (codepoint == CODEPOINT_PERC) {
				ret.append("%25");
				mod = true;
			} else if (codepoint == CODEPOINT_DLR) {
				ret.append("%24");
				mod = true;
			} else if (codepoint == CODEPOINT_PNT) {
				ret.append("%2e");
				mod = true;
			} else {
				ret.appendCodePoint(codepoint);
			}
			offset += Character.charCount(codepoint);
		}
		if (mod) {
			return ret.toString();
		} else {
			return s;
		}
	}
	
	private static final String M_SAVE_WTH = String.format("{$inc: {%s: #}}",
					Fields.WS_NUMOBJ);
	private static final String M_SAVE_PROJ = String.format("{%s: 1, %s: 0}",
			Fields.WS_NUMOBJ, Fields.MONGO_ID);
			
	//at this point the objects are expected to be validated and references rewritten
	@Override
	public List<ObjectInformation> saveObjects(final WorkspaceUser user, 
			final ResolvedWorkspaceID rwsi,
			final List<ResolvedSaveObject> objects)
			throws WorkspaceCommunicationException,
			NoSuchObjectException {
		//TODO break this up
		//this method must maintain the order of the objects
		
		final ResolvedMongoWSID wsidmongo = query.convertResolvedWSID(rwsi);
		final List<ObjectSavePackage> packages = saveObjectsBuildPackages(
				objects);
		final Map<ObjectIDNoWSNoVer, List<ObjectSavePackage>> idToPkg =
				new HashMap<ObjectIDNoWSNoVer, List<ObjectSavePackage>>();
		int newobjects = 0;
		for (final ObjectSavePackage p: packages) {
			final ObjectIDNoWSNoVer o = p.wo.getObjectIdentifier();
			if (o != null) {
				if (idToPkg.get(o) == null) {
					idToPkg.put(o, new ArrayList<ObjectSavePackage>());
				}
				idToPkg.get(o).add(p);
			} else {
				newobjects++;
			}
		}
		final Map<ObjectIDNoWSNoVer, ResolvedMongoObjectID> objIDs =
				resolveObjectIDs(wsidmongo, idToPkg.keySet());
		for (ObjectIDNoWSNoVer o: idToPkg.keySet()) {
			if (!objIDs.containsKey(o)) {
				if (o.getId() != null) {
					throw new NoSuchObjectException(
							"There is no object with id " + o.getId());
				} else {
					for (ObjectSavePackage pkg: idToPkg.get(o)) {
						pkg.name = o.getName();
					}
					newobjects++;
				}
			} else {
				for (ObjectSavePackage pkg: idToPkg.get(o)) {
					pkg.name = objIDs.get(o).getName();
				}
			}
		}
		//at this point everything should be ready to save, only comm errors
		//can stop us now, the world is doomed
		saveData(wsidmongo, packages);
		saveProvenance(packages);
		updateReferenceCounts(packages);
		long newid = incrementWorkspaceCounter(wsidmongo, newobjects);
		/*  alternate impl: 1) make all save objects 2) increment all version
		 *  counters 3) batch save versions
		 *  This probably won't help much. Firstly, saving the same object
		 *  multiple times (e.g. save over the same object in the same
		 *  saveObjects call) is going to be a rare op - who wants to do that?
		 *  Hence batching up the version increments is probably not going to
		 *  help much.
		 *  Secondly, the write lock is on a per document basis, so batching
		 *  writes has no effect on write locking.
		 *  That means that the gain from batching writes is removal of the 
		 *  flight time to/from the server between each object. This may
		 *  be significant for many small objects, but is probably
		 *  insignificant for a few objects, or many large objects.
		 *  Summary: probably not worth the trouble and increase in code
		 *  complexity.
		 */
		final List<ObjectInformation> ret = new ArrayList<ObjectInformation>();
		final Map<String, Long> seenNames = new HashMap<String, Long>();
		for (final ObjectSavePackage p: packages) {
			final ObjectIDNoWSNoVer oi = p.wo.getObjectIdentifier();
			if (oi == null) { //no name given, need to generate one
				final IDName obj = saveWorkspaceObject(wsidmongo, newid++,
						null);
				p.name = obj.name;
				ret.add(saveObjectVersion(user, wsidmongo, obj.id, p));
			} else if (oi.getId() != null) { //confirmed ok id
				ret.add(saveObjectVersion(user, wsidmongo, oi.getId(), p));
			} else if (objIDs.get(oi) != null) {//given name translated to id
				ret.add(saveObjectVersion(user, wsidmongo, objIDs.get(oi).getId(), p));
			} else if (seenNames.containsKey(oi.getName())) {
				//we've already generated an id for this name
				ret.add(saveObjectVersion(user, wsidmongo, seenNames.get(oi.getName()), p));
			} else {//new name, need to generate new id
				final IDName obj = saveWorkspaceObject(wsidmongo, newid++,
						oi.getName());
				p.name = obj.name;
				seenNames.put(obj.name, obj.id);
				ret.add(saveObjectVersion(user, wsidmongo, obj.id, p));
			}
		}
		updateWorkspaceModifiedDate(wsidmongo);
		return ret;
	}

	//returns starting object number
	private long incrementWorkspaceCounter(final ResolvedMongoWSID wsidmongo,
			final int newobjects) throws WorkspaceCommunicationException {
		final long lastid;
		try {
			lastid = ((Number) wsjongo.getCollection(COL_WORKSPACES)
					.findAndModify(M_WS_ID_QRY, wsidmongo.getID())
					.returnNew().with(M_SAVE_WTH, (long) newobjects)
					.projection(M_SAVE_PROJ)
					.as(DBObject.class).get(Fields.WS_NUMOBJ)).longValue();
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		long newid = lastid - newobjects + 1;
		return newid;
	}
	
	private void saveProvenance(final List<ObjectSavePackage> packages)
			throws WorkspaceCommunicationException {
		final List<MongoProvenance> prov = new LinkedList<MongoProvenance>();
		for (final ObjectSavePackage p: packages) {
			final MongoProvenance mp = new MongoProvenance(
					p.wo.getProvenance());
			prov.add(mp);
			p.mprov = mp;
		}
		try {
			wsjongo.getCollection(COL_PROVENANCE).insert((Object[])
					prov.toArray(new MongoProvenance[prov.size()]));
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
	}

	private static class VerCount {
		final public int ver;
		final public int count;

		public VerCount (final int ver, final int count) {
			this.ver = ver;
			this.count = count;
		}
		
		@Override
		public String toString() {
			return "VerCount [ver=" + ver + ", count=" + count + "]";
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + count;
			result = prime * result + ver;
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			VerCount other = (VerCount) obj;
			if (count != other.count)
				return false;
			if (ver != other.ver)
				return false;
			return true;
		}
	}
	
	private void updateReferenceCounts(final List<ObjectSavePackage> packages)
			throws WorkspaceCommunicationException {
		//TODO when garbage collection working much more testing of these methods
		final Map<Long, Map<Long, Map<Integer, Counter>>> refcounts = 
				countReferences(packages);
		/* since the version numbers are probably highly skewed towards 1 and
		 * the reference counts are also highly skewed towards 1 we can 
		 * probably minimize the number of updates by running one update
		 * per version/count combination
		 */
		updateReferenceCounts(refcounts);
	}
	
	private void updateReferenceCountsForVersions(
			final List<Map<String, Object>> versions)
			throws WorkspaceCommunicationException {
		//TODO when garbage collection working much more testing of these methods
		final Map<Long, Map<Long, Map<Integer, Counter>>> refcounts = 
				countReferencesForVersions(versions);
		/* since the version numbers are probably highly skewed towards 1 and
		 * the reference counts are also highly skewed towards 1 we can 
		 * probably minimize the number of updates by running one update
		 * per version/count combination
		 */
		updateReferenceCounts(refcounts);
	}

	private void updateReferenceCounts(
			final Map<Long, Map<Long, Map<Integer, Counter>>> refcounts)
			throws WorkspaceCommunicationException {
		final Map<VerCount, Map<Long, List<Long>>> queries = 
				new HashMap<VerCount, Map<Long,List<Long>>>();
		for (final Long ws: refcounts.keySet()) {
			for (final Long obj: refcounts.get(ws).keySet()) {
				for (final Integer ver: refcounts.get(ws).get(obj).keySet()) {
					final VerCount vc = new VerCount(ver,
							refcounts.get(ws).get(obj).get(ver).getValue());
					if (!queries.containsKey(vc)) {
						queries.put(vc, new HashMap<Long, List<Long>>());
					}
					if (!queries.get(vc).containsKey(ws)) {
						queries.get(vc).put(ws, new LinkedList<Long>());
					}
					queries.get(vc).get(ws).add(obj);
				}
			}
		}
		for (final VerCount vc: queries.keySet()) {
			updateReferenceCounts(vc, queries.get(vc));
		}
	}

	private void updateReferenceCounts(final VerCount vc,
			final Map<Long, List<Long>> wsToObjs)
			throws WorkspaceCommunicationException {
		final DBObject update = new BasicDBObject("$inc",
				new BasicDBObject(Fields.OBJ_REFCOUNTS + "." + (vc.ver - 1),
						vc.count));
		final List<DBObject> orquery = new LinkedList<DBObject>();
		for (final Long ws: wsToObjs.keySet()) {
			final DBObject query = new BasicDBObject(Fields.OBJ_WS_ID, ws);
			query.put(Fields.OBJ_ID, new BasicDBObject("$in",
					wsToObjs.get(ws)));
			orquery.add(query);
		}
		try {
			wsmongo.getCollection(COL_WORKSPACE_OBJS).update(
					new BasicDBObject("$or", orquery), update, false, true);
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
	}

	private Map<Long, Map<Long, Map<Integer, Counter>>> countReferences(
			final List<ObjectSavePackage> packages) {
		final Map<Long, Map<Long, Map<Integer, Counter>>> refcounts =
				new HashMap<Long, Map<Long,Map<Integer,Counter>>>();
		for (final ObjectSavePackage p: packages) {
			//these were checked to be MongoReferences in saveObjectBuildPackages
			final Set<Reference> refs = new HashSet<Reference>();
			refs.addAll(p.wo.getRefs());
			refs.addAll(p.wo.getProvRefs());
			countReferences(refcounts, refs);
		}
		return refcounts;
	}
	
	private Map<Long, Map<Long, Map<Integer, Counter>>> countReferencesForVersions(
			final List<Map<String, Object>> versions) {
		final Map<Long, Map<Long, Map<Integer, Counter>>> refcounts =
				new HashMap<Long, Map<Long,Map<Integer,Counter>>>();
		for (final Map<String, Object> p: versions) {
			//these were checked to be MongoReferences in saveObjectBuildPackages
			final Set<Reference> refs = new HashSet<Reference>();
			@SuppressWarnings("unchecked")
			final List<String> objrefs = (List<String>) p.get(Fields.VER_REF);
			@SuppressWarnings("unchecked")
			final List<String> provrefs = (List<String>) p.get(Fields.VER_PROVREF);
//			objrefs.addAll(provrefs); //DON'T DO THIS YOU MORON
			for (final String s: objrefs) {
				refs.add(new MongoReference(s));
			}
			for (final String s: provrefs) {
				refs.add(new MongoReference(s));
			}
			countReferences(refcounts, refs);
		}
		return refcounts;
	}

	private void countReferences(
			final Map<Long, Map<Long, Map<Integer, Counter>>> refcounts,
			final Set<Reference> refs) {
		for (final Reference r: refs) {
			if (!refcounts.containsKey(r.getWorkspaceID())) {
				refcounts.put(r.getWorkspaceID(),
						new HashMap<Long, Map<Integer, Counter>>());
			}
			if (!refcounts.get(r.getWorkspaceID())
					.containsKey(r.getObjectID())) {
				refcounts.get(r.getWorkspaceID()).put(r.getObjectID(),
						new HashMap<Integer, Counter>());
			}
			if (!refcounts.get(r.getWorkspaceID()).get(r.getObjectID())
					.containsKey(r.getVersion())) {
				refcounts.get(r.getWorkspaceID()).get(r.getObjectID())
					.put(r.getVersion(), new Counter());
			}
			refcounts.get(r.getWorkspaceID()).get(r.getObjectID())
				.get(r.getVersion()).increment();
		}
	}

	//this whole method needs a rethink now we're dealing with Writeables
	//could have the blob store calc & return the size & MD5
	private void saveData(final ResolvedMongoWSID workspaceid,
			final List<ObjectSavePackage> data) throws
			WorkspaceCommunicationException {
		final Map<TypeDefId, List<ObjectSavePackage>> pkgByType =
				new HashMap<TypeDefId, List<ObjectSavePackage>>();
		for (final ObjectSavePackage p: data) {
			if (pkgByType.get(p.td.getType()) == null) {
				pkgByType.put(p.td.getType(),
						new ArrayList<ObjectSavePackage>());
			}
			pkgByType.get(p.td.getType()).add(p);
		}
		try {
			for (final TypeDefId type: pkgByType.keySet()) {
				ensureTypeIndex(type);
				final String col = TypeData.getTypeCollection(type);
				final Map<String, TypeData> chksum =
						new HashMap<String, TypeData>();
				for (ObjectSavePackage p: pkgByType.get(type)) {
					chksum.put(p.td.getChksum(), p.td);
				}
				final Set<String> existChksum = getExistingMD5sInCollection(
						col, chksum.keySet());
				final List<TypeData> newdata = new ArrayList<TypeData>();
				for (String md5: chksum.keySet()) { //better set operators in java would be nice
					if (existChksum.contains(md5)) {
						continue;
					}
					newdata.add(chksum.get(md5));
					try {
						//this is kind of stupid, but no matter how you slice
						//it you have to calc md5s before you save the data
						blob.saveBlob(new MD5(md5), chksum.get(md5).getData(),
								true); //always sorted in 0.2.0+
					} catch (BlobStoreCommunicationException e) {
						throw new WorkspaceCommunicationException(
								e.getLocalizedMessage(), e);
					} catch (BlobStoreAuthorizationException e) {
						throw new WorkspaceCommunicationException(
								"Authorization error communicating with the backend storage system",
								e);
					}
				}
				for (final TypeData td: newdata) {
					try {
						wsjongo.getCollection(col).insert(td);
					} catch (MongoException.DuplicateKey dk) {
						// Was just inserted by another
						// thread, which is fine - do nothing
					} catch (MongoException me) {
						throw new WorkspaceCommunicationException(
								"There was a problem communicating with the database",
								me);
					}
				}
			}
		} finally {
			for (ObjectSavePackage wo: data) {
				try {
					wo.td.getData().releaseResources();
				} catch (IOException ioe) {
					//ok, we just possibly left a temp file on disk,
					//but it's not worth interrupting the entire call for
				}
			}
		}
	}

	private Set<String> getExistingMD5sInCollection(final String col,
			Set<String> md5s) throws WorkspaceCommunicationException {
		final DBObject query = new BasicDBObject(Fields.TYPE_CHKSUM,
				new BasicDBObject("$in", new ArrayList<String>(
						md5s)));
		final DBObject proj = new BasicDBObject(Fields.TYPE_CHKSUM, 1);
		proj.put(Fields.MONGO_ID, 0);
		final Set<String> existChksum = new HashSet<String>();
		try {
			final DBCursor res = wsmongo.getCollection(col)
					.find(query, proj);
			for (DBObject dbo: res) {
				existChksum.add((String)dbo.get(Fields.TYPE_CHKSUM));
			}
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database",
					me);
		}
		return existChksum;
	}

	private static final Set<String> FLDS_VER_GET_OBJECT_SUBDATA = newHashSet(
			Fields.VER_VER, Fields.VER_TYPE, Fields.VER_CHKSUM);
	
	private static final String M_GETOBJSUB_QRY = String.format(
			"{%s: {$in: #}}", Fields.TYPE_CHKSUM);
	private static final String M_GETOBJSUB_PROJ = String.format(
			"{%s: 1, %s: 1, %s: 0}",
			Fields.TYPE_CHKSUM, Fields.TYPE_SUBDATA, Fields.MONGO_ID);
	
	public Map<ObjectIDResolvedWS, Map<String, Object>> getObjectSubData(
			final Set<ObjectIDResolvedWS> objectIDs)
			throws NoSuchObjectException, WorkspaceCommunicationException {
		//keep doing the next two lines over and over, should probably extract
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> oids =
				resolveObjectIDs(objectIDs);
		final Map<ResolvedMongoObjectID, Map<String, Object>> vers = 
				query.queryVersions(
						new HashSet<ResolvedMongoObjectID>(oids.values()),
						FLDS_VER_GET_OBJECT_SUBDATA);
		final Map<TypeDefId, Map<String, Set<ObjectIDResolvedWS>>> toGet =
				new HashMap<TypeDefId, Map<String,Set<ObjectIDResolvedWS>>>();
		for (final ObjectIDResolvedWS oid: objectIDs) {
			final ResolvedMongoObjectID roi = oids.get(oid);
			if (!vers.containsKey(roi)) {
				throw new NoSuchObjectException(String.format(
						"No object with id %s (name %s) and version %s exists "
						+ "in workspace %s", roi.getId(), roi.getName(), 
						roi.getVersion(), 
						roi.getWorkspaceIdentifier().getID()), oid);
			}
			final Map<String, Object> v = vers.get(roi);
			final TypeDefId type = TypeDefId.fromTypeString(
					(String) v.get(Fields.VER_TYPE));
			final String md5 = (String) v.get(Fields.VER_CHKSUM);
			if (!toGet.containsKey(type)) {
				toGet.put(type, new HashMap<String, Set<ObjectIDResolvedWS>>());
			}
			if (!toGet.get(type).containsKey(md5)) {
				toGet.get(type).put(md5, new HashSet<ObjectIDResolvedWS>());
			}
			toGet.get(type).get(md5).add(oid);
		}
		final Map<ObjectIDResolvedWS, Map<String, Object>> ret =
				new HashMap<ObjectIDResolvedWS, Map<String,Object>>();
		for (final TypeDefId type: toGet.keySet()) {
			try {
				@SuppressWarnings("rawtypes")
				final Iterable<Map> subdata = wsjongo.getCollection(
						TypeData.getTypeCollection(type))
						.find(M_GETOBJSUB_QRY, toGet.get(type).keySet())
						.projection(M_GETOBJSUB_PROJ).as(Map.class);
				for (@SuppressWarnings("rawtypes") final Map m: subdata) {
					final String md5 = (String) m.get(Fields.TYPE_CHKSUM);
					@SuppressWarnings("unchecked")
					final Map<String, Object> sd =
							(Map<String, Object>) m.get(Fields.TYPE_SUBDATA);
					for (final ObjectIDResolvedWS o: toGet.get(type).get(md5)) {
						ret.put(o, sd);
					}
				}
			} catch (MongoException me) {
				throw new WorkspaceCommunicationException(
						"There was a problem communicating with the database",
						me);
			}
		}
		return ret;
	}
	
	private static final Set<String> FLDS_VER_GET_OBJECT = newHashSet(
			Fields.VER_VER, Fields.VER_META, Fields.VER_TYPE,
			Fields.VER_SAVEDATE, Fields.VER_SAVEDBY,
			Fields.VER_CHKSUM, Fields.VER_SIZE, Fields.VER_PROV,
			Fields.VER_PROVREF, Fields.VER_REF);
	
	@Override
	public Map<ObjectIDResolvedWS, WorkspaceObjectInformation>
			getObjectProvenance(final Set<ObjectIDResolvedWS> objectIDs)
			throws NoSuchObjectException, WorkspaceCommunicationException {
		//similar to getObjects but the code got too messy trying to combine
		//could try to factor out methods 
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> resobjs =
				resolveObjectIDs(objectIDs);
		final Map<ResolvedMongoObjectID, Map<String, Object>> vers = 
				query.queryVersions(
						new HashSet<ResolvedMongoObjectID>(resobjs.values()),
						FLDS_VER_GET_OBJECT);
		final Map<ObjectId, MongoProvenance> provs = getProvenance(vers);
		final Map<ObjectIDResolvedWS, WorkspaceObjectInformation> ret =
				new HashMap<ObjectIDResolvedWS, WorkspaceObjectInformation>();
		for (final ObjectIDResolvedWS o: objectIDs) {
			final ResolvedMongoObjectID roi = resobjs.get(o);
			if (!vers.containsKey(roi)) {
				throw new NoSuchObjectException(String.format(
						"No object with id %s (name %s) and version %s exists "
						+ "in workspace %s", roi.getId(), roi.getName(), 
						roi.getVersion(), 
						roi.getWorkspaceIdentifier().getID()), o);
			}
			final MongoProvenance prov = provs.get((ObjectId) vers.get(roi)
					.get(Fields.VER_PROV));
			@SuppressWarnings("unchecked")
			final List<String> refs =
					(List<String>) vers.get(roi).get(Fields.VER_REF);
			final MongoObjectInfo info = generateObjectInfo(
					roi, vers.get(roi));
			ret.put(o, new WorkspaceObjectInformation(info, prov, refs));
		}
		return ret;
	}
	
	@Override
	public Map<ObjectIDResolvedWS, Map<ObjectPaths, WorkspaceObjectData>>
			getObjects(final Set<ObjectIDResolvedWS> objectIDs)
			throws NoSuchObjectException, WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		final Map<ObjectIDResolvedWS, Set<ObjectPaths>> paths =
				new HashMap<ObjectIDResolvedWS, Set<ObjectPaths>>();
		for (final ObjectIDResolvedWS o: objectIDs) {
			paths.put(o, null);
		}
		try {
			return getObjects(paths);
		} catch (TypedObjectExtractionException toee) {
			throw new RuntimeException(
					"No extraction done, so something's very wrong here", toee);
		}
	}
	
	@Override
	public Map<ObjectIDResolvedWS, Map<ObjectPaths, WorkspaceObjectData>>
			getObjects(final Map<ObjectIDResolvedWS, Set<ObjectPaths>> objects)
			throws NoSuchObjectException, WorkspaceCommunicationException,
			CorruptWorkspaceDBException, TypedObjectExtractionException {
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> oids =
				resolveObjectIDs(objects.keySet());
		return getObjects(objects, oids);
	}

	private Map<ObjectIDResolvedWS, Map<ObjectPaths, WorkspaceObjectData>>
			getObjectsPreResolved(
					final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> oids)
			throws WorkspaceCommunicationException, NoSuchObjectException,
			TypedObjectExtractionException, CorruptWorkspaceDBException {
		final Map<ObjectIDResolvedWS, Set<ObjectPaths>> paths =
				new HashMap<ObjectIDResolvedWS, Set<ObjectPaths>>();
		for (final ObjectIDResolvedWS oi: oids.keySet()) {
			paths.put(oi, null);
		}
		return getObjects(paths, oids);
	}
	
	private Map<ObjectIDResolvedWS, Map<ObjectPaths, WorkspaceObjectData>>
			getObjects(final Map<ObjectIDResolvedWS, Set<ObjectPaths>> paths,
			final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> resobjs)
			throws WorkspaceCommunicationException, NoSuchObjectException,
			TypedObjectExtractionException, CorruptWorkspaceDBException {
		
		final Map<ResolvedMongoObjectID, Map<String, Object>> vers = 
				query.queryVersions(
						new HashSet<ResolvedMongoObjectID>(resobjs.values()),
						FLDS_VER_GET_OBJECT);
		checkTotalFileSize(paths, resobjs, vers);
		final Map<ObjectId, MongoProvenance> provs = getProvenance(vers);
		final Map<String, ByteArrayFileCache> chksumToData =
				new HashMap<String, ByteArrayFileCache>();
		final Map<ObjectIDResolvedWS, Map<ObjectPaths, WorkspaceObjectData>> ret =
				new HashMap<ObjectIDResolvedWS, Map<ObjectPaths, WorkspaceObjectData>>();
		final ByteArrayFileCacheManager bafcMan = new ByteArrayFileCacheManager(
				rescfg.getMaxReturnedDataMemoryUsage(),
				//maximum possible disk usage is when subsetting a objects
				//summing to 1G to 1G objects, since the 1G originals will be discarded
				rescfg.getMaxReturnedDataSize() * 2L,
				tfm);
		for (final ObjectIDResolvedWS o: paths.keySet()) {
			final ResolvedMongoObjectID roi = resobjs.get(o);
			if (!vers.containsKey(roi)) {
				cleanUpTempObjectFiles(chksumToData, ret);
				throw new NoSuchObjectException(String.format(
						"No object with id %s (name %s) and version %s exists "
						+ "in workspace %s", roi.getId(), roi.getName(), 
						roi.getVersion(), 
						roi.getWorkspaceIdentifier().getID()), o);
			}
			final MongoProvenance prov = provs.get((ObjectId) vers.get(roi)
					.get(Fields.VER_PROV));
			@SuppressWarnings("unchecked")
			final List<String> refs =
					(List<String>) vers.get(roi).get(Fields.VER_REF);
			final MongoObjectInfo meta = generateObjectInfo(
					roi, vers.get(roi));
			try {
				if (paths.get(o) == null || paths.get(o).isEmpty()) {
					buildReturnedObjectData(chksumToData, ret, o, prov, refs,
							meta, null, bafcMan);
				} else {
					for (final ObjectPaths op: paths.get(o)) {
						buildReturnedObjectData(chksumToData, ret, o, prov,
								refs, meta, op, bafcMan);
					}
				}
			} catch (TypedObjectExtractionException e) {
				cleanUpTempObjectFiles(chksumToData, ret);
				throw e;
			} catch (WorkspaceCommunicationException e) {
				cleanUpTempObjectFiles(chksumToData, ret);
				throw e;
			} catch (CorruptWorkspaceDBException e) {
				cleanUpTempObjectFiles(chksumToData, ret);
				throw e;
			} catch (IllegalStateException e) {
				cleanUpTempObjectFiles(chksumToData, ret);
				throw e;
			}
		}
		return ret;
	}

	private void checkTotalFileSize(
			final Map<ObjectIDResolvedWS, Set<ObjectPaths>> paths,
			final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> resobjs,
			final Map<ResolvedMongoObjectID, Map<String, Object>> vers) {
		//could take into account that identical md5s won't incur a real
		//size penalty, but meh
		long size = 0;
		for (final ObjectIDResolvedWS o: paths.keySet()) {
			final Set<ObjectPaths> ops = paths.get(o);
			final long mult = ops == null || ops.size() < 1 ? 1 : ops.size();
			size += mult * (Long) vers.get(resobjs.get(o))
					.get(Fields.VER_SIZE);
		}
		if (size > rescfg.getMaxReturnedDataSize()) {
			throw new IllegalArgumentException(String.format(
					"Too much data requested from the workspace at once; " +
					"data requested including potential subsets is %sB " + 
					"which  exceeds maximum of %s.", size,
					rescfg.getMaxReturnedDataSize()));
		}
	}

	private void cleanUpTempObjectFiles(
			final Map<String, ByteArrayFileCache> chksumToData,
			final Map<ObjectIDResolvedWS, Map<ObjectPaths, WorkspaceObjectData>> ret) {
		for (final ByteArrayFileCache f: chksumToData.values()) {
			f.destroy();
		}
		for (final Map<ObjectPaths, WorkspaceObjectData> m:
			ret.values()) {
			for (final WorkspaceObjectData wod: m.values()) {
				wod.getDataAsTokens().destroy();
			}
		}
	}
	

	//yuck. Think more about the interface here
	private void buildReturnedObjectData(
			final Map<String, ByteArrayFileCache> chksumToData,
			final Map<ObjectIDResolvedWS, Map<ObjectPaths, WorkspaceObjectData>> ret,
			final ObjectIDResolvedWS o, final MongoProvenance prov,
			final List<String> refs, final MongoObjectInfo meta,
			final ObjectPaths op, final ByteArrayFileCacheManager bafcMan)
			throws TypedObjectExtractionException,
			WorkspaceCommunicationException, CorruptWorkspaceDBException {
		if (!ret.containsKey(o)) {
			ret.put(o, new HashMap<ObjectPaths, WorkspaceObjectData>());
		}
		if (chksumToData.containsKey(meta.getCheckSum())) {
			/* might be subsetting the same object the same way multiple
			 * times, but probably unlikely. If it becomes a problem
			 * memoize the subset
			 */
			ret.get(o).put(op, new WorkspaceObjectData(getDataSubSet(
					chksumToData.get(meta.getCheckSum()), op, bafcMan),
					meta, prov, refs));
		} else {
			final ByteArrayFileCache data;
			try {
				data = blob.getBlob(new MD5(meta.getCheckSum()), bafcMan);
			} catch (FileCacheIOException e) {
				throw new WorkspaceCommunicationException(
						e.getLocalizedMessage(), e);
			} catch (FileCacheLimitExceededException e) {
				throw new IllegalArgumentException( //shouldn't happen if size was checked correctly beforehand
						"Too much data requested from the workspace at once; " +
						"data requested including subsets exceeds maximum of "
						+ bafcMan.getMaxSizeOnDisk());
			} catch (BlobStoreCommunicationException e) {
				throw new WorkspaceCommunicationException(
						e.getLocalizedMessage(), e);
			} catch (BlobStoreAuthorizationException e) {
				throw new WorkspaceCommunicationException(
						"Authorization error communicating with the backend storage system",
						e);
			} catch (NoSuchBlobException e) {
				throw new CorruptWorkspaceDBException(String.format(
						"No data present for valid object %s.%s.%s",
						meta.getWorkspaceId(), meta.getObjectId(),
						meta.getVersion()), e);
			}
			chksumToData.put(meta.getCheckSum(), data);
			ret.get(o).put(op, new WorkspaceObjectData(getDataSubSet(
					data, op, bafcMan), meta, prov, refs));
		}
	}
	
	private ByteArrayFileCache getDataSubSet(final ByteArrayFileCache data,
			final ObjectPaths paths, final ByteArrayFileCacheManager bafcMan)
			throws TypedObjectExtractionException,
			WorkspaceCommunicationException {
		if (paths == null || paths.isEmpty()) {
			return data;
		}
		try {
			return bafcMan.getSubdataExtraction(data, paths);
		} catch (FileCacheIOException e) {
			throw new WorkspaceCommunicationException(
					e.getLocalizedMessage(), e);
		} catch (FileCacheLimitExceededException e) {
			throw new IllegalArgumentException( //shouldn't happen if size was checked correctly beforehand
					"Too much data requested from the workspace at once; " +
					"data requested including subsets exceeds maximum of "
					+ bafcMan.getMaxSizeOnDisk());
		}
	}

	private static final Set<String> FLDS_GETOBJREF = newHashSet(
			Fields.VER_WS_ID, Fields.VER_PROVREF, Fields.VER_REF);

	@Override
	public Map<ObjectChainResolvedWS, WorkspaceObjectData> getReferencedObjects(
			final Set<ObjectChainResolvedWS> chains)
			throws NoSuchObjectException, WorkspaceCommunicationException,
			NoSuchReferenceException, CorruptWorkspaceDBException {
		final Set<ObjectIDResolvedWS> heads = new HashSet<ObjectIDResolvedWS>();
		final Set<ObjectIDResolvedWS> ch = new HashSet<ObjectIDResolvedWS>();
		for (final ObjectChainResolvedWS chain: chains) {
			heads.add(chain.getHead());
			ch.addAll(chain.getChain());
		}
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> resheads = 
				resolveObjectIDs(heads);
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> reschains = 
				resolveObjectIDs(ch, false, true);
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> resall =
				new HashMap<ObjectIDResolvedWS, ResolvedMongoObjectID>(resheads);
		resall.putAll(reschains);
		final Map<ResolvedMongoObjectID, Map<String, Object>> foo =
				query.queryVersions(
						new HashSet<ResolvedMongoObjectID>(resall.values()),
						FLDS_GETOBJREF);
		final Map<ObjectIDResolvedWS, Set<String>> refs =
				setUpRefs(resall, foo);
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> toGet =
				new HashMap<ObjectIDResolvedWS, ResolvedMongoObjectID>();
		for (final ObjectChainResolvedWS chain: chains) {
			ObjectIDResolvedWS pos = chain.getHead();
			final List<ObjectIDResolvedWS> lch = chain.getChain();
			for (final ObjectIDResolvedWS oi: lch) {
				final String ref = resall.get(oi).getReference().toString();
				if (!refs.get(pos).contains(ref)) {
					throw new NoSuchReferenceException(String.format(
							"The object %s in workspace %s does not contain the reference %s",
							pos.getIdentifierString(),
							pos.getWorkspaceIdentifier().getName(), ref),
							pos, oi);
				}
				pos = oi;
			}
			toGet.put(chain.getLast(), resall.get(chain.getLast()));
		}
		final Map<ObjectIDResolvedWS, Map<ObjectPaths, WorkspaceObjectData>> res;
		try {
			res = getObjectsPreResolved(toGet);
		} catch (TypedObjectExtractionException toee) {
			throw new RuntimeException(
					"No extraction done, so something's very wrong here", toee);
		}
		final Map<ObjectChainResolvedWS, WorkspaceObjectData> ret =
				new HashMap<ObjectChainResolvedWS, WorkspaceObjectData>();
		for (final ObjectChainResolvedWS chain: chains) {
			ret.put(chain, res.get(chain.getLast()).get(null));
		}
		return ret;
	}

	private Map<ObjectIDResolvedWS, Set<String>> setUpRefs(
			final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> objs,
			final Map<ResolvedMongoObjectID, Map<String, Object>> refs)
			throws NoSuchObjectException {
		
		final Map<ObjectIDResolvedWS, Set<String>> ret =
				new HashMap<ObjectIDResolvedWS, Set<String>>();
		for (final ObjectIDResolvedWS oi: objs.keySet()) {
			final ResolvedMongoObjectID roi = objs.get(oi);
			if (!refs.containsKey(roi)) {
				throw new NoSuchObjectException(String.format(
						"No object with id %s (name %s) and version %s exists "
						+ "in workspace %s", roi.getId(), roi.getName(), 
						roi.getVersion(), 
						roi.getWorkspaceIdentifier().getID()), oi);
			}
			final Map<String, Object> m = refs.get(objs.get(oi));
			@SuppressWarnings("unchecked")
			final List<String> r = (List<String>) m.get(Fields.VER_REF);
			@SuppressWarnings("unchecked")
			final List<String> pr = (List<String>) m.get(Fields.VER_PROVREF);
			final Set<String> s = new HashSet<String>(r);
			s.addAll(pr);
			ret.put(oi, s);
		}
		return ret;
	}

	private static final Set<String> FLDS_GETREFOBJ = newHashSet(
			Fields.VER_WS_ID, Fields.VER_ID, Fields.VER_VER,
			Fields.VER_VER, Fields.VER_TYPE, Fields.VER_META,
			Fields.VER_SAVEDATE, Fields.VER_SAVEDBY,
			Fields.VER_CHKSUM, Fields.VER_SIZE,
			Fields.VER_PROVREF, Fields.VER_REF);
	
	@Override
	public Map<ObjectIDResolvedWS, Set<ObjectInformation>>
			getReferencingObjects(final PermissionSet perms,
					final Set<ObjectIDResolvedWS> objs)
		throws NoSuchObjectException, WorkspaceCommunicationException {
		final List<Long> wsids = new LinkedList<Long>();
		for (final ResolvedWorkspaceID ws: perms.getWorkspaces()) {
			wsids.add(ws.getID());
		}
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> resobjs =
				resolveObjectIDs(objs);
		verifyVersions(new HashSet<ResolvedMongoObjectID>(resobjs.values()));
		final Map<String, Set<ObjectIDResolvedWS>> ref2id =
				new HashMap<String, Set<ObjectIDResolvedWS>>();
		for (final ObjectIDResolvedWS oi: objs) {
			final ResolvedMongoObjectID r = resobjs.get(oi);
			final String ref = r.getReference().toString();
			if (!ref2id.containsKey(ref)) {
				ref2id.put(ref, new HashSet<ObjectIDResolvedWS>());
			}
			ref2id.get(ref).add(oi);
		}
		final DBObject q = new BasicDBObject(Fields.VER_WS_ID,
				new BasicDBObject("$in", wsids));
		q.put("$or", Arrays.asList(
				new BasicDBObject(Fields.VER_REF,
						new BasicDBObject("$in", ref2id.keySet())),
				new BasicDBObject(Fields.VER_PROVREF,
						new BasicDBObject("$in", ref2id.keySet()))));
		final List<Map<String, Object>> vers = query.queryCollection(
				COL_WORKSPACE_VERS, q, FLDS_GETREFOBJ);
		final Map<Map<String, Object>, ObjectInformation> voi =
				generateObjectInfo(perms, vers, true, false, false, true);
		final Map<ObjectIDResolvedWS, Set<ObjectInformation>> ret = 
				new HashMap<ObjectIDResolvedWS, Set<ObjectInformation>>();
		for (final ObjectIDResolvedWS o: objs) {
			ret.put(o, new HashSet<ObjectInformation>());
		}
		for (final Map<String, Object> ver: voi.keySet()) {
			@SuppressWarnings("unchecked")
			final List<String> refs = (List<String>) ver.get(Fields.VER_REF);
			@SuppressWarnings("unchecked")
			final List<String> provrefs = (List<String>) ver.get(
					Fields.VER_PROVREF);
			final Set<String> allrefs = new HashSet<String>();
			allrefs.addAll(refs);
			allrefs.addAll(provrefs);
			for (final String ref: allrefs) {
				if (ref2id.containsKey(ref)) {
					for (final ObjectIDResolvedWS oi: ref2id.get(ref)) {
						ret.get(oi).add(voi.get(ver));
					}
				}
			}
		}
		return ret;
	}
	
	private static final Set<String> FLDS_REF_CNT = newHashSet(
			Fields.OBJ_ID, Fields.OBJ_NAME, Fields.OBJ_DEL,
			Fields.OBJ_LATEST, Fields.OBJ_VCNT, Fields.OBJ_REFCOUNTS);
	
	@Override
	public Map<ObjectIDResolvedWS, Integer> getReferencingObjectCounts(
			final Set<ObjectIDResolvedWS> objects)
			throws WorkspaceCommunicationException, NoSuchObjectException {
		//TODO test w/ garbage collection
		final Map<ObjectIDResolvedWS, Map<String, Object>> objdata =
				queryObjects(objects, FLDS_REF_CNT, true, true);
		final Map<ObjectIDResolvedWS, Integer> ret =
				new HashMap<ObjectIDResolvedWS, Integer>();
		for (final ObjectIDResolvedWS o: objects) {
			//this is another place where extremely rare failures could cause
			//problems
			final int ver;
			if (o.getVersion() == null) {
				ver = (Integer) objdata.get(o).get(LATEST_VERSION);
			} else {
				ver = o.getVersion();
			}
			@SuppressWarnings("unchecked")
			final List<Integer> refs = (List<Integer>) objdata.get(o).get(
					Fields.OBJ_REFCOUNTS);
			//TODO when GC enabled handle the case where the version is deleted
			ret.put(o, refs.get(ver - 1));
		}
		return ret;
	}
	
	private Map<ObjectId, MongoProvenance> getProvenance(
			final Map<ResolvedMongoObjectID, Map<String, Object>> vers)
			throws WorkspaceCommunicationException {
		final Map<ObjectId, Map<String, Object>> provIDs =
				new HashMap<ObjectId, Map<String,Object>>();
		for (final ResolvedMongoObjectID id: vers.keySet()) {
			provIDs.put((ObjectId) vers.get(id).get(Fields.VER_PROV),
					vers.get(id));
		}
		final Map<ObjectId, MongoProvenance> ret =
				new HashMap<ObjectId, MongoProvenance>();
		try {
			final Iterable<MongoProvenance> provs =
					wsjongo.getCollection(COL_PROVENANCE)
					.find("{_id: {$in: #}}", provIDs.keySet())
					.as(MongoProvenance.class);
			for (MongoProvenance p: provs) {
				@SuppressWarnings("unchecked")
				final List<String> resolvedRefs = (List<String>) provIDs
				.get(p.getMongoId()).get(Fields.VER_PROVREF);
				ret.put(p.getMongoId(), p);
				p.resolveReferences(resolvedRefs); //this is a gross hack. I'm rather proud of it actually
			}
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		return ret;
	}
	
	private MongoObjectInfo generateObjectInfo(
			final ResolvedMongoObjectID roi, final Map<String, Object> ver) {
		return generateObjectInfo(roi.getWorkspaceIdentifier(), roi.getId(),
				roi.getName(), ver);
	}

	private MongoObjectInfo generateObjectInfo(
			final ResolvedMongoWSID rwsi, final long objid, final String name,
			final Map<String, Object> ver) {
		@SuppressWarnings("unchecked")
		final List<Map<String, String>> meta =
				(List<Map<String, String>>) ver.get(Fields.VER_META);
		return new MongoObjectInfo(
				objid,
				name,
				(String) ver.get(Fields.VER_TYPE),
				(Date) ver.get(Fields.VER_SAVEDATE),
				(Integer) ver.get(Fields.VER_VER),
				new WorkspaceUser((String) ver.get(Fields.VER_SAVEDBY)),
				rwsi,
				(String) ver.get(Fields.VER_CHKSUM),
				(Long) ver.get(Fields.VER_SIZE),
				meta == null ? null : metaMongoArrayToHash(meta));
	}
	
	private static final Set<String> FLDS_VER_TYPE = newHashSet(
			Fields.VER_TYPE, Fields.VER_VER);
	
	public Map<ObjectIDResolvedWS, TypeAndReference> getObjectType(
			final Set<ObjectIDResolvedWS> objectIDs) throws
			NoSuchObjectException, WorkspaceCommunicationException {
		//this method is a pattern - generalize somehow?
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> oids =
				resolveObjectIDs(objectIDs);
		final Map<ResolvedMongoObjectID, Map<String, Object>> vers = 
				query.queryVersions(
						new HashSet<ResolvedMongoObjectID>(oids.values()),
						FLDS_VER_TYPE);
		final Map<ObjectIDResolvedWS, TypeAndReference> ret =
				new HashMap<ObjectIDResolvedWS, TypeAndReference>();
		for (ObjectIDResolvedWS o: objectIDs) {
			final ResolvedMongoObjectID roi = oids.get(o);
			if (!vers.containsKey(roi)) {
				throw new NoSuchObjectException(String.format(
						"No object with id %s (name %s) and version %s exists "
						+ "in workspace %s", roi.getId(), roi.getName(), 
						roi.getVersion(), 
						roi.getWorkspaceIdentifier().getID()), o);
			}
			ret.put(o, new TypeAndReference(
					AbsoluteTypeDefId.fromAbsoluteTypeString(
							(String) vers.get(roi).get(Fields.VER_TYPE)),
					new MongoReference(roi.getWorkspaceIdentifier().getID(),
							roi.getId(),
							(Integer) vers.get(roi).get(Fields.VER_VER))));
		}
		return ret;
	}
	
	private static final Set<String> FLDS_LIST_OBJ_VER = newHashSet(
			Fields.VER_VER, Fields.VER_TYPE, Fields.VER_SAVEDATE,
			Fields.VER_SAVEDBY, Fields.VER_VER, Fields.VER_CHKSUM,
			Fields.VER_SIZE, Fields.VER_ID, Fields.VER_WS_ID);
	
	private static final Set<String> FLDS_LIST_OBJ = newHashSet(
			Fields.OBJ_ID, Fields.OBJ_NAME, Fields.OBJ_DEL, Fields.OBJ_HIDE,
			Fields.OBJ_LATEST, Fields.OBJ_VCNT, Fields.OBJ_WS_ID);
	
	private static final String LATEST_VERSION = "_latestVersion";

	@Override
	public List<ObjectInformation> getObjectInformation(
			final PermissionSet pset, final TypeDefId type,
			final List<WorkspaceUser> savedby, final Map<String, String> meta,
			final Date after, final Date before,
			final boolean showHidden, final boolean showDeleted,
			final boolean showOnlyDeleted, final boolean showAllVers,
			final boolean includeMetadata, final int skip, final int limit)
			throws WorkspaceCommunicationException {
		/* Could make this method more efficient by doing different queries
		 * based on the filters. If there's no filters except the workspace,
		 * for example, just grab all the objects for the workspaces,
		 * filtering out hidden and deleted in the query and pull the most
		 * recent versions for the remaining objects. For now, just go
		 * with a dumb general method and add smarter heuristics as needed.
		 */
		if (!(pset instanceof MongoPermissionSet)) {
			throw new IllegalArgumentException(
					"Illegal implementation of PermissionSet: " +
					pset.getClass().getName());
		}
		if (pset.isEmpty()) {
			return new LinkedList<ObjectInformation>();
		}
		final Set<Long> ids = new HashSet<Long>();
		for (final ResolvedWorkspaceID rwsi: pset.getWorkspaces()) {
			ids.add(rwsi.getID());
		}
		final DBObject verq = new BasicDBObject();
		verq.put(Fields.VER_WS_ID, new BasicDBObject("$in", ids));
		if (type != null) {
			verq.put(Fields.VER_TYPE,
					new BasicDBObject("$regex", "^" + type.getTypePrefix()));
		}
		if (savedby != null && !savedby.isEmpty()) {
			verq.put(Fields.VER_SAVEDBY,
					new BasicDBObject("$in", convertWorkspaceUsers(savedby)));
		}
		if (meta != null && !meta.isEmpty()) {
			final List<DBObject> andmetaq = new LinkedList<DBObject>();
			for (final Entry<String, String> e: meta.entrySet()) {
				final DBObject mentry = new BasicDBObject();
				mentry.put(Fields.META_KEY, e.getKey());
				mentry.put(Fields.META_VALUE, e.getValue());
				andmetaq.add(new BasicDBObject(Fields.VER_META, mentry));
			}
			verq.put("$and", andmetaq); //note more than one entry is untested
		}
		if (before != null || after != null) {
			final DBObject d = new BasicDBObject();
			if (before != null) {
				d.put("$lt", before);
			}
			if (after != null) {
				d.put("$gt", after);
			}
			verq.put(Fields.VER_SAVEDATE, d);
		}
		final Set<String> fields;
		if (includeMetadata) {
			fields = new HashSet<String>(FLDS_LIST_OBJ_VER);
			fields.add(Fields.VER_META);
		} else {
			fields = FLDS_LIST_OBJ_VER;
		}
		final List<Map<String, Object>> verobjs = query.queryCollection(
				COL_WORKSPACE_VERS, verq, fields, skip, limit);
		if (verobjs.isEmpty()) {
			return new LinkedList<ObjectInformation>();
		}
		return new LinkedList<ObjectInformation>(
				generateObjectInfo(pset, verobjs, showHidden, showDeleted,
				showOnlyDeleted, showAllVers).values());
	}

	private Map<Map<String, Object>, ObjectInformation> generateObjectInfo(
			final PermissionSet pset, final List<Map<String, Object>> verobjs,
			final boolean includeHidden, final boolean includeDeleted,
			final boolean onlyIncludeDeleted, final boolean includeAllVers)
			throws WorkspaceCommunicationException {
		final Map<Map<String, Object>, ObjectInformation> ret =
				new HashMap<Map<String, Object>, ObjectInformation>();
		if (verobjs.isEmpty()) {
			return ret;
		}
		final Map<Long, ResolvedWorkspaceID> ids =
				new HashMap<Long, ResolvedWorkspaceID>();
		for (final ResolvedWorkspaceID rwsi: pset.getWorkspaces()) {
			final ResolvedMongoWSID rm = query.convertResolvedWSID(rwsi);
			ids.put(rm.getID(), rm);
		}
		final Map<Long, Set<Long>> verdata = getObjectIDsFromVersions(verobjs);
		//TODO This $or query might be better as multiple individual queries, test
		final List<DBObject> orquery = new LinkedList<DBObject>();
		for (final Long wsid: verdata.keySet()) {
			final DBObject query = new BasicDBObject(Fields.VER_WS_ID, wsid);
			query.put(Fields.VER_ID, new BasicDBObject(
					"$in", verdata.get(wsid)));
			orquery.add(query);
		}
		final DBObject objq = new BasicDBObject("$or", orquery);
		//could include / exclude hidden and deleted objects here? Prob
		// not worth the effort
		final Map<Long, Map<Long, Map<String, Object>>> objdata =
				organizeObjData(query.queryCollection(
						COL_WORKSPACE_OBJS, objq, FLDS_LIST_OBJ));
		for (final Map<String, Object> vo: verobjs) {
			final long wsid = (Long) vo.get(Fields.VER_WS_ID);
			final long id = (Long) vo.get(Fields.VER_ID);
			final int ver = (Integer) vo.get(Fields.VER_VER);
			final Map<String, Object> obj = objdata.get(wsid).get(id);
			final int lastver = (Integer) obj.get(LATEST_VERSION);
			final ResolvedMongoWSID rwsi = (ResolvedMongoWSID) ids.get(wsid);
			boolean isDeleted = (Boolean) obj.get(Fields.OBJ_DEL);
			if (!includeAllVers && lastver != ver) {
				/* this is tricky. As is, if there's a failure between incrementing
				 * an object ver count and saving the object version no latest
				 * ver will be listed. On the other hand, if we just take
				 * the max ver we'd be adding incorrect latest vers when filters
				 * exclude the real max ver. To do this correctly we've have to
				 * get the max ver for all objects which is really expensive.
				 * Since the failure mode should be very rare and it fixable
				 * by simply reverting the object do nothing for now.
				 */
				continue;
			}
			if ((Boolean) obj.get(Fields.OBJ_HIDE) && !includeHidden) {
				continue;
			}
			if (onlyIncludeDeleted) {
				if (isDeleted && pset.hasPermission(rwsi, Permission.WRITE)) {
					ret.put(vo, generateObjectInfo(rwsi, id,
							(String) obj.get(Fields.OBJ_NAME), vo));
				}
				continue;
			}
			if (isDeleted && (!includeDeleted ||
					!pset.hasPermission(rwsi, Permission.WRITE))) {
				continue;
			}
			ret.put(vo, generateObjectInfo(rwsi, id,
					(String) obj.get(Fields.OBJ_NAME), vo));
		}
		return ret;
	}
	
	private static final Set<String> FLDS_VER_OBJ_HIST = newHashSet(
			Fields.VER_WS_ID, Fields.VER_ID, Fields.VER_VER,
			Fields.VER_TYPE, Fields.VER_CHKSUM, Fields.VER_SIZE,
			Fields.VER_META, Fields.VER_SAVEDATE, Fields.VER_SAVEDBY);
	
	@Override
	public List<ObjectInformation> getObjectHistory(
			final ObjectIDResolvedWS oi)
		throws NoSuchObjectException, WorkspaceCommunicationException {
		final ResolvedMongoObjectID roi = resolveObjectIDs(
				new HashSet<ObjectIDResolvedWS>(Arrays.asList(oi))).get(oi);
		final ResolvedMongoObjectIDNoVer o =
				new ResolvedMongoObjectIDNoVer(roi);
		final List<Map<String, Object>> versions = query.queryAllVersions(
				new HashSet<ResolvedMongoObjectIDNoVer>(Arrays.asList(o)),
				FLDS_VER_OBJ_HIST).get(o);
		final LinkedList<ObjectInformation> ret =
				new LinkedList<ObjectInformation>();
		for (final Map<String, Object> v: versions) {
			ret.add(generateObjectInfo(roi, v));
		}
		return ret;
	}
	
	private Map<Long, Map<Long, Map<String, Object>>> organizeObjData(
			final List<Map<String, Object>> objs) {
		final Map<Long, Map<Long, Map<String, Object>>> ret =
				new HashMap<Long, Map<Long,Map<String,Object>>>();
		for (final Map<String, Object> o: objs) {
			final long wsid = (Long) o.get(Fields.OBJ_WS_ID);
			final long objid = (Long) o.get(Fields.OBJ_ID);
			calcLatestObjVersion(o);
			if (!ret.containsKey(wsid)) {
				ret.put(wsid, new HashMap<Long, Map<String, Object>>());
			}
			ret.get(wsid).put(objid, o);
		}
		return ret;
	}

	private Map<Long, Set<Long>> getObjectIDsFromVersions(
			final List<Map<String, Object>> objs) {
		final Map<Long, Set<Long>> ret = new HashMap<Long, Set<Long>>();
		for (final Map<String, Object> o: objs) {
			final long wsid = (Long) o.get(Fields.VER_WS_ID);
			final long objid = (Long) o.get(Fields.VER_ID);
			if (!ret.containsKey(wsid)) {
				ret.put(wsid, new HashSet<Long>());
			}
			ret.get(wsid).add(objid);
		}
		return ret;
	}

	private static final Set<String> FLDS_VER_META = newHashSet(
			Fields.VER_VER, Fields.VER_TYPE,
			Fields.VER_SAVEDATE, Fields.VER_SAVEDBY,
			Fields.VER_CHKSUM, Fields.VER_SIZE);
	
	@Override
	public Map<ObjectIDResolvedWS, ObjectInformation> getObjectInformation(
			final Set<ObjectIDResolvedWS> objectIDs,
			final boolean includeMetadata,
			final boolean ignoreMissingAndDeleted)
			throws NoSuchObjectException, WorkspaceCommunicationException {
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> oids =
				resolveObjectIDs(objectIDs, !ignoreMissingAndDeleted,
						!ignoreMissingAndDeleted);
		final Iterator<Entry<ObjectIDResolvedWS, ResolvedMongoObjectID>> iter =
				oids.entrySet().iterator();
		while (iter.hasNext()) {
			Entry<ObjectIDResolvedWS, ResolvedMongoObjectID> e = iter.next();
			if (e.getValue().isDeleted()) {
				iter.remove();
			}
		}
		final Set<String> fields;
		if (includeMetadata) {
			fields = new HashSet<String>(FLDS_VER_META);
			fields.add(Fields.VER_META);
		} else {
			fields = FLDS_VER_META;
		}
		final Map<ResolvedMongoObjectID, Map<String, Object>> vers = 
				query.queryVersions(
						new HashSet<ResolvedMongoObjectID>(oids.values()),
						fields);
		final Map<ObjectIDResolvedWS, ObjectInformation> ret =
				new HashMap<ObjectIDResolvedWS, ObjectInformation>();
		for (ObjectIDResolvedWS o: objectIDs) {
			final ResolvedMongoObjectID roi = oids.get(o);
			if (!vers.containsKey(roi)) {
				if (!ignoreMissingAndDeleted) {
					throw new NoSuchObjectException(String.format(
							"No object with id %s (name %s) and version %s " +
							" exists in workspace %s", roi.getId(),
							roi.getName(), roi.getVersion(), 
							roi.getWorkspaceIdentifier().getID()), o);
				}
			} else {
				ret.put(o, generateObjectInfo(roi, vers.get(roi)));
			}
		}
		return ret;
	}
	
	private Map<String, String> metaMongoArrayToHash(
			final List<? extends Object> meta) {
		final Map<String, String> ret = new HashMap<String, String>();
		if (meta != null) {
			for (final Object o: meta) {
				//frigging mongo
				if (o instanceof DBObject) {
					final DBObject dbo = (DBObject) o;
					ret.put((String) dbo.get(Fields.META_KEY),
							(String) dbo.get(Fields.META_VALUE));
				} else {
					@SuppressWarnings("unchecked")
					final Map<String, String> m = (Map<String, String>) o;
					ret.put(m.get(Fields.META_KEY),
							m.get(Fields.META_VALUE));
				}
			}
		}
		return ret;
	}
	
	private static final Set<String> FLDS_RESOLVE_OBJS =
			newHashSet(Fields.OBJ_ID, Fields.OBJ_NAME, Fields.OBJ_DEL,
					Fields.OBJ_LATEST, Fields.OBJ_VCNT);
	
	private Map<ObjectIDResolvedWS, ResolvedMongoObjectID> resolveObjectIDs(
			final Set<ObjectIDResolvedWS> objectIDs)
			throws NoSuchObjectException, WorkspaceCommunicationException {
		return resolveObjectIDs(objectIDs, true, true);
	}
	
	private Map<ObjectIDResolvedWS, ResolvedMongoObjectID> resolveObjectIDs(
			final Set<ObjectIDResolvedWS> objectIDs,
			final boolean exceptIfDeleted, final boolean exceptIfMissing)
			throws NoSuchObjectException, WorkspaceCommunicationException {
		return resolveObjectIDs(objectIDs, exceptIfDeleted, exceptIfMissing,
				false);
	}
	
	private Map<ObjectIDResolvedWS, ResolvedMongoObjectID> resolveObjectIDs(
			final Set<ObjectIDResolvedWS> objectIDs,
			final boolean exceptIfDeleted, final boolean exceptIfMissing,
			final boolean ignoreVersion)
			throws NoSuchObjectException, WorkspaceCommunicationException {
		final Map<ObjectIDResolvedWS, Map<String, Object>> ids = 
				queryObjects(objectIDs, FLDS_RESOLVE_OBJS, exceptIfDeleted,
						exceptIfMissing);
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> ret =
				new HashMap<ObjectIDResolvedWS, ResolvedMongoObjectID>();
		for (final ObjectIDResolvedWS o: ids.keySet()) {
			final String name = (String) ids.get(o).get(Fields.OBJ_NAME);
			final long id = (Long) ids.get(o).get(Fields.OBJ_ID);
			final boolean deleted = (Boolean) ids.get(o).get(Fields.OBJ_DEL);
			final int latestVersion = (Integer) ids.get(o).get(LATEST_VERSION);
			if (o.getVersion() == null || ignoreVersion ||
					o.getVersion().equals(latestVersion)) {
				//TODO this could be wrong if the vercount was incremented without a ver save, should verify and then sort if needed
				ret.put(o, new ResolvedMongoOIDWithObjLastVer(
						query.convertResolvedWSID(o.getWorkspaceIdentifier()),
						name, id, latestVersion, deleted));
			} else {
				if (o.getVersion().compareTo(latestVersion) > 0) {
					if (exceptIfMissing) {
						throw new NoSuchObjectException(String.format(
								"No object with id %s (name %s) and version %s"
								+ " exists in workspace %s", id, name,
								o.getVersion(), 
								o.getWorkspaceIdentifier().getID()), o);
					}
				} else {
					ret.put(o, new ResolvedMongoObjectID(
							query.convertResolvedWSID(
									o.getWorkspaceIdentifier()),
									name, id, o.getVersion().intValue(),
									deleted));
				}
			}
		}
		return ret;
	}
	
	
	private Map<ObjectIDResolvedWS, Map<String, Object>> queryObjects(
			final Set<ObjectIDResolvedWS> objectIDs, Set<String> fields,
			final boolean exceptIfDeleted, final boolean exceptIfMissing)
			throws WorkspaceCommunicationException, NoSuchObjectException {
		final Map<ObjectIDResolvedWS, ObjectIDResolvedWSNoVer> nover =
				new HashMap<ObjectIDResolvedWS, ObjectIDResolvedWSNoVer>();
		for (final ObjectIDResolvedWS o: objectIDs) {
			nover.put(o, new ObjectIDResolvedWSNoVer(o));
		}
		final Map<ObjectIDResolvedWSNoVer, Map<String, Object>> ids = 
				query.queryObjects(
						new HashSet<ObjectIDResolvedWSNoVer>(nover.values()),
						fields);
		final Map<ObjectIDResolvedWS, Map<String, Object>> ret =
				new HashMap<ObjectIDResolvedWS, Map<String,Object>>();
		for (final ObjectIDResolvedWS oid: nover.keySet()) {
			//this could happen multiple times per object *shrug*
			//if becomes a problem, hash nover -> ver and just loop through
			//the novers
			final ObjectIDResolvedWSNoVer o = nover.get(oid);
			if (!ids.containsKey(o)) {
				if (exceptIfMissing) {
					final String err = oid.getId() == null ? "name" : "id";
					throw new NoSuchObjectException(String.format(
							"No object with %s %s exists in workspace %s",
							err, oid.getIdentifierString(),
							oid.getWorkspaceIdentifier().getID()), oid);
				} else {
					continue;
				}
			}
			final String name = (String) ids.get(o).get(Fields.OBJ_NAME);
			final long id = (Long) ids.get(o).get(Fields.OBJ_ID);
			final boolean deleted = (Boolean) ids.get(o).get(Fields.OBJ_DEL);
			if (exceptIfDeleted && deleted) {
				throw new NoSuchObjectException(String.format(
						"Object %s (name %s) in workspace %s has been deleted",
						id, name, oid.getWorkspaceIdentifier().getID()), oid);
			}
			calcLatestObjVersion(ids.get(o));
			ret.put(oid, ids.get(o));
		}
		return ret;
	}

	private void calcLatestObjVersion(Map<String, Object> m) {
		final Integer latestVersion;
		if ((Integer) m.get(Fields.OBJ_LATEST) == null) {
			latestVersion = (Integer) m.get(Fields.OBJ_VCNT);
		} else {
			//TODO check this works with GC
			latestVersion = (Integer) m.get(Fields.OBJ_LATEST);
		}
		m.put(LATEST_VERSION, latestVersion);
	}
	
	private void verifyVersions(final Set<ResolvedMongoObjectID> objs)
			throws WorkspaceCommunicationException, NoSuchObjectException {
		final Map<ResolvedMongoObjectID, Map<String, Object>> vers =
				query.queryVersions(objs, new HashSet<String>()); //don't actually need the data
		for (final ResolvedMongoObjectID o: objs) {
			if (!vers.containsKey(o)) {
				throw new NoSuchObjectException(String.format(
						"No object with id %s (name %s) and version %s exists "
						+ "in workspace %s", o.getId(), o.getName(), 
						o.getVersion(), 
						o.getWorkspaceIdentifier().getID()));
			}
		}
	}

	@Override
	public void setObjectsHidden(final Set<ObjectIDResolvedWS> objectIDs,
			final boolean hide)
			throws NoSuchObjectException, WorkspaceCommunicationException {
		//TODO nearly identical to delete objects, generalize
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> ids =
				resolveObjectIDs(objectIDs);
		final Map<ResolvedMongoWSID, List<Long>> toModify =
				new HashMap<ResolvedMongoWSID, List<Long>>();
		for (final ObjectIDResolvedWS o: objectIDs) {
			final ResolvedMongoWSID ws = query.convertResolvedWSID(
					o.getWorkspaceIdentifier());
			if (!toModify.containsKey(ws)) {
				toModify.put(ws, new ArrayList<Long>());
			}
			toModify.get(ws).add(ids.get(o).getId());
		}
		//Do this by workspace since per mongo docs nested $ors are crappy
		for (final ResolvedMongoWSID ws: toModify.keySet()) {
			setObjectsHidden(ws, toModify.get(ws), hide);
		}
	}
	
	private static final String M_HIDOBJ_WTH = String.format(
			"{$set: {%s: #}}", Fields.OBJ_HIDE);
	private static final String M_HIDOBJ_QRY = String.format(
			"{%s: #, %s: {$in: #}}", Fields.OBJ_WS_ID, Fields.OBJ_ID);
	
	private void setObjectsHidden(final ResolvedMongoWSID ws,
			final List<Long> objectIDs, final boolean hide)
			throws WorkspaceCommunicationException {
		//TODO make general set field method?
		if (objectIDs.isEmpty()) {
			throw new IllegalArgumentException("Object IDs cannot be empty");
		}
		try {
			wsjongo.getCollection(COL_WORKSPACE_OBJS)
					.update(M_HIDOBJ_QRY, ws.getID(), objectIDs).multi()
					.with(M_HIDOBJ_WTH, hide);
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
	}
	
	@Override
	public void setObjectsDeleted(final Set<ObjectIDResolvedWS> objectIDs,
			final boolean delete)
			throws NoSuchObjectException, WorkspaceCommunicationException {
		final Map<ObjectIDResolvedWS, ResolvedMongoObjectID> ids =
				resolveObjectIDs(objectIDs, delete, true);
		final Map<ResolvedMongoWSID, List<Long>> toModify =
				new HashMap<ResolvedMongoWSID, List<Long>>();
		for (final ObjectIDResolvedWS o: objectIDs) {
			final ResolvedMongoWSID ws = query.convertResolvedWSID(
					o.getWorkspaceIdentifier());
			if (!toModify.containsKey(ws)) {
				toModify.put(ws, new ArrayList<Long>());
			}
			toModify.get(ws).add(ids.get(o).getId());
		}
		//Do this by workspace since per mongo docs nested $ors are crappy
		for (final ResolvedMongoWSID ws: toModify.keySet()) {
			setObjectsDeleted(ws, toModify.get(ws), delete);
			updateWorkspaceModifiedDate(ws);
		}
	}
	
	private static final String M_DELOBJ_WTH = String.format(
			"{$set: {%s: #, %s: #}}", Fields.OBJ_DEL, Fields.OBJ_MODDATE);
	
	private void setObjectsDeleted(final ResolvedMongoWSID ws,
			final List<Long> objectIDs, final boolean delete)
			throws WorkspaceCommunicationException {
		final String query;
		if (objectIDs.isEmpty()) {
			query = String.format(
					"{%s: %s, %s: %s}", Fields.OBJ_WS_ID, ws.getID(),
					Fields.OBJ_DEL, !delete);
		} else {
			query = String.format(
					"{%s: %s, %s: {$in: [%s]}, %s: %s}",
					Fields.OBJ_WS_ID, ws.getID(), Fields.OBJ_ID,
					StringUtils.join(objectIDs, ", "), Fields.OBJ_DEL, !delete);
		}
		try {
			wsjongo.getCollection(COL_WORKSPACE_OBJS).update(query).multi()
					.with(M_DELOBJ_WTH, delete, new Date());
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
	}
	
	private static final String M_DELWS_UPD = String.format("{%s: #}",
						Fields.WS_ID);
	private static final String M_DELWS_WTH = String.format(
			"{$set: {%s: #, %s: #}}", Fields.WS_DEL, Fields.WS_MODDATE);
	
	public void setWorkspaceDeleted(final ResolvedWorkspaceID rwsi,
			final boolean delete) throws WorkspaceCommunicationException {
		//there's a possibility of a race condition here if a workspace is
		//deleted and undeleted or vice versa in a very short amount of time,
		//but that seems so unlikely it's not worth the code
		final ResolvedMongoWSID mrwsi = query.convertResolvedWSID(rwsi);
		try {
			wsjongo.getCollection(COL_WORKSPACES).update(
							M_DELWS_UPD, mrwsi.getID())
					.with(M_DELWS_WTH, delete, new Date());
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		setObjectsDeleted(mrwsi, new ArrayList<Long>(), delete);
	}
	
	@Override
	public Set<WorkspaceUser> getAllWorkspaceOwners()
			throws WorkspaceCommunicationException {
		final Set<WorkspaceUser> ret = new HashSet<WorkspaceUser>();
		try {
			@SuppressWarnings("unchecked")
			final List<String> users = wsmongo.getCollection(COL_WORKSPACES)
					.distinct(Fields.WS_OWNER);
			for (final String u: users) {
				ret.add(new WorkspaceUser(u));
			}
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		return ret;
	}

	private static final String M_ADMIN_QRY = String.format(
			"{%s: #}", Fields.ADMIN_NAME);
	
	@Override
	public boolean isAdmin(WorkspaceUser putativeAdmin)
			throws WorkspaceCommunicationException {
		try {
			return wsjongo.getCollection(COL_ADMINS).count(M_ADMIN_QRY,
					putativeAdmin.getUser()) > 0;
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
	}
	

	@Override
	public Set<WorkspaceUser> getAdmins()
			throws WorkspaceCommunicationException {
		final Set<WorkspaceUser> ret = new HashSet<WorkspaceUser>();
		final DBCursor cur;
		try {
			cur = wsmongo.getCollection(COL_ADMINS).find();
			for (final DBObject dbo: cur) {
				ret.add(new WorkspaceUser((String) dbo.get(Fields.ADMIN_NAME)));
			}
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
		return ret;
	}

	@Override
	public void removeAdmin(WorkspaceUser user)
			throws WorkspaceCommunicationException {
		try {
			wsjongo.getCollection(COL_ADMINS).remove(M_ADMIN_QRY,
					user.getUser());
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
	}

	@Override
	public void addAdmin(WorkspaceUser user)
			throws WorkspaceCommunicationException {
		try {
			wsjongo.getCollection(COL_ADMINS).update(M_ADMIN_QRY,
					user.getUser()).upsert().with(M_ADMIN_QRY, user.getUser());
		} catch (MongoException me) {
			throw new WorkspaceCommunicationException(
					"There was a problem communicating with the database", me);
		}
	}
	
	public static class TestMongoSuperInternals {
		
		//screwy tests for methods that can't be tested in a black box manner
	
		private static MongoWorkspaceDB testdb;
		
		@BeforeClass
		public static void setUpClass() throws Exception {
			WorkspaceTestCommon.destroyAndSetupDB(1, "gridFS", "foo");
			String host = WorkspaceTestCommon.getHost();
			String db1 = WorkspaceTestCommon.getDB1();
			String mUser = WorkspaceTestCommon.getMongoUser();
			String mPwd = WorkspaceTestCommon.getMongoPwd();
			final String kidlpath = new Util().getKIDLpath();
			if (mUser == null || mUser == "") {
				testdb = new MongoWorkspaceDB(host, db1, kidlpath, "foo", "foo",
						"foo", null, TempFilesManager.forTests());
			} else {
				testdb = new MongoWorkspaceDB(host, db1, kidlpath, mUser, mPwd,
						"foo", null, TempFilesManager.forTests());
			}
		}
		
		@Test
		public void createObject() throws Exception {
			testdb.createWorkspace(new WorkspaceUser("u"), "ws", false, null, null);
			final Map<String, Object> data = new HashMap<String, Object>();
			Map<String, String> meta = new HashMap<String, String>();
			Map<String, Object> moredata = new HashMap<String, Object>();
			moredata.put("foo", "bar");
			data.put("fubar", moredata);
			meta.put("metastuff", "meta");
			Provenance p = new Provenance(new WorkspaceUser("kbasetest2"));
			TypeDefId t = new TypeDefId(new TypeDefName("SomeModule", "AType"), 0, 1);
			AbsoluteTypeDefId at = new AbsoluteTypeDefId(new TypeDefName("SomeModule", "AType"), 0, 1);
			WorkspaceSaveObject wo = new WorkspaceSaveObject(
					new ObjectIDNoWSNoVer("testobj"),
					new UObject(data), t, meta, p, false);
			List<ResolvedSaveObject> wco = new ArrayList<ResolvedSaveObject>();
			wco.add(wo.resolve(new DummyTypedObjectValidationReport(at, wo.getData()),
					new HashSet<Reference>(), new LinkedList<Reference>()));
			ObjectSavePackage pkg = new ObjectSavePackage();
			pkg.wo = wo.resolve(new DummyTypedObjectValidationReport(at, wo.getData()),
					new HashSet<Reference>(), new LinkedList<Reference>());
			ResolvedMongoWSID rwsi = new ResolvedMongoWSID("ws", 1, false, false);
			pkg.td = new TypeData(new Writable() {
				@Override
				public void write(OutputStream os) throws IOException {
					MAPPER.writeValue(os, data);				
				}
				@Override
				public void releaseResources() throws IOException {
				}
			}, at, data);
			testdb.saveObjects(new WorkspaceUser("u"), rwsi, wco);
			IDName r = testdb.saveWorkspaceObject(rwsi, 3, "testobj");
			pkg.name = r.name;
			testdb.saveProvenance(Arrays.asList(pkg));
			ObjectInformation md = testdb.saveObjectVersion(new WorkspaceUser("u"), rwsi, r.id, pkg);
			assertThat("objectid is revised to existing object", md.getObjectId(), is(1L));
		}
	}
}
