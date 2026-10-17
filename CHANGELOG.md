# Changelog

All notable changes of this fork are documented here. Releases up to 3.0.5 are those of the upstream project
[aznamier/keycloak-event-listener-rabbitmq](https://github.com/aznamier/keycloak-event-listener-rabbitmq).

## Versioning

Releases follow [Semantic Versioning](https://semver.org/). The first fork release, `4.0.0-efim0v.1`, was a major
step from upstream `3.0.5` because it requires Keycloak 26 and verifies the TLS peer; its suffix marked it as a fork
build. From 4.1.0 on, releases are numbered `<major>.<minor>.<patch>`.

* A change of the message format (routing keys, exchange, properties, JSON fields) on any supported Keycloak
  version, or dropping support for a Keycloak version, requires a new major version.
* New features and changes in behaviour that keep the message format increase the minor version.
* Fixes, dependency updates and build changes increase the patch version.

## 4.1.0 - 2026-10-17

Publishing no longer fails Keycloak requests with HTTP 500 or makes them hang during a RabbitMQ alarm, the default
settings connect, and CI tests the jar on every Keycloak 26 line.

Upgrading from 4.0.0-efim0v.1:

* Replace the jar. No setting was added, renamed or given a new default, and the messages are the same: the contract
  tests pass unchanged, and the integration test passes on every Keycloak 26 line.
* `vhost` can stay empty: the listener then connects to RabbitMQ's default virtual host, `/`. `KK_TO_RMQ_VHOST=/`
  keeps working. An empty `vhost` no longer reaches a virtual host named `""`: RabbitMQ lets
  `rabbitmqctl add_vhost ""` create one, and 4.0.0-efim0v.1 connected to it when `vhost` was empty. If your events go
  to such a virtual host, move the exchange and its queues to another virtual host and set `vhost` to its name.
  Otherwise the events now go to `/`; with the default exchange, `amq.topic`, which exists in every virtual host,
  RabbitMQ accepts them there and the listener logs no error.
* A publish waits at most one second for the publishes before it. An event that has waited that long is dropped and
  logged instead of holding up its request, and while RabbitMQ blocks the connection because of a memory or disk
  alarm, events are dropped without being written. On a slow but working broker a burst can therefore lose events
  that 4.0.0-efim0v.1 delivered late. Delivery is at most once, as before.
* New log lines, in the category `com.github.aznamier.keycloak.event.provider`:
  * WARN `keycloak-to-rabbitmq: RabbitMQ blocked the connection (<reason>); events are dropped until it is unblocked`
    and INFO `keycloak-to-rabbitmq: RabbitMQ unblocked the connection`;
  * for each dropped event, ERROR `keycloak-to-rabbitmq ERROR sending message: <routing key>` followed by
    `(RabbitMQ blocks publishing: <reason>)` or `(earlier messages have not reached RabbitMQ within 1000 ms)`;
  * WARN `keycloak-to-rabbitmq: RabbitMQ refused an event and closed the channel: <reason>` when RabbitMQ refuses a
    message, for example `NOT_FOUND - no exchange 'keycloak.events' in vhost '/'`. It comes once per closed channel:
    that event is lost, and so are the events published on the channel after it;
  * ERROR `keycloak-to-rabbitmq ERROR sending message: no routing key for event <type> of realm <realm id>`, or
    `no routing key for admin event <operation> <resource type> of realm <realm id>`, when the listener cannot work
    out the routing key (the exception logged with the line says why); that event is dropped;
  * at shutdown, WARN `keycloak-to-rabbitmq: the connection to RabbitMQ did not close within 2000 ms` when closing
    takes longer.
* Not changed: until the first connection succeeds, each new Keycloak session still connects on its request thread,
  with the RabbitMQ Java client's timeouts (60 s to connect, 10 s for the handshake);
  [Effect on Keycloak requests](https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq/blob/master/docs/operations.md#effect-on-keycloak-requests)
  has the details.

### Fixed

* During a RabbitMQ memory or disk alarm, Keycloak requests that publish events (logins, token requests, admin
  changes) hung until the alarm cleared once the socket buffers were full, without a line in the log: RabbitMQ stops
  reading from publishing connections, the publish in progress blocked inside the shared channel, and the others
  waited behind it. Publishing on the shared channel now runs in a critical section, as the
  [RabbitMQ Java client guide](https://www.rabbitmq.com/client-libraries/java-api-guide#concurrency) requires, with
  the bounded wait described above, and the listener follows `connection.blocked` and `connection.unblocked`.
* When the broker closed the connection (a restart, a forced close), a request that came in while the RabbitMQ Java
  client was recovering it opened a second connection, and both stayed open. The listener now keeps its connection
  and lets the client recover it; requests no longer connect once the first connection is made.
* When the network stopped carrying the connection while a publish was stuck in a write, the connection was neither
  closed nor recovered until the operating system gave up on the write (15 minutes or more with Linux defaults). The
  RabbitMQ Java client now has a shutdown executor: it stops waiting for the channel after 1.05 heartbeat intervals,
  closes the socket and recovers the connection.
* Closing the listener when Keycloak stops could wait without limit for a write that RabbitMQ did not read. It now
  closes the connection on a thread of its own and stops waiting after 2 s. A request thread stuck in such a write
  can still delay Keycloak's shutdown.
* A connection failure while the listener opened its channel, for example `AlreadyClosedException`, failed the
  Keycloak request.
* A request during which Keycloak changed the realm was answered with HTTP 500 although it had succeeded, and its
  event was lost ([aznamier#58](https://github.com/aznamier/keycloak-event-listener-rabbitmq/issues/58)). One such
  request is the first token for a client that signs with ES512 in a realm without an ES512 key, for which Keycloak
  creates the key. The listener looked up the realm name for the routing key after Keycloak's transaction had
  completed, and Keycloak's realm cache refused to read the changed realm
  (`IllegalStateException: Cannot access delegate without a transaction`). The routing key is now worked out while
  the transaction is active, and an error while building or publishing a message is logged instead of failing the
  request.
* With the default, empty `vhost`, the listener did not connect: unless a virtual host named `""` had been created,
  RabbitMQ refused the connection with `530 NOT_ALLOWED - vhost  not found`. An empty `vhost` now means RabbitMQ's
  default virtual host, `/`; see Upgrading if your broker has a virtual host named `""`.
* An event that RabbitMQ refused because the exchange did not exist was lost without a line in the log. The listener
  now logs a warning with RabbitMQ's reason.

### Security

* Build tooling: `cyclonedx-maven-plugin` 2.9.3, its latest release, depends on `cyclonedx-core-java` 9.0.5
  (GHSA-6fhj-vr9j-g45r) and on Jackson 2.17.2, affected by GHSA-72hv-8253-57qq, GHSA-7hhh-6rmp-j9qf,
  GHSA-p6pp-m3f8-5c89 and GHSA-r7wm-3cxj-wff9 (`jackson-core`) and GHSA-3pjw-73gf-8qr5, GHSA-5jmj-h7xm-6q6v,
  GHSA-cxp5-3px4-pw24, GHSA-gx83-3vf8-gh7j, GHSA-hgj6-7826-r7m5, GHSA-j3rv-43j4-c7qm, GHSA-q4xh-88c3-wmh7,
  GHSA-rmj7-2vxq-3g9f, GHSA-vvgp-rfg2-7rr6, GHSA-wjgm-6hv5-3cvf and GHSA-wv8q-qhhj-9h54 (`jackson-databind`). They
  are overridden at plugin level with `cyclonedx-core-java` 11.0.1 and Jackson 2.18.11. None of them is part of the
  jar.

### Changed

* The repository moved to the Mizzle-Studio organisation:
  https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq. GitHub redirects the old address. The Maven
  coordinates are now `io.github.mizzle-studio:keycloak-to-rabbit`.
* CI builds the jar once and runs the integration test with that jar on every Keycloak 26 line at its latest patch
  release (26.0 to 26.7), on the Keycloak version in `pom.xml` (26.8.1), and on 26.8.1 with RabbitMQ 3.13. A leg
  fails if the integration test was skipped.
* The jar contains `META-INF/License.md` and `META-INF/NOTICE`, and its manifest has `Implementation-Title` and
  `Implementation-Version`.
* Releases also carry a CycloneDX SBOM (`keycloak-to-rabbit-<version>.cdx.json`), a signed build provenance
  attestation for the jar and the SBOM, and an SBOM attestation for the jar:
  `gh attestation verify keycloak-to-rabbit-<version>.jar --repo Mizzle-Studio/keycloak-event-listener-rabbitmq`. The
  release notes list the Keycloak and RabbitMQ versions the integration test passed on.
* Dependabot puts the Keycloak artifacts in one group, so that a Keycloak release arrives as one pull request.

### Added

* The provider image `ghcr.io/mizzle-studio/keycloak-to-rabbit:<version>` for linux/amd64 and linux/arm64, with the
  jar at `/opt/keycloak/providers/keycloak-to-rabbit.jar` and a build provenance attestation, for custom Keycloak
  images (`COPY --from`) and Kubernetes init containers.
* A weekly "Keycloak latest" workflow runs the integration test of the latest release and of `master` on
  `quay.io/keycloak/keycloak:latest`. A failure opens an issue labelled `keycloak-latest`.
* A Docker Compose quick start in
  [examples/docker-compose](https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq/tree/master/examples/docker-compose),
  and guides in [docs](https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq/tree/master/docs): installation
  (container image, Keycloak Operator, Helm), configuration, message format, consumers (Spring Boot, Node.js,
  Python), migration and operations.
* CONTRIBUTING.md, SUPPORT.md, CODE_OF_CONDUCT.md (the Contributor Covenant 2.1), issue forms and a pull request
  template.

## 4.0.0-efim0v.1 - 2026-10-15

First release of the fork.

Upgrading from upstream 3.0.5:

* Keycloak 26 is required.
* With TLS enabled the broker certificate and host name are verified, and a failed TLS setup no longer falls
  back to plain text (see Security). A broker certificate that the JVM default trust store does not accept
  needs a `trust_store`.
* The messages are the same as those of upstream 3.0.5 on the same Keycloak version: the contract tests written
  against upstream 3.0.5 pass, and the integration test, which upstream 3.0.5 also passes, produces messages
  with the same routing keys, properties and JSON fields on Keycloak 26.0 and 26.8.1. Admin events carry
  `resourceId` on Keycloak 26.4 and later, where Keycloak computes it, as with upstream 3.0.5.

### Security

* `amqp-client` 5.14.2 -> 5.37.0, which fixes:
  GHSA-mm8h-8587-p46h (CVE-2023-46120),
  GHSA-qx7j-jv8m-fppr (CVE-2026-63335),
  GHSA-5m9f-rphj-c435 (CVE-2026-63336),
  GHSA-5xwg-cfvj-gff5 (CVE-2026-61634),
  GHSA-6g32-pxv4-2wfj (CVE-2026-63337),
  GHSA-68mj-5wr7-6fgg (CVE-2026-69219),
  GHSA-93j5-89vc-pph4 (CVE-2026-69220),
  GHSA-jh4v-gfqj-7rhx (CVE-2026-75516),
  GHSA-h6w7-qmcm-q6xr (CVE-2026-106123),
  GHSA-7822-rcf6-97fx (CVE-2026-106122),
  GHSA-cqgh-8p3p-mx4m (CVE-2026-106121).
* TLS: with `use_tls=true` and no trust store the listener used to accept any broker certificate
  (`ConnectionFactory#useSslProtocol()`, see GHSA-5m9f-rphj-c435). The certificate is now verified against the
  configured trust store or the JVM default trust store, and the broker host name is verified.
* TLS: when the TLS setup failed the listener connected in plain text, sending the credentials unencrypted. It
  now logs the error and does not connect.
* The key store and trust store passwords were written to the log at startup. All passwords are now logged as
  `<hidden>` / `<empty>`.
* The provided Keycloak dependencies used for compilation (21.1.2, with many advisories in `keycloak-services`
  and its transitive dependencies) are replaced by the three Keycloak 26.8.1 artifacts the listener needs, which
  have no known advisories. These were never part of the jar.
* Build tooling: Maven 3.9.3 -> 3.10.0 with a pinned distribution checksum, Maven wrapper 3.2.0 -> 3.3.4
  (script only, no binary wrapper jar), all Maven plugins pinned to their latest releases. Libraries with
  advisories that the latest plugin releases still use are overridden: `plexus-utils` 4.0.3 / 3.6.2
  (GHSA-6fmv-xxpf-w3cw), `plexus-archiver` 4.14.0 (drops `aircompressor` 0.27, GHSA-vx9q-rhv9-3jvg),
  `commons-beanutils` 1.11.0 (GHSA-wxr5-93ph-8wr9), `jsoup` 1.23.2 (GHSA-65r4-943x-97jj, GHSA-pmhh-3w7g-xqp8).
* Known and not fixable here: `maven-site-plugin` 3.22.0 (the latest release) depends on Jetty 9.4.58, affected by
  GHSA-355h-qmc2-wpwf, GHSA-qh8g-58pp-2wxh, GHSA-2fvj-hgj9-j2gr and GHSA-7p3p-8qv8-m2vh. Jetty 9.4 is end of
  life and no fixed 9.4 release is published on Maven Central. The site plugin is not run by this project's build
  or CI (Jetty is only used by `site:run`), and none of it ends up in the provider jar.

### Changed

* Built against Keycloak 26.8.1 (`keycloak-core`, `keycloak-server-spi`, `keycloak-server-spi-private`, provided)
  and compiled for Java 17, the bytecode level of the Keycloak 26 SPI. Requires Keycloak 26.
* The JSON body is restricted to the fields upstream 3.0.5 publishes on Keycloak 26 (`@JsonIncludeProperties`):
  the fields the listener copies from the event, plus `resourceId`, which Keycloak 26.4 and later computes for
  admin events. Fields that future Keycloak versions add to their event classes stay out of the message.
* SLF4J and Netty are no longer bundled in the jar: Keycloak ships both, and `amqp-client` needs Netty only for
  its optional Netty IO layer, which the listener does not use.
* TLS negotiates TLS 1.3 or 1.2 instead of pinning TLS 1.2, and uses the default trust and key manager
  algorithms.
* Maven coordinates changed to `io.github.efim0v:keycloak-to-rabbit`. Java package and class names are kept
  because they appear in the messages.
* `RabbitMqConfig#getKeyStorePass()` added; the misspelled `getKeytStorePass()` is deprecated.

### Fixed

* Closing the provider factory when the broker had never been reached threw a `NullPointerException`.
* A missing broker connection is logged as such instead of as a `NullPointerException` per event.
* Key store files are closed after reading.

### Removed

* Support for WildFly-based Keycloak (16 and older): the `utils/cli` scripts and the `standalone.xml`
  instructions.
* The `@XmlRootElement` (`javax.xml.bind`) annotations; Jackson ignores them and `javax.xml.bind` is not
  available in Keycloak 26.
* The upstream GitHub Packages publishing workflow and `distributionManagement`.

### Added

* Contract tests for the message format, routing keys, message properties, configuration and broker failure
  handling, written against upstream 3.0.5 before any change.
* An integration test that runs Keycloak and RabbitMQ in containers (Testcontainers) with the built jar.
* GitHub Actions CI (build, unit and integration tests on the target Keycloak version and on the latest 26.0.x,
  OSV-Scanner on the POM and the built jar) and Dependabot for Maven and GitHub Actions.
* Releases are built and published by CI from a pushed tag, with a SHA-256 checksum file; the procedure is
  described under "Releases" in [CONTRIBUTING.md](CONTRIBUTING.md).
* [SECURITY.md](SECURITY.md) with the vulnerability reporting process.
