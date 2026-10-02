package com.example.shortlink;
import com.example.shortlink.stats.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class VisitPublisherFailureTest {
 @Test void unknownIsTerminalEvenWhenSenderCannotReturnAndLateAckArrives() throws Exception {
  var template=mock(RabbitTemplate.class); var factory=mock(CachingConnectionFactory.class);
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var cleanup=new CountDownLatch(1);
  var correlation=new java.util.concurrent.atomic.AtomicReference<CorrelationData>();
  doAnswer(i->{correlation.set(i.getArgument(3));entered.countDown();release.await();return null;}).when(template).send(anyString(),anyString(),any(),any(CorrelationData.class));
  doAnswer(i->{cleanup.await();return null;}).when(factory).resetConnection();
  var recorder=new AsyncVisitRecorder(new VisitRabbitProperties(null,null,null,null,null,2,1,null,null,null,null,null,false),template,mock(RabbitAdmin.class),mock(SimpleMessageListenerContainer.class),new VisitMessageCodec(),factory);
  try {
   recorder.ready();recorder.record(event());assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
   org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(7)).untilAsserted(()->assertThat(recorder.snapshot().outcomes().get("unknown")).isEqualTo(1));
   assertThat(recorder.snapshot().unconfirmed()).isZero(); assertThat(recorder.snapshot().recovering()).isTrue();
   correlation.get().getFuture().complete(new CorrelationData.Confirm(true,null));
   assertThat(recorder.snapshot().outcomes().get("accepted")).isZero();
   recorder.record(event()); recorder.record(event());recorder.record(event());
   assertThat(recorder.snapshot().pending()).isLessThanOrEqualTo(2);
  } finally {release.countDown();cleanup.countDown();recorder.close();}
 }
 @Test void returnAndAckProduceOnlyOneReturnOutcome() {
  var template=mock(RabbitTemplate.class);
  doAnswer(i->{CorrelationData cd=i.getArgument(3);cd.setReturned(new org.springframework.amqp.core.ReturnedMessage(new org.springframework.amqp.core.Message(new byte[]{1}),312,"NO_ROUTE","test","x"));cd.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(template).send(anyString(),anyString(),any(),any(CorrelationData.class));
  var recorder=new AsyncVisitRecorder(new VisitRabbitProperties(null,null,null,null,null,null,null,null,null,null,null,null,false),template,mock(RabbitAdmin.class),mock(SimpleMessageListenerContainer.class),new VisitMessageCodec(),mock(CachingConnectionFactory.class));
  try {recorder.ready();recorder.record(event());org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(recorder.snapshot().outcomes().get("return")).isEqualTo(1));assertThat(recorder.snapshot().outcomes().get("accepted")).isZero();assertThat(recorder.snapshot().unconfirmed()).isZero();}finally{recorder.close();}
 }
 @Test void returnWithoutFollowingAckIsStillReapedAndRecovered() {
  var template=mock(RabbitTemplate.class);var correlations=new java.util.concurrent.CopyOnWriteArrayList<CorrelationData>();
  doAnswer(i->{CorrelationData cd=i.getArgument(3);correlations.add(cd);cd.setReturned(new org.springframework.amqp.core.ReturnedMessage(new org.springframework.amqp.core.Message(new byte[]{1}),312,"NO_ROUTE","test","x"));return null;}).when(template).send(anyString(),anyString(),any(),any(CorrelationData.class));
  var recorder=new AsyncVisitRecorder(new VisitRabbitProperties(null,null,null,null,null,null,1,null,null,null,null,null,false),template,mock(RabbitAdmin.class),mock(SimpleMessageListenerContainer.class),new VisitMessageCodec(),mock(CachingConnectionFactory.class));
  try {recorder.ready();recorder.record(event());org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(recorder.snapshot().outcomes().get("return")).isEqualTo(1));assertThat(recorder.snapshot().unconfirmed()).isEqualTo(1);
   org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(7)).untilAsserted(()->assertThat(recorder.snapshot().unconfirmed()).isZero());assertThat(recorder.snapshot().outcomes().get("recovery")).isEqualTo(1);assertThat(recorder.snapshot().outcomes().get("unknown")).isZero();
   correlations.get(0).getFuture().complete(new CorrelationData.Confirm(false,"late"));assertThat(recorder.snapshot().outcomes().get("nack")).isZero();
  }finally{recorder.close();}
 }
 @Test void resubmittedEventKeepsPayloadIdentityButUsesIndependentCorrelationIds() {
  var template=mock(RabbitTemplate.class);var ids=new java.util.concurrent.CopyOnWriteArrayList<String>();
  doAnswer(i->{CorrelationData cd=i.getArgument(3);ids.add(cd.getId());cd.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(template).send(anyString(),anyString(),any(),any(CorrelationData.class));
  var recorder=new AsyncVisitRecorder(new VisitRabbitProperties(null,null,null,null,null,null,null,null,null,null,null,null,false),template,mock(RabbitAdmin.class),mock(SimpleMessageListenerContainer.class),new VisitMessageCodec(),mock(CachingConnectionFactory.class));
  try {recorder.ready();var same=event();recorder.record(same);recorder.record(same);org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(recorder.snapshot().outcomes().get("accepted")).isEqualTo(2));assertThat(ids).doesNotHaveDuplicates();}finally{recorder.close();}
 }
 static VisitEvent event(){var now=Instant.now();return new VisitEvent(UUID.randomUUID(),"Ab123",now,now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),new byte[32],1,null,null,null);}
}


