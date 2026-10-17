// Modified in 2026 by the contributors of https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq
// (fork of aznamier/keycloak-event-listener-rabbitmq); see NOTICE and CHANGELOG.md.
package com.github.aznamier.keycloak.event.provider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.jboss.logging.Logger;
import org.keycloak.Config.Scope;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownSignalException;

public class RabbitMqEventListenerProviderFactory implements EventListenerProviderFactory {

    private static final Logger log = Logger.getLogger(RabbitMqEventListenerProviderFactory.class);
    /** How long {@link #close()} waits for the connection to close. */
    private static final int CLOSE_TIMEOUT_MILLIS = 2000;
    private RabbitMqConfig cfg;
    private ConnectionFactory connectionFactory;
    private Connection connection;
    private Channel channel;
    /** The providers all publish on the one channel, through this gate. */
    private PublishGate publishGate;
    /** Set when TLS was requested but could not be set up: the listener then never connects in plain text. */
    private boolean tlsUnavailable;
    private boolean closed;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new RabbitMqEventListenerProvider(channelForSession(), publishGate, session, cfg);
    }

    /**
     * The channel to publish on, connecting first if there is no connection yet; {@code null} if there is none.
     * Once open, the connection is kept: amqp-client recovers it after a network failure or a broker restart,
     * and a second connection opened in the meantime would stay open next to the recovered one.
     */
    private synchronized Channel channelForSession() {
        if (tlsUnavailable || closed) {
            return null;
        }
        try {
            if (connection == null) {
                connection = connectionFactory.newConnection();
                connection.addBlockedListener(publishGate);
                connection.addShutdownListener(publishGate);
            }
            if (!connection.isOpen()) {
                return null; // amqp-client is recovering it
            }
            if (channel == null || !channel.isOpen()) {
                channel = connection.createChannel();
                channel.addShutdownListener(RabbitMqEventListenerProviderFactory::logChannelClosedByRabbitMq);
            }
            return channel;
        }
        catch (IOException | TimeoutException | RuntimeException e) {
            log.error("keycloak-to-rabbitmq ERROR on connection to rabbitmq", e);
            return null;
        }
    }

    /**
     * RabbitMQ closes the channel when it refuses a published message, for example because the exchange does not
     * exist. The message is lost, and the publish itself does not fail: without this line the loss would not show in
     * the log. The next session gets a new channel.
     */
    private static void logChannelClosedByRabbitMq(ShutdownSignalException cause) {
        if (cause.isHardError() || cause.isInitiatedByApplication()) {
            return; // the connection ended, or the listener closed it
        }
        String reason = cause.getReason() instanceof AMQP.Channel.Close close ? close.getReplyText() : cause.getMessage();
        log.warnf("keycloak-to-rabbitmq: RabbitMQ refused an event and closed the channel: %s", reason);
    }

    @Override
    public void init(Scope config) {
        cfg = RabbitMqConfig.createFromScope(config);
        this.publishGate = new PublishGate(maxPublishWaitMillis());
        this.connectionFactory = newConnectionFactory();

        this.connectionFactory.setUsername(cfg.getUsername());
        this.connectionFactory.setPassword(cfg.getPassword());
        // The setting is empty by default. RabbitMQ refuses a connection to the virtual host "" (530 NOT_ALLOWED,
        // "vhost  not found") unless one with that name was created. Without a setting amqp-client connects to its
        // default virtual host, "/", so a virtual host named "" cannot be reached.
        if (isSet(cfg.getVhost())) {
            this.connectionFactory.setVirtualHost(cfg.getVhost());
        }
        this.connectionFactory.setHost(cfg.getHostUrl());
        this.connectionFactory.setPort(cfg.getPort());
        this.connectionFactory.setAutomaticRecoveryEnabled(true);
        // When a connection stops carrying data, amqp-client notices the missed heartbeats, but before it closes
        // the socket it waits for the channel, which a publish stuck in a write holds. Without a shutdown executor
        // it waits until the operating system gives up on the write, many minutes; with one it stops waiting
        // after 1.05 heartbeat intervals, closes the socket and recovers the connection.
        this.connectionFactory.setShutdownExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "keycloak-to-rabbitmq-shutdown");
            thread.setDaemon(true);
            return thread;
        }));

        if (cfg.getUseTls()) {
            try {
                // amqp-client also turns on hostname verification here
                this.connectionFactory.useSslProtocol(createSslContext(cfg));
            }
            catch (Exception e) {
                tlsUnavailable = true;
                log.error("keycloak-to-rabbitmq: could not set up TLS; events will not be sent to RabbitMQ", e);
            }
        }
    }

    /** Separate so that tests can observe how the connection factory is configured. */
    ConnectionFactory newConnectionFactory() {
        return new ConnectionFactory();
    }

    /** The longest a publish waits for the ones before it; separate so that tests can shorten it. */
    long maxPublishWaitMillis() {
        return PublishGate.MAX_WAIT_MILLIS;
    }

    /**
     * TLS context that verifies the broker certificate: against the configured trust store, or against
     * the JVM default trust store when none is configured. A key store, when configured, provides the
     * client certificate.
     */
    static SSLContext createSslContext(RabbitMqConfig cfg) throws GeneralSecurityException, IOException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(isSet(cfg.getTrustStore()) ? loadKeyStore(cfg.getTrustStore(), "JKS", cfg.getTrustStorePass()) : null);

        KeyManager[] keyManagers = null;
        if (isSet(cfg.getKeyStore())) {
            char[] keyPassphrase = passphrase(cfg.getKeyStorePass());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(loadKeyStore(cfg.getKeyStore(), "PKCS12", cfg.getKeyStorePass()), keyPassphrase);
            keyManagers = kmf.getKeyManagers();
        }

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, tmf.getTrustManagers(), null);
        return context;
    }

    private static KeyStore loadKeyStore(String path, String type, String password)
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            store.load(in, passphrase(password));
        }
        return store;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isEmpty();
    }

    private static char[] passphrase(String password) {
        return password == null ? new char[0] : password.toCharArray();
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {

    }

    @Override
    public void close() {
        Connection open;
        synchronized (this) {
            closed = true;
            open = connection;
        }
        if (open == null) {
            return;
        }
        // Closing the connection closes the channel too. Closing writes to the socket, which a publish that RabbitMQ
        // does not read (resource alarm, network failure) can hold indefinitely: it runs on a thread of its own, and
        // Keycloak's shutdown waits for it only briefly.
        Thread closing = new Thread(() -> {
            try {
                open.abort(CLOSE_TIMEOUT_MILLIS);
            }
            catch (RuntimeException e) {
                log.error("keycloak-to-rabbitmq ERROR on close", e);
            }
        }, "keycloak-to-rabbitmq-close");
        closing.setDaemon(true);
        closing.start();
        try {
            closing.join(CLOSE_TIMEOUT_MILLIS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (closing.isAlive()) {
            log.warnf("keycloak-to-rabbitmq: the connection to RabbitMQ did not close within %d ms",
                    CLOSE_TIMEOUT_MILLIS);
        }
    }

    @Override
    public String getId() {
        return "keycloak-to-rabbitmq";
    }

}
