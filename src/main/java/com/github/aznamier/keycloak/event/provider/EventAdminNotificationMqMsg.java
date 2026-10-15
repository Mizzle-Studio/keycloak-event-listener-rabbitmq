// Modified in 2026 by the contributors of https://github.com/efim0v/keycloak-event-listener-rabbitmq
// (fork of aznamier/keycloak-event-listener-rabbitmq); see NOTICE and CHANGELOG.md.
package com.github.aznamier.keycloak.event.provider;

import java.io.Serializable;

import org.keycloak.events.admin.AdminEvent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;

/**
 * The published message. Only the properties listed below are written (plus the {@code @class} type
 * id), whatever else the Keycloak {@code AdminEvent} class of the running server exposes, so the message
 * format does not change when Keycloak adds fields or computed getters to its event classes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonIncludeProperties({
		"time", "realmId", "authDetails", "resourceType", "operationType", "resourcePath",
		"representation", "error", "resourceTypeAsString"
})
@JsonTypeInfo(use = Id.CLASS)
public class EventAdminNotificationMqMsg extends AdminEvent implements Serializable  {

	private static final long serialVersionUID = -7367949289101799624L;

	public static EventAdminNotificationMqMsg create(AdminEvent adminEvent) {
		EventAdminNotificationMqMsg msg = new EventAdminNotificationMqMsg();
		msg.setAuthDetails(adminEvent.getAuthDetails());
		msg.setError(adminEvent.getError());
		msg.setOperationType(adminEvent.getOperationType());
		msg.setRealmId(adminEvent.getRealmId());
		msg.setRepresentation(adminEvent.getRepresentation());
		msg.setResourcePath(adminEvent.getResourcePath());
		msg.setResourceType(adminEvent.getResourceType());
		msg.setResourceTypeAsString(adminEvent.getResourceTypeAsString());
		msg.setTime(adminEvent.getTime());
		return msg;
	}

	
}
