package ch.perimetre.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HealthcheckPingerTest {

    private static final String URL = "https://hc-ping.com/0b9d8e0a-0000-4000-8000-000000000000";

    @Test
    void noOpWhenUnset() {
        for (String url : new String[] { "", "  ", null }) {
            RestClient.Builder builder = RestClient.builder();
            // No expectations: any request fails the test (an AssertionError, which the pinger does not catch).
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            HealthcheckPinger pinger = new HealthcheckPinger(url, builder);
            pinger.start();
            pinger.success("ok");
            pinger.fail("ko");
            server.verify();
        }
    }

    @Test
    void pingsStartSuccessAndFail() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL + "/start")).andExpect(method(HttpMethod.POST)).andRespond(withSuccess());
        server.expect(requestTo(URL)).andExpect(content().string("summary")).andRespond(withSuccess());
        server.expect(requestTo(URL + "/fail")).andExpect(content().string("boom")).andRespond(withSuccess());

        HealthcheckPinger pinger = new HealthcheckPinger(URL + "/", builder);
        pinger.start();
        pinger.success("summary");
        pinger.fail("boom");
        server.verify();
    }

    @Test
    void aFailedPingNeverThrows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL + "/start")).andRespond(withServerError());
        assertThatCode(() -> new HealthcheckPinger(URL, builder).start()).doesNotThrowAnyException();
        server.verify();

        // Nothing listens on the discard port: connection refused.
        assertThatCode(() -> new HealthcheckPinger("http://127.0.0.1:9", RestClient.builder()).success("x"))
                .doesNotThrowAnyException();
    }
}
