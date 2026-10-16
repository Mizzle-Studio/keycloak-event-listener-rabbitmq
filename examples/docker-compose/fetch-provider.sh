#!/bin/sh
# Downloads the provider jar of release $PLUGIN_VERSION, checks it against the release's SHA-256 file
# and puts it into the volume that Keycloak mounts as /opt/keycloak/providers.
set -eu

release="https://github.com/efim0v/keycloak-event-listener-rabbitmq/releases/download/v${PLUGIN_VERSION}"
jar="keycloak-to-rabbit-${PLUGIN_VERSION}.jar"

cd /tmp
wget -q -O "$jar" "$release/$jar"
wget -q -O keycloak-to-rabbit.sha256 "$release/keycloak-to-rabbit.sha256"
sha256sum -c keycloak-to-rabbit.sha256

# A fixed name, so that switching versions replaces the jar instead of adding a second one.
cp "$jar" /providers/keycloak-to-rabbit.jar
chmod 644 /providers/keycloak-to-rabbit.jar
