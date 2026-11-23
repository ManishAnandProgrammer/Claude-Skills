package com.example.app.support;

import com.example.app.support.PreviousReleaseDatabase.Release;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("The previous release's changesets lead the master: the run stops at the first new one")
    void theShippedLeadTheMaster() {
        Release release = Release.parse("26,101");

        assertThat(release.leadingShipped(List.of("changes/0026-a.sql", "changes/0100-b.sql", "changes/0101-c.sql",
                "changes/0027-d.sql", "changes/0102-e.sql"))).isEqualTo(3);
    }

    @Test
    @DisplayName("A piece's new changeset inserted before the project's shipped ones is refused, naming both")
    void aNewChangesetAmongTheShippedIsRefused() {
        Release release = Release.parse("26,101");

        assertThatThrownBy(() -> release.leadingShipped(List.of("changes/0026-a.sql", "changes/0027-d.sql",
                "changes/0100-b.sql", "changes/0101-c.sql")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("changes/0100-b.sql shipped in the previous release but the master lists it after changes/0027-d.sql, which is new");
    }

    @ParameterizedTest
    @ValueSource(strings = {"100", "26,50", "26,105,110", "x"})
    @DisplayName("A pieces' number from 0100, a project's below it, or more than two numbers is refused")
    void malformedReleases(String release) {
        assertThatThrownBy(() -> Release.parse(release)).isInstanceOf(IllegalArgumentException.class);
    }
}
