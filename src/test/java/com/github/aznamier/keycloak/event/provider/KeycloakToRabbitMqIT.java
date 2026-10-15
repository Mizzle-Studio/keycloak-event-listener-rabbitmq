package com.github.aznamier.keycloak.event.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

/**
 * End-to-end check against a real Keycloak with the provider jar installed and a real RabbitMQ.
 *
 * <p>System properties (set by the Failsafe configuration in the POM):
 * <ul>
 * <li>{@code it.keycloak.image} - Keycloak image, the target version by default</li>
 * <li>{@code it.rabbitmq.image} - RabbitMQ image</li>
 * <li>{@code it.provider.jar} - provider jar to install, the one built by this project by default;
 * pointing it to another release of the listener runs the same contract against that release</li>
 * </ul>
 * Received message bodies are written to {@code target/it-messages/} for inspection.
 *
 * <p>The expected field sets are those of release 3.0.5 of this listener running on Keycloak 26.0.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KeycloakToRabbitMqIT {

	private static final String RABBIT_USER = "keycloak";
	private static final String RABBIT_PASSWORD = "integration-test";
	private static final String EXCHANGE = "keycloak.events";
	private static final String REALM = "it.realm";
	private static final String REALM_IN_KEY = "itrealm";
	private static final String CLIENT = "it.client";
	private static final String CLIENT_IN_KEY = "itclient";
	private static final String USERNAME = "contract-user";
	private static final String PASSWORD = "Contract-Passw0rd";

	private static final String CLIENT_TYPE = "com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg";
	private static final String ADMIN_TYPE = "com.github.aznamier.keycloak.event.provider.EventAdminNotificationMqMsg";

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

	@Container
	private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
			DockerImageName.parse(System.getProperty("it.keycloak.image", "quay.io/keycloak/keycloak:26.8.1")))
			.withNetwork(NETWORK)
			.dependsOn(RABBITMQ)
			.withCommand("start-dev")
			.withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
			.withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
			.withEnv("KK_TO_RMQ_URL", "rabbitmq")
			.withEnv("KK_TO_RMQ_VHOST", "/")
			.withEnv("KK_TO_RMQ_USERNAME", RABBIT_USER)
			.withEnv("KK_TO_RMQ_PASSWORD", RABBIT_PASSWORD)
			.withEnv("KK_TO_RMQ_EXCHANGE", EXCHANGE)
			.withCopyFileToContainer(MountableFile.forHostPath(PROVIDER_JAR),
					"/opt/keycloak/providers/keycloak-to-rabbit.jar")
			.withExposedPorts(8080)
			.waitingFor(Wait.forHttp("/realms/master").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));

	private static final HttpClient HTTP = HttpClient.newHttpClient();
	private static final BlockingQueue<Received> INBOX = new LinkedBlockingQueue<>();
	private static final List<Received> SEEN = new ArrayList<>();
	private static Connection consumerConnection;

	private static String realmId;
	private static String userId;

	private record Received(String exchange, String routingKey, AMQP.BasicProperties properties, String body) {
		JsonNode json() {
			try {
				return JSON.readTree(body);
			} catch (IOException e) {
				throw new AssertionError("not JSON: " + body, e);
			}
		}
	}

	@BeforeAll
	static void subscribeAndPrepareRealm() throws Exception {
		ConnectionFactory cf = new ConnectionFactory();
		cf.setHost(RABBITMQ.getHost());
		cf.setPort(RABBITMQ.getMappedPort(5672));
		cf.setUsername(RABBIT_USER);
		cf.setPassword(RABBIT_PASSWORD);
		consumerConnection = cf.newConnection();
		Channel channel = consumerConnection.createChannel();
		// The listener publishes to an existing exchange; a topic exchange lets consumers filter by key.
		channel.exchangeDeclare(EXCHANGE, "topic", true);
		String queue = channel.queueDeclare().getQueue();
		channel.queueBind(queue, EXCHANGE, "KK.EVENT.#");
		channel.basicConsume(queue, true, new DefaultConsumer(channel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
					byte[] body) {
				INBOX.add(new Received(envelope.getExchange(), envelope.getRoutingKey(), properties,
						new String(body, StandardCharsets.UTF_8)));
			}
		});

		// Requests from the host do not come from localhost inside the container: allow plain HTTP.
		kcadm("config", "credentials", "--server", "http://localhost:8080", "--realm", "master", "--user", "admin",
				"--password", "admin");
		kcadm("update", "realms/master", "-s", "sslRequired=NONE");

		String token = adminToken();
		send(token, "POST", "/admin/realms", Map.of(
				"realm", REALM,
				"enabled", true,
				"sslRequired", "none",
				"eventsListeners", List.of("jboss-logging", "keycloak-to-rabbitmq")), 201);
		realmId = JSON.readTree(send(token, "GET", "/admin/realms/" + REALM, null, 200).body()).get("id").asText();
		send(token, "POST", "/admin/realms/" + REALM + "/clients", Map.of(
				"clientId", CLIENT,
				"publicClient", true,
				"directAccessGrantsEnabled", true), 201);
		HttpResponse<String> created = send(token, "POST", "/admin/realms/" + REALM + "/users", Map.of(
				"username", USERNAME,
				"enabled", true,
				"email", "contract-user@example.org",
				"emailVerified", true,
				"firstName", "Contract",
				"lastName", "User",
				"credentials", List.of(Map.of("type", "password", "value", PASSWORD, "temporary", false))), 201);
		String location = created.headers().firstValue("Location").orElseThrow();
		userId = location.substring(location.lastIndexOf('/') + 1);
	}

	@AfterAll
	static void dumpMessages() throws IOException {
		if (consumerConnection != null) {
			consumerConnection.close();
		}
		String label = PROVIDER_JAR.getFileName() + "_on_"
				+ System.getProperty("it.keycloak.image", "keycloak").replaceAll("[^A-Za-z0-9.-]", "_");
		Path dir = Paths.get("target", "it-messages", label);
		Files.createDirectories(dir);
		int i = 0;
		for (Received message : SEEN) {
			Files.writeString(dir.resolve(String.format("%02d_%s.json", i++, message.routingKey())), message.body());
		}
	}

	@Test
	@Order(1)
	void userLogin() throws Exception {
		HttpResponse<String> login = token(USERNAME, PASSWORD);
		assertThat(login.statusCode()).isEqualTo(200);

		Received message = await("KK.EVENT.CLIENT." + REALM_IN_KEY + ".SUCCESS." + CLIENT_IN_KEY + ".LOGIN");
		assertContractProperties(message, CLIENT_TYPE);

		JsonNode body = message.json();
		assertThat(fieldNames(body)).containsExactlyInAnyOrder("@class", "time", "type", "realmId", "clientId",
				"userId", "sessionId", "ipAddress", "details");
		assertThat(body.get("@class").asText()).isEqualTo(CLIENT_TYPE);
		assertThat(body.get("time").isIntegralNumber()).isTrue();
		assertThat(body.get("type").asText()).isEqualTo("LOGIN");
		assertThat(body.get("realmId").asText()).isEqualTo(realmId);
		assertThat(body.get("clientId").asText()).isEqualTo(CLIENT);
		assertThat(body.get("userId").asText()).isEqualTo(userId);
		assertThat(body.get("sessionId").isTextual()).isTrue();
		assertThat(body.get("ipAddress").isTextual()).isTrue();
		assertThat(body.get("details").isObject()).isTrue();
		assertThat(body.get("details").get("username").asText()).isEqualTo(USERNAME);
	}

	@Test
	@Order(2)
	void failedUserLogin() throws Exception {
		HttpResponse<String> login = token(USERNAME, "wrong-password");
		assertThat(login.statusCode()).isBetween(400, 401);

		Received message = await("KK.EVENT.CLIENT." + REALM_IN_KEY + ".ERROR." + CLIENT_IN_KEY + ".LOGIN_ERROR");
		assertContractProperties(message, CLIENT_TYPE);

		JsonNode body = message.json();
		assertThat(fieldNames(body)).containsExactlyInAnyOrder("@class", "time", "type", "realmId", "clientId",
				"userId", "ipAddress", "error", "details");
		assertThat(body.get("type").asText()).isEqualTo("LOGIN_ERROR");
		assertThat(body.get("error").asText()).isEqualTo("invalid_user_credentials");
		assertThat(body.get("userId").asText()).isEqualTo(userId);
	}

	@Test
	@Order(3)
	void userUpdatedByAnAdministrator() throws Exception {
		String token = adminToken();
		ObjectNode user = (ObjectNode) JSON.readTree(
				send(token, "GET", "/admin/realms/" + REALM + "/users/" + userId, null, 200).body());
		user.put("firstName", "Changed");
		send(token, "PUT", "/admin/realms/" + REALM + "/users/" + userId, user, 204);

		Received message = await("KK.EVENT.ADMIN." + REALM_IN_KEY + ".SUCCESS.USER.UPDATE");
		assertContractProperties(message, ADMIN_TYPE);

		JsonNode body = message.json();
		assertThat(fieldNames(body)).containsExactlyInAnyOrder("@class", "time", "realmId", "authDetails",
				"resourceType", "operationType", "resourcePath", "representation", "resourceTypeAsString");
		assertThat(body.get("@class").asText()).isEqualTo(ADMIN_TYPE);
		assertThat(body.get("time").isIntegralNumber()).isTrue();
		assertThat(body.get("realmId").asText()).isEqualTo(realmId);
		assertThat(body.get("resourceType").asText()).isEqualTo("USER");
		assertThat(body.get("operationType").asText()).isEqualTo("UPDATE");
		assertThat(body.get("resourcePath").asText()).isEqualTo("users/" + userId);
		assertThat(body.get("resourceTypeAsString").asText()).isEqualTo("USER");
		// the representation is a JSON document embedded as a string
		assertThat(body.get("representation").isTextual()).isTrue();
		assertThat(JSON.readTree(body.get("representation").asText()).get("firstName").asText()).isEqualTo("Changed");

		JsonNode auth = body.get("authDetails");
		assertThat(fieldNames(auth)).containsExactlyInAnyOrder("realmId", "realmName", "clientId", "userId",
				"ipAddress");
		assertThat(auth.get("realmName").asText()).isEqualTo("master");
	}

	@Test
	@Order(4)
	void userDeletedByAnAdministrator() throws Exception {
		send(adminToken(), "DELETE", "/admin/realms/" + REALM + "/users/" + userId, null, 204);

		Received message = await("KK.EVENT.ADMIN." + REALM_IN_KEY + ".SUCCESS.USER.DELETE");
		assertContractProperties(message, ADMIN_TYPE);

		JsonNode body = message.json();
		assertThat(body.get("operationType").asText()).isEqualTo("DELETE");
		assertThat(body.get("resourcePath").asText()).isEqualTo("users/" + userId);
		assertThat(body.has("resourceId")).isFalse();
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new TreeSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private static void assertContractProperties(Received message, String typeId) {
		assertThat(message.exchange()).isEqualTo(EXCHANGE);
		MessagePropertiesContract.assertProperties(message.properties(), typeId);
	}

	/** Waits for the next message with the given routing key; earlier unrelated messages are kept for the dump. */
	private static Received await(String routingKey) throws InterruptedException {
		synchronized (SEEN) {
			for (Iterator<Received> it = SEEN.iterator(); it.hasNext();) {
				Received seen = it.next();
				if (seen.routingKey().equals(routingKey) && !seen.body().isEmpty()) {
					return seen;
				}
			}
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			Received next = INBOX.poll(1, TimeUnit.SECONDS);
			if (next == null) {
				continue;
			}
			synchronized (SEEN) {
				SEEN.add(next);
			}
			if (next.routingKey().equals(routingKey)) {
				return next;
			}
		}
		throw new AssertionError("no message with routing key " + routingKey + "; received: "
				+ SEEN.stream().map(Received::routingKey).collect(Collectors.toList()));
	}

	private static void kcadm(String... args) throws Exception {
		String[] command = new String[args.length + 1];
		command[0] = "/opt/keycloak/bin/kcadm.sh";
		System.arraycopy(args, 0, command, 1, args.length);
		org.testcontainers.containers.Container.ExecResult result = KEYCLOAK.execInContainer(command);
		assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
	}

	private static String baseUrl() {
		return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
	}

	private static HttpResponse<String> token(String username, String password) throws Exception {
		return form("/realms/" + REALM + "/protocol/openid-connect/token", Map.of(
				"grant_type", "password",
				"client_id", CLIENT,
				"username", username,
				"password", password,
				"scope", "openid"));
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
