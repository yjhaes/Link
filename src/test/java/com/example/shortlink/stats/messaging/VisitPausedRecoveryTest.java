package com.example.shortlink.stats.messaging;



import com.example.shortlink.stats.messaging.VisitPausedRecoveryTest;
import com.example.shortlink.stats.messaging.VisitPublisherBrokerTest;
import com.example.shortlink.stats.messaging.VisitPublisherFailureTest;
import com.example.shortlink.stats.messaging.AsyncVisitRecorder;
import com.example.shortlink.stats.messaging.VisitMqRuntime;
import com.example.shortlink.stats.messaging.VisitRabbitConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"short-link.stats.rabbit.consumer-enabled=false","short-link.stats.rabbit.virtual-host=${SHORT_LINK_STATS_RABBIT_VIRTUAL_HOST:link-lifecycle-test}"})
@ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class VisitPausedRecoveryTest {
 static final VisitPublisherBrokerTest.TcpBlackout proxy=create();
 static VisitPublisherBrokerTest.TcpBlackout create(){try{var result=new VisitPublisherBrokerTest.TcpBlackout(Integer.parseInt(System.getenv().getOrDefault("RABBIT_TEST_PORT","5672")));result.unavailable=true;return result;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("short-link.stats.rabbit.host",()->"127.0.0.1");registry.add("short-link.stats.rabbit.port",proxy::port);}
 @Autowired AsyncVisitRecorder recorder;
 @Autowired VisitMqRuntime runtime;
 @Autowired @Qualifier("visitListener") SimpleMessageListenerContainer listener;
 @Autowired @Qualifier("visitRabbitAdmin") RabbitAdmin admin;
 @Test void configurationPauseSurvivesStartupFailureAndPublisherRecovery() throws Exception {
  try {
   assertThat(listener.isRunning()).isFalse();
   recorder.record(VisitPublisherFailureTest.event());
   org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(recorder.snapshot().outcomes().get("send-failed")).isEqualTo(1));
   proxy.unavailable=false;
   org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(admin.getQueueInfo(VisitRabbitConfiguration.QUEUE)).isNotNull());
   org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(()->!recorder.snapshot().recovering());
   recorder.record(VisitPublisherFailureTest.event());
   org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(recorder.snapshot().outcomes().get("accepted")).isEqualTo(1));
   assertThat(listener.isRunning()).isFalse();assertThat(listener.getActiveConsumerCount()).isZero();
   assertThat(admin.getQueueInfo(VisitRabbitConfiguration.QUEUE).getMessageCount()).isEqualTo(1);
  }finally{proxy.unavailable=false;admin.purgeQueue(VisitRabbitConfiguration.QUEUE);runtime.close();proxy.close();}
 }
}
