# keycloak-event-listener-rabbitmq

A Keycloak event listener (SPI provider) that publishes Keycloak user events and admin events to RabbitMQ.

## About this fork

This is a maintained fork of [aznamier/keycloak-event-listener-rabbitmq](https://github.com/aznamier/keycloak-event-listener-rabbitmq).
The upstream project has had no release since 3.0.5 (March 2024). That release bundles
`amqp-client` 5.14.2, which has a number of published advisories, and is built against Keycloak 21.

This fork keeps the plugin usable on current Keycloak releases and free of known vulnerabilities, and keeps
the message format of upstream 3.0.5: on the same Keycloak version, routing keys, exchange, message
properties and JSON bodies are the same as those of upstream 3.0.5. The message contract is pinned by tests
(see [Building](#building)).

### Differences from upstream 3.0.5

- Built and integration-tested against Keycloak 26 (Java 17 bytecode); `amqp-client` 5.37.0. See
  [CHANGELOG.md](CHANGELOG.md) for the advisories this closes.
- The JSON body contains at most the fields listed under [Message format](#message-format), the fields upstream
  3.0.5 publishes on Keycloak 26. Upstream serialises the Keycloak event classes as they are, so fields a
  future Keycloak version adds to them would appear in its messages; they do not appear in the fork's.
- TLS connections verify the broker certificate and host name. Upstream trusts any certificate when TLS is
  enabled without a trust store, and silently falls back to a plain-text connection when the TLS setup fails.
- Passwords of the key store and trust store are no longer written to the log.
- SLF4J and Netty are not bundled (Keycloak provides them); the jar contains the listener and `amqp-client` only.
- Support for WildFly-based Keycloak (16 and older) and its `standalone.xml` / CLI configuration was removed.
- Maven coordinates are `io.github.efim0v:keycloak-to-rabbit`. The Java package and class names are
  unchanged because they are part of the message (`@class` field and `__TypeId__` header).

### Upgrading from upstream 3.0.5

The first release of the fork is numbered 4.0.0 because it is not a drop-in replacement in every setup:

- It requires Keycloak 26.
- With TLS enabled the broker certificate and host name are verified, and a failed TLS setup no longer falls
  back to plain text. A broker certificate that the JVM default trust store does not accept needs a
  `trust_store` (see [TLS](#tls)).

The messages are the same as those of upstream 3.0.5 on the same Keycloak version.

## Compatibility

| Release            | Keycloak                                                  | Java (runtime)               |
|--------------------|-----------------------------------------------------------|------------------------------|
| 4.0.0-efim0v.1     | 26.0 or newer 26.x; built against 26.8.1. CI runs the integration test on 26.8.1 and on the latest 26.0.x | 17 or newer |
| upstream 3.0.5     | 16 or newer according to upstream (WildFly and Quarkus distributions); also runs on 26.x | 8 or newer |

## Message format

For example, a user updated by an administrator:

* routing key: `KK.EVENT.ADMIN.MYREALM.SUCCESS.USER.UPDATE`
* published to exchange: `amq.topic` (configurable)
* properties: `app_id=Keycloak`, `content_type=application/json`, `content_encoding=UTF-8`,
  header `__TypeId__` = the value of `@class`; no other properties are set (messages are not persistent)
* body (pretty-printed JSON, UTF-8):

```json
{
  "@class" : "com.github.aznamier.keycloak.event.provider.EventAdminNotificationMqMsg",
  "time" : 1596951200408,
  "realmId" : "5f3a2c4e-0000-0000-0000-000000000000",
  "authDetails" : {
    "realmId" : "8b2c1d9a-0000-0000-0000-000000000000",
    "realmName" : "master",
    "clientId" : "1e6d8f0b-0000-0000-0000-000000000000",
    "userId" : "c0a8e7f2-0000-0000-0000-000000000000",
    "ipAddress" : "192.0.2.10"
  },
  "resourceType" : "USER",
  "operationType" : "UPDATE",
  "resourcePath" : "users/9d4b7a61-0000-0000-0000-000000000000",
  "representation" : "{\"id\":\"9d4b7a61-0000-0000-0000-000000000000\",\"username\":\"jdoe\", ...}",
  "resourceTypeAsString" : "USER",
  "resourceId" : "9d4b7a61-0000-0000-0000-000000000000"
}
```

A user event (here a login):

```json
{
  "@class" : "com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg",
  "time" : 1596951200408,
  "type" : "LOGIN",
  "realmId" : "5f3a2c4e-0000-0000-0000-000000000000",
  "clientId" : "my-client",
  "userId" : "9d4b7a61-0000-0000-0000-000000000000",
  "sessionId" : "a1b2c3d4-0000-0000-0000-000000000000",
  "ipAddress" : "192.0.2.10",
  "details" : {
    "auth_method" : "openid-connect",
    "username" : "jdoe"
  }
}
```

Fields:

* user events: `time`, `type`, `realmId`, `clientId`, `userId`, `sessionId`, `ipAddress`, `error`, `details`
* admin events: `time`, `realmId`, `authDetails`, `resourceType`, `operationType`, `resourcePath`,
  `representation`, `error`, `resourceTypeAsString`, `resourceId`

`resourceId` is the last segment of `resourcePath` (for example the user id in `users/<id>`). Keycloak computes
it since 26.4; on Keycloak 26.0 to 26.3 admin events do not carry it, as with upstream 3.0.5.

Fields without a value are omitted. The event id and realm name that newer Keycloak versions add to events are
not part of the message. `authDetails` is written as Keycloak provides it. Admin events carry the
`representation` Keycloak passes to event listeners; the realm's "Include representation" setting is not applied.

The routing key is calculated as follows:
* admin events: `KK.EVENT.ADMIN.<REALM>.<RESULT>.<RESOURCE_TYPE>.<OPERATION>`
* user events: `KK.EVENT.CLIENT.<REALM>.<RESULT>.<CLIENT>.<EVENT_TYPE>`

where `<REALM>` is the realm name (not its id), `<RESULT>` is `SUCCESS` or `ERROR`, and `<REALM>` and `<CLIENT>`
have their dots removed. In the whole key, spaces become underscores and characters other than
`a-z A-Z 0-9 _ - . * #` are removed.

The listener does not declare the exchange; it must exist. With a **topic** exchange (such as `amq.topic`)
consumers can subscribe to selected combinations, for example:
* all events: `KK.EVENT.#`
* all events from my realm: `KK.EVENT.*.MYREALM.#`
* all error events from my realm: `KK.EVENT.*.MYREALM.ERROR.#`
* all user events of my realm and my client: `KK.EVENT.CLIENT.MYREALM.*.MYCLIENT.*`

## Installation

1. Download `keycloak-to-rabbit-<version>.jar` and `keycloak-to-rabbit.sha256` from the
   [releases](https://github.com/efim0v/keycloak-event-listener-rabbitmq/releases) page and check the jar
   with `sha256sum --check keycloak-to-rabbit.sha256`, or build it from source (see below).
2. Copy the jar to `/opt/keycloak/providers/`. Run `kc.sh build` if you start Keycloak with
   `--optimized`; a plain `kc.sh start` picks the provider up by itself.
3. Configure the connection (see below).
4. Restart Keycloak.
5. Enable the listener in each realm: **Realm settings > Events > Event listeners**, add
   `keycloak-to-rabbitmq`.

## Configuration

Each setting is read from the Keycloak SPI configuration of the provider first (option
`spi-events-listener-keycloak-to-rabbitmq-<name>`, for example
`--spi-events-listener-keycloak-to-rabbitmq-url=broker.example`; see the Keycloak guide on configuring
providers for the option syntax of your version), then from the environment variable `KK_TO_RMQ_<NAME>`,
then the default applies.

| Name               | Environment variable          | Default     |
|--------------------|-------------------------------|-------------|
| `url`              | `KK_TO_RMQ_URL`               | `localhost` |
| `port`             | `KK_TO_RMQ_PORT`              | `5672`      |
| `vhost`            | `KK_TO_RMQ_VHOST`             | empty       |
| `exchange`         | `KK_TO_RMQ_EXCHANGE`          | `amq.topic` |
| `username`         | `KK_TO_RMQ_USERNAME`          | `admin`     |
| `password`         | `KK_TO_RMQ_PASSWORD`          | `admin`     |
| `use_tls`          | `KK_TO_RMQ_USE_TLS`           | `false`     |
| `key_store`        | `KK_TO_RMQ_KEY_STORE`         | empty       |
| `key_store_pass`   | `KK_TO_RMQ_KEY_STORE_PASS`    | empty       |
| `trust_store`      | `KK_TO_RMQ_TRUST_STORE`       | empty       |
| `trust_store_pass` | `KK_TO_RMQ_TRUST_STORE_PASS`  | empty       |

The settings are logged at startup; the three passwords are shown as `<hidden>` or `<empty>`.

### TLS

With `use_tls=true`:

* the broker certificate is verified against `trust_store` (JKS or PKCS12), or against the JVM default trust
  store when `trust_store` is empty;
* the broker host name (`url`) must match the certificate;
* `key_store` (PKCS12), when set, provides a client certificate;
* if the TLS setup fails (for example the trust store cannot be read), the error is logged and the listener does
  not connect at all; it never falls back to plain text.

### Behaviour when RabbitMQ is unavailable

Events are published after the Keycloak transaction completes. If the broker cannot be reached, the error is
logged, the event is dropped, and Keycloak continues normally; the connection is retried for the next Keycloak
session. An established connection recovers automatically.

## Building

Requirements: JDK 17 or newer; Docker for the integration tests.

```sh
./mvnw -B clean verify
```

This runs the unit tests (including the message contract tests), then the integration test, which starts
Keycloak (the version in `pom.xml`) with the built jar and RabbitMQ in containers and checks the messages for a
login, a failed login and an administrator updating and deleting a user. Add `-DskipITs` to skip it. The jar is
written to `target/keycloak-to-rabbit-<version>.jar`.

The integration test can be pointed at another Keycloak image or another build of the listener:
`-Dit.keycloak.image=quay.io/keycloak/keycloak:<version>`, `-Dit.provider.jar=<path to jar>`. CI runs it on the
Keycloak version in `pom.xml` and on the latest 26.0.x release, the oldest supported line.

## Releases

Releases are built by CI, not on a developer machine. Pushing a tag `v<version>` (matching the version in
`pom.xml`) runs the CI workflow, which:

1. builds the jar with Temurin JDK 21 and `./mvnw --batch-mode clean verify` (unit and integration tests);
2. writes its SHA-256 checksum with `sha256sum` to `keycloak-to-rabbit.sha256`;
3. scans `pom.xml` and the jar with OSV-Scanner and stops on known vulnerabilities;
4. creates the GitHub release for the tag with the jar, the checksum file and the release's section of
   [CHANGELOG.md](CHANGELOG.md) as notes.

The build is reproducible: `project.build.outputTimestamp` in `pom.xml` fixes the timestamps in the jar, so a
build of the tag with the same JDK (Temurin 21) produces the same checksum. Builds with other JDK versions
may differ in the compiled classes.

## Security

See [SECURITY.md](SECURITY.md) for how to report a vulnerability.

## License

Apache License 2.0, see [License.md](License.md) and [NOTICE](NOTICE).
