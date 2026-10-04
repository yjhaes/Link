package com.example.shortlink;

import com.example.shortlink.cache.RedirectCache;
import com.example.shortlink.persistence.ShortLinkMapper;
import com.example.shortlink.ratelimit.RateLimiter;
import com.example.shortlink.stats.VisitRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig({InternalManagementApiTest.WebConfiguration.class, RedirectRateLimitApiTest.CollectionConfiguration.class})
@WebAppConfiguration
@TestPropertySource(properties = "short-link.base-url=http://localhost")
class RedirectRateLimitApiTest {
    @Autowired WebApplicationContext context;
    @Autowired ShortLinkMapper mapper;
    @Autowired RedirectCache cache;
    @Autowired VisitRecorder recorder;
    @MockitoBean RateLimiter limiter;
    MockMvc mvc;
    @BeforeEach void setup() {
        reset(mapper, cache, limiter, recorder);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }
    @org.springframework.context.annotation.Configuration
    static class CollectionConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        com.example.shortlink.stats.config.VisitStatsProperties enabledStats() {
            return new com.example.shortlink.stats.config.VisitStatsProperties(true,
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 1, null, null, null, null, null, null);
        }
    }
    @Test void unavailableGetCollectsButHeadNeverCollects() throws Exception {
        when(limiter.admitRedirect(anyString())).thenReturn(RateLimiter.Decision.unavailable());
        var mapping = new com.example.shortlink.persistence.ShortLinkEntity();
        mapping.setEnabled(true); mapping.setOriginalUrl("https://example.com/");
        when(mapper.selectById("Ab12")).thenReturn(mapping);
        mvc.perform(get("/s/Ab12")).andExpect(status().isFound()).andExpect(header().exists("Set-Cookie"));
        verify(recorder).record(any(com.example.shortlink.stats.VisitEvent.class));
        clearInvocations(recorder);
        mvc.perform(head("/s/Ab12")).andExpect(status().isFound()).andExpect(content().string(""))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(recorder);
    }
    @Test void exhaustedQueryBudgetReturnsDistinct503WithoutCookieOrEvent() throws Exception {
        when(limiter.admitRedirect(anyString())).thenReturn(RateLimiter.Decision.allowed());
        var entered = new java.util.concurrent.CountDownLatch(4);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(mapper.selectById(anyString())).thenAnswer(call -> {
            entered.countDown(); org.assertj.core.api.Assertions.assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); return null;
        });
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        var requests = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (String code : java.util.List.of("Ab12", "Cd34", "Ef56", "Gh78")) requests.add(executor.submit(() -> mvc.perform(get("/s/" + code))));
            org.assertj.core.api.Assertions.assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            mvc.perform(get("/s/Ij90")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("REDIRECT_LOAD_BUSY"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
            mvc.perform(head("/s/Ij90")).andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
            verify(mapper, never()).selectById("Ij90");
            verifyNoInteractions(recorder);
            release.countDown();
            for (var result : requests) result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void rejectedGetAndHeadPrecedeCacheAndNeverCollectVisits() throws Exception {
        when(limiter.admitRedirect(anyString())).thenReturn(RateLimiter.Decision.rejected(1201));
        mvc.perform(get("/s/Ab12").header("X-Forwarded-For", "1.2.3.4"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "2"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(head("/s/Cd34").header("Forwarded", "for=9.8.7.6"))
                .andExpect(status().isTooManyRequests()).andExpect(content().string(""))
                .andExpect(header().string("Retry-After", "2"));
        verify(limiter, times(2)).admitRedirect("127.0.0.1");
        verifyNoInteractions(mapper, cache, recorder);
    }
    @Test void invalidCodePrecedesAdmissionAndAnyDependency() throws Exception {
        for (String code : new String[]{"abc", "Ab-c", "123456789"}) {
            mvc.perform(get("/s/" + code)).andExpect(status().isNotFound());
        }
        verifyNoInteractions(limiter, mapper, cache, recorder);
    }
    @Test void unavailableAdmissionStillReachesExistingRedirectResult() throws Exception {
        when(limiter.admitRedirect(anyString())).thenReturn(RateLimiter.Decision.unavailable());
        mvc.perform(get("/s/Ab12")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
        verify(mapper).selectById("Ab12");
        verifyNoInteractions(recorder);
    }
}
