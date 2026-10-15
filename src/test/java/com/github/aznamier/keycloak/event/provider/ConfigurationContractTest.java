package com.github.aznamier.keycloak.event.provider;

import static com.github.aznamier.keycloak.event.provider.ContractFixtures.map;
import static com.github.aznamier.keycloak.event.provider.ContractFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Configuration is read from the Keycloak SPI configuration first
 * ({@code spi-events-listener-keycloak-to-rabbitmq-<name>}) and falls back to the environment
 * variable {@code KK_TO_RMQ_<NAME>}, then to a default.
 *
 * <p>Environment variables are covered by {@link EnvironmentConfigurationContractTest}, which runs
 * in its own JVM with the variables set.
 */
class ConfigurationContractTest {

	@BeforeEach
	void noListenerVariablesInThisJvm() {
		assumeTrue(System.getenv().keySet().stream().noneMatch(name -> name.startsWith("KK_TO_RMQ_")),
				"KK_TO_RMQ_* variables are set in the environment of this test run");
	}

	@Test
	void defaults() {
		RabbitMqConfig cfg = RabbitMqConfig.createFromScope(scope(map()));

		assertThat(cfg.getHostUrl()).isEqualTo("localhost");
		assertThat(cfg.getPort()).isEqualTo(5672);
		assertThat(cfg.getUsername()).isEqualTo("admin");
		assertThat(cfg.getPassword()).isEqualTo("admin");
		assertThat(cfg.getVhost()).isEmpty();
		assertThat(cfg.getUseTls()).isFalse();
		assertThat(cfg.getTrustStore()).isEmpty();
		assertThat(cfg.getTrustStorePass()).isEmpty();
		assertThat(cfg.getKeyStore()).isEmpty();
		assertThat(cfg.getKeytStorePass()).isEmpty();
		assertThat(cfg.getExchange()).isEqualTo("amq.topic");
	}

	@Test
	void defaultsWithoutAnySpiConfiguration() {
		RabbitMqConfig cfg = RabbitMqConfig.createFromScope(null);

		assertThat(cfg.getHostUrl()).isEqualTo("localhost");
		assertThat(cfg.getPort()).isEqualTo(5672);
		assertThat(cfg.getExchange()).isEqualTo("amq.topic");
	}

	@Test
	void spiConfigurationKeys() {
		RabbitMqConfig cfg = RabbitMqConfig.createFromScope(scope(map(
				"url", "broker.example",
				"port", "5671",
				"username", "kc",
				"password", "s3cret",
				"vhost", "/keycloak",
				"use_tls", "true",
				"trust_store", "/etc/trust.jks",
				"trust_store_pass", "trust-pass",
				"key_store", "/etc/key.p12",
				"key_store_pass", "key-pass",
				"exchange", "keycloak.events")));

		assertThat(cfg.getHostUrl()).isEqualTo("broker.example");
		assertThat(cfg.getPort()).isEqualTo(5671);
		assertThat(cfg.getUsername()).isEqualTo("kc");
		assertThat(cfg.getPassword()).isEqualTo("s3cret");
		assertThat(cfg.getVhost()).isEqualTo("/keycloak");
		assertThat(cfg.getUseTls()).isTrue();
		assertThat(cfg.getTrustStore()).isEqualTo("/etc/trust.jks");
		assertThat(cfg.getTrustStorePass()).isEqualTo("trust-pass");
		assertThat(cfg.getKeyStore()).isEqualTo("/etc/key.p12");
		assertThat(cfg.getKeytStorePass()).isEqualTo("key-pass");
		assertThat(cfg.getExchange()).isEqualTo("keycloak.events");
	}

	@Test
	void booleanFlagIsLenient() {
		assertThat(RabbitMqConfig.createFromScope(scope(map("use_tls", "TRUE"))).getUseTls()).isTrue();
		assertThat(RabbitMqConfig.createFromScope(scope(map("use_tls", "yes"))).getUseTls()).isFalse();
	}
}
