package com.example.shortlink.stats;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import jakarta.annotation.PreDestroy;
/** HTTP hands off frozen values with offer only. All MQ work belongs to bounded daemon workers. */
@Component @Primary
public class AsyncVisitRecorder implements VisitRecorder {
 private record Pending(VisitEvent event,long acceptedAt){}
 private final ArrayBlockingQueue<Pending> pending;
 private final Semaphore permits;
 private final RabbitTemplate template;
 private final RabbitAdmin admin;
 private final SimpleMessageListenerContainer listener;
 private final VisitMessageCodec codec;
 private final VisitRabbitProperties properties;
 private final AtomicBoolean accepting=new AtomicBoolean(true), started=new AtomicBoolean();
 private final ExecutorService sender=Executors.newSingleThreadExecutor(r -> {var t=new Thread(r,"visit-publisher");t.setDaemon(true);return t;});
 private final ExecutorService startup=Executors.newSingleThreadExecutor(r -> {var t=new Thread(r,"visit-mq-startup");t.setDaemon(true);return t;});
 public AsyncVisitRecorder(VisitRabbitProperties properties,@Qualifier("visitRabbitTemplate") RabbitTemplate template,@Qualifier("visitRabbitAdmin") RabbitAdmin admin,@Qualifier("visitListener") SimpleMessageListenerContainer listener,VisitMessageCodec codec){
  this.properties=properties;this.template=template;this.admin=admin;this.listener=listener;this.codec=codec;
  pending=new ArrayBlockingQueue<>(properties.bufferCapacity());permits=new Semaphore(properties.unconfirmedLimit());
 }
 public record Snapshot(int pending,int unconfirmed){}
 public Snapshot snapshot(){return new Snapshot(pending.size(),properties.unconfirmedLimit()-permits.availablePermits());}
 @Override public void record(VisitEvent event){if(accepting.get())pending.offer(new Pending(event,System.nanoTime()));}
 @EventListener(ApplicationReadyEvent.class) public void ready(){if(!started.compareAndSet(false,true))return;sender.execute(this::sendLoop);startup.execute(()->{try{admin.initialize();if(properties.consumerEnabled())listener.start();}catch(Exception failure){log("startup");}});}
 private void sendLoop(){while(accepting.get()){try{
  Pending next=pending.poll(200,TimeUnit.MILLISECONDS);if(next==null)continue;
  if(System.nanoTime()-next.acceptedAt()>TimeUnit.SECONDS.toNanos(5)||!permits.tryAcquire())continue;
  var done=new AtomicBoolean(); Runnable release=()->{if(done.compareAndSet(false,true))permits.release();};
  try{
   byte[] body=codec.encode(next.event());var mp=new MessageProperties();mp.setContentType("application/json");mp.setContentEncoding("UTF-8");mp.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
   var correlation=new CorrelationData(next.event().eventId().toString());
   correlation.getFuture().orTimeout(5,TimeUnit.SECONDS).whenComplete((confirm,failure)->{if(failure!=null||!confirm.isAck()||correlation.getReturned()!=null)log("publish-unconfirmed");release.run();});
   template.send(VisitRabbitConfiguration.EXCHANGE,VisitRabbitConfiguration.KEY,new Message(body,mp),correlation);
  }catch(Exception failure){release.run();log("publish");}
 }catch(InterruptedException stop){Thread.currentThread().interrupt();return;}catch(Exception failure){log("publisher");}}}
 private void log(String category){org.slf4j.LoggerFactory.getLogger(AsyncVisitRecorder.class).warn("Visit MQ degraded: category={}",category);}
 @PreDestroy public void close(){accepting.set(false);pending.clear();sender.shutdownNow();startup.shutdownNow();}
}

