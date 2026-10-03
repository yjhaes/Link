package com.example.shortlink;


import com.example.shortlink.stats.messaging.AsyncVisitRecorder;
import com.example.shortlink.stats.messaging.VisitConsumer;
import com.example.shortlink.stats.VisitEvent;
import com.example.shortlink.stats.messaging.VisitMessageCodec;
import com.example.shortlink.stats.VisitPersistence;
import com.example.shortlink.stats.messaging.VisitRabbitConfiguration;
import com.example.shortlink.stats.VisitWriteObservations;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"short-link.stats.enabled=false","short-link.stats.rabbit.consumer-enabled=false","short-link.stats.rabbit.port=${RABBIT_TEST_PORT:5672}","short-link.stats.rabbit.virtual-host=${SHORT_LINK_STATS_RABBIT_VIRTUAL_HOST:link-lifecycle-test}","short-link.redirect-cache.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class VisitBacklogIntegrationTest {
 @Autowired JdbcTemplate db;
 @Autowired javax.sql.DataSource dataSource;
 @Autowired MockMvc http;
 @Autowired VisitPersistence persistence;
 @Autowired VisitWriteObservations writes;
 @Autowired VisitConsumer consumer;
 @Autowired VisitMessageCodec codec;
 @Autowired AsyncVisitRecorder recorder;
 @Autowired @Qualifier("visitRabbitAdmin") RabbitAdmin admin;
 @Autowired @Qualifier("visitRabbitTemplate") RabbitTemplate rabbit;
 @Autowired @Qualifier("visitListener") SimpleMessageListenerContainer listener;
 private final HttpClient management=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
 private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
 private final String vhost=System.getenv().getOrDefault("SHORT_LINK_STATS_RABBIT_VIRTUAL_HOST","link-lifecycle-test");
 private final String api=System.getenv().getOrDefault("RABBIT_MANAGEMENT_URL","http://127.0.0.1:15673")+"/api/";
 private String code;
 private String path(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
 private com.fasterxml.jackson.databind.JsonNode request(String method,String suffix,String body) throws Exception {
  String credentials=System.getenv().getOrDefault("RABBITMQ_USERNAME","guest")+":"+System.getenv().getOrDefault("RABBITMQ_PASSWORD","guest");
  var r=HttpRequest.newBuilder(URI.create(api+suffix)).header("Authorization","Basic "+Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8))).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
  var response=management.send(r,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isBetween(200,299);
  return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());
 }
 private com.fasterxml.jackson.databind.JsonNode policies() throws Exception {return json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("ops/visit-consumer-policies.json")));}
 @BeforeEach void setup() throws Exception {
  assertThat(vhost).isEqualTo("link-lifecycle-test");
  listener.stop();admin.initialize();admin.purgeQueue(VisitRabbitConfiguration.QUEUE);admin.purgeQueue(VisitRabbitConfiguration.DLQ);
  listener.setMessageListener(consumer);
  for(var policy:policies()) request("PUT","policies/"+path(vhost)+"/"+path(policy.get("name").asText()),policy.toString());
  code="B"+UUID.randomUUID().toString().replace("-","").substring(0,7);
 }
 @AfterEach void cleanup() {
  listener.stop();listener.setMessageListener(consumer);
  admin.purgeQueue(VisitRabbitConfiguration.QUEUE);admin.purgeQueue(VisitRabbitConfiguration.DLQ);
  try {for(var policy:policies()) request("PUT","policies/"+path(vhost)+"/"+path(policy.get("name").asText()),policy.toString());}catch(Exception failure){throw new AssertionError(failure);}
  db.update("DELETE FROM short_link_visit_log WHERE short_code=?",code);db.update("DELETE FROM short_link WHERE short_code=?",code);
 }
 private void await(Runnable assertion){org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(assertion::run);}
 private VisitEvent event(){var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);return new VisitEvent(UUID.randomUUID(),code,now,now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),new byte[32],1,null,null,null);}
 private void publish(VisitEvent e){rabbit.send(VisitRabbitConfiguration.EXCHANGE,VisitRabbitConfiguration.KEY,new Message(codec.encode(e),new MessageProperties()));}
 private com.fasterxml.jackson.databind.JsonNode queue(String name) throws Exception {return request("GET","queues/"+path(vhost)+"/"+path(name),null);}
 @Test void mainPolicyContainsAllBudgetsTogetherWithDlxAndBrokerAppliesThem() throws Exception {
  var definition=policies().get(0).path("definition");
  assertThat(definition.path("max-length").asInt()).isEqualTo(10000);
  assertThat(definition.path("max-length-bytes").asInt()).isEqualTo(16777216);
  assertThat(definition.path("message-ttl").asInt()).isEqualTo(86400000);
  assertThat(definition.path("overflow").asText()).isEqualTo("reject-publish");
  assertThat(definition.path("dead-letter-exchange").asText()).isEqualTo(VisitRabbitConfiguration.DLX);
  assertThat(definition.path("dead-letter-routing-key").asText()).isEqualTo(VisitRabbitConfiguration.DEAD_KEY);
  await(()->{try {assertThat(queue(VisitRabbitConfiguration.QUEUE).path("effective_policy_definition")).isEqualTo(definition);}catch(Exception e){throw new AssertionError(e);}});
 }
 private void scaledPolicy(int index,int length,int bytes,int ttl) throws Exception {
  var policy=(com.fasterxml.jackson.databind.node.ObjectNode)policies().get(index);
  var definition=(com.fasterxml.jackson.databind.node.ObjectNode)policy.get("definition");
  definition.put("max-length",length).put("max-length-bytes",bytes).put("message-ttl",ttl);
  request("PUT","policies/"+path(vhost)+"/"+path(policy.get("name").asText()),policy.toString());
  String name=index==0?VisitRabbitConfiguration.QUEUE:VisitRabbitConfiguration.DLQ;
  await(()->{try {assertThat(queue(name).path("effective_policy_definition")).isEqualTo(definition);}catch(Exception e){throw new AssertionError(e);}});
 }
 private CorrelationData confirmed(byte[] body) throws Exception {
  var correlation=new CorrelationData();
  rabbit.send(VisitRabbitConfiguration.EXCHANGE,VisitRabbitConfiguration.KEY,new Message(body,new MessageProperties()),correlation);
  correlation.getFuture().get(5,TimeUnit.SECONDS);return correlation;
 }
 @Test void readyCountAndBodyLimitsNackOverflowWithoutDeadLetteringRejectedPublish() throws Exception {
  scaledPolicy(0,1,1024,60000);
  assertThat(confirmed(new byte[]{1,2}).getFuture().join().isAck()).isTrue();
  assertThat(confirmed(new byte[]{3,4}).getFuture().join().isAck()).isFalse();
  assertThat(admin.getQueueInfo(VisitRabbitConfiguration.QUEUE).getMessageCount()).isEqualTo(1);
  assertThat(admin.getQueueInfo(VisitRabbitConfiguration.DLQ).getMessageCount()).isZero();
  admin.purgeQueue(VisitRabbitConfiguration.QUEUE);
  scaledPolicy(0,1000,3,60000);
  assertThat(confirmed(new byte[]{5,6}).getFuture().join().isAck()).isTrue();
  assertThat(confirmed(new byte[]{7,8}).getFuture().join().isAck()).isFalse();
  assertThat(admin.getQueueInfo(VisitRabbitConfiguration.QUEUE).getMessageCount()).isEqualTo(1);
  assertThat(admin.getQueueInfo(VisitRabbitConfiguration.DLQ).getMessageCount()).isZero();
 }
 @Test void mainExpiryDeadLettersThenDlqHasIndependentResidenceTtlAndDropHead() throws Exception {
  scaledPolicy(0,10,1024,150);scaledPolicy(1,1,1024,1500);
  assertThat(confirmed(new byte[]{11}).getFuture().join().isAck()).isTrue();
  await(()->assertThat(admin.getQueueInfo(VisitRabbitConfiguration.DLQ).getMessageCount()).isEqualTo(1));
  var dead=rabbit.receive(VisitRabbitConfiguration.DLQ);
  assertThat(dead.getMessageProperties().getXDeathHeader()).hasSize(1);
  assertThat(dead.getMessageProperties().getXDeathHeader().get(0).get("reason")).isEqualTo("expired");
  // Re-publish to the isolated test DLQ so its own TTL is independently observable.
  rabbit.send(VisitRabbitConfiguration.DLX,VisitRabbitConfiguration.DEAD_KEY,new Message(new byte[]{21},new MessageProperties()));
  org.awaitility.Awaitility.await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(admin.getQueueInfo(VisitRabbitConfiguration.DLQ).getMessageCount()).isEqualTo(1));
  await(()->assertThat(admin.getQueueInfo(VisitRabbitConfiguration.DLQ).getMessageCount()).isZero());
  scaledPolicy(1,1,1024,60000);
  rabbit.send(VisitRabbitConfiguration.DLX,VisitRabbitConfiguration.DEAD_KEY,new Message(new byte[]{31},new MessageProperties()));
  rabbit.send(VisitRabbitConfiguration.DLX,VisitRabbitConfiguration.DEAD_KEY,new Message(new byte[]{32},new MessageProperties()));
  await(()->assertThat(admin.getQueueInfo(VisitRabbitConfiguration.DLQ).getMessageCount()).isEqualTo(1));
  assertThat(rabbit.receive(VisitRabbitConfiguration.DLQ).getBody()).containsExactly((byte)32);
 }
 @Test void oneSynchronousConsumerPrefetchesTenAndLeavesThreeReadyWhileDatabaseWorkIsBlocked() throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new AtomicInteger();
  var observed=new VisitConsumer(codec,e->{started.incrementAndGet();entered.countDown();try {if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("test release missing");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}return persistence.persist(e);},Clock.systemUTC());
  listener.setMessageListener(observed);
  try {
   for(int i=0;i<13;i++)publish(event());
   listener.start();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
   await(()->{try {var q=queue(VisitRabbitConfiguration.QUEUE);assertThat(q.path("messages_unacknowledged").asInt()).isEqualTo(10);assertThat(q.path("messages_ready").asInt()).isEqualTo(3);assertThat(q.path("consumers").asInt()).isEqualTo(1);}catch(Exception failure){throw new AssertionError(failure);}});
   assertThat(started).hasValue(1);assertThat(observed.snapshot().inFlight()).isEqualTo(1);
   assertThat(writes.snapshot().poolTotal()).isLessThanOrEqualTo(4);
   assertThat(recorder.snapshot().pending()).isLessThanOrEqualTo(256);assertThat(recorder.snapshot().unconfirmed()).isLessThanOrEqualTo(32);
   release.countDown();await(()->assertThat(observed.snapshot().outcomes().get(VisitConsumer.Category.SAVED)).isEqualTo(13));
   await(()->{try {assertThat(queue(VisitRabbitConfiguration.QUEUE).path("messages_unacknowledged").asInt()).isZero();}catch(Exception failure){throw new AssertionError(failure);}});
   assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log WHERE short_code=?",Integer.class,code)).isEqualTo(13);
  } finally {release.countDown();listener.stop();}
 }
 @Test void sustainedRealDatabaseFailureIsObservableCanBePausedAndRestoredWhileRedirectStillWorks() throws Exception {
  db.update("INSERT INTO short_link(short_code,original_url,created_at,enabled) VALUES (?,'https://example.com/',UTC_TIMESTAMP(3),true)",code);
  long before=consumer.snapshot().outcomes().get(VisitConsumer.Category.EXHAUSTED);
  long attempts=writes.snapshot().attempted();
  try(var connection=dataSource.getConnection();var statement=connection.createStatement()) {
   statement.execute("LOCK TABLES short_link_visit_log READ");
   try {
   listener.start();publish(event());publish(event());
   await(()->assertThat(consumer.snapshot().outcomes().get(VisitConsumer.Category.EXHAUSTED)).isEqualTo(before+2));
   assertThat(writes.snapshot().attempted()-attempts).isEqualTo(6);
   assertThat(admin.getQueueInfo(VisitRabbitConfiguration.DLQ).getMessageCount()).isEqualTo(2);
   listener.stop();publish(event());
   await(()->assertThat(admin.getQueueInfo(VisitRabbitConfiguration.QUEUE).getMessageCount()).isEqualTo(1));
   long accepted=recorder.snapshot().eventOutcomes().get("local-accepted");
   http.perform(get("/s/"+code)).andExpect(status().isFound()).andExpect(header().string("Location","https://example.com/")).andExpect(header().doesNotExist("Set-Cookie"));
   assertThat(recorder.snapshot().eventOutcomes().get("local-accepted")).isEqualTo(accepted);
   assertThat(listener.isRunning()).isFalse();
   } finally {statement.execute("UNLOCK TABLES");}
  }
  listener.start();await(()->assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log WHERE short_code=?",Integer.class,code)).isGreaterThanOrEqualTo(1));
  http.perform(get("/s/"+code)).andExpect(status().isFound());
  assertThat(consumer.snapshot().outcomes().get(VisitConsumer.Category.EXHAUSTED)).isEqualTo(before+2);
  // Failed events remain in DLQ; restoring DB does not initiate history replay.
  assertThat(admin.getQueueInfo(VisitRabbitConfiguration.DLQ).getMessageCount()).isEqualTo(2);
 }
}
