package com.github.aznamier.keycloak.event.provider;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.jboss.logging.Logger;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.BlockedListener;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;

/**
 * Publishing on a channel that several Keycloak request threads use at the same time.
 *
 * <p>The RabbitMQ Java client requires {@code basicPublish} on a shared channel to run in a critical
 * section (https://www.rabbitmq.com/client-libraries/java-api-guide#concurrency). Requests must not wait
 * for RabbitMQ without bound, so a message is dropped, and logged, when
 * <ul>
 * <li>RabbitMQ blocks the connection because of a resource alarm (memory or disk space): it does not read
 * from the connection until the alarm clears, so the write would wait as long;</li>
 * <li>the messages before it have not been written within the maximum wait, for example because RabbitMQ
 * or the network does not take data. Once a publish has taken longer than that, the following ones do not
 * wait for it at all.</li>
 * </ul>
 * The factory registers the gate on its connection for {@code connection.blocked} and
 * {@code connection.unblocked}, and for the end of the connection: a connection that amqp-client opens to
 * recover a lost one is not blocked.
 */
final class PublishGate implements BlockedListener, ShutdownListener {

	/** The longest a publish waits for the ones before it. */
	static final long MAX_WAIT_MILLIS = 1000;

	private static final Logger log = Logger.getLogger(PublishGate.class);
	private static final long NOT_PUBLISHING = Long.MIN_VALUE;

	/** Fair, so that a waiting publish is not overtaken by later ones until its wait runs out. */
	private final ReentrantLock lock = new ReentrantLock(true);
	private final long maxWaitMillis;
	/** Why RabbitMQ blocks the connection; {@code null} while it does not. */
	private volatile String blockedReason;
	/** {@link System#nanoTime()} at the start of the publish in progress, or {@link #NOT_PUBLISHING}. */
	private volatile long publishingSince = NOT_PUBLISHING;

	PublishGate() {
		this(MAX_WAIT_MILLIS);
	}

	PublishGate(long maxWaitMillis) {
		this.maxWaitMillis = maxWaitMillis;
	}

	/**
	 * Publishes the message on the channel, unless it has to be dropped.
	 *
	 * @return whether the message was handed to the channel; if not, the reason has been logged
	 */
	boolean publish(Channel channel, String exchange, String routingKey, BasicProperties props, byte[] body)
			throws IOException, InterruptedException {
		if (isBlocked(routingKey)) {
			return false;
		}
		if (!lock.tryLock(nanosToWait(), TimeUnit.NANOSECONDS)) {
			log.errorf("keycloak-to-rabbitmq ERROR sending message: %s"
					+ " (earlier messages have not reached RabbitMQ within %d ms)", routingKey, maxWaitMillis);
			return false;
		}
		try {
			if (isBlocked(routingKey)) { // RabbitMQ may have blocked the connection while this thread waited
				return false;
			}
			publishingSince = System.nanoTime();
			channel.basicPublish(exchange, routingKey, props, body);
			return true;
		} finally {
			publishingSince = NOT_PUBLISHING;
			lock.unlock();
		}
	}

	private boolean isBlocked(String routingKey) {
		String reason = blockedReason;
		if (reason != null) {
			log.errorf("keycloak-to-rabbitmq ERROR sending message: %s (RabbitMQ blocks publishing: %s)", routingKey, reason);
		}
		return reason != null;
	}

	/** Up to the maximum wait, counted from the start of the publish in progress if there is one. */
	private long nanosToWait() {
		long maxWait = TimeUnit.MILLISECONDS.toNanos(maxWaitMillis);
		long since = publishingSince;
		return since == NOT_PUBLISHING ? maxWait : maxWait - (System.nanoTime() - since);
	}

	@Override
	public void handleBlocked(String reason) {
		blockedReason = reason == null || reason.isEmpty() ? "no reason given" : reason;
		log.warnf("keycloak-to-rabbitmq: RabbitMQ blocked the connection (%s); events are dropped until it is unblocked",
				blockedReason);
	}

	@Override
	public void handleUnblocked() {
		blockedReason = null;
		log.info("keycloak-to-rabbitmq: RabbitMQ unblocked the connection");
	}

	@Override
	public void shutdownCompleted(ShutdownSignalException cause) {
		blockedReason = null;
	}
}
