package com.example.shortlink;

import com.example.shortlink.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

class CreateRateLimitEmbeddedHttpTest {
    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class, RabbitAutoConfiguration.class})
    @Import(InternalManagementApiTest.WebConfiguration.class)
    static class Application {
        @Bean AtomicReference<RateLimiter.Decision> decision() {
            return new AtomicReference<>(RateLimiter.Decision.allowed());
        }
        @Bean @Primary RateLimiter embeddedAdmission(AtomicReference<RateLimiter.Decision> decision) {
            return peer -> decision.get();
        }
    }

    @Test
    void oversizedMultipartIsRejectedByAdmissionBeforeParsingAndAllowedMalformedJsonRemains400() throws Exception {
        try (var context = new SpringApplicationBuilder(Application.class)
                .properties("server.port=0", "spring.main.banner-mode=off", "short-link.base-url=http://localhost",
                        "spring.servlet.multipart.max-file-size=1KB", "spring.servlet.multipart.max-request-size=1KB")
                .run()) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/api/links");
            var http = HttpClient.newHttpClient();
            @SuppressWarnings("unchecked")
            var decisions = (AtomicReference<RateLimiter.Decision>) context.getBean(AtomicReference.class);
            String multipart = "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"file\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n" + "x".repeat(4096) + "\r\n--boundary--\r\n";
            var request = HttpRequest.newBuilder(endpoint).header("Content-Type", "multipart/form-data; boundary=boundary")
                    .POST(HttpRequest.BodyPublishers.ofString(multipart)).build();
            decisions.set(RateLimiter.Decision.rejected(1201));
            var denied = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(429, denied.statusCode());
            assertTrue(denied.body().contains("RATE_LIMIT_EXCEEDED"));
            assertEquals("2", denied.headers().firstValue("Retry-After").orElseThrow());
            decisions.set(RateLimiter.Decision.unavailable());
            var unavailable = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(503, unavailable.statusCode());
            assertTrue(unavailable.body().contains("RATE_LIMIT_UNAVAILABLE"));
            assertEquals("no-store", unavailable.headers().firstValue("Cache-Control").orElseThrow());
            decisions.set(RateLimiter.Decision.allowed());
            var malformed = http.send(HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, malformed.statusCode());
            verifyNoInteractions(context.getBean(com.example.shortlink.persistence.ShortLinkMapper.class),
                    context.getBean(com.example.shortlink.shortcode.ShortCodeIdIssuer.class),
                    context.getBean(com.example.shortlink.cache.RedirectCache.class));
        }
    }
}
