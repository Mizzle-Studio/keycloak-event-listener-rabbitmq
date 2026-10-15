package com.github.aznamier.keycloak.event.provider;

import static com.github.aznamier.keycloak.event.provider.ContractFixtures.map;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;

/**
 * Routing keys:
 * <ul>
 * <li>user events: {@code KK.EVENT.CLIENT.<REALM>.<RESULT>.<CLIENT>.<EVENT_TYPE>}</li>
 * <li>admin events: {@code KK.EVENT.ADMIN.<REALM>.<RESULT>.<RESOURCE_TYPE>.<OPERATION>}</li>
 * </ul>
 * where {@code <REALM>} is the realm <em>name</em> (not its id).
 */
class RoutingKeyContractTest {

	private static final String REALM_ID = "4f0b0c8e-2f63-4a52-9c3e-0d2b7e1f9a10";

	private static Event event(String clientId, EventType type, String error) {
		Event event = new Event();
		event.setRealmId(REALM_ID);
		event.setClientId(clientId);
		event.setType(type);
		event.setError(error);
		return event;
	}

	private static String clientKey(String realmName, String clientId) {
		KeycloakSession session = ContractFixtures.session(map(REALM_ID, realmName), null).session;
		return RabbitMqConfig.calculateRoutingKey(event(clientId, EventType.LOGIN, null), session);
	}

	@Test
	void userEventUsesTheRealmNameNotTheRealmId() {
		assertThat(clientKey("acme", "web-app")).isEqualTo("KK.EVENT.CLIENT.acme.SUCCESS.web-app.LOGIN");
	}

	@Test
	void userEventResult() {
		KeycloakSession session = ContractFixtures.session(map(REALM_ID, "acme"), null).session;
		assertThat(RabbitMqConfig.calculateRoutingKey(event("web-app", EventType.CODE_TO_TOKEN_ERROR, "invalid_code"),
				session)).isEqualTo("KK.EVENT.CLIENT.acme.ERROR.web-app.CODE_TO_TOKEN_ERROR");
	}

	@ParameterizedTest(name = "realm \"{0}\", client \"{1}\" -> {2}")
	@CsvSource(delimiter = '|', value = {
			// dots inside the realm name and the client id are removed so they do not add key segments
			"my.realm      | my.client    | KK.EVENT.CLIENT.myrealm.SUCCESS.myclient.LOGIN",
			// spaces become underscores
			"My Realm      | My Client    | KK.EVENT.CLIENT.My_Realm.SUCCESS.My_Client.LOGIN",
			// characters outside [a-zA-Z0-9 _.*#-] are dropped; hyphens and underscores are kept
			"réalm!        | client/äpp   | KK.EVENT.CLIENT.ralm.SUCCESS.clientpp.LOGIN",
			"acme-prod_1   | web-app_2    | KK.EVENT.CLIENT.acme-prod_1.SUCCESS.web-app_2.LOGIN",
			// wildcard characters are not escaped
			"a*b           | c#d          | KK.EVENT.CLIENT.a*b.SUCCESS.c#d.LOGIN",
	})
	void userEventKeyNormalisation(String realmName, String clientId, String expected) {
		assertThat(clientKey(realmName.trim(), clientId.trim())).isEqualTo(expected);
	}

	@Test
	void userEventWithoutClient() {
		assertThat(clientKey("acme", null)).isEqualTo("KK.EVENT.CLIENT.acme.SUCCESS.null.LOGIN");
	}

	private static AdminEvent adminEvent(ResourceType type, OperationType operation, String error) {
		AdminEvent event = new AdminEvent();
		event.setRealmId(REALM_ID);
		event.setResourceType(type);
		event.setOperationType(operation);
		event.setError(error);
		return event;
	}

	@Test
	void adminEventUsesTheRealmNameOfTheSessionContext() {
		KeycloakSession session = ContractFixtures.session(map(REALM_ID, "event-realm"), "context-realm").session;
		assertThat(RabbitMqConfig.calculateRoutingKey(adminEvent(ResourceType.USER, OperationType.CREATE, null), session))
				.isEqualTo("KK.EVENT.ADMIN.context-realm.SUCCESS.USER.CREATE");
	}

	@Test
	void adminEventResultAndNormalisation() {
		KeycloakSession session = ContractFixtures.session(map(), "my.big realm").session;
		assertThat(RabbitMqConfig.calculateRoutingKey(adminEvent(ResourceType.CLIENT, OperationType.ACTION, "boom"), session))
				.isEqualTo("KK.EVENT.ADMIN.mybig_realm.ERROR.CLIENT.ACTION");
	}

	@Test
	void adminEventWithCustomResourceType() {
		KeycloakSession session = ContractFixtures.session(map(), "acme").session;
		AdminEvent event = adminEvent(ResourceType.USER, OperationType.UPDATE, null);
		event.setResourceTypeAsString("CUSTOM_RESOURCE");
		assertThat(RabbitMqConfig.calculateRoutingKey(event, session))
				.isEqualTo("KK.EVENT.ADMIN.acme.SUCCESS.CUSTOM_RESOURCE.UPDATE");
	}

	@Test
	void normalisationHelpers() {
		assertThat(RabbitMqConfig.ROUTING_KEY_PREFIX).isEqualTo("KK.EVENT");
		assertThat(RabbitMqConfig.removeDots("a.b.c")).isEqualTo("abc");
		assertThat(RabbitMqConfig.removeDots(null)).isNull();
		assertThat(RabbitMqConfig.normalizeKey("A b.C-d_e*#f!é")).isEqualTo("A_b.C-d_e*#f");
	}
}
