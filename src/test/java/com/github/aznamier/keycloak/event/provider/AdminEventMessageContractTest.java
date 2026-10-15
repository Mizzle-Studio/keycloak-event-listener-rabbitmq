package com.github.aznamier.keycloak.event.provider;

import static com.github.aznamier.keycloak.event.provider.ContractFixtures.json;
import static com.github.aznamier.keycloak.event.provider.ContractFixtures.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.AuthDetails;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;

/**
 * Contract of the message published for an admin event.
 *
 * <p>Consumers parse these messages, so field names, value shapes, the routing key and the AMQP
 * properties must stay exactly as they are.
 */
class AdminEventMessageContractTest {

	private static final String REALM_ID = "4f0b0c8e-2f63-4a52-9c3e-0d2b7e1f9a10";
	private static final String USER_ID = "8d3c5a9e-1b2f-4c3d-8e4f-5a6b7c8d9e0f";
	private static final String TYPE_ID = "com.github.aznamier.keycloak.event.provider.EventAdminNotificationMqMsg";

	private final Channel channel = mock(Channel.class);
	// The admin routing key takes the realm name from the session context, not from the event.
	private final ContractFixtures.SessionDouble session = ContractFixtures.session(map(REALM_ID, "unused"), "acme");
	private final RabbitMqEventListenerProvider provider = new RabbitMqEventListenerProvider(channel, session.session,
			ContractFixtures.config("events.exchange"));

	private static AdminEvent userUpdate() {
		AuthDetails auth = new AuthDetails();
		auth.setRealmId("master-realm-id");
		auth.setClientId("admin-cli-client-id");
		auth.setUserId("admin-user-id");
		auth.setIpAddress("198.51.100.4");

		AdminEvent event = new AdminEvent();
		event.setId("0f1e2d3c-4b5a-4968-8776-655443322110");
		event.setTime(1700000000456L);
		event.setRealmId(REALM_ID);
		event.setAuthDetails(auth);
		event.setResourceType(ResourceType.USER);
		event.setOperationType(OperationType.UPDATE);
		event.setResourcePath("users/" + USER_ID);
		event.setRepresentation("{\"username\":\"jdoe\",\"enabled\":true}");
		return event;
	}

	private static ObjectNode expectedUserUpdateBody() {
		return (ObjectNode) json("{"
				+ "\"@class\":\"" + TYPE_ID + "\","
				+ "\"time\":1700000000456,"
				+ "\"realmId\":\"" + REALM_ID + "\","
				+ "\"authDetails\":{"
				+ "\"realmId\":\"master-realm-id\","
				+ "\"clientId\":\"admin-cli-client-id\","
				+ "\"userId\":\"admin-user-id\","
				+ "\"ipAddress\":\"198.51.100.4\"},"
				+ "\"resourceType\":\"USER\","
				+ "\"operationType\":\"UPDATE\","
				+ "\"resourcePath\":\"users/" + USER_ID + "\","
				+ "\"representation\":\"{\\\"username\\\":\\\"jdoe\\\",\\\"enabled\\\":true}\","
				+ "\"resourceTypeAsString\":\"USER\""
				+ "}");
	}

	@Test
	void publishesNothingUntilTheKeycloakTransactionCompletes() {
		provider.onEvent(userUpdate(), true);

		verifyNoInteractions(channel);
	}

	@Test
	void successfulAdminEventMessage() throws Exception {
		provider.onEvent(userUpdate(), true);
		session.completeTransaction();

		PublishedMessage message = PublishedMessage.single(channel);
		verifyNoMoreInteractions(channel); // the exchange is never declared: it must exist on the broker

		assertThat(message.exchange).isEqualTo("events.exchange");
		assertThat(message.routingKey).isEqualTo("KK.EVENT.ADMIN.acme.SUCCESS.USER.UPDATE");
		MessagePropertiesContract.assertProperties(message.properties, TYPE_ID);
		// JsonNode equality compares names, values and value types; the representation stays a string.
		assertThat(message.bodyJson()).isEqualTo(expectedUserUpdateBody());
		assertThat(message.bodyText()).startsWith("{\n  \"@class\" : \"" + TYPE_ID + "\",\n");
	}

	@Test
	void representationIsPublishedAsKeycloakSuppliedIt() throws Exception {
		// The include-representation flag is not interpreted by the listener: Keycloak fills the
		// representation only when the realm asks for it, and the listener forwards what it got.
		provider.onEvent(userUpdate(), false);
		session.completeTransaction();

		assertThat(PublishedMessage.single(channel).bodyJson()).isEqualTo(expectedUserUpdateBody());
	}

	@Test
	void eventIdIsNotPartOfTheMessage() throws Exception {
		provider.onEvent(userUpdate(), true);
		session.completeTransaction();

		assertThat(PublishedMessage.single(channel).bodyJson().has("id")).isFalse();
	}

	@Test
	void userDeletionByAnAdministrator() throws Exception {
		AdminEvent event = userUpdate();
		event.setOperationType(OperationType.DELETE);
		event.setRepresentation(null);

		provider.onEvent(event, false);
		session.completeTransaction();

		PublishedMessage message = PublishedMessage.single(channel);
		assertThat(message.routingKey).isEqualTo("KK.EVENT.ADMIN.acme.SUCCESS.USER.DELETE");
		ObjectNode expected = expectedUserUpdateBody();
		expected.put("operationType", "DELETE");
		expected.remove("representation");
		assertThat(message.bodyJson()).isEqualTo(expected);
	}

	@Test
	void failedAdminEventMessage() throws Exception {
		AdminEvent event = userUpdate();
		event.setError("unknown_error");

		provider.onEvent(event, true);
		session.completeTransaction();

		PublishedMessage message = PublishedMessage.single(channel);
		assertThat(message.routingKey).isEqualTo("KK.EVENT.ADMIN.acme.ERROR.USER.UPDATE");
		JsonNode body = message.bodyJson();
		assertThat(body.get("error").asText()).isEqualTo("unknown_error");
	}

	@Test
	void resourceTypeOfAnotherKind() throws Exception {
		AdminEvent event = userUpdate();
		event.setResourceType(ResourceType.REALM_ROLE_MAPPING);
		event.setOperationType(OperationType.CREATE);
		event.setResourcePath("users/" + USER_ID + "/role-mappings/realm");

		provider.onEvent(event, true);
		session.completeTransaction();

		PublishedMessage message = PublishedMessage.single(channel);
		assertThat(message.routingKey).isEqualTo("KK.EVENT.ADMIN.acme.SUCCESS.REALM_ROLE_MAPPING.CREATE");
		assertThat(message.bodyJson().get("resourceType").asText()).isEqualTo("REALM_ROLE_MAPPING");
		assertThat(message.bodyJson().get("resourceTypeAsString").asText()).isEqualTo("REALM_ROLE_MAPPING");
	}
}
