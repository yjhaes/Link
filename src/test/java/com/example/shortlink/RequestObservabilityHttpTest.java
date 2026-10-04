package com.example.shortlink;

import com.example.shortlink.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class RequestObservabilityHttpTest {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class, RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class, RabbitAutoConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration.class})
    @Import(InternalManagementApiTest.WebConfiguration.class)
    @ComponentScan(basePackages="com.example.shortlink.observability",useDefaultFilters=false,
        includeFilters=@ComponentScan.Filter(type=FilterType.REGEX,pattern=".*(RequestObservationFilter|MetricPrivacyConfiguration|ObservabilityMetricsConfiguration)"))
    static class Application {
        @Bean AtomicReference<RateLimiter.Decision> decision() { return new AtomicReference<>(RateLimiter.Decision.rejected(1000)); }
        @Bean com.example.shortlink.stats.retention.VisitLogCleanup cleanup(
                com.example.shortlink.stats.config.VisitStatsProperties properties, java.time.Clock clock) {
            return new com.example.shortlink.stats.retention.VisitLogCleanup(
                    org.mockito.Mockito.mock(javax.sql.DataSource.class), properties, clock);
        }
        @Bean @Primary RateLimiter admission(AtomicReference<RateLimiter.Decision> decision) {
            return new RateLimiter() {
                public Decision admitCreate(String peer) { return decision.get(); }
                public Decision admitManagementWrite() { return decision.get(); }
                public Decision admitManagementQuery() { return decision.get(); }
            };
        }
    }
    @Test void realRejectionsHaveServerIdsBoundedSafeLogsAndFiniteMetricTags(CapturedOutput output) throws Exception {
        try(var context=new SpringApplicationBuilder(Application.class).run("--server.port=0", "--management.server.port=0",
                "--spring.main.banner-mode=off","--short-link.base-url=http://localhost")) {
            int main=((ServletWebServerApplicationContext)context).getWebServer().getPort();
            int management=context.getEnvironment().getRequiredProperty("local.management.port",Integer.class);
            var http=HttpClient.newHttpClient();
            String first=null;
            for(int i=0;i<20;i++) {
                var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+main+"/api/links?query-secret-canary"))
                    .header("X-Request-ID","client-id-canary").header("Cookie","visitor-cookie-canary")
                    .header("Referer","https://referer-canary/private").header("User-Agent","agent-canary")
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("body-secret-canary"))
                    .build(),HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(429);
                String id=response.headers().firstValue("X-Request-ID").orElseThrow();
                assertThat(id).matches("[0-9a-f-]{36}").isNotEqualTo(first);
                first=id;
            }
            for(int i=0;i<5;i++) {
                http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+main+"/unknown-path-canary-"+i)).GET().build(),HttpResponse.BodyHandlers.ofString());
            }
            var metrics=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+management+"/actuator/metrics/shortlink.http.requests?tag=group:create&tag=result:rejected"))
                .GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(metrics.statusCode()).isEqualTo(200);
            assertThat(metrics.body()).contains("20.0").doesNotContain("canary");
            var automatic=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+management+"/actuator/metrics/http.server.requests"))
                .GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(automatic.statusCode()).isEqualTo(200);
            assertThat(automatic.body()).doesNotContain("canary", "uri", "method", "exception");
            String logs=output.getAll();
            assertThat(logs).contains("operation=create result=rejected", "requestId=");
            assertThat(logs.lines().filter(line->line.contains("operation=create result=rejected")).count()).isEqualTo(1);
            assertThat(logs).doesNotContain("client-id-canary","query-secret-canary","visitor-cookie-canary","referer-canary","agent-canary","body-secret-canary");
        }
    }
    @Test void actualSqlPressureAndCacheFailuresAreVisibleWithoutLeakingDriverOrBusinessCanaries(CapturedOutput output) throws Exception {
        try(var context=new SpringApplicationBuilder(Application.class).run("--server.port=0", "--management.server.port=0",
                "--spring.main.banner-mode=off","--short-link.base-url=http://localhost")) {
            int main=((ServletWebServerApplicationContext)context).getWebServer().getPort();
            int management=context.getEnvironment().getRequiredProperty("local.management.port",Integer.class);
            var http=HttpClient.newHttpClient();
            var cache=context.getBean(com.example.shortlink.cache.RedirectCache.class);
            var mapper=context.getBean(com.example.shortlink.persistence.ShortLinkMapper.class);
            org.mockito.Mockito.when(cache.find(org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException(
                "redis://password-canary host-ip-canary original-url-canary visitor-hash-canary payload-canary"));
            var entered=new java.util.concurrent.CountDownLatch(4);
            var release=new java.util.concurrent.CountDownLatch(1);
            org.mockito.Mockito.when(mapper.selectById(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call->{
                entered.countDown(); assertThat(release.await(8,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                throw new IllegalStateException("jdbc:mysql://password-canary private-sql-canary");
            });
            var running=new java.util.ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
            try {
                for(String code:java.util.List.of("Ab12","Cd34","Ef56","Gh78")) running.add(http.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+main+"/s/"+code)).GET().build(),HttpResponse.BodyHandlers.ofString()));
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var busy=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+main+"/s/Ij90")).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(busy.statusCode()).isEqualTo(503);
                assertThat(busy.body()).contains("REDIRECT_LOAD_BUSY");
                assertThat(busy.headers().firstValue("Set-Cookie")).isEmpty();
                for(String metric:java.util.List.of("shortlink.redirect.load.inflight","shortlink.redirect.load.rejected","shortlink.cache.read")) {
                    var result=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+management+"/actuator/metrics/"+metric)).GET().build(),HttpResponse.BodyHandlers.ofString());
                    assertThat(result.statusCode()).as(metric).isEqualTo(200);
                    assertThat(result.body()).doesNotContain("canary","Ab12","Ij90");
                    if(metric.endsWith("inflight")) assertThat(result.body()).contains("4.0");
                    if(metric.endsWith("rejected")) assertThat(result.body()).contains("1.0");
                    if(metric.endsWith("read")) assertThat(result.body()).contains("failed", "5.0");
                }
            } finally { release.countDown(); }
            for(var request:running) assertThat(request.get(5,java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(500);
            var idle=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+management+"/actuator/metrics/shortlink.redirect.load.inflight")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(idle.body()).contains("0.0");
            org.mockito.Mockito.doReturn(com.example.shortlink.cache.RedirectCacheRead.redirect(
                "confirmed", new com.example.shortlink.cache.RedirectCacheEntry("https://safe.example/", null)))
                .when(cache).find(org.mockito.ArgumentMatchers.anyString());
            var recovered=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+main+"/s/Ab12")).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(recovered.statusCode()).isEqualTo(302);
            assertThat(output.getAll()).contains("Dependency recovery: category=cache-read");
            assertThat(output.getAll()).contains("category=cache-read", "operation=redirect result=unavailable");
            assertThat(output.getAll()).doesNotContain("password-canary","host-ip-canary","original-url-canary","visitor-hash-canary","payload-canary","private-sql-canary");
        }
    }
    @Test void existingSnapshotMetricsRetainUnknownCleanupRatherThanFabricatingZero() throws Exception {
        try(var context=new SpringApplicationBuilder(Application.class).run("--server.port=0", "--management.server.port=0",
                "--spring.main.banner-mode=off","--short-link.base-url=http://localhost")) {
            int management=context.getEnvironment().getRequiredProperty("local.management.port",Integer.class);
            var http=HttpClient.newHttpClient();
            for(String metric:java.util.List.of("shortlink.statistics.write.outcomes", "shortlink.cleanup.backlog.known", "shortlink.cleanup.backlog")) {
                var result=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+management+"/actuator/metrics/"+metric)).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(result.statusCode()).as(metric).isEqualTo(200);
                if(metric.endsWith("known")) assertThat(result.body()).contains("0.0");
                else if(metric.endsWith("backlog")) assertThat(result.body()).contains("NaN");
                else assertThat(result.body()).contains("saved", "uncertain", "failed");
            }
        }
    }
    @Test void authorizedAdmissionCountsAndCommittedCoordinationKeepSafeLocation(CapturedOutput output) throws Exception {
        try(var context=new SpringApplicationBuilder(Application.class).run("--server.port=0", "--management.server.port=0",
                "--spring.main.banner-mode=off","--short-link.base-url=http://localhost",
                "--short-link.internal-token="+InternalManagementApiTest.TOKEN)) {
            int main=((ServletWebServerApplicationContext)context).getWebServer().getPort();
            int management=context.getEnvironment().getRequiredProperty("local.management.port",Integer.class);
            var http=HttpClient.newHttpClient();
            @SuppressWarnings("unchecked")
            var decision=(AtomicReference<RateLimiter.Decision>)context.getBean(AtomicReference.class);
            for(String group:java.util.List.of("management_write","management_query")) {
                String path=group.endsWith("write")?"/api/links/Ab12/enabled":"/api/internal/links/Ab12/stats";
                var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+main+path)).header("X-Internal-Token",InternalManagementApiTest.TOKEN);
                var denied=http.send(group.endsWith("write")?builder.PUT(HttpRequest.BodyPublishers.ofString("{")).build():builder.GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(denied.statusCode()).isEqualTo(429);
                var metric=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+management+"/actuator/metrics/shortlink.rate.admission?tag=group:"+group+"&tag=result:rejected")).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(metric.statusCode()).isEqualTo(200); assertThat(metric.body()).contains("1.0");
            }
            decision.set(RateLimiter.Decision.unavailable());
            var endpoint=URI.create("http://127.0.0.1:"+main+"/api/links");
            assertThat(http.send(HttpRequest.newBuilder(endpoint).POST(HttpRequest.BodyPublishers.ofString("{")).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
            decision.set(RateLimiter.Decision.allowed());
            assertThat(http.send(HttpRequest.newBuilder(endpoint).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{")).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            var metric=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+management+"/actuator/metrics/shortlink.rate.admission?tag=group:create&tag=result:allowed")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(metric.body()).contains("1.0");
            var mapper=context.getBean(com.example.shortlink.persistence.ShortLinkMapper.class);
            var issuer=context.getBean(com.example.shortlink.shortcode.ShortCodeIdIssuer.class);
            org.mockito.Mockito.when(issuer.issue()).thenReturn(1L);
            org.mockito.Mockito.when(mapper.insert(org.mockito.ArgumentMatchers.any(com.example.shortlink.persistence.ShortLinkEntity.class))).thenReturn(1);
            var cache=context.getBean(com.example.shortlink.cache.RedirectCache.class);
            org.mockito.Mockito.doThrow(new IllegalStateException("redis://coord-password-canary private-original-url-canary"))
                .when(cache).replaceVersion(org.mockito.ArgumentMatchers.anyString());
            var committed=http.send(HttpRequest.newBuilder(endpoint).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"originalUrl\":\"https://private-original-url-canary.example/\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(committed.statusCode()).isEqualTo(503);
            var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(committed.body());
            String code=body.get("shortCode").asText();
            assertThat(output.getAll()).contains("category=committed-cache-unconfirmed shortCode="+code,
                "Dependency recovery: category=rate-create");
            assertThat(output.getAll()).doesNotContain("coord-password-canary","private-original-url-canary", InternalManagementApiTest.TOKEN);
        }
    }
}
