package com.example.shortlink;

import com.example.shortlink.api.*;
import com.example.shortlink.api.error.ApiExceptionHandler;
import com.example.shortlink.api.ratelimit.RedirectRateLimitInterceptor;
import com.example.shortlink.api.stats.VisitCollection;
import com.example.shortlink.cache.*;
import com.example.shortlink.persistence.*;
import com.example.shortlink.ratelimit.*;
import com.example.shortlink.service.*;
import com.example.shortlink.stats.VisitRecorder;
import com.example.shortlink.stats.config.VisitStatsProperties;
import com.example.shortlink.stats.persistence.VisitWriteObservations;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
class RedirectRateLimitRedisIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    final List<RedisRateLimiter> limiters = new ArrayList<>();
    final ShortLinkMapper mapper = mock(ShortLinkMapper.class);
    final VisitRecorder recorder = mock(VisitRecorder.class);
    final RedirectCacheProperties cacheProperties = new RedirectCacheProperties(true,"5m","30s","5m","15s","5s");
    @BeforeEach void setup() {
        connection = new LettuceConnectionFactory(
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
                org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(200)).build());
        connection.afterPropertiesSet(); connection.start();
        redis = new StringRedisTemplate(connection);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> { c.serverCommands().flushDb(); return null; });
    }
    @AfterEach void close() {
        connection.destroy(); limiters.forEach(RedisRateLimiter::destroy);
    }
    RedisRateLimiter limiter(int capacity, String interval) {
        var properties = new RedisProperties(); properties.setHost(REDIS.getHost()); properties.setPort(REDIS.getMappedPort(6379));
        properties.setTimeout(Duration.ofMillis(200)); properties.setConnectTimeout(Duration.ofMillis(200));
        var limiter = new RedisRateLimiter(properties, new RateLimitProperties(3,"6s",capacity,interval,5,"1s",5,"1s"));
        limiters.add(limiter); return limiter;
    }
    MockMvc mvc(RedisRateLimiter limiter) {
        var cache = new RedisRedirectCache(redis, new ObjectMapper(), Clock.systemUTC(), cacheProperties);
        var service = new RedirectService(mapper, Clock.systemUTC(), cache, cacheProperties);
        var collection = new VisitCollection(new VisitStatsProperties(true,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",1,
                null,null,null,null,null,null), recorder,"http://localhost",new VisitWriteObservations(mock(javax.sql.DataSource.class)));
        var controller = new ShortLinkController(mock(ShortLinkCreationService.class), service,
                mock(ShortLinkStateService.class),collection,"http://localhost");
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).addInterceptors(new RedirectRateLimitInterceptor(limiter)).build();
    }
    @Test void realBucketIsSharedAcrossCodesMethodsAndContextsAndSeparateFromOtherGroups() throws Exception {
        var first = limiter(2,"30s"); var second = limiter(2,"30s");
        var a = mvc(first); var b = mvc(second);
        a.perform(get("/s/Ab12")).andExpect(status().isNotFound());
        b.perform(head("/s/Cd34")).andExpect(status().isNotFound());
        a.perform(get("/s/Ef56").header("X-Forwarded-For","8.8.8.8"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","30"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        b.perform(head("/s/Gh78")).andExpect(status().isTooManyRequests()).andExpect(content().string(""));
        verify(mapper, never()).selectById("Ef56"); verify(mapper, never()).selectById("Gh78");
        verifyNoInteractions(recorder);
        assertThat(first.admitCreate("127.0.0.1").status()).isEqualTo(RateLimiter.Decision.Status.ALLOWED);
        assertThat(first.admitManagementWrite().status()).isEqualTo(RateLimiter.Decision.Status.ALLOWED);
        assertThat(first.admitManagementQuery().status()).isEqualTo(RateLimiter.Decision.Status.ALLOWED);
        assertThat(first.admitRedirect("127.0.0.2").status()).isEqualTo(RateLimiter.Decision.Status.ALLOWED);
        REDIS.execInContainer("redis-cli","SCRIPT","FLUSH");
        assertThat(second.admitRedirect("127.0.0.1").status()).isEqualTo(RateLimiter.Decision.Status.REJECTED);
    }
    @Test void defaultInitialBurstIsSixtyAndLastTokenCompetitionIsAtomic() throws Exception {
        var first = limiter(60,"30s"); var second = limiter(60,"30s");
        for (int i=0; i<59; i++) assertThat(first.admitRedirect("127.0.0.1").status()).isEqualTo(RateLimiter.Decision.Status.ALLOWED);
        var pool = Executors.newFixedThreadPool(8); var start = new CountDownLatch(1);
        try {
            List<Future<RateLimiter.Decision>> requests = new ArrayList<>();
            for (int i=0;i<8;i++) { var chosen = i%2==0 ? first : second; requests.add(pool.submit(() -> { start.await(); return chosen.admitRedirect("127.0.0.1"); })); }
            start.countDown(); int allowed=0;
            for (var request : requests) if (request.get(5,TimeUnit.SECONDS).status()==RateLimiter.Decision.Status.ALLOWED) allowed++;
            assertThat(allowed).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void realRedisDisconnectFallsBackWithFourActualQueriesAndRecoversWithoutCachingBusyRejection() throws Exception {
        var limiter = limiter(60,"100ms"); var mvc = mvc(limiter);
        var mapping = new ShortLinkEntity(); mapping.setEnabled(true); mapping.setOriginalUrl("https://example.com/");
        CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
        when(mapper.selectById(anyString())).thenAnswer(call -> { entered.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return mapping; });
        var pool = Executors.newFixedThreadPool(4); List<Future<?>> requests = new ArrayList<>();
        // Pause the actual container network; neither limiter nor version cache can contact Redis.
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            for (String code : List.of("Ab12","Cd34","Ef56","Gh78")) requests.add(pool.submit(() -> mvc.perform(get("/s/"+code)).andExpect(status().isFound())));
            assertThat(entered.await(8,TimeUnit.SECONDS)).isTrue();
            mvc.perform(get("/s/Ij90")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("REDIRECT_LOAD_BUSY"))
                    .andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("Set-Cookie"));
            verify(mapper,never()).selectById("Ij90"); verifyNoInteractions(recorder);
            release.countDown(); for (var request : requests) request.get(8,TimeUnit.SECONDS);
            verify(recorder,times(4)).record(any(com.example.shortlink.stats.VisitEvent.class));
        } finally { release.countDown(); pool.shutdownNow(); docker.unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        assertThat(limiter.admitRedirect("127.0.0.2").status()).isEqualTo(RateLimiter.Decision.Status.ALLOWED);
        doReturn(mapping).when(mapper).selectById("Ij90");
        mvc.perform(get("/s/Ij90")).andExpect(status().isFound());
        verify(mapper).selectById("Ij90");
        mvc.perform(get("/s/Ij90")).andExpect(status().isFound());
        verify(mapper,times(1)).selectById("Ij90");
    }
}
