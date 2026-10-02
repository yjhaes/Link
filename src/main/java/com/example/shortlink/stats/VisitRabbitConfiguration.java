package com.example.shortlink.stats;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(VisitRabbitProperties.class)
public class VisitRabbitConfiguration {
 @Bean static org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor publisherDestructionOwnership(){
  return new org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor(){
   @Override public void postProcessMergedBeanDefinition(org.springframework.beans.factory.support.RootBeanDefinition definition,Class<?> type,String name){
    if(name.equals("visitPublisherConnectionFactory"))definition.registerExternallyManagedDestroyMethod("destroy");
   }
  };
 }
 public static final String EXCHANGE="shortlink.visit.x", QUEUE="shortlink.visit.stats.q", KEY="visit.occurred.v1";
 @Bean VisitMessageCodec visitMessageCodec(){return new VisitMessageCodec();}
 private CachingConnectionFactory factory(VisitRabbitProperties p, boolean publisher, java.util.concurrent.ExecutorService executor) {
  var nativeFactory=new com.rabbitmq.client.ConnectionFactory();
  nativeFactory.setHost(p.host()); nativeFactory.setPort(p.port()); nativeFactory.setUsername(p.username()); nativeFactory.setPassword(p.password()); nativeFactory.setVirtualHost(p.virtualHost());
  nativeFactory.setExceptionHandler(new com.rabbitmq.client.impl.DefaultExceptionHandler(){
   @Override protected void log(String ignored,Throwable failure){org.slf4j.LoggerFactory.getLogger(VisitRabbitConfiguration.class).error("Visit MQ degraded: category=driver");}
  });
  nativeFactory.setConnectionTimeout(p.connectionTimeoutMs()); nativeFactory.setHandshakeTimeout(p.handshakeTimeoutMs()); nativeFactory.setRequestedHeartbeat(p.heartbeatSeconds()); nativeFactory.setAutomaticRecoveryEnabled(false);
  var factory=new CachingConnectionFactory(nativeFactory);
  factory.setConnectionNameStrategy(ignored -> publisher ? "visit-publisher" : "visit-consumer");
  if(publisher){ factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); factory.setPublisherReturns(true); factory.setChannelCacheSize(p.channelLimit()); factory.setChannelCheckoutTimeout(p.channelCheckoutMs()); factory.setCloseTimeout(500); factory.setExecutor(executor); }
  return factory;
 }
 @Bean(name="visitPublisherFrameworkExecutor",destroyMethod="shutdownNow") java.util.concurrent.ExecutorService publisherFrameworkExecutor(){return new java.util.concurrent.ThreadPoolExecutor(2,2,0L,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(32),r -> {var thread=new Thread(r,"visit-publisher-framework");thread.setDaemon(true);return thread;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());}
 @Bean(name="visitPublisherConnectionFactory",destroyMethod="") CachingConnectionFactory publisherFactory(VisitRabbitProperties p,@Qualifier("visitPublisherFrameworkExecutor") java.util.concurrent.ExecutorService executor){return factory(p,true,executor);}
 @Bean(name="visitConsumerConnectionFactory") @Primary CachingConnectionFactory consumerFactory(VisitRabbitProperties p){return factory(p,false,null);}
 @Bean(name="visitRabbitTemplate") RabbitTemplate template(@Qualifier("visitPublisherConnectionFactory") CachingConnectionFactory factory){var t=new RabbitTemplate(factory);t.setMandatory(true);return t;}
 @Bean(name="visitRabbitAdmin") RabbitAdmin admin(@Qualifier("visitConsumerConnectionFactory") CachingConnectionFactory factory){var a=new RabbitAdmin(factory);a.setAutoStartup(false);return a;}
 @Bean DirectExchange visitExchange(){return new DirectExchange(EXCHANGE,true,false);}
 @Bean Queue visitQueue(){return QueueBuilder.durable(QUEUE).withArgument("x-queue-type","classic").build();}
 @Bean Binding visitBinding(){return BindingBuilder.bind(visitQueue()).to(visitExchange()).with(KEY);}
 @Bean(name="visitListener") SimpleMessageListenerContainer listener(@Qualifier("visitConsumerConnectionFactory") CachingConnectionFactory factory,VisitMessageCodec codec,VisitPersistence persistence){
  var c=new SimpleMessageListenerContainer(factory); c.setQueueNames(QUEUE);c.setAutoStartup(false);c.setConcurrentConsumers(1);c.setMaxConcurrentConsumers(1);c.setPrefetchCount(10);c.setBatchSize(1);c.setAcknowledgeMode(AcknowledgeMode.AUTO);c.setDefaultRequeueRejected(false);c.setMissingQueuesFatal(false);c.setShutdownTimeout(1000);
  c.setMessageListener((MessageListener) message -> {try { persistence.persist(codec.decode(message.getBody())); } catch(Exception failure) {throw new AmqpRejectAndDontRequeueException("Visit consumption failed.");}});
  c.setErrorHandler(failure -> org.slf4j.LoggerFactory.getLogger(VisitRabbitConfiguration.class).warn("Visit listener failed: category=consumption"));
  return c;
 }
}






