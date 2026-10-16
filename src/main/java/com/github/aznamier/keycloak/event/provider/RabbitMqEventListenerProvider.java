// Modified in 2026 by the contributors of https://github.com/efim0v/keycloak-event-listener-rabbitmq
// (fork of aznamier/keycloak-event-listener-rabbitmq); see NOTICE and CHANGELOG.md.
package com.github.aznamier.keycloak.event.provider;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerTransaction;
import org.keycloak.events.admin.AdminEvent;
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

	private final EventListenerTransaction tx = new EventListenerTransaction(this::publishAdminEvent, this::publishEvent);

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
		session.getTransactionManager().enlistAfterCompletion(tx);
	}

	@Override
	public void close() {

	}

	@Override
	public void onEvent(Event event) {
		tx.addEvent(event.clone());
	}

	@Override
	public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
		tx.addAdminEvent(adminEvent, includeRepresentation);
	}
	
	private void publishEvent(Event event) {
		EventClientNotificationMqMsg msg = EventClientNotificationMqMsg.create(event);
		String routingKey = RabbitMqConfig.calculateRoutingKey(event, session);
		String messageString = RabbitMqConfig.writeAsJson(msg, true);
		
		BasicProperties msgProps = RabbitMqEventListenerProvider.getMessageProps(EventClientNotificationMqMsg.class.getName());
		this.publishNotification(messageString, msgProps, routingKey);
	}
	
	private void publishAdminEvent(AdminEvent adminEvent, boolean includeRepresentation) {
		EventAdminNotificationMqMsg msg = EventAdminNotificationMqMsg.create(adminEvent);
		String routingKey = RabbitMqConfig.calculateRoutingKey(adminEvent, session);
		String messageString = RabbitMqConfig.writeAsJson(msg, true);

		BasicProperties msgProps = RabbitMqEventListenerProvider.getMessageProps(EventAdminNotificationMqMsg.class.getName());
		this.publishNotification(messageString,msgProps, routingKey);
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

}