package nl.hackyourfuture.project.jobservice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trial for the CI gates: fails on purpose, never merges.
 */
class FailsOnPurposeTest {

    @Test
    void failsOnPurpose() {
        assertThat(1).isEqualTo(2);
    }
}
