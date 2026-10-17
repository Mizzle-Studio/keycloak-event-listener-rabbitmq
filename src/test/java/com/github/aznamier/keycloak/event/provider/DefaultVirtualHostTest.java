// Modified in 2026 by the contributors of https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq
// (fork of aznamier/keycloak-event-listener-rabbitmq); see NOTICE and CHANGELOG.md.
package com.github.aznamier.keycloak.event.provider;

import static com.github.aznamier.keycloak.event.provider.ContractFixtures.map;
import static com.github.aznamier.keycloak.event.provider.ContractFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.rabbitmq.client.ConnectionFactory;

/**
 * The virtual host the listener connects to. The setting is empty by default, and RabbitMQ refuses a connection to
 * the virtual host "" with {@code 530 NOT_ALLOWED - vhost  not found} unless one with that name was created. An empty
 * setting means "/", so a virtual host named "" cannot be reached; {@link VirtualHostIT} checks this against RabbitMQ.
 */
class DefaultVirtualHostTest {

	@Test
	void withoutTheSettingTheListenerConnectsToTheDefaultVirtualHost() {
		assumeTrue(System.getenv("KK_TO_RMQ_VHOST") == null, "KK_TO_RMQ_VHOST is set in the environment of this test run");

		assertThat(virtualHostOf(map())).isEqualTo("/");
	}

	@Test
	void anEmptySettingIsTheDefaultVirtualHost() {
		assertThat(virtualHostOf(map("vhost", ""))).isEqualTo("/");
		// the setting itself still reads as empty
		assertThat(RabbitMqConfig.createFromScope(scope(map("vhost", ""))).getVhost()).isEmpty();
	}

	@Test
	void aVirtualHostThatIsSetIsUsedAsItIs() {
		assertThat(virtualHostOf(map("vhost", "/"))).isEqualTo("/");
		assertThat(virtualHostOf(map("vhost", "keycloak"))).isEqualTo("keycloak");
		assertThat(virtualHostOf(map("vhost", "/keycloak"))).isEqualTo("/keycloak");
	}

	/** The virtual host of the connection factory that the listener sets up for these settings. */
	private static String virtualHostOf(Map<String, String> settings) {
		ConnectionFactory connectionFactory = new ConnectionFactory();
		RabbitMqEventListenerProviderFactory factory = new RabbitMqEventListenerProviderFactory() {
			@Override
			ConnectionFactory newConnectionFactory() {
				return connectionFactory;
			}
		};
		settings.putIfAbsent("use_tls", "false");
		factory.init(scope(settings));
		return connectionFactory.getVirtualHost();
	}
}
