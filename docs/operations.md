# Operations

What to expect from the listener in production, and what to watch.

- [Delivery guarantees](#delivery-guarantees)
- [Effect on Keycloak requests](#effect-on-keycloak-requests)
- [Several Keycloak nodes](#several-keycloak-nodes)
- [Logging and monitoring](#logging-and-monitoring)
- [Troubleshooting](#troubleshooting)

## Delivery guarantees

At most once. The listener publishes each event once, without publisher confirms, and does not retry or buffer. An
event is lost when:

- the broker cannot be reached, or the connection is being re-established;
- RabbitMQ blocks the connection because of a memory or disk alarm;
- a publish has waited one second for the publishes before it, for example during a burst on a slow broker;
- RabbitMQ rejects the publish, for example because the exchange does not exist or the user may not write to it.
  For a missing exchange the listener logs a warning with RabbitMQ's reason (see
  [Logging and monitoring](#logging-and-monitoring));
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
an event costs one write to an open socket. Concurrent requests take turns on the channel. An exception while the
listener works out the routing key, builds a message or publishes it is logged and does not fail the request.

When the broker is not healthy, requests that produce events (logins, token requests, admin changes) are affected:

| Situation | What happens |
|---|---|
| The first connection is refused (broker stopped, wrong port) | Each request that produces events tries to connect. The attempt fails quickly, the request continues, the events are dropped and logged. |
| The broker host does not answer the first connection (packets dropped, a port that does not speak AMQP) | Each attempt waits for the RabbitMQ client's timeouts: up to 60 s to connect and up to 10 s for the AMQP handshake. Attempts run one at a time, so concurrent requests wait for each other. |
| The connection is lost after it was established (network failure, broker restart) | The RabbitMQ client recovers it in the background, every 5 s. Requests do not wait; events produced meanwhile are dropped and logged. |
| RabbitMQ raises a memory or disk alarm | RabbitMQ blocks publishing connections ([RabbitMQ: Alarms](https://www.rabbitmq.com/docs/alarms)). While the connection is blocked, the listener drops events instead of writing them. A message written just before the block is delivered when the alarm clears; if it is larger than the socket buffers, its request waits until then. |
| RabbitMQ is slow to take messages (an overloaded broker, a burst of large admin events) | Publishes take turns. One that has waited 1 s for the publishes before it is dropped and logged: requests stay fast, but a burst can lose events. |
| RabbitMQ or the network stops taking data | Events are dropped after the same wait of at most 1 s. The request whose message is being written waits until the write completes or the connection fails. When the network stops carrying the connection, the RabbitMQ client notices the missing heartbeats and recovers the connection; with the default heartbeat of 60 s this takes a few minutes. |

When Keycloak stops, the listener closes its connection and waits at most 2 s for it. Before that, it waits for first
connection attempts that are still running, each up to the RabbitMQ client's timeouts above. A request whose write is
stuck keeps running, and Keycloak waits for its running requests before it exits.

Keep RabbitMQ close to Keycloak, alert on RabbitMQ resource alarms, and make sure `url` resolves to a host that
answers.

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
| ERROR | `keycloak-to-rabbitmq ERROR sending message: <routing key>` | an event was dropped; the text in parentheses says why: `not connected to RabbitMQ`, `RabbitMQ blocks publishing: <reason>`, or `earlier messages have not reached RabbitMQ within 1000 ms`. Without parentheses, the exception logged with the line says why, for example `AlreadyClosedException` for a channel that RabbitMQ has closed |
| ERROR | `keycloak-to-rabbitmq ERROR sending message: no routing key for event <type> of realm <realm id>`, or `keycloak-to-rabbitmq ERROR sending message: no routing key for admin event <operation> <resource type> of realm <realm id>` | the listener could not work out the routing key of an event; the exception logged with the line says why. The event is dropped |
| WARN | `keycloak-to-rabbitmq: RabbitMQ refused an event and closed the channel: <reason>` | RabbitMQ refused a message, for example with `NOT_FOUND - no exchange 'keycloak.events' in vhost '/'` when the exchange does not exist; see below |
| WARN | `keycloak-to-rabbitmq: RabbitMQ blocked the connection (<reason>); events are dropped until it is unblocked` | a resource alarm on the broker |
| INFO | `keycloak-to-rabbitmq: RabbitMQ unblocked the connection` | the alarm cleared |
| WARN | `keycloak-to-rabbitmq: the connection to RabbitMQ did not close within 2000 ms` | at shutdown, while a write was stuck |
| ERROR | `keycloak-to-rabbitmq: could not set up TLS; events will not be sent to RabbitMQ` | at startup, a trust store or key store could not be read |
| TRACE | `keycloak-to-rabbitmq SUCCESS sending message: <routing key>` | every published event |

When the exchange does not exist, RabbitMQ closes the channel instead of taking the message. The warning comes once
for each channel that RabbitMQ closes, not once for each lost event: the events published on that channel after the
refused one are lost too, and Keycloak sessions that still hold the closed channel log
`keycloak-to-rabbitmq ERROR sending message: <routing key>` for their events. The next session opens a new channel;
while the exchange is missing, the same happens again.

To see every published event, raise the category to TRACE, for example
`bin/kc.sh start --log-level="INFO,com.github.aznamier.keycloak.event.provider:trace"`.

On the RabbitMQ side, the Keycloak nodes appear as connections of the user in `username`
(`rabbitmqctl list_connections user name state`), and the management UI shows the publish rate of the exchange.

## Troubleshooting

See the [checklist in Configuration](configuration.md#checklist-nothing-arrives-in-rabbitmq).
