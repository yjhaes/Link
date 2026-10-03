package com.example.shortlink.stats.messaging;
import com.example.shortlink.stats.VisitEvent;
import com.example.shortlink.stats.VisitPersistence;
import com.example.shortlink.stats.VisitPersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class VisitConsumerTest {
 private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC);
 private VisitConsumer consumer(VisitMessageCodec codec, VisitPersistence persistence) { return new VisitConsumer(codec, persistence, clock); }
 private final VisitMessageCodec codec = new VisitMessageCodec();
 private VisitEvent event() {var at=Instant.parse("2026-10-02T00:00:00.123Z");return new VisitEvent(UUID.randomUUID(),"Ab12",at,LocalDate.of(2026,10,2),new byte[32],1,null,null,null);}
 @Test void observationsSeparateDeliveredEventsFromPersistenceAttemptsAndProcessedEventDelay() {
  var calls=new AtomicInteger();
  var c=consumer(codec,e->{if(calls.incrementAndGet()<3)throw new VisitPersistenceException(VisitPersistenceException.Failure.TRANSIENT);return VisitPersistence.Outcome.SAVED;});
  var older=new VisitEvent(UUID.randomUUID(),"Ab12",Instant.parse("2026-10-01T23:59:58.877Z"),LocalDate.of(2026,10,2),new byte[32],1,null,null,null);
  c.onMessage(new Message(codec.encode(older),new MessageProperties()));
  var snapshot=c.snapshot();
  assertThat(snapshot.deliveries()).isEqualTo(1);
  assertThat(snapshot.persistenceAttempts()).isEqualTo(3);
  assertThat(snapshot.outcomes().get(VisitConsumer.Category.SAVED)).isEqualTo(1);
  assertThat(snapshot.outcomes().get(VisitConsumer.Category.ATTEMPT_FAILED)).isEqualTo(2);
  assertThat(snapshot.processingNanos()).isPositive();
  assertThat(snapshot.processedEventDelayCount()).isEqualTo(1);
  assertThat(snapshot.processedEventDelayMillis()).isEqualTo(1123); // frozen clock; retries must not change occurredAt
  assertThat(snapshot.completedPerSecond()).isPositive();
 }
 @Test void exhaustedFailureAndInvalidDataHaveFixedDistinctTerminalCategories() {
  var c=consumer(codec,e->{throw new VisitPersistenceException(VisitPersistenceException.Failure.BUSY);});
  assertThatThrownBy(()->c.onMessage(new Message(codec.encode(event()),new MessageProperties()))).hasNoCause();
  assertThatThrownBy(()->c.onMessage(new Message("payload-secret".getBytes(),new MessageProperties()))).hasNoCause();
  var snapshot=c.snapshot();
  assertThat(snapshot.deliveries()).isEqualTo(2);
  assertThat(snapshot.persistenceAttempts()).isEqualTo(3);
  assertThat(snapshot.outcomes().get(VisitConsumer.Category.EXHAUSTED)).isEqualTo(1);
  assertThat(snapshot.outcomes().get(VisitConsumer.Category.INVALID)).isEqualTo(1);
  assertThat(snapshot.inFlight()).isZero();
 }
 @Test void expiredEventIsAnExplicitSuccessfulTerminalOutcomeWithoutPersistence() {
  var old = new VisitEvent(UUID.randomUUID(), "Ab12", Instant.parse("2026-09-01T00:00:00Z"), LocalDate.of(2026,9,1),new byte[32],1,null,null,null);
  var attempts = new AtomicInteger();
  var consumer = consumer(codec, e -> { attempts.incrementAndGet(); return VisitPersistence.Outcome.SAVED; });
  assertThat(consumer.consume(new Message(codec.encode(old),new MessageProperties()))).isEqualTo(VisitConsumer.Outcome.EXPIRED);
  assertThat(attempts).hasValue(0);
  assertThat(consumer.expiredCount()).isEqualTo(1);
  assertThat(consumer.snapshot().outcomes().get(VisitConsumer.Category.EXPIRED)).isEqualTo(1);
  assertThat(consumer.snapshot().persistenceAttempts()).isZero();
 }
 @Test void transientFailureRecoversOnThirdAttemptUsingFrozenEvent() {
  var e=event(); var attempts=new AtomicInteger();var seen=new ArrayList<VisitEvent>();var times=new ArrayList<Long>();
  var consumer=consumer(codec, value->{seen.add(value);times.add(System.nanoTime());if(attempts.incrementAndGet()<3)throw new VisitPersistenceException(VisitPersistenceException.Failure.TRANSIENT);return VisitPersistence.Outcome.SAVED;});
  consumer.onMessage(new Message(codec.encode(e),new MessageProperties()));
  assertThat(attempts).hasValue(3);assertThat(seen).hasSize(3);assertThat(seen.get(0)).isSameAs(seen.get(1)).isSameAs(seen.get(2));
  assertThat(Duration.ofNanos(times.get(1)-times.get(0))).isGreaterThanOrEqualTo(Duration.ofMillis(190));
  assertThat(Duration.ofNanos(times.get(2)-times.get(1))).isGreaterThanOrEqualTo(Duration.ofMillis(490));
 }
 @Test void exhaustedBusyTransientAndUncertainAreRejectedAfterThreeAttemptsWithoutSensitiveCause() {
  for(var kind:List.of(VisitPersistenceException.Failure.BUSY,VisitPersistenceException.Failure.TRANSIENT,VisitPersistenceException.Failure.UNCERTAIN)) {
   var attempts=new AtomicInteger(); var c=consumer(codec,e->{attempts.incrementAndGet();throw new VisitPersistenceException(kind);});
   assertThatThrownBy(()->c.onMessage(new Message(codec.encode(event()),new MessageProperties())))
    .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class).hasNoCause().hasMessage("Visit delivery rejected.");
   assertThat(attempts).hasValue(3);
  }
 }
 @Test void permanentAndInvalidMessagesNeverRetryAndNullOutcomeCannotAck() {
  var attempts=new AtomicInteger();var c=consumer(codec,e->{attempts.incrementAndGet();throw new VisitPersistenceException(VisitPersistenceException.Failure.PERMANENT);});
  assertThatThrownBy(()->c.onMessage(new Message(codec.encode(event()),new MessageProperties()))).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
  assertThat(attempts).hasValue(1);
  assertThatThrownBy(()->c.onMessage(new Message("payload-password-secret".getBytes(),new MessageProperties()))).hasNoCause();
  assertThat(attempts).hasValue(1);
  var nullConsumer=consumer(codec,e->null);
  assertThatThrownBy(()->nullConsumer.onMessage(new Message(codec.encode(event()),new MessageProperties()))).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
 }
 @Test void savedAndDuplicateAloneReturnNormally() {
  for(var outcome:VisitPersistence.Outcome.values()) {
   var attempts=new AtomicInteger(); var c=consumer(codec,e->{attempts.incrementAndGet();return outcome;});
   c.onMessage(new Message(codec.encode(event()),new MessageProperties())); assertThat(attempts).hasValue(1);
  }
 }
 @Test void retryClassificationFollowsControlledPersistenceCauseAndNeverRetainsWrapperText() {
  var attempts=new AtomicInteger();var c=consumer(codec,e->{if(attempts.incrementAndGet()<3)throw new IllegalStateException("driver-password-canary",new VisitPersistenceException(VisitPersistenceException.Failure.TRANSIENT));return VisitPersistence.Outcome.SAVED;});
  c.onMessage(new Message(codec.encode(event()),new MessageProperties()));assertThat(attempts).hasValue(3);
 }
}
