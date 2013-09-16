package us.kbase.workspace.database;

import java.util.List;
import java.util.Map;
import java.util.Set;

import us.kbase.workspace.database.exceptions.CorruptWorkspaceDBException;
import us.kbase.workspace.database.exceptions.NoSuchObjectException;
import us.kbase.workspace.database.exceptions.NoSuchWorkspaceException;
import us.kbase.workspace.database.exceptions.PreExistingWorkspaceException;
import us.kbase.workspace.database.exceptions.WorkspaceCommunicationException;
import us.kbase.workspace.workspaces.WorkspaceSaveObject;

public interface Database {

	public String getBackendType();
	
	public ResolvedWorkspaceID resolveWorkspace(final WorkspaceIdentifier wsi)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException;
	
	public Map<WorkspaceIdentifier, ResolvedWorkspaceID> resolveWorkspaces(
			Set<WorkspaceIdentifier> rwsis) throws NoSuchWorkspaceException,
			WorkspaceCommunicationException;

	public WorkspaceMetaData createWorkspace(WorkspaceUser owner, String wsname,
			boolean globalread, String description) throws
			PreExistingWorkspaceException, WorkspaceCommunicationException,
			CorruptWorkspaceDBException;
	
	public void setPermissions(ResolvedWorkspaceID rwsi,
			List<WorkspaceUser> users, Permission perm) throws
			WorkspaceCommunicationException, CorruptWorkspaceDBException;
	
	public Permission getPermission(WorkspaceUser user,
			ResolvedWorkspaceID rwsi)
			throws WorkspaceCommunicationException, CorruptWorkspaceDBException;
	
	public Map<ResolvedWorkspaceID, Permission> getPermissions(
			WorkspaceUser user, Set<ResolvedWorkspaceID> rwsis)
			throws WorkspaceCommunicationException, CorruptWorkspaceDBException;

	public Map<User, Permission> getUserAndGlobalPermission(WorkspaceUser user,
			ResolvedWorkspaceID rwsi) throws WorkspaceCommunicationException,
			CorruptWorkspaceDBException;
	
	public Map<User, Permission> getAllPermissions(
			ResolvedWorkspaceID rwsi) throws WorkspaceCommunicationException,
			CorruptWorkspaceDBException;

	public WorkspaceMetaData getWorkspaceMetadata(WorkspaceUser user,
			ResolvedWorkspaceID rwsi) throws CorruptWorkspaceDBException,
			WorkspaceCommunicationException;

	public String getWorkspaceDescription(ResolvedWorkspaceID rwsi)
			throws WorkspaceCommunicationException, CorruptWorkspaceDBException;
	
	public List<ObjectMetaData> saveObjects(WorkspaceUser user,
			ResolvedWorkspaceID rwsi, List<WorkspaceSaveObject> objects) throws
			NoSuchWorkspaceException, WorkspaceCommunicationException,
			NoSuchObjectException;
	
	public List<WorkspaceObjectData> getObjects(
			List<ObjectIDResolvedWS> objectIDs);
}
