# Development

- [Build and test](#build-and-test)
- [The message contract](#the-message-contract)
- [Pull requests](#pull-requests)
- [Releases](#releases)

## Build and test

Requirements: JDK 17 or newer, and Docker for the integration test.

```sh
./mvnw -B clean verify
```

This runs the unit tests, including the message contract tests, then the integration test: it starts Keycloak (the
version in `pom.xml`) with the built jar and RabbitMQ in containers, and checks the messages for a login, a failed
login, and an administrator updating and deleting a user. The messages it receives are written to
`target/it-messages/`. Add `-DskipITs` to skip it. The jar is `target/keycloak-to-rabbit-<version>.jar`.

The integration test can run against another Keycloak image or another build of the listener:

```sh
./mvnw -B verify -Dit.keycloak.image=quay.io/keycloak/keycloak:26.0
./mvnw -B verify -Dit.provider.jar=/path/to/keycloak-to-rabbit.jar
```

CI runs it on the Keycloak version in `pom.xml` and on the latest 26.0.x release, the oldest supported line, for every
push and pull request and once a week.

To try a local build by hand, the [quick start](../examples/docker-compose/) can use it instead of a release:

```sh
./mvnw -B package -DskipTests
cd examples/docker-compose
docker compose -f compose.yaml -f compose.local.yaml up -d --wait
```

## The message contract

The routing keys, AMQP properties, headers and JSON fields are a contract with every consumer written for upstream
3.0.5. The contract tests (`*ContractTest`, `MessagePropertiesContract`) were written against upstream 3.0.5 before any
change in this fork. A change that needs one of them edited changes the format, and a format change needs a new major
version (see "Versioning" in [CHANGELOG.md](../CHANGELOG.md)).

The Java package `com.github.aznamier.keycloak.event.provider` and the class names stay as they are: they appear in
every message as `@class` and `__TypeId__`.

## Pull requests

This repository is itself a fork, so GitHub may propose the original repository,
`aznamier/keycloak-event-listener-rabbitmq`, as the base of a new pull request. Choose
`efim0v/keycloak-event-listener-rabbitmq`, branch `master`.

Keep commits small, with a short imperative subject, and describe changes that users notice in
[CHANGELOG.md](../CHANGELOG.md).

## Releases

Releases are built by CI, not on a developer machine. Pushing a tag `v<version>` that matches the version in `pom.xml`
runs the CI workflow, which:

1. builds the jar with Temurin JDK 21 and `./mvnw --batch-mode clean verify` (unit and integration tests);
2. writes its SHA-256 checksum with `sha256sum` to `keycloak-to-rabbit.sha256`;
3. scans `pom.xml` and the jar with OSV-Scanner and stops on known vulnerabilities;
4. creates the GitHub release for the tag with the jar, the checksum file and the release's section of
   [CHANGELOG.md](../CHANGELOG.md) as notes.

The build is reproducible: `project.build.outputTimestamp` in `pom.xml` fixes the timestamps in the jar, so a build of
the tag with the same JDK (Temurin 21) produces the same checksum. Builds with other JDK versions may differ in the
compiled classes.
