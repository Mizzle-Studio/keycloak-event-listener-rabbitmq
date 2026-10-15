package com.github.aznamier.keycloak.event.provider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.keycloak.Config;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test doubles shared by the message contract tests.
 *
 * <p>The provider talks to Keycloak only through a handful of calls: it enlists an
 * after-completion transaction, resolves the realm name of a user event through
 * {@code session.realms()} and the realm name of an admin event through
 * {@code session.getContext()}. The fixture mocks exactly those calls.
 */
final class ContractFixtures {

	static final ObjectMapper JSON = new ObjectMapper();

	private ContractFixtures() {
	}

	/** A Keycloak session double together with the transaction the provider enlists in it. */
	static final class SessionDouble {
		final KeycloakSession session;
		private final AtomicReference<KeycloakTransaction> enlisted;

		private SessionDouble(KeycloakSession session, AtomicReference<KeycloakTransaction> enlisted) {
			this.session = session;
			this.enlisted = enlisted;
		}

		/** Commits the after-completion transaction, which is when the provider publishes. */
		void completeTransaction() {
			KeycloakTransaction tx = enlisted.get();
			if (tx == null) {
				throw new AssertionError("the provider did not enlist an after-completion transaction");
			}
			tx.begin();
			tx.commit();
		}

		boolean hasEnlistedTransaction() {
			return enlisted.get() instanceof AbstractKeycloakTransaction;
		}
	}

	/**
	 * @param realmNamesById realm id to realm name, used for user events
	 * @param contextRealmName realm name of {@code session.getContext().getRealm()}, used for admin events
	 */
	static SessionDouble session(Map<String, String> realmNamesById, String contextRealmName) {
		KeycloakSession session = mock(KeycloakSession.class);

		KeycloakTransactionManager txManager = mock(KeycloakTransactionManager.class);
		AtomicReference<KeycloakTransaction> enlisted = new AtomicReference<>();
		doAnswer(invocation -> {
			enlisted.set(invocation.getArgument(0));
			return null;
		}).when(txManager).enlistAfterCompletion(any(KeycloakTransaction.class));
		when(session.getTransactionManager()).thenReturn(txManager);

		RealmProvider realms = mock(RealmProvider.class);
		when(realms.getRealm(anyString())).thenAnswer(invocation -> {
			String id = invocation.getArgument(0);
			String name = realmNamesById.get(id);
			return name == null ? null : realm(id, name);
		});
		when(session.realms()).thenReturn(realms);

		KeycloakContext context = mock(KeycloakContext.class);
		if (contextRealmName != null) {
			RealmModel contextRealm = realm("context-realm-id", contextRealmName);
			when(context.getRealm()).thenReturn(contextRealm);
		}
		when(session.getContext()).thenReturn(context);

		return new SessionDouble(session, enlisted);
	}

	static RealmModel realm(String id, String name) {
		RealmModel realm = mock(RealmModel.class);
		when(realm.getId()).thenReturn(id);
		when(realm.getName()).thenReturn(name);
		return realm;
	}

	/** A {@link Config.Scope} backed by a map; missing keys read as {@code null} like Keycloak's own scope. */
	static Config.Scope scope(Map<String, String> values) {
		Config.Scope scope = mock(Config.Scope.class);
		when(scope.get(anyString())).thenAnswer(invocation -> values.get(invocation.<String>getArgument(0)));
		return scope;
	}

	static Map<String, String> map(String... keyValues) {
		Map<String, String> map = new HashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put(keyValues[i], keyValues[i + 1]);
		}
		return map;
	}

	static RabbitMqConfig config(String exchange) {
		return RabbitMqConfig.createFromScope(scope(map("exchange", exchange)));
	}

	static JsonNode json(String text) {
		try {
			return JSON.readTree(text);
		} catch (Exception e) {
			throw new AssertionError("not JSON: " + text, e);
		}
	}
}
