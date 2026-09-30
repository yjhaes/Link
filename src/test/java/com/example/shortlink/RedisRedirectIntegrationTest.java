package com.example.shortlink;

import com.example.shortlink.LinkApplication;
import com.example.shortlink.service.RedirectCache;
import com.example.shortlink.service.RedirectCacheEntry;
import com.example.shortlink.service.RedirectCacheRead;
import com.example.shortlink.service.CreatedShortLink;
import com.example.shortlink.service.ShortLinkService;
import com.example.shortlink.service.PermutedShortCodeEncoder;
import com.example.shortlink.persistence.ShortLinkMapper;
import com.example.shortlink.persistence.ShortLinkEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mockingDetails;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = LinkApplication.class, properties = "short-link.redirect-cache.load-wait=5s")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class RedisRedirectIntegrationTest {

    private static final String CACHE_KEY_PREFIX = "shortlink:redirect:v2:";
    private static final String ORIGINAL_URL = "https://redis-cache.example/article";
    private static final long CACHE_TTL_MILLIS = 300_000L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("short_link_test")
            .withUsername("short_link")
            .withPassword("short_link_test");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private RedirectCache redirectCache;

    @MockitoSpyBean
    private PermutedShortCodeEncoder shortCodeEncoder;

    @Autowired
    private ShortLinkService shortLinkService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private ShortLinkMapper shortLinkMapper;

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @BeforeEach
    void clearTestData() {
        jdbcTemplate.update("DELETE FROM short_link");
        jdbcTemplate.update("DELETE FROM short_code_issuance");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void creatingAPermanentMappingDoesNotPrewarmTheRedirectCache() throws Exception {
        String response = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL).toString()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String shortCode = objectMapper.readTree(response).path("shortCode").asText();

        JsonNode placeholder = objectMapper.readTree(redisTemplate.opsForValue().get(cacheKey(shortCode)));
        assertThat(placeholder.path("status").asText()).isEqualTo("PLACEHOLDER");
        assertThat(placeholder.path("originalUrl").isNull()).isTrue();
        assertThat(redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS))
                .isPositive().isLessThanOrEqualTo(CACHE_TTL_MILLIS);
    }

    @Test
    void committedCreationWithUnconfirmedCoordinationReturnsRecoverable503() throws Exception {
        doThrow(new IllegalStateException("Redis coordination timed out"))
                .when(redirectCache).replaceVersion(anyString());

        String response = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL).toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CREATE_CACHE_COORDINATION_UNCONFIRMED"))
                .andExpect(jsonPath("$.shortCode").isNotEmpty())
                .andExpect(jsonPath("$.message").value(
                        "Database creation committed; cache coordination unconfirmed."))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        String shortCode = objectMapper.readTree(response).path("shortCode").asText();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code = ?", Integer.class, shortCode)).isEqualTo(1);
        long mappings = mappingCount();
        long issued = issuanceCount();
        jdbcTemplate.update("UPDATE short_link SET enabled = FALSE WHERE short_code = ?", shortCode);
        doCallRealMethod().when(redirectCache).replaceVersion(anyString());
        shortLinkService.recoverCacheCoordination(shortCode);
        shortLinkService.recoverCacheCoordination(shortCode);

        assertThat(mappingCount()).isEqualTo(mappings);
        assertThat(issuanceCount()).isEqualTo(issued);
        mockMvc.perform(get("/s/" + shortCode))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LINK_DISABLED"));
    }

    @Test
    void successfulCreationClearsAPrevious404AndReturnsAUsableMapping() throws Exception {
        String shortCode = "Made";
        mockMvc.perform(get("/s/" + shortCode)).andExpect(status().isNotFound());
        String previousGeneration = redirectCache.find(shortCode).generation();
        doReturn(shortCode).when(shortCodeEncoder).encode(anyLong());

        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL).toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value(shortCode));
        assertThat(redirectCache.find(shortCode).generation()).isNotEqualTo(previousGeneration);
        assertThat(redirectCache.find(shortCode).status()).isEqualTo(RedirectCacheRead.Status.PLACEHOLDER);
        assertRedirect(shortCode, ORIGINAL_URL);
    }

    @Test
    void creationConfirmsItsOwnCommitEvenWhenACallingTransactionRollsBack() throws Exception {
        String shortCode = new TransactionTemplate(transactionManager).execute(transaction -> {
            CreatedShortLink created = shortLinkService.create(ORIGINAL_URL, null);
            transaction.setRollbackOnly();
            return created.shortCode();
        });

        assertRedirect(shortCode, ORIGINAL_URL);
        assertThat(mappingCount()).isEqualTo(1);
        assertThat(issuanceCount()).isEqualTo(1);
    }

    @Test
    void unconfirmedDatabaseInsertReturns500WithoutPartialCompletionOrCoordination() throws Exception {
        doThrow(new IllegalStateException("Database commit was not confirmed"))
                .when(shortLinkMapper).insert(any(ShortLinkEntity.class));

        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL).toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.shortCode").doesNotExist());
        verify(redirectCache, never()).replaceVersion(anyString());
    }

    @Test
    void executedCoordinationWithLostAcknowledgementCanBeRecoveredRepeatedly() throws Exception {
        String shortCode = "New1";
        doReturn(shortCode).when(shortCodeEncoder).encode(anyLong());
        mockMvc.perform(get("/s/" + shortCode)).andExpect(status().isNotFound());
        String oldGeneration = redirectCache.find(shortCode).generation();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Redis applied the write but its response timed out");
        }).when(redirectCache).replaceVersion(shortCode);

        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL).toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.shortCode").value(shortCode));
        assertThat(redirectCache.find(shortCode).generation()).isNotEqualTo(oldGeneration);
        assertThat(redirectCache.find(shortCode).status()).isEqualTo(RedirectCacheRead.Status.PLACEHOLDER);

        doCallRealMethod().when(redirectCache).replaceVersion(shortCode);
        shortLinkService.recoverCacheCoordination(shortCode);
        shortLinkService.recoverCacheCoordination(shortCode);
        assertRedirect(shortCode, ORIGINAL_URL);
        assertThat(mappingCount()).isEqualTo(1);
        assertThat(issuanceCount()).isEqualTo(1);
    }

    @Test
    void creationInAnIndependentInstanceRejectsLate404RefillAndNewRequestsSeeTheMapping() throws Exception {
        String shortCode = "Race";
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch resumeOldQuery = new CountDownLatch(1);
        AtomicBoolean firstQuery = new AtomicBoolean(true);
        AtomicReference<Boolean> oldRefillAccepted = new AtomicReference<>();
        var queryMySql = mockingDetails(shortLinkMapper).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object snapshot = queryMySql.answer(invocation);
            if (firstQuery.getAndSet(false)) {
                assertThat(snapshot).isNull();
                snapshotRead.countDown();
                if (!resumeOldQuery.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release the old query");
                }
            }
            return snapshot;
        }).when(shortLinkMapper).selectById(shortCode);
        doAnswer(invocation -> {
            Boolean accepted = (Boolean) invocation.callRealMethod();
            oldRefillAccepted.set(accepted);
            return accepted;
        }).when(redirectCache).storeIfVersion(eq(shortCode), anyString(),
                eq(RedirectCacheRead.Status.NOT_FOUND), isNull());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (ConfigurableApplicationContext creator = independentCreator()) {
            assertThat(creator.getBean(RedirectCache.class)).isNotSameAs(redirectCache);
            Future<Integer> oldRequest = executor.submit(() ->
                    mockMvc.perform(get("/s/" + shortCode)).andReturn().getResponse().getStatus());
            assertThat(snapshotRead.await(5, TimeUnit.SECONDS)).isTrue();
            String oldGeneration = redirectCache.find(shortCode).generation();

            CreatedShortLink created = creator.getBean(ShortLinkService.class).create(ORIGINAL_URL, null);
            assertThat(created.shortCode()).isEqualTo(shortCode);
            assertThat(redirectCache.find(shortCode).generation()).isNotEqualTo(oldGeneration);
            // This request starts after create returned, while the old snapshot is still paused.
            Future<?> currentRequest = executor.submit(() -> {
                assertRedirect(shortCode, ORIGINAL_URL);
                return null;
            });
            currentRequest.get(2, TimeUnit.SECONDS); // Less than the shared-load wait budget.
            verify(shortLinkMapper, times(2)).selectById(shortCode);
            resumeOldQuery.countDown();
            // Overlapping requests may finish with the snapshot they read before creation.
            assertThat(oldRequest.get(5, TimeUnit.SECONDS)).isEqualTo(404);
            assertThat(oldRefillAccepted.get()).isFalse();
            assertRedirect(shortCode, ORIGINAL_URL);
            assertThat(redirectCache.find(shortCode).status()).isEqualTo(RedirectCacheRead.Status.REDIRECT);
        } finally {
            resumeOldQuery.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void databaseQueryFailureReturns500AndDoesNotCacheNotFound() throws Exception {
        doThrow(new IllegalStateException("MySQL query failed")).when(shortLinkMapper).selectById("Fail");
        mockMvc.perform(get("/s/Fail"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertThat(redirectCache.find("Fail").status()).isEqualTo(RedirectCacheRead.Status.PLACEHOLDER);
        doAnswer(mockingDetails(shortLinkMapper).getMockCreationSettings().getDefaultAnswer())
                .when(shortLinkMapper).selectById("Fail");
        mockMvc.perform(get("/s/Fail")).andExpect(status().isNotFound());
        assertThat(redirectCache.find("Fail").status()).isEqualTo(RedirectCacheRead.Status.NOT_FOUND);
    }

    @Test
    void newInvalidCodesEachQueryMySqlAndHaveFiniteTtlAndExpirationReloads() throws Exception {
        for (String shortCode : new String[]{"Bad1", "Bad2", "Bad3"}) {
            mockMvc.perform(get("/s/" + shortCode)).andExpect(status().isNotFound());
            verify(shortLinkMapper).selectById(shortCode);
            assertThat(redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS))
                    .isPositive().isLessThanOrEqualTo(30_000L);
        }
        redisTemplate.expire(cacheKey("Bad1"), Duration.ofMillis(50));
        await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey("Bad1"))));
        mockMvc.perform(get("/s/Bad1")).andExpect(status().isNotFound());
        verify(shortLinkMapper, times(2)).selectById("Bad1");
    }

    @Test
    void repeatedPostAfterALostResponseStillCreatesAnotherMapping() throws Exception {
        for (int request = 0; request < 2; request++) {
            mockMvc.perform(post("/api/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.createObjectNode().put("originalUrl", ORIGINAL_URL).toString()))
                    .andExpect(status().isCreated());
        }
        assertThat(mappingCount()).isEqualTo(2);
        assertThat(issuanceCount()).isEqualTo(2);
    }

    @Test
    void firstRedirectMissLoadsMySqlAndWritesTheExpectedValueWithAnExpiringTtl() throws Exception {
        String shortCode = "A1b2";
        insertMapping(shortCode, ORIGINAL_URL);

        mockMvc.perform(get("/s/" + shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", ORIGINAL_URL))
                .andExpect(header().string("Cache-Control", "no-store"));

        String serializedValue = redisTemplate.opsForValue().get(cacheKey(shortCode));
        JsonNode cacheValue = objectMapper.readTree(serializedValue);
        assertThat(cacheValue.size()).isEqualTo(5);
        assertThat(cacheValue.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(cacheValue.path("generation").asText()).isNotBlank();
        assertThat(cacheValue.path("status").asText()).isEqualTo("REDIRECT");
        assertThat(cacheValue.path("originalUrl").asText()).isEqualTo(ORIGINAL_URL);
        assertThat(cacheValue.has("expiresAt")).isTrue();
        assertThat(cacheValue.get("expiresAt").isNull()).isTrue();

        Long ttlMillis = redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS);
        assertThat(ttlMillis).isBetween((long) (CACHE_TTL_MILLIS * 0.9) - 1_000L, CACHE_TTL_MILLIS);
        verify(shortLinkMapper).selectById(shortCode);
    }

    @Test
    void repeatedRedirectsUseCaseSensitiveKeysAndDoNotReadMySqlAgain() throws Exception {
        insertMapping("A1b2", "https://uppercase.example/");
        insertMapping("a1b2", "https://lowercase.example/");

        assertRedirect("A1b2", "https://uppercase.example/");
        assertRedirect("a1b2", "https://lowercase.example/");
        clearInvocations(shortLinkMapper);

        assertRedirect("A1b2", "https://uppercase.example/");
        assertRedirect("a1b2", "https://lowercase.example/");

        verify(shortLinkMapper, never()).selectById("A1b2");
        verify(shortLinkMapper, never()).selectById("a1b2");
        assertThat(redisTemplate.opsForValue().get(cacheKey("A1b2")))
                .contains("https://uppercase.example/");
        assertThat(redisTemplate.opsForValue().get(cacheKey("a1b2")))
                .contains("https://lowercase.example/");
    }

    @Test
    void committedDisableAndReenableTakeEffectAfterControlledCoordination() throws Exception {
        String shortCode = "Md12";
        String key = cacheKey(shortCode);
        insertMapping(shortCode, ORIGINAL_URL);

        assertRedirect(shortCode, ORIGINAL_URL);
        assertThat(redisTemplate.hasKey(key)).isTrue();

        assertThat(jdbcTemplate.update(
                "UPDATE short_link SET enabled = FALSE WHERE short_code = ?", shortCode))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT enabled FROM short_link WHERE short_code = ?", Integer.class, shortCode))
                .isZero();
        assertThat(redisTemplate.hasKey(key)).isTrue();
        shortLinkService.recoverCacheCoordination(shortCode);
        assertThat(redirectCache.find(shortCode).status()).isEqualTo(RedirectCacheRead.Status.PLACEHOLDER);

        clearInvocations(shortLinkMapper);
        mockMvc.perform(get("/s/" + shortCode))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LINK_DISABLED"))
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(shortLinkMapper).selectById(shortCode);
        JsonNode placeholder = objectMapper.readTree(redisTemplate.opsForValue().get(key));
        assertThat(placeholder.path("status").asText()).isEqualTo("DISABLED");

        assertThat(jdbcTemplate.update(
                "UPDATE short_link SET enabled = TRUE WHERE short_code = ?", shortCode))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT enabled FROM short_link WHERE short_code = ?", Integer.class, shortCode))
                .isEqualTo(1);
        shortLinkService.recoverCacheCoordination(shortCode);
        clearInvocations(shortLinkMapper);

        assertRedirect(shortCode, ORIGINAL_URL);
        assertThat(redisTemplate.hasKey(key)).isTrue();
        verify(shortLinkMapper).selectById(shortCode);
    }

    @Test
    void expiringRedirectStoresItsBusinessExpiryAndRemainingTtlAndLaterHitsAvoidMySql() throws Exception {
        String shortCode = "Tm12";
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC)
                .plusMinutes(1)
                .truncatedTo(ChronoUnit.MILLIS);
        insertExpiringMapping(shortCode, ORIGINAL_URL, expiresAt);

        assertRedirect(shortCode, ORIGINAL_URL);

        String key = cacheKey(shortCode);
        JsonNode cacheValue = objectMapper.readTree(redisTemplate.opsForValue().get(key));
        Instant expectedExpiry = expiresAt.toInstant(ZoneOffset.UTC);
        assertThat(cacheValue.size()).isEqualTo(5);
        assertThat(cacheValue.path("status").asText()).isEqualTo("REDIRECT");
        assertThat(cacheValue.path("originalUrl").asText()).isEqualTo(ORIGINAL_URL);
        assertThat(cacheValue.path("expiresAt").asText()).isEqualTo(expectedExpiry.toString());

        Long ttlMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        long remainingMillis = Duration.between(Instant.now(), expectedExpiry).toMillis();
        assertThat(ttlMillis).isPositive();
        assertThat(ttlMillis).isLessThanOrEqualTo(CACHE_TTL_MILLIS);
        assertThat(ttlMillis).isBetween(remainingMillis - 10_000L, remainingMillis + 1_000L);
        verify(shortLinkMapper).selectById(shortCode);

        clearInvocations(shortLinkMapper);
        assertRedirect(shortCode, ORIGINAL_URL);
        verify(shortLinkMapper, never()).selectById(shortCode);
    }

    @Test
    void expiringRedirectTtlDoesNotExceedTheConfiguredMaximum() throws Exception {
        String shortCode = "Cp12";
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC)
                .plusMinutes(10)
                .truncatedTo(ChronoUnit.MILLIS);
        insertExpiringMapping(shortCode, ORIGINAL_URL, expiresAt);

        assertRedirect(shortCode, ORIGINAL_URL);

        Long ttlMillis = redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS);
        assertThat(ttlMillis).isBetween((long) (CACHE_TTL_MILLIS * 0.9) - 1_000L, CACHE_TTL_MILLIS);
    }

    @Test
    void expiredSnapshotBecomesGoneWithoutReadingMySql() throws Exception {
        String shortCode = "Ex12";
        Instant expiresAt = Instant.now().minusSeconds(1);
        String value = objectMapper.createObjectNode()
                .put("schemaVersion", 2)
                .put("generation", "00000000-0000-0000-0000-000000000001")
                .put("status", "REDIRECT")
                .put("originalUrl", ORIGINAL_URL)
                .put("expiresAt", expiresAt.toString())
                .toString();
        redisTemplate.opsForValue().set(cacheKey(shortCode), value, Duration.ofMinutes(5));
        clearInvocations(shortLinkMapper);

        mockMvc.perform(get("/s/" + shortCode))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("LINK_EXPIRED"))
                .andExpect(header().string("Cache-Control", "no-store"));

        assertThat(redirectCache.find(shortCode).status()).isEqualTo(RedirectCacheRead.Status.EXPIRED);
        mockMvc.perform(get("/s/" + shortCode)).andExpect(status().isGone());
        verify(shortLinkMapper, never()).selectById(shortCode);
    }

    @Test
    void malformedCacheValueFallsBackToMySqlAndReplacesTheEntry() throws Exception {
        String shortCode = "Bad1";
        insertMapping(shortCode, ORIGINAL_URL);
        redisTemplate.opsForValue().set(cacheKey(shortCode), "{broken", Duration.ofMinutes(5));

        assertRedirect(shortCode, ORIGINAL_URL);

        JsonNode repairedValue = objectMapper.readTree(redisTemplate.opsForValue().get(cacheKey(shortCode)));
        assertThat(repairedValue.path("originalUrl").asText()).isEqualTo(ORIGINAL_URL);
        verify(shortLinkMapper, times(1)).selectById(shortCode);
    }

    @Test
    void firstReadAtomicallyInitializesAFinitePlaceholderAndLaterReadsItsGeneration() throws Exception {
        String shortCode = "Mis1";

        RedirectCacheRead firstRead = redirectCache.find(shortCode);
        String serializedPlaceholder = redisTemplate.opsForValue().get(cacheKey(shortCode));
        JsonNode placeholder = objectMapper.readTree(serializedPlaceholder);
        Long ttlMillis = redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS);

        assertThat(firstRead.status()).isEqualTo(RedirectCacheRead.Status.MISS);
        assertThat(firstRead.generation()).isNotBlank();
        assertThat(placeholder.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(placeholder.path("generation").asText()).isEqualTo(firstRead.generation());
        assertThat(placeholder.path("status").asText()).isEqualTo("PLACEHOLDER");
        assertThat(placeholder.path("originalUrl").isNull()).isTrue();
        assertThat(placeholder.path("expiresAt").isNull()).isTrue();
        assertThat(ttlMillis).isPositive().isLessThanOrEqualTo(CACHE_TTL_MILLIS);

        RedirectCacheRead secondRead = redirectCache.find(shortCode);
        assertThat(secondRead.status()).isEqualTo(RedirectCacheRead.Status.PLACEHOLDER);
        assertThat(secondRead.generation()).isEqualTo(firstRead.generation());
    }

    @Test
    void aValueWithoutFiniteRedisTtlIsReplacedWithANewVersionedPlaceholder() throws Exception {
        String shortCode = "Inf1";
        String oldGeneration = "00000000-0000-0000-0000-000000000001";
        redisTemplate.opsForValue().set(
                cacheKey(shortCode),
                objectMapper.createObjectNode()
                        .put("schemaVersion", 2)
                        .put("generation", oldGeneration)
                        .put("status", "REDIRECT")
                        .put("originalUrl", ORIGINAL_URL)
                        .putNull("expiresAt")
                        .toString());

        RedirectCacheRead read = redirectCache.find(shortCode);
        JsonNode replacement = objectMapper.readTree(redisTemplate.opsForValue().get(cacheKey(shortCode)));
        Long ttlMillis = redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS);

        assertThat(read.status()).isEqualTo(RedirectCacheRead.Status.MISS);
        assertThat(read.generation()).isNotEqualTo(oldGeneration);
        assertThat(replacement.path("status").asText()).isEqualTo("PLACEHOLDER");
        assertThat(replacement.path("generation").asText()).isEqualTo(read.generation());
        assertThat(ttlMillis).isPositive().isLessThanOrEqualTo(CACHE_TTL_MILLIS);
    }

    @Test
    void redirectCacheProtocolKeepsTheThreeRejectionResultsDistinct() throws Exception {
        RedirectCacheRead.Status[] outcomes = {
                RedirectCacheRead.Status.NOT_FOUND,
                RedirectCacheRead.Status.EXPIRED,
                RedirectCacheRead.Status.DISABLED
        };

        for (int index = 0; index < outcomes.length; index++) {
            String shortCode = "Rj" + index + "1";
            RedirectCacheRead initialized = redirectCache.find(shortCode);
            RedirectCacheEntry result = outcomes[index] == RedirectCacheRead.Status.DISABLED
                    ? new RedirectCacheEntry(null, Instant.now().plusSeconds(60))
                    : null;

            assertThat(redirectCache.storeIfVersion(shortCode, initialized.generation(), outcomes[index], result))
                    .isTrue();
            RedirectCacheRead cached = redirectCache.find(shortCode);

            assertThat(cached.status()).isEqualTo(outcomes[index]);
            if (outcomes[index] == RedirectCacheRead.Status.DISABLED) {
                assertThat(cached.entry().expiresAt()).isEqualTo(result.expiresAt());
            }
        }
    }

    @Test
    void expiredMappingMissAndHitReuseGoneUntilCacheNaturallyExpires() throws Exception {
        String shortCode = "Gone";
        insertMapping(shortCode, ORIGINAL_URL);
        jdbcTemplate.update("UPDATE short_link SET expires_at = ?, enabled = false WHERE short_code = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), shortCode);
        Long previousTtl = null;
        for (int request = 0; request < 2; request++) {
            mockMvc.perform(get("/s/" + shortCode))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("LINK_EXPIRED"))
                    .andExpect(header().string("Cache-Control", "no-store"));
            Long ttl = redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS);
            assertThat(ttl).isBetween(269_000L, 300_000L);
            if (previousTtl != null) {
                assertThat(ttl).isLessThanOrEqualTo(previousTtl);
            }
            previousTtl = ttl;
        }
        verify(shortLinkMapper).selectById(shortCode);
        JsonNode result = objectMapper.readTree(redisTemplate.opsForValue().get(cacheKey(shortCode)));
        assertThat(result.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(result.path("originalUrl").isNull()).isTrue();
        redisTemplate.expire(cacheKey(shortCode), Duration.ofMillis(50));
        await().atMost(Duration.ofSeconds(2)).until(() -> !redisTemplate.hasKey(cacheKey(shortCode)));
        mockMvc.perform(get("/s/" + shortCode))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("LINK_EXPIRED"));
        verify(shortLinkMapper, times(2)).selectById(shortCode);
        assertThat(redirectCache.find(shortCode).status()).isEqualTo(RedirectCacheRead.Status.EXPIRED);
    }

    @Test
    void expirationUpdateCannotOverwriteReplacedOrReinitializedVersion() throws Exception {
        for (boolean reinitialize : new boolean[]{false, true}) {
            String shortCode = reinitialize ? "Rein" : "Repl";
            RedirectCacheRead original = redirectCache.find(shortCode);
            assertThat(redirectCache.storeIfVersion(shortCode, original.generation(),
                    RedirectCacheRead.Status.REDIRECT, new RedirectCacheEntry(ORIGINAL_URL, null))).isTrue();
            if (reinitialize) {
                redisTemplate.delete(cacheKey(shortCode));
                redirectCache.find(shortCode);
            } else {
                redirectCache.replaceVersion(shortCode);
            }
            RedirectCacheRead current = redirectCache.find(shortCode);
            assertThat(redirectCache.storeIfVersion(shortCode, original.generation(),
                    RedirectCacheRead.Status.EXPIRED, null)).isFalse();
            assertThat(redirectCache.find(shortCode)).isEqualTo(current);
        }
    }

    @Test
    void repeatedMissingMappingRequestsReuseNotFoundWithoutRenewingTtl() throws Exception {
        String shortCode = "Nope";
        Long firstTtl = null;
        for (int request = 0; request < 2; request++) {
            mockMvc.perform(get("/s/" + shortCode))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                    .andExpect(header().string("Cache-Control", "no-store"));
            Long ttl = redisTemplate.getExpire(cacheKey(shortCode), TimeUnit.MILLISECONDS);
            assertThat(ttl).isBetween(26_000L, 30_000L);
            if (firstTtl == null) {
                firstTtl = ttl;
            } else {
                assertThat(ttl).isLessThanOrEqualTo(firstTtl);
            }
        }

        JsonNode placeholder = objectMapper.readTree(redisTemplate.opsForValue().get(cacheKey(shortCode)));
        assertThat(placeholder.path("status").asText()).isEqualTo("NOT_FOUND");
        verify(shortLinkMapper).selectById(shortCode);
    }

    @Test
    void versionReplacementAndMissingEntryBothRejectAnOldConditionalRefill() throws Exception {
        String replacedCode = "Rep1";
        RedirectCacheRead beforeReplacement = redirectCache.find(replacedCode);
        String replacementGeneration = redirectCache.replaceVersion(replacedCode);

        assertThat(replacementGeneration).isNotEqualTo(beforeReplacement.generation());
        assertThat(redirectCache.storeIfVersion(
                replacedCode,
                beforeReplacement.generation(),
                RedirectCacheRead.Status.REDIRECT,
                new RedirectCacheEntry(ORIGINAL_URL, null))).isFalse();
        assertThat(redirectCache.find(replacedCode).status()).isEqualTo(RedirectCacheRead.Status.PLACEHOLDER);
        JsonNode replacement = objectMapper.readTree(redisTemplate.opsForValue().get(cacheKey(replacedCode)));
        assertThat(replacement.path("generation").asText()).isEqualTo(replacementGeneration);
        assertThat(replacement.path("status").asText()).isEqualTo("PLACEHOLDER");

        String lostCode = "Los1";
        RedirectCacheRead beforeLoss = redirectCache.find(lostCode);
        assertThat(redisTemplate.delete(cacheKey(lostCode))).isTrue();
        RedirectCacheRead afterLoss = redirectCache.find(lostCode);

        assertThat(afterLoss.status()).isEqualTo(RedirectCacheRead.Status.MISS);
        assertThat(afterLoss.generation()).isNotEqualTo(beforeLoss.generation());
        assertThat(redirectCache.storeIfVersion(
                lostCode,
                beforeLoss.generation(),
                RedirectCacheRead.Status.REDIRECT,
                new RedirectCacheEntry(ORIGINAL_URL, null))).isFalse();
        JsonNode freshEntry = objectMapper.readTree(redisTemplate.opsForValue().get(cacheKey(lostCode)));
        assertThat(freshEntry.path("generation").asText()).isEqualTo(afterLoss.generation());
        assertThat(freshEntry.path("status").asText()).isEqualTo("PLACEHOLDER");
    }

    @Test
    void disabledMissHitKeepsExpiryAndDoesNotRenewTtl() throws Exception {
        String code = "Dis1";
        insertExpiringMapping(code, ORIGINAL_URL, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        jdbcTemplate.update("UPDATE short_link SET enabled = false WHERE short_code = ?", code);
        Long previous = null;
        for (int request = 0; request < 2; request++) {
            mockMvc.perform(get("/s/" + code)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("LINK_DISABLED"))
                    .andExpect(header().string("Cache-Control", "no-store"));
            Long ttl = redisTemplate.getExpire(cacheKey(code), TimeUnit.MILLISECONDS);
            assertThat(ttl).isBetween(13_000L, 15_000L);
            if (previous != null) assertThat(ttl).isLessThanOrEqualTo(previous);
            previous = ttl;
        }
        verify(shortLinkMapper).selectById(code);
        RedirectCacheRead cached = redirectCache.find(code);
        assertThat(cached.status()).isEqualTo(RedirectCacheRead.Status.DISABLED);
        assertThat(cached.entry().expiresAt()).isNotNull();
        assertThat(cached.entry().originalUrl()).isNull();
        redisTemplate.expire(cacheKey(code), Duration.ofMillis(50));
        await().atMost(Duration.ofSeconds(2)).until(() -> !redisTemplate.hasKey(cacheKey(code)));
        mockMvc.perform(get("/s/" + code)).andExpect(status().isForbidden());
        verify(shortLinkMapper, times(2)).selectById(code);
    }

    @Test
    void disabledCachedSnapshotExpiresToGoneWithoutMySql() throws Exception {
        String code = "Dis2";
        insertMapping(code, ORIGINAL_URL);
        jdbcTemplate.update("UPDATE short_link SET enabled = false WHERE short_code = ?", code);
        mockMvc.perform(get("/s/" + code)).andExpect(status().isForbidden());
        RedirectCacheRead read = redirectCache.find(code);
        // Preserve a disabled result beyond its business deadline to exercise the HTTP hit boundary.
        assertThat(redirectCache.storeIfVersion(code, read.generation(), RedirectCacheRead.Status.DISABLED,
                new RedirectCacheEntry(null, Instant.now().minusSeconds(1)))).isTrue();
        mockMvc.perform(get("/s/" + code)).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("LINK_EXPIRED"));
        mockMvc.perform(get("/s/" + code)).andExpect(status().isGone());
        verify(shortLinkMapper).selectById(code);
        assertThat(redirectCache.find(code).status()).isEqualTo(RedirectCacheRead.Status.EXPIRED);
    }

    @Test
    void reenableInIndependentInstanceRejectsLateDisabledRefill() throws Exception {
        String code = "Dis3";
        insertMapping(code, ORIGINAL_URL);
        jdbcTemplate.update("UPDATE short_link SET enabled = false WHERE short_code = ?", code);
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicReference<Boolean> accepted = new AtomicReference<>();
        var query = mockingDetails(shortLinkMapper).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object snapshot = query.answer(invocation);
            if (first.getAndSet(false)) {
                assertThat(((ShortLinkEntity) snapshot).isEnabled()).isFalse();
                snapshotRead.countDown();
                if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Old query timed out");
            }
            return snapshot;
        }).when(shortLinkMapper).selectById(code);
        doAnswer(invocation -> {
            Boolean stored = (Boolean) invocation.callRealMethod();
            accepted.set(stored);
            return stored;
        }).when(redirectCache).storeIfVersion(eq(code), anyString(), eq(RedirectCacheRead.Status.DISABLED), any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (ConfigurableApplicationContext maintainer = independentCreator()) {
            Future<Integer> old = executor.submit(() -> mockMvc.perform(get("/s/" + code))
                    .andReturn().getResponse().getStatus());
            assertThat(snapshotRead.await(5, TimeUnit.SECONDS)).isTrue();
            maintainer.getBean(JdbcTemplate.class).update(
                    "UPDATE short_link SET enabled = true WHERE short_code = ?", code);
            maintainer.getBean(ShortLinkService.class).recoverCacheCoordination(code);
            Future<?> currentRequest = executor.submit(() -> {
                assertRedirect(code, ORIGINAL_URL);
                return null;
            });
            currentRequest.get(2, TimeUnit.SECONDS); // Cannot pass by timing out of the old task.
            verify(shortLinkMapper, times(2)).selectById(code);
            resume.countDown();
            assertThat(old.get(5, TimeUnit.SECONDS)).isEqualTo(403);
            assertThat(accepted.get()).isFalse();
            assertRedirect(code, ORIGINAL_URL);
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void maintenanceRetryOnlyCoordinatesCurrentCommittedState() throws Exception {
        String code = "Dis4";
        insertMapping(code, ORIGINAL_URL);
        jdbcTemplate.update("UPDATE short_link SET enabled = false WHERE short_code = ?", code);
        mockMvc.perform(get("/s/" + code)).andExpect(status().isForbidden());
        long mappings = mappingCount();
        long issuances = issuanceCount();
        jdbcTemplate.update("UPDATE short_link SET enabled = true WHERE short_code = ?", code);
        doThrow(new IllegalStateException("Redis unavailable")).when(redirectCache).replaceVersion(code);
        assertThatThrownBy(() -> shortLinkService.recoverCacheCoordination(code)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM short_link WHERE short_code = ?", Boolean.class, code)).isTrue();
        // A later committed maintenance change must survive recovery of the earlier attempt.
        jdbcTemplate.update("UPDATE short_link SET enabled = false WHERE short_code = ?", code);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Applied, response lost");
        }).when(redirectCache).replaceVersion(code);
        assertThatThrownBy(() -> shortLinkService.recoverCacheCoordination(code)).isInstanceOf(IllegalStateException.class);
        doCallRealMethod().when(redirectCache).replaceVersion(code);
        shortLinkService.recoverCacheCoordination(code);
        shortLinkService.recoverCacheCoordination(code);
        mockMvc.perform(get("/s/" + code)).andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM short_link WHERE short_code = ?", Boolean.class, code)).isFalse();
        jdbcTemplate.update("UPDATE short_link SET enabled = true WHERE short_code = ?", code);
        shortLinkService.recoverCacheCoordination(code);
        assertRedirect(code, ORIGINAL_URL);
        jdbcTemplate.update("UPDATE short_link SET enabled = false, expires_at = ? WHERE short_code = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), code);
        shortLinkService.recoverCacheCoordination(code);
        mockMvc.perform(get("/s/" + code)).andExpect(status().isGone());
        jdbcTemplate.update("UPDATE short_link SET enabled = true WHERE short_code = ?", code);
        shortLinkService.recoverCacheCoordination(code);
        mockMvc.perform(get("/s/" + code)).andExpect(status().isGone());
        assertThat(mappingCount()).isEqualTo(mappings);
        assertThat(issuanceCount()).isEqualTo(issuances);
    }
    private void assertRedirect(String shortCode, String originalUrl) throws Exception {
        mockMvc.perform(get("/s/" + shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", originalUrl))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private void insertMapping(String shortCode, String originalUrl) {
        jdbcTemplate.update(
                "INSERT INTO short_link (short_code, original_url, created_at, expires_at, enabled) " +
                        "VALUES (?, ?, ?, NULL, TRUE)",
                shortCode,
                originalUrl,
                LocalDateTime.now(ZoneOffset.UTC));
    }

    private void insertExpiringMapping(String shortCode, String originalUrl, LocalDateTime expiresAt) {
        jdbcTemplate.update(
                "INSERT INTO short_link (short_code, original_url, created_at, expires_at, enabled) " +
                        "VALUES (?, ?, ?, ?, TRUE)",
                shortCode,
                originalUrl,
                LocalDateTime.now(ZoneOffset.UTC),
                expiresAt);
    }

    private String cacheKey(String shortCode) {
        return CACHE_KEY_PREFIX + shortCode;
    }

    private long mappingCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_link", Long.class);
    }

    private long issuanceCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_code_issuance", Long.class);
    }

    private ConfigurableApplicationContext independentCreator() {
        return independentInstance(FixedCodeConfiguration.class);
    }

    private ConfigurableApplicationContext independentInstance(Class<?> configuration) {
        return independentInstance(configuration, true);
    }

    private ConfigurableApplicationContext independentInstance(Class<?> configuration, boolean cacheEnabled) {
        return new SpringApplicationBuilder(LinkApplication.class, configuration)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getFirstMappedPort(),
                        "--spring.sql.init.mode=never", "--spring.main.banner-mode=off",
                        "--short-link.redirect-cache.load-wait=5s",
                        "--short-link.redirect-cache.enabled=" + cacheEnabled);
    }

    @Test
    void realRedisTimeoutKeepsAllMySqlOutcomesAndCannotConfirmCreation() throws Exception {
        insertMapping("Live", ORIGINAL_URL);
        insertMapping("Off1", ORIGINAL_URL);
        jdbcTemplate.update("UPDATE short_link SET enabled = false WHERE short_code = 'Off1'");
        insertExpiringMapping("Past", ORIGINAL_URL, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        pauseRedis();
        try {
            assertRedirect("Live", ORIGINAL_URL);
            mockMvc.perform(get("/s/Off1")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("LINK_DISABLED"));
            mockMvc.perform(get("/s/Past")).andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("LINK_EXPIRED"));
            mockMvc.perform(get("/s/None")).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
            mockMvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"originalUrl\":\"https://committed.example/\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("CREATE_CACHE_COORDINATION_UNCONFIRMED"))
                    .andExpect(jsonPath("$.shortCode").isString());
            assertThat(mappingCount()).isEqualTo(4);
        } finally {
            resumeRedis();
        }
        verify(shortLinkMapper).selectById("Live");
        verify(redirectCache, never()).storeIfVersion(eq("Live"), anyString(), any(), any());
        assertRedirect("Live", ORIGINAL_URL);
        assertRedirect("Live", ORIGINAL_URL);
        verify(shortLinkMapper, times(2)).selectById("Live");
    }

    @Test
    void realRedisTimeoutDuringRefillDoesNotChangeTheDatabaseResult() throws Exception {
        insertMapping("Fill", ORIGINAL_URL);
        var query = mockingDetails(shortLinkMapper).getMockCreationSettings().getDefaultAnswer();
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object snapshot = query.answer(invocation);
            if (first.getAndSet(false)) pauseRedis();
            return snapshot;
        }).when(shortLinkMapper).selectById("Fill");
        try {
            assertRedirect("Fill", ORIGINAL_URL);
        } finally {
            resumeRedis();
        }
        verify(redirectCache).storeIfVersion(eq("Fill"), anyString(), eq(RedirectCacheRead.Status.REDIRECT), any());
        // A timed out command may still execute after the server resumes; its effect is not assumed absent.
        assertRedirect("Fill", ORIGINAL_URL);
    }

    @Test
    void realRedisTimeoutDoesNotJoinAnOldMissingLoadAfterCreation() throws Exception {
        String code = "Unkn";
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        var query = mockingDetails(shortLinkMapper).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object snapshot = query.answer(invocation);
            if (first.getAndSet(false)) {
                assertThat(snapshot).isNull();
                snapshotRead.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return snapshot;
        }).when(shortLinkMapper).selectById(code);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Integer> old = executor.submit(() -> mockMvc.perform(get("/s/" + code))
                .andReturn().getResponse().getStatus());
        try {
            assertThat(snapshotRead.await(5, TimeUnit.SECONDS)).isTrue();
            insertMapping(code, ORIGINAL_URL);
            shortLinkService.recoverCacheCoordination(code);
            pauseRedis();
            try {
                assertRedirect(code, ORIGINAL_URL);
                assertThat(old.isDone()).isFalse();
                verify(redirectCache, never()).storeIfVersion(eq(code), anyString(),
                        eq(RedirectCacheRead.Status.REDIRECT), any());
            } finally {
                resumeRedis();
            }
            release.countDown();
            assertThat(old.get(5, TimeUnit.SECONDS)).isEqualTo(404);
            assertRedirect(code, ORIGINAL_URL);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void malformedUnknownAndInvalidTimeValuesCannotRedirectOrReject() throws Exception {
        String code = "Bad2";
        insertMapping(code, ORIGINAL_URL);
        String valid = "{\"schemaVersion\":2,\"generation\":\"00000000-0000-0000-0000-000000000001\","
                + "\"status\":\"REDIRECT\",\"originalUrl\":\"https://wrong.example/\",\"expiresAt\":null}";
        for (String value : List.of("null", "[]", "{broken", "{\"originalUrl\":\"https://wrong.example/\"}",
                valid.replace("\"schemaVersion\":2", "\"schemaVersion\":99"),
                valid.replace("REDIRECT", "UNKNOWN"),
                valid.replace("\"expiresAt\":null", "\"expiresAt\":\"not-a-time\""),
                valid.replace("\"expiresAt\":null", "\"expiresAt\":42"),
                valid.replace("REDIRECT", "NOT_FOUND"))) {
            redisTemplate.opsForValue().set(cacheKey(code), value, Duration.ofMinutes(5));
            assertRedirect(code, ORIGINAL_URL);
            assertThat(redirectCache.find(code).entry().originalUrl()).isEqualTo(ORIGINAL_URL);
        }
        verify(shortLinkMapper, times(9)).selectById(code);
    }

    @Test
    void controlledCleanupRemovesRestoredResultsAndVersionsBeforeDemandLoading() throws Exception {
        String code = "Back";
        mockMvc.perform(get("/s/" + code)).andExpect(status().isNotFound());
        String oldSnapshot = redisTemplate.opsForValue().get(cacheKey(code));
        String oldGeneration = redirectCache.find(code).generation();
        insertMapping(code, ORIGINAL_URL);
        shortLinkService.recoverCacheCoordination(code);
        assertRedirect(code, ORIGINAL_URL);
        // Restore the whole old entry, including its version, as an old snapshot would do.
        redisTemplate.opsForValue().set(cacheKey(code), oldSnapshot, Duration.ofSeconds(30));
        redisTemplate.opsForValue().set("shortlink:redirect:v1:" + code,
                "{\"originalUrl\":\"https://old.example/\",\"expiresAt\":null}");
        redisTemplate.opsForValue().set("other:keep", "unrelated");
        try (ConfigurableApplicationContext disabled = independentInstance(CoalescingConfiguration.class, false)) {
            ShortLinkService service = disabled.getBean(ShortLinkService.class);
            assertThat(service.findOriginalUrl(code)).isEqualTo(ORIGINAL_URL);
            assertThat(service.findOriginalUrl(code)).isEqualTo(ORIGINAL_URL);
            verify(disabled.getBean(ShortLinkMapper.class), times(2)).selectById(code);
            assertThatThrownBy(() -> service.recoverCacheCoordination(code)).isInstanceOf(IllegalStateException.class);
            assertThat(redisTemplate.opsForValue().get(cacheKey(code))).isEqualTo(oldSnapshot);
        }
        // All callers are idle here; production must stop and drain every cache-enabled instance.
        try (var keys = redisTemplate.scan(ScanOptions.scanOptions().match("shortlink:redirect:*").count(100).build())) {
            keys.forEachRemaining(redisTemplate::delete);
        }
        assertThat(redisTemplate.hasKey(cacheKey(code))).isFalse();
        assertThat(redisTemplate.hasKey("shortlink:redirect:v1:" + code)).isFalse();
        assertThat(redisTemplate.opsForValue().get("other:keep")).isEqualTo("unrelated");
        assertThat(redirectCache.storeIfVersion(code, oldGeneration, RedirectCacheRead.Status.NOT_FOUND, null)).isFalse();
        assertRedirect(code, ORIGINAL_URL);
        assertThat(redirectCache.find(code).generation()).isNotEqualTo(oldGeneration);
        clearInvocations(shortLinkMapper);
        assertRedirect(code, ORIGINAL_URL);
        verify(shortLinkMapper, never()).selectById(code);
    }

    @Test
    void differentMissingCodesStillQueryAndAllEntriesHaveFiniteTtl() throws Exception {
        for (String code : List.of("Scan1", "Scan2", "Scan3", "Scan4")) {
            mockMvc.perform(get("/s/" + code)).andExpect(status().isNotFound());
            verify(shortLinkMapper).selectById(code);
            assertThat(redisTemplate.getExpire(cacheKey(code), TimeUnit.MILLISECONDS))
                    .isPositive().isLessThanOrEqualTo(30_000L);
            mockMvc.perform(get("/s/" + code)).andExpect(status().isNotFound());
            verify(shortLinkMapper).selectById(code);
        }
        for (String code : List.of("Hold1", "Hold2")) {
            assertThat(redirectCache.find(code).status()).isEqualTo(RedirectCacheRead.Status.MISS);
            assertThat(redisTemplate.getExpire(cacheKey(code), TimeUnit.MILLISECONDS))
                    .isPositive().isLessThanOrEqualTo(CACHE_TTL_MILLIS);
        }
    }

    private void pauseRedis() throws Exception {
        // WRITE also pauses all Lua scripts, including the cache's read-or-initialize script.
        assertThat(REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "10000", "WRITE").getExitCode()).isZero();
    }

    private void resumeRedis() throws Exception {
        assertThat(REDIS.execInContainer("redis-cli", "CLIENT", "UNPAUSE").getExitCode()).isZero();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(redisTemplate.execute((RedisCallback<String>) connection -> connection.ping())).isEqualTo("PONG"));
    }

    @Test
    void isolatedApplicationInstancesEachCoalesceTheirOwnOverlappingMisses() throws Exception {
        String code = "Coal";
        insertMapping(code, ORIGINAL_URL);
        redirectCache.find(code); // Both instances read the same initialized generation.
        CountDownLatch bothQueries = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        try (ConfigurableApplicationContext first = independentInstance(CoalescingConfiguration.class);
             ConfigurableApplicationContext second = independentInstance(CoalescingConfiguration.class)) {
            ShortLinkService firstService = first.getBean(ShortLinkService.class);
            ShortLinkService secondService = second.getBean(ShortLinkService.class);
            ShortLinkMapper firstMapper = first.getBean(ShortLinkMapper.class);
            ShortLinkMapper secondMapper = second.getBean(ShortLinkMapper.class);
            assertThat(firstService).isNotSameAs(secondService);
            assertThat(firstMapper).isNotSameAs(secondMapper);
            for (ShortLinkMapper mapper : new ShortLinkMapper[]{firstMapper, secondMapper}) {
                var query = mockingDetails(mapper).getMockCreationSettings().getDefaultAnswer();
                doAnswer(invocation -> {
                    Object snapshot = query.answer(invocation);
                    bothQueries.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    return snapshot;
                }).when(mapper).selectById(code);
            }
            var firstLoad = startRedirect(firstService, code, threads);
            var secondLoad = startRedirect(secondService, code, threads);
            assertThat(bothQueries.await(5, TimeUnit.SECONDS)).isTrue();
            var firstWaiter = startRedirect(firstService, code, threads);
            var secondWaiter = startRedirect(secondService, code, threads);
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(1)).untilAsserted(() -> {
                assertThat(threads.get(2).getState()).isEqualTo(Thread.State.TIMED_WAITING);
                assertThat(threads.get(3).getState()).isEqualTo(Thread.State.TIMED_WAITING);
            });
            verify(firstMapper).selectById(code);
            verify(secondMapper).selectById(code);
            release.countDown();
            for (var request : List.of(firstLoad, secondLoad, firstWaiter, secondWaiter)) {
                assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo(ORIGINAL_URL);
            }
            verify(firstMapper).selectById(code);
            verify(secondMapper).selectById(code);
        } finally {
            release.countDown();
            for (Thread thread : threads) {
                thread.join(5000);
                assertThat(thread.isAlive()).isFalse();
            }
        }
    }

    private FutureTask<String> startRedirect(
            ShortLinkService service, String code, List<Thread> threads) {
        var task = new FutureTask<String>(() -> service.findOriginalUrl(code));
        Thread thread = new Thread(task);
        threads.add(thread);
        thread.start();
        return task;
    }

    @TestConfiguration
    static class CoalescingConfiguration {
        @Bean
        @Primary
        ShortLinkMapper countedMapper(
                @Qualifier("shortLinkMapper") ShortLinkMapper delegate) {
            return mock(ShortLinkMapper.class, delegatesTo(delegate));
        }
    }
    @TestConfiguration
    static class FixedCodeConfiguration {
        @Bean
        @Primary
        PermutedShortCodeEncoder fixedEncoder() {
            return new PermutedShortCodeEncoder() {
                @Override
                public String encode(long id) {
                    return "Race";
                }
            };
        }
    }
}
