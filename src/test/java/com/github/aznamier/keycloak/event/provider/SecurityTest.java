package com.github.aznamier.keycloak.event.provider;

import static com.github.aznamier.keycloak.event.provider.ContractFixtures.map;
import static com.github.aznamier.keycloak.event.provider.ContractFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;

import com.rabbitmq.client.ConnectionFactory;

/** Fixes that go beyond the 3.0.5 behaviour: TLS verification, secret handling and shutdown. */
class SecurityTest {

	private static final String STORE_PASSWORD = "store-password";

	@TempDir
	Path dir;

	/** Factory whose connection factory is a spy, to observe how it is configured and used. */
	private static final class ObservedFactory extends RabbitMqEventListenerProviderFactory {
		ConnectionFactory connectionFactory;

		@Override
		ConnectionFactory newConnectionFactory() {
			connectionFactory = spy(new ConnectionFactory());
			return connectionFactory;
		}
	}

	// --- broker certificate verification ---------------------------------------------------------

	@Test
	void tlsWithoutATrustStoreUsesTheJvmTrustStoreAndRejectsAnUnknownCertificate() throws Exception {
		Path serverKeys = selfSignedServerKeys();
		RabbitMqConfig cfg = RabbitMqConfig.createFromScope(scope(map("use_tls", "true")));

		SSLContext client = RabbitMqEventListenerProviderFactory.createSslContext(cfg);

		assertThatThrownBy(() -> handshake(serverContext(serverKeys), client)).isInstanceOf(SSLException.class);
	}

	@Test
	void tlsWithATrustStoreAcceptsTheTrustedCertificate() throws Exception {
		Path serverKeys = selfSignedServerKeys();
		Path trustStore = trustStoreFor(serverKeys);
		RabbitMqConfig cfg = RabbitMqConfig.createFromScope(scope(map(
				"use_tls", "true",
				"trust_store", trustStore.toString(),
				"trust_store_pass", STORE_PASSWORD)));

		SSLContext client = RabbitMqEventListenerProviderFactory.createSslContext(cfg);

		assertThatCode(() -> handshake(serverContext(serverKeys), client)).doesNotThrowAnyException();
	}

	@Test
	void brokerHostnameIsVerified() {
		ObservedFactory factory = new ObservedFactory();
		factory.init(scope(map("use_tls", "true")));

		assertThat(factory.connectionFactory.isSSL()).isTrue();
		verify(factory.connectionFactory).enableHostnameVerification();
	}

	@Test
	void brokenTlsSetupNeverFallsBackToPlainText() throws Exception {
		ObservedFactory factory = new ObservedFactory();
		factory.init(scope(map("use_tls", "true", "trust_store", dir.resolve("missing.jks").toString())));

		ContractFixtures.SessionDouble session = ContractFixtures.session(map("realm-id", "acme"), "acme");
		EventListenerProvider provider = factory.create(session.session);
		provider.onEvent(loginEvent());

		assertThatCode(session::completeTransaction).doesNotThrowAnyException();
		verify(factory.connectionFactory, never()).newConnection();
	}

	// --- secrets ----------------------------------------------------------------------------------

	private final List<String> logLines = new ArrayList<>();
	private final Handler capture = new Handler() {
		@Override
		public void publish(LogRecord record) {
			String message = record.getMessage();
			if (record.getParameters() != null && record.getParameters().length > 0) {
				message = String.format(message, record.getParameters());
			}
			logLines.add(message);
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}
	};

	@BeforeEach
	void captureLog() {
		Logger.getLogger(RabbitMqConfig.class.getName()).addHandler(capture);
	}

	@AfterEach
	void releaseLog() {
		Logger.getLogger(RabbitMqConfig.class.getName()).removeHandler(capture);
	}

	@Test
	void secretsAreNeverLogged() {
		RabbitMqConfig.createFromScope(scope(map(
				"password", "broker-secret",
				"trust_store_pass", "trust-secret",
				"key_store_pass", "key-secret",
				"url", "broker.example")));

		assertThat(logLines).anyMatch(line -> line.contains("url=broker.example"));
		assertThat(logLines).anyMatch(line -> line.contains("password=<hidden>"));
		assertThat(logLines).anyMatch(line -> line.contains("trust_store_pass=<hidden>"));
		assertThat(logLines).anyMatch(line -> line.contains("key_store_pass=<hidden>"));
		assertThat(String.join("\n", logLines)).doesNotContain("broker-secret", "trust-secret", "key-secret");
	}

