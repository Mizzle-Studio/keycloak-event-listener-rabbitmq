package com.github.aznamier.keycloak.event.provider;

import static com.github.aznamier.keycloak.event.provider.ContractFixtures.map;
import static com.github.aznamier.keycloak.event.provider.ContractFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.AuthDetails;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.SocketConfigurators;

/**
 * The listener against a real RabbitMQ: many requests publishing at once, a resource alarm, a connection
 * closed by the broker, a connection the network stops carrying, and shutdown.
 *
 * <p>Keycloak is not started: the test creates providers and completes their transactions on several threads,
 * as Keycloak does on its request threads, with the session doubles of the contract tests.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PublishingIT {

	/** The listener's broker user; the test's own connections use another user so that they can be told apart. */
	private static final String LISTENER_USER = "keycloak";
	private static final String LISTENER_PASSWORD = "publishing-test";
	private static final String TEST_USER = "test-consumer";
	private static final String TEST_PASSWORD = "test-consumer-password";
	private static final String EXCHANGE = "keycloak.events";
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

	/** Bodies of the messages received, by the user name in their details or the resource path. */
	private static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
	private static Connection consumer;

	/** Parent of the listener's loggers; held here because java.util.logging keeps loggers only weakly. */
	private static final Logger LISTENER_LOG = Logger.getLogger(RabbitMqEventListenerProvider.class.getPackageName());
	private static final List<String> LOG_LINES = new CopyOnWriteArrayList<>();
	private static final Handler LOG_CAPTURE = new Handler() {
		@Override
		public void publish(LogRecord record) {
			String message = record.getMessage();
			if (record.getParameters() != null && record.getParameters().length > 0) {
				message = String.format(message, record.getParameters());
			}
			LOG_LINES.add(message);
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}
	};

	private final ExecutorService requestThreads = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable);
		thread.setDaemon(true);
		return thread;
	});
	private final List<RabbitMqEventListenerProviderFactory> factories = new ArrayList<>();

	@BeforeAll
	static void consumeEverything() throws Exception {
		LISTENER_LOG.addHandler(LOG_CAPTURE);
		rabbitmqctl("add_user", TEST_USER, TEST_PASSWORD);
		rabbitmqctl("set_permissions", "-p", "/", TEST_USER, ".*", ".*", ".*");
		ConnectionFactory cf = new ConnectionFactory();
		cf.setHost(RABBITMQ.getHost());
		cf.setPort(RABBITMQ.getMappedPort(5672));
		cf.setUsername(TEST_USER);
		cf.setPassword(TEST_PASSWORD);
		consumer = cf.newConnection();
		Channel channel = consumer.createChannel();
		channel.exchangeDeclare(EXCHANGE, "topic", true);
		String queue = channel.queueDeclare().getQueue();
		channel.queueBind(queue, EXCHANGE, "KK.EVENT.#");
		channel.basicConsume(queue, true, new DefaultConsumer(channel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
					byte[] body) {
				RECEIVED.add(new String(body, StandardCharsets.UTF_8));
			}
		});
	}

	@AfterAll
	static void closeConsumer() throws Exception {
		LISTENER_LOG.removeHandler(LOG_CAPTURE);
		if (consumer != null) {
			consumer.close();
		}
	}

	@AfterEach
	void shutDown() {
		requestThreads.shutdownNow();
		factories.forEach(RabbitMqEventListenerProviderFactory::close);
	}

	@Test
	@Order(1)
	void everyMessageArrivesIntactWhenManyRequestsPublishAtOnce() throws Exception {
		RabbitMqEventListenerProviderFactory factory = listener();
		int threads = 16;
		int perThread = 100;
		// every 20th event is an admin event larger than frame_max (128 KiB): its body takes two frames
		String largeRepresentation = "x".repeat(150_000);

		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> requests = new ArrayList<>();
		Set<String> expected = new HashSet<>();
		for (int t = 0; t < threads; t++) {
			for (int i = 0; i < perThread; i++) {
				expected.add("u" + t + "-" + i);
			}
			int thread = t;
			requests.add(requestThreads.submit(() -> {
				start.await();
				for (int i = 0; i < perThread; i++) {
					String id = "u" + thread + "-" + i;
					if (i % 20 == 0) {
						adminUpdate(factory, id, largeRepresentation);
					} else {
						login(factory, id);
					}
				}
				return null;
			}));
		}
		start.countDown();
		for (Future<?> request : requests) {
			request.get(2, TimeUnit.MINUTES);
		}

		Set<String> received = new HashSet<>();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		while (received.size() < expected.size() && System.nanoTime() < deadline) {
			String body = RECEIVED.poll(1, TimeUnit.SECONDS);
			if (body != null) {
				assertThat(received.add(idOf(body))).as("received once: " + idOf(body)).isTrue();
			}
		}
		assertThat(received).isEqualTo(expected);
	}

	@Test
	@Order(2)
	void requestsGoOnWhileAResourceAlarmBlocksPublishing() throws Exception {
		RabbitMqEventListenerProviderFactory factory = listener();
		login(factory, "before-alarm");
		awaitMessage("before-alarm");

		try {
			// A disk alarm. RabbitMQ blocks a connection when it publishes: it reads that message, stops reading
			// from the connection until the alarm clears, and sends connection.blocked.
			rabbitmqctl("set_disk_free_limit", "10000000000000");
			awaitCondition("disk alarm", () -> !alarmsCheckPasses());
			login(factory, "first-during-alarm");
			awaitLogLine("RabbitMQ blocked the connection");

			// More than the socket buffers hold: written to the blocked connection, they would block the requests.
			String megabyte = "x".repeat(1_000_000);
			Future<?> admin = requestThreads.submit(() -> {
				for (int i = 0; i < 30; i++) {
					adminUpdate(factory, "during-alarm-" + i, megabyte);
				}
			});
			Future<?> user = requestThreads.submit(() -> login(factory, "during-alarm"));
			assertThatCode(() -> admin.get(10, TimeUnit.SECONDS)).as("admin request publishing 30 MB during the alarm")
					.doesNotThrowAnyException();
			assertThatCode(() -> user.get(10, TimeUnit.SECONDS)).as("login during the alarm")
					.doesNotThrowAnyException();
		} finally {
			rabbitmqctl("set_disk_free_limit", "50000000");
			awaitCondition("alarm cleared", PublishingIT::alarmsCheckPasses);
		}
		assertThat(LOG_LINES).anyMatch(line -> line.contains("RabbitMQ blocked the connection (low on disk)"));

		// RabbitMQ unblocks the connection; events are delivered again
		awaitDelivery(factory, "after-alarm");
	}

	@Test
	@Order(3)
	void aConnectionClosedByTheBrokerIsRecoveredAndNotDuplicated() throws Exception {
		awaitCondition("no listener connections left", () -> listenerConnections() == 0);
		RabbitMqEventListenerProviderFactory factory = listener();
		login(factory, "before-close");
		awaitMessage("before-close");
		assertThat(listenerConnections()).isEqualTo(1);

		rabbitmqctl("close_all_user_connections", LISTENER_USER, "closed by the integration test");
		long closedAt = System.nanoTime();
		// requests that arrive while amqp-client waits to recover the connection (5 s by default)
		for (int i = 0; i < 4; i++) {
			Thread.sleep(500);
			String id = "while-recovering-" + i;
			assertThatCode(() -> login(factory, id)).doesNotThrowAnyException();
		}

		awaitDelivery(factory, "recovered");
		// past the recovery interval, so that any second connection would have been opened and recovered by now
		long settled = closedAt + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < settled) {
			Thread.sleep(200);
		}
		assertThat(listenerConnections()).as("connections of the listener").isEqualTo(1);

		factory.close();
		awaitCondition("listener connection closed", () -> listenerConnections() == 0);
	}

	@Test
	@Order(4)
	void aConnectionTheNetworkStopsCarryingIsRecoveredWhileAPublishIsStuckInIt() throws Exception {
		try (FreezingProxy proxy = new FreezingProxy(RABBITMQ.getHost(), RABBITMQ.getMappedPort(5672))) {
			RabbitMqEventListenerProviderFactory factory = listenerWithShortHeartbeats(proxy.port());
			login(factory, "before-partition");
			awaitMessage("before-partition");

			// The network stops carrying the connection, as in a partition: an admin event larger than the socket
			// buffers stays in its write and holds the channel, and heartbeats no longer arrive.
			proxy.freeze();
			String eightMegabytes = "x".repeat(8_000_000);
			Future<?> stuck = requestThreads.submit(() -> adminUpdate(factory, "stuck-in-the-write", eightMegabytes));
			Thread.sleep(1000);
			assertThat(stuck.isDone()).as("admin request whose event cannot be written").isFalse();

			// amqp-client notices the missed heartbeats, gives up the connection although the write still holds
			// the channel, and recovers it on a new connection, which the network carries
			awaitCondition("a new connection through the proxy", () -> proxy.connections() == 2);
			awaitDelivery(factory, "after-partition");
			assertThatCode(() -> stuck.get(10, TimeUnit.SECONDS)).as("admin request").doesNotThrowAnyException();
		}
	}

	// --- helpers ------------------------------------------------------------------------------------

	private RabbitMqEventListenerProviderFactory listener() {
		RabbitMqEventListenerProviderFactory factory = new RabbitMqEventListenerProviderFactory();
		factories.add(factory);
		factory.init(scope(map(
				"url", RABBITMQ.getHost(),
				"port", String.valueOf(RABBITMQ.getMappedPort(5672)),
				"username", LISTENER_USER,
				"password", LISTENER_PASSWORD,
				"vhost", "/",
				"exchange", EXCHANGE)));
		return factory;
	}

	/**
	 * The listener through the given port, with 2 s heartbeats and recovery after 1 s to keep the test short. Its
	 * socket send buffer is small, so that the stuck write does not depend on how far the operating system lets
	 * send buffers grow.
	 */
	private RabbitMqEventListenerProviderFactory listenerWithShortHeartbeats(int port) {
		RabbitMqEventListenerProviderFactory factory = new RabbitMqEventListenerProviderFactory() {
			@Override
			ConnectionFactory newConnectionFactory() {
				ConnectionFactory connectionFactory = new ConnectionFactory();
				connectionFactory.setRequestedHeartbeat(2);
				connectionFactory.setNetworkRecoveryInterval(1000);
				connectionFactory.setSocketConfigurator(SocketConfigurators.defaultConfigurator()
						.andThen(socket -> socket.setSendBufferSize(64 * 1024)));
				return connectionFactory;
			}
		};
		factories.add(factory);
		factory.init(scope(map(
				"url", "127.0.0.1",
				"port", String.valueOf(port),
				"username", LISTENER_USER,
				"password", LISTENER_PASSWORD,
				"vhost", "/",
				"exchange", EXCHANGE)));
		return factory;
	}

	/** A Keycloak request that logs a user in. */
	private static void login(RabbitMqEventListenerProviderFactory factory, String username) {
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

	/** A Keycloak admin request that updates a user, with a representation of the given size. */
	private static void adminUpdate(RabbitMqEventListenerProviderFactory factory, String id, String padding) {
		ContractFixtures.SessionDouble session = ContractFixtures.session(map(REALM_ID, "acme"), "acme");
		EventListenerProvider provider = factory.create(session.session);
		AuthDetails auth = new AuthDetails();
		auth.setRealmId("master-realm-id");
		auth.setClientId("admin-cli");
		auth.setUserId("admin-user-id");
		auth.setIpAddress("192.0.2.1");
		AdminEvent event = new AdminEvent();
		event.setTime(System.currentTimeMillis());
		event.setRealmId(REALM_ID);
		event.setAuthDetails(auth);
		event.setResourceType(ResourceType.USER);
		event.setOperationType(OperationType.UPDATE);
		event.setResourcePath("users/" + id);
		event.setRepresentation("{\"padding\":\"" + padding + "\"}");
		provider.onEvent(event, true);
		session.completeTransaction();
	}

	/** The user name of a login message, or the last segment of the resource path of an admin message. */
	private static String idOf(String body) {
		JsonNode json;
		try {
			json = JSON.readTree(body);
		} catch (Exception e) {
			throw new AssertionError("not JSON: " + body.substring(0, Math.min(200, body.length())), e);
		}
		if (json.has("details")) {
			return json.get("details").get("username").asText();
		}
		String path = json.get("resourcePath").asText();
		return path.substring(path.lastIndexOf('/') + 1);
	}

	private static void awaitMessage(String id) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			String body = RECEIVED.poll(1, TimeUnit.SECONDS);
			if (body != null && idOf(body).equals(id)) {
				return;
			}
		}
		throw new AssertionError("no message for " + id);
	}

	/** Logs users in every half second until a message from one of them arrives. */
	private static void awaitDelivery(RabbitMqEventListenerProviderFactory factory, String prefix)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		for (int attempt = 0; System.nanoTime() < deadline; attempt++) {
			login(factory, prefix + "-" + attempt);
			String body = RECEIVED.poll(500, TimeUnit.MILLISECONDS);
			while (body != null) {
				if (idOf(body).startsWith(prefix + "-")) {
					return;
				}
				body = RECEIVED.poll();
			}
		}
		throw new AssertionError("no message for " + prefix + "-* within 30 s");
	}

	/** Waits up to 10 s for the listener to log a line containing the text; the test checks the log itself. */
	private static void awaitLogLine(String text) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (LOG_LINES.stream().noneMatch(line -> line.contains(text)) && System.nanoTime() < deadline) {
			Thread.sleep(50);
		}
	}

	private static void awaitCondition(String what, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timed out waiting for: " + what);
			}
			Thread.sleep(250);
		}
	}

	private static boolean alarmsCheckPasses() {
		try {
			return RABBITMQ.execInContainer("rabbitmq-diagnostics", "-q", "check_alarms").getExitCode() == 0;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static int listenerConnections() {
		try {
			String out = rabbitmqctl("list_connections", "-q", "--no-table-headers", "user");
			int count = 0;
			for (String line : out.split("\n")) {
				if (line.trim().equals(LISTENER_USER)) {
					count++;
				}
			}
			return count;
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

	/**
	 * A TCP proxy in front of RabbitMQ. {@link #freeze()} makes the connections it carries stop carrying data in both
	 * directions, as a network partition does; connections opened afterwards work.
	 */
	private static final class FreezingProxy implements AutoCloseable {
		private final ServerSocket server = new ServerSocket();
		private final List<Socket> sockets = new CopyOnWriteArrayList<>();
		private final Set<Socket> frozen = ConcurrentHashMap.newKeySet();
		private final AtomicInteger connections = new AtomicInteger();

		FreezingProxy(String host, int port) throws IOException {
			server.setReceiveBufferSize(8192); // so that a frozen connection fills up after little data
			server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			start(() -> {
				while (!server.isClosed()) {
					try {
						Socket client = server.accept();
						Socket broker = new Socket(host, port);
						sockets.add(client);
						sockets.add(broker);
						connections.incrementAndGet();
						start(() -> pump(client, broker));
						start(() -> pump(broker, client));
					} catch (IOException e) {
						// the proxy is closed
					}
				}
			});
		}

		int port() {
			return server.getLocalPort();
		}

		int connections() {
			return connections.get();
		}

		void freeze() {
			frozen.addAll(sockets);
		}

		private void pump(Socket from, Socket to) {
			byte[] buffer = new byte[8192];
			try {
				InputStream in = from.getInputStream();
				OutputStream out = to.getOutputStream();
				while (true) {
					if (frozen.contains(from)) {
						Thread.sleep(50);
						continue;
					}
					int read = in.read(buffer);
					if (read < 0) {
						break;
					}
					out.write(buffer, 0, read);
				}
			} catch (IOException | InterruptedException e) {
				// the connection is closed
			} finally {
				closeQuietly(from);
				closeQuietly(to);
			}
		}

		private static void start(Runnable task) {
			Thread thread = new Thread(task, "freezing-proxy");
			thread.setDaemon(true);
			thread.start();
		}

		private static void closeQuietly(Socket socket) {
			try {
				socket.close();
			} catch (IOException e) {
				// already closed
			}
		}

		@Override
		public void close() throws IOException {
			server.close();
			sockets.forEach(FreezingProxy::closeQuietly);
		}
	}
}
