// Modified in 2026 by the contributors of https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq
// (fork of aznamier/keycloak-event-listener-rabbitmq); see NOTICE and CHANGELOG.md.
package com.github.aznamier.keycloak.event.provider;

import static com.github.aznamier.keycloak.event.provider.ContractFixtures.map;
import static com.github.aznamier.keycloak.event.provider.ContractFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

/**
 * The virtual host of the listener's connection, against a RabbitMQ that has a virtual host named "". RabbitMQ lets
 * {@code rabbitmqctl add_vhost ""} create one, and a client that asks for the virtual host "" then connects to it, as
 * the test's own consumer does. The listener's user has permissions there too, as in a setup that relied on it. With
 * the vhost setting missing or empty, the listener connects to RabbitMQ's default virtual host, "/", all the same,
 * and its events go there, not to the virtual host named "".
 *
 * <p>Keycloak is not started, as in {@link PublishingIT}. The exchange is the default one, {@code amq.topic}, which
 * exists in every virtual host.
 */
@Testcontainers(disabledWithoutDocker = true)
class VirtualHostIT {

	/** The listener's broker user; the test's consumers use another user so that the connections can be told apart. */
	private static final String LISTENER_USER = "keycloak";
	private static final String LISTENER_PASSWORD = "virtual-host-test";
	private static final String TEST_USER = "test-consumer";
	private static final String TEST_PASSWORD = "test-consumer-password";
	private static final String NAMED_EMPTY = "";
	private static final String DEFAULT_VIRTUAL_HOST = "/";
	private static final String REALM_ID = "realm-id";
	private static final ObjectMapper JSON = new ObjectMapper();

