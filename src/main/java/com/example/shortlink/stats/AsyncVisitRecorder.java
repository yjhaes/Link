package com.example.shortlink.stats;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import jakarta.annotation.PreDestroy;

/** HTTP admission never waits for MQ; observation and cleanup cannot depend on the sender. */
@Component @Primary
public class AsyncVisitRecorder implements VisitRecorder {
 private static final long BUDGET=TimeUnit.SECONDS.toNanos(5);
 private record Pending(VisitEvent event,long acceptedAt){}
 private final class Attempt {
  final CorrelationData correlation; final long began=System.nanoTime(); final AtomicBoolean terminal=new AtomicBoolean(), released=new AtomicBoolean();
  Attempt(VisitEvent event){correlation=new CorrelationData(UUID.randomUUID().toString());}
  void finish(String result){if(terminal.compareAndSet(false,true))count(result);}
  void retire(){if(released.compareAndSet(false,true)){attempts.remove(this);permits.release();}}
 }
 private final ArrayBlockingQueue<Pending> pending;
 private final Semaphore permits;
 private final Set<Attempt> attempts=ConcurrentHashMap.newKeySet();
 private final Map<String,LongAdder> outcomes=new ConcurrentHashMap<>();
 private final RabbitTemplate template;
 private final RabbitAdmin admin;
 private final SimpleMessageListenerContainer listener;
 private final CachingConnectionFactory publisher;
 private final VisitMessageCodec codec;
 private final VisitRabbitProperties properties;
 private final AtomicBoolean accepting=new AtomicBoolean(true), started=new AtomicBoolean(), recovering=new AtomicBoolean();
 private final Object recoveryLock=new Object();
 private final AtomicBoolean senderBusy=new AtomicBoolean(), cleanupDone=new AtomicBoolean();
 private final ExecutorService sender=Executors.newSingleThreadExecutor(r -> daemon(r,"visit-publisher"));
 private final ExecutorService startup=Executors.newSingleThreadExecutor(r -> daemon(r,"visit-mq-startup"));
 private final ExecutorService cleanup=Executors.newSingleThreadExecutor(r -> daemon(r,"visit-publisher-cleanup"));
 private final ScheduledExecutorService observer=Executors.newSingleThreadScheduledExecutor(r -> daemon(r,"visit-publisher-observer"));
 private static Thread daemon(Runnable r,String name){var t=new Thread(r,name);t.setDaemon(true);return t;}
 public AsyncVisitRecorder(VisitRabbitProperties properties,@Qualifier("visitRabbitTemplate") RabbitTemplate template,@Qualifier("visitRabbitAdmin") RabbitAdmin admin,@Qualifier("visitListener") SimpleMessageListenerContainer listener,VisitMessageCodec codec,@Qualifier("visitPublisherConnectionFactory") CachingConnectionFactory publisher){
  this.properties=properties;this.template=template;this.admin=admin;this.listener=listener;this.codec=codec;this.publisher=publisher;
  pending=new ArrayBlockingQueue<>(properties.bufferCapacity());permits=new Semaphore(properties.unconfirmedLimit());
  for(String category:List.of("local-accepted","full","expired","limited","attempt","accepted","return","nack","unknown","send-failed","encoding","recovery","recovery-failed"))outcomes.put(category,new LongAdder());
 }
 public record Snapshot(int pending,int unconfirmed,boolean recovering,Map<String,Long> outcomes){}
 public Snapshot snapshot(){var counts=new HashMap<String,Long>();outcomes.forEach((k,v)->counts.put(k,v.sum()));return new Snapshot(pending.size(),properties.unconfirmedLimit()-permits.availablePermits(),recovering.get(),Map.copyOf(counts));}
 private void count(String category){outcomes.get(category).increment();}
 @Override public void record(VisitEvent event){if(accepting.get())count(pending.offer(new Pending(event,System.nanoTime()))?"local-accepted":"full");}
 @EventListener(ApplicationReadyEvent.class) public void ready(){if(!started.compareAndSet(false,true)||!accepting.get())return;sender.execute(this::sendLoop);observer.scheduleWithFixedDelay(this::observe,100,100,TimeUnit.MILLISECONDS);startup.execute(this::startupLoop);}
 private void startupLoop(){while(accepting.get()){
  try{admin.initialize();if(properties.consumerEnabled())listener.start();return;}
  catch(Exception failure){log("startup");}
  try{TimeUnit.MILLISECONDS.sleep(500);}catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
 }}
 private void sendLoop(){while(accepting.get()){try{
  if(recovering.get()){TimeUnit.MILLISECONDS.sleep(50);continue;}
  Pending next=pending.poll(100,TimeUnit.MILLISECONDS);if(next==null)continue;
  if(System.nanoTime()-next.acceptedAt()>BUDGET){count("expired");continue;}
  if(!permits.tryAcquire()){count("limited");continue;}
  byte[] body;
  try{body=codec.encode(next.event());}catch(Exception invalid){permits.release();count("encoding");continue;}
  var attempt=new Attempt(next.event());attempts.add(attempt);senderBusy.set(true);
  try {
   // Recheck after admission: a concurrent timeout can have closed the recovery gate.
   if(recovering.get()){attempt.finish("unknown");attempt.retire();continue;}
   attempt.correlation.getFuture().whenComplete((confirm,failure)->{
    if(attempt.correlation.getReturned()!=null)attempt.finish("return");
    else if(failure!=null)attempt.finish("unknown");
    else attempt.finish(confirm.isAck()?"accepted":"nack");
    attempt.retire();
   });
   var mp=new MessageProperties();mp.setContentType("application/json");mp.setContentEncoding("UTF-8");mp.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
   if(System.nanoTime()-next.acceptedAt()>BUDGET){attempt.finish("expired");attempt.retire();continue;}
   count("attempt");
   template.send(VisitRabbitConfiguration.EXCHANGE,VisitRabbitConfiguration.KEY,new Message(body,mp),attempt.correlation);
  }catch(Exception failure){attempt.finish(attempt.correlation.getReturned()!=null?"return":"send-failed");attempt.retire();requestRecovery();}
  finally{senderBusy.set(false);}
 }catch(InterruptedException stop){Thread.currentThread().interrupt();return;}catch(Exception failure){log("publisher");}}}
 private void observe(){
  try {
   boolean timedOut=false;long now=System.nanoTime();
   for(var attempt:attempts){
    if(attempt.correlation.getReturned()!=null)attempt.finish("return");
    if(now-attempt.began>BUDGET){attempt.finish("unknown");attempt.retire();timedOut=true;}
   }
   if(timedOut)requestRecovery();
   // A completed reset is insufficient until the original sender actually returns.
   synchronized(recoveryLock){if(recovering.get()&&cleanupDone.get()&&!senderBusy.get())recovering.set(false);}
  }catch(Exception failure){log("observer");}
 }
 private void requestRecovery(){
  synchronized(recoveryLock){if(!accepting.get()||recovering.get())return;cleanupDone.set(false);recovering.set(true);}
  count("recovery");
  for(var attempt:attempts){attempt.finish("unknown");attempt.retire();}
  try{cleanup.execute(()->{
   try{template.getUnconfirmed(0);publisher.resetConnection();cleanupDone.set(true);}
   catch(Exception failure){count("recovery-failed");log("recovery");}
  });}catch(RejectedExecutionException stopped){/* shutdown retains the gate */}
 }
 private void log(String category){org.slf4j.LoggerFactory.getLogger(AsyncVisitRecorder.class).warn("Visit MQ degraded: category={}",category);}
 @PreDestroy public void close(){
  if(!accepting.getAndSet(false))return;
  pending.clear();observer.shutdownNow();for(var attempt:attempts){attempt.finish("unknown");attempt.retire();}
  sender.shutdownNow();startup.shutdownNow();
  // Never destroy the CCF synchronously: reset can block behind a TCP write.
  if(recovering.compareAndSet(false,true))try{cleanup.execute(()->{try{publisher.resetConnection();}catch(Exception failure){log("close");}});}catch(RejectedExecutionException ignored){}
  cleanup.shutdown();
 }
}




