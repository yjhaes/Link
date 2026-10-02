package com.example.shortlink;

import com.example.shortlink.cache.RedirectCache;
import com.example.shortlink.cache.RedirectCacheProperties;
import com.example.shortlink.persistence.MySqlShortLinkWriter;
import com.example.shortlink.persistence.ShortLinkMapper;
import com.example.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.service.RedirectService;
import com.example.shortlink.service.ShortLinkCreationService;
import com.example.shortlink.service.ShortLinkStateService;
import com.example.shortlink.shortcode.PermutedShortCodeEncoder;
import com.example.shortlink.shortcode.ShortCodeIdIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.shortlink.api.InternalManagement;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import jakarta.servlet.http.Cookie;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(InternalManagementApiTest.WebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = {"short-link.base-url=http://localhost",
        "short-link.internal-token=0123456789abcdef0123456789abcdef"})
class InternalManagementApiTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    @Autowired WebApplicationContext context;
    @Autowired ShortLinkMapper mapper;
    @Autowired RedirectCache cache;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ShortCodeIdIssuer issuer;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(mapper, cache, transactions, issuer);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void missingTokenRejectsMalformedBodyBeforeAnyBusinessAccess() throws Exception {
        mvc.perform(put("/api/links/Ab12/enabled").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTERNAL_UNAUTHORIZED"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(mapper, cache, transactions, issuer);
    }

    @Test
    void incorrectRepeatedAndWhitespaceModifiedTokensCannotEnterStateBusiness() throws Exception {
        for (String[] values : new String[][]{{"wrong"}, {TOKEN, TOKEN}, {"wrong", TOKEN},
                {TOKEN, "wrong"}, {" " + TOKEN}, {TOKEN + " "}, {TOKEN + "," + TOKEN}, {""}}) {
            mvc.perform(put("/api/links/Ab12/enabled").header("X-Internal-Token", (Object[]) values)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INTERNAL_UNAUTHORIZED"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(TOKEN))));
        }
        verifyNoInteractions(mapper, cache, transactions, issuer);
    }

    @Test
    void authPrecedesBodyContentTypeAndShortCodeValidation() throws Exception {
        for (String body : new String[]{"{", "{}", "{\"enabled\":null}", "{\"enabled\":\"false\"}"}) {
            mvc.perform(put("/api/links/!/enabled").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(put("/api/links/Ab12/enabled").contentType(MediaType.TEXT_PLAIN).content("bad"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper, cache, transactions, issuer);
    }

    @Test
    void queryAndCookieTokensDoNotAuthorizeManagement() throws Exception {
        mvc.perform(put("/api/links/Ab12/enabled").param("X-Internal-Token", TOKEN)
                        .cookie(new Cookie("X-Internal-Token", TOKEN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper, cache, transactions, issuer);
    }

    @Test
    void authorizedRequestsStillValidateBodyWithoutBusinessSideEffects() throws Exception {
        mvc.perform(put("/api/links/Ab12/enabled").header("x-internal-token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(mapper, cache, transactions, issuer);
    }

    @Test
    void authorizedStateChangeCommitsAndCoordinatesAsBefore() throws Exception {
        ShortLinkEntity mapping = new ShortLinkEntity();
        mapping.setShortCode("Ab12");
        mapping.setEnabled(true);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.selectForUpdate("Ab12")).thenReturn(mapping);
        when(mapper.updateEnabled("Ab12", false)).thenReturn(1);
        mvc.perform(put("/api/links/Ab12/enabled").header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(transactions).commit(any());
        verify(mapper).updateEnabled("Ab12", false);
        verify(cache).replaceVersion("Ab12");
    }

    @Test
    void publicCreationAndRedirectDoNotRequireAnInternalToken() throws Exception {
        when(issuer.issue()).thenReturn(1L);
        when(mapper.insert(any(ShortLinkEntity.class))).thenReturn(1);
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalUrl\":\"https://example.com/\"}"))
                .andExpect(status().isCreated());
        ShortLinkEntity mapping = new ShortLinkEntity();
        mapping.setEnabled(true);
        mapping.setOriginalUrl("https://example.com/");
        when(mapper.selectById("Ab12")).thenReturn(mapping);
        mvc.perform(get("/s/Ab12"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/"));
    }

    @Test
    void sharedBoundaryAlsoProtectsAnnotatedGetAndImplicitHeadHandlers() throws Exception {
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[]{
                get("/test/internal"), head("/test/internal")}) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get("/test/internal").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(mapper, cache, transactions, issuer);
    }

    @RestController
    @InternalManagement
    static class FutureManagementController {
        @GetMapping("/test/internal")
        ResponseEntity<String> read() { return ResponseEntity.ok("protected"); }
    }

    @Configuration
    @EnableWebMvc
    @ComponentScan("com.example.shortlink.api")
    @Import({ShortLinkCreationService.class, ShortLinkStateService.class, RedirectService.class,
            MySqlShortLinkWriter.class, PermutedShortCodeEncoder.class, FutureManagementController.class})
    static class WebConfiguration {
        @Bean com.example.shortlink.stats.MySqlVisitStatsQuery visitStatsQuery() {
            return mock(com.example.shortlink.stats.MySqlVisitStatsQuery.class);
        }
        @Bean com.example.shortlink.stats.VisitWriteObservations visitWriteObservations() {
            return new com.example.shortlink.stats.VisitWriteObservations(mock(javax.sql.DataSource.class));
        }
        @Bean com.example.shortlink.stats.VisitStatsProperties visitStatsProperties() {
            return new com.example.shortlink.stats.VisitStatsProperties(
                    false, null, null, null, null, null, null, null, null);
        }
        @Bean com.example.shortlink.stats.VisitRecorder visitRecorder() {
            return mock(com.example.shortlink.stats.VisitRecorder.class);
        }
        @Bean ShortLinkMapper mapper() { return mock(ShortLinkMapper.class); }
        @Bean RedirectCache cache() { return mock(RedirectCache.class); }
        @Bean PlatformTransactionManager transactions() { return mock(PlatformTransactionManager.class); }
        @Bean ShortCodeIdIssuer issuer() { return mock(ShortCodeIdIssuer.class); }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC); }
        @Bean com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
        @Bean RedirectCacheProperties cacheProperties() {
            return new RedirectCacheProperties(true, "5m", "30s", "5m", "15s", "200ms");
        }
    }
}
