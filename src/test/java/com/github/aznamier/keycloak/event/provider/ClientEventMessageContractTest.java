package com.github.aznamier.keycloak.event.provider;

import static com.github.aznamier.keycloak.event.provider.ContractFixtures.json;
import static com.github.aznamier.keycloak.event.provider.ContractFixtures.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.Channel;

/**
 * Contract of the message published for a user (client) event.
 *
 * <p>Consumers parse these messages, so field names, value shapes, the routing key and the AMQP
 * properties must stay exactly as they are.
 */
class ClientEventMessageContractTest {

	private static final String REALM_ID = "4f0b0c8e-2f63-4a52-9c3e-0d2b7e1f9a10";
	private static final String TYPE_ID = "com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg";

	private final Channel channel = mock(Channel.class);
	private final ContractFixtures.SessionDouble session = ContractFixtures.session(map(REALM_ID, "acme"), "unused");
	private final RabbitMqEventListenerProvider provider = new RabbitMqEventListenerProvider(channel, session.session,
			ContractFixtures.config("events.exchange"));

	private static Event loginEvent() {
		Event event = new Event();
		event.setId("6c1f52f6-6a2c-4d4b-9a57-5c86f6f0a7d3");
		event.setTime(1700000000123L);
		event.setType(EventType.LOGIN);
		event.setRealmId(REALM_ID);
		event.setClientId("account-console");
		event.setUserId("8d3c5a9e-1b2f-4c3d-8e4f-5a6b7c8d9e0f");
		event.setSessionId("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
		event.setIpAddress("203.0.113.7");
		Map<String, String> details = new LinkedHashMap<>();
		details.put("auth_method", "openid-connect");
		details.put("username", "jdoe");
		details.put("display_name", "Zoë Ångström");
		event.setDetails(details);
		return event;
	}

	@Test
	void publishesNothingUntilTheKeycloakTransactionCompletes() {
		provider.onEvent(loginEvent());

		verifyNoInteractions(channel);
		assertThat(session.hasEnlistedTransaction()).isTrue();
	}

	@Test
	void successfulEventMessage() throws Exception {
		provider.onEvent(loginEvent());
		session.completeTransaction();

		PublishedMessage message = PublishedMessage.single(channel);
		verifyNoMoreInteractions(channel); // the exchange is never declared: it must exist on the broker

		assertThat(message.exchange).isEqualTo("events.exchange");
		assertThat(message.routingKey).isEqualTo("KK.EVENT.CLIENT.acme.SUCCESS.account-console.LOGIN");
		MessagePropertiesContract.assertProperties(message.properties, TYPE_ID);

		JsonNode expected = json("{"
				+ "\"@class\":\"" + TYPE_ID + "\","
				+ "\"time\":1700000000123,"
				+ "\"type\":\"LOGIN\","
				+ "\"realmId\":\"" + REALM_ID + "\","
				+ "\"clientId\":\"account-console\","
				+ "\"userId\":\"8d3c5a9e-1b2f-4c3d-8e4f-5a6b7c8d9e0f\","
				+ "\"sessionId\":\"1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d\","
				+ "\"ipAddress\":\"203.0.113.7\","
				+ "\"details\":{\"auth_method\":\"openid-connect\",\"username\":\"jdoe\",\"display_name\":\"Zoë Ångström\"}"
				+ "}");
		// JsonNode equality compares names, values and value types (time is an integral number).
		assertThat(message.bodyJson()).isEqualTo(expected);
		assertThat(message.bodyJson().get("time").isIntegralNumber()).isTrue();
	}

	@Test
	void eventIdIsNotPartOfTheMessage() throws Exception {
		provider.onEvent(loginEvent());
		session.completeTransaction();

		assertThat(PublishedMessage.single(channel).bodyJson().has("id")).isFalse();
	}

	@Test
	void bodyIsPrettyPrintedUtf8JsonWithTheTypeFirst() throws Exception {
		provider.onEvent(loginEvent());
		session.completeTransaction();

		PublishedMessage message = PublishedMessage.single(channel);
		assertThat(message.bodyText()).startsWith("{\n  \"@class\" : \"" + TYPE_ID + "\",\n");
		assertThat(message.bodyText()).contains("Zoë Ångström");
	}

	@Test
	void failedEventMessage() throws Exception {
		Event event = loginEvent();
		event.setType(EventType.LOGIN_ERROR);
		event.setError("invalid_user_credentials");
		event.setUserId(null);
		event.setSessionId(null);

		provider.onEvent(event);
		session.completeTransaction();

		PublishedMessage message = PublishedMessage.single(channel);
		assertThat(message.routingKey).isEqualTo("KK.EVENT.CLIENT.acme.ERROR.account-console.LOGIN_ERROR");
		JsonNode body = message.bodyJson();
		assertThat(body.get("error").asText()).isEqualTo("invalid_user_credentials");
		assertThat(body.get("type").asText()).isEqualTo("LOGIN_ERROR");
		// absent values are omitted rather than written as null
		assertThat(body.has("userId")).isFalse();
		assertThat(body.has("sessionId")).isFalse();
	}

	@Test
	void eventsAreSentInOrderAfterCompletion() throws Exception {
		Event first = loginEvent();
		Event second = loginEvent();
		second.setType(EventType.LOGOUT);

		provider.onEvent(first);
		provider.onEvent(second);
		session.completeTransaction();

		assertThat(PublishedMessage.capturedFrom(channel)).extracting(m -> m.routingKey).containsExactly(
				"KK.EVENT.CLIENT.acme.SUCCESS.account-console.LOGIN",
				"KK.EVENT.CLIENT.acme.SUCCESS.account-console.LOGOUT");
	}

	@Test
	void eventIsCopiedWhenReceived() throws Exception {
		Event event = loginEvent();
		provider.onEvent(event);
		event.setType(EventType.LOGOUT); // a later change by the caller does not leak into the message
		session.completeTransaction();

		assertThat(PublishedMessage.single(channel).routingKey).endsWith(".LOGIN");
	}
}
