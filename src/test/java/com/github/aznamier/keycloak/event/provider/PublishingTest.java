package com.github.aznamier.keycloak.event.provider;

import static com.github.aznamier.keycloak.event.provider.ContractFixtures.map;
import static com.github.aznamier.keycloak.event.provider.ContractFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.BlockedListener;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;

/**
 * Publishing on the channel that all providers of one factory share. Keycloak publishes on its request
 * threads, so several requests use the channel at the same time.
 *
 * <p>amqp-client is replaced by doubles. Where it matters they behave like amqp-client 5.37.0, which writes
 * a message while holding a lock of the channel and the lock of the socket output, and writes
 * {@code connection.close} through the same socket output when a connection is closed.
 */
class PublishingTest {

	private static final String REALM_ID = "realm-id";
	/** Plain connections even if the environment sets KK_TO_RMQ_USE_TLS: the doubles do not speak TLS. */
	private static final Config.Scope PLAIN_CONNECTION = scope(map("use_tls", "false"));
	/** Parent of the listener's loggers; held here because java.util.logging keeps loggers only weakly. */
	private static final Logger LISTENER_LOG = Logger.getLogger(RabbitMqEventListenerProvider.class.getPackageName());

	private final ExecutorService requestThreads = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable);
		thread.setDaemon(true);
		return thread;
	});

	private final List<String> logLines = new CopyOnWriteArrayList<>();
	private final Handler capture = new Handler() {
		@Override
		public void publish(LogRecord record) {
			String message = record.getMessage();
			if (record.getParameters() != null && record.getParameters().length > 0) {
				message = String.format(message, record.getParameters());
			}
			logLines.add(levelAsKeycloakLogsIt(record.getLevel()) + " " + message);
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}
	};

	@BeforeEach
	void captureLog() {
		LISTENER_LOG.addHandler(capture);
	}

	@AfterEach
	void stopRequests() {
		LISTENER_LOG.removeHandler(capture);
		requestThreads.shutdownNow();
	}

	// --- the critical section ----------------------------------------------------------------------

	@Test
	void requestsPublishOnTheSharedChannelOneAtATime() throws Exception {
		// RabbitMQ Java client guide: Channel#basicPublish on a shared channel must be invoked in a critical section
		AmqpClientDouble client = new AmqpClientDouble();
		AtomicInteger inside = new AtomicInteger();
		AtomicInteger mostInside = new AtomicInteger();
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch firstInside = new CountDownLatch(1);
		CountDownLatch secondInside = new CountDownLatch(1);
		client.onPublish(invocation -> {
			mostInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
			try {
				if (calls.incrementAndGet() == 1) {
					firstInside.countDown();
					// gives the second request a second to come in, which it can only if nothing keeps it out
					secondInside.await(1, TimeUnit.SECONDS);
				} else {
					secondInside.countDown();
				}
			} finally {
				inside.decrementAndGet();
			}
			return null;
		});
		RabbitMqEventListenerProviderFactory factory = factory(client, 30_000);

		Future<?> first = request(factory, "first");
		assertThat(firstInside.await(10, TimeUnit.SECONDS)).isTrue();
		Future<?> second = request(factory, "second");
		first.get(10, TimeUnit.SECONDS);
		second.get(10, TimeUnit.SECONDS);

		assertThat(mostInside.get()).as("threads inside Channel#basicPublish at the same time").isEqualTo(1);
		assertThat(client.publishCalls()).isEqualTo(2);
	}

	@Test
	void manyRequestsAtOnceStillPublishOneAtATimeAndLoseNothing() throws Exception {
		AmqpClientDouble client = new AmqpClientDouble();
		AtomicInteger inside = new AtomicInteger();
		AtomicInteger mostInside = new AtomicInteger();
		client.onPublish(invocation -> {
			mostInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
			Thread.yield();
			inside.decrementAndGet();
			return null;
		});
		RabbitMqEventListenerProviderFactory factory = factory(client);
		int threads = 16;
		int perThread = 50;

		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> requests = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			String thread = "user" + t;
			requests.add(requestThreads.submit(() -> {
				start.await();
				for (int i = 0; i < perThread; i++) {
					login(factory, thread + "-" + i);
				}
				return null;
			}));
		}
		start.countDown();
		for (Future<?> request : requests) {
			request.get(60, TimeUnit.SECONDS);
		}

		assertThat(mostInside.get()).as("threads inside Channel#basicPublish at the same time").isEqualTo(1);
		assertThat(client.publishCalls()).isEqualTo(threads * perThread);
		assertThat(logLines).noneMatch(line -> line.startsWith("ERROR"));
	}

	// --- a publish that RabbitMQ does not take ------------------------------------------------------

	@Test
	void aPublishThatRabbitMqDoesNotTakeHoldsUpOtherRequestsOnlyForTheMaximumWait() throws Exception {
		AmqpClientDouble client = new AmqpClientDouble();
		CountDownLatch stuck = new CountDownLatch(1);
		CountDownLatch rabbitMqReads = new CountDownLatch(1);
		client.onPublish(firstWriteNotRead(client, stuck, rabbitMqReads));
		RabbitMqEventListenerProviderFactory factory = factory(client, 200);
		try {
			Future<?> first = request(factory, "first");
			assertThat(stuck.await(10, TimeUnit.SECONDS)).isTrue();

			Future<?> second = request(factory, "second");
			assertThatCode(() -> second.get(5, TimeUnit.SECONDS)).as("request while a write is stuck")
					.doesNotThrowAnyException();
			// its event is dropped, and logged, instead of waiting behind the stuck one
			assertThat(client.publishCalls()).isEqualTo(1);
			assertThat(logLines).anyMatch(line -> line.startsWith("ERROR") && line.contains("200 ms"));

			rabbitMqReads.countDown();
			first.get(10, TimeUnit.SECONDS);
			request(factory, "third").get(10, TimeUnit.SECONDS);
			assertThat(client.publishCalls()).isEqualTo(2);
		} finally {
			rabbitMqReads.countDown();
		}
	}

	@Test
	void requestsDoNotWaitAtAllForAPublishThatIsAlreadyOverdue() throws Exception {
		AmqpClientDouble client = new AmqpClientDouble();
		CountDownLatch stuck = new CountDownLatch(1);
		CountDownLatch rabbitMqReads = new CountDownLatch(1);
		client.onPublish(firstWriteNotRead(client, stuck, rabbitMqReads));
		RabbitMqEventListenerProviderFactory factory = factory(client, 1000);
		try {
			request(factory, "first");
			assertThat(stuck.await(10, TimeUnit.SECONDS)).isTrue();
			Thread.sleep(1200); // the write has now been stuck for longer than the maximum wait

			long start = System.nanoTime();
			Future<?> later = request(factory, "later");
			assertThatCode(() -> later.get(5, TimeUnit.SECONDS)).as("request after the maximum wait")
					.doesNotThrowAnyException();
			assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).as("milliseconds the request took")
					.isLessThan(500);
		} finally {
			rabbitMqReads.countDown();
		}
	}

	// --- resource alarms ------------------------------------------------------------------------------

	@Test
	void eventsAreDroppedWithoutWaitingWhileRabbitMqBlocksTheConnection() throws Exception {
		// During a resource alarm RabbitMQ stops reading from publishing connections, so a write blocks
		// until the alarm clears; it tells the client with connection.blocked.
		AmqpClientDouble client = new AmqpClientDouble();
		AtomicBoolean alarm = new AtomicBoolean();
		CountDownLatch alarmCleared = new CountDownLatch(1);
		AtomicInteger written = new AtomicInteger();
		client.onPublish(invocation -> {
			if (alarm.get()) {
				alarmCleared.await(30, TimeUnit.SECONDS);
			}
			written.incrementAndGet();
			return null;
		});
		RabbitMqEventListenerProviderFactory factory = factory(client);
		try {
			request(factory, "before").get(5, TimeUnit.SECONDS);
			assertThat(written.get()).isEqualTo(1);

			alarm.set(true);
			client.block("low on disk");
			Future<?> during = request(factory, "during");
			assertThatCode(() -> during.get(5, TimeUnit.SECONDS)).as("request during the alarm")
					.doesNotThrowAnyException();
			assertThat(client.publishCalls()).as("publishes during the alarm").isEqualTo(1);
			assertThat(logLines).anyMatch(line -> line.startsWith("WARN") && line.contains("low on disk"));
			assertThat(logLines).anyMatch(line -> line.startsWith("ERROR") && line.contains("low on disk"));

			alarm.set(false);
			alarmCleared.countDown();
			client.unblock();
			request(factory, "after").get(5, TimeUnit.SECONDS);
			assertThat(written.get()).isEqualTo(2);
		} finally {
			alarmCleared.countDown();
		}
	}

	@Test
	void aRecoveredConnectionIsNotBlocked() throws Exception {
		AmqpClientDouble client = new AmqpClientDouble();
		client.onPublish(invocation -> null);
		RabbitMqEventListenerProviderFactory factory = factory(client);
		request(factory, "before").get(5, TimeUnit.SECONDS);

		client.block("low on memory");
		request(factory, "blocked").get(5, TimeUnit.SECONDS);
		assertThat(client.publishCalls()).isEqualTo(1);

		// the broker restarts: the blocked connection is gone, amqp-client opens a new one, which is not blocked
		client.loseConnection();
		client.open.set(true);
		request(factory, "recovered").get(5, TimeUnit.SECONDS);
		assertThat(client.publishCalls()).isEqualTo(2);
	}

	// --- connection recovery ---------------------------------------------------------------------------

	@Test
	void aConnectionThatAmqpClientIsRecoveringIsNotReplaced() throws Exception {
		AmqpClientDouble client = new AmqpClientDouble();
		client.onPublish(invocation -> null);
		RabbitMqEventListenerProviderFactory factory = factory(client);
		request(factory, "connected").get(5, TimeUnit.SECONDS);

		client.loseConnection(); // amqp-client recovers it in the background
		assertThatCode(() -> request(factory, "while recovering").get(5, TimeUnit.SECONDS))
				.doesNotThrowAnyException();

		client.open.set(true); // recovered: the same connection and channel objects are open again
		request(factory, "recovered").get(5, TimeUnit.SECONDS);

		verify(client.connectionFactory, times(1)).newConnection();
		verify(client.connection, times(1)).createChannel();
		assertThat(client.publishCalls()).isEqualTo(2);
	}

	@Test
	void aConnectionLostWhileTheChannelIsOpenedDoesNotFailTheRequest() throws Exception {
		AmqpClientDouble client = new AmqpClientDouble();
		when(client.connection.createChannel())
				.thenThrow(new AlreadyClosedException(new ShutdownSignalException(true, false, null, client.connection)));
		RabbitMqEventListenerProviderFactory factory = factory(client);

		assertThatCode(() -> request(factory, "unlucky").get(5, TimeUnit.SECONDS)).doesNotThrowAnyException();
	}

	// --- shutdown ---------------------------------------------------------------------------------------

	@Test
	void closeDoesNotWaitForAWriteThatRabbitMqDoesNotRead() throws Exception {
		AmqpClientDouble client = new AmqpClientDouble();
		CountDownLatch stuck = new CountDownLatch(1);
		CountDownLatch rabbitMqReads = new CountDownLatch(1);
		client.onPublish(firstWriteNotRead(client, stuck, rabbitMqReads));
		RabbitMqEventListenerProviderFactory factory = factory(client);
		try {
			request(factory, "stuck");
			assertThat(stuck.await(10, TimeUnit.SECONDS)).isTrue();

			Future<?> closing = requestThreads.submit(factory::close);
			assertThatCode(() -> closing.get(10, TimeUnit.SECONDS)).as("close at Keycloak shutdown")
					.doesNotThrowAnyException();
		} finally {
			rabbitMqReads.countDown();
		}
	}

	@Test
	void closeClosesTheConnectionAndNothingReconnectsAfterIt() throws Exception {
		AmqpClientDouble client = new AmqpClientDouble();
		client.onPublish(invocation -> null);
		RabbitMqEventListenerProviderFactory factory = factory(client);
		request(factory, "before close").get(5, TimeUnit.SECONDS);

		factory.close();
		assertThat(client.open.get()).as("connection open after close").isFalse();

		assertThatCode(() -> request(factory, "after close").get(5, TimeUnit.SECONDS)).doesNotThrowAnyException();
		verify(client.connectionFactory, times(1)).newConnection();
		assertThat(client.publishCalls()).isEqualTo(1);
	}

	// --- helpers ------------------------------------------------------------------------------------

	private static String levelAsKeycloakLogsIt(Level level) {
		if (level.intValue() >= Level.SEVERE.intValue()) {
			return "ERROR";
		}
		return level.intValue() >= Level.WARNING.intValue() ? "WARN" : "INFO";
	}

	/** A Keycloak request that logs a user in, on a thread of its own as Keycloak runs requests. */
	private Future<?> request(RabbitMqEventListenerProviderFactory factory, String username) {
		return requestThreads.submit(() -> login(factory, username));
	}

	private static void login(RabbitMqEventListenerProviderFactory factory, String username) {
		ContractFixtures.SessionDouble session = ContractFixtures.session(map(REALM_ID, "acme"), "acme");
		EventListenerProvider provider = factory.create(session.session);
		Event event = new Event();
		event.setTime(System.currentTimeMillis());
		event.setType(EventType.LOGIN);
		event.setRealmId(REALM_ID);
		event.setClientId("web-app");
		event.setDetails(Map.of("username", username));
		provider.onEvent(event);
		session.completeTransaction();
	}

	/**
	 * The first write is not read by RabbitMQ until {@code rabbitMqReads}; as in amqp-client it holds the
	 * channel lock and the socket output lock meanwhile.
	 */
	private static Answer<Void> firstWriteNotRead(AmqpClientDouble client, CountDownLatch stuck,
			CountDownLatch rabbitMqReads) {
		Object channelLock = new Object();
		AtomicInteger writes = new AtomicInteger();
		return invocation -> {
			synchronized (channelLock) {
				synchronized (client.socketOutput) {
					if (writes.incrementAndGet() == 1) {
						stuck.countDown();
						rabbitMqReads.await(30, TimeUnit.SECONDS);
					}
				}
			}
			return null;
		};
	}

	private static RabbitMqEventListenerProviderFactory factory(AmqpClientDouble client) {
		TestFactory factory = new TestFactory(client);
		factory.init(PLAIN_CONNECTION);
		return factory;
	}

	private static RabbitMqEventListenerProviderFactory factory(AmqpClientDouble client, long maxPublishWaitMillis) {
		TimedFactory factory = new TimedFactory(client, maxPublishWaitMillis);
		factory.init(PLAIN_CONNECTION);
		return factory;
	}

	/** The listener's factory on top of the amqp-client double. */
	private static class TestFactory extends RabbitMqEventListenerProviderFactory {
		private final ConnectionFactory connectionFactory;

		TestFactory(AmqpClientDouble client) {
			this.connectionFactory = client.connectionFactory;
		}

		@Override
		ConnectionFactory newConnectionFactory() {
			return connectionFactory;
		}
	}

	/** A factory whose publishes wait for the one in progress at most the given time. */
	private static final class TimedFactory extends TestFactory {
		private final long maxPublishWaitMillis;

		TimedFactory(AmqpClientDouble client, long maxPublishWaitMillis) {
			super(client);
			this.maxPublishWaitMillis = maxPublishWaitMillis;
		}

		@Override
		long maxPublishWaitMillis() {
			return maxPublishWaitMillis;
		}
	}

	/** amqp-client as the factory uses it: a connection factory, a connection and a channel. */
	private static final class AmqpClientDouble {
		/** Held while a frame is written, like the socket output lock of amqp-client. */
		final Object socketOutput = new Object();
		/** Whether the connection, and with it the channel, is open. */
		final AtomicBoolean open = new AtomicBoolean(true);
		final List<BlockedListener> blockedListeners = new CopyOnWriteArrayList<>();
		final List<ShutdownListener> shutdownListeners = new CopyOnWriteArrayList<>();

		final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
		final Connection connection = mock(Connection.class, withSettings().defaultAnswer(this::closingWritesAFrame));
		final Channel channel = mock(Channel.class, withSettings().defaultAnswer(this::closingWritesAFrame));

		AmqpClientDouble() throws IOException, TimeoutException {
			when(connectionFactory.newConnection()).thenReturn(connection);
			when(connection.isOpen()).thenAnswer(invocation -> open.get());
			when(connection.createChannel()).thenReturn(channel);
			when(channel.isOpen()).thenAnswer(invocation -> open.get());
			doAnswer(invocation -> {
				blockedListeners.add(invocation.getArgument(0));
				return null;
			}).when(connection).addBlockedListener(any(BlockedListener.class));
			doAnswer(invocation -> {
				shutdownListeners.add(invocation.getArgument(0));
				return null;
			}).when(connection).addShutdownListener(any(ShutdownListener.class));
		}

		/** Closing a channel or a connection writes a frame, then the connection is closed. */
		private Object closingWritesAFrame(InvocationOnMock invocation) throws Throwable {
			String method = invocation.getMethod().getName();
			if (method.equals("close") || method.equals("abort")) {
				synchronized (socketOutput) {
					open.set(false);
				}
				return null;
			}
			return RETURNS_DEFAULTS.answer(invocation);
		}

		void onPublish(Answer<?> answer) throws IOException {
			doAnswer(answer).when(channel).basicPublish(anyString(), anyString(), any(AMQP.BasicProperties.class),
					any(byte[].class));
		}

		long publishCalls() {
			return mockingDetails(channel).getInvocations().stream()
					.filter(invocation -> invocation.getMethod().getName().equals("basicPublish")).count();
		}

		/** RabbitMQ sends connection.blocked. */
		void block(String reason) throws IOException {
			for (BlockedListener listener : blockedListeners) {
				listener.handleBlocked(reason);
			}
		}

		/** RabbitMQ sends connection.unblocked. */
		void unblock() throws IOException {
			for (BlockedListener listener : blockedListeners) {
				listener.handleUnblocked();
			}
		}

		/** The connection fails, for example because the broker restarts. */
		void loseConnection() {
			open.set(false);
			ShutdownSignalException cause = new ShutdownSignalException(true, false, null, connection);
			for (ShutdownListener listener : shutdownListeners) {
				listener.shutdownCompleted(cause);
			}
		}
	}
}
