// Modified in 2026 by the contributors of https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq
// (fork of aznamier/keycloak-event-listener-rabbitmq); see NOTICE and CHANGELOG.md.
package com.github.aznamier.keycloak.event.provider;

import static com.github.aznamier.keycloak.event.provider.ContractFixtures.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.AuthDetails;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;

import com.rabbitmq.client.Channel;

/**
 * The provider publishes after Keycloak's transaction has completed. Keycloak takes an exception thrown there as a
 * failure of the request, although the request has succeeded, and answers HTTP 500
 * (aznamier/keycloak-event-listener-rabbitmq#58).
 *
 * <p>The session double behaves like Keycloak's realm cache for a realm that was changed during the request, for
 * example because Keycloak added a key to it: reading the realm works while the transaction is active and throws
 * {@code IllegalStateException} once it has completed.
 */
class AfterCompletionTest {

	private static final String REALM_ID = "4f0b0c8e-2f63-4a52-9c3e-0d2b7e1f9a10";
	/** Parent of the listener's loggers; held here because java.util.logging keeps loggers only weakly. */
	private static final Logger LISTENER_LOG = Logger.getLogger(RabbitMqEventListenerProvider.class.getPackageName());

	private final Channel channel = mock(Channel.class);
	private final List<String> errors = new CopyOnWriteArrayList<>();
	private final Handler capture = new Handler() {
		@Override
		public void publish(LogRecord record) {
			if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
				String message = record.getMessage();
				if (record.getParameters() != null && record.getParameters().length > 0) {
					message = String.format(message, record.getParameters());
				}
				errors.add(message);
			}
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}
	};

	@BeforeEach
	void captureLog() {
		LISTENER_LOG.addHandler(capture);
	}

	@AfterEach
	void stopCapturing() {
		LISTENER_LOG.removeHandler(capture);
	}

	@Test
	void aUserEventIsPublishedAlthoughTheRealmCannotBeReadAfterTheTransaction() throws Exception {
		KeycloakDouble keycloak = new KeycloakDouble("acme");
		// without the realm name, as code other than Keycloak's EventBuilder may send it
		provider(keycloak.session).onEvent(login(null));

		assertThatCode(keycloak::completeTransaction).doesNotThrowAnyException();
		assertThat(PublishedMessage.single(channel).routingKey)
				.isEqualTo("KK.EVENT.CLIENT.acme.SUCCESS.web-app.LOGIN");
	}

	@Test
	void aUserEventFromKeycloakIsPublishedUnderTheRealmNameItCarries() throws Exception {
		KeycloakDouble keycloak = new KeycloakDouble("acme");
		// Keycloak's EventBuilder sets the realm name along with the realm id
		provider(keycloak.session).onEvent(login("acme"));

		assertThatCode(keycloak::completeTransaction).doesNotThrowAnyException();
		assertThat(PublishedMessage.single(channel).routingKey)
				.isEqualTo("KK.EVENT.CLIENT.acme.SUCCESS.web-app.LOGIN");
		verify(keycloak.session, never()).realms();
	}

	@Test
	void anAdminEventIsPublishedAlthoughTheRealmCannotBeReadAfterTheTransaction() throws Exception {
		KeycloakDouble keycloak = new KeycloakDouble("acme");
		provider(keycloak.session).onEvent(userUpdate(), true);

		assertThatCode(keycloak::completeTransaction).doesNotThrowAnyException();
		assertThat(PublishedMessage.single(channel).routingKey).isEqualTo("KK.EVENT.ADMIN.acme.SUCCESS.USER.UPDATE");
	}

	@Test
	void anAdminEventIsStillPublishedUnderTheRealmOfTheSessionContext() throws Exception {
		// Keycloak assigns the deletion of a realm to the realm of the administrator; the session context has the
		// deleted realm.
		ContractFixtures.SessionDouble keycloak = ContractFixtures.session(map("master-realm-id", "master"),
				"short-lived");
		AdminEvent deletion = userUpdate();
		deletion.setRealmId("master-realm-id");
		deletion.setRealmName("master");
		deletion.setResourceType(ResourceType.REALM);
		deletion.setOperationType(OperationType.DELETE);
		deletion.setResourcePath("short-lived");
		provider(keycloak.session).onEvent(deletion, false);

		keycloak.completeTransaction();
		assertThat(PublishedMessage.single(channel).routingKey)
				.isEqualTo("KK.EVENT.ADMIN.short-lived.SUCCESS.REALM.DELETE");
	}

	@Test
	void anEventWithoutARoutingKeyIsLoggedAndNothingIsThrownIntoKeycloak() {
		KeycloakDouble keycloak = new KeycloakDouble("acme");
		keycloak.withoutRealmInTheContext();
		RabbitMqEventListenerProvider provider = provider(keycloak.session);
		Event unknownRealm = login(null);
		unknownRealm.setRealmId("no-such-realm");

		assertThatCode(() -> {
			provider.onEvent(unknownRealm);
			provider.onEvent(userUpdate(), true);
		}).doesNotThrowAnyException();
		assertThatCode(keycloak::completeTransaction).doesNotThrowAnyException();

		verifyNoInteractions(channel);
		assertThat(errors).anyMatch(line -> line.contains("no routing key for event LOGIN of realm no-such-realm"));
		assertThat(errors).anyMatch(line -> line.contains("no routing key for admin event UPDATE USER of realm "
				+ REALM_ID));
	}

	@Test
	void aMessageThatCannotBeBuiltIsLoggedAndTheOtherEventsArePublished() throws Exception {
		KeycloakDouble keycloak = new KeycloakDouble("acme");
		RabbitMqEventListenerProvider provider = provider(keycloak.session);
		// each kind once with a getter that throws when the message is built, and once as it should be
		provider.onEvent(new AdminEvent(userUpdate()) {
			@Override
			public String getRepresentation() {
				throw new IllegalStateException("representation not available");
			}
		}, true);
		provider.onEvent(userUpdate(), true);
		provider.onEvent(new Event() {
			{
				setType(EventType.LOGIN);
				setRealmId(REALM_ID);
				setClientId("broken-app");
			}

			@Override
			public Event clone() {
				return this;
			}

			@Override
			public Map<String, String> getDetails() {
				throw new IllegalStateException("details not available");
			}
		});
		provider.onEvent(login(null));

		assertThatCode(keycloak::completeTransaction).doesNotThrowAnyException();
		assertThat(PublishedMessage.capturedFrom(channel)).extracting(message -> message.routingKey).containsExactly(
				"KK.EVENT.ADMIN.acme.SUCCESS.USER.UPDATE",
				"KK.EVENT.CLIENT.acme.SUCCESS.web-app.LOGIN");
		assertThat(errors).anyMatch(line -> line.contains("KK.EVENT.ADMIN.acme.SUCCESS.USER.UPDATE"));
		assertThat(errors).anyMatch(line -> line.contains("KK.EVENT.CLIENT.acme.SUCCESS.broken-app.LOGIN"));
	}

	@Test
	void adminEventsAreStillPublishedBeforeUserEvents() throws Exception {
		ContractFixtures.SessionDouble keycloak = ContractFixtures.session(map(REALM_ID, "acme"), "acme");
		RabbitMqEventListenerProvider provider = provider(keycloak.session);
		provider.onEvent(login(null));
		provider.onEvent(userUpdate(), true);

		keycloak.completeTransaction();
		assertThat(PublishedMessage.capturedFrom(channel)).extracting(message -> message.routingKey).containsExactly(
				"KK.EVENT.ADMIN.acme.SUCCESS.USER.UPDATE",
				"KK.EVENT.CLIENT.acme.SUCCESS.web-app.LOGIN");
	}

	@Test
	void eventsOfATransactionThatIsRolledBackAreStillNotPublished() {
		KeycloakDouble keycloak = new KeycloakDouble("acme");
		RabbitMqEventListenerProvider provider = provider(keycloak.session);
		provider.onEvent(login(null));
		provider.onEvent(userUpdate(), true);

		keycloak.rollBack();
		verifyNoInteractions(channel);
	}

	// --- helpers ------------------------------------------------------------------------------------

	private RabbitMqEventListenerProvider provider(KeycloakSession session) {
		return new RabbitMqEventListenerProvider(channel, session, ContractFixtures.config("events.exchange"));
	}

	private static Event login(String realmName) {
		Event event = new Event();
		event.setTime(1700000000123L);
		event.setType(EventType.LOGIN);
		event.setRealmId(REALM_ID);
		event.setRealmName(realmName);
		event.setClientId("web-app");
		event.setDetails(Map.of("username", "jdoe"));
		return event;
	}

	private static AdminEvent userUpdate() {
		AuthDetails auth = new AuthDetails();
		auth.setRealmId("master-realm-id");
		auth.setClientId("admin-cli-client-id");
		auth.setUserId("admin-user-id");
		auth.setIpAddress("198.51.100.4");
		AdminEvent event = new AdminEvent();
		event.setTime(1700000000456L);
		event.setRealmId(REALM_ID);
		event.setAuthDetails(auth);
		event.setResourceType(ResourceType.USER);
		event.setOperationType(OperationType.UPDATE);
		event.setResourcePath("users/8d3c5a9e-1b2f-4c3d-8e4f-5a6b7c8d9e0f");
		event.setRepresentation("{\"username\":\"jdoe\"}");
		return event;
	}

	/**
	 * A Keycloak session with one realm, {@link #REALM_ID}, which it reads only while the transaction is active; it is
	 * also the realm of the session context.
	 */
	private static final class KeycloakDouble {
		final KeycloakSession session = mock(KeycloakSession.class);
		private final KeycloakContext context = mock(KeycloakContext.class);
		private final AtomicReference<KeycloakTransaction> afterCompletion = new AtomicReference<>();
		private volatile boolean transactionActive = true;

		KeycloakDouble(String realmName) {
			KeycloakTransactionManager manager = mock(KeycloakTransactionManager.class);
			doAnswer(invocation -> {
				afterCompletion.set(invocation.getArgument(0));
				return null;
			}).when(manager).enlistAfterCompletion(any(KeycloakTransaction.class));
			when(session.getTransactionManager()).thenReturn(manager);

			RealmModel realm = mock(RealmModel.class);
			when(realm.getId()).thenReturn(REALM_ID);
			when(realm.getName()).thenAnswer(invocation -> whileTheTransactionIsActive(realmName));
			RealmProvider realms = mock(RealmProvider.class);
			when(realms.getRealm(anyString())).thenAnswer(invocation ->
					whileTheTransactionIsActive(REALM_ID.equals(invocation.getArgument(0)) ? realm : null));
			when(session.realms()).thenReturn(realms);

			when(context.getRealm()).thenReturn(realm);
			when(session.getContext()).thenReturn(context);
		}

		void withoutRealmInTheContext() {
			when(context.getRealm()).thenReturn(null);
		}

		/** What Keycloak's realm cache throws when it has to read a realm after the transaction has completed. */
		private <T> T whileTheTransactionIsActive(T value) {
			if (!transactionActive) {
				throw new IllegalStateException("Cannot access delegate without a transaction");
			}
			return value;
		}

		/** Keycloak commits its transaction, then the after-completion transaction, in which the provider publishes. */
		void completeTransaction() {
			KeycloakTransaction tx = afterCompletion.get();
			tx.begin();
			transactionActive = false;
			tx.commit();
		}

		void rollBack() {
			KeycloakTransaction tx = afterCompletion.get();
			tx.begin();
			transactionActive = false;
			tx.rollback();
		}
	}
}
