package com.example.shortlink.shortcode;


import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermutedShortCodeEncoderTest {

    private static final long MAX_SHORT_CODE_ID = 218_340_105_584_896L;

    private final PermutedShortCodeEncoder encoder = new PermutedShortCodeEncoder();

    @Test
    void encodesTheFixedAdrVectors() {
        assertThat(encoder.encode(1)).isEqualTo("pGeGLWSx");
        assertThat(encoder.encode(2)).isEqualTo("1ZXuEkNa");
        assertThat(encoder.encode(23)).isEqualTo("G8o4qYn");
        assertThat(encoder.encode(MAX_SHORT_CODE_ID)).isEqualTo("NmvSTyXU");
    }

    @Test
    void preservesTheFourToEightCharacterBase62LengthBoundaries() {
        List<EncodedVector> vectors = List.of(
                new EncodedVector(16_701_244_089_626L, "0000"),
                new EncodedVector(215_936_530_859_463L, "0ZZZ"),
                new EncodedVector(213_898_962_729_794L, "1000"),
                new EncodedVector(17_951_455_155_535L, "ZZZZ"),
                new EncodedVector(15_913_887_025_866L, "10000"),
                new EncodedVector(188_262_779_851_071L, "ZZZZZ"),
                new EncodedVector(186_225_211_721_402L, "100000"),
                new EncodedVector(48_899_737_314_399L, "ZZZZZZ"),
                new EncodedVector(46_862_169_184_730L, "1000000"),
                new EncodedVector(141_995_323_436_575L, "ZZZZZZZ"),
                new EncodedVector(139_957_755_306_906L, "10000000"));

        for (EncodedVector vector : vectors) {
            String code = encoder.encode(vector.id());
            assertThat(code).isEqualTo(vector.expectedCode());
            assertThat(code).matches("[0-9a-zA-Z]{4,8}");
        }
    }

    @Test
    void rejectsIdsOutsideThePositiveSupportedRange() {
        assertThatThrownBy(() -> encoder.encode(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoder.encode(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoder.encode(MAX_SHORT_CODE_ID + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record EncodedVector(long id, String expectedCode) {
    }
}
