package us.kbase.workspace.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.ini4j.Ini;
import org.ini4j.Profile.Section;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import us.kbase.JsonClientException;
import us.kbase.ServerException;
import us.kbase.Tuple6;
import us.kbase.workspace.CreateWorkspaceParams;
import us.kbase.workspace.SetPermissionsParams;
import us.kbase.workspace.WorkspaceClient;
import us.kbase.workspace.WorkspaceIdentity;
import us.kbase.workspace.WorkspaceServer;

/*
 * These tests are specifically for testing the JSON-RPC communications between
 * the client, up to the invocation of the {@link us.kbase.workspace.workspaces.Workspaces}
 * methods. As such they do not test the full functionality of the Workspaces methods;
 * {@link us.kbase.workspace.workspaces.test.TestWorkspaces} handles that. This means
 * that only one backend (the simplest gridFS backend) is tested here, while TestWorkspaces
 * tests all backends and {@link us.kbase.workspace.database.Database} implementations.
 */
public class JSONRPCLayerTest {
	
	private static WorkspaceServer SERVER = null;
	private static WorkspaceClient CLIENT1 = null;
	private static String USERNOEMAIL = null;
	private static String USER1 = null;
	private static WorkspaceClient CLIENT2 = null;
	private static String USER2 = null;
	private static WorkspaceClient CLIENT_NO_AUTH = null;
	
	private static class ServerThread extends Thread {
		
		public void run() {
			try {
				SERVER.startupServer();
			} catch (Exception e) {
				System.err.println("Can't start server:");
				e.printStackTrace();
			}
		}
	}
	
	//http://quirkygba.blogspot.com/2009/11/setting-environment-variables-in-java.html
	@SuppressWarnings("unchecked")
	public static Map<String, String> getenv() throws NoSuchFieldException,
			SecurityException, IllegalArgumentException, IllegalAccessException {
		Map<String, String> unmodifiable = System.getenv();
		Class<?> cu = unmodifiable.getClass();
		Field m = cu.getDeclaredField("m");
		m.setAccessible(true);
		return (Map<String, String>) m.get(unmodifiable);
	}
	
	@BeforeClass
	public static void setUpClass() throws Exception {
		//TODO catch exceptions and print nice errors - next deploy
		USER1 = System.getProperty("test.user1");
		USER2 = System.getProperty("test.user2");
		USERNOEMAIL = System.getProperty("test.user.noemail");
		String p1 = System.getProperty("test.pwd1");
		String p2 = System.getProperty("test.pwd2");
		Common.destroyAndSetupDB(1, "gridFS", null);
		
		//write the server config file:
		File iniFile = File.createTempFile("test", ".cfg", new File("./"));
		iniFile.deleteOnExit();
		System.out.println("Created temporary config file: " + iniFile.getAbsolutePath());
		Ini ini = new Ini();
		Section ws = ini.add("Workspace");
		ws.add("mongodb-host", Common.getHost());
		ws.add("mongodb-database", Common.getDB1());
		ws.add("mongodb-user", Common.getMongoUser());
		ws.add("mongodb-pwd", Common.getMongoPwd());
		ws.add("backend-secret", "");
		ini.store(iniFile);
		
		//set up env
		Map<String, String> env = getenv();
		env.put("KB_DEPLOYMENT_CONFIG", iniFile.getAbsolutePath());
		env.put("KB_SERVICE_NAME", "Workspace");

		SERVER = new WorkspaceServer();
		new ServerThread().start();
		System.out.println("Main thread waiting for server to start up");
		while(SERVER.getServerPort() == null) {
			Thread.sleep(1000);
		}
		int port = SERVER.getServerPort();
		System.out.println("Started test server on port " + port);
		System.out.println("Starting tests");
		CLIENT1 = new WorkspaceClient(new URL("http://localhost:" + port), USER1, p1);
		CLIENT2 = new WorkspaceClient(new URL("http://localhost:" + port), USER2, p2);
		CLIENT_NO_AUTH = new WorkspaceClient(new URL("http://localhost:" + port));
		CLIENT1.setAuthAllowedForHttp(true);
		CLIENT2.setAuthAllowedForHttp(true);
		CLIENT_NO_AUTH.setAuthAllowedForHttp(true);
	}
	
	@AfterClass
	public static void tearDownClass() throws Exception {
		System.out.print("Killing server... ");
		SERVER.stopServer();
		System.out.println("Done");
	}
	
