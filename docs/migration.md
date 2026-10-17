# Migration

- [From aznamier/keycloak-event-listener-rabbitmq 3.x](#from-aznamierkeycloak-event-listener-rabbitmq-3x)
- [From the focus-shift fork](#from-the-focus-shift-fork)

## From aznamier/keycloak-event-listener-rabbitmq 3.x

Consumers need no change: on the same Keycloak version this listener publishes the same routing keys, AMQP properties,
headers and JSON fields as upstream 3.0.5. The provider id is still `keycloak-to-rabbitmq`, so realm settings and
realm exports stay valid, and every setting keeps its name and its `KK_TO_RMQ_*` variable.

What to change:

| | Upstream 3.0.5 | This fork |
|---|---|---|
| Jar | `keycloak-to-rabbit-3.0.5.jar` | `keycloak-to-rabbit-<version>.jar` from the [releases](https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq/releases). Remove the old jar. |
| Keycloak | 16 or newer according to its README, built against 21.1.2 | 26.0 or newer |
| Java | 8 or newer | 17 or newer (Keycloak 26 itself runs on Java 21 in the official images) |
| WildFly-based Keycloak (16 and older) | `standalone.xml` and CLI scripts | not supported |
| TLS (`use_tls=true`) | with neither a trust store nor a key store, any broker certificate was accepted; if the TLS setup failed, the listener connected without TLS | the broker certificate and host name are verified; a failed TLS setup means no connection |
| Empty `vhost` (the default) | sent to RabbitMQ as it is: `NOT_ALLOWED - vhost  not found`, unless the broker has a virtual host named `""`, which the listener then connects to | from 4.1.0 on, RabbitMQ's default virtual host `/` |
| Libraries in the jar | `amqp-client` 5.14.2, SLF4J | `amqp-client` 5.37.0 only; SLF4J and Netty come from Keycloak |
| Maven coordinates | `com.github.aznamier.keycloak.event.provider:keycloak-to-rabbit` | `io.github.mizzle-studio:keycloak-to-rabbit`, not published to Maven Central yet: download the jar |

Steps:

1. Replace the jar in `providers/` (or in your image) and run `kc.sh build` if you start with `--optimized`.
2. If you use TLS, make sure the broker certificate is trusted and issued for the host name in `url`. For a private
   CA, add it to Keycloak's trusted certificates or give the listener a `trust_store`
   ([TLS](configuration.md#tls-and-client-certificates)).
3. If you set options through `KC_SPI_…` variables, check the listener's `configuration:` log lines after the upgrade:
   Keycloak 26.8 ignores `KC_SPI_…` variables for the settings with an underscore (`use_tls`, `trust_store`,
   `trust_store_pass`, `key_store`, `key_store_pass`). The `KK_TO_RMQ_*` variables work everywhere
   ([details](configuration.md#ways-to-set-an-option)).
4. `vhost` can stay empty: from 4.1.0 on, an empty `vhost` connects to `/`. 3.0.5 passes an empty `vhost` on to the
   RabbitMQ client as it is (`setVirtualHost("")`), so a 3.0.5 setup with an empty `vhost` that delivers events uses a
   virtual host named `""`. 4.1.0 and later do not connect to that one: move the exchange and its queues to another
   virtual host and set `vhost` to its name.
5. Restart Keycloak and watch for `keycloak-to-rabbitmq ERROR` lines.

The full list of changes and the advisories fixed by the dependency updates are in the [CHANGELOG](../CHANGELOG.md).

## From the focus-shift fork

[focus-shift/keycloak-event-listener-rabbitmq](https://github.com/focus-shift/keycloak-event-listener-rabbitmq) is
another fork of the same project. It uses the same provider id, settings, environment variables, Java class names and
routing keys, so moving between the two is a jar swap, with these differences:

| | focus-shift fork | This fork |
|---|---|---|
| Versions | follow Keycloak's version numbers (for example 26.8.1-1 for Keycloak 26.8.1) | one release line for Keycloak 26.0 and newer; the [compatibility table](../README.md#compatibility) lists the tested Keycloak versions |
| Admin event messages | include the `details` that Keycloak 26 adds to admin events | no `details`, as in upstream 3.0.5 |
| TLS without `trust_store` and `key_store` | certificate checks as in upstream 3.0.5 | the broker certificate is verified against the JVM default trust store |
| Empty `vhost` (the default) | sent to RabbitMQ as it is: `NOT_ALLOWED - vhost  not found`, unless the broker has a virtual host named `""`, which the listener then connects to | from 4.1.0 on, RabbitMQ's default virtual host `/` |

Steps:

1. If a consumer reads `details` from admin events, change it first: this fork does not publish that field.
2. Replace the jar and run `kc.sh build` if you start with `--optimized`.
3. With TLS and no `trust_store`, make sure the broker certificate is trusted, as in step 2 of the migration from
   upstream.
4. With an empty `vhost`, a setup that delivers events uses a virtual host named `""`, which 4.1.0 and later do not
   connect to: move the exchange and its queues to another virtual host and set `vhost` to its name, as in step 4 of
   the migration from upstream.
