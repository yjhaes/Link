package com.example.shortlink.stats.messaging;


import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** One terminal close action, including native teardown, regardless of lifecycle callbacks. */
final class VisitListenerContainer extends SimpleMessageListenerContainer {
    private final CachingConnectionFactory factory;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final ExecutorService closer =
            Executors.newSingleThreadExecutor(
                    r -> {
                        var t = new Thread(r, "visit-consumer-close");
                        t.setDaemon(true);
                        return t;
                    });

    VisitListenerContainer(CachingConnectionFactory factory) {
        super(factory);
        this.factory = factory;
    }

    @Override
    public synchronized void start() {
        if (!closing.get()) super.start();
    }

    @Override
    public void stop(Runnable callback) {
        closed.whenComplete((ignored, failure) -> callback.run());
        if (closing.compareAndSet(false, true)) {
            closer.execute(
                    () -> {
                        try {
                            synchronized (this) {
                                super.stop();
                            }
                            factory.destroy();
                            closed.complete(null);
                        } catch (Exception failure) {
                            org.slf4j.LoggerFactory.getLogger(VisitListenerContainer.class)
                                    .warn("Visit MQ degraded: category=consumer-close");
                            closed.completeExceptionally(
                                    new IllegalStateException("Consumer close failed."));
                        } finally {
                            closer.shutdown();
                        }
                    });
        }
    }
}
