package com.anorys.pmm.maven;

import com.anorys.pmm.maven.Versions.Lag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionsTest {

    @Test
    void ordersLikeMaven() {
        assertThat(Versions.compare("3.10.0", "3.9.2")).isPositive();
        assertThat(Versions.compare("3.1.4", "3.1.4-SNAPSHOT")).isPositive();
        assertThat(Versions.compare("2.0.0", "2.0.0-RC1")).isPositive();
    }

    @Test
    void classifiesTheLag() {
        assertThat(Versions.lag("3.1.4", "3.1.4")).isEqualTo(Lag.NONE);
        assertThat(Versions.lag("3.2.0", "3.1.9")).isEqualTo(Lag.NONE);
        assertThat(Versions.lag("3.1.4", "3.1.6")).isEqualTo(Lag.PATCH);
        assertThat(Versions.lag("3.1.4", "3.3.0")).isEqualTo(Lag.MINOR);
        assertThat(Versions.lag("3.1.4", "4.0.0")).isEqualTo(Lag.MAJOR);
        assertThat(Versions.lag("7", "8")).isEqualTo(Lag.MAJOR);
        assertThat(Versions.lag("1.0", "1.0.1")).isEqualTo(Lag.PATCH);
    }

    @Test
    void recognisesPreReleasesAndRanges() {
        assertThat(Versions.isPreRelease("2.0.0-RC1")).isTrue();
        assertThat(Versions.isPreRelease("2.0.0-beta-2")).isTrue();
        assertThat(Versions.isPreRelease("6.0.0-M3")).isTrue();
        assertThat(Versions.isPreRelease("1.0.0rc1")).isTrue();
        assertThat(Versions.isPreRelease("2.0.0")).isFalse();
        assertThat(Versions.isPreRelease("33.0.0-jre")).isFalse();
        assertThat(Versions.isPreRelease("1.2.0-platform")).isFalse();
        assertThat(Versions.isPreRelease("5.3.1.Final")).isFalse();
        assertThat(Versions.isRange("[1.0,2.0)")).isTrue();
        assertThat(Versions.isSnapshot("1.0-SNAPSHOT")).isTrue();
    }
}
