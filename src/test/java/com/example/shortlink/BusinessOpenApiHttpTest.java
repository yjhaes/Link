package com.example.shortlink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import com.example.shortlink.ratelimit.RateLimiter;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.assertThat;

class BusinessOpenApiHttpTest {
    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class, RabbitAutoConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration.class})
    @Import(InternalManagementApiTest.WebConfiguration.class)
    static class Application {
        @Bean AtomicReference<RateLimiter.Decision> decision() { return new AtomicReference<>(RateLimiter.Decision.allowed()); }
        @Bean @Primary RateLimiter mutableLimiter(AtomicReference<RateLimiter.Decision> decision) {
            return new RateLimiter() {
                public Decision admitCreate(String peer) { return decision.get(); }
                public Decision admitRedirect(String peer) { return decision.get(); }
                public Decision admitManagementWrite() { return decision.get(); }
                public Decision admitManagementQuery() { return decision.get(); }
            };
        }
    }

    @Test void realOpenApiAndLocalUiDescribeOnlyBusinessOperations() throws Exception {
        try (var context = new SpringApplicationBuilder(Application.class).run("--server.port=0",
                "--management.server.port=0", "--short-link.internal-token=0123456789abcdef0123456789abcdef", "--spring.sql.init.mode=never", "--spring.main.banner-mode=off")) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var http = HttpClient.newHttpClient();
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode document = new ObjectMapper().readTree(response.body());
            assertThat(document.path("paths").size()).isEqualTo(5);
            validateSchemaReferences(document, document);
            int management = context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
            var isolated = get(http, management, "/v3/api-docs");
            assertThat(isolated.statusCode()).isNotEqualTo(200);
            assertThat(isolated.body()).doesNotContain("openapi", "paths");
            assertThat(document.path("paths").has("/actuator/health")).isFalse();
            assertThat(response.body()).doesNotContain("0123456789abcdef0123456789abcdef", "persistAuthorization\":true");
            for (String path : new String[]{"/api/links", "/api/links/{code}/enabled", "/s/{code}", "/api/internal/links/{code}/stats", "/api/internal/links/{code}/visits"}) {
                String method = path.equals("/api/links") ? "post" : path.endsWith("/enabled") ? "put" : "get";
                var operation = document.path("paths").path(path).path(method);
                assertThat(operation.path("responses").has("503")).isTrue();
                assertThat(operation.path("responses").path("429").path("headers").has("Retry-After")).isTrue();
                assertThat(operation.path("responses").path("429").path("headers").has("Cache-Control")).isTrue();
                if (path.contains("internal") || path.endsWith("/enabled"))
                    assertThat(operation.path("security").get(0).has("InternalToken")).isTrue();
            }
            var queryParameters = document.path("paths").path("/api/internal/links/{code}/visits").path("get").path("parameters");
            assertThat(queryParameters.toString()).contains("from", "to", "limit", "cursor").doesNotContain("parameters");
            assertThat(document.path("components").path("securitySchemes").path("InternalToken").path("name").asText()).isEqualTo("X-Internal-Token");
            var config = get(http, port, "/v3/api-docs/swagger-config");
            assertThat(config.statusCode()).isEqualTo(200);
            assertThat(new ObjectMapper().readTree(config.body()).path("persistAuthorization").asBoolean(true)).isFalse();
            assertThat(get(http, port, "/swagger-ui/index.html").statusCode()).isEqualTo(200);
            assertThat(get(http, port, "/swagger-ui/swagger-initializer.js").statusCode()).isEqualTo(200);
            assertThat(get(http, port, "/swagger-ui/swagger-initializer.js").body()).doesNotContain("preauthorizeApiKey", "0123456789abcdef0123456789abcdef");
            assertThat(document.at("/paths/~1api~1links/post/responses").has("201")).isTrue();
            for (String path : new String[]{"/s/{code}", "/api/internal/links/{code}/stats", "/api/internal/links/{code}/visits"}) {
                assertThat(document.path("paths").path(path).has("head")).as(path).isTrue();
                assertThat(document.path("paths").path(path).path("head").path("responses").path("429").has("content")).isFalse();
            }
        }
    }
    private static void validateSchemaReferences(JsonNode node, JsonNode document) {
        if (node.isObject() && node.has("$ref")) {
            String reference = node.path("$ref").asText();
            assertThat(reference).startsWith("#/components/schemas/");
            assertThat(document.at(reference.substring(1)).isMissingNode()).as(reference).isFalse();
        }
        node.elements().forEachRemaining(child -> validateSchemaReferences(child, document));
    }
    private static HttpResponse<String> get(HttpClient http, int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void documentedHeadAndDistinct503sAreRealHttpContracts() throws Exception {
        try (var context = new SpringApplicationBuilder(Application.class).run("--server.port=0", "--management.server.port=0",
                "--short-link.internal-token=0123456789abcdef0123456789abcdef", "--spring.sql.init.mode=never", "--spring.main.banner-mode=off")) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var http = HttpClient.newHttpClient();
            var mapper = context.getBean(com.example.shortlink.persistence.ShortLinkMapper.class);
            var entity = new com.example.shortlink.persistence.ShortLinkEntity();
            entity.setShortCode("Ab12"); entity.setOriginalUrl("https://example.com/"); entity.setEnabled(true);
            when(mapper.selectById("Ab12")).thenReturn(entity);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/s/Ab12"));
            var head = http.send(request.method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(head.statusCode()).isEqualTo(302);
            assertThat(head.body()).isEmpty();
            assertThat(head.headers().firstValue("Location")).contains("https://example.com/");
            assertThat(head.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(head.headers().firstValue("Set-Cookie")).isEmpty();
            verifyNoInteractions(context.getBean(com.example.shortlink.stats.VisitRecorder.class));
            @SuppressWarnings("unchecked") var decisions = (AtomicReference<RateLimiter.Decision>) context.getBean(AtomicReference.class);
            decisions.set(RateLimiter.Decision.rejected(1201));
            for (String path : new String[]{"/s/Ab12", "/api/internal/links/Ab12/stats", "/api/internal/links/Ab12/visits"}) {
                var denied = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("X-Internal-Token", "0123456789abcdef0123456789abcdef").method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(denied.statusCode()).isEqualTo(429);
                assertThat(denied.body()).isEmpty();
                assertThat(denied.headers().firstValue("Retry-After")).contains("2");
                assertThat(denied.headers().firstValue("Cache-Control")).contains("no-store");
                assertThat(denied.headers().firstValue("Set-Cookie")).isEmpty();
            }
            decisions.set(RateLimiter.Decision.unavailable());
            var unavailable = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/links"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{" )).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(unavailable.statusCode()).isEqualTo(503);
            assertThat(unavailable.body()).contains("RATE_LIMIT_UNAVAILABLE").doesNotContain("shortCode", "COORDINATION_UNCONFIRMED");
            var issuer = context.getBean(com.example.shortlink.shortcode.ShortCodeIdIssuer.class);
            verifyNoInteractions(issuer);
            decisions.set(RateLimiter.Decision.allowed());
            when(issuer.issue()).thenReturn(1L);
            when(mapper.insert(any(com.example.shortlink.persistence.ShortLinkEntity.class))).thenReturn(1);
            var cache = context.getBean(com.example.shortlink.cache.RedirectCache.class);
            doThrow(new IllegalStateException("controlled-cache-failure")).when(cache).replaceVersion(anyString());
            var committed = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/links"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"originalUrl\":\"https://example.com/\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(committed.statusCode()).isEqualTo(503);
            assertThat(committed.body()).contains("CREATE_CACHE_COORDINATION_UNCONFIRMED", "shortCode").doesNotContain("RATE_LIMIT_UNAVAILABLE", "controlled-cache-failure");
            assertThat(committed.headers().firstValue("Cache-Control")).contains("no-store");
            verify(issuer).issue();
        }
    }
}