package com.example.shortlink.stats.messaging;

import com.example.shortlink.stats.VisitPersistence;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VisitRabbitProperties.class)
public class VisitRabbitConfiguration {
    @Bean
    static org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor
            publisherDestructionOwnership() {
        return new org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor() {
            @Override
            public void postProcessMergedBeanDefinition(
                    org.springframework.beans.factory.support.RootBeanDefinition definition,
                    Class<?> type,
                    String name) {
                if (name.equals("visitPublisherConnectionFactory")
                        || name.equals("visitConsumerConnectionFactory")
                        || name.equals("visitListener"))
                    definition.registerExternallyManagedDestroyMethod("destroy");
            }
        };
    }

    public static final String EXCHANGE = "shortlink.visit.x",
            QUEUE = "shortlink.visit.stats.q",
            KEY = "visit.occurred.v1";
    public static final String DLX = "shortlink.visit.dlx",
            DLQ = "shortlink.visit.stats.dlq",
            DEAD_KEY = "visit.failed.v1";

    @Bean
    VisitMessageCodec visitMessageCodec() {
        return new VisitMessageCodec();
    }

    private CachingConnectionFactory factory(
            VisitRabbitProperties p,
            boolean publisher,
            java.util.concurrent.ExecutorService executor) {
        var nativeFactory = new com.rabbitmq.client.ConnectionFactory();
        nativeFactory.setHost(p.host());
        nativeFactory.setPort(p.port());
        nativeFactory.setUsername(p.username());
        nativeFactory.setPassword(p.password());
        nativeFactory.setVirtualHost(p.virtualHost());
        nativeFactory.setExceptionHandler(
                new com.rabbitmq.client.impl.DefaultExceptionHandler() {
                    @Override
                    protected void log(String ignored, Throwable failure) {
                        org.slf4j.LoggerFactory.getLogger(VisitRabbitConfiguration.class)
                                .error("Visit MQ degraded: category=driver");
                    }
                });
        nativeFactory.setConnectionTimeout(p.connectionTimeoutMs());
        nativeFactory.setHandshakeTimeout(p.handshakeTimeoutMs());
        nativeFactory.setRequestedHeartbeat(p.heartbeatSeconds());
        nativeFactory.setAutomaticRecoveryEnabled(false);
        nativeFactory.setChannelRpcTimeout(1000);
        nativeFactory.setShutdownTimeout(500);
        nativeFactory.setThreadFactory(
                r -> {
                    var thread =
                            new Thread(
                                    r,
                                    publisher ? "visit-publisher-native" : "visit-consumer-native");
                    thread.setDaemon(true);
                    return thread;
                });
        var factory = new VisitConnectionFactory(nativeFactory);
        factory.setCloseTimeout(500);
        factory.setExecutor(executor);
        factory.setConnectionNameStrategy(
                ignored -> publisher ? "visit-publisher" : "visit-consumer");
        if (publisher) {
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
            factory.setChannelCacheSize(p.channelLimit());
            factory.setChannelCheckoutTimeout(p.channelCheckoutMs());
            factory.setCloseTimeout(500);
            factory.setExecutor(executor);
        }
        return factory;
    }

    private static java.util.concurrent.ExecutorService boundedExecutor(
            int threads, int capacity, String name) {
        return new java.util.concurrent.ThreadPoolExecutor(
                threads,
                threads,
                0L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(capacity),
                r -> {
                    var thread = new Thread(r, name);
                    thread.setDaemon(true);
                    return thread;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "visitPublisherFrameworkExecutor", destroyMethod = "shutdownNow")
    java.util.concurrent.ExecutorService publisherFrameworkExecutor() {
        return boundedExecutor(2, 32, "visit-publisher-framework");
    }

    @Bean(name = "visitPublisherConnectionFactory", destroyMethod = "")
    CachingConnectionFactory publisherFactory(
            VisitRabbitProperties p,
            @Qualifier("visitPublisherFrameworkExecutor")
                    java.util.concurrent.ExecutorService executor) {
        return factory(p, true, executor);
    }

    @Bean(name = "visitConsumerFrameworkExecutor", destroyMethod = "shutdownNow")
    java.util.concurrent.ExecutorService consumerFrameworkExecutor() {
        return boundedExecutor(4, 64, "visit-consumer-framework");
    }

    @Bean(name = "visitListenerExecutor", destroyMethod = "shutdownNow")
    java.util.concurrent.ExecutorService listenerExecutor() {
        return boundedExecutor(1, 1, "visit-consumer-listener");
    }

    @Bean(name = "visitConsumerConnectionFactory", destroyMethod = "")
    @Primary
    CachingConnectionFactory consumerFactory(
            VisitRabbitProperties p,
            @Qualifier("visitConsumerFrameworkExecutor")
                    java.util.concurrent.ExecutorService executor) {
        return factory(p, false, executor);
    }

    @Bean(name = "visitRabbitTemplate")
    RabbitTemplate template(
            @Qualifier("visitPublisherConnectionFactory") CachingConnectionFactory factory) {
        var t = new RabbitTemplate(factory);
        t.setMandatory(true);
        return t;
    }

    @Bean(name = "visitRabbitAdmin")
    RabbitAdmin admin(
            @Qualifier("visitConsumerConnectionFactory") CachingConnectionFactory factory) {
        var a = new RabbitAdmin(factory);
        a.setAutoStartup(false);
        return a;
    }

    @Bean
    DirectExchange visitExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue visitQueue() {
        return QueueBuilder.durable(QUEUE).withArgument("x-queue-type", "classic").build();
    }

    @Bean
    Binding visitBinding() {
        return BindingBuilder.bind(visitQueue()).to(visitExchange()).with(KEY);
    }

    @Bean
    DirectExchange visitDeadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    Queue visitDeadLetterQueue() {
        return QueueBuilder.durable(DLQ).withArgument("x-queue-type", "classic").build();
    }

    @Bean
    Binding visitDeadLetterBinding() {
        return BindingBuilder.bind(visitDeadLetterQueue())
                .to(visitDeadLetterExchange())
                .with(DEAD_KEY);
    }

    @Bean
    VisitConsumer visitConsumer(
            VisitMessageCodec codec, VisitPersistence persistence, java.time.Clock clock) {
        return new VisitConsumer(codec, persistence, clock);
    }

    @Bean(name = "visitListener")
    SimpleMessageListenerContainer listener(
            @Qualifier("visitConsumerConnectionFactory") CachingConnectionFactory factory,
            VisitConsumer consumer,
            @Qualifier("visitListenerExecutor") java.util.concurrent.ExecutorService executor) {
        var c = new VisitListenerContainer(factory);
        c.setTaskExecutor(executor);
        c.setConsumerStartTimeout(1500);
        c.setPossibleAuthenticationFailureFatal(false);
        c.setAutoDeclare(false);
        c.setQueueNames(QUEUE);
        c.setAutoStartup(false);
        c.setConcurrentConsumers(1);
        c.setMaxConcurrentConsumers(1);
        c.setPrefetchCount(10);
        c.setBatchSize(1);
        c.setAcknowledgeMode(AcknowledgeMode.AUTO);
        c.setDefaultRequeueRejected(false);
        c.setMissingQueuesFatal(false);
        c.setShutdownTimeout(1000);
        c.setForceStop(true);
        c.setMessageListener(consumer);

        c.setErrorHandler(
                failure ->
                        org.slf4j.LoggerFactory.getLogger(VisitRabbitConfiguration.class)
                                .warn("Visit listener failed: category=consumption"));
        return c;
    }
}
