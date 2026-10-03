package com.example.shortlink.stats.messaging;



import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class VisitMqRuntimeTest {
    @Test
    void contextCloseRejectsLateTopologyCompletionAndRepeatedReady() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var admin = mock(RabbitAdmin.class);
        doAnswer(
                        call -> {
                            entered.countDown();
                            boolean interrupted = false;
                            try {
                                while (release.getCount() > 0) {
                                    try {
                                        release.await();
                                    } catch (InterruptedException stopped) {
                                        interrupted = true;
                                    }
                                }
                            } finally {
                                returned.countDown();
                                if (interrupted) Thread.currentThread().interrupt();
                            }
                            return null;
                        })
                .when(admin)
                .initialize();
        var listener = mock(SimpleMessageListenerContainer.class);
        doAnswer(
                        call -> {
                            call.getArgument(0, Runnable.class).run();
                            return null;
                        })
                .when(listener)
                .stop(any(Runnable.class));
        var properties =
                new VisitRabbitProperties(
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        true);
        var recorder =
                new AsyncVisitRecorder(
                        properties,
                        mock(RabbitTemplate.class),
                        new VisitMessageCodec(),
                        mock(CachingConnectionFactory.class));
        var runtime = new VisitMqRuntime(recorder, admin, listener, properties);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(VisitMqRuntime.class, () -> runtime);
            context.refresh();
            runtime.ready();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            long began = System.nanoTime();
            context.close();
            assertThat(Duration.ofNanos(System.nanoTime() - began))
                    .isLessThan(Duration.ofSeconds(3));
            runtime.ready();
            runtime.close();
            release.countDown();
            assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
            // Let the completed declaration reach the startup gate before asserting no consumer.
            verify(listener, after(200).never()).start();

            verify(listener, times(1)).stop(any(Runnable.class));
            verify(admin, times(1)).initialize();
        } finally {
            release.countDown();
            runtime.close();
        }
    }
}