	@Container
	private static final GenericContainer<?> RABBITMQ = new GenericContainer<>(
			DockerImageName.parse(System.getProperty("it.rabbitmq.image", "rabbitmq:4.3.6-alpine")))
			.withEnv("RABBITMQ_DEFAULT_USER", LISTENER_USER)
			.withEnv("RABBITMQ_DEFAULT_PASS", LISTENER_PASSWORD)
			.withExposedPorts(5672)
			.waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1)
					.withStartupTimeout(Duration.ofMinutes(2)));

	/** User names of the logins received, by the virtual host they arrived in. */
	private static final Map<String, BlockingQueue<String>> RECEIVED = Map.of(
			NAMED_EMPTY, new LinkedBlockingQueue<>(),
			DEFAULT_VIRTUAL_HOST, new LinkedBlockingQueue<>());
	private static final List<Connection> CONSUMERS = new ArrayList<>();

	private RabbitMqEventListenerProviderFactory factory;

	@BeforeAll
	static void consumeInBothVirtualHosts() throws Exception {
		rabbitmqctl("add_vhost", NAMED_EMPTY);
		rabbitmqctl("set_permissions", "-p", NAMED_EMPTY, LISTENER_USER, ".*", ".*", ".*");
		rabbitmqctl("add_user", TEST_USER, TEST_PASSWORD);
		for (String virtualHost : RECEIVED.keySet()) {
			rabbitmqctl("set_permissions", "-p", virtualHost, TEST_USER, ".*", ".*", ".*");
			consume(virtualHost);
		}
	}

	@AfterAll
	static void closeConsumers() throws Exception {
		for (Connection consumer : CONSUMERS) {
			consumer.close();
		}
	}

	@AfterEach
	void closeTheListener() throws InterruptedException {
		if (factory != null) {
			factory.close();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
			while (!listenerVirtualHosts().isEmpty()) {
				if (System.nanoTime() > deadline) {
					throw new AssertionError("the listener's connection is still open: " + listenerVirtualHosts());
				}
				Thread.sleep(250);
			}
		}
	}

	@Test
	void withoutTheSettingTheListenerConnectsToTheDefaultVirtualHost() throws Exception {
		assumeTrue(System.getenv("KK_TO_RMQ_VHOST") == null,
				"KK_TO_RMQ_VHOST is set in the environment of this test run");

		factory = listener(map());
		login("without-setting");

		assertThat(virtualHostOfLogin("without-setting")).as("virtual host the login arrived in")
				.isEqualTo(DEFAULT_VIRTUAL_HOST);
		assertThat(listenerVirtualHosts()).as("virtual hosts of the listener's connections")
				.containsExactly(DEFAULT_VIRTUAL_HOST);
	}

	@Test
	void anEmptySettingDoesNotReachTheVirtualHostNamedEmpty() throws Exception {
		factory = listener(map("vhost", ""));
		login("empty-setting");

		assertThat(virtualHostOfLogin("empty-setting")).as("virtual host the login arrived in")
				.isEqualTo(DEFAULT_VIRTUAL_HOST);
		assertThat(listenerVirtualHosts()).as("virtual hosts of the listener's connections")
				.containsExactly(DEFAULT_VIRTUAL_HOST);
	}

	// --- helpers ------------------------------------------------------------------------------------

	/** A queue in the virtual host that receives every event published to {@code amq.topic} there. */
	private static void consume(String virtualHost) throws Exception {
		ConnectionFactory cf = new ConnectionFactory();
		cf.setHost(RABBITMQ.getHost());
		cf.setPort(RABBITMQ.getMappedPort(5672));
		cf.setUsername(TEST_USER);
		cf.setPassword(TEST_PASSWORD);
		cf.setVirtualHost(virtualHost);
		Connection consumer = cf.newConnection();
		CONSUMERS.add(consumer);
		Channel channel = consumer.createChannel();
		String queue = channel.queueDeclare().getQueue();
		channel.queueBind(queue, "amq.topic", "KK.EVENT.#");
		channel.basicConsume(queue, true, new DefaultConsumer(channel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
					byte[] body) {
				try {
					String username = JSON.readTree(new String(body, StandardCharsets.UTF_8)).get("details")
							.get("username").asText();
					RECEIVED.get(virtualHost).add(username);
				} catch (Exception e) {
					RECEIVED.get(virtualHost).add("not a login: " + e);
				}
			}
		});
	}

	/** The listener with the broker's address and the listener's user, and the given settings. */
	private static RabbitMqEventListenerProviderFactory listener(Map<String, String> settings) {
		settings.put("url", RABBITMQ.getHost());
		settings.put("port", String.valueOf(RABBITMQ.getMappedPort(5672)));
		settings.put("username", LISTENER_USER);
		settings.put("password", LISTENER_PASSWORD);
		settings.put("use_tls", "false");
		RabbitMqEventListenerProviderFactory factory = new RabbitMqEventListenerProviderFactory();
		factory.init(scope(settings));
		return factory;
	}

	/** A Keycloak request that logs a user in. */
	private void login(String username) {
		ContractFixtures.SessionDouble session = ContractFixtures.session(map(REALM_ID, "acme"), "acme");
		EventListenerProvider provider = factory.create(session.session);
		Event event = new Event();
		event.setTime(System.currentTimeMillis());
		event.setType(EventType.LOGIN);
		event.setRealmId(REALM_ID);
		event.setClientId("web-app");
		event.setDetails(Map.of("username", username));
		provider.onEvent(event);
		session.completeTransaction();
	}

	/** Waits for the login of the user and returns the virtual host it arrived in. */
	private static String virtualHostOfLogin(String username) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			for (Map.Entry<String, BlockingQueue<String>> received : RECEIVED.entrySet()) {
				if (received.getValue().remove(username)) {
					return received.getKey();
				}
			}
			Thread.sleep(100);
		}
		throw new AssertionError("no login of " + username + " in any virtual host; received: " + RECEIVED);
	}

	/** The virtual hosts of the listener's open connections. */
	private static List<String> listenerVirtualHosts() {
		try {
			List<String> virtualHosts = new ArrayList<>();
			String connections = rabbitmqctl("list_connections", "-q", "--no-table-headers", "user", "vhost");
			for (String line : connections.split("\n")) {
				String[] columns = line.split("\t", -1);
				if (columns.length == 2 && columns[0].trim().equals(LISTENER_USER)) {
					virtualHosts.add(columns[1].trim());
				}
			}
			return virtualHosts;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String rabbitmqctl(String... args) throws Exception {
		String[] command = new String[args.length + 1];
		command[0] = "rabbitmqctl";
		System.arraycopy(args, 0, command, 1, args.length);
		ExecResult result = RABBITMQ.execInContainer(command);
		assertThat(result.getExitCode()).as(String.join(" ", command) + ": " + result.getStderr()).isZero();
		return result.getStdout();
	}
}
