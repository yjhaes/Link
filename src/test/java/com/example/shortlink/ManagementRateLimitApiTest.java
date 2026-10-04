package com.example.shortlink;

import com.example.shortlink.ratelimit.RateLimiter;
import com.example.shortlink.persistence.ShortLinkMapper;
import com.example.shortlink.cache.RedirectCache;
import com.example.shortlink.shortcode.ShortCodeIdIssuer;
import com.example.shortlink.stats.query.MySqlVisitStatsQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(InternalManagementApiTest.WebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = {"short-link.base-url=http://localhost", "short-link.internal-token=0123456789abcdef0123456789abcdef"})
class ManagementRateLimitApiTest {
    @Autowired WebApplicationContext context;
    @Autowired ShortLinkMapper mapper;
    @Autowired RedirectCache cache;
    @Autowired ShortCodeIdIssuer issuer;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MySqlVisitStatsQuery query;
    @MockitoBean RateLimiter limiter;
    MockMvc mvc;
    @BeforeEach void setup() {
        reset(mapper, cache, issuer, transactions, query, limiter);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }
    @Test void authorizedExhaustionPrecedesMalformedBodyAndCodeValidation() throws Exception {
        // Admission rejects before MVC can parse the malformed authorized request.
        when(limiter.admitManagementWrite()).thenReturn(RateLimiter.Decision.rejected(1201));
        mvc.perform(put("/api/links/!/enabled").header("X-Internal-Token", InternalManagementApiTest.TOKEN)
                        .contentType("application/json").content("{"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "2"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(mapper, cache, issuer, transactions, query);
    }
    @Test void unauthorizedRequestsNeverAskAdmissionOrTouchDependencies() throws Exception {
        mvc.perform(put("/api/links/!/enabled").contentType("application/json").content("{")).andExpect(status().isUnauthorized());
        for (String[] headers : new String[][]{{""}, {"wrong"}, {InternalManagementApiTest.TOKEN, InternalManagementApiTest.TOKEN}}) {
            mvc.perform(put("/api/links/!/enabled").header("X-Internal-Token", (Object[]) headers)
                    .contentType("application/json").content("{"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/internal/links/!/stats").header("X-Internal-Token", (Object[]) headers).param("from", "bad"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(head("/api/internal/links/!/visits").header("X-Internal-Token", (Object[]) headers))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(limiter, mapper, cache, issuer, transactions, query);
    }
    @Test void unavailableWriteOrQueryRejectsBeforeParsingAndNeverClaimsCommitted() throws Exception {
        when(limiter.admitManagementWrite()).thenReturn(RateLimiter.Decision.unavailable());
        when(limiter.admitManagementQuery()).thenReturn(RateLimiter.Decision.unavailable());
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                put("/api/links/!/enabled").contentType("application/json").content("{"),
                get("/api/internal/links/!/stats").param("from", "bad"), get("/api/internal/links/!/visits").param("limit", "bad")}) {
            mvc.perform(request.header("X-Internal-Token", InternalManagementApiTest.TOKEN))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().doesNotExist("Retry-After")).andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(jsonPath("$.code").value("RATE_LIMIT_UNAVAILABLE"))
                    .andExpect(jsonPath("$.shortCode").doesNotExist());
        }
        verifyNoInteractions(mapper, cache, issuer, transactions, query);
    }
    @Test void bothQueriesAndHeadUseTheSameAdmissionAndHeadRejectionHasNoBody() throws Exception {
        when(limiter.admitManagementQuery()).thenReturn(RateLimiter.Decision.rejected(1));
        for (String endpoint : new String[]{"stats", "visits"}) {
            mvc.perform(get("/api/internal/links/!/" + endpoint).header("X-Internal-Token", InternalManagementApiTest.TOKEN))
                    .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
            mvc.perform(head("/api/internal/links/!/" + endpoint).header("X-Internal-Token", InternalManagementApiTest.TOKEN))
                    .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"))
                    .andExpect(content().string("")).andExpect(header().doesNotExist("Set-Cookie"));
        }
        when(limiter.admitManagementQuery()).thenReturn(RateLimiter.Decision.unavailable());
        mvc.perform(head("/api/internal/links/Ab12/stats").header("X-Internal-Token", InternalManagementApiTest.TOKEN))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(""))
                .andExpect(header().doesNotExist("Retry-After"));
        verify(limiter, times(5)).admitManagementQuery();
        verifyNoInteractions(mapper, cache, issuer, transactions, query);
    }
    @Test void admittedInvalidRequestConsumesAdmissionAndUnrelatedManagementHandlerDoesNot() throws Exception {
        when(limiter.admitManagementWrite()).thenReturn(RateLimiter.Decision.allowed(), RateLimiter.Decision.rejected(1));
        mvc.perform(put("/api/links/Ab12/enabled").header("X-Internal-Token", InternalManagementApiTest.TOKEN)
                .contentType("application/json").content("{")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/links/Ab12/enabled").header("X-Internal-Token", InternalManagementApiTest.TOKEN)
                .contentType("application/json").content("{")).andExpect(status().isTooManyRequests());
        mvc.perform(get("/test/internal").header("X-Internal-Token", InternalManagementApiTest.TOKEN))
                .andExpect(status().isOk());
        verify(limiter, times(2)).admitManagementWrite();
        verifyNoMoreInteractions(limiter);
        verifyNoInteractions(mapper, cache, issuer, transactions, query);
    }
    @Test void allowedQueriesKeepBusyAndTimeoutDistinctFromAdmissionUnavailability() throws Exception {
        when(limiter.admitManagementQuery()).thenReturn(RateLimiter.Decision.allowed());
        when(query.query(anyString(), any())).thenThrow(new com.example.shortlink.stats.query.StatsQueryException(
                com.example.shortlink.stats.query.StatsQueryException.Reason.BUSY));
        mvc.perform(get("/api/internal/links/Ab12/stats").header("X-Internal-Token", InternalManagementApiTest.TOKEN))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STATS_BUSY"));
        when(query.page(anyString(), any(), anyInt(), any())).thenThrow(new com.example.shortlink.stats.query.StatsQueryException(
                com.example.shortlink.stats.query.StatsQueryException.Reason.TIMEOUT));
        mvc.perform(get("/api/internal/links/Ab12/visits").header("X-Internal-Token", InternalManagementApiTest.TOKEN))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STATS_QUERY_TIMEOUT"));
    }    @Test void admittedStateOperationsKeepConflictExpiryAndCommittedCoordinationContracts() throws Exception {
        when(limiter.admitManagementWrite()).thenReturn(RateLimiter.Decision.allowed());
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new org.springframework.transaction.support.SimpleTransactionStatus());
        var mapping = new com.example.shortlink.persistence.ShortLinkEntity();
        mapping.setEnabled(false);
        when(mapper.selectForUpdate("Ab12")).thenReturn(mapping);
        mvc.perform(put("/api/links/Ab12/enabled").header("X-Internal-Token", InternalManagementApiTest.TOKEN)
                .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LINK_ALREADY_DISABLED"));
        mapping.setExpiresAt(java.time.LocalDateTime.parse("2026-09-30T23:59:59"));
        mvc.perform(put("/api/links/Ab12/enabled").header("X-Internal-Token", InternalManagementApiTest.TOKEN)
                .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("LINK_EXPIRED"));
        verify(mapper, never()).updateEnabled(anyString(), anyBoolean());
        mapping.setExpiresAt(null); mapping.setEnabled(true);
        when(mapper.updateEnabled("Ab12", false)).thenReturn(1);
        doThrow(new IllegalStateException("cache unavailable")).when(cache).replaceVersion("Ab12");
        mvc.perform(put("/api/links/Ab12/enabled").header("X-Internal-Token", InternalManagementApiTest.TOKEN)
                .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LINK_STATE_CACHE_COORDINATION_UNCONFIRMED"))
                .andExpect(jsonPath("$.shortCode").value("Ab12"));
        verify(transactions).commit(any());
        verify(mapper).updateEnabled("Ab12", false);
    }}
