# Quick start: Keycloak, RabbitMQ and keycloak-to-rabbitmq

Three containers on your machine: RabbitMQ with the management UI, a one-shot container that downloads the listener
from its GitHub release and checks the SHA-256 checksum, and Keycloak in development mode with a demo realm that has
the listener enabled. Needs Docker with Compose v2.

```sh
docker compose up -d --wait
```

The command returns when Keycloak and RabbitMQ are ready, in about half a minute once the images are pulled.

| | URL | Login |
|---|---|---|
| Keycloak admin console | http://localhost:8080 | `admin` / `admin` |
| Demo realm account page | http://localhost:8080/realms/demo/account | `alice` / `alice` |
| RabbitMQ management UI | http://localhost:15672 | `demo` / `demo` |

Log in as `alice` to produce an event:

```sh
curl -s -o /dev/null -w '%{http_code}\n' \
  -d client_id=demo-app -d username=alice -d password=alice -d grant_type=password \
  http://localhost:8080/realms/demo/protocol/openid-connect/token
```

and read it back from the queue `keycloak-events`, which is bound to `amq.topic` with `KK.EVENT.#`:

```sh
curl -s -u demo:demo -H 'content-type: application/json' \
  -d '{"count":1,"ackmode":"ack_requeue_false","encoding":"auto"}' \
  http://localhost:15672/api/queues/%2F/keycloak-events/get
```

The answer contains `"routing_key":"KK.EVENT.CLIENT.demo.SUCCESS.demo-app.LOGIN"` and the event as `payload`. In the
management UI the same is under Queues and Streams > `keycloak-events` > Get messages. Changes made in the admin
console to realm `demo` arrive as admin events, for example `KK.EVENT.ADMIN.demo.SUCCESS.USER.UPDATE`.

The [consumer examples](../../docs/consumers.md) connect to this setup as they are.

Stop and remove everything:

```sh
docker compose down -v
```

## Options

- `PLUGIN_VERSION=<version> docker compose up -d --wait` installs another release of the listener.
- `docker compose -f compose.yaml -f compose.local.yaml up -d --wait` uses the jar built from this repository
  (`./mvnw -B package -DskipTests` in the repository root) instead of a release.

## Files

| File | Content |
|---|---|
| `compose.yaml` | the three services; the listener is configured with `KK_TO_RMQ_*` variables |
| `fetch-provider.sh` | downloads the jar and `keycloak-to-rabbit.sha256` of the release and checks the jar |
| `keycloak/demo-realm.json` | realm `demo` with the listener enabled, public client `demo-app`, user `alice` |
| `rabbitmq/definitions.json` | user `keycloak`, which may only publish to `amq.topic`; user `demo` (administrator); the quorum queue `keycloak-events` and its binding |
| `rabbitmq/rabbitmq.conf` | loads the definitions at startup |

Not a production setup: Keycloak runs in development mode, every password is a demo password, and the ports are
open on `127.0.0.1` only.
