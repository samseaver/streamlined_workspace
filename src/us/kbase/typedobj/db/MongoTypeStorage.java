package us.kbase.typedobj.db;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.bson.BSONObject;
import org.jongo.Jongo;
import org.jongo.MongoCollection;

import us.kbase.typedobj.exceptions.TypeStorageException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;

public class MongoTypeStorage implements TypeStorage {
	
	private Jongo jdb;
	private final DB db;
	
	public static final String TABLE_MODULE_REQUEST = "module_request";
	public static final String TABLE_MODULE_VERSION = "module_version";
	public static final String TABLE_MODULE_OWNER = "module_owner";
	public static final String TABLE_MODULE_INFO_HISTORY = "module_info_history";
	public static final String TABLE_MODULE_SPEC_HISTORY = "module_spec_history";
	public static final String TABLE_MODULE_TYPE_SCHEMA = "module_type_schema";
	public static final String TABLE_MODULE_TYPE_PARSE = "module_type_parse";
	public static final String TABLE_MODULE_FUNC_PARSE = "module_func_parse";
	public static final String TABLE_FUNC_REFS = "func_refs";
	public static final String TABLE_TYPE_REFS = "type_refs";

	public static final int MAX_REQUESTS_BY_USER = 30;
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	private static final Pattern dotSep = Pattern.compile(Pattern.quote("."));
	
	public MongoTypeStorage(DB db) {
		this.db = db;
		jdb = new Jongo(db);
		ensureIndeces();
	}
	
	private void ensureIndeces() {
		MongoCollection reqs = jdb.getCollection(TABLE_MODULE_REQUEST);
		reqs.ensureIndex("{moduleName:1,ownerUserId:1}", "{unique:true}");
		reqs.ensureIndex("{ownerUserId:1}", "{unique:false}");
		MongoCollection vers = jdb.getCollection(TABLE_MODULE_VERSION);
		vers.ensureIndex("{moduleName:1}", "{unique:true}");
		MongoCollection owns = jdb.getCollection(TABLE_MODULE_OWNER);
		owns.ensureIndex("{moduleName:1,ownerUserId:1}", "{unique:true}");
		owns.ensureIndex("{ownerUserId:1}", "{unique:false}");
		MongoCollection infos = jdb.getCollection(TABLE_MODULE_INFO_HISTORY);
		infos.ensureIndex("{moduleName:1,versionTime:1}", "{unique:true}");
		MongoCollection specs = jdb.getCollection(TABLE_MODULE_SPEC_HISTORY);
		specs.ensureIndex("{moduleName:1,versionTime:1}", "{unique:true}");
		MongoCollection tschs = jdb.getCollection(TABLE_MODULE_TYPE_SCHEMA);
		tschs.ensureIndex("{moduleName:1,typeName:1,version:1}", "{unique:true}");
		tschs.ensureIndex("{moduleName:1,moduleVersion:1}", "{unique:false}");
		MongoCollection tprs = jdb.getCollection(TABLE_MODULE_TYPE_PARSE);
		tprs.ensureIndex("{moduleName:1,typeName:1,version:1}", "{unique:true}");
		tprs.ensureIndex("{moduleName:1,moduleVersion:1}", "{unique:false}");
		MongoCollection fprs = jdb.getCollection(TABLE_MODULE_FUNC_PARSE);
		fprs.ensureIndex("{moduleName:1,funcName:1,version:1}", "{unique:true}");
		fprs.ensureIndex("{moduleName:1,moduleVersion:1}", "{unique:false}");
		MongoCollection frefs = jdb.getCollection(TABLE_FUNC_REFS);
		frefs.ensureIndex("{depModule:1,depName:1,depVersion:1}", "{unique:false}");
		frefs.ensureIndex("{refModule:1,refName:1,refVersion:1}", "{unique:false}");
		frefs.ensureIndex("{depModule:1,depModuleVersion:1}", "{unique:false}");
		MongoCollection trefs = jdb.getCollection(TABLE_TYPE_REFS);
		trefs.ensureIndex("{depModule:1,depName:1,depVersion:1}", "{unique:false}");
		trefs.ensureIndex("{refModule:1,refName:1,refVersion:1}", "{unique:false}");
		trefs.ensureIndex("{depModule:1,depModuleVersion:1}", "{unique:false}");
	}
	
	private Map<String, Object> toMap(final Object obj) {
		return MAPPER.convertValue(obj, new TypeReference<Map<String, Object>>() {});
	}
	
	private DBObject toDBObj(final Object obj) {
		return new BasicDBObject(toMap(obj));
	}
	
