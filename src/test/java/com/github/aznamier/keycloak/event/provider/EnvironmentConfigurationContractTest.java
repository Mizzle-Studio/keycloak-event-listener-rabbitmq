package com.github.aznamier.keycloak.event.provider;

import static com.github.aznamier.keycloak.event.provider.ContractFixtures.map;
import static com.github.aznamier.keycloak.event.provider.ContractFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Environment variables {@code KK_TO_RMQ_<NAME>}.
 *
 * <p>Runs in a dedicated Surefire execution ({@code environment-variables} in the POM) that sets the
 * variables below; in any other JVM the test is skipped.
 */
class EnvironmentConfigurationContractTest {

	@BeforeEach
	void runOnlyWithTheVariablesSet() {
		assumeTrue("env-broker".equals(System.getenv("KK_TO_RMQ_URL")),
				"runs in the environment-variables Surefire execution only");
	}

	@Test
	void everySettingHasAnEnvironmentVariable() {
		RabbitMqConfig cfg = RabbitMqConfig.createFromScope(scope(map()));

		assertThat(cfg.getHostUrl()).isEqualTo("env-broker");
		assertThat(cfg.getPort()).isEqualTo(5673);
		assertThat(cfg.getUsername()).isEqualTo("env-user");
		assertThat(cfg.getPassword()).isEqualTo("env-password");
		assertThat(cfg.getVhost()).isEqualTo("env-vhost");
		assertThat(cfg.getUseTls()).isTrue();
		assertThat(cfg.getTrustStore()).isEqualTo("/env/trust.jks");
		assertThat(cfg.getTrustStorePass()).isEqualTo("env-trust-pass");
		assertThat(cfg.getKeyStore()).isEqualTo("/env/key.p12");
		assertThat(cfg.getKeytStorePass()).isEqualTo("env-key-pass");
		assertThat(cfg.getExchange()).isEqualTo("env.exchange");
	}

	@Test
	void environmentIsUsedWithoutAnySpiConfiguration() {
		RabbitMqConfig cfg = RabbitMqConfig.createFromScope(null);

		assertThat(cfg.getHostUrl()).isEqualTo("env-broker");
		assertThat(cfg.getExchange()).isEqualTo("env.exchange");
	}

	@Test
	void spiConfigurationWinsOverTheEnvironment() {
		RabbitMqConfig cfg = RabbitMqConfig.createFromScope(scope(map("url", "spi-broker", "exchange", "spi.exchange")));

		assertThat(cfg.getHostUrl()).isEqualTo("spi-broker");
		assertThat(cfg.getExchange()).isEqualTo("spi.exchange");
		assertThat(cfg.getPort()).isEqualTo(5673); // not set in the SPI configuration: still from the environment
	}
}
