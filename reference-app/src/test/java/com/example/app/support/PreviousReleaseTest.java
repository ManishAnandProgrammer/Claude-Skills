package com.example.app.support;

import com.example.app.support.PreviousReleaseDatabase.Release;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a release shipped, in the two changeset ranges: the pieces' 0001 to 0099, a project's own from 0100. */
class PreviousReleaseTest {

    @Test
    @DisplayName("A release of the pieces alone shipped up to its number and none of the project's own")
    void piecesAlone() {
        Release release = Release.parse("23");

        assertThat(release.shipped(23)).isTrue();
        assertThat(release.shipped(24)).isFalse();
        assertThat(release.shipped(100)).isFalse();
    }

    @Test
    @DisplayName("""
            A release with the project's own changesets shipped both ranges up to their numbers: a piece's \\
            changeset numbered below the project's but added since (0027 after 0105) is new""")
    void bothRanges() {
        Release release = Release.parse("26,105");

        assertThat(release.shipped(26)).isTrue();
        assertThat(release.shipped(105)).isTrue();
        assertThat(release.shipped(27)).isFalse();
        assertThat(release.shipped(106)).isFalse();
        assertThat(release.shipped(0)).as("a file without a number").isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"100", "26,50", "26,105,110", "x"})
    @DisplayName("A pieces' number from 0100, a project's below it, or more than two numbers is refused")
    void malformedReleases(String release) {
        assertThatThrownBy(() -> Release.parse(release)).isInstanceOf(IllegalArgumentException.class);
    }
}