	@Test
	public void createWSandCheck() throws Exception {
		Tuple6<Integer, String, String, String, String, String> meta =
				CLIENT1.createWorkspace(new CreateWorkspaceParams()
					.withWorkspace("foo")
					.withGlobalread("r")
					.withDescription("boogabooga"));
		Tuple6<Integer, String, String, String, String, String> metaget =
				CLIENT1.getWorkspaceMetadata(new WorkspaceIdentity()
						.withWorkspace("foo"));
		assertThat("ids are equal", meta.getE1(), is(metaget.getE1()));
		assertThat("moddates equal", meta.getE4(), is(metaget.getE4()));
		for (Tuple6<Integer, String, String, String, String, String> m:
				Arrays.asList(meta, metaget)) {
			assertThat("ws name correct", m.getE2(), is("foo"));
			assertThat("user name correct", m.getE3(), is(USER1));
			assertThat("permission correct", m.getE5(), is("a"));
			assertThat("global read correct", m.getE6(), is("r"));
		}
		assertThat("description correct", CLIENT1.getWorkspaceDescription(
				new WorkspaceIdentity().withWorkspace("foo")), is("boogabooga"));
	}
	
	@Test
	public void createWSBadGlobal() throws Exception {
		CLIENT1.createWorkspace(new CreateWorkspaceParams()
			.withWorkspace("gl1")); //should work fine w/o globalread
		CLIENT1.createWorkspace(new CreateWorkspaceParams()
		.withWorkspace("gl2").withGlobalread("n")); //should work fine w/o globalread
		assertThat("globalread correct", CLIENT1.getWorkspaceMetadata(
				new WorkspaceIdentity().withWorkspace("gl1")).getE6(), is("n"));
		assertThat("globalread correct", CLIENT1.getWorkspaceMetadata(
				new WorkspaceIdentity().withWorkspace("gl2")).getE6(), is("n"));
		try {
			CLIENT1.createWorkspace(new CreateWorkspaceParams()
				.withWorkspace("gl_fail").withGlobalread("w"));
			fail("call succeeded w/ illegal global read param");
		} catch (ServerException e) {
			assertThat("correct exception message", e.getLocalizedMessage(),
					is("globalread must be n or r"));
		}
		try {
			CLIENT1.createWorkspace(new CreateWorkspaceParams()
				.withWorkspace("gl_fail").withGlobalread("a"));
			fail("call succeeded w/ illegal global read param");
		} catch (ServerException e) {
			assertThat("correct exception message", e.getLocalizedMessage(),
					is("globalread must be n or r"));
		}
		try {
			CLIENT1.createWorkspace(new CreateWorkspaceParams()
				.withWorkspace("gl_fail").withGlobalread("b"));
			fail("call succeeded w/ illegal global read param");
		} catch (ServerException e) {
			assertThat("correct exception message", e.getLocalizedMessage(),
					is("globalread must be n or r"));
		}
	}
	
	@Test
	public void createWSNoAuth() throws Exception {
		try {
			CLIENT_NO_AUTH.createWorkspace(new CreateWorkspaceParams().withWorkspace("noauth"));
			fail("created workspace without auth");
		} catch (IllegalStateException e) {
			assertThat("correct exception message", e.getLocalizedMessage(),
					is("RPC method requires authentication but neither user nor token was set"));
		}
	}

	@Test
	public void setBadPermissions() throws Exception {
		CLIENT1.createWorkspace(new CreateWorkspaceParams().withWorkspace("badperms"));
		try {
			CLIENT1.setPermissions(new SetPermissionsParams().withWorkspace("badperms")
					.withUsers(Arrays.asList(USER2)));
			fail("able to set null permission");
		} catch (ServerException e) {
			assertThat("correct exception message", e.getLocalizedMessage(),
					is("Invalid permission: null"));
		
		}
		try {
			CLIENT1.setPermissions(new SetPermissionsParams().withWorkspace("badperms")
					.withNewPermission("f").withUsers(Arrays.asList(USER2)));
			fail("able to set illegal permission");
		} catch (ServerException e) {
			assertThat("correct exception message", e.getLocalizedMessage(),
					is("Invalid permission: f"));
		
		}
		try {
			CLIENT1.setPermissions(new SetPermissionsParams().withWorkspace("badperms")
					.withNewPermission("r").withUsers(new ArrayList<String>()));
			fail("able to set permission with no useres");
		} catch (ServerException e) {
			assertThat("correct exception message", e.getLocalizedMessage(),
					is("Must provide at least one user"));
		}
		
		try {
			CLIENT1.setPermissions(new SetPermissionsParams().withWorkspace("badperms")
					.withNewPermission("r").withUsers(Arrays.asList(USER2,
					"thisisnotarealuserihopeotherwisethistestwillfailandthatdbeabadthing")));
			fail("able to set  permission with bad user");
		} catch (ServerException e) {
			assertThat("correct exception message", e.getLocalizedMessage(),
					is("User thisisnotarealuserihopeotherwisethistestwillfailandthatdbeabadthing is not a valid user"));
		}
		Map<String, String> expected = new HashMap<String, String>();
		expected.put(USER1, "a");
		Map<String, String> perms = CLIENT1.getPermissions(new WorkspaceIdentity().withWorkspace("badperms"));
		assertThat("Bad permissions were added to a workspace", perms, is(expected));
	}
	