	private <T> T toObj(final DBObject dbo, final Class<T> clazz) {
		if (dbo == null) {
			return null;
		}
		return MAPPER.convertValue(toMapRec(dbo), clazz);
	}
	
	// Unimplemented error for dbo.toMap()
	// dbo is read only
	// can't call convertValue() on dbo since it has a 'size' field outside of the internal map
	// and just weird shit happens when you do anyway
	private Map<String, Object> toMapRec(final BSONObject dbo) {
		final Map<String, Object> ret = new HashMap<>();
		for (final String k: dbo.keySet()) {
			if (k.equals("_id")) {
				continue;
			}
			final Object o = dbo.get(k);
			if (o instanceof BSONObject) {
				ret.put(k, toMapRec((BSONObject) o));
			// may need lists too?
			} else {
				ret.put(k, o);
			}
		}
		return ret;
	}
	
	@Override
	public void addRefs(Set<RefInfo> typeRefs, Set<RefInfo> funcRefs)
			throws TypeStorageException {
		try {
			if (typeRefs.size() > 0) {
				DBCollection refs = db.getCollection(TABLE_TYPE_REFS);
				for (RefInfo ref : typeRefs) {
					if (ref.getDepModuleVersion() == 0)
						throw new TypeStorageException("Dependent type's module version was not initialized");
					refs.insert(toDBObj(ref));
				}
			}
			if (funcRefs.size() > 0) {
				DBCollection refs = db.getCollection(TABLE_FUNC_REFS);
				for (RefInfo ref : funcRefs) {
					if (ref.getDepModuleVersion() == 0)
						throw new TypeStorageException("Dependent function's module version was not initialized");
					refs.insert(toDBObj(ref));
				}
			}
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public long getLastReleasedModuleVersion(String moduleName) throws TypeStorageException {
		Long ret = getLastModuleVersionOrNull(moduleName);
		if (ret == null)
			throw new TypeStorageException("Module " + moduleName + " is not registered");
		return ret;
	}
	
	@Override
	public boolean checkModuleExist(String moduleName) throws TypeStorageException {
		return getLastModuleVersionOrNull(moduleName) != null;
	}
	
	private Long getLastModuleVersionOrNull(String moduleName) throws TypeStorageException {
		try {
			final DBCollection vers = db.getCollection(TABLE_MODULE_VERSION);
			final ModuleVersion ret = toObj(vers.findOne(
					new BasicDBObject("moduleName", moduleName)), ModuleVersion.class);
			return ret == null ? null : ret.releasedVersionTime;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public boolean checkModuleInfoRecordExist(String moduleName, long version) throws TypeStorageException {
		return getModuleInfoOrNull(moduleName, version) != null;
	}
	
	@Override
	public boolean checkModuleSpecRecordExist(String moduleName, long version) throws TypeStorageException {
		return getModuleSpecOrNull(moduleName, version) != null;
	}

	private ModuleInfo getModuleInfoOrNull(String moduleName, long version) throws TypeStorageException {
		try {
			final DBCollection infos = db.getCollection(TABLE_MODULE_INFO_HISTORY);
			final ModuleInfo info = toObj(infos.findOne(new BasicDBObject("moduleName", moduleName)
					.append("versionTime", version)), ModuleInfo.class);
			return info;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}

	private String getModuleSpecOrNull(String moduleName, long version) throws TypeStorageException {
		try {
			final DBCollection specs = db.getCollection(TABLE_MODULE_SPEC_HISTORY);
			final ModuleSpec spec = toObj(specs.findOne(new BasicDBObject("moduleName", moduleName)
					.append("versionTime", version)), ModuleSpec.class);
			return spec == null ? null : spec.document;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}

	@Override
	public boolean checkTypeSchemaRecordExists(String moduleName,
			String typeName, String version) throws TypeStorageException {
		try {
			MongoCollection docs = jdb.getCollection(TABLE_MODULE_TYPE_SCHEMA);
			return docs.findOne("{moduleName:#,typeName:#,version:#}", 
					moduleName, typeName, version).as(Map.class) != null;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public Set<String> getAllRegisteredModules(boolean withUnsupported) throws TypeStorageException {
		try {
			MongoCollection infos = jdb.getCollection(TABLE_MODULE_VERSION);
			Map<String, Boolean> map = getProjection(infos, "{}", "moduleName", String.class, "supported", Boolean.class);
			Set<String> ret = new TreeSet<String>();
			if (withUnsupported) {
				ret.addAll(map.keySet());
			} else {
				for (String module : map.keySet())
					if (map.get(module))
						ret.add(module);
			}
			return ret;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public boolean getModuleSupportedState(String moduleName)
			throws TypeStorageException {
		try {
			MongoCollection vers = jdb.getCollection(TABLE_MODULE_VERSION);
			ModuleVersion ret = vers.findOne("{moduleName:#}", moduleName).as(ModuleVersion.class);
			if (ret == null)
				throw new TypeStorageException("Support information is unavailable for module: " + moduleName);
			return ret.isSupported();
		} catch (TypeStorageException e) {
			throw e;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public String getFuncParseRecord(String moduleName, String funcName,
			String version) throws TypeStorageException {
		Map<String, Object> ret;
		try {
			MongoCollection docs = jdb.getCollection(TABLE_MODULE_FUNC_PARSE);
			ret = docs.findOne("{moduleName:#,funcName:#,version:#}",
					moduleName, funcName, version).as(Map.class);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
		if (ret == null)
			throw new TypeStorageException("Function parse record was not found " +
					"for " + moduleName + "." + funcName + "." + version);
		return ret.get("document").toString();
	}
	
	@Override
	public Set<RefInfo> getFuncRefsByDep(String depModule, String depFunc,
			String version) throws TypeStorageException {
		try {
			MongoCollection refs = jdb.getCollection(TABLE_FUNC_REFS);
			return Sets.newTreeSet(refs.find("{depModule:#,depName:#,depVersion:#}",
					depModule, depFunc, version).as(RefInfo.class));
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public Set<RefInfo> getFuncRefsByRef(String refModule, String refType,
			String version) throws TypeStorageException {
		try {
			MongoCollection refs = jdb.getCollection(TABLE_FUNC_REFS);
			return Sets.newTreeSet(refs.find("{refModule:#,refName:#,refVersion:#}",
					refModule, refType, version).as(RefInfo.class));
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public ModuleInfo getModuleInfoRecord(String moduleName, long version)
			throws TypeStorageException {
		ModuleInfo ret = getModuleInfoOrNull(moduleName, version);
		if (ret == null)
			throw new TypeStorageException("Info-record was not found for module: " + moduleName);
		return ret;
	}
	
	@Override
	public String getModuleSpecRecord(String moduleName, long version)
			throws TypeStorageException {
		String ret = getModuleSpecOrNull(moduleName, version);
		if (ret == null)
			throw new TypeStorageException("Spec-record was not found for module: " + moduleName);
		return ret;
	}
	
	@Override
	public TreeMap<Long, Boolean> getAllModuleVersions(String moduleName) throws TypeStorageException {
		try {
			MongoCollection infos = jdb.getCollection(TABLE_MODULE_INFO_HISTORY);
			Map<Long, Boolean> map = getProjection(infos, "{moduleName:#}", "versionTime", Long.class, 
					"released", Boolean.class, moduleName);
			long releaseVer = getLastReleasedModuleVersion(moduleName);
			TreeMap<Long, Boolean> ret = new TreeMap<Long, Boolean>();
			for (long ver : map.keySet())
				ret.put(ver, ver <= releaseVer && map.get(ver));
			return ret;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected <KT, VT> Map<KT, VT> getProjection(MongoCollection infos, String whereCondition, 
			String keySelectField, Class<KT> keyType, String valueSelectField, Class<VT> valueType, 
			Object... params) throws TypeStorageException {
		List<Map> data = Lists.newArrayList(infos.find(whereCondition, params).projection(
				"{'" + keySelectField + "':1,'" + valueSelectField + "':1}").as(Map.class));
		Map<KT, VT> ret = new LinkedHashMap<KT, VT>();
		for (Map<?,?> item : data) {
			Object key = getMongoProp(item, keySelectField);
			if (key == null || !(keyType.isInstance(key)))
				throw new TypeStorageException("Key is wrong: " + key);
			Object value = getMongoProp(item, valueSelectField);
			if (value == null || !(valueType.isInstance(value)))
				throw new TypeStorageException("Value is wrong: " + value);
			ret.put((KT)key, (VT)value);
		}
		return ret;
	}
	
	private static Object getMongoProp(Map<?,?> data, String propWithDots) {
		String[] parts = dotSep.split(propWithDots);
		Object value = null;
		for (String part : parts) {
			if (value != null) {
				data = (Map<?,?>)value;
			}
			value = data.get(part);
		}
		return value;
	}

	@Override
	public Map<String, Boolean> getAllTypeVersions(String moduleName, String typeName) throws TypeStorageException {
		if (typeName.contains("'"))
			throw new TypeStorageException("Type names with symbol ['] are not supperted");
		try {
			MongoCollection schemas = jdb.getCollection(TABLE_MODULE_INFO_HISTORY);
			Map<Long, String> typeMap = getProjection(schemas, "{moduleName:#,'types." + typeName + ".typeName':#}", 
					"versionTime", Long.class, "types." + typeName + ".typeVersion", String.class, 
					moduleName, typeName);
			Map<Long, Boolean> moduleMap = getAllModuleVersions(moduleName);
			Map<String, Boolean> ret = new LinkedHashMap<String, Boolean>();
			for (Map.Entry<Long, String> entry : typeMap.entrySet()) {
				long moduleVer = entry.getKey();
				String typeVer = entry.getValue();
				boolean prevTypeRet = ret.containsKey(typeVer) ? ret.get(typeVer) : false;
				boolean newTypeRet = moduleMap.get(moduleVer);
				ret.put(typeVer, prevTypeRet || newTypeRet);
			}
			return ret;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public Map<String, Boolean> getAllFuncVersions(String moduleName, String funcName) throws TypeStorageException {
		if (funcName.contains("'"))
			throw new TypeStorageException("Function names with symbol ['] are not supperted");
		try {
			MongoCollection schemas = jdb.getCollection(TABLE_MODULE_INFO_HISTORY);
			Map<Long, String> funcMap = getProjection(schemas, "{moduleName:#,'funcs." + funcName + ".funcName':#}", 
					"versionTime", Long.class, "funcs." + funcName + ".funcVersion", String.class, 
					moduleName, funcName);
			Map<Long, Boolean> moduleMap = getAllModuleVersions(moduleName);
			Map<String, Boolean> ret = new LinkedHashMap<String, Boolean>();
			for (Map.Entry<Long, String> entry : funcMap.entrySet()) {
				long moduleVer = entry.getKey();
				String funcVer = entry.getValue();
				boolean prevFuncRet = ret.containsKey(funcVer) ? ret.get(funcVer) : false;
				boolean newFuncRet = moduleMap.get(moduleVer);
				ret.put(funcVer, prevFuncRet || newFuncRet);
			}
			return ret;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public long generateNewModuleVersion(String moduleName) throws TypeStorageException {
		long ret = System.currentTimeMillis();
		Long value = getLastModuleVersionWithUnreleasedOrNull(moduleName);
		if (value != null && ret <= value)
			ret = value + 1;
		return ret;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public String getTypeParseRecord(String moduleName, String typeName,
			String version) throws TypeStorageException {
		Map<String, Object> ret;
		try {
			MongoCollection docs = jdb.getCollection(TABLE_MODULE_TYPE_PARSE);
			ret = docs.findOne("{moduleName:#,typeName:#,version:#}", 
					moduleName, typeName, version).as(Map.class);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
		if (ret == null)
			throw new TypeStorageException("Type parse record was not found " +
					"for " + moduleName + "." + typeName + "." + version);
		return ret.get("document").toString();
	}
	
	@Override
	public Set<RefInfo> getTypeRefsByDep(String depModule, String depType,
			String version) throws TypeStorageException {
		try {
			MongoCollection refs = jdb.getCollection(TABLE_TYPE_REFS);
			return Sets.newTreeSet(refs.find("{depModule:#,depName:#,depVersion:#}",
					depModule, depType, version).as(RefInfo.class));
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public Set<RefInfo> getTypeRefsByRef(String refModule, String refType,
			String version) throws TypeStorageException {
		try {
			MongoCollection refs = jdb.getCollection(TABLE_TYPE_REFS);
			return Sets.newTreeSet(refs.find("{refModule:#,refName:#,refVersion:#}",
					refModule, refType, version).as(RefInfo.class));
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public String getTypeSchemaRecord(String moduleName, String typeName,
			String version) throws TypeStorageException {
		String query = "{moduleName:#,typeName:#,version:#}";
		Map<String, Object> ret = findTypeRecord(moduleName, typeName, version,
				query, moduleName, typeName, version);
		if (ret == null)
			throw new TypeStorageException("Type schema record was not found " +
					"for " + moduleName + "." + typeName + "." + version);
		return ret.get("document").toString();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> findTypeRecord(String moduleName,
			String typeName, String version, String query, Object... params)
			throws TypeStorageException {
		Map<String, Object> ret;
		try {
			MongoCollection docs = jdb.getCollection(TABLE_MODULE_TYPE_SCHEMA);
			ret = docs.findOne(query, params).as(Map.class);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
		return ret;
	}

	@Override
	public String getTypeMd5(String moduleName, String typeName,
			String version) throws TypeStorageException {
		String query = "{moduleName:#,typeName:#,version:#}";
		Map<String, Object> ret = findTypeRecord(moduleName, typeName, version,
				query, moduleName, typeName, version);
		if (ret == null)
			throw new TypeStorageException("Type schema record was not found " +
					"for " + moduleName + "." + typeName + "." + version);
		return ret.get("md5hash").toString();
	}

	@SuppressWarnings("rawtypes")
	@Override
	public List<String> getTypeVersionsByMd5(String moduleName, String typeName,
			String md5) throws TypeStorageException {
		try {
			String query = "{moduleName:#,typeName:#,md5hash:#}";
			MongoCollection docs = jdb.getCollection(TABLE_MODULE_TYPE_SCHEMA);
			List<Map> list = Lists.newArrayList(docs.find(query, moduleName, typeName, md5).as(Map.class));
			List<String> ret = new ArrayList<String>();
			for (Map<?,?> map : list)
				ret.add(map.get("version").toString());
			return ret;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public void removeAllData() throws TypeStorageException {
		jdb.getCollection(TABLE_TYPE_REFS).remove();
		jdb.getCollection(TABLE_FUNC_REFS).remove();
		jdb.getCollection(TABLE_MODULE_TYPE_PARSE).remove();
		jdb.getCollection(TABLE_MODULE_TYPE_SCHEMA).remove();
		jdb.getCollection(TABLE_MODULE_FUNC_PARSE).remove();
		jdb.getCollection(TABLE_MODULE_REQUEST).remove();
		jdb.getCollection(TABLE_MODULE_OWNER).remove();
		jdb.getCollection(TABLE_MODULE_SPEC_HISTORY).remove();
		jdb.getCollection(TABLE_MODULE_INFO_HISTORY).remove();
		jdb.getCollection(TABLE_MODULE_VERSION).remove();
	}
	
	// removed removeModule method after 3ad9e2d. Untested, unused.
		
	@Override
	public void writeFuncParseRecord(String moduleName, String funcName,
			String version, long moduleVersion, String parseText) throws TypeStorageException {
		try {
			MongoCollection recs = jdb.getCollection(TABLE_MODULE_FUNC_PARSE);
			recs.remove("{moduleName:#,funcName:#,version:#}", moduleName, funcName, version);
			FuncRecord rec = new FuncRecord();
			rec.setModuleName(moduleName);
			rec.setFuncName(funcName);
			rec.setVersion(version);
			rec.setModuleVersion(moduleVersion);
			rec.setDocument(parseText);
			recs.save(rec);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	private void writeModuleVersion(String moduleName, long version) throws TypeStorageException {
		try {
			MongoCollection vers = jdb.getCollection(TABLE_MODULE_VERSION);
			if (vers.findOne("{moduleName:#}", moduleName).as(ModuleVersion.class) == null) {
				ModuleVersion ver = new ModuleVersion();
				ver.setModuleName(moduleName);
				ver.setVersionTime(version);
				ver.setReleasedVersionTime(version);
				ver.setSupported(true);
				vers.insert(ver);
			} else {
				vers.update("{moduleName:#}", moduleName).with("{$set: {versionTime: #}}", version);
			}
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}

	@Override
	public void writeModuleRecords(ModuleInfo info,  String specDocument, long version) 
			throws TypeStorageException {
		writeModuleVersion(info.getModuleName(), version);
		writeModuleInfo(info, version);
		try {
			MongoCollection specs = jdb.getCollection(TABLE_MODULE_SPEC_HISTORY);
			ModuleSpec spec = new ModuleSpec();
			spec.setModuleName(info.getModuleName());
			spec.setDocument(specDocument);
			spec.setVersionTime(version);
			specs.insert(spec);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}

	@Override
	public void initModuleInfoRecord(ModuleInfo info) throws TypeStorageException {
		long version = generateNewModuleVersion(info.getModuleName());
		writeModuleVersion(info.getModuleName(), version);
		writeModuleInfo(info, version);
	}
	
	private void writeModuleInfo(ModuleInfo info, long version) throws TypeStorageException {
		try {
			MongoCollection infos = jdb.getCollection(TABLE_MODULE_INFO_HISTORY);
			info.setVersionTime(version);
			infos.insert(info);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public void writeTypeParseRecord(String moduleName, String typeName,
			String version, long moduleVersion, String document) throws TypeStorageException {
		try {
			MongoCollection recs = jdb.getCollection(TABLE_MODULE_TYPE_PARSE);
			recs.remove("{moduleName:#,typeName:#,version:#}", moduleName, typeName, version);
			TypeRecord rec = new TypeRecord();
			rec.setModuleName(moduleName);
			rec.setTypeName(typeName);
			rec.setVersion(version);
			rec.setModuleVersion(moduleVersion);
			rec.setDocument(document);
			recs.save(rec);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public Map<Long, Boolean> getModuleVersionsForTypeVersion(String moduleName, String typeName, 
			String typeVersion) throws TypeStorageException {
		if (typeName.contains("'"))
			throw new TypeStorageException("Type names with symbol ['] are not supperted");
		try {
			MongoCollection infoCol = jdb.getCollection(TABLE_MODULE_INFO_HISTORY);
			return getProjection(infoCol, "{moduleName:#,'types." + typeName + ".typeVersion':#," +
					"'types." + typeName + ".supported':#}", "versionTime", Long.class, 
					"released", Boolean.class, moduleName, typeVersion, true);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public Map<Long, Boolean> getModuleVersionsForFuncVersion(String moduleName, 
			String funcName, String funcVersion) throws TypeStorageException {
		if (funcName.contains("'"))
			throw new TypeStorageException("Function names with symbol ['] are not supperted");
		try {
			MongoCollection infoCol = jdb.getCollection(TABLE_MODULE_INFO_HISTORY);
			return getProjection(infoCol, "{moduleName:#,'funcs." + funcName + ".funcVersion':#}", 
					"versionTime", Long.class, "released", Boolean.class, 
					moduleName, funcVersion);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public void writeTypeSchemaRecord(String moduleName, String typeName,
			String version, long moduleVersion, String document, String md5) throws TypeStorageException {
		try {
			MongoCollection recs = jdb.getCollection(TABLE_MODULE_TYPE_SCHEMA);
			recs.remove("{moduleName:#,typeName:#,version:#}", moduleName, typeName, version);
			TypeRecord rec = new TypeRecord();
			rec.setModuleName(moduleName);
			rec.setTypeName(typeName);
			rec.setVersion(version);
			rec.setModuleVersion(moduleVersion);
			rec.setDocument(document);
			rec.setMd5hash(md5);
			recs.insert(rec);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public void addNewModuleRegistrationRequest(String moduleName, String userId)
			throws TypeStorageException {
		try {
			MongoCollection recs = jdb.getCollection(TABLE_MODULE_REQUEST);
			int prevCount = Lists.newArrayList(recs.find("{ownerUserId:#}", 
					userId).as(OwnerInfo.class)).size();
			if (prevCount >= MAX_REQUESTS_BY_USER)
				throw new TypeStorageException("User " + userId + " has maximal count " +
						"of requests: " + MAX_REQUESTS_BY_USER);
			if (recs.findOne("{moduleName:#}", moduleName).as(OwnerInfo.class) != null)
				throw new TypeStorageException("Registration of module " + moduleName + " was already requested");
			if (checkModuleExist(moduleName))
				throw new TypeStorageException("Module " + moduleName + " was already registered");
			OwnerInfo rec = new OwnerInfo();
			rec.setOwnerUserId(userId);
			rec.setWithChangeOwnersPrivilege(true);
			rec.setModuleName(moduleName);
			recs.insert(rec);
		} catch (TypeStorageException e) {
			throw e;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public String getOwnerForNewModuleRegistrationRequest(String moduleName)
			throws TypeStorageException {
		OwnerInfo ret;
		try {
			MongoCollection recs = jdb.getCollection(TABLE_MODULE_REQUEST);
			ret = recs.findOne("{moduleName:#}", moduleName).as(OwnerInfo.class);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
		if (ret == null)
			throw new TypeStorageException("There is no request for module " + moduleName);
		return ret.getOwnerUserId();
	}
	
	@Override
	public void addOwnerToModule(String moduleName, String userId,
			boolean withChangeOwnersPrivilege) throws TypeStorageException {
		try {
			MongoCollection recs = jdb.getCollection(TABLE_MODULE_OWNER);
			recs.remove("{moduleName:#,ownerUserId:#}", moduleName, userId);
			OwnerInfo rec = new OwnerInfo();
			rec.setOwnerUserId(userId);
			rec.setWithChangeOwnersPrivilege(withChangeOwnersPrivilege);
			rec.setModuleName(moduleName);
			recs.insert(rec);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public List<OwnerInfo> getNewModuleRegistrationRequests()
			throws TypeStorageException {
		try {
			MongoCollection recs = jdb.getCollection(TABLE_MODULE_REQUEST);
			return Lists.newArrayList(recs.find().as(OwnerInfo.class));
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public Map<String, OwnerInfo> getOwnersForModule(String moduleName)
			throws TypeStorageException {
		try {
			MongoCollection recs = jdb.getCollection(TABLE_MODULE_OWNER);
			List<OwnerInfo> owners = Lists.newArrayList(recs.find("{moduleName:#}", moduleName).as(OwnerInfo.class));
			Map<String, OwnerInfo> ret = new TreeMap<String, OwnerInfo>();
			for (OwnerInfo oi : owners)
				ret.put(oi.getOwnerUserId(), oi);
			return ret;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}

	@Override
	public Map<String, OwnerInfo> getModulesForOwner(String userId)
			throws TypeStorageException {
		try {
			MongoCollection recs = jdb.getCollection(TABLE_MODULE_OWNER);
			List<OwnerInfo> owners = Lists.newArrayList(recs.find("{ownerUserId:#}", userId).as(OwnerInfo.class));
			Map<String, OwnerInfo> ret = new TreeMap<String, OwnerInfo>();
			for (OwnerInfo oi : owners)
				ret.put(oi.getModuleName(), oi);
			return ret;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}

	@Override
	public void removeNewModuleRegistrationRequest(String moduleName,
			String userId) throws TypeStorageException {
		try {
			MongoCollection recs = jdb.getCollection(TABLE_MODULE_REQUEST);
			recs.remove("{moduleName:#,ownerUserId:#}", moduleName, userId);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public void removeOwnerFromModule(String moduleName, String userId)
			throws TypeStorageException {
		try {
			MongoCollection recs = jdb.getCollection(TABLE_MODULE_OWNER);
			recs.remove("{moduleName:#,ownerUserId:#}", moduleName, userId);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public Map<String, Long> listObjects() throws TypeStorageException {
		String[] tables = {
			TABLE_FUNC_REFS,
			TABLE_MODULE_FUNC_PARSE,
			TABLE_MODULE_INFO_HISTORY,
			TABLE_MODULE_OWNER,
			TABLE_MODULE_REQUEST,
			TABLE_MODULE_SPEC_HISTORY,
			TABLE_MODULE_TYPE_PARSE,
			TABLE_MODULE_TYPE_SCHEMA,
			TABLE_MODULE_VERSION,
			TABLE_TYPE_REFS
		};
		Map<String, Long> ret = new TreeMap<String, Long>();
		for (String table : tables) {
			MongoCollection recs = jdb.getCollection(table);
			List<Object> rows = Lists.newArrayList(recs.find().as(Object.class));
			ret.put(table, (long)rows.size());
		}
		return ret;
	}
	
	@Override
	public void removeModuleVersionAndSwitchIfNotCurrent(String moduleName,
			long versionToDelete, long versionToSwitchTo)
			throws TypeStorageException {
		try {
			jdb.getCollection(TABLE_TYPE_REFS).remove("{depModule:#,depModuleVersion:#}", moduleName, versionToDelete);
			jdb.getCollection(TABLE_FUNC_REFS).remove("{depModule:#,depModuleVersion:#}", moduleName, versionToDelete);
			jdb.getCollection(TABLE_MODULE_TYPE_SCHEMA).remove("{moduleName:#,moduleVersion:#}", moduleName, versionToDelete);
			jdb.getCollection(TABLE_MODULE_TYPE_PARSE).remove("{moduleName:#,moduleVersion:#}", moduleName, versionToDelete);
			jdb.getCollection(TABLE_MODULE_FUNC_PARSE).remove("{moduleName:#,moduleVersion:#}", moduleName, versionToDelete);
			jdb.getCollection(TABLE_MODULE_SPEC_HISTORY).remove("{moduleName:#,versionTime:#}", moduleName, versionToDelete);
			jdb.getCollection(TABLE_MODULE_INFO_HISTORY).remove("{moduleName:#,versionTime:#}", moduleName, versionToDelete);
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
		if (versionToSwitchTo != getLastModuleVersionWithUnreleased(moduleName))
			writeModuleVersion(moduleName, versionToSwitchTo);
	}

	@Override
	public long getLastModuleVersionWithUnreleased(String moduleName)
			throws TypeStorageException {
		Long ret = getLastModuleVersionWithUnreleasedOrNull(moduleName);
		if (ret == null)
			throw new TypeStorageException("Module" + moduleName + " was not registered");
		return ret;
	}
	
	private Long getLastModuleVersionWithUnreleasedOrNull(String moduleName)
			throws TypeStorageException {
		try {
			MongoCollection vers = jdb.getCollection(TABLE_MODULE_VERSION);
			ModuleVersion ret = vers.findOne("{moduleName:#}", moduleName).as(ModuleVersion.class);
			if (ret == null)
				return null;
			return ret.versionTime;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	@Override
	public void setModuleReleaseVersion(String moduleName, long version)
			throws TypeStorageException {
		try {
			MongoCollection vers = jdb.getCollection(TABLE_MODULE_VERSION);
			if (vers.findOne("{moduleName:#}", moduleName).as(ModuleVersion.class) == null) {
				throw new TypeStorageException("Module" + moduleName + " was not registered");
			} else {
				MongoCollection infos = jdb.getCollection(TABLE_MODULE_INFO_HISTORY);
				infos.update("{moduleName:#, versionTime:#}", moduleName, version).with("{$set: {released: #}}", true);
				vers.update("{moduleName:#}", moduleName).with("{$set: {releasedVersionTime: #}}", version);
			}
		} catch (TypeStorageException e) {
			throw e;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	// used to be a method here called copyModuleVersion, removed after commit 3ad9e2d.

	@Override
	public void changeModuleSupportedState(String moduleName, boolean supported)
			throws TypeStorageException {
		try {
			MongoCollection vers = jdb.getCollection(TABLE_MODULE_VERSION);
			if (vers.findOne("{moduleName:#}", moduleName).as(ModuleVersion.class) == null)
				throw new TypeStorageException("Support information is unavailable for module: " + moduleName);
			vers.update("{moduleName:#}", moduleName).with("{$set: {supported: #}}", supported);
		} catch (TypeStorageException e) {
			throw e;
		} catch (Exception e) {
			throw new TypeStorageException(e);
		}
	}
	
	public static class ModuleSpec {
		private String moduleName;
		private String document;
		private long versionTime;
		
		public String getModuleName() {
			return moduleName;
		}
		
		public void setModuleName(String moduleName) {
			this.moduleName = moduleName;
		}
		
		public String getDocument() {
			return document;
		}
		
		public void setDocument(String document) {
			this.document = document;
		}
		
		public long getVersionTime() {
			return versionTime;
		}
		
		public void setVersionTime(long versionTime) {
			this.versionTime = versionTime;
		}
	}
	
	public static class ModuleVersion {
		private String moduleName;
		private long versionTime;
		private long releasedVersionTime;
		private boolean supported;
		
		public String getModuleName() {
			return moduleName;
		}
		
		public void setModuleName(String moduleName) {
			this.moduleName = moduleName;
		}
		
		public long getVersionTime() {
			return versionTime;
		}
		
		public void setVersionTime(long versionTime) {
			this.versionTime = versionTime;
		}
		
		public long getReleasedVersionTime() {
			return releasedVersionTime;
		}
		
		public void setReleasedVersionTime(long releasedVersionTime) {
			this.releasedVersionTime = releasedVersionTime;
		}
		
		public boolean isSupported() {
			return supported;
		}
		
		public void setSupported(boolean supported) {
			this.supported = supported;
		}
	}
	
	public class TypeRecord {
		private String moduleName; 
		private String typeName;
		private String version; 
		private long moduleVersion;
		private String document;
		private String md5hash;
		
		public TypeRecord() {
		}
		
		public String getModuleName() {
			return moduleName;
		}
		
		public void setModuleName(String moduleName) {
			this.moduleName = moduleName;
		}
		
		public String getTypeName() {
			return typeName;
		}
		
		public void setTypeName(String typeName) {
			this.typeName = typeName;
		}
		
		public String getVersion() {
			return version;
		}
		
		public void setVersion(String version) {
			this.version = version;
		}
		
		public long getModuleVersion() {
			return moduleVersion;
		}
		
		public void setModuleVersion(long moduleVersion) {
			this.moduleVersion = moduleVersion;
		}
		
		public String getDocument() {
			return document;
		}
		
		public void setDocument(String document) {
			this.document = document;
		}
		
		public String getMd5hash() {
			return md5hash;
		}
		
		public void setMd5hash(String md5hash) {
			this.md5hash = md5hash;
		}
	}
	
	public class FuncRecord {
		private String moduleName; 
		private String funcName;
		private String version; 
		private long moduleVersion;
		private String document;
		
		public String getModuleName() {
			return moduleName;
		}
		
		public void setModuleName(String moduleName) {
			this.moduleName = moduleName;
		}

		public String getFuncName() {
			return funcName;
		}
		
		public void setFuncName(String funcName) {
			this.funcName = funcName;
		}
		
		public String getVersion() {
			return version;
		}
		
		public void setVersion(String version) {
			this.version = version;
		}
		
		public long getModuleVersion() {
			return moduleVersion;
		}
		
		public void setModuleVersion(long moduleVersion) {
			this.moduleVersion = moduleVersion;
		}
		
		public String getDocument() {
			return document;
		}
		
		public void setDocument(String document) {
			this.document = document;
		}
	}
}
