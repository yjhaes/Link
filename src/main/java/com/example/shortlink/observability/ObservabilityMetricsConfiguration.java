package com.example.shortlink.observability;

import com.example.shortlink.stats.messaging.AsyncVisitRecorder;
import com.example.shortlink.stats.messaging.VisitConsumer;
import com.example.shortlink.stats.persistence.VisitWriteObservations;
import com.example.shortlink.stats.query.VisitQueryObservations;
import com.example.shortlink.stats.retention.VisitLogCleanup;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Locale;
import java.util.function.DoubleSupplier;

/** Read-only views of the existing process-local observations; never query the broker or SQL. */
@Configuration(proxyBeanMethods=false)
public class ObservabilityMetricsConfiguration {
    @Bean MeterBinder visitMetrics(ObjectProvider<AsyncVisitRecorder> publisher,
            ObjectProvider<VisitConsumer> consumer, ObjectProvider<VisitWriteObservations> writes,
            ObjectProvider<VisitQueryObservations> queries, ObjectProvider<VisitLogCleanup> cleanup) {
        return registry -> {
            var p = publisher.getIfAvailable();
            if (p != null && p.snapshot() != null) {
                Gauge.builder("shortlink.mq.local.pending", p, x -> x.snapshot().pending()).register(registry);
                Gauge.builder("shortlink.mq.publish.unconfirmed", p, x -> x.snapshot().unconfirmed()).register(registry);
                Gauge.builder("shortlink.mq.publish.recovering", p, x -> x.snapshot().recovering() ? 1 : 0).register(registry);
                Gauge.builder("shortlink.mq.local.oldest.age", p, x -> x.snapshot().localOldestQueuedAgeNanos()/1e9).baseUnit("seconds").register(registry);
                for(String result : p.snapshot().eventOutcomes().keySet())
                    FunctionCounter.builder("shortlink.mq.event.outcomes", p, x -> x.snapshot().eventOutcomes().get(result))
                        .tag("result", result).register(registry);
                for(String result : p.snapshot().publishOutcomes().keySet())
                    FunctionCounter.builder("shortlink.mq.publish.outcomes", p, x -> x.snapshot().publishOutcomes().get(result))
                        .tag("result", result).register(registry);
                FunctionCounter.builder("shortlink.mq.publish.duration", p, x -> x.snapshot().publishDurationNanos()/1e9)
                    .baseUnit("seconds").register(registry);
            }
            var c = consumer.getIfAvailable();
            if(c != null) {
                FunctionCounter.builder("shortlink.mq.consumer.deliveries", c, x -> x.snapshot().deliveries()).register(registry);
                FunctionCounter.builder("shortlink.mq.consumer.persistence.attempts", c, x -> x.snapshot().persistenceAttempts()).register(registry);
                FunctionCounter.builder("shortlink.mq.consumer.processing.duration", c, x -> x.snapshot().processingNanos()/1e9).baseUnit("seconds").register(registry);
                FunctionCounter.builder("shortlink.mq.consumer.event.delay", c, x -> x.snapshot().processedEventDelayMillis()/1000d).baseUnit("seconds").register(registry);
                FunctionCounter.builder("shortlink.mq.consumer.event.delay.samples", c, x -> x.snapshot().processedEventDelayCount()).register(registry);
                Gauge.builder("shortlink.mq.consumer.inflight", c, x -> x.snapshot().inFlight()).register(registry);
                Gauge.builder("shortlink.mq.consumer.completed.average", c, x -> x.snapshot().completedPerSecond()).baseUnit("events_per_second").register(registry);
                for(var result : VisitConsumer.Category.values())
                    FunctionCounter.builder("shortlink.mq.consumer.outcomes", c, x -> x.snapshot().outcomes().get(result))
                        .tag("result", result.name().toLowerCase(Locale.ROOT)).register(registry);
            }
            var w = writes.getIfAvailable();
            if(w != null) {
                FunctionCounter.builder("shortlink.statistics.write.attempts", w, x -> x.snapshot().attempted()).register(registry);
                FunctionCounter.builder("shortlink.statistics.write.duration", w, x -> x.snapshot().durationNanos()/1e9).baseUnit("seconds").register(registry);
                Gauge.builder("shortlink.statistics.write.inflight", w, x -> x.snapshot().inFlight()).register(registry);
                for(var result : VisitWriteObservations.Outcome.values())
                    FunctionCounter.builder("shortlink.statistics.write.outcomes", w, x -> x.snapshot().outcomes().get(result))
                        .tag("result", result.name().toLowerCase(Locale.ROOT)).register(registry);
                for(var category : VisitWriteObservations.Category.values())
                    FunctionCounter.builder("shortlink.statistics.write.failures", w, x -> x.snapshot().categories().get(category))
                        .tag("category", category.name().toLowerCase(Locale.ROOT)).register(registry);
            }
            var q = queries.getIfAvailable();
            if(q != null) FunctionCounter.builder("shortlink.statistics.query.timeouts", q, VisitQueryObservations::timeouts).register(registry);
            var cl = cleanup.getIfAvailable();
            if(cl != null) {
                gauge(registry,"shortlink.cleanup.backlog.known", () -> cl.snapshot().expiredRows() == null ? 0 : 1);
                gauge(registry,"shortlink.cleanup.backlog", () -> cl.snapshot().expiredRows() == null ? Double.NaN : cl.snapshot().expiredRows());
                gauge(registry,"shortlink.cleanup.backlog.lower.bound", () -> cl.snapshot().expiredRows() == null ? Double.NaN : cl.snapshot().backlogLowerBound() ? 1 : 0);
                gauge(registry,"shortlink.cleanup.observation.age", () -> cl.snapshot().observedAt() == null ? Double.NaN :
                    Math.max(0, java.time.Duration.between(cl.snapshot().observedAt(),java.time.Instant.now()).toMillis()/1000d));
                gauge(registry,"shortlink.cleanup.last.deleted", () -> cl.snapshot().deletedRows());
                for(var result : VisitLogCleanup.Outcome.values())
                    Gauge.builder("shortlink.cleanup.last.outcome", cl, x -> x.snapshot().outcome() == result ? 1 : 0)
                        .tag("result",result.name().toLowerCase(Locale.ROOT)).register(registry);
            }
        };
    }
    private static void gauge(io.micrometer.core.instrument.MeterRegistry registry, String name, DoubleSupplier value) {
        Gauge.builder(name, value, DoubleSupplier::getAsDouble).strongReference(true).register(registry);
    }
}
