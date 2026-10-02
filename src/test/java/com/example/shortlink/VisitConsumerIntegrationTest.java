package com.example.shortlink;

import com.example.shortlink.stats.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"short-link.stats.rabbit.consumer-enabled=false","short-link.stats.rabbit.port=${RABBIT_TEST_PORT:5672}","short-link.stats.rabbit.virtual-host=${SHORT_LINK_STATS_RABBIT_VIRTUAL_HOST:link-consumer-test}"})
@ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class VisitConsumerIntegrationTest {
 @Autowired JdbcTemplate db;
 @Autowired javax.sql.DataSource dataSource;
 @Autowired VisitPersistence persistence;
 @Autowired VisitWriteObservations writes;
 @Autowired VisitMessageCodec codec;
 @Autowired @Qualifier("visitRabbitAdmin") RabbitAdmin admin;
 @Autowired @Qualifier("visitRabbitTemplate") RabbitTemplate rabbit;
 @Autowired @Qualifier("visitListener") SimpleMessageListenerContainer listener;
 @Autowired @Qualifier("visitConsumerConnectionFactory") CachingConnectionFactory consumerFactory;
 private VisitEvent event;
 private String code;
 private final HttpClient management=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
 private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
 private String vhost=System.getenv().getOrDefault("SHORT_LINK_STATS_RABBIT_VIRTUAL_HOST","link-consumer-test");
 private String api=System.getenv().getOrDefault("RABBIT_MANAGEMENT_URL","http://127.0.0.1:15673")+"/api/";
 private String path(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
 private String request(String method,String suffix,String body) throws Exception {
  String credentials=System.getenv().getOrDefault("RABBITMQ_USERNAME","guest")+":"+System.getenv().getOrDefault("RABBITMQ_PASSWORD","guest");
  var r=HttpRequest.newBuilder(URI.create(api+suffix)).header("Authorization","Basic "+Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8))).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
  var response=management.send(r,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isBetween(200,299);return response.body();
 }
 @BeforeEach void setup() throws Exception {
  assertThat(vhost).isEqualTo("link-consumer-test"); // Never mutate the publisher's primary vhost.
  for(var policy:json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("ops/visit-consumer-policies.json")))) {
   request("PUT","policies/"+path(vhost)+"/"+path(policy.get("name").asText()),policy.toString());
  }
  admin.initialize();listener.stop();admin.purgeQueue(VisitRabbitConfiguration.QUEUE);admin.purgeQueue(VisitRabbitConfiguration.DLQ);
  listener.setMessageListener(new VisitConsumer(codec,persistence));
  code="C"+UUID.randomUUID().toString().replace("-","").substring(0,7);
  var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
  event=new VisitEvent(UUID.randomUUID(),code,at,at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),new byte[32],1,null,null,null);
 }
 @AfterEach void cleanup() {
  listener.stop();db.update("DELETE FROM short_link_visit_log WHERE short_code=?",code);
  admin.purgeQueue(VisitRabbitConfiguration.QUEUE);admin.purgeQueue(VisitRabbitConfiguration.DLQ);
 }
 private void publish(byte[] body){rabbit.send(VisitRabbitConfiguration.EXCHANGE,VisitRabbitConfiguration.KEY,new Message(body,new MessageProperties()));}
 private void await(Runnable assertion){org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(assertion::run);}
 private int rows(){return db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log WHERE short_code=?",Integer.class,code);}
 private int ready(String queue){return admin.getQueueInfo(queue).getMessageCount();}
 @Test void lockFailureThenRecoverySavesFrozenEventWithoutDeadLetter() throws Exception {
  long failed=writes.snapshot().outcomes().get(VisitWriteObservations.Outcome.FAILED)+writes.snapshot().outcomes().get(VisitWriteObservations.Outcome.UNCERTAIN);
  try(var lock=dataSource.getConnection();var s=lock.createStatement()) {
   s.execute("LOCK TABLES short_link_visit_log READ");listener.start();publish(codec.encode(event));
   await(()->assertThat(writes.snapshot().outcomes().get(VisitWriteObservations.Outcome.FAILED)+writes.snapshot().outcomes().get(VisitWriteObservations.Outcome.UNCERTAIN)).isGreaterThan(failed));
   s.execute("UNLOCK TABLES");
  }
  await(()->assertThat(rows()).isEqualTo(1));await(()->assertThat(ready(VisitRabbitConfiguration.QUEUE)).isZero());assertThat(ready(VisitRabbitConfiguration.DLQ)).isZero();
  assertThat(db.queryForObject("SELECT stat_date FROM short_link_visit_log WHERE short_code=?",java.sql.Date.class,code).toLocalDate()).isEqualTo(event.statDate());
 }
 @Test void exhaustedDatabaseFailureIsRejectedExactlyOnceIntoTheSingleDlq() throws Exception {
  long attempted=writes.snapshot().attempted();
  try(var lock=dataSource.getConnection();var s=lock.createStatement()) {
   s.execute("LOCK TABLES short_link_visit_log READ");listener.start();publish(codec.encode(event));
   await(()->assertThat(ready(VisitRabbitConfiguration.DLQ)).isEqualTo(1));
   assertThat(writes.snapshot().attempted()-attempted).isEqualTo(3);assertThat(ready(VisitRabbitConfiguration.QUEUE)).isZero();
   s.execute("UNLOCK TABLES");
  }
  assertThat(rows()).isZero();
  var dead=rabbit.receive(VisitRabbitConfiguration.DLQ);assertThat(dead).isNotNull();
  assertThat(codec.decode(dead.getBody()).eventId()).isEqualTo(event.eventId());
  assertThat(dead.getMessageProperties().getXDeathHeader()).hasSize(1);
  assertThat(dead.getMessageProperties().getXDeathHeader().get(0).get("reason")).isEqualTo("rejected");
 }
 @Test void invalidPayloadAndSchemaAreDeadLetteredWithoutAnyDatabaseAttempt() {
  long before=writes.snapshot().attempted();listener.start();publish("password-secret-invalid".getBytes(StandardCharsets.UTF_8));
  publish(new String(codec.encode(event),StandardCharsets.UTF_8).replace("\"schemaVersion\":1","\"schemaVersion\":99").getBytes(StandardCharsets.UTF_8));
  await(()->assertThat(ready(VisitRabbitConfiguration.DLQ)).isEqualTo(2));assertThat(writes.snapshot().attempted()).isEqualTo(before);
 }
 @Test void differentConstraintIsPermanentAndNeverMistakenForEventDuplicate() {
  db.execute("ALTER TABLE short_link_visit_log ADD CONSTRAINT consumer_test_check CHECK (visitor_key_version <> 11)");
  try {
   var invalid=new VisitEvent(event.eventId(),code,event.occurredAt(),event.statDate(),event.visitorHash(),11,null,null,null);
   long before=writes.snapshot().attempted();listener.start();publish(codec.encode(invalid));
   await(()->assertThat(ready(VisitRabbitConfiguration.DLQ)).isEqualTo(1));assertThat(writes.snapshot().attempted()-before).isEqualTo(1);assertThat(rows()).isZero();
  } finally {db.execute("ALTER TABLE short_link_visit_log DROP CHECK consumer_test_check");}
 }
 @Test void eventDuplicateIsAcknowledgedWithoutIncreasingPv() {
  assertThat(persistence.persist(event)).isEqualTo(VisitPersistence.Outcome.SAVED);
  long duplicate=writes.snapshot().outcomes().get(VisitWriteObservations.Outcome.DUPLICATE);
  listener.start();publish(codec.encode(event));
  await(()->assertThat(writes.snapshot().outcomes().get(VisitWriteObservations.Outcome.DUPLICATE)).isEqualTo(duplicate+1));
  assertThat(rows()).isEqualTo(1);assertThat(ready(VisitRabbitConfiguration.DLQ)).isZero();
 }
 @Test void committedInsertSurvivesConsumerConnectionLossBeforeAckAndRedeliveryStaysSingleRow() throws Exception {
  var saved=new AtomicInteger();var duplicate=new AtomicInteger();var redelivered=new AtomicBoolean();
  var real=new VisitConsumer(codec,value->{
   var outcome=persistence.persist(value);
   if(outcome==VisitPersistence.Outcome.SAVED && saved.incrementAndGet()==1) {
    try {
     for(var connection:json.readTree(request("GET","connections",null))) {
      if(connection.path("vhost").asText().equals(vhost) && connection.path("client_properties").path("connection_name").asText().equals("visit-consumer"))
       request("DELETE","connections/"+path(connection.get("name").asText()),null);
     }
     Thread.sleep(200); // Broker processed connection close before AUTO can send ACK.
    } catch(Exception failure) {throw new IllegalStateException("Test connection close failed.");}
   } else if(outcome==VisitPersistence.Outcome.DUPLICATE) duplicate.incrementAndGet();
   return outcome;
  });
  listener.setMessageListener((MessageListener)m->{if(m.getMessageProperties().isRedelivered())redelivered.set(true);real.onMessage(m);});
  listener.start();publish(codec.encode(event));await(()->assertThat(duplicate.get()).isEqualTo(1));
  assertThat(redelivered).isTrue();assertThat(saved).hasValue(1);assertThat(rows()).isEqualTo(1);assertThat(ready(VisitRabbitConfiguration.DLQ)).isZero();
 }
 @Test void duplicateOfAnotherUniqueKeyIsPermanentAndDoesNotAckAsEventDuplicate() {
  assertThat(persistence.persist(event)).isEqualTo(VisitPersistence.Outcome.SAVED);
  db.execute("ALTER TABLE short_link_visit_log ADD UNIQUE KEY consumer_test_unique ((CASE WHEN short_code = '"+code+"' THEN short_code ELSE NULL END))");
  try {
   var other=new VisitEvent(UUID.randomUUID(),code,event.occurredAt(),event.statDate(),event.visitorHash(),1,null,null,null);
   long before=writes.snapshot().attempted();long duplicate=writes.snapshot().outcomes().get(VisitWriteObservations.Outcome.DUPLICATE);
   listener.start();publish(codec.encode(other));await(()->assertThat(ready(VisitRabbitConfiguration.DLQ)).isEqualTo(1));
   assertThat(writes.snapshot().attempted()-before).isEqualTo(1);assertThat(writes.snapshot().outcomes().get(VisitWriteObservations.Outcome.DUPLICATE)).isEqualTo(duplicate);assertThat(rows()).isEqualTo(1);
  } finally {db.execute("ALTER TABLE short_link_visit_log DROP INDEX consumer_test_unique");}
 }
}
