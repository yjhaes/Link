package com.example.shortlink;

import com.example.shortlink.api.ShortLinkController;
import com.example.shortlink.api.error.ApiExceptionHandler;
import com.example.shortlink.api.management.InternalManagementAccess;
import com.example.shortlink.api.ratelimit.ManagementRateLimitInterceptor;
import com.example.shortlink.api.stats.VisitCollection;
import com.example.shortlink.api.stats.VisitStatsController;
import com.example.shortlink.ratelimit.*;
import com.example.shortlink.service.*;
import com.example.shortlink.stats.config.VisitStatsProperties;
import com.example.shortlink.stats.query.MySqlVisitStatsQuery;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Clock;
import java.time.Duration;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ManagementRateLimitRedisIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);
    RedisRateLimiter limiter;
    ShortLinkStateService state;
    MySqlVisitStatsQuery query;
    VisitCollection collection;
    MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHDB");
        var redis = new RedisProperties(); redis.setHost(REDIS.getHost()); redis.setPort(REDIS.getMappedPort(6379));
        redis.setTimeout(Duration.ofMillis(200)); redis.setConnectTimeout(Duration.ofMillis(200));
        // Long configurable refill isolates burst accounting from machine scheduling.
        limiter = new RedisRateLimiter(redis, new RateLimitProperties(3, "30s", 60, "100ms", 5, "30s", 5, "30s"));
        state = mock(ShortLinkStateService.class); query = mock(MySqlVisitStatsQuery.class); collection = mock(VisitCollection.class);
        mvc = mvc(limiter);
    }
    MockMvc mvc(RateLimiter admission) {
        return MockMvcBuilders.standaloneSetup(
                new ShortLinkController(mock(ShortLinkCreationService.class), mock(RedirectService.class), state, collection, "http://localhost"),
                new VisitStatsController(query, Clock.systemUTC(), new VisitStatsProperties(false, null, null, null, null, null, null, null, null)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addInterceptors(new InternalManagementAccess(InternalManagementApiTest.TOKEN, new com.fasterxml.jackson.databind.ObjectMapper()),
                        new ManagementRateLimitInterceptor(admission)).build();
    }
    @AfterEach void close() { limiter.destroy(); }
    @Test void realSharedQueryBucketCoversDifferentCodesEndpointsAndHeadWhileWriteAndPublicStayIndependent() throws Exception {
        for (int i=0; i<5; i++) {
            mvc.perform(get("/api/internal/links/!/" + (i%2==0 ? "stats" : "visits"))
                    .header("X-Internal-Token", InternalManagementApiTest.TOKEN)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/internal/links/Other1/visits").header("X-Internal-Token", InternalManagementApiTest.TOKEN))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(head("/api/internal/links/Ab12/stats").header("X-Internal-Token", InternalManagementApiTest.TOKEN))
                .andExpect(status().isTooManyRequests()).andExpect(content().string(""));
        for (int i=0; i<5; i++) {
            mvc.perform(put("/api/links/Ab12/enabled").header("X-Internal-Token", InternalManagementApiTest.TOKEN)
                    .contentType("application/json").content("{")).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/links/Other1/enabled").header("X-Internal-Token", InternalManagementApiTest.TOKEN)
                .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isTooManyRequests());
        assertEquals(RateLimiter.Decision.Status.ALLOWED, limiter.admitCreate("127.0.0.1").status());
        assertEquals(RateLimiter.Decision.Status.ALLOWED, limiter.admitRedirect("127.0.0.1").status());
        verifyNoInteractions(state, query, collection);
        String keys = REDIS.execInContainer("redis-cli", "KEYS", "shortlink:rate-limit:*").getStdout();
        assertFalse(keys.contains(InternalManagementApiTest.TOKEN));
    }
    @Test void independentClientsShareFixedManagementIdentityAndUnauthorizedCallsDoNotConsumeIt() throws Exception {
        for (int i=0;i<8;i++) mvc.perform(get("/api/internal/links/!/stats")).andExpect(status().isUnauthorized());
        var redis = new RedisProperties(); redis.setHost(REDIS.getHost()); redis.setPort(REDIS.getMappedPort(6379));
        var second = new RedisRateLimiter(redis, new RateLimitProperties(3, "30s", 60, "100ms", 5, "30s", 5, "30s"));
        try {
            for (int i=0;i<5;i++) assertEquals(RateLimiter.Decision.Status.ALLOWED,
                    (i%2==0 ? limiter : second).admitManagementQuery().status());
            assertEquals(RateLimiter.Decision.Status.REJECTED, second.admitManagementQuery().status());
            assertEquals(RateLimiter.Decision.Status.ALLOWED, second.admitManagementWrite().status());
        } finally { second.destroy(); }
        verifyNoInteractions(state, query, collection);
    }
    @Test void realRedisConnectionFailureClosesAuthorizedManagementBeforeBusinessAndHeadHasNoBody() throws Exception {
        var unavailable = new RedisProperties(); unavailable.setHost("127.0.0.1");
        try (var socket = new java.net.ServerSocket(0)) { unavailable.setPort(socket.getLocalPort()); }
        unavailable.setTimeout(Duration.ofMillis(200)); unavailable.setConnectTimeout(Duration.ofMillis(200));
        var broken = new RedisRateLimiter(unavailable, new RateLimitProperties(3, "6s"));
        try {
            var http = mvc(broken);
            http.perform(put("/api/links/Ab12/enabled").header("X-Internal-Token", InternalManagementApiTest.TOKEN)
                    .contentType("application/json").content("{\"enabled\":false}"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RATE_LIMIT_UNAVAILABLE"));
            http.perform(head("/api/internal/links/Ab12/visits").header("X-Internal-Token", InternalManagementApiTest.TOKEN))
                    .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
            http.perform(get("/api/internal/links/Ab12/stats"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(state, query, collection);
        } finally { broken.destroy(); }
    }
}
