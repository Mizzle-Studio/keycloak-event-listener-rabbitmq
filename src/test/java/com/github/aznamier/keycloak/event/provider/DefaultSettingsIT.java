// Modified in 2026 by the contributors of https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq
// (fork of aznamier/keycloak-event-listener-rabbitmq); see NOTICE and CHANGELOG.md.
package com.github.aznamier.keycloak.event.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

/**
 * Keycloak with the provider jar and the listener's default settings, against a real RabbitMQ: only the broker's
 * address, the user, the password and the exchange are set. It covers what the contract tests cannot see because
 * they replace Keycloak and RabbitMQ with doubles:
 * <ul>
 * <li>the default virtual host, which is empty in the configuration;</li>
 * <li>an event that RabbitMQ refuses because the exchange does not exist.</li>
 * </ul>
 * The realms are created through the admin API, so their ids are UUIDs that differ from their names. System
 * properties as in {@link KeycloakToRabbitMqIT}.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DefaultSettingsIT {

	private static final String RABBIT_USER = "keycloak";
	private static final String RABBIT_PASSWORD = "default-settings-test";
	private static final String EXCHANGE = "keycloak.events";
	private static final String CLIENT = "web-app";
	private static final String USERNAME = "default-settings-user";
	private static final String PASSWORD = "Default-Settings-Passw0rd";

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Network NETWORK = Network.newNetwork();
	private static final Path PROVIDER_JAR = Paths.get(System.getProperty("it.provider.jar", "target/provider.jar"));

	@Container
	private static final GenericContainer<?> RABBITMQ = new GenericContainer<>(
			DockerImageName.parse(System.getProperty("it.rabbitmq.image", "rabbitmq:4.3.6-alpine")))
			.withNetwork(NETWORK)
			.withNetworkAliases("rabbitmq")
			.withEnv("RABBITMQ_DEFAULT_USER", RABBIT_USER)
			.withEnv("RABBITMQ_DEFAULT_PASS", RABBIT_PASSWORD)
			.withExposedPorts(5672)
			.waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1)
					.withStartupTimeout(Duration.ofMinutes(2)));

	/** No KK_TO_RMQ_VHOST, unlike {@link KeycloakToRabbitMqIT}. */
	@Container
	private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
			DockerImageName.parse(System.getProperty("it.keycloak.image", "quay.io/keycloak/keycloak:26.8.1")))
			.withNetwork(NETWORK)
			.dependsOn(RABBITMQ)
			.withCommand("start-dev")
			.withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
			.withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
			.withEnv("KK_TO_RMQ_URL", "rabbitmq")
			.withEnv("KK_TO_RMQ_USERNAME", RABBIT_USER)
			.withEnv("KK_TO_RMQ_PASSWORD", RABBIT_PASSWORD)
			.withEnv("KK_TO_RMQ_EXCHANGE", EXCHANGE)
			.withCopyFileToContainer(MountableFile.forHostPath(PROVIDER_JAR),
					"/opt/keycloak/providers/keycloak-to-rabbit.jar")
			.withExposedPorts(8080)
			.waitingFor(Wait.forHttp("/realms/master").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));

	private static final HttpClient HTTP = HttpClient.newHttpClient();
	private static final BlockingQueue<Received> INBOX = new LinkedBlockingQueue<>();
	private static final List<String> SEEN = new ArrayList<>();
	private static Connection consumerConnection;
	private static Channel consumerChannel;
	private static String queue;

	private record Received(String routingKey, String body) {
		JsonNode json() {
			try {
				return JSON.readTree(body);
			} catch (IOException e) {
				throw new AssertionError("not JSON: " + body, e);
			}
		}
	}

	@BeforeAll
	static void subscribe() throws Exception {
		ConnectionFactory cf = new ConnectionFactory();
		cf.setHost(RABBITMQ.getHost());
		cf.setPort(RABBITMQ.getMappedPort(5672));
		cf.setUsername(RABBIT_USER);
		cf.setPassword(RABBIT_PASSWORD);
		consumerConnection = cf.newConnection();
		consumerChannel = consumerConnection.createChannel();
		queue = consumerChannel.queueDeclare().getQueue();
		declareTheExchange();
		consumerChannel.basicConsume(queue, true, new DefaultConsumer(consumerChannel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
					byte[] body) {
				INBOX.add(new Received(envelope.getRoutingKey(), new String(body, StandardCharsets.UTF_8)));
			}
		});

		// Requests from the host do not come from localhost inside the container: allow plain HTTP.
		kcadm("config", "credentials", "--server", "http://localhost:8080", "--realm", "master", "--user", "admin",
				"--password", "admin");
		kcadm("update", "realms/master", "-s", "sslRequired=NONE");
	}

	@AfterAll
	static void closeConsumer() throws IOException {
		if (consumerConnection != null) {
			consumerConnection.close();
		}
	}

	@Test
	@Order(1)
	void eventsReachRabbitMqThroughTheDefaultVirtualHost() throws Exception {
		String realmId = createRealm("defaults", Map.of());

		HttpResponse<String> login = token("defaults");
		assertThat(login.statusCode()).as(login.body()).isEqualTo(200);

		Received message = await("KK.EVENT.CLIENT.defaults.SUCCESS." + CLIENT + ".LOGIN");
		assertThat(message.json().get("realmId").asText()).isEqualTo(realmId);
	}

	@Test
	@Order(2)
	void anEventThatRabbitMqRefusesBecauseTheExchangeIsMissingIsLogged() throws Exception {
		createRealm("no-exchange", Map.of());
		consumerChannel.exchangeDelete(EXCHANGE);
		try {
			// RabbitMQ closes the channel instead of taking the message; the publish itself does not fail
			HttpResponse<String> login = token("no-exchange");
			assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
			awaitLogLine("keycloak-to-rabbitmq: RabbitMQ refused an event and closed the channel: "
					+ "NOT_FOUND - no exchange '" + EXCHANGE + "' in vhost '/'");
		} finally {
			declareTheExchange();
		}

		// the next session publishes on a new channel
		assertThat(token("no-exchange").statusCode()).isEqualTo(200);
		await("KK.EVENT.CLIENT.no-exchange.SUCCESS." + CLIENT + ".LOGIN");
	}

	// --- helpers ------------------------------------------------------------------------------------

	/** The exchange the listener publishes to, with the test's queue bound to all events. */
	private static void declareTheExchange() throws IOException {
		consumerChannel.exchangeDeclare(EXCHANGE, "topic", true);
		consumerChannel.queueBind(queue, EXCHANGE, "KK.EVENT.#");
	}

	private static void awaitLogLine(String text) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (!KEYCLOAK.getLogs().contains(text)) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("no log line with: " + text + listenerLog());
			}
			Thread.sleep(200);
		}
	}

	/**
	 * A realm with the listener, a public client and a user with a password, created through the admin API.
	 *
	 * @return the id that Keycloak gave the realm
	 */
	private static String createRealm(String name, Map<String, String> clientAttributes) throws Exception {
		String token = adminToken();
		send(token, "POST", "/admin/realms", Map.of(
				"realm", name,
				"enabled", true,
				"sslRequired", "none",
				"eventsListeners", List.of("jboss-logging", "keycloak-to-rabbitmq")), 201);
		send(token, "POST", "/admin/realms/" + name + "/clients", Map.of(
				"clientId", CLIENT,
				"publicClient", true,
				"directAccessGrantsEnabled", true,
				"attributes", clientAttributes), 201);
		send(token, "POST", "/admin/realms/" + name + "/users", Map.of(
				"username", USERNAME,
				"enabled", true,
				"email", USERNAME + "@example.org",
				"emailVerified", true,
				"firstName", "Default",
				"lastName", "Settings",
				"credentials", List.of(Map.of("type", "password", "value", PASSWORD, "temporary", false))), 201);
		String id = JSON.readTree(send(token, "GET", "/admin/realms/" + name, null, 200).body()).get("id").asText();
		assertThat(id).as("id of realm " + name).isNotEqualTo(name);
		return id;
	}

	/** Waits for the next message with the given routing key. */
	private static Received await(String routingKey) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			Received next = INBOX.poll(1, TimeUnit.SECONDS);
			if (next == null) {
				continue;
			}
			SEEN.add(next.routingKey());
			if (next.routingKey().equals(routingKey)) {
				return next;
			}
		}
		throw new AssertionError("no message with routing key " + routingKey + "; received: " + SEEN + listenerLog());
	}

	/** The last lines of the Keycloak log about the listener: its own, RabbitMQ's replies, errors of requests. */
	private static String listenerLog() {
		List<String> lines = KEYCLOAK.getLogs().lines()
				.filter(line -> line.contains("keycloak-to-rabbitmq") || line.contains("reply-text")
						|| line.contains("Uncaught server error") || line.contains("at com.github.aznamier."))
				.filter(line -> !line.contains("keycloak-to-rabbitmq configuration:"))
				.collect(Collectors.toList());
		return String.join("\n", lines.subList(Math.max(0, lines.size() - 20), lines.size()))
				.transform(text -> "\nlistener log:\n" + text);
	}

	private static void kcadm(String... args) throws Exception {
		String[] command = new String[args.length + 1];
		command[0] = "/opt/keycloak/bin/kcadm.sh";
		System.arraycopy(args, 0, command, 1, args.length);
		ExecResult result = KEYCLOAK.execInContainer(command);
		assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
	}

	private static String baseUrl() {
		return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
	}

	private static HttpResponse<String> token(String realm) throws Exception {
		return form("/realms/" + realm + "/protocol/openid-connect/token", Map.of(
				"grant_type", "password",
				"client_id", CLIENT,
				"username", USERNAME,
				"password", PASSWORD));
	}

	private static String adminToken() throws Exception {
		HttpResponse<String> response = form("/realms/master/protocol/openid-connect/token", Map.of(
				"grant_type", "password",
				"client_id", "admin-cli",
				"username", "admin",
				"password", "admin"));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return JSON.readTree(response.body()).get("access_token").asText();
	}

	private static HttpResponse<String> form(String path, Map<String, String> fields) throws Exception {
		String body = fields.entrySet().stream()
				.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
						+ URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
				.collect(Collectors.joining("&"));
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> send(String token, String method, String path, Object json, int expectedStatus)
			throws Exception {
		HttpRequest.BodyPublisher publisher = json == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(json));
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", "application/json")
				.method(method, publisher)
				.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(method + " " + path + ": " + response.body()).isEqualTo(expectedStatus);
		return response;
	}
}
