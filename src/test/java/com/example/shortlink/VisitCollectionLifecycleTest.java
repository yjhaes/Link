package com.example.shortlink;

import com.example.shortlink.stats.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"short-link.stats.enabled=false","short-link.stats.rabbit.consumer-enabled=true","short-link.stats.rabbit.port=${RABBIT_TEST_PORT:5672}","short-link.stats.rabbit.virtual-host=${SHORT_LINK_STATS_RABBIT_VIRTUAL_HOST:link-lifecycle-test}","short-link.internal-token=0123456789abcdef0123456789abcdef","short-link.redirect-cache.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class VisitCollectionLifecycleTest {
 @Autowired MockMvc http;
 @Autowired JdbcTemplate db;
 @Autowired VisitMessageCodec codec;
 @Autowired VisitLogCleanup cleanup;
 @Autowired VisitPersistence persistence;
 @Autowired AsyncVisitRecorder recorder;
 @Autowired @Qualifier("visitRabbitTemplate") RabbitTemplate rabbit;
 @Autowired @Qualifier("visitRabbitAdmin") RabbitAdmin admin;
 @Autowired @Qualifier("visitListener") SimpleMessageListenerContainer listener;
 @Autowired @Qualifier("visitConsumerConnectionFactory") CachingConnectionFactory consumerFactory;
 private void await(Runnable assertion){org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(assertion::run);}
 private VisitEvent event(String code,Instant time){return new VisitEvent(UUID.randomUUID(),code,time,time.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),new byte[32],1,null,null,null);}
 private void publish(VisitEvent event){rabbit.send(VisitRabbitConfiguration.EXCHANGE,VisitRabbitConfiguration.KEY,new Message(codec.encode(event),new MessageProperties()));}
 @Test void stoppingNewCollectionStillConsumesHistoryQueriesAndCleansOldLogs() throws Exception {
  String code="L"+UUID.randomUUID().toString().replace("-","").substring(0,7);
  var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
  db.update("INSERT INTO short_link(short_code,original_url,created_at,enabled) VALUES (?,'https://example.com/',UTC_TIMESTAMP(3),true)",code);
  try {
   await(()->assertThat(listener.getActiveConsumerCount()).isEqualTo(1));
   long accepted=recorder.snapshot().outcomes().get("local-accepted");
   http.perform(get("/s/"+code)).andExpect(status().isFound()).andExpect(header().doesNotExist("Set-Cookie"));
   assertThat(recorder.snapshot().outcomes().get("local-accepted")).isEqualTo(accepted);
   publish(event(code,now));
   await(()->assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log WHERE short_code=?",Integer.class,code)).isEqualTo(1));
   http.perform(get("/api/internal/links/"+code+"/stats").header("X-Internal-Token","0123456789abcdef0123456789abcdef"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.pv").value(1)).andExpect(jsonPath("$.collectionEnabled").value(false));
   var expired=event(code,now.minus(Duration.ofDays(40)));
   db.update("INSERT INTO short_link_visit_log(event_id,short_code,occurred_at,stat_date,visitor_hash,visitor_key_version) VALUES(UUID_TO_BIN(?),?,?,?,?,1)",expired.eventId().toString(),code,java.sql.Timestamp.from(expired.occurredAt()),expired.statDate(),new byte[32]);
   cleanup.runRound();
   assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log WHERE short_code=?",Integer.class,code)).isEqualTo(1);
  }finally{db.update("DELETE FROM short_link_visit_log WHERE short_code=?",code);db.update("DELETE FROM short_link WHERE short_code=?",code);}
 }
 @Test void manuallyStoppedListenerStaysPausedAcrossConnectionResetThenResumesHistory() {
  String code="P"+UUID.randomUUID().toString().replace("-","").substring(0,7);
  var event=event(code,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
  try {
   await(()->assertThat(listener.getActiveConsumerCount()).isEqualTo(1));
   listener.stop();consumerFactory.resetConnection();admin.initialize();
   publish(event);
   await(()->assertThat(admin.getQueueInfo(VisitRabbitConfiguration.QUEUE).getMessageCount()).isEqualTo(1));
   assertThat(listener.isRunning()).isFalse();assertThat(listener.getActiveConsumerCount()).isZero();
   assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log WHERE short_code=?",Integer.class,code)).isZero();
   listener.start();
   await(()->assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log WHERE short_code=?",Integer.class,code)).isEqualTo(1));
  }finally{listener.start();db.update("DELETE FROM short_link_visit_log WHERE short_code=?",code);}
 }
 @Test @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
 void terminalShutdownRequeuesUnackedCommittedEventAndResumeDeduplicatesIt() throws Exception {
  String code="U"+UUID.randomUUID().toString().replace("-","").substring(0,7);
  var event=event(code,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
  var saved=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  var deliveryChannel=new java.util.concurrent.atomic.AtomicReference<com.rabbitmq.client.Channel>();
  var closed=new java.util.concurrent.CountDownLatch(1);var duplicate=new java.util.concurrent.atomic.AtomicInteger();
  listener.stop();
  var blocked=new VisitConsumer(codec,value->{
   var result=persistence.persist(value);
   if(result==VisitPersistence.Outcome.SAVED){saved.countDown();boolean interrupted=false;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);try{while(release.getCount()>0){long left=deadline-System.nanoTime();if(left<=0)throw new AssertionError("ACK gap was not released.");try{release.await(left,java.util.concurrent.TimeUnit.NANOSECONDS);}catch(InterruptedException stop){interrupted=true;}}}finally{if(interrupted)Thread.currentThread().interrupt();}}
   return result;
  },java.time.Clock.systemUTC());
  listener.setMessageListener((org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener)(message,channel)->{deliveryChannel.set(channel);blocked.onMessage(message);});
  CachingConnectionFactory resumedFactory=null;SimpleMessageListenerContainer resumed=null;
  try {
   listener.start();publish(event);assertThat(saved.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
   listener.stop(closed::countDown);
   assertThat(closed.await(4,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
   assertThat(release.getCount()).isEqualTo(1);
   assertThat(deliveryChannel.get().isOpen()).isFalse();
   resumedFactory=new CachingConnectionFactory("127.0.0.1",Integer.parseInt(System.getenv().getOrDefault("RABBIT_TEST_PORT","5672")));
   resumedFactory.setVirtualHost(System.getenv().getOrDefault("SHORT_LINK_STATS_RABBIT_VIRTUAL_HOST","link-lifecycle-test"));
   resumedFactory.setCloseTimeout(500);
   var resumedAdmin=new RabbitAdmin(resumedFactory);
   await(()->assertThat(resumedAdmin.getQueueInfo(VisitRabbitConfiguration.QUEUE).getMessageCount()).isEqualTo(1));
   release.countDown();
   resumed=new SimpleMessageListenerContainer(resumedFactory);resumed.setQueueNames(VisitRabbitConfiguration.QUEUE);resumed.setConcurrentConsumers(1);resumed.setPrefetchCount(1);resumed.setShutdownTimeout(1000);
   resumed.setMessageListener(new VisitConsumer(codec,value->{var result=persistence.persist(value);if(result==VisitPersistence.Outcome.DUPLICATE)duplicate.incrementAndGet();return result;},java.time.Clock.systemUTC()));
   resumed.afterPropertiesSet();resumed.start();
   await(()->assertThat(duplicate.get()).isEqualTo(1));
   assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log WHERE short_code=?",Integer.class,code)).isEqualTo(1);
  }finally{release.countDown();if(resumed!=null)resumed.stop();if(resumedFactory!=null)resumedFactory.destroy();db.update("DELETE FROM short_link_visit_log WHERE short_code=?",code);}
 }}
