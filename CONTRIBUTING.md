# Contributing

Thank you for helping with this Keycloak event listener. Small, focused pull requests are the easiest to review.

* Questions and ideas: [Discussions](https://github.com/efim0v/keycloak-event-listener-rabbitmq/discussions)
  (category Q&A). See [SUPPORT.md](SUPPORT.md) for what is supported.
* Bugs: open an issue with the bug report form; it asks for the versions and the configuration needed to
  reproduce the problem.
* Vulnerabilities: never in a public issue; see [SECURITY.md](SECURITY.md).

Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).

## Building and testing

Requirements: JDK 17 or newer, `unzip` (the Maven wrapper checks the pinned checksum of the Maven zip it
downloads, and without `unzip` it fetches the tar.gz, which fails that check), and Docker for the integration
test.

```sh
./mvnw -B -ntp verify
```

This runs the unit tests, including the message contract tests, builds the provider jar
`target/keycloak-to-rabbit-<version>.jar` (the listener and `amqp-client`) with its CycloneDX SBOM
`target/keycloak-to-rabbit-<version>.cdx.json`, and then runs the integration test: Keycloak (the version in
`pom.xml`) with the built jar and RabbitMQ in containers, checking the messages for a login, a failed login and
an administrator updating and deleting a user. `-DskipITs` skips the integration test.

The integration test takes other images or another build of the listener:

```sh
./mvnw -B -ntp verify -Dit.keycloak.image=quay.io/keycloak/keycloak:26.4
./mvnw -B -ntp verify -Dit.rabbitmq.image=rabbitmq:3.13-alpine
# only the integration test, with a jar built elsewhere
./mvnw -B -ntp test-compile failsafe:integration-test failsafe:verify -Dit.provider.jar=/path/to/keycloak-to-rabbit-<version>.jar
```

## The message contract

Consumers parse the messages this listener publishes, so their format is a contract: it stays compatible with
upstream release 3.0.5.

* The Java package and class names (`com.github.aznamier.keycloak.event.provider.*`), the provider id
  `keycloak-to-rabbitmq`, the routing keys, the JSON fields, and the AMQP properties and headers do not change.
* The contract tests (`*ContractTest`, `MessagePropertiesContract`, `ContractFixtures`) pin that format. A change
  must keep them passing without editing them. If you think a contract test is wrong, open an issue first.
* New fields, properties or headers are added only behind an option that is off by default. Changing what is
  published by default needs a new major version (see [CHANGELOG.md](CHANGELOG.md)).

## Compatibility testing

The [CI workflow](.github/workflows/ci.yml) builds the jar once, then runs the integration test with that jar on
each Keycloak 26 line (the latest patch release of each) and on the Keycloak version in `pom.xml`, and once with
an older RabbitMQ line. When `keycloak.version` in `pom.xml` moves to a new minor line, add the previous line to
the matrix of the `integration-test` job.

The [Keycloak latest workflow](.github/workflows/keycloak-latest.yml) runs the integration test every week on
`quay.io/keycloak/keycloak:latest`, for the latest release and for the default branch. When it fails it opens an
issue labelled `keycloak-latest` (or comments on the open one) and closes it once the test passes again.

CI also builds the provider image (`Dockerfile`) for both platforms on every run, without pushing it.

Dependabot opens the pull requests for new Keycloak releases (group `keycloak`), the other Maven dependencies,
the GitHub Actions and the base image of the provider image.

## Pull requests

* This repository is itself a fork, so GitHub may propose `aznamier/keycloak-event-listener-rabbitmq` as the base of
  a new pull request. Choose `efim0v/keycloak-event-listener-rabbitmq`, branch `master`.
* One topic per pull request, with a test for each change in behaviour.
* `./mvnw -B -ntp verify` passes.
* Changes users notice get a line in [CHANGELOG.md](CHANGELOG.md).
* Commit messages are short and imperative, like the existing history, for example
  "Verify TLS peers, keep secrets out of the log".
* Contributions are licensed under the Apache License, Version 2.0 (section 5 of [License.md](License.md)).

## Releases

For maintainers:

1. Set `<version>` in `pom.xml` and add the section `## <version> - <date>` to [CHANGELOG.md](CHANGELOG.md).
2. Merge to `master` with CI green, then push an annotated tag: `git tag -a v<version> -m "keycloak-to-rabbit
   <version>"` and `git push origin v<version>`.
3. CI checks that the tag matches the version in `pom.xml`, runs the build, the integration test matrix and the
   vulnerability scan, and then publishes:
   * the GitHub release with `keycloak-to-rabbit-<version>.jar`, `keycloak-to-rabbit.sha256` and the SBOM
     `keycloak-to-rabbit-<version>.cdx.json`; the notes are the CHANGELOG section, the Keycloak and RabbitMQ
     versions the integration test passed on, and the merged pull requests;
   * build provenance and SBOM attestations for the jar (`gh attestation verify keycloak-to-rabbit-<version>.jar
     --repo efim0v/keycloak-event-listener-rabbitmq`);
   * the provider image `ghcr.io/efim0v/keycloak-to-rabbit:<version>` (linux/amd64 and linux/arm64), which
     contains the released jar at `/opt/keycloak/providers/keycloak-to-rabbit.jar`, with a provenance
     attestation.

To build the provider image locally from a build of the jar:

```sh
./mvnw -B -ntp package -DskipTests
docker build --build-arg VERSION=<version> -t keycloak-to-rabbit:<version> .
```
