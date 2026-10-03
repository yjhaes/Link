package com.example.shortlink.stats.messaging;

import com.rabbitmq.client.ConnectionFactory;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime selects teardown and background adapters execute it; framework stop never resets on its
 * caller.
 */
final class VisitConnectionFactory extends CachingConnectionFactory {
    private final AtomicBoolean lifecycleRunning = new AtomicBoolean();

    VisitConnectionFactory(ConnectionFactory nativeFactory) {
        super(nativeFactory);
    }

    @Override
    public void start() {
        lifecycleRunning.set(true);
    }

    @Override
    public void stop() {
        lifecycleRunning.set(false);
    }

    @Override
    public boolean isRunning() {
        return lifecycleRunning.get();
    }
}
