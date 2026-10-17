# Provider image: the keycloak-to-rabbit jar at /opt/keycloak/providers/keycloak-to-rabbit.jar.
#
# For Keycloak images built with "COPY --from=ghcr.io/mizzle-studio/keycloak-to-rabbit:<version> ...", and for
# Kubernetes init containers that copy the jar into Keycloak's providers directory (busybox provides cp).
# CI builds it from the released jar; to build it locally see CONTRIBUTING.md.
FROM busybox:1.38.0@sha256:fd7dc98638c8e305f4dc34e979f1c0fdfdcaeb0fbf8fcff77ae834b6da3d7e6e

ARG VERSION
# WORKDIR creates the directory (mode 0755); COPY --chmod would also apply 0644 to directories it creates.
WORKDIR /opt/keycloak/providers
COPY --chmod=0644 target/keycloak-to-rabbit-${VERSION}.jar /opt/keycloak/providers/keycloak-to-rabbit.jar

LABEL org.opencontainers.image.title="keycloak-to-rabbit" \
      org.opencontainers.image.description="Keycloak event listener that publishes events to RabbitMQ. The jar is at /opt/keycloak/providers/keycloak-to-rabbit.jar." \
      org.opencontainers.image.source="https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq" \
      org.opencontainers.image.licenses="Apache-2.0"

USER 65534:65534
