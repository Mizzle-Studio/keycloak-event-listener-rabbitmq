package com.github.aznamier.keycloak.event.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.rabbitmq.client.AMQP;

/** The AMQP properties and headers every published message carries. */
final class MessagePropertiesContract {

	private MessagePropertiesContract() {
	}

	static void assertProperties(AMQP.BasicProperties props, String typeId) {
		assertThat(props.getAppId()).isEqualTo("Keycloak");
		assertThat(props.getContentType()).isEqualTo("application/json");
		assertThat(props.getContentEncoding()).isEqualTo("UTF-8");
		assertThat(props.getHeaders()).containsExactly(Map.entry("__TypeId__", typeId));

		// Everything else is left unset: transient delivery mode, no ids, no timestamp.
		assertThat(props.getDeliveryMode()).isNull();
		assertThat(props.getPriority()).isNull();
		assertThat(props.getCorrelationId()).isNull();
		assertThat(props.getReplyTo()).isNull();
		assertThat(props.getExpiration()).isNull();
		assertThat(props.getMessageId()).isNull();
		assertThat(props.getTimestamp()).isNull();
		assertThat(props.getType()).isNull();
		assertThat(props.getUserId()).isNull();
		assertThat(props.getClusterId()).isNull();
	}
}
