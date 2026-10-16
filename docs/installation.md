# Installation

The listener is one jar: the provider classes plus the RabbitMQ Java client. Keycloak loads it from its `providers/`
directory. Keep only one `keycloak-to-rabbit` jar there.

- [Download and verify](#download-and-verify)
- [Keycloak distribution (zip)](#keycloak-distribution-zip)
- [Container image](#container-image)
- [Kubernetes](#kubernetes)
- [Upgrading the listener](#upgrading-the-listener)
- [Building from source](#building-from-source)

After installing, [configure the connection](configuration.md) and
[enable the listener in your realms](configuration.md#enabling-the-listener-in-a-realm).

## Download and verify

Every [release](https://github.com/efim0v/keycloak-event-listener-rabbitmq/releases) has the jar and a
`keycloak-to-rabbit.sha256` file, both built by CI from the release tag.

```sh
VERSION=4.1.0
BASE=https://github.com/efim0v/keycloak-event-listener-rabbitmq/releases/download/v$VERSION
curl -fLO "$BASE/keycloak-to-rabbit-$VERSION.jar"
curl -fLO "$BASE/keycloak-to-rabbit.sha256"
sha256sum --check keycloak-to-rabbit.sha256    # macOS: shasum -a 256 --check keycloak-to-rabbit.sha256
```

The build is reproducible: building the release tag with the JDK that CI uses (Temurin 21) gives the same checksum.

Provider jars run inside Keycloak with all of its privileges, so install only jars whose checksum you checked
([Keycloak: Configuring providers](https://www.keycloak.org/server/configuration-provider)).

## Keycloak distribution (zip)

1. Copy the jar to `providers/` of the Keycloak installation.
2. If you start Keycloak with `--optimized`, run `bin/kc.sh build` (with your other build options) so that Keycloak
   registers the provider. A plain `bin/kc.sh start` picks it up by itself.
3. Configure the listener and restart Keycloak.

## Container image

For production, build an optimized image, as the
[Keycloak container guide](https://www.keycloak.org/server/containers) recommends. `ADD --checksum` makes the build
fail if the downloaded jar does not match the checksum:

```dockerfile
# syntax=docker/dockerfile:1
ARG KEYCLOAK_VERSION=26.8.1

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder
# The release to install and the SHA-256 from the keycloak-to-rabbit.sha256 file of that release
ARG PLUGIN_VERSION=4.1.0
ARG PLUGIN_SHA256
ADD --checksum=sha256:${PLUGIN_SHA256} --chown=keycloak:keycloak --chmod=644 \
    https://github.com/efim0v/keycloak-event-listener-rabbitmq/releases/download/v${PLUGIN_VERSION}/keycloak-to-rabbit-${PLUGIN_VERSION}.jar \
    /opt/keycloak/providers/keycloak-to-rabbit.jar
# Build options of your deployment (database, health, metrics, features) go before this line.
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

```sh
VERSION=4.1.0
SHA256=$(curl -fsSL https://github.com/efim0v/keycloak-event-listener-rabbitmq/releases/download/v$VERSION/keycloak-to-rabbit.sha256 | cut -d ' ' -f 1)
docker build --build-arg PLUGIN_VERSION=$VERSION --build-arg PLUGIN_SHA256=$SHA256 -t my-keycloak:26.8.1 .
```

To pin the jar, write the checksum into the Dockerfile as the default of `PLUGIN_SHA256` once, instead of fetching it
at build time. A missing or wrong checksum stops the build.

Start the image with `start --optimized` and the listener's settings, for example as `KK_TO_RMQ_*` variables.

For a quick test without building an image, mount the jar into the official image:

```sh
docker run -p 8080:8080 \
  -v "$PWD/keycloak-to-rabbit-4.1.0.jar:/opt/keycloak/providers/keycloak-to-rabbit.jar:ro" \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=change-me \
  -e KK_TO_RMQ_URL=rabbitmq.internal -e KK_TO_RMQ_VHOST=/ \
  -e KK_TO_RMQ_USERNAME=keycloak -e KK_TO_RMQ_PASSWORD=change-me \
  quay.io/keycloak/keycloak:26.8.1 start-dev
```

## Kubernetes

### Keycloak Operator

Use an image built as above and point the Keycloak custom resource at it. The Operator ignores build options for
custom images ([Keycloak: Using custom Keycloak images](https://www.keycloak.org/operator/customizing-keycloak)), so
put them into the Dockerfile before `kc.sh build`, for example `ENV KC_DB=postgres`, `ENV KC_HEALTH_ENABLED=true` and
`ENV KC_METRICS_ENABLED=true`.

```yaml
apiVersion: k8s.keycloak.org/v2beta1   # k8s.keycloak.org/v2alpha1 with operators older than 26.6
kind: Keycloak
metadata:
  name: keycloak
spec:
  image: registry.example.com/my-keycloak:26.8.1   # built from the Dockerfile above
  env:                                              # Keycloak Operator 26.4 and later
    - name: KK_TO_RMQ_URL
      value: rabbitmq.messaging.svc
    - name: KK_TO_RMQ_VHOST
      value: /
    - name: KK_TO_RMQ_USERNAME
      value: keycloak
    - name: KK_TO_RMQ_PASSWORD
      secret:
        name: keycloak-rabbitmq
        key: password
  truststores:                                      # only if the broker certificate comes from a private CA
    rabbitmq-ca:
      secret:
        name: rabbitmq-ca                           # a Secret with the CA certificate as a PEM file
  # instances, db, http, hostname and the rest of your configuration as usual
```

`spec.env` passes the listener's own variables, which every Keycloak 26 release reads the same way. With operators
older than 26.4, set the options through `spec.additionalOptions` instead, for example
`name: spi-events-listener-keycloak-to-rabbitmq-url`; `additionalOptions` entries also accept `secret` references.
`spec.truststores` adds the CA to Keycloak's trusted certificates, which the listener uses when `trust_store` is not
set (see [TLS](configuration.md#tls-and-client-certificates)).

### Helm

Most Keycloak charts let you set the image, init containers, volumes and environment variables. Two ways:

- **Your own image.** Build the image above and set it as the chart's Keycloak image. Nothing is downloaded when a pod
  starts.
<!-- provider-image -->
- **The provider image in an init container.** Each release also publishes `ghcr.io/efim0v/keycloak-to-rabbit:<version>`,
  a small image with the jar at `/opt/keycloak/providers/keycloak-to-rabbit.jar`. An init container copies it into a
  volume that the Keycloak container mounts as `/opt/keycloak/providers`. With the `keycloakx` chart:

  ```yaml
  extraInitContainers: |
    - name: keycloak-to-rabbit
      image: ghcr.io/efim0v/keycloak-to-rabbit:4.1.0
      command: ["cp", "/opt/keycloak/providers/keycloak-to-rabbit.jar", "/providers/"]
      volumeMounts:
        - name: providers
          mountPath: /providers
  extraVolumes: |
    - name: providers
      emptyDir: {}
  extraVolumeMounts: |
    - name: providers
      mountPath: /opt/keycloak/providers
  ```

  With the official Keycloak image, Keycloak registers the provider when it starts without `--optimized`.
<!-- /provider-image -->

Pass the listener's settings as environment variables of the Keycloak container, the password from a Secret. With the
`keycloakx` chart:

```yaml
extraEnv: |
  - name: KK_TO_RMQ_URL
    value: rabbitmq.messaging.svc
  - name: KK_TO_RMQ_VHOST
    value: /
  - name: KK_TO_RMQ_USERNAME
    value: keycloak
  - name: KK_TO_RMQ_PASSWORD
    valueFrom:
      secretKeyRef:
        name: keycloak-rabbitmq
        key: password
```

## Upgrading the listener

1. Replace the jar in `providers/` (or the version in your Dockerfile) and remove the old one.
2. Run `kc.sh build` again if you start with `--optimized`; an image built from the Dockerfile above does this.
3. Restart Keycloak. Check the [CHANGELOG](../CHANGELOG.md) for changes that need action.

Coming from upstream 3.0.5 or from another fork: see [Migration](migration.md).

## Building from source

```sh
./mvnw -B package -DskipTests
```

The jar is `target/keycloak-to-rabbit-<version>.jar`; the smaller `target/keycloak-to-rabbit.jar` next to it lacks the
RabbitMQ client and does not work on its own. See [Development](development.md) for the tests.
