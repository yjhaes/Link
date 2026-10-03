package com.example.shortlink;

import com.example.shortlink.ratelimit.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CreateRateLimitRedisIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    @BeforeEach void setup() {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet(); connection.start();
        redis = new StringRedisTemplate(connection);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> { c.serverCommands().flushDb(); return null; });
    }
    java.util.List<RedisRateLimiter> limiters = new java.util.ArrayList<>();
    RedisRateLimiter limiter(int capacity, String interval) {
        var p = new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
        p.setHost(REDIS.getHost()); p.setPort(REDIS.getMappedPort(6379));
        p.setTimeout(java.time.Duration.ofMillis(200)); p.setConnectTimeout(java.time.Duration.ofMillis(200));
        var result = new RedisRateLimiter(p, new RateLimitProperties(capacity, interval));
        limiters.add(result); return result;
    }
    @AfterEach void close() { connection.destroy(); for (var limiter : limiters) limiter.destroy(); }
    @Test void lastTokenIsAtomicAcrossIndependentClients() throws Exception {
        var secondConnection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        secondConnection.afterPropertiesSet(); secondConnection.start();
        try {
            var first = limiter(3, "6s");
            var second = limiter(3, "6s");
            assertEquals(RateLimiter.Decision.Status.ALLOWED, first.admitCreate("127.0.0.1").status());
            assertEquals(RateLimiter.Decision.Status.ALLOWED, second.admitCreate("127.0.0.1").status());
            var pool = Executors.newFixedThreadPool(8);
            var start = new CountDownLatch(1);
            try {
                var futures = new java.util.ArrayList<Future<RateLimiter.Decision>>();
                for (int i=0;i<16;i++) { var limiter = i%2==0 ? first : second;
                    futures.add(pool.submit(() -> { start.await(); return limiter.admitCreate("127.0.0.1"); })); }
                start.countDown();
                int allowed=0;
                for (var f : futures) if (f.get(5, TimeUnit.SECONDS).status()==RateLimiter.Decision.Status.ALLOWED) allowed++;
                assertEquals(1, allowed);
            } finally { pool.shutdownNow(); }
        } finally { secondConnection.destroy(); }
    }
    @Test void configuredCredentialsAndDatabaseAreUsedWithoutSharingDefaultDatabaseState() throws Exception {
        REDIS.execInContainer("redis-cli", "ACL", "SETUSER", "limiter-test", "on", ">test-only-limiter-credential", "~*", "+@all");
        var p = new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
        p.setHost(REDIS.getHost()); p.setPort(REDIS.getMappedPort(6379));
        p.setUsername("limiter-test"); p.setPassword("test-only-limiter-credential"); p.setDatabase(1);
        var limiter = new RedisRateLimiter(p, new RateLimitProperties(1, "6s"));
        limiters.add(limiter);
        assertEquals(RateLimiter.Decision.Status.ALLOWED, limiter.admitCreate("127.0.0.1").status());
        assertEquals(RateLimiter.Decision.Status.REJECTED, limiter.admitCreate("127.0.0.1").status());
        assertFalse(redis.hasKey("shortlink:rate-limit:v1:create:127.0.0.1"));
        // FlushDB above intentionally isolates database zero; clean database one explicitly.
        REDIS.execInContainer("redis-cli", "-n", "1", "FLUSHDB");
    }
    @Test void independentApplicationContextsShareTheSameRedisBucket() {
        try (var first = applicationContext(); var second = applicationContext()) {
            var a = first.getBean(RateLimiter.class);
            var b = second.getBean(RateLimiter.class);
            assertEquals(RateLimiter.Decision.Status.ALLOWED, a.admitCreate("127.0.0.1").status());
            assertEquals(RateLimiter.Decision.Status.ALLOWED, b.admitCreate("127.0.0.1").status());
            assertEquals(RateLimiter.Decision.Status.ALLOWED, a.admitCreate("127.0.0.1").status());
            assertEquals(RateLimiter.Decision.Status.REJECTED, b.admitCreate("127.0.0.1").status());
            assertEquals(RateLimiter.Decision.Status.ALLOWED, b.admitCreate("127.0.0.2").status());
        }
    }

    org.springframework.context.annotation.AnnotationConfigApplicationContext applicationContext() {
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.registerBean(org.springframework.boot.autoconfigure.data.redis.RedisProperties.class, () -> {
            var p = new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
            p.setHost(REDIS.getHost()); p.setPort(REDIS.getMappedPort(6379));
            return p;
        });
        context.registerBean(RateLimitProperties.class, () -> new RateLimitProperties(3, "6s"));
        context.register(RedisRateLimiter.class);
        context.refresh();
        return context;
    }
    @Test void refillCapAndIdleTtlDoNotPrematurelyResetBucket() throws Exception {
        var limiter = limiter(2, "200ms");
        assertEquals(RateLimiter.Decision.Status.ALLOWED, limiter.admitCreate("::1").status());
        assertEquals(RateLimiter.Decision.Status.ALLOWED, limiter.admitCreate("0:0:0:0:0:0:0:1").status());
        var denied = limiter.admitCreate("::1");
        assertEquals(RateLimiter.Decision.Status.REJECTED, denied.status());
        assertTrue(denied.waitMillis() >= 1 && denied.waitMillis() <= 200);
        var key = "shortlink:rate-limit:v1:create:0:0:0:0:0:0:0:1";
        assertTrue(redis.getExpire(key, TimeUnit.MILLISECONDS) > 300);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2)).pollInterval(java.time.Duration.ofMillis(20))
                .until(() -> limiter.admitCreate("::1").status() == RateLimiter.Decision.Status.ALLOWED);
        // Idle expiry occurs only once the entire bucket could have refilled.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2)).until(() -> !redis.hasKey(key));
        assertEquals(RateLimiter.Decision.Status.ALLOWED, limiter.admitCreate("::1").status());
        assertEquals(RateLimiter.Decision.Status.ALLOWED, limiter.admitCreate("::1").status());
        assertEquals(RateLimiter.Decision.Status.REJECTED, limiter.admitCreate("::1").status());
    }

    @Test void scriptFlushRecoversWithoutResettingRemainingQuota() {
        var limiter = limiter(3, "6s");
        assertEquals(RateLimiter.Decision.Status.ALLOWED, limiter.admitCreate("127.0.0.1").status());
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> { c.scriptingCommands().scriptFlush(); return null; });
        assertEquals(RateLimiter.Decision.Status.ALLOWED, limiter.admitCreate("127.0.0.1").status());
        assertEquals(RateLimiter.Decision.Status.ALLOWED, limiter.admitCreate("127.0.0.1").status());
        assertEquals(RateLimiter.Decision.Status.REJECTED, limiter.admitCreate("127.0.0.1").status());
    }

    @Test void corruptAndFutureStateFailsClosedWithoutGrantingOrOverwritingQuota() {
        var limiter = limiter(3, "6s");
        String key = "shortlink:rate-limit:v1:create:127.0.0.1";
        for (String tokens : new String[]{"NaN", "-1", "4", "inf"}) {
            redis.opsForHash().putAll(key, java.util.Map.of("tokens", tokens, "at", "1"));
            assertEquals(RateLimiter.Decision.Status.UNAVAILABLE, limiter.admitCreate("127.0.0.1").status());
        }
        redis.opsForHash().putAll(key, java.util.Map.of("tokens", "2", "at", "9007199254740991"));
        assertEquals(RateLimiter.Decision.Status.UNAVAILABLE, limiter.admitCreate("127.0.0.1").status());
        assertEquals("2", redis.opsForHash().get(key, "tokens"));
    }    @Test void actualRedisAdmissionConsumesInvalidRequestsBeforeBodyParsingAndNeverRefunds() throws Exception {
        var mapper = org.mockito.Mockito.mock(com.example.shortlink.persistence.ShortLinkMapper.class);
        var issuer = org.mockito.Mockito.mock(com.example.shortlink.shortcode.ShortCodeIdIssuer.class);
        var mvc = createMvc(limiter(3, "6s"), mapper, issuer);
        for (int i = 0; i < 3; i++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/links")
                            .contentType("application/json").content("{"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/links")
                        .header("Forwarded", "for=127.0.0.2").header("X-Forwarded-For", "127.0.0.2")
                        .contentType("application/json").content("{\"originalUrl\":\"https://example.com/\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests());
        org.mockito.Mockito.verifyNoInteractions(mapper, issuer);
    }

    org.springframework.test.web.servlet.MockMvc createMvc(RateLimiter limiter,
            com.example.shortlink.persistence.ShortLinkMapper mapper,
            com.example.shortlink.shortcode.ShortCodeIdIssuer issuer) {
        var creation = new com.example.shortlink.service.ShortLinkCreationService(mapper,
                new com.example.shortlink.persistence.MySqlShortLinkWriter(mapper), issuer,
                new com.example.shortlink.shortcode.PermutedShortCodeEncoder(), java.time.Clock.systemUTC(),
                org.mockito.Mockito.mock(com.example.shortlink.cache.RedirectCache.class));
        var controller = new com.example.shortlink.api.ShortLinkController(creation,
                org.mockito.Mockito.mock(com.example.shortlink.service.RedirectService.class),
                org.mockito.Mockito.mock(com.example.shortlink.service.ShortLinkStateService.class),
                org.mockito.Mockito.mock(com.example.shortlink.api.stats.VisitCollection.class), "http://localhost");
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.example.shortlink.api.error.ApiExceptionHandler())
                .addInterceptors(new com.example.shortlink.api.ratelimit.CreateRateLimitInterceptor(limiter)).build();
    }
    @Test void executedCommandWithLostResponseIsNotRetriedOrReplayed() throws Exception {
        var direct = limiter(3, "30s");
        direct.admitCreate("127.0.0.2"); // Preload the script; EVALSHA below really executes.
        try (var proxy = new LostResponseProxy(REDIS.getHost(), REDIS.getMappedPort(6379))) {
            var p = new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
            p.setHost("127.0.0.1"); p.setPort(proxy.port());
            p.setTimeout(java.time.Duration.ofMillis(200)); p.setConnectTimeout(java.time.Duration.ofMillis(200));
            var limiter = new RedisRateLimiter(p, new RateLimitProperties(3, "30s"));
            limiters.add(limiter);
            var mapper = org.mockito.Mockito.mock(com.example.shortlink.persistence.ShortLinkMapper.class);
            var issuer = org.mockito.Mockito.mock(com.example.shortlink.shortcode.ShortCodeIdIssuer.class);
            createMvc(limiter, mapper, issuer).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/links")
                            .contentType("application/json").content("{\"originalUrl\":\"https://example.com/\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("RATE_LIMIT_UNAVAILABLE"));
            org.mockito.Mockito.verifyNoInteractions(mapper, issuer);
            assertTrue(proxy.executed.await(2, TimeUnit.SECONDS));
            proxy.completed.get(2, TimeUnit.SECONDS);
            assertEquals(1, proxy.evaluations.get());
            assertEquals(RateLimiter.Decision.Status.ALLOWED, direct.admitCreate("127.0.0.1").status());
            assertEquals(RateLimiter.Decision.Status.ALLOWED, direct.admitCreate("127.0.0.1").status());
            assertEquals(RateLimiter.Decision.Status.REJECTED, direct.admitCreate("127.0.0.1").status());
        }
    }

    /** Forward complete RESP frames, discard only the response after Redis executed EVALSHA. */
    static class LostResponseProxy implements AutoCloseable {
        final java.net.ServerSocket server;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final CountDownLatch executed = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger evaluations = new java.util.concurrent.atomic.AtomicInteger();
        final Future<?> completed;
        volatile java.net.Socket downstream;
        LostResponseProxy(String host, int port) throws java.io.IOException {
            server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
            completed = executor.submit(() -> {
                try (var client = server.accept(); var upstream = new java.net.Socket(host, port)) {
                    downstream = client;
                    client.setSoTimeout(2000); upstream.setSoTimeout(2000);
                    while (true) {
                        byte[] request = frame(client.getInputStream());
                        upstream.getOutputStream().write(request); upstream.getOutputStream().flush();
                        byte[] reply = frame(upstream.getInputStream());
                        if (new String(request, java.nio.charset.StandardCharsets.UTF_8).contains("EVALSHA")) {
                            evaluations.incrementAndGet(); executed.countDown();
                            // Keep the connection open until the client's command deadline closes it.
                        } else {
                            client.getOutputStream().write(reply); client.getOutputStream().flush();
                        }
                    }
                } catch (java.io.EOFException expectedClose) {
                    // Client closes the uncertain connection; no reconnect is allowed.
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
        }
        int port() { return server.getLocalPort(); }
        static byte[] frame(java.io.InputStream input) throws java.io.IOException {
            var output = new java.io.ByteArrayOutputStream();
            int type = input.read();
            if (type == -1) throw new java.io.EOFException();
            output.write(type);
            var line = new java.io.ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int next = input.read();
                if (next == -1) throw new java.io.EOFException();
                output.write(next); line.write(next);
                if (previous == '\r' && next == '\n') break;
                previous = next;
            }
            String value = line.toString(java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (type == '$') {
                int length = Integer.parseInt(value);
                if (length >= 0) {
                    byte[] bytes = input.readNBytes(length + 2);
                    if (bytes.length != length + 2) throw new java.io.EOFException();
                    output.write(bytes);
                }
            } else if (type == '*' || type == '%' || type == '~' || type == '>') {
                int count = Integer.parseInt(value) * (type == '%' ? 2 : 1);
                for (int i = 0; i < count; i++) output.write(frame(input));
            }
            return output.toByteArray();
        }
        @Override public void close() throws java.io.IOException {
            server.close(); if (downstream != null) downstream.close(); executor.shutdownNow();
        }
    }}
