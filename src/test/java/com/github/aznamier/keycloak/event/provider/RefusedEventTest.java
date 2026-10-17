// Modified in 2026 by the contributors of https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq
// (fork of aznamier/keycloak-event-listener-rabbitmq); see NOTICE and CHANGELOG.md.
package com.github.aznamier.keycloak.event.provider;

import static com.github.aznamier.keycloak.event.provider.ContractFixtures.map;
import static com.github.aznamier.keycloak.event.provider.ContractFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;

/**
 * RabbitMQ closes the channel when it refuses a published message, for example because the exchange does not exist.
 * The publish itself does not fail, so the lost event has to be logged when the channel closes.
 */
class RefusedEventTest {

	/** Parent of the listener's loggers; held here because java.util.logging keeps loggers only weakly. */
	private static final Logger LISTENER_LOG = Logger.getLogger(RabbitMqEventListenerProvider.class.getPackageName());

	private final List<String> warnings = new CopyOnWriteArrayList<>();
	private final Handler capture = new Handler() {
		@Override
		public void publish(LogRecord record) {
			if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
				String message = record.getMessage();
				if (record.getParameters() != null && record.getParameters().length > 0) {
					message = String.format(message, record.getParameters());
				}
				warnings.add(message);
			}
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}
	};

	private final Channel channel = mock(Channel.class);

	@BeforeEach
	void captureLog() {
		LISTENER_LOG.addHandler(capture);
	}

	@AfterEach
	void stopCapturing() {
		LISTENER_LOG.removeHandler(capture);
	}

	@Test
	void aChannelThatRabbitMqClosesIsLoggedWithRabbitMqsReason() throws Exception {
		ShutdownListener listener = listenerOnTheChannel();

		AMQP.Channel.Close close = new AMQP.Channel.Close.Builder().replyCode(404)
				.replyText("NOT_FOUND - no exchange 'keycloak.events' in vhost '/'").classId(60).methodId(40).build();
		listener.shutdownCompleted(new ShutdownSignalException(false, false, close, channel));

		assertThat(warnings).containsExactly("keycloak-to-rabbitmq: RabbitMQ refused an event and closed the channel: "
				+ "NOT_FOUND - no exchange 'keycloak.events' in vhost '/'");
	}

	@Test
	void theEndOfTheConnectionIsNotTakenForARefusedEvent() throws Exception {
		ShutdownListener listener = listenerOnTheChannel();

		// the connection is lost, for example because the broker restarts
		listener.shutdownCompleted(new ShutdownSignalException(true, false, null, channel));
		// the listener closes the connection when Keycloak stops
		listener.shutdownCompleted(new ShutdownSignalException(true, true, null, channel));

		assertThat(warnings).isEmpty();
	}

	/** The shutdown listener that the factory puts on the channel it opens for a session. */
	private ShutdownListener listenerOnTheChannel() throws Exception {
		Connection connection = mock(Connection.class);
		when(connection.isOpen()).thenReturn(true);
		when(connection.createChannel()).thenReturn(channel);
		ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
		when(connectionFactory.newConnection()).thenReturn(connection);
		RabbitMqEventListenerProviderFactory factory = new RabbitMqEventListenerProviderFactory() {
			@Override
			ConnectionFactory newConnectionFactory() {
				return connectionFactory;
			}
		};
		factory.init(scope(map("use_tls", "false")));
		factory.create(ContractFixtures.session(map(), "acme").session);

		ArgumentCaptor<ShutdownListener> listener = ArgumentCaptor.forClass(ShutdownListener.class);
		verify(channel).addShutdownListener(listener.capture());
		return listener.getValue();
	}
}
