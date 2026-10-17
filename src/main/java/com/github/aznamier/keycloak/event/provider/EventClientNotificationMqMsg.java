// Modified in 2026 by the contributors of https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq
// (fork of aznamier/keycloak-event-listener-rabbitmq); see NOTICE and CHANGELOG.md.
package com.github.aznamier.keycloak.event.provider;

import java.io.Serializable;

import org.keycloak.events.Event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;

/**
 * The published message. Only the properties listed below are written (plus the {@code @class} type
 * id), whatever else the Keycloak {@code Event} class of the running server exposes, so the message
 * format does not change when Keycloak adds fields or computed getters to its event classes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonIncludeProperties({
		"time", "type", "realmId", "clientId", "userId", "sessionId", "ipAddress", "error", "details"
})
@JsonTypeInfo(use = Id.CLASS)
public class EventClientNotificationMqMsg extends Event implements Serializable {

	private static final long serialVersionUID = -2192461924304841222L;
	
	public static EventClientNotificationMqMsg create(Event event) {
		EventClientNotificationMqMsg msg = new EventClientNotificationMqMsg();
		msg.setClientId(event.getClientId());
		msg.setDetails(event.getDetails());
		msg.setError(event.getError());
		msg.setIpAddress(event.getIpAddress());
		msg.setRealmId(event.getRealmId());
		msg.setSessionId(event.getSessionId());
		msg.setTime(event.getTime());
		msg.setType(event.getType());
		msg.setUserId(event.getUserId());

		return msg;
	}

	
}
