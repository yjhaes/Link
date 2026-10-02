package com.example.shortlink.stats;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class VisitConsumerTest {
 private final VisitMessageCodec codec = new VisitMessageCodec();
 private VisitEvent event() {var at=Instant.parse("2026-10-02T00:00:00.123Z");return new VisitEvent(UUID.randomUUID(),"Ab12",at,LocalDate.of(2026,10,2),new byte[32],1,null,null,null);}
 @Test void transientFailureRecoversOnThirdAttemptUsingFrozenEvent() {
  var e=event(); var attempts=new AtomicInteger();var seen=new ArrayList<VisitEvent>();var times=new ArrayList<Long>();
  var consumer=new VisitConsumer(codec, value->{seen.add(value);times.add(System.nanoTime());if(attempts.incrementAndGet()<3)throw new VisitPersistenceException(VisitPersistenceException.Failure.TRANSIENT);return VisitPersistence.Outcome.SAVED;});
  consumer.onMessage(new Message(codec.encode(e),new MessageProperties()));
  assertThat(attempts).hasValue(3);assertThat(seen).hasSize(3);assertThat(seen.get(0)).isSameAs(seen.get(1)).isSameAs(seen.get(2));
  assertThat(Duration.ofNanos(times.get(1)-times.get(0))).isGreaterThanOrEqualTo(Duration.ofMillis(190));
  assertThat(Duration.ofNanos(times.get(2)-times.get(1))).isGreaterThanOrEqualTo(Duration.ofMillis(490));
 }
 @Test void exhaustedBusyTransientAndUncertainAreRejectedAfterThreeAttemptsWithoutSensitiveCause() {
  for(var kind:List.of(VisitPersistenceException.Failure.BUSY,VisitPersistenceException.Failure.TRANSIENT,VisitPersistenceException.Failure.UNCERTAIN)) {
   var attempts=new AtomicInteger(); var c=new VisitConsumer(codec,e->{attempts.incrementAndGet();throw new VisitPersistenceException(kind);});
   assertThatThrownBy(()->c.onMessage(new Message(codec.encode(event()),new MessageProperties())))
    .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class).hasNoCause().hasMessage("Visit delivery rejected.");
   assertThat(attempts).hasValue(3);
  }
 }
 @Test void permanentAndInvalidMessagesNeverRetryAndNullOutcomeCannotAck() {
  var attempts=new AtomicInteger();var c=new VisitConsumer(codec,e->{attempts.incrementAndGet();throw new VisitPersistenceException(VisitPersistenceException.Failure.PERMANENT);});
  assertThatThrownBy(()->c.onMessage(new Message(codec.encode(event()),new MessageProperties()))).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
  assertThat(attempts).hasValue(1);
  assertThatThrownBy(()->c.onMessage(new Message("payload-password-secret".getBytes(),new MessageProperties()))).hasNoCause();
  assertThat(attempts).hasValue(1);
  var nullConsumer=new VisitConsumer(codec,e->null);
  assertThatThrownBy(()->nullConsumer.onMessage(new Message(codec.encode(event()),new MessageProperties()))).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
 }
 @Test void savedAndDuplicateAloneReturnNormally() {
  for(var outcome:VisitPersistence.Outcome.values()) {
   var attempts=new AtomicInteger(); var c=new VisitConsumer(codec,e->{attempts.incrementAndGet();return outcome;});
   c.onMessage(new Message(codec.encode(event()),new MessageProperties())); assertThat(attempts).hasValue(1);
  }
 }
 @Test void retryClassificationFollowsControlledPersistenceCauseAndNeverRetainsWrapperText() {
  var attempts=new AtomicInteger();var c=new VisitConsumer(codec,e->{if(attempts.incrementAndGet()<3)throw new IllegalStateException("driver-password-canary",new VisitPersistenceException(VisitPersistenceException.Failure.TRANSIENT));return VisitPersistence.Outcome.SAVED;});
  c.onMessage(new Message(codec.encode(event()),new MessageProperties()));assertThat(attempts).hasValue(3);
 }
}
