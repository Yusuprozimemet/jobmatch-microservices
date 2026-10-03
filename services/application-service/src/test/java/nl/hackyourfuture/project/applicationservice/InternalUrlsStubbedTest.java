package nl.hackyourfuture.project.applicationservice;

import nl.hackyourfuture.project.backend.shared.internal.InternalClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both internal clients reach StubUpstream in the service tests: a test that sets a path on the
 * stub is the one its client calls.
 */
class InternalUrlsStubbedTest extends ApplicationServiceTest {

    @Autowired
    private InternalClients internalClients;

    private final StubUpstream stub = StubUpstream.instance();

    @BeforeEach
    void resetTheStub() {
        stub.reset();
    }

    @Test
    void identityUrlIsTheStub() {
        stub.answer("/probe", 200, "{}");

        internalClients.forUrlProperty("app.internal.identity-url").get().post().uri("/probe").retrieve()
                .toBodilessEntity();

        assertThat(stub.calls("/probe")).isOne();
    }

    @Test
    void jobsUrlIsTheStub() {
        stub.answer("/probe", 200, "{}");

        internalClients.forUrlProperty("app.internal.jobs-url").get().post().uri("/probe").retrieve()
                .toBodilessEntity();

        assertThat(stub.calls("/probe")).isOne();
    }
}
