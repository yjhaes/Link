package com.example.shortlink;



import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "short-link.internal-token=0123456789abcdef0123456789abcdef")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ShortLinkApiTest.ControlledTimeConfiguration.class)
class VisitLogsApiTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired ShortLinkApiTest.ControllableClock clock;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean(name = "statsDataSource")
    com.zaxxer.hikari.HikariDataSource pool;

    @BeforeEach
    void reset() {
        db.update("DELETE FROM short_link_visit_log");
        db.update("DELETE FROM short_link");
        clock.reset(Instant.parse("2026-09-30T16:00:00Z"));
        db.update("INSERT INTO short_link(short_code,original_url,created_at,enabled) VALUES ('Ab12','https://secret.example/path',UTC_TIMESTAMP(3),false)");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder page(String code) {
        return get("/api/internal/links/" + code + "/visits")
                .header("X-Internal-Token", "0123456789abcdef0123456789abcdef");
    }

    private void visit(String code, String utc, String agent) {
        db.update("""
                INSERT INTO short_link_visit_log(event_id,short_code,occurred_at,stat_date,visitor_hash,visitor_key_version,user_agent)
                VALUES(UUID_TO_BIN(UUID()),?,?,DATE(DATE_ADD(?,INTERVAL 8 HOUR)),?,1,?)
                """, code, utc, utc, new byte[32], agent);
    }

    private JsonNode result(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(http.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString());
    }

    @Test
    void sameMillisecondUsesStableDescendingPositionAndMinimalItems() throws Exception {
        visit("Ab12", "2026-09-30 16:00:00.123", "first");
        visit("Ab12", "2026-09-30 16:00:00.123", "second");
        visit("Ab12", "2026-09-30 16:00:00.123", "third");
        var first = result(page("Ab12").param("limit", "2"));
        assertThat(first.path("from").asText()).isEqualTo("2026-09-25");
        assertThat(first.path("to").asText()).isEqualTo("2026-10-01");
        assertThat(first.path("shortCode").asText()).isEqualTo("Ab12");
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("items").size()).isEqualTo(2);
        assertThat(first.at("/items/0/userAgent").asText()).isEqualTo("third");
        assertThat(first.at("/items/1/userAgent").asText()).isEqualTo("second");
        assertThat(first.at("/items/0/occurredAt").asText()).isEqualTo("2026-09-30T16:00:00.123Z");
        assertThat(first.at("/items/0").size()).isEqualTo(4);
        assertThat(first.at("/items/0/peerIpNetwork").isNull()).isTrue();
        assertThat(first.at("/items/0/refererHost").isNull()).isTrue();
        var last = result(page("Ab12").param("limit", "2").param("cursor", first.path("nextCursor").asText()));
        assertThat(last.path("items").size()).isEqualTo(1);
        assertThat(last.at("/items/0/userAgent").asText()).isEqualTo("first");
        assertThat(last.path("hasMore").asBoolean()).isFalse();
        assertThat(last.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void defaultAndMaximumPageSizeAreBoundedAndCaseSensitive() throws Exception {
        db.update("INSERT INTO short_link(short_code,original_url,created_at,expires_at,enabled) VALUES('ab12','https://secret.example/path',UTC_TIMESTAMP(3),'2026-09-01',true)");
        for (int i = 0; i < 101; i++) visit("Ab12", "2026-09-30 16:00:00", "agent" + i);
        visit("ab12", "2026-09-30 16:00:00", null);
        visit("Ab12", "2026-09-01 15:59:59.999", "outside");
        visit("Ab12", "2026-10-01 16:00:00", "future");
        var first = result(page("Ab12"));
        assertThat(first.path("items").size()).isEqualTo(20);
        var maximum = result(page("Ab12").param("limit", "100"));
        assertThat(maximum.path("items").size()).isEqualTo(100);
        assertThat(maximum.path("hasMore").asBoolean()).isTrue();
        var last = result(page("Ab12").param("limit", "100").param("cursor", maximum.path("nextCursor").asText()));
        assertThat(last.path("items").size()).isEqualTo(1);
        assertThat(last.path("hasMore").asBoolean()).isFalse();
        var lower = result(page("ab12"));
        assertThat(lower.path("items").size()).isEqualTo(1);
        assertThat(lower.at("/items/0/userAgent").isNull()).isTrue();
        assertThat(result(page("Ab12").param("from", "2026-09-02").param("to", "2026-09-02")).path("items")).isEmpty();
        db.update("UPDATE short_link_visit_log SET peer_ip_network='192.0.2.0/24',referer_host='example.com',user_agent=? WHERE short_code='ab12'", "x".repeat(512));
        var metadata = result(page("ab12"));
        assertThat(metadata.at("/items/0/peerIpNetwork").asText()).isEqualTo("192.0.2.0/24");
        assertThat(metadata.at("/items/0/refererHost").asText()).isEqualTo("example.com");
        assertThat(metadata.at("/items/0/userAgent").asText()).hasSize(512);
        assertThat(metadata.toString()).doesNotContain("secret.example", "visitor_hash", "visitorHash", "event_id", "Cookie");
        var decoded = new String(java.util.Base64.getUrlDecoder().decode(first.path("nextCursor").asText()), java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(decoded.split("\\|", -1)).hasSize(6);
        assertThat(decoded).startsWith("1|Ab12|2026-09-25|2026-10-01|");
    }

    @Test
    void deletedCursorRowAndConcurrentWritesOrCleanupChangeLaterPagesWithoutSnapshotGuarantee() throws Exception {
        visit("Ab12", "2026-09-30 16:00:00", "old");
        visit("Ab12", "2026-09-30 16:00:01", "removed");
        visit("Ab12", "2026-09-30 16:00:02", "cursor-row");
        visit("Ab12", "2026-09-30 16:00:03", "top");
        var first = result(page("Ab12").param("limit", "2"));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                db.update("DELETE FROM short_link_visit_log WHERE user_agent IN ('cursor-row','removed')");
                visit("Ab12", "2026-09-30 16:00:04", "newer");
                visit("Ab12", "2026-09-30 16:00:01.500", "late");
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        var second = result(page("Ab12").param("limit", "2").param("cursor", first.path("nextCursor").asText()));
        assertThat(second.path("items").size()).isEqualTo(2);
        assertThat(second.at("/items/0/userAgent").asText()).isEqualTo("late");
        assertThat(second.at("/items/1/userAgent").asText()).isEqualTo("old");
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(result(page("Ab12")).at("/items/0/userAgent").asText()).isEqualTo("newer");
    }

    @Test
    void pageParametersCursorStructureAndBindingAreStrictAndNeverPreserveExpiredWindow() throws Exception {
        for (String limit : new String[]{"", "0", "101", "-1", "1.5", "abc", "2147483648", " 2", "+2"}) {
            http.perform(page("Ab12").param("limit", limit)).andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        http.perform(page("Ab12").param("limit", "1", "2")).andExpect(status().isBadRequest());
        http.perform(page("Ab12").param("cursor", "a", "b")).andExpect(status().isBadRequest());
        visit("Ab12", "2026-09-02 16:00:00", "oldest");
        visit("Ab12", "2026-09-30 16:00:00", "latest");
        var first = result(page("Ab12").param("limit", "1").param("from", "2026-09-02").param("to", "2026-10-01"));
        String cursor = first.path("nextCursor").asText();
        http.perform(page("ab12").param("from", "2026-09-02").param("to", "2026-10-01").param("cursor", cursor)).andExpect(status().isBadRequest());
        http.perform(page("Ab12").param("cursor", cursor)).andExpect(status().isBadRequest());
        String valid = new String(java.util.Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.US_ASCII);
        for (String value : new String[]{"", "!", cursor + "=", "a".repeat(201),
                encoded(valid.replace("1|Ab12", "2|Ab12")), encoded(valid + "|extra"),
                encoded("1|Ab12|2026-09-02|2026-10-01|0|1"),
                encoded("1|Ab12|2026-09-02|2026-10-01|1790784000000|0"),
                encoded("1|Ab12|2026-09-02|2026-10-01|1790784000000|9223372036854775808")}) {
            http.perform(page("Ab12").param("from", "2026-09-02").param("to", "2026-10-01").param("cursor", value))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        result(page("Ab12").param("from", "2026-09-02").param("to", "2026-10-01").param("cursor", cursor));
        clock.reset(Instant.parse("2026-10-01T16:00:00Z"));
        http.perform(page("Ab12").param("from", "2026-09-02").param("to", "2026-10-01").param("cursor", cursor))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
    }

    private static String encoded(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void pageDatesReuseStrictShanghaiWindowIncludingUtcBoundaries() throws Exception {
        for (String[] dates : new String[][]{{"2026-09-30", null}, {null, "2026-10-01"},
                {"2026-09-31", "2026-10-01"}, {"2026-9-30", "2026-10-01"},
                {"2026-10-01", "2026-09-30"}, {"2026-10-01", "2026-10-02"}, {"2026-09-01", "2026-10-01"}}) {
            var request = page("Ab12");
            if (dates[0] != null) request.param("from", dates[0]);
            if (dates[1] != null) request.param("to", dates[1]);
            http.perform(request).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        }
        for (String key : new String[]{"from", "to"}) {
            http.perform(page("Ab12").param("from", "2026-09-02").param("to", "2026-10-01").param(key, "2026-09-02"))
                    .andExpect(status().isBadRequest());
        }
        visit("Ab12", "2026-09-01 15:59:59.999", "before");
        visit("Ab12", "2026-09-01 16:00:00", "start");
        visit("Ab12", "2026-10-01 15:59:59.999", "end");
        visit("Ab12", "2026-10-01 16:00:00", "after");
        var page = result(page("Ab12").param("from", "2026-09-02").param("to", "2026-10-01"));
        assertThat(page.path("items").size()).isEqualTo(2);
        assertThat(page.at("/items/0/userAgent").asText()).isEqualTo("end");
        assertThat(page.at("/items/1/userAgent").asText()).isEqualTo("start");
    }

    @Test
    void realHttpGetAndHeadRequireTokenBeforeValidationAndDoNotIssueCookies() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        for (String method : new String[]{"GET", "HEAD"}) {
            for (String token : new String[]{"", "bad", "0123456789abcdef0123456789abcdef"}) {
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port
                        + "/api/internal/links/Ab12/visits?limit=bad&cursor=bad"))
                        .method(method, java.net.http.HttpRequest.BodyPublishers.noBody());
                if (!token.isEmpty()) request.header("X-Internal-Token", token);
                var response = client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(token.length() == 32 ? 400 : 401);
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
                if (method.equals("HEAD")) assertThat(response.body()).isEmpty();
            }
        }
        http.perform(page("Ab12").header("X-Internal-Token", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isUnauthorized());
        for (String code : new String[]{"bad!", "None"}) http.perform(page(code)).andExpect(status().isNotFound());
        var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/internal/links/Ab12/visits"))
                .header("X-Internal-Token", "0123456789abcdef0123456789abcdef").GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("items")).isEmpty();
    }

    @Test
    void pageSharesAggregationCapacityAndDatabaseErrorPolicyAndReleasesPermit() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(pool).getConnection();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> result(page("Ab12")));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            for (String path : new String[]{"visits", "stats"}) {
                http.perform(get("/api/internal/links/Ab12/" + path).header("X-Internal-Token", "0123456789abcdef0123456789abcdef"))
                        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STATS_BUSY"))
                        .andExpect(header().string("Cache-Control", "no-store"));
            }
            release.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { release.countDown(); executor.shutdownNow(); org.mockito.Mockito.doCallRealMethod().when(pool).getConnection(); }
        for (var failure : new java.sql.SQLException[]{new java.sql.SQLTimeoutException("private"), new java.sql.SQLException("private", "42000")}) {
            org.mockito.Mockito.doThrow(failure).when(pool).getConnection();
            http.perform(page("Ab12")).andExpect(status().is(failure instanceof java.sql.SQLTimeoutException ? 503 : 500))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
            org.mockito.Mockito.doCallRealMethod().when(pool).getConnection();
            result(page("Ab12"));
        }
    }
}
