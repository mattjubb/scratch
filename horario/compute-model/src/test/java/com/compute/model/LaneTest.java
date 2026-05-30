package com.compute.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaneTest {

    @ParameterizedTest
    @CsvSource({"DEV,dev", "SIT,sit", "UAT,uat", "PFIX,pfix", "PROD,prod"})
    void codeIsLowercase(Lane lane, String expected) {
        assertThat(lane.code()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"dev,DEV", "sit,SIT", "uat,UAT", "pfix,PFIX", "prod,PROD"})
    void fromCodeIsCaseInsensitive(String code, Lane expected) {
        assertThat(Lane.fromCode(code)).isEqualTo(expected);
        assertThat(Lane.fromCode(code.toUpperCase())).isEqualTo(expected);
    }

    @Test
    void fromCodeRejectsUnknown() {
        assertThatThrownBy(() -> Lane.fromCode("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allLanesHaveUniqueCode() {
        long distinct = java.util.Arrays.stream(Lane.values())
                .map(Lane::code).distinct().count();
        assertThat(distinct).isEqualTo(Lane.values().length);
    }
}
