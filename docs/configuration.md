# Configuration

The listener has eleven settings. It reads them once, when Keycloak starts, so a change needs a restart.

- [Settings](#settings)
- [Where a setting comes from](#where-a-setting-comes-from)
- [Ways to set an option](#ways-to-set-an-option)
- [TLS and client certificates](#tls-and-client-certificates)
- [Enabling the listener in a realm](#enabling-the-listener-in-a-realm)
- [Examples](#examples)
- [Checklist: nothing arrives in RabbitMQ](#checklist-nothing-arrives-in-rabbitmq)

Identifiers that never change (they are part of the message contract with upstream 3.0.5):

| What | Value |
|---|---|
| Event listener name (provider id) | `keycloak-to-rabbitmq` |
| SPI | `eventsListener`, option prefix `spi-events-listener` |
| Factory class | `com.github.aznamier.keycloak.event.provider.RabbitMqEventListenerProviderFactory` |

## Settings

| Name | Environment variable | Default | Meaning |
|---|---|---|---|
| `url` | `KK_TO_RMQ_URL` | `localhost` | Broker host name or IP address. Not an AMQP URI: no scheme, port or credentials. With TLS it is also the name checked against the broker certificate. |
| `port` | `KK_TO_RMQ_PORT` | `5672` | Broker port. It does not change when TLS is on: set the TLS port (usually `5671`) yourself. A value that is not a number stops Keycloak at startup with `ERROR: For input string: "…"`. |
| `vhost` | `KK_TO_RMQ_VHOST` | empty | Virtual host. **Set `/` for RabbitMQ's default virtual host.** <!-- behaviour:vhost -->The empty default is sent to RabbitMQ as it is, and RabbitMQ refuses the connection with `NOT_ALLOWED - vhost  not found`.<!-- /behaviour:vhost --> |
| `exchange` | `KK_TO_RMQ_EXCHANGE` | `amq.topic` | Exchange the messages are published to. The listener does not declare it, so it must exist. A topic exchange lets consumers filter by routing key. |
| `username` | `KK_TO_RMQ_USERNAME` | `admin` | Broker user. It needs write permission on the exchange and nothing else (see the [quick start definitions](../examples/docker-compose/rabbitmq/definitions.json)). |
| `password` | `KK_TO_RMQ_PASSWORD` | `admin` | Broker password. Never logged. |
| `use_tls` | `KK_TO_RMQ_USE_TLS` | `false` | Only `true` (in any letter case) turns TLS on. Every other value, such as `yes`, `1` or `on`, means plain AMQP. |
| `trust_store` | `KK_TO_RMQ_TRUST_STORE` | empty | Path of a trust store (PKCS12 or JKS) with the CA that signed the broker certificate. Empty: the JVM default trust store, which includes Keycloak's own trusted certificates (see [TLS](#tls-and-client-certificates)). |
| `trust_store_pass` | `KK_TO_RMQ_TRUST_STORE_PASS` | empty | Trust store password. Never logged. |
| `key_store` | `KK_TO_RMQ_KEY_STORE` | empty | Path of a PKCS12 key store with a client certificate and its key, for brokers that require client certificates. |
| `key_store_pass` | `KK_TO_RMQ_KEY_STORE_PASS` | empty | Key store password, also used as the password of the key inside it. Never logged. |

<!-- behaviour:options -->
Not configurable in this release: connection and handshake timeouts (the RabbitMQ Java client defaults apply: 60 s to
connect, 10 s for the AMQP handshake), publisher confirms, message persistence or priority, declaring the exchange,
filtering events, OAuth 2 credentials for the broker.
<!-- /behaviour:options -->

## Where a setting comes from

For each setting the listener takes the first value it finds:

1. the Keycloak provider configuration (`spi-events-listener-keycloak-to-rabbitmq-<name>`, in any of the forms below);
2. the environment variable `KK_TO_RMQ_<NAME>` of the Keycloak process;
3. the default.

At startup the listener logs every value it uses. This is the quickest way to see whether an option reached it:

```text
INFO  [com.github.aznamier.keycloak.event.provider.RabbitMqConfig] (main) keycloak-to-rabbitmq configuration: url=rabbitmq
INFO  [com.github.aznamier.keycloak.event.provider.RabbitMqConfig] (main) keycloak-to-rabbitmq configuration: vhost=/
INFO  [com.github.aznamier.keycloak.event.provider.RabbitMqConfig] (main) keycloak-to-rabbitmq configuration: password=<hidden>
```

Passwords are printed as `<hidden>` when set and `<empty>` when not.

## Ways to set an option

Write the setting name exactly as in the table, underscores included: `use_tls`, not `use-tls`.

| Form | Example | Keycloak 26.0.8 | Keycloak 26.8.1 |
|---|---|---|---|
| The listener's own variable | `KK_TO_RMQ_USE_TLS=true` | works | works |
| Command line | `--spi-events-listener-keycloak-to-rabbitmq-use_tls=true` | works | works |
| `conf/keycloak.conf` | `spi-events-listener-keycloak-to-rabbitmq-trust_store=/opt/keycloak/conf/rabbitmq-trust.p12` | works | works |
| `KC_` variable, setting name without `_` | `KC_SPI_EVENTS_LISTENER_KEYCLOAK_TO_RABBITMQ_VHOST=/` | works | works |
| `KC_` variable, setting name with `_` | `KC_SPI_EVENTS_LISTENER_KEYCLOAK_TO_RABBITMQ_TRUST_STORE_PASS=…` | works | **ignored** |
| `KC_` variable with a `KCKEY_` variable that names the option | `KC_SPI_…_KEY_STORE=…` and `KCKEY_SPI_…_KEY_STORE=spi-events-listener-keycloak-to-rabbitmq-key_store` | works | works |
| Command line, double-dash form | `--spi-events-listener--keycloak-to-rabbitmq--port=5672` | **ignored** | works |

"Ignored" means that Keycloak starts normally and the listener gets the default, with nothing in the log except the
listener's own `configuration:` line. Each row was checked by starting the official Keycloak images with the listener
and reading those lines.

Why the `KC_` variables fail for `use_tls`, `trust_store`, `trust_store_pass`, `key_store` and `key_store_pass` on
newer Keycloak versions: Keycloak turns a `KC_` variable name into an option name by lower-casing it and replacing
every `_` with `-`, so the listener's `trust_store` becomes `trust-store`. Keycloak documents the `KCKEY_` pair for
such names in [Configuring Keycloak](https://www.keycloak.org/server/configuration) ("Formats for environment variable
keys with special characters"). The Keycloak Operator writes that pair for `additionalOptions` since 26.3.

**Recommendation:** use the `KK_TO_RMQ_*` variables, or the command line and `keycloak.conf` forms. They behave the
same on every Keycloak 26 release.

## TLS and client certificates

With `use_tls=true` the listener:

- verifies the broker certificate and checks that it was issued for the host in `url`;
- negotiates TLS 1.3 or 1.2, as the JVM allows;
- sends a client certificate when `key_store` is set (mutual TLS);
- logs `could not set up TLS; events will not be sent to RabbitMQ` at startup when a store cannot be read, and then
  never connects. It never falls back to plain AMQP.

Authentication to RabbitMQ is always by user name and password (SASL `PLAIN`), also with a client certificate.

**Trusting a private CA.** Two ways, both checked on Keycloak 26.0.8 and 26.8.1 against RabbitMQ with
`ssl_options.verify = verify_peer` and `ssl_options.fail_if_no_peer_cert = true`:

1. Add the CA certificate to Keycloak's own trusted certificates: put the PEM file into `conf/truststores/` or point
   [`--truststore-paths`](https://www.keycloak.org/server/keycloak-truststore) at it. Keycloak installs these
   certificates as the JVM default trust store, which the listener uses when `trust_store` is empty. With the Keycloak
   Operator, `spec.truststores` mounts Secrets into the same directory.
2. Give the listener its own trust store:

   ```sh
   keytool -importcert -noprompt -alias rabbitmq-ca -file ca.pem \
     -storetype PKCS12 -keystore rabbitmq-trust.p12 -storepass change-me
   ```

   and set `trust_store=/opt/keycloak/conf/rabbitmq-trust.p12` and `trust_store_pass=change-me`.

**Client certificate.** Package the certificate and its key as PKCS12 with one password for the store and the key:

```sh
openssl pkcs12 -export -in client.pem -inkey client.key -name keycloak \
  -out rabbitmq-client.p12 -passout pass:change-me
```

and set `key_store=/opt/keycloak/conf/rabbitmq-client.p12` and `key_store_pass=change-me`.

## Enabling the listener in a realm

Configuration alone publishes nothing: add `keycloak-to-rabbitmq` to the event listeners of every realm whose events
should reach RabbitMQ.

- **Admin console:** Realm settings > Events > Event listeners, add `keycloak-to-rabbitmq`, Save.
- **kcadm.sh:** the realm attribute `eventsListeners` replaces the whole list, so keep `jboss-logging` if you use it:

  ```sh
  kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin
  kcadm.sh update realms/myrealm -s 'eventsListeners=["jboss-logging","keycloak-to-rabbitmq"]'
  ```

- **Admin REST API:** `PUT /admin/realms/myrealm` with the body `{"eventsListeners":["jboss-logging","keycloak-to-rabbitmq"]}`.
- **Realm import:** `"eventsListeners": ["jboss-logging", "keycloak-to-rabbitmq"]` in the realm JSON, as in the
  [quick start realm](../examples/docker-compose/keycloak/demo-realm.json).

The realm's "Save events", "Saved types" and "Save admin events" settings control only Keycloak's own event store. The
listener receives every user event and every admin event of the realm either way.

At every start Keycloak logs `KC-SERVICES0047: keycloak-to-rabbitmq (…) is implementing the internal SPI
eventsListener. This SPI is internal and may change without notice`. Keycloak prints it for every provider of an
internal SPI that does not ship with Keycloak; it is expected.

## Examples

Container, all settings as the listener's variables (the form used by the integration test and the quick start):

```sh
docker run -p 8080:8080 \
  -v "$PWD/keycloak-to-rabbit-4.1.0.jar:/opt/keycloak/providers/keycloak-to-rabbit.jar:ro" \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=change-me \
  -e KK_TO_RMQ_URL=rabbitmq.internal \
  -e KK_TO_RMQ_VHOST=/ \
  -e KK_TO_RMQ_USERNAME=keycloak \
  -e KK_TO_RMQ_PASSWORD=change-me \
  quay.io/keycloak/keycloak:26.8.1 start-dev
```

Command line, TLS with the listener's own trust store (the password is better kept in `KK_TO_RMQ_PASSWORD` than on
the command line):

```sh
bin/kc.sh start \
  --spi-events-listener-keycloak-to-rabbitmq-url=rabbitmq.internal \
  --spi-events-listener-keycloak-to-rabbitmq-port=5671 \
  --spi-events-listener-keycloak-to-rabbitmq-vhost=/ \
  --spi-events-listener-keycloak-to-rabbitmq-username=keycloak \
  --spi-events-listener-keycloak-to-rabbitmq-use_tls=true \
  --spi-events-listener-keycloak-to-rabbitmq-trust_store=/opt/keycloak/conf/rabbitmq-trust.p12 \
  --spi-events-listener-keycloak-to-rabbitmq-trust_store_pass=change-me
```

`conf/keycloak.conf`:

```properties
spi-events-listener-keycloak-to-rabbitmq-url=rabbitmq.internal
spi-events-listener-keycloak-to-rabbitmq-vhost=/
spi-events-listener-keycloak-to-rabbitmq-exchange=keycloak.events
```

Kubernetes and the Keycloak Operator: see [Installation](installation.md#kubernetes).

## Checklist: nothing arrives in RabbitMQ

1. The listener's `configuration:` lines in the Keycloak log show the values you expect. A form that Keycloak ignores
   leaves the default.
2. `vhost` is set; `/` is RabbitMQ's default virtual host.
3. `url` is reachable from Keycloak. In Docker Compose and Kubernetes that is the service name, not `localhost`.
4. The exchange exists, and a queue is bound to it with a matching key, for example `KK.EVENT.#`. RabbitMQ drops
   messages that match no binding.
5. `keycloak-to-rabbitmq` is in the realm's event listeners.
6. With TLS: `port` is the TLS port, `use_tls` is exactly `true`, the broker certificate matches `url`, and its CA is
   trusted.
7. The Keycloak log has no `keycloak-to-rabbitmq ERROR` lines. They name the routing key of every event that could not
   be sent.
