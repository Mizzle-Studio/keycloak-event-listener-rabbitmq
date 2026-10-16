# Operations

What to expect from the listener in production, and what to watch.

- [Delivery guarantees](#delivery-guarantees)
- [Effect on Keycloak requests](#effect-on-keycloak-requests)
- [Several Keycloak nodes](#several-keycloak-nodes)
- [Logging and monitoring](#logging-and-monitoring)
- [Known issues](#known-issues)
- [Troubleshooting](#troubleshooting)

## Delivery guarantees

At most once. The listener publishes each event once, without publisher confirms, and does not retry or buffer. An
event is lost when:

- the broker cannot be reached, or the connection is being re-established;
- RabbitMQ rejects the publish, for example because the exchange does not exist or the user may not write to it;
- no queue is bound with a matching key: RabbitMQ drops the message;
- Keycloak stops or crashes between committing a request and publishing its events;
- the broker restarts and the message waits in a classic queue: messages are published as transient, which classic
  queues discard on restart ([RabbitMQ: Queues](https://www.rabbitmq.com/docs/queues#durability)). Quorum queues
  keep them.

When every event matters, for example for an audit trail, keep Keycloak's own event store as the record (Realm
settings > Events > User events settings and Admin events settings) and use the messages to drive integrations.

## Effect on Keycloak requests

The listener publishes in the Keycloak request thread, after the request's transaction is committed and before the
response is sent. Each Keycloak node opens one connection and one channel and reuses them, so with a healthy broker
an event costs one write to an open socket.

<!-- behaviour:failures -->
When the broker is not healthy, requests that produce events (logins, token requests, admin changes) are affected:

| Situation | What happens |
|---|---|
| Broker refuses connections (stopped, wrong port) | Each such request tries to connect again. The attempt fails quickly, the request continues, the event is dropped and logged. |
| Broker host does not answer (packets dropped, a port that does not speak AMQP) | Each connection attempt waits for the RabbitMQ client's timeouts: up to 60 s to connect and up to 10 s for the AMQP handshake. Attempts run one at a time, so concurrent requests wait for each other. |
| RabbitMQ raises a memory or disk alarm | RabbitMQ stops reading from publishing connections ([RabbitMQ: Alarms](https://www.rabbitmq.com/docs/alarms)). Once the socket buffers are full, publishing waits until the alarm clears, and so do the requests that publish and the requests waiting for the same channel. |
| Connection lost after it was established (network failure, broker restart) | The next request tries to connect again, and the RabbitMQ client also recovers the old connection every 5 s. Events produced while the broker is unreachable are dropped. |

Keep RabbitMQ close to Keycloak, alert on RabbitMQ resource alarms, and make sure `url` resolves to a host that
answers.
<!-- /behaviour:failures -->

## Several Keycloak nodes

Every node connects on its own and publishes the events of the requests it handles. RabbitMQ keeps the order of
messages published on one channel and routed to one queue ([RabbitMQ: Message ordering](https://www.rabbitmq.com/docs/semantics#ordering)),
so the events of one node arrive in order; between nodes there is no order. Use `time` from the body when the order
across nodes matters.

## Logging and monitoring

The listener logs through Keycloak's logging, category `com.github.aznamier.keycloak.event.provider`:

| Level | Message | Meaning |
|---|---|---|
| INFO | `keycloak-to-rabbitmq configuration: <name>=<value>` | at startup, one line per setting; passwords as `<hidden>` or `<empty>` |
| ERROR | `keycloak-to-rabbitmq ERROR on connection to rabbitmq` | a connection attempt failed, with the cause |
| ERROR | `keycloak-to-rabbitmq ERROR sending message: <routing key>` | an event was dropped; `(not connected to RabbitMQ)` when there was no connection |
| ERROR | `keycloak-to-rabbitmq: could not set up TLS; events will not be sent to RabbitMQ` | at startup, a trust store or key store could not be read |
| TRACE | `keycloak-to-rabbitmq SUCCESS sending message: <routing key>` | every published event |

To see every published event, raise the category to TRACE, for example
`bin/kc.sh start --log-level="INFO,com.github.aznamier.keycloak.event.provider:trace"`.

On the RabbitMQ side, the Keycloak nodes appear as connections of the user in `username`
(`rabbitmqctl list_connections user name state`), and the management UI shows the publish rate of the exchange.

## Known issues

<!-- behaviour:known-issues -->
- **HTTP 500 on a request whose realm cache entry is invalidated during that request.** The listener computes the
  routing key of a user event after Keycloak's transaction has ended. If Keycloak invalidated the realm's cache entry
  during that request, for example when it created a missing realm key, the lookup of the realm name fails with
  `IllegalStateException: Cannot access delegate without a transaction`, and the client gets HTTP 500 for a request
  that otherwise succeeded. The next request works. Reported upstream as
  [aznamier#58](https://github.com/aznamier/keycloak-event-listener-rabbitmq/issues/58).
- **A missing exchange is not reported.** If the exchange does not exist, RabbitMQ closes the channel on the first
  publish. The listener opens a new channel for the next request, and the events are lost without an error line.
  Create the exchange before enabling the listener, or use `amq.topic`.
- **Reconnect during recovery opens an extra connection.** When the broker drops the connection and a request comes
  in while the client is reconnecting, the listener opens a second connection and the first one also comes back.
  Each such drop can leave one extra idle connection until Keycloak restarts.
<!-- /behaviour:known-issues -->

## Troubleshooting

See the [checklist in Configuration](configuration.md#checklist-nothing-arrives-in-rabbitmq).
