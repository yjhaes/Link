package com.example.shortlink.api;


import com.example.shortlink.service.RedirectDecision;
import com.example.shortlink.stats.VisitRecorder;
import com.example.shortlink.stats.VisitStatsProperties;
import com.example.shortlink.stats.VisitWriteObservations;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import javax.crypto.Mac;
import javax.sql.DataSource;
import java.security.SecureRandom;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VisitCollectionFailureTest {
    private final VisitStatsProperties properties = new VisitStatsProperties(true,
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 1, null, null, null, null, null, null);

    @Test
    void metadataProcessingFailureSkipsTheEventAndKeepsAValidIndependentCookie() {
        var recorder = mock(VisitRecorder.class);
        var observations = new VisitWriteObservations(mock(DataSource.class));
        var collection = new VisitCollection(properties, recorder, "http://short.local", observations);
        var request = spy(new MockHttpServletRequest("GET", "/s/Ab12"));
        doThrow(new IllegalArgumentException("private metadata")).when(request).getRemoteAddr();
        assertThat(collection.collect("Ab12", new RedirectDecision("https://example.com/", Instant.EPOCH), request))
                .contains("sl_visitor=");
        verifyNoInteractions(recorder);
        assertThat(observations.snapshot().categories().get(VisitWriteObservations.Category.COLLECTION)).isEqualTo(1);
    }

    @Test
    void randomFailureSkipsIdentityAndNeverUsesAPublicFallback() {
        try (var random = mockConstruction(SecureRandom.class, (mock, context) ->
                doThrow(new IllegalStateException("private random failure")).when(mock).nextBytes(any()))) {
            var recorder = mock(VisitRecorder.class);
            var observations = new VisitWriteObservations(mock(DataSource.class));
            var collection = new VisitCollection(properties, recorder, "http://short.local", observations);
            var request = new MockHttpServletRequest("GET", "/s/Ab12");
            assertThat(collection.collect("Ab12", new RedirectDecision("https://example.com/", Instant.EPOCH), request)).isNull();
            verifyNoInteractions(recorder);
            assertThat(observations.snapshot().categories().get(VisitWriteObservations.Category.COLLECTION)).isEqualTo(1);
        }
    }

    @Test
    void digestFailureSkipsTheEventAndCookieWithoutExposingOriginalInput() throws Exception {
        try (var mac = mockStatic(Mac.class)) {
            mac.when(() -> Mac.getInstance("HmacSHA256")).thenThrow(new java.security.NoSuchAlgorithmException("private"));
            var recorder = mock(VisitRecorder.class);
            var observations = new VisitWriteObservations(mock(DataSource.class));
            var collection = new VisitCollection(properties, recorder, "http://short.local", observations);
            var request = new MockHttpServletRequest("GET", "/s/Ab12");
            request.addHeader("Cookie", "sl_visitor=AAAAAAAAAAAAAAAAAAAAAA");
            assertThat(collection.collect("Ab12", new RedirectDecision("https://example.com/", Instant.EPOCH), request)).isNull();
            verifyNoInteractions(recorder);
        }
    }
}
