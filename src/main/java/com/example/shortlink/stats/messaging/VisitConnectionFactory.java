package com.example.shortlink.stats.messaging;


import com.rabbitmq.client.ConnectionFactory;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/** The recorder owns network teardown; Spring lifecycle stop must never reset on its caller. */
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