	@Test
	void printableValues() {
		assertThat(RabbitMqConfig.printable("password", "")).isEqualTo("<empty>");
		assertThat(RabbitMqConfig.printable("key_store_pass", "x")).isEqualTo("<hidden>");
		assertThat(RabbitMqConfig.printable("username", "kc")).isEqualTo("kc");
	}

	// --- shutdown ---------------------------------------------------------------------------------

	@Test
	void closeIsSafeWhenTheBrokerWasNeverReached() throws Exception {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		RabbitMqEventListenerProviderFactory factory = new RabbitMqEventListenerProviderFactory();
		factory.init(scope(map("url", "127.0.0.1", "port", String.valueOf(port))));
		factory.create(ContractFixtures.session(Map.of(), "acme").session);

		assertThatCode(factory::close).doesNotThrowAnyException();
	}

	@Test
	void closeIsSafeBeforeAnySession() {
		RabbitMqEventListenerProviderFactory factory = new RabbitMqEventListenerProviderFactory();
		factory.init(scope(map()));

		assertThatCode(factory::close).doesNotThrowAnyException();
	}

	// --- helpers ----------------------------------------------------------------------------------

	private static Event loginEvent() {
		Event event = new Event();
		event.setType(EventType.LOGIN);
		event.setRealmId("realm-id");
		event.setClientId("web-app");
		return event;
	}

	private Path selfSignedServerKeys() throws Exception {
		Path keys = dir.resolve("server.p12");
		keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
				"-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
				"-storetype", "PKCS12", "-keystore", keys.toString(), "-storepass", STORE_PASSWORD);
		return keys;
	}

	private Path trustStoreFor(Path serverKeys) throws Exception {
		Path cert = dir.resolve("server.cer");
		Path trust = dir.resolve("trust.jks");
		keytool("-exportcert", "-alias", "server", "-keystore", serverKeys.toString(), "-storepass", STORE_PASSWORD,
				"-file", cert.toString());
		keytool("-importcert", "-noprompt", "-alias", "server", "-file", cert.toString(), "-storetype", "JKS",
				"-keystore", trust.toString(), "-storepass", STORE_PASSWORD);
		return trust;
	}

	private static void keytool(String... args) throws Exception {
		List<String> command = new ArrayList<>();
		command.add(Paths.get(System.getProperty("java.home"), "bin", "keytool").toString());
		command.addAll(List.of(args));
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
			throw new IllegalStateException("keytool failed: " + output);
		}
	}

	private static SSLContext serverContext(Path keys) throws Exception {
		java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
		try (var in = java.nio.file.Files.newInputStream(keys)) {
			store.load(in, STORE_PASSWORD.toCharArray());
		}
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(store, STORE_PASSWORD.toCharArray());
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(kmf.getKeyManagers(), null, null);
		return context;
	}

	/** Runs a TLS handshake between a local server and a client using the given contexts. */
	private static void handshake(SSLContext server, SSLContext client) throws Exception {
		try (SSLServerSocket serverSocket = (SSLServerSocket) server.getServerSocketFactory()
				.createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			serverSocket.setSoTimeout(10_000);
			CompletableFuture<Void> serverSide = CompletableFuture.runAsync(() -> {
				try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
					accepted.setSoTimeout(10_000);
					accepted.startHandshake();
					accepted.getOutputStream().write(1);
					accepted.getOutputStream().flush();
				} catch (IOException ignored) {
					// the client side reports the outcome
				}
			});
			try (SSLSocket socket = (SSLSocket) client.getSocketFactory()
					.createSocket(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort())) {
				socket.setSoTimeout(10_000);
				socket.startHandshake();
				if (socket.getInputStream().read() != 1) {
					throw new SSLException("no application data after the handshake");
				}
			} finally {
				serverSide.get(20, TimeUnit.SECONDS);
			}
		}
	}
}
