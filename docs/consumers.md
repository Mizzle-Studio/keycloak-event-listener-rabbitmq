# Consuming the events

Each service that needs Keycloak events declares its own durable queue and binds it to the listener's exchange with
the routing keys it cares about (see [binding examples](message-format.md#binding-examples)). The listener does not
know about queues, so adding a consumer never needs a change in Keycloak.

The examples connect to the [quick start](../examples/docker-compose/) (RabbitMQ on `localhost:5672`, user `demo`,
password `demo`) and declare quorum queues, which keep messages across broker restarts. Each was run against the
quick start with Keycloak 26.8.1: Spring Boot 4.1 with Spring AMQP 4.1, Node.js 25 with amqplib 2.2, Python 3.9 with
pika 1.4.

- [Spring Boot](#spring-boot)
- [Node.js (amqplib)](#nodejs-amqplib)
- [Python (pika)](#python-pika)
- [Good practice](#good-practice)

## Spring Boot

The listener sets the `__TypeId__` header that Spring AMQP's JSON converter reads. Map its two values to classes of
your application, and a class-level `@RabbitListener` dispatches each event to the matching `@RabbitHandler`.

Dependencies (Spring Boot 4):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jackson</artifactId>
</dependency>
```

`application.properties`:

```properties
spring.rabbitmq.host=localhost
spring.rabbitmq.port=5672
spring.rabbitmq.username=demo
spring.rabbitmq.password=demo
```

The two event types:

```java
package com.example.events;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A Keycloak user event (routing key KK.EVENT.CLIENT.…). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakUserEvent(long time, String type, String realmId, String clientId, String userId,
        String sessionId, String ipAddress, String error, Map<String, String> details) {
}
```

```java
package com.example.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A Keycloak admin event (routing key KK.EVENT.ADMIN.…). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakAdminEvent(long time, String realmId, AuthDetails authDetails, String resourceType,
        String operationType, String resourcePath, String representation, String error,
        String resourceTypeAsString, String resourceId) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthDetails(String realmId, String realmName, String clientId, String userId, String ipAddress) {
    }
}
```

The queue, its binding and the converter:

```java
package com.example.events;

import java.util.Map;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class KeycloakEventsConfig {

    static final String QUEUE = "user-sync.keycloak-events";

    @Bean
    Queue keycloakEventsQueue() {
        return QueueBuilder.durable(QUEUE).quorum().build();
    }

    // amq.topic exists in every virtual host and cannot be declared, so it is not a bean.
    @Bean
    Binding keycloakEventsBinding(Queue keycloakEventsQueue) {
        return BindingBuilder.bind(keycloakEventsQueue).to(new TopicExchange("amq.topic")).with("KK.EVENT.#");
    }

    // Maps the __TypeId__ header that the listener sets to the classes of this application.
    @Bean
    MessageConverter keycloakEventConverter() {
        DefaultJacksonJavaTypeMapper typeMapper = new DefaultJacksonJavaTypeMapper();
        typeMapper.setIdClassMapping(Map.of(
                "com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg", KeycloakUserEvent.class,
                "com.github.aznamier.keycloak.event.provider.EventAdminNotificationMqMsg", KeycloakAdminEvent.class));
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }
}
```

The handler:

```java
package com.example.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RabbitListener(queues = KeycloakEventsConfig.QUEUE)
public class KeycloakEventHandler {

    private static final Logger log = LoggerFactory.getLogger(KeycloakEventHandler.class);

    @RabbitHandler
    public void onUserEvent(KeycloakUserEvent event) {
        log.info("{} of user {} through client {}", event.type(), event.userId(), event.clientId());
    }

    @RabbitHandler
    public void onAdminEvent(KeycloakAdminEvent event) {
        log.info("{} {} by user {}", event.operationType(), event.resourceType(), event.authDetails().userId());
    }
}
```

Spring Boot auto-configuration uses the `MessageConverter` bean for `@RabbitListener` methods and declares the queue
and the binding at startup.

**Spring Boot 3** (Spring AMQP 3, Jackson 2): use `spring-boot-starter-json` instead of `spring-boot-starter-jackson`,
and `Jackson2JsonMessageConverter` with `DefaultJackson2JavaTypeMapper` instead of `JacksonJsonMessageConverter` with
`DefaultJacksonJavaTypeMapper`. The method calls are the same. This variant was compiled against Spring Boot 3.5.

## Node.js (amqplib)

```sh
npm install amqplib
```

`consumer.js`:

```js
// Prints the Keycloak events that keycloak-to-rabbitmq publishes.
const amqp = require('amqplib');

const QUEUE = 'audit-log.keycloak-events';

async function main() {
  const connection = await amqp.connect(process.env.AMQP_URL ?? 'amqp://demo:demo@localhost:5672/%2F');
  const channel = await connection.createChannel();
  // A queue of this service, bound to the listener's exchange: every event of realm "demo".
  await channel.assertQueue(QUEUE, { durable: true, arguments: { 'x-queue-type': 'quorum' } });
  await channel.bindQueue(QUEUE, 'amq.topic', 'KK.EVENT.*.demo.#');
  await channel.prefetch(50);
  await channel.consume(QUEUE, (message) => {
    if (message === null) return; // the queue was deleted
    const event = JSON.parse(message.content.toString('utf8'));
    const key = message.fields.routingKey;
    if (key.startsWith('KK.EVENT.ADMIN.')) {
      // event.representation is a JSON document inside a string
      console.log(key, event.operationType, event.resourceType, event.resourcePath ?? '');
    } else {
      console.log(key, event.type, event.userId, event.error ?? '');
    }
    channel.ack(message);
  });
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
```

```sh
node consumer.js
```

## Python (pika)

```sh
pip install pika
```

`consumer.py`:

```python
"""Prints the Keycloak events that keycloak-to-rabbitmq publishes."""
import json
import os

import pika

QUEUE = "login-stats.keycloak-events"

connection = pika.BlockingConnection(
    pika.URLParameters(os.environ.get("AMQP_URL", "amqp://demo:demo@localhost:5672/%2F")))
channel = connection.channel()
# A queue of this service, bound to the listener's exchange: successful logins in every realm.
channel.queue_declare(queue=QUEUE, durable=True, arguments={"x-queue-type": "quorum"})
channel.queue_bind(queue=QUEUE, exchange="amq.topic", routing_key="KK.EVENT.CLIENT.*.SUCCESS.*.LOGIN")


def on_message(ch, method, properties, body):
    event = json.loads(body)  # UTF-8 JSON
    print(method.routing_key, event["type"], event.get("userId"), event.get("details", {}).get("username"))
    ch.basic_ack(delivery_tag=method.delivery_tag)


channel.basic_qos(prefetch_count=50)
channel.basic_consume(queue=QUEUE, on_message_callback=on_message)
try:
    channel.start_consuming()
except KeyboardInterrupt:
    connection.close()
```

```sh
python consumer.py
```

## Good practice

- **One queue per service.** Bind only the keys the service needs; RabbitMQ does the filtering.
- **Acknowledge after processing**, as the examples do, so that a crash before the acknowledgement makes RabbitMQ
  deliver the message again. Handlers should therefore tolerate seeing an event twice.
- **Dead-letter messages you cannot process** instead of requeueing them forever: configure a dead-letter exchange on
  the queue ([RabbitMQ: Dead Letter Exchanges](https://www.rabbitmq.com/docs/dlx)).
- **Limit what a queue keeps** when nobody consumes it, for example with a length limit or a message TTL, because the
  messages carry personal data ([RabbitMQ: Queue Length Limit](https://www.rabbitmq.com/docs/maxlength)).
- **Use `realmId`, not the realm name in the routing key, when the realm must be exact**: dots and non-Latin letters
  are removed from names in routing keys.
