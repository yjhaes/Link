package com.example.shortlink;



import com.example.shortlink.cache.RedirectCache;
import com.example.shortlink.persistence.ShortLinkMapper;
import com.example.shortlink.shortcode.ShortCodeIdIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(InternalManagementApiTest.WebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = "short-link.base-url=http://localhost")
class InternalManagementDisabledApiTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.example.shortlink.ratelimit.RateLimiter limiter;
    @Autowired WebApplicationContext context;
    @Autowired ShortLinkMapper mapper;
    @Autowired RedirectCache cache;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ShortCodeIdIssuer issuer;
    @Autowired com.example.shortlink.stats.query.MySqlVisitStatsQuery query;

    @Test
    void absentTokenClosesStatisticsGetAndHeadBeforeValidationOrQuery() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        for (var method : new org.springframework.http.HttpMethod[]{org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.HEAD}) {
            for (String endpoint : new String[]{"stats", "visits"}) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(method, "/api/internal/links/bad!/" + endpoint)
                            .header("X-Internal-Token", InternalManagementApiTest.TOKEN).param("from", "invalid"))
                    .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
            }
        }
        verifyNoInteractions(limiter, query, mapper, cache, transactions, issuer);
    }

    @Test
    void absentTokenConfigurationClosesStateApiEvenWithMalformedBodiesAndAnOfferedToken() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        for (String body : new String[]{"{", "{\"enabled\":false}"}) {
            mvc.perform(put("/api/links/Ab12/enabled").header("X-Internal-Token", InternalManagementApiTest.TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        verifyNoInteractions(limiter, mapper, cache, transactions, issuer);
    }
}
