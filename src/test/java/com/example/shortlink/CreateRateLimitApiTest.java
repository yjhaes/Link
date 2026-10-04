package com.example.shortlink;

import com.example.shortlink.cache.RedirectCache;
import com.example.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.persistence.ShortLinkMapper;
import com.example.shortlink.ratelimit.RateLimiter;
import com.example.shortlink.shortcode.ShortCodeIdIssuer;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(InternalManagementApiTest.WebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = "short-link.base-url=http://localhost")
class CreateRateLimitApiTest {
    @Autowired WebApplicationContext context;
    @Autowired ShortLinkMapper mapper;
    @Autowired RedirectCache cache;
    @Autowired ShortCodeIdIssuer issuer;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean RateLimiter limiter;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(mapper, cache, issuer, transactions, limiter);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void rejectionPrecedesMalformedBodyAndHasNoBusinessSideEffects() throws Exception {
        when(limiter.admitCreate("127.0.0.1")).thenReturn(RateLimiter.Decision.rejected(1201));
        mvc.perform(post("/api/links").contentType("application/json").content("{")
                        .header("X-Forwarded-For", "1.2.3.4").header("Forwarded", "for=9.8.7.6"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "2"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(limiter).admitCreate("127.0.0.1");
        verifyNoInteractions(mapper, issuer, cache, transactions);
    }

    @Test
    void unavailableRejectsBeforeParsingWithoutClaimingSaved() throws Exception {
        when(limiter.admitCreate(anyString())).thenReturn(RateLimiter.Decision.unavailable());
        mvc.perform(post("/api/links").contentType("application/json").content("{"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.shortCode").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(mapper, issuer, cache, transactions);
    }

    @Test
    void allowedRequestsStillCreatePermanentAndLimitedMappings() throws Exception {
        when(limiter.admitCreate(anyString())).thenReturn(RateLimiter.Decision.allowed());
        when(issuer.issue()).thenReturn(1L, 2L);
        when(mapper.insert(any(ShortLinkEntity.class))).thenReturn(1);
        mvc.perform(post("/api/links").contentType("application/json").content("{\"originalUrl\":\"https://example.com/\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.expiresAt").isEmpty());
        mvc.perform(post("/api/links").contentType("application/json").content("{\"originalUrl\":\"https://example.com/\",\"validMinutes\":60}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.expiresAt").isNotEmpty());
        verify(mapper, times(2)).insert(any(ShortLinkEntity.class));
        verify(issuer, times(2)).issue();
    }

    @Test
    void allowedInvalidBodyConsumesAdmissionAndOtherRoutesDoNotUseCreateLimiter() throws Exception {
        when(limiter.admitCreate(anyString())).thenReturn(RateLimiter.Decision.allowed(), RateLimiter.Decision.rejected(1));
        mvc.perform(post("/api/links").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/links").contentType("application/json").content("{"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(get("/test/internal")).andExpect(status().isNotFound());
        mvc.perform(put("/api/links/Ab12/enabled").contentType("application/json").content("{"))
                .andExpect(status().isNotFound());
        verify(limiter, times(2)).admitCreate("127.0.0.1");
        verifyNoInteractions(mapper, issuer, cache, transactions);
    }
}
