package com.example.shortlink;

import com.example.shortlink.observability.RequestObservationFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class RequestContextFilterTest {
    @Test void requestContextIsServerGeneratedAndClearedEvenWhenDownstreamThrows() throws Exception {
        var registry=new SimpleMeterRegistry();
        try {
            var filter=new RequestObservationFilter(registry);
            var request=new MockHttpServletRequest("GET","/s/Ab12");
            request.addHeader("X-Request-ID","client-canary");
            var response=new MockHttpServletResponse();
            try {
                filter.doFilter(request,response,(incoming,outgoing)->{
                    assertThat(MDC.get("requestId")).matches("[0-9a-f-]{36}").isEqualTo(response.getHeader("X-Request-ID"));
                    throw new jakarta.servlet.ServletException("controlled failure");
                });
            } catch(jakarta.servlet.ServletException expected) { assertThat(expected).hasMessage("controlled failure"); }
            assertThat(MDC.get("requestId")).isNull();
            assertThat(registry.get("shortlink.http.requests").tag("result", "failed").timer().count()).isEqualTo(1);
            MDC.put("requestId","outer-context");
            try {
                filter.doFilter(new MockHttpServletRequest("GET","/"),new MockHttpServletResponse(),(incoming,outgoing)->assertThat(MDC.get("requestId")).isNotEqualTo("outer-context"));
                assertThat(MDC.get("requestId")).isEqualTo("outer-context");
            } finally { MDC.remove("requestId"); }
        } finally { registry.close(); }
    }
}
