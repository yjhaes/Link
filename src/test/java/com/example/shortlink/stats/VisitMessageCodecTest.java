package com.example.shortlink.stats;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class VisitMessageCodecTest {
    @Test void frozenEventRoundTripsThroughOnlyTheTenAllowedFields() {
        var event = new VisitEvent(UUID.fromString("12345678-1234-1234-1234-123456789abc"), "Ab12",
                Instant.parse("2026-09-30T16:00:00.123Z"), LocalDate.of(2026,10,1), new byte[32], 1,
                "192.168.1.0/24", "agent", "example.com");
        var codec = new VisitMessageCodec();
        var bytes = codec.encode(event);
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).contains("\"schemaVersion\":1", "2026-09-30T16:00:00.123Z")
                .doesNotContain("Cookie", "originalUrl", "javaType");
        var decoded = codec.decode(bytes);
        assertThat(decoded.eventId()).isEqualTo(event.eventId());
        assertThat(decoded.visitorHash()).containsExactly(new byte[32]);
        assertThat(decoded.statDate()).isEqualTo(LocalDate.of(2026,10,1));
    }
    @Test void rejectsOversizedBodiesUnknownFieldsAndUnsafeMetadataWithoutPayloadInErrors() {
        var codec=new VisitMessageCodec();
        assertThatThrownBy(() -> codec.decode(new byte[16385])).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid visit message contract.").hasNoCause();
        var e=new VisitEvent(UUID.randomUUID(),"Ab12",Instant.parse("2026-09-30T16:00:00.123Z"),LocalDate.of(2026,10,1),new byte[32],1,null,null,null);
        String valid=new String(codec.encode(e),java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(()->codec.decode(valid.getBytes(java.nio.charset.StandardCharsets.UTF_16)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid visit message contract.");
        for(String broken:new String[]{valid.replace("\"schemaVersion\":1","\"schemaVersion\":2"),
            valid.replace("\"schemaVersion\":1","\"schemaVersion\":1.0"),
            valid.replace("\"statDate\":\"2026-10-01\"","\"statDate\":\"2026-09-30\""),
            valid.replace("\"peerIpNetwork\":null","\"peerIpNetwork\":\"192.168.1.99\""),
            valid.replace("\"userAgent\":null","\"userAgent\":\"secret\\u0001\""),
            valid.replace("\"refererHost\":null","\"refererHost\":\"example.com/private\""),
            valid.replace("\"userAgent\":null","\"userAgent\":\""+"😀".repeat(513)+"\""),
            valid.substring(0,valid.length()-1)+",\"Cookie\":\"private-secret\"}"})
            assertThatThrownBy(()->codec.decode(broken.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid visit message contract.").hasNoCause();
    }}
