package us.kbase.workspace.test.kbase;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static us.kbase.common.test.TestCommon.set;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import us.kbase.auth.AuthToken;
import us.kbase.common.test.TestCommon;
import us.kbase.shock.client.BasicShockClient;
import us.kbase.shock.client.ShockACL;
import us.kbase.shock.client.ShockACLType;
import us.kbase.shock.client.ShockNode;
import us.kbase.shock.client.ShockNodeId;
import us.kbase.shock.client.ShockUserId;
import us.kbase.shock.client.exceptions.InvalidShockUrlException;
import us.kbase.shock.client.exceptions.ShockAuthorizationException;
import us.kbase.shock.client.exceptions.ShockHttpException;
import us.kbase.shock.client.exceptions.ShockNoNodeException;
import us.kbase.typedobj.idref.IdReferenceType;
import us.kbase.typedobj.idref.SimpleRemappedId;
import us.kbase.typedobj.idref.IdReferenceHandlerSet.IdReferenceException;
import us.kbase.typedobj.idref.IdReferenceHandlerSet.IdReferenceHandler;
import us.kbase.typedobj.idref.IdReferenceHandlerSet.IdReferenceHandlerException;
import us.kbase.typedobj.idref.IdReferenceHandlerSet.NoSuchIdException;
import us.kbase.typedobj.idref.IdReferencePermissionHandlerSet.IdReferencePermissionHandler;
import us.kbase.typedobj.idref.IdReferencePermissionHandlerSet.IdReferencePermissionHandlerException;
import us.kbase.workspace.kbase.ShockIdHandlerFactory;
import us.kbase.workspace.kbase.ShockIdHandlerFactory.ShockClientCloner;

public class ShockIdHandlerFactoryTest {
	
	@Test
	public void type() throws Exception {
		final BasicShockClient cli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		assertThat("incorrect type", new ShockIdHandlerFactory(cli, cloner).getIDType(),
				is(new IdReferenceType("shock")));
		assertThat("incorrect type", ShockIdHandlerFactory.TYPE, is(new IdReferenceType("shock")));
	}
	
