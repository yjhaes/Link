package com.example.shortlink;

import com.example.shortlink.stats.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisitMqShutdownTest {
 @Test void actualContextCloseDoesNotWaitForBlockedPublisherOrCreateReplacementWorkers() throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  var context=new AnnotationConfigApplicationContext();
  context.registerBean(VisitPersistence.class,()->event->VisitPersistence.Outcome.SAVED);
  context.register(VisitRabbitConfiguration.class,AsyncVisitRecorder.class);
  context.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor(){ public Object postProcessAfterInitialization(Object bean,String name){if(name.equals("visitPublisherConnectionFactory")){var instrumented=spy((CachingConnectionFactory)bean);doAnswer(i->{entered.countDown();while(!release.await(1,TimeUnit.SECONDS)){} return null;}).when(instrumented).resetConnection();return instrumented;}return bean;}});
  context.refresh();
  var template=context.getBean("visitRabbitTemplate",RabbitTemplate.class);
  var factory=context.getBean("visitPublisherConnectionFactory",CachingConnectionFactory.class);
  // A real CCF has entered its running lifecycle even before a broker connection exists.
  assertThat(factory.isRunning()).isTrue();
  var recorder=context.getBean(AsyncVisitRecorder.class);
  recorder.ready();
  var close=Executors.newSingleThreadExecutor();
  try {
   // Framework stop must not perform network work on the lifecycle caller.
   var stopped=close.submit(()->factory.stop());
   stopped.get(2,TimeUnit.SECONDS);
   assertThat(factory.isRunning()).isFalse();
   var result=close.submit(context::close);
   assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
   result.get(5,TimeUnit.SECONDS);
   assertThat(release.getCount()).isEqualTo(1);
   recorder.record(VisitPublisherFailureTest.event());
   assertThat(recorder.snapshot().pending()).isZero();
   assertThat(recorder.snapshot().unconfirmed()).isZero();
  }finally{release.countDown();context.close();close.shutdownNow();}
 }
}

