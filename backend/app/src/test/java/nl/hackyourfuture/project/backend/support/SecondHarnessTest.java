package nl.hackyourfuture.project.backend.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A second test class, only so the guarantees that span classes can be asserted: the run uses
 * one container, and the reset also happens between classes, not just between methods.
 */
class SecondHarnessTest extends IntegrationTest {

    @Test
    void sharesTheContainerWithEveryOtherTestClass() {
        assertThat(ObservedContainers.record()).hasSize(1);
    }

    @Test
    void seesNothingAnotherTestClassCreated() {
        assertThat(jdbc().sql("SELECT count(*) FROM users").query(Long.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM analytics.fct_postings WHERE source = 'test'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void seesTheBaselineMart() {
        assertThat(jdbc().sql("SELECT count(*) FROM analytics.fct_postings WHERE source = 'seed'")
                .query(Long.class).single()).isEqualTo(24);
    }
}
