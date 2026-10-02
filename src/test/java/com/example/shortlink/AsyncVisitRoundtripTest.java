package com.example.shortlink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
@SpringBootTest(properties={"short-link.stats.rabbit.consumer-enabled=true","short-link.stats.rabbit.virtual-host=${SHORT_LINK_STATS_RABBIT_VIRTUAL_HOST:/}","short-link.stats.enabled=true","short-link.stats.visitor-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","short-link.stats.visitor-key-version=1","short-link.stats.rabbit.port=${RABBIT_TEST_PORT:5672}"})
@AutoConfigureMockMvc @ActiveProfiles("test") @org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AsyncVisitRoundtripTest {
 @Autowired MockMvc http; @Autowired JdbcTemplate db;
 @Autowired com.example.shortlink.stats.AsyncVisitRecorder recorder;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean(name="visitRabbitTemplate") org.springframework.amqp.rabbit.core.RabbitTemplate publishing;
 @Test void repeatedGetsUseNewEventsAndSameCookieIdentityThroughRealBrokerAndDatabase() throws Exception {
  String code="Q"+java.util.UUID.randomUUID().toString().replace("-", "").substring(0,7);
  db.update("INSERT INTO short_link(short_code,original_url,created_at,enabled) VALUES (?,'https://example.com/',UTC_TIMESTAMP(3),true)",code);
  try {
  var result=http.perform(get("/s/"+code).header("User-Agent","test-agent").header("Referer","https://example.com/private?secret=x"))
   .andExpect(status().isFound()).andExpect(header().string("Cache-Control","no-store")).andReturn();
  String cookie=result.getResponse().getHeader("Set-Cookie").split(";",2)[0];
  http.perform(get("/s/"+code).header("Cookie",cookie)).andExpect(status().isFound()).andExpect(header().doesNotExist("Set-Cookie"));
  org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log WHERE short_code=?",Integer.class,code)).isEqualTo(2));
  assertThat(db.queryForObject("SELECT COUNT(DISTINCT event_id) FROM short_link_visit_log WHERE short_code=?",Integer.class,code)).isEqualTo(2);
  assertThat(db.queryForObject("SELECT COUNT(DISTINCT visitor_hash) FROM short_link_visit_log WHERE short_code=?",Integer.class,code)).isEqualTo(1);
  assertThat(db.queryForObject("SELECT referer_host FROM short_link_visit_log WHERE short_code=? AND referer_host IS NOT NULL",String.class,code)).isEqualTo("example.com");
  http.perform(head("/s/"+code)).andExpect(status().isFound()).andExpect(header().doesNotExist("Set-Cookie"));
  http.perform(get("/s/Zz99")).andExpect(status().isNotFound()).andExpect(header().doesNotExist("Set-Cookie"));
  } finally { db.update("DELETE FROM short_link_visit_log WHERE short_code=?",code); db.update("DELETE FROM short_link WHERE short_code=?",code); }
 }
 @Test void blockedBrokerSendAndFullLocalBufferNeverHoldTheHttpThread() throws Exception {
  String code="R"+java.util.UUID.randomUUID().toString().replace("-", "").substring(0,7);
  db.update("INSERT INTO short_link(short_code,original_url,created_at,enabled) VALUES (?,'https://example.com/',UTC_TIMESTAMP(3),true)",code);
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  org.mockito.Mockito.doAnswer(invocation->{entered.countDown();assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return null;})
    .when(publishing).send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.amqp.core.Message.class),org.mockito.ArgumentMatchers.any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
  try {
   http.perform(get("/s/"+code)).andExpect(status().isFound()).andExpect(header().exists("Set-Cookie"));
   assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
   for(int i=0;i<258;i++) http.perform(get("/s/"+code)).andExpect(status().isFound()).andExpect(header().exists("Set-Cookie"));
   assertThat(release.getCount()).isEqualTo(1); // Every HTTP response completed while real MQ send remained blocked.
   assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log WHERE short_code=?",Integer.class,code)).isZero();
  } finally {release.countDown();org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(()->recorder.snapshot().pending()==0 && recorder.snapshot().unconfirmed()==0);db.update("DELETE FROM short_link WHERE short_code=?",code);org.mockito.Mockito.doCallRealMethod().when(publishing).send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.amqp.core.Message.class),org.mockito.ArgumentMatchers.any(org.springframework.amqp.rabbit.connection.CorrelationData.class));}
 }}






