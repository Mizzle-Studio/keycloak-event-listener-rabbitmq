// Modified in 2026 by the contributors of https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq
// (fork of aznamier/keycloak-event-listener-rabbitmq); see NOTICE and CHANGELOG.md.
package com.github.aznamier.keycloak.event.provider;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.AMQP.BasicProperties.Builder;
import com.rabbitmq.client.Channel;

public class RabbitMqEventListenerProvider implements EventListenerProvider {

	private static final Logger log = Logger.getLogger(RabbitMqEventListenerProvider.class);
	
	private final RabbitMqConfig cfg;
	private final Channel channel;
	private final PublishGate publishGate;

	private final KeycloakSession session;

	private final AfterCompletion afterCompletion = new AfterCompletion();

	/** A provider that is the only user of the channel. */
	public RabbitMqEventListenerProvider(Channel channel, KeycloakSession session, RabbitMqConfig cfg) {
		this(channel, new PublishGate(), session, cfg);
	}

	/** A provider that publishes on a channel shared with the other providers of the same gate. */
	RabbitMqEventListenerProvider(Channel channel, PublishGate publishGate, KeycloakSession session, RabbitMqConfig cfg) {
		this.cfg = cfg;
		this.channel = channel;
		this.publishGate = publishGate;
		this.session = session;
		session.getTransactionManager().enlistAfterCompletion(afterCompletion);
	}

	@Override
	public void close() {

	}

	/*
	 * Keycloak hands over an event while its transaction is active, and the listener publishes it once the transaction
	 * has completed. The routing key is worked out right away: after completion Keycloak's realm cache may refuse to
	 * read the realm, and its "IllegalStateException: Cannot access delegate without a transaction" failed the request
	 * (aznamier/keycloak-event-listener-rabbitmq#58).
	 */

	@Override
	public void onEvent(Event event) {
		Event copy = event.clone();
		String routingKey = routingKey(copy);
		if (routingKey != null) {
			afterCompletion.userEvents.add(() -> publishEvent(copy, routingKey));
		}
	}

	@Override
	public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
		String routingKey = routingKey(adminEvent);
		if (routingKey != null) {
			afterCompletion.adminEvents.add(() -> publishAdminEvent(adminEvent, routingKey));
		}
	}

	/** The routing key of a user event; {@code null} if there is none, which is logged. */
	private String routingKey(Event event) {
		try {
			// Keycloak's EventBuilder sets the realm name along with the realm id; other code may set only the id.
			return event.getRealmName() != null
					? RabbitMqConfig.calculateRoutingKey(event, event.getRealmName())
					: RabbitMqConfig.calculateRoutingKey(event, session);
		} catch (RuntimeException e) {
			log.errorf(e, "keycloak-to-rabbitmq ERROR sending message: no routing key for event %s of realm %s",
					event.getType(), event.getRealmId());
			return null;
		}
	}

	/** The routing key of an admin event; {@code null} if there is none, which is logged. */
	private String routingKey(AdminEvent adminEvent) {
		try {
			// The realm of the session context, as before, and not that of the event: when a realm is deleted, the
			// event has the realm of the administrator and the session context the deleted realm.
			return RabbitMqConfig.calculateRoutingKey(adminEvent, session);
		} catch (RuntimeException e) {
			log.errorf(e, "keycloak-to-rabbitmq ERROR sending message: no routing key for admin event %s %s of realm %s",
					adminEvent.getOperationType(), adminEvent.getResourceTypeAsString(), adminEvent.getRealmId());
			return null;
		}
	}

	private void publishEvent(Event event, String routingKey) {
		try {
			EventClientNotificationMqMsg msg = EventClientNotificationMqMsg.create(event);
			String messageString = RabbitMqConfig.writeAsJson(msg, true);

			BasicProperties msgProps = RabbitMqEventListenerProvider.getMessageProps(EventClientNotificationMqMsg.class.getName());
			this.publishNotification(messageString, msgProps, routingKey);
		} catch (RuntimeException e) {
			log.errorf(e, "keycloak-to-rabbitmq ERROR sending message: %s", routingKey);
		}
	}

	private void publishAdminEvent(AdminEvent adminEvent, String routingKey) {
		try {
			EventAdminNotificationMqMsg msg = EventAdminNotificationMqMsg.create(adminEvent);
			String messageString = RabbitMqConfig.writeAsJson(msg, true);

			BasicProperties msgProps = RabbitMqEventListenerProvider.getMessageProps(EventAdminNotificationMqMsg.class.getName());
			this.publishNotification(messageString, msgProps, routingKey);
		} catch (RuntimeException e) {
			log.errorf(e, "keycloak-to-rabbitmq ERROR sending message: %s", routingKey);
		}
	}
	
	private static BasicProperties getMessageProps(String className) {
		
		Map<String,Object> headers = new HashMap<>();
		headers.put("__TypeId__", className);
		
		Builder propsBuilder = new AMQP.BasicProperties.Builder()
				.appId("Keycloak")
				.headers(headers)
				.contentType("application/json")
				.contentEncoding("UTF-8");
		return propsBuilder.build();
	}

	private void publishNotification(String messageString, BasicProperties props, String routingKey) {
		if (channel == null) {
			log.errorf("keycloak-to-rabbitmq ERROR sending message: %s (not connected to RabbitMQ)", routingKey);
			return;
		}
		try {
			byte[] body = messageString.getBytes(StandardCharsets.UTF_8);
			if (publishGate.publish(channel, cfg.getExchange(), routingKey, props, body)) {
				log.tracef("keycloak-to-rabbitmq SUCCESS sending message: %s", routingKey);
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			log.errorf("keycloak-to-rabbitmq ERROR sending message: %s (interrupted)", routingKey);
		} catch (Exception ex) {
			log.errorf(ex, "keycloak-to-rabbitmq ERROR sending message: %s", routingKey);
		}
	}

	/**
	 * Publishes the events of the session once Keycloak's transaction has completed, admin events first as Keycloak's
	 * own EventListenerTransaction does, and drops them if the transaction is rolled back. An exception thrown here
	 * would reach Keycloak and fail a request that has succeeded; the publishing methods catch them.
	 */
	private static final class AfterCompletion extends AbstractKeycloakTransaction {
		private final List<Runnable> adminEvents = new ArrayList<>();
		private final List<Runnable> userEvents = new ArrayList<>();

		@Override
		protected void commitImpl() {
			adminEvents.forEach(Runnable::run);
			userEvents.forEach(Runnable::run);
		}

		@Override
		protected void rollbackImpl() {
			adminEvents.clear();
			userEvents.clear();
		}
	}

}