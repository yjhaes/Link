package com.example.shortlink.stats.messaging;


import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.shortlink.stats.messaging.AsyncVisitRecorder;
import java.time.Duration;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"short-link.stats.rabbit.consumer-enabled=true","short-link.stats.rabbit.virtual-host=${SHORT_LINK_STATS_RABBIT_VIRTUAL_HOST:/}","short-link.stats.enabled=true","short-link.stats.visitor-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","short-link.stats.visitor-key-version=1"})
@AutoConfigureMockMvc @ActiveProfiles("test") @org.springframework.test.annotation.DirtiesContext
class AsyncVisitStartupRecoveryTest {
 static final VisitPublisherBrokerTest.TcpBlackout proxy=createProxy();
 static VisitPublisherBrokerTest.TcpBlackout createProxy(){try{var p=new VisitPublisherBrokerTest.TcpBlackout(Integer.parseInt(System.getenv().getOrDefault("RABBIT_TEST_PORT","15672")));p.unavailable=true;return p;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void mq(DynamicPropertyRegistry properties){properties.add("short-link.stats.rabbit.port",proxy::port);properties.add("short-link.stats.rabbit.host",()->"127.0.0.1");}
 @Autowired MockMvc http;@Autowired JdbcTemplate db;@Autowired AsyncVisitRecorder recorder;
 @Autowired VisitMqRuntime runtime;
 @Test void coreStartsAndRedirectsWhileBrokerUnavailableThenOnlyFreshEventIsConsumed() throws Exception {
  String code="S"+java.util.UUID.randomUUID().toString().replace("-", "").substring(0,7);
  db.update("INSERT INTO short_link(short_code,original_url,created_at,enabled) VALUES (?,'https://example.com/',UTC_TIMESTAMP(3),true)",code);
  try {
   http.perform(get("/s/"+code)).andExpect(status().isFound()).andExpect(header().string("Location","https://example.com/"));
   org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(recorder.snapshot().outcomes().get("send-failed")).isEqualTo(1));
   assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log WHERE short_code=?",Integer.class,code)).isZero();
   proxy.unavailable=false;
   org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->!recorder.snapshot().recovering());
   http.perform(get("/s/"+code)).andExpect(status().isFound());
   org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log WHERE short_code=?",Integer.class,code)).isEqualTo(1));
   assertThat(recorder.snapshot().outcomes().get("attempt")).isEqualTo(2);
  }finally {proxy.unavailable=false;db.update("DELETE FROM short_link_visit_log WHERE short_code=?",code);db.update("DELETE FROM short_link WHERE short_code=?",code);runtime.close();proxy.close();}
 }
}
