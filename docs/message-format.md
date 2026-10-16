# Message format

One AMQP message per Keycloak event. The format is the same as that of upstream release 3.0.5 on the same Keycloak
version: routing keys, AMQP properties, headers and JSON fields. Contract tests in `src/test` pin it, and the
integration test checks the messages a real Keycloak produces.

- [Transport](#transport)
- [When a message is sent](#when-a-message-is-sent)
- [AMQP properties](#amqp-properties)
- [Body](#body)
- [Routing key](#routing-key)
- [Binding examples](#binding-examples)
- [Examples](#examples)
- [Notes for consumers](#notes-for-consumers)

## Transport

| Aspect | Value |
|---|---|
| Exchange | the `exchange` setting, `amq.topic` by default. The listener does not declare it. |
| Exchange type | your choice. With a topic exchange consumers bind with patterns; a direct exchange needs the full routing key; a fanout exchange ignores keys. |
| Publish | `mandatory` is off: RabbitMQ drops a message that matches no binding, without telling the listener. |
| Publisher confirms | not used. |
| Delivery mode | not set, so messages are transient. A durable classic queue discards transient messages when the broker restarts ([RabbitMQ: Queues](https://www.rabbitmq.com/docs/queues#durability)); a quorum queue stores every message on disk regardless of the delivery mode ([RabbitMQ: Quorum Queues](https://www.rabbitmq.com/docs/quorum-queues#dead-lettering)). |
| Delivery guarantee | at most once. See [Operations](operations.md#delivery-guarantees). |

## When a message is sent

- **Successful user events** (`LOGIN`, `CODE_TO_TOKEN`, `LOGOUT`, `REGISTER`, …) are collected during the request and
  published after Keycloak commits the request's transaction. If the transaction rolls back, they are discarded.
- **Error user events** (`LOGIN_ERROR`, `CODE_TO_TOKEN_ERROR`, …) are written by Keycloak in a transaction of their
  own, so they are published when the error is recorded, even if the request later fails.
- **Admin events** are published after the admin request's transaction commits.
- Within one transaction the admin events come first, then the user events, each in the order Keycloak recorded them.
- Publishing happens in the Keycloak request thread, before the HTTP response is sent.
- Only realms that list `keycloak-to-rabbitmq` among their event listeners publish anything. The realm's "Save
  events" and "Save admin events" settings do not filter what the listener receives.

## AMQP properties

| Property | Value |
|---|---|
| `app_id` | `Keycloak` |
| `content_type` | `application/json` |
| `content_encoding` | `UTF-8` |
| `headers` | one header, `__TypeId__`, with the value of the `@class` field (the type id header of Spring AMQP) |
| everything else | not set: no `delivery_mode`, `priority`, `message_id`, `timestamp`, `correlation_id`, `reply_to`, `expiration`, `type`, `user_id` |

`__TypeId__` and `@class` values:

- user events: `com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg`
- admin events: `com.github.aznamier.keycloak.event.provider.EventAdminNotificationMqMsg`

The Java package keeps the original author's name because it is part of every message.

## Body

UTF-8 JSON, pretty-printed (two-space indentation, `"name" : value`). The first property is always `@class`. Fields
without a value are left out; `time` is always present. Non-ASCII characters are written as UTF-8, not escaped. Read
the fields by name: apart from `@class` their order is not part of the format, and in admin events
`resourceTypeAsString` and `resourceId` can swap places after a Keycloak restart.

### User event

| Field | Type | Content |
|---|---|---|
| `@class` | string | `com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg` |
| `time` | number | event time, milliseconds since the epoch |
| `type` | string | Keycloak event type: `LOGIN`, `LOGIN_ERROR`, `CODE_TO_TOKEN`, `LOGOUT`, `REGISTER`, … |
| `realmId` | string | realm id (a UUID), not the realm name |
| `clientId` | string | the client's client ID as configured in Keycloak |
| `userId` | string | user id |
| `sessionId` | string | user session id |
| `ipAddress` | string | client IP address as Keycloak saw it |
| `error` | string | error code, error events only, for example `invalid_user_credentials` |
| `details` | object | string values; the keys depend on the event type, for example `username`, `auth_method`, `grant_type`, `scope`, `token_id` |

The event `id` and the `realmName` that Keycloak 26 adds to its events are not part of the message.

### Admin event

| Field | Type | Content |
|---|---|---|
| `@class` | string | `com.github.aznamier.keycloak.event.provider.EventAdminNotificationMqMsg` |
| `time` | number | milliseconds since the epoch |
| `realmId` | string | id of the realm the change was made in |
| `authDetails` | object | who made the change: `realmId`, `realmName`, `clientId` (the internal id of the client, a UUID), `userId`, `ipAddress`. Written as Keycloak provides it. |
| `resourceType` | string | `USER`, `CLIENT`, `REALM`, `REALM_ROLE_MAPPING`, `GROUP_MEMBERSHIP`, …; `CUSTOM` for types Keycloak does not list |
| `operationType` | string | `CREATE`, `UPDATE`, `DELETE` or `ACTION` |
| `resourcePath` | string | for example `users/<user id>` or `users/<user id>/role-mappings/realm`; absent for some resources, such as the realm itself |
| `representation` | string | the changed resource as JSON **inside a string**: parse it a second time |
| `error` | string | failed operations only |
| `resourceTypeAsString` | string | the resource type name; custom resource types appear here as they are |
| `resourceId` | string | the last segment of `resourcePath`. Keycloak computes it since 26.4; on 26.0 to 26.3 the field is absent, as with upstream 3.0.5 |

The event `id`, and the `realmName` and `details` that Keycloak 26 adds to admin events, are not part of the message.

**The representation.** Keycloak 26 fills the representation of every admin event and passes it to event listeners;
the realm's "Include representation" setting is not applied by the listener. Before that, Keycloak removes
credentials from user representations and masks client secrets as `**********`. Personal data such as the user
name, e-mail address, first and last name and user attributes stay in it.

## Routing key

```text
user event:   KK.EVENT.CLIENT.<REALM>.<RESULT>.<CLIENT>.<EVENT_TYPE>
admin event:  KK.EVENT.ADMIN.<REALM>.<RESULT>.<RESOURCE_TYPE>.<OPERATION>

<REALM>          realm name with every "." removed (user events: the event's realm;
                 admin events: the realm of the admin request)
<RESULT>         ERROR if the event has an error, otherwise SUCCESS
<CLIENT>         the event's client ID with every "." removed; the text "null" if the event has no client
<EVENT_TYPE>     Keycloak event type, for example LOGIN or LOGIN_ERROR
<RESOURCE_TYPE>  resource type name, custom types as they are
<OPERATION>      CREATE, UPDATE, DELETE or ACTION
```

Then, over the whole key, characters other than `A-Z a-z 0-9 _ - . * #` and space are removed and spaces become `_`.
Letter case is kept; RabbitMQ compares keys case-sensitively. The prefix of user events is `KK.EVENT.CLIENT`, not
`USER`, for historical reasons.

| Event | Routing key |
|---|---|
| realm `acme`, client `web-app`, `LOGIN` | `KK.EVENT.CLIENT.acme.SUCCESS.web-app.LOGIN` |
| realm `acme`, wrong password | `KK.EVENT.CLIENT.acme.ERROR.web-app.LOGIN_ERROR` |
| realm `my.realm`, client `my.client` | `KK.EVENT.CLIENT.myrealm.SUCCESS.myclient.LOGIN` |
| realm `My Realm`, client `My Client` | `KK.EVENT.CLIENT.My_Realm.SUCCESS.My_Client.LOGIN` |
| realm `réalm!`, client `client/äpp` | `KK.EVENT.CLIENT.ralm.SUCCESS.clientpp.LOGIN` |
| event without a client | `KK.EVENT.CLIENT.acme.SUCCESS.null.LOGIN` |
| administrator creates a user in realm `acme` | `KK.EVENT.ADMIN.acme.SUCCESS.USER.CREATE` |
| administrator changes realm `acme` itself | `KK.EVENT.ADMIN.acme.SUCCESS.REALM.UPDATE` |

Things to keep in mind:

- Names are not escaped, so different names can give the same key (`my.realm` and `myrealm`; names written only in
  non-Latin letters become empty). When the exact realm matters, use `realmId` from the body.
- A `*` or `#` in a realm or client name ends up in the key. There it is an ordinary character, but in a binding it
  is a wildcard.

## Binding examples

For a topic exchange (`*` matches one word, `#` matches zero or more):

| What you want | Binding key |
|---|---|
| everything | `KK.EVENT.#` |
| all user events | `KK.EVENT.CLIENT.#` |
| all admin events | `KK.EVENT.ADMIN.#` |
| everything from realm `myrealm` | `KK.EVENT.*.myrealm.#` |
| all errors from realm `myrealm` | `KK.EVENT.*.myrealm.ERROR.#` |
| user events of client `myclient` in realm `myrealm` | `KK.EVENT.CLIENT.myrealm.*.myclient.*` |
| successful logins in any realm | `KK.EVENT.CLIENT.*.SUCCESS.*.LOGIN` |
| new registrations | `KK.EVENT.CLIENT.*.SUCCESS.*.REGISTER` |
| users deleted by administrators | `KK.EVENT.ADMIN.*.SUCCESS.USER.DELETE` |

## Examples

Messages published by Keycloak 26.8.1 in the [quick start](../examples/docker-compose/).

`KK.EVENT.CLIENT.demo.SUCCESS.demo-app.LOGIN`, a password grant for user `alice`:

```json
{
  "@class" : "com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg",
  "time" : 1792178809437,
  "type" : "LOGIN",
  "realmId" : "b43f6f2c-1a03-40d4-a3c2-6cd8df8e49b6",
  "clientId" : "demo-app",
  "userId" : "05fce3fd-ee8b-4d27-bbbd-de6f3a29820b",
  "sessionId" : "gIOG9-ZDDp96Liom5YidaEd1",
  "ipAddress" : "172.24.0.1",
  "details" : {
    "refresh_token_provider_id" : "default",
    "auth_method" : "openid-connect",
    "token_id" : "onrtro:4e3cf708-b646-8545-636b-d3dc379b3595",
    "grant_type" : "password",
    "refresh_token_type" : "Refresh",
    "scope" : "email profile",
    "refresh_token_id" : "e60cb8e4-a5a5-379d-e51b-a3a3ed128453",
    "client_auth_method" : "client-secret",
    "username" : "alice"
  }
}
```

`KK.EVENT.CLIENT.demo.ERROR.demo-app.LOGIN_ERROR`, a wrong password (no `sessionId`):

```json
{
  "@class" : "com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg",
  "time" : 1792178809516,
  "type" : "LOGIN_ERROR",
  "realmId" : "b43f6f2c-1a03-40d4-a3c2-6cd8df8e49b6",
  "clientId" : "demo-app",
  "userId" : "05fce3fd-ee8b-4d27-bbbd-de6f3a29820b",
  "ipAddress" : "172.24.0.1",
  "error" : "invalid_user_credentials",
  "details" : {
    "auth_method" : "openid-connect",
    "grant_type" : "password",
    "client_auth_method" : "client-secret",
    "username" : "alice"
  }
}
```

`KK.EVENT.ADMIN.demo.SUCCESS.USER.CREATE`, an administrator of the `master` realm created user `bob` with `kcadm.sh`:

```json
{
  "@class" : "com.github.aznamier.keycloak.event.provider.EventAdminNotificationMqMsg",
  "time" : 1792178814521,
  "realmId" : "b43f6f2c-1a03-40d4-a3c2-6cd8df8e49b6",
  "authDetails" : {
    "realmId" : "2deccc3c-3373-4b86-b794-1ac08d843a94",
    "realmName" : "master",
    "clientId" : "98593b9f-b78b-487f-aee5-527c7b510684",
    "userId" : "5801dfbb-1691-4291-98df-6452f5d163c5",
    "ipAddress" : "127.0.0.1"
  },
  "resourceType" : "USER",
  "operationType" : "CREATE",
  "resourcePath" : "users/72dc0c85-a57c-43a8-9eac-2604b94e4ca5",
  "representation" : "{\"username\":\"bob\",\"enabled\":true}",
  "resourceTypeAsString" : "USER",
  "resourceId" : "72dc0c85-a57c-43a8-9eac-2604b94e4ca5"
}
```

## Notes for consumers

- Consumers in other languages can ignore `@class` and `__TypeId__`. Spring AMQP uses `__TypeId__` to choose the
  class to convert to: map the two values to your own classes instead of adding the listener to your classpath (see
  [Consumers](consumers.md#spring-boot)).
- `representation` is a string: parse it again to get the resource.
- The body has no event id. To recognise a message you have seen, build a key from the fields, for example `time`,
  `type`, `userId` and `sessionId`. The listener does not retry, so it does not send the same event twice.
- Messages carry personal data: user names, e-mail addresses, IP addresses, user attributes in representations. See
  [Security](../README.md#security).
