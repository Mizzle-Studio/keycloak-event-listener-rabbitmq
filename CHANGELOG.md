# Changelog

All notable changes of this fork are documented here. Releases up to 3.0.5 are those of the upstream project
[aznamier/keycloak-event-listener-rabbitmq](https://github.com/aznamier/keycloak-event-listener-rabbitmq).

## Versioning

Fork releases are numbered `<upstream base>-efim0v.<n>`:

* `<upstream base>` follows Semantic Versioning relative to the upstream release line. The first fork release
  is `4.0.0`, a major step from upstream `3.0.5`, because it is not a drop-in replacement in every setup: it
  requires Keycloak 26, and on Keycloak 26.4 and later its admin events lack the `resourceId` field that
  upstream `3.0.5` publishes there (see below).
* `-efim0v` marks the build as coming from this fork (`github.com/efim0v`) and not from upstream, so the jar
  name alone tells which line it belongs to and cannot be mistaken for an upstream release.
* `.<n>` counts fork releases on the same base, starting at 1. Changes that only touch dependencies,
  tests or the build increase `<n>`; listener changes increase the base version and reset `<n>` to 1.

A change of the message format (routing keys, exchange, properties, JSON fields) on any supported Keycloak
version, or dropping support for a Keycloak version, requires a new major version.

## 4.0.0-efim0v.1 - 2026-10-15

First release of the fork.

Upgrading from upstream 3.0.5:

* Keycloak 26 is required.
* On Keycloak 26.0 to 26.3 the messages are the same as those of upstream 3.0.5: the contract tests written
  against upstream 3.0.5 pass without modification, and the integration test produces messages with the same
  routing keys, properties and JSON fields as upstream 3.0.5 on Keycloak 26.0.
* On Keycloak 26.4 and later upstream 3.0.5 also publishes `resourceId` in admin events, a property Keycloak
  computes as the last segment of `resourcePath`. This fork does not publish it on any Keycloak version.
  Consumers that read `resourceId` should take the last segment of `resourcePath` instead.

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
* The JSON body is restricted to the fields the listener copies from the event (`@JsonIncludeProperties`), so
  it no longer depends on the Keycloak version. Upstream 3.0.5 serialises the Keycloak event classes as they
  are, so on Keycloak 26.4 and later it also publishes `resourceId` in admin events (see above).
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
  described under "Releases" in [README.md](README.md).
* [SECURITY.md](SECURITY.md) with the vulnerability reporting process.
