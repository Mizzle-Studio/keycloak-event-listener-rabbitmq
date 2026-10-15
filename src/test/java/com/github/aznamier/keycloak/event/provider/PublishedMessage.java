package com.github.aznamier.keycloak.event.provider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;

/** One {@code basicPublish} call captured from a mocked channel. */
final class PublishedMessage {

	final String exchange;
	final String routingKey;
	final AMQP.BasicProperties properties;
	final byte[] body;

	private PublishedMessage(String exchange, String routingKey, AMQP.BasicProperties properties, byte[] body) {
		this.exchange = exchange;
		this.routingKey = routingKey;
		this.properties = properties;
		this.body = body;
	}

	String bodyText() {
		return new String(body, StandardCharsets.UTF_8);
	}

	JsonNode bodyJson() {
		return ContractFixtures.json(bodyText());
	}

	static List<PublishedMessage> capturedFrom(Channel channel) throws IOException {
		ArgumentCaptor<String> exchange = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> routingKey = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<AMQP.BasicProperties> props = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
		ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
		verify(channel, atLeast(0)).basicPublish(exchange.capture(), routingKey.capture(), props.capture(), body.capture());
		List<PublishedMessage> messages = new ArrayList<>();
		for (int i = 0; i < exchange.getAllValues().size(); i++) {
			messages.add(new PublishedMessage(exchange.getAllValues().get(i), routingKey.getAllValues().get(i),
					props.getAllValues().get(i), body.getAllValues().get(i)));
		}
		return messages;
	}

	static PublishedMessage single(Channel channel) throws IOException {
		List<PublishedMessage> messages = capturedFrom(channel);
		if (messages.size() != 1) {
			throw new AssertionError("expected exactly one published message, got " + messages.size());
		}
		verify(channel).basicPublish(anyString(), anyString(), any(AMQP.BasicProperties.class), any(byte[].class));
		return messages.get(0);
	}
}