	@Test
	public void constructFail() throws Exception {
		final BasicShockClient cli = mock(BasicShockClient.class);
		
		try {
			new ShockIdHandlerFactory(cli, null);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, new NullPointerException("cloner"));
		}
	}
	
	@Test
	public void addReadPermissionNoIDs() throws Exception {
		final BasicShockClient cli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		final IdReferencePermissionHandler h1 = new ShockIdHandlerFactory(cli, cloner)
				.createPermissionHandler();
		final IdReferencePermissionHandler h2 = new ShockIdHandlerFactory(cli, cloner)
				.createPermissionHandler("user");
		
		h1.addReadPermission(null);
		h2.addReadPermission(null);
		h1.addReadPermission(Collections.emptyList());
		h2.addReadPermission(Collections.emptyList());
		
		verifyZeroInteractions(cli);
		verifyZeroInteractions(cloner);
	}
	
	@Test
	public void addReadPermissionNullUser() throws Exception {
		final BasicShockClient cli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		final IdReferencePermissionHandler h = new ShockIdHandlerFactory(cli, cloner)
				.createPermissionHandler();
		final UUID id1 = UUID.randomUUID();
		final UUID id2 = UUID.randomUUID();
		
		h.addReadPermission(set(id1.toString(), id2.toString()));
		
		verify(cli).setPubliclyReadable(new ShockNodeId(id1.toString()), true);
		verify(cli).setPubliclyReadable(new ShockNodeId(id2.toString()), true);
		
		verifyNoMoreInteractions(cli);
		verifyZeroInteractions(cloner);
	}
	
	@Test
	public void addReadPermissionWithUser() throws Exception {
		final BasicShockClient cli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		final IdReferencePermissionHandler h = new ShockIdHandlerFactory(cli, cloner)
				.createPermissionHandler("user1");
		final UUID id1 = UUID.randomUUID();
		final UUID id2 = UUID.randomUUID();
		
		h.addReadPermission(set(id1.toString(), id2.toString()));
		
		verify(cli).addToNodeAcl(
				new ShockNodeId(id1.toString()), Arrays.asList("user1"), ShockACLType.READ);
		verify(cli).addToNodeAcl(
				new ShockNodeId(id2.toString()), Arrays.asList("user1"), ShockACLType.READ);
		
		verifyNoMoreInteractions(cli);
		verifyZeroInteractions(cloner);
	}
	
	@Test
	public void addReadPermissionFailNoClient() throws Exception {
		addReadPermissionFailWithAndWithoutUser(
				new ShockIdHandlerFactory(null, null), "foo", set("id"),
				new IdReferencePermissionHandlerException(
						"There is no connection configured for the Shock Service and " +
						"Shock IDs cannot be processed."));
	}
	
	@Test
	public void addReadPermissionFailBadID() throws Exception {
		final BasicShockClient cli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
	
		addReadPermissionFailWithAndWithoutUser(
				new ShockIdHandlerFactory(cli, cloner), "foo",
				set(UUID.randomUUID().toString(), "id"),
				new IdReferencePermissionHandlerException("Illegal shock ID: id"));
	}
	
	@Test
	public void addReadPermissionFailIOError() throws Exception {
		final BasicShockClient cli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		final UUID id = UUID.randomUUID();
		
		doThrow(new IOException("whoopsie"))
				.when(cli).setPubliclyReadable(new ShockNodeId(id.toString()), true);
		doThrow(new IOException("whoopsie"))
				.when(cli).addToNodeAcl(
						new ShockNodeId(id.toString()), Arrays.asList("foo"), ShockACLType.READ);
	
		addReadPermissionFailWithAndWithoutUser(
				new ShockIdHandlerFactory(cli, cloner), "foo",
				set(UUID.randomUUID().toString(), id.toString()),
				new IdReferencePermissionHandlerException(
						"There was an IO problem while attempting to set Shock ACLs: whoopsie"));
	}
	
	@Test
	public void addReadPermissionFailShockError() throws Exception {
		final BasicShockClient cli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		final UUID id = UUID.randomUUID();
		
		doThrow(new ShockHttpException(400, "darn heck"))
				.when(cli).setPubliclyReadable(new ShockNodeId(id.toString()), true);
		doThrow(new ShockHttpException(400, "darn heck"))
				.when(cli).addToNodeAcl(
						new ShockNodeId(id.toString()), Arrays.asList("foo"), ShockACLType.READ);
	
		addReadPermissionFailWithAndWithoutUser(
				new ShockIdHandlerFactory(cli, cloner), "foo",
				set(UUID.randomUUID().toString(), id.toString()),
				new IdReferencePermissionHandlerException(
						"Shock reported a problem while attempting to set Shock ACLs: darn heck"));
	}
	
	private void addReadPermissionFailWithAndWithoutUser(
			final ShockIdHandlerFactory fac,
			final String user,
			final Collection<String> ids,
			final Exception expected) {
		try {
			fac.createPermissionHandler().addReadPermission(ids);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, expected);
		}
		try {
			fac.createPermissionHandler(user).addReadPermission(ids);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, expected);
		}
	}
	
	@Test
	public void createHandlerFail() throws Exception {
		final BasicShockClient cli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		try {
			new ShockIdHandlerFactory(cli, cloner).createHandler(Long.class, null);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, new NullPointerException("userToken"));
		}
	}
	
	@Test
	public void processIDsGetType() throws Exception {
		final BasicShockClient cli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		final IdReferenceHandler<Long> h = new ShockIdHandlerFactory(cli, cloner)
				.createHandler(Long.class, new AuthToken("token", "user"));
		
		assertThat("incorrect type", h.getIdType(), is(new IdReferenceType("shock")));
	}
	
	@Test
	public void processIDsNoIDs() throws Exception {
		final BasicShockClient cli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		final IdReferenceHandler<Long> h = new ShockIdHandlerFactory(cli, cloner)
				.createHandler(Long.class, new AuthToken("token", "user"));
		
		h.processIds();
		
		verifyZeroInteractions(cli);
		verifyZeroInteractions(cloner);
	}
	
	@Test
	public void processIDs() throws Exception {
		/* For the id handler portion of the code, it's not really possible to test without
		 * going through the entire process flow, so the tests are going to be largish.
		 */
		final BasicShockClient adminCli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		final IdReferenceHandler<String> h = new ShockIdHandlerFactory(adminCli, cloner)
				.createHandler(String.class, new AuthToken("token", "user"));
		
		final String id_foo_1 = UUID.randomUUID().toString();
//		System.out.println("id_foo_1: " + id_foo_1);
		final String id_foo_2 = UUID.randomUUID().toString();
//		System.out.println("id_foo_2: " + id_foo_2);
		final String id_bar_1 = UUID.randomUUID().toString();
//		System.out.println("id_bar_1: " + id_bar_1);
		final String id_bar_2 = id_foo_2;
		
		assertThat("incorrect uniq", h.addId("foo", id_foo_1, null), is(true));
		assertThat("incorrect uniq", h.addId("foo", id_foo_2, null), is(true));
		assertThat("incorrect uniq", h.addId("foo", id_foo_2, null), is(false));
		assertThat("incorrect uniq", h.addId("bar", id_bar_1, null), is(true));
		assertThat("incorrect uniq", h.addId("bar", id_bar_2, null), is(true));
		
		final BasicShockClient cloned = mock(BasicShockClient.class);
		when(cloner.clone(adminCli)).thenReturn(cloned);
		
		when(adminCli.getToken()).thenReturn(new AuthToken("token", "admin"));

		setUpShockACLResponse(cloned, id_foo_1, "admin");
		setUpShockACLResponse(cloned, id_foo_2, "user");
		setUpShockACLResponse(cloned, id_bar_1, "notadmin");
		
		final String id_foo_2_new = UUID.randomUUID().toString();
		final String id_bar_1_new = UUID.randomUUID().toString();
		
		setUpShockCopyResponse(adminCli, id_foo_2, id_foo_2_new);
		setUpShockCopyResponse(adminCli, id_bar_1, id_bar_1_new);
		
		h.processIds();
		
		verify(cloned).updateToken(new AuthToken("token", "user"));
		// the same ID in 2 objects should only result in 1 call
		verify(cloned, times(1)).getACLs(new ShockNodeId(id_foo_1));
		verify(cloned, times(1)).getACLs(new ShockNodeId(id_foo_2));
		verify(cloned, times(1)).getACLs(new ShockNodeId(id_bar_1));
		verify(adminCli, times(1)).copyNode(new ShockNodeId(id_foo_2), true);
		verify(adminCli, times(1)).copyNode(new ShockNodeId(id_bar_1), true);
		
		assertThat("incorrect id", h.getRemappedId(id_foo_1), is(toSRD(id_foo_1)));
		assertThat("incorrect id", h.getRemappedId(id_foo_2), is(toSRD(id_foo_2_new)));
		assertThat("incorrect id", h.getRemappedId(id_bar_1), is(toSRD(id_bar_1_new)));
		assertThat("incorrect id", h.getRemappedId(id_bar_2), is(toSRD(id_foo_2_new)));
		
		assertThat("incorrect ids", h.getRemappedIds("foo"),
				is(set(toSRD(id_foo_1), toSRD(id_foo_2_new))));
		assertThat("incorrect ids", h.getRemappedIds("bar"),
				is(set(toSRD(id_bar_1_new), toSRD(id_foo_2_new))));
		assertThat("incorrect ids", h.getRemappedIds("baz"), is(set()));
	}

	private SimpleRemappedId toSRD(final String id) {
		return new SimpleRemappedId(id);
	}

	private void setUpShockCopyResponse(
			final BasicShockClient client,
			final String id,
			final String newId)
			throws Exception {
		final ShockNode node = mock(ShockNode.class);
		when(client.copyNode(new ShockNodeId(id), true)).thenReturn(node);
		when(node.getId()).thenReturn(new ShockNodeId(newId));
	}

	// ugh, the shock client needs to be easier to test with.
	private void setUpShockACLResponse(
			final BasicShockClient client,
			final String shockID,
			final String user)
			throws Exception {
		final ShockACL acl = mock(ShockACL.class);
		when(client.getACLs(new ShockNodeId(shockID))).thenReturn(acl);
		final ShockUserId shockUser = mock(ShockUserId.class);
		when(acl.getOwner()).thenReturn(shockUser);
		when(shockUser.getUsername()).thenReturn(user);
	}
	
	@Test
	public void addIdImplFailNoClient() throws Exception {
		final IdReferenceHandler<Long> h = new ShockIdHandlerFactory(null, null)
				.createHandler(Long.class, new AuthToken("token", "user"));
		
		addIdImplFail(h, 4L, "i", Arrays.asList("foo", "bar"), new IdReferenceException(
				"Found shock id i. There is no connection configured for the Shock Service " +
				"and so objects containing shock IDs cannot be processed.",
				new IdReferenceType("shock"), 4L, "i", null, null));
	}
	
	@Test
	public void addIdImplFailBadID() throws Exception {
		final BasicShockClient cli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		final IdReferenceHandler<String> h = new ShockIdHandlerFactory(cli, cloner)
				.createHandler(String.class, new AuthToken("token", "user"));
		
		addIdImplFail(h, "foo", "id", Arrays.asList("foo", "bat"), new IdReferenceException(
				"Illegal shock ID: id",
				new IdReferenceType("shock"), "foo", "id", null, null));
	}
	
	private <T> void addIdImplFail(
			final IdReferenceHandler<T> h,
			final T associatedObject,
			final String id,
			final List<String> attributes,
			final IdReferenceException expected)
			throws Exception {
		
		try {
			h.addId(associatedObject, id, attributes);
			fail("expected exception");
		} catch (IdReferenceException got) {
			TestCommon.assertExceptionCorrect(got, expected);
			assertThat("incorrect assobj", got.getAssociatedObject(),
					is(expected.getAssociatedObject()));
			assertThat("incorrect id", got.getId(), is(expected.getId()));
			assertThat("incorrect id ref", got.getIdReference(), is(expected.getIdReference()));
			assertThat("incorrect id attribs", got.getIdAttributes(),
					is(expected.getIdAttributes()));
			assertThat("incorrect id type", got.getIdType(), is(expected.getIdType()));
		}
	}
	
	@Test
	public void processIDsFailOnCloneIOException() throws Exception {
		processIDsFailOnClone(
				new IOException("crap"),
				new IdReferenceHandlerException("Error contacting Shock to validate IDs: crap",
						new IdReferenceType("shock"), null));
	}
	
	@Test
	public void processIDsFailOnCloneInvalidURLException() throws Exception {
		processIDsFailOnClone(
				new InvalidShockUrlException("poop"),
				new IdReferenceHandlerException("Error contacting Shock to validate IDs: poop",
						new IdReferenceType("shock"), null));
	}

	private void processIDsFailOnClone(
			final Exception thrown,
			final IdReferenceHandlerException expected)
			throws Exception {
		final BasicShockClient adminCli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		final IdReferenceHandler<String> h = new ShockIdHandlerFactory(adminCli, cloner)
				.createHandler(String.class, new AuthToken("token", "user"));
		
		final String id = UUID.randomUUID().toString();
		
		h.addId("foo", id, null);
		
		when(cloner.clone(adminCli)).thenThrow(thrown);
		
		processIDsFail(h, expected);
	}
	
	@Test
	public void processIDsFailOnGetACLsAuthException() throws Exception {
		processIDsFailOnGetACLs(
				new ShockAuthorizationException(400, "booga booga booga"),
				new IdReferenceException(
						"User someuser cannot read node 51b68baa-ef40-4be1-a072-03814d61280e",
						new IdReferenceType("shock"), "foo",
						"51b68baa-ef40-4be1-a072-03814d61280e", null, null));
	}
	
	@Test
	public void processIDsFailOnGetACLsNoNodeException() throws Exception {
		processIDsFailOnGetACLs(
				new ShockNoNodeException(400, "hey nonny nonny"),
				new IdReferenceException(
						"Node 51b68baa-ef40-4be1-a072-03814d61280e does not exist",
						new IdReferenceType("shock"), "foo",
						"51b68baa-ef40-4be1-a072-03814d61280e", null, null));
	}
	
	@Test
	public void processIDsFailOnGetACLsIOException() throws Exception {
		processIDsFailOnGetACLs(
				new IOException("rats"),
				new IdReferenceHandlerException(
						"There was an IO problem while attempting to contact Shock to " +
						"process IDs: rats",
						new IdReferenceType("shock"), null));
	}
	
	@Test
	public void processIDsFailOnGetACLsShockHTTPException() throws Exception {
		processIDsFailOnGetACLs(
				new ShockHttpException(400, "my pants are on fire"),
				new IdReferenceHandlerException(
						"Shock reported a problem while attempting to " +
						"process IDs: my pants are on fire",
						new IdReferenceType("shock"), null));
	}

	private void processIDsFailOnGetACLs(
			final Exception thrown,
			final IdReferenceHandlerException expected)
			throws Exception {
		final BasicShockClient adminCli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		final IdReferenceHandler<String> h = new ShockIdHandlerFactory(adminCli, cloner)
				.createHandler(String.class, new AuthToken("token", "someuser"));
		
		final String id = "51b68baa-ef40-4be1-a072-03814d61280e";
		
		h.addId("foo", id, null);
		
		final BasicShockClient cloned = mock(BasicShockClient.class);
		when(cloner.clone(adminCli)).thenReturn(cloned);
		
		when(adminCli.getToken()).thenReturn(new AuthToken("token", "admin"));

		when(cloned.getACLs(new ShockNodeId(id))).thenThrow(thrown);
		
		processIDsFail(h, expected);
	}
	
	@Test
	public void processIDsFailOnCopyIOException() throws Exception {
		processIDsFailOnCopy(
				new IOException("poopy doopy"),
				new IdReferenceHandlerException(
						"There was an IO problem while attempting to contact Shock to " +
						"copy nodes: poopy doopy",
						new IdReferenceType("shock"), null));
	}
	
	@Test
	public void processIDsFailOnCopyShockHTTPException() throws Exception {
		processIDsFailOnCopy(
				new ShockHttpException(400, "plectrum"),
				new IdReferenceHandlerException(
						"Shock reported a problem while attempting to copy nodes: plectrum",
						new IdReferenceType("shock"), null));
	}

	private void processIDsFailOnCopy(
			final Exception thrown,
			final IdReferenceHandlerException expected)
			throws Exception {
		final BasicShockClient adminCli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		final IdReferenceHandler<String> h = new ShockIdHandlerFactory(adminCli, cloner)
				.createHandler(String.class, new AuthToken("token", "someuser"));
		
		final String id = "51b68baa-ef40-4be1-a072-03814d61280e";
		
		h.addId("foo", id, null);
		
		final BasicShockClient cloned = mock(BasicShockClient.class);
		when(cloner.clone(adminCli)).thenReturn(cloned);
		
		when(adminCli.getToken()).thenReturn(new AuthToken("token", "admin"));
		
		setUpShockACLResponse(cloned, id, "someuser");

		when(adminCli.copyNode(new ShockNodeId(id), true)).thenThrow(thrown);
		
		processIDsFail(h, expected);
	}
	
	private <T> void processIDsFail(
			final IdReferenceHandler<T> h,
			final IdReferenceHandlerException expected)
			throws Exception {
		try {
			h.processIds();
			fail("expected exception");
		} catch (IdReferenceHandlerException got) {
			TestCommon.assertExceptionCorrect(got, expected);
			assertThat("incorrect id type", got.getIdType(), is(expected.getIdType()));
			if (expected instanceof IdReferenceException) {
				final IdReferenceException got2 = (IdReferenceException) got;
				final IdReferenceException expected2 = (IdReferenceException) expected;
				assertThat("incorrect assobj", got2.getAssociatedObject(),
						is(expected2.getAssociatedObject()));
				assertThat("incorrect id", got2.getId(), is(expected2.getId()));
				assertThat("incorrect id ref", got2.getIdReference(),
						is(expected2.getIdReference()));
				assertThat("incorrect id attribs", got2.getIdAttributes(),
						is(expected2.getIdAttributes()));
				
			}
		}
	}
	
	@Test
	public void getRemappedIDFail() throws Exception {
		final BasicShockClient adminCli = mock(BasicShockClient.class);
		final ShockClientCloner cloner = mock(ShockClientCloner.class);
		
		final IdReferenceHandler<String> h = new ShockIdHandlerFactory(adminCli, cloner)
				.createHandler(String.class, new AuthToken("token", "user"));
		
		final String id = UUID.randomUUID().toString();
		
		h.addId("foo", id, null);
		
		final BasicShockClient cloned = mock(BasicShockClient.class);
		when(cloner.clone(adminCli)).thenReturn(cloned);
		
		when(adminCli.getToken()).thenReturn(new AuthToken("token", "admin"));

		setUpShockACLResponse(cloned, id, "admin");
		
		h.processIds();
		
		assertThat("incorrect id", h.getRemappedId(id), is(toSRD(id)));
		
		final String badID = UUID.randomUUID().toString();
		try {
			h.getRemappedId(badID);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, new NoSuchIdException(
					"No such ID contained in this mapper: " + badID));
		}
	}

}