	@Test
	public void permissions() throws IOException, JsonClientException {
		CLIENT1.createWorkspace(new CreateWorkspaceParams().withWorkspace("permspriv")
				.withDescription("foo"));
		CLIENT1.createWorkspace(new CreateWorkspaceParams().withWorkspace("permsglob")
				.withGlobalread("r").withDescription("bar"));
		//should work, global read
		CLIENT2.getWorkspaceDescription(new WorkspaceIdentity().withWorkspace("permsglob"));
		CLIENT_NO_AUTH.getWorkspaceDescription(new WorkspaceIdentity().withWorkspace("permsglob"));
		CLIENT_NO_AUTH.getWorkspaceMetadata(new WorkspaceIdentity().withWorkspace("permsglob"));
		
		try {
			CLIENT_NO_AUTH.getWorkspaceDescription(new WorkspaceIdentity().withWorkspace("permspriv"));
			fail("able to read workspace desc with no auth");
		} catch (ServerException e) {
			assertThat("exception message corrent", e.getLocalizedMessage(),
					is("Anonymous users may not read workspace permspriv"));
		}
		
		try {
			CLIENT_NO_AUTH.getWorkspaceMetadata(new WorkspaceIdentity().withWorkspace("permspriv"));
			fail("able to read workspace desc with no auth");
		} catch (ServerException e) {
			assertThat("exception message corrent", e.getLocalizedMessage(),
					is("Anonymous users may not read workspace permspriv"));
		}
		
		try {
			CLIENT2.getWorkspaceDescription(new WorkspaceIdentity().withWorkspace("permspriv"));
			fail("Able to get ws desc without read perms");
		} catch (ServerException e) {
			assertThat("Correct excp message", e.getLocalizedMessage(),
					is("User kbasetest2 may not read workspace permspriv"));
		}
		CLIENT1.setPermissions(new SetPermissionsParams().withWorkspace("permspriv")
				.withNewPermission("r").withUsers(Arrays.asList(USER2)));
		CLIENT2.getWorkspaceDescription(new WorkspaceIdentity().withWorkspace("permspriv")); //should work, now readable
		//TODO WAIT for more methods: test write permissions
		try {
			CLIENT2.setPermissions(new SetPermissionsParams().withWorkspace("permspriv")
					.withNewPermission("a").withUsers(Arrays.asList(USER1)));
		} catch (ServerException e) {
			assertThat("Correct excp message", e.getLocalizedMessage(),
					is("User kbasetest2 may not set permissions on workspace permspriv"));
		}
		CLIENT1.setPermissions(new SetPermissionsParams().withWorkspace("permspriv")
				.withNewPermission("a").withUsers(Arrays.asList(USER2)));
		CLIENT2.setPermissions(new SetPermissionsParams().withWorkspace("permspriv")
				.withNewPermission("w").withUsers(Arrays.asList(USERNOEMAIL))); //should work
		Map<String, String> expected = new HashMap<String, String>();
		expected.put(USER1, "a");
		expected.put(USER2, "a");
		expected.put(USERNOEMAIL, "w");
		Map<String, String> perms = CLIENT2.getPermissions(new WorkspaceIdentity()
			.withWorkspace("permspriv"));
		assertThat("Permissions set correctly", perms, is(expected));
	}
	
	@Test
	public void badIdent() throws Exception {
		try {
			CLIENT1.getPermissions(new WorkspaceIdentity());
			fail("got non-existant workspace");
		} catch (ServerException e) {
			assertThat("correct exception message", e.getLocalizedMessage(),
					is("Must provide one and only one of workspace or id"));
		}
	}
}
