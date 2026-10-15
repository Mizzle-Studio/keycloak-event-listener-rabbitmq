package com.github.aznamier.keycloak.event.provider;

import static com.github.aznamier.keycloak.event.provider.ContractFixtures.map;
import static com.github.aznamier.keycloak.event.provider.ContractFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.events.EventType;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

/** Registration of the provider, broker connection settings and behaviour when the broker is unavailable. */
class ConnectionContractTest {

	private static final String REALM_ID = "realm-id";

	private static Event loginEvent() {
		Event event = new Event();
		event.setTime(1L);
		event.setType(EventType.LOGIN);
		event.setRealmId(REALM_ID);
		event.setClientId("web-app");
		return event;
	}

	private static ConnectionFactory connectionFactoryOf(RabbitMqEventListenerProviderFactory factory) throws Exception {
		Field field = RabbitMqEventListenerProviderFactory.class.getDeclaredField("connectionFactory");
		field.setAccessible(true);
		return (ConnectionFactory) field.get(factory);
	}

	private static void replaceConnectionFactory(RabbitMqEventListenerProviderFactory factory, ConnectionFactory replacement)
			throws Exception {
		Field field = RabbitMqEventListenerProviderFactory.class.getDeclaredField("connectionFactory");
		field.setAccessible(true);
		field.set(factory, replacement);
	}

	private static int closedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	@Test
	void providerIdIsTheEventListenerName() {
		assertThat(new RabbitMqEventListenerProviderFactory().getId()).isEqualTo("keycloak-to-rabbitmq");
	}

	@Test
	void factoryIsRegisteredAsAKeycloakEventListener() {
		ServiceLoader<EventListenerProviderFactory> loader = ServiceLoader.load(EventListenerProviderFactory.class);
		assertThat(StreamSupport.stream(loader.spliterator(), false).map(f -> f.getClass().getName())
				.collect(Collectors.toList()))
				.contains(RabbitMqEventListenerProviderFactory.class.getName());
	}

	@Test
	void connectionSettingsComeFromTheConfiguration() throws Exception {
		RabbitMqEventListenerProviderFactory factory = new RabbitMqEventListenerProviderFactory();
		factory.init(scope(map("url", "broker.example", "port", "5673", "username", "kc", "password", "pw", "vhost",
				"/kc")));

		ConnectionFactory cf = connectionFactoryOf(factory);
		assertThat(cf.getHost()).isEqualTo("broker.example");
		assertThat(cf.getPort()).isEqualTo(5673);
		assertThat(cf.getUsername()).isEqualTo("kc");
		assertThat(cf.getPassword()).isEqualTo("pw");
		assertThat(cf.getVirtualHost()).isEqualTo("/kc");
		assertThat(cf.isAutomaticRecoveryEnabled()).isTrue();
		assertThat(cf.isSSL()).isFalse();
	}

	@Test
	void tlsIsUsedWhenEnabled() throws Exception {
		RabbitMqEventListenerProviderFactory factory = new RabbitMqEventListenerProviderFactory();
		factory.init(scope(map("url", "broker.example", "use_tls", "true")));

		assertThat(connectionFactoryOf(factory).isSSL()).isTrue();
	}

	@Test
	void unreachableBrokerDoesNotBreakKeycloak() throws Exception {
		RabbitMqEventListenerProviderFactory factory = new RabbitMqEventListenerProviderFactory();
		factory.init(scope(map("url", "127.0.0.1", "port", String.valueOf(closedPort()))));

		ContractFixtures.SessionDouble session = ContractFixtures.session(map(REALM_ID, "acme"), "acme");
		EventListenerProvider provider = factory.create(session.session);
		assertThat(provider).isNotNull();

		// The event is dropped (and logged); nothing is thrown into Keycloak's request handling.
		provider.onEvent(loginEvent());
		assertThatCode(session::completeTransaction).doesNotThrowAnyException();
	}

	@Test
	void connectionIsRetriedForTheNextSessionAndThenReused() throws Exception {
		RabbitMqEventListenerProviderFactory factory = new RabbitMqEventListenerProviderFactory();
		factory.init(scope(map()));

		Channel channel = mock(Channel.class);
		when(channel.isOpen()).thenReturn(true);
		Connection connection = mock(Connection.class);
		when(connection.isOpen()).thenReturn(true);
		when(connection.createChannel()).thenReturn(channel);
		ConnectionFactory cf = mock(ConnectionFactory.class);
		when(cf.newConnection()).thenThrow(new ConnectException("Connection refused")).thenReturn(connection);
		replaceConnectionFactory(factory, cf);

		ContractFixtures.SessionDouble first = ContractFixtures.session(map(REALM_ID, "acme"), "acme");
		assertThat(factory.create(first.session)).isNotNull();

		ContractFixtures.SessionDouble second = ContractFixtures.session(map(REALM_ID, "acme"), "acme");
		EventListenerProvider provider = factory.create(second.session);
		provider.onEvent(loginEvent());
		second.completeTransaction();

		factory.create(ContractFixtures.session(map(), "acme").session);

		verify(cf, times(2)).newConnection();
		verify(connection, times(1)).createChannel();
		verify(channel).basicPublish(anyString(), anyString(), any(AMQP.BasicProperties.class), any(byte[].class));
	}

	@Test
	void closedChannelIsReplaced() throws Exception {
		RabbitMqEventListenerProviderFactory factory = new RabbitMqEventListenerProviderFactory();
		factory.init(scope(map()));

		Channel closed = mock(Channel.class);
		when(closed.isOpen()).thenReturn(false);
		Channel fresh = mock(Channel.class);
		when(fresh.isOpen()).thenReturn(true);
		Connection connection = mock(Connection.class);
		when(connection.isOpen()).thenReturn(true);
		when(connection.createChannel()).thenReturn(closed, fresh);
		ConnectionFactory cf = mock(ConnectionFactory.class);
		when(cf.newConnection()).thenReturn(connection);
		replaceConnectionFactory(factory, cf);

		factory.create(ContractFixtures.session(map(), "acme").session);
		factory.create(ContractFixtures.session(map(), "acme").session);

		verify(cf, times(1)).newConnection();
		verify(connection, times(2)).createChannel();
	}

	@Test
	void publishFailureIsNotPropagated() throws Exception {
		Channel channel = mock(Channel.class);
		doThrow(new IOException("channel closed")).when(channel).basicPublish(anyString(), anyString(),
				any(AMQP.BasicProperties.class), any(byte[].class));
		ContractFixtures.SessionDouble session = ContractFixtures.session(map(REALM_ID, "acme"), "acme");
		RabbitMqEventListenerProvider provider = new RabbitMqEventListenerProvider(channel, session.session,
				ContractFixtures.config("amq.topic"));

		provider.onEvent(loginEvent());
		assertThatCode(session::completeTransaction).doesNotThrowAnyException();
	}
}
