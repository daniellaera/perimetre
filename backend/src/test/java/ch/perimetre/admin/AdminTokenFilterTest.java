package ch.perimetre.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminTokenFilterTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private final AdminTokenFilter filter = new AdminTokenFilter(TOKEN);

    @Test
    void passesWithTheToken() throws Exception {
        assertThat(run("Bearer " + TOKEN).chain.getRequest()).isNotNull();
        assertThat(run("bearer " + TOKEN).chain.getRequest()).isNotNull();
    }

    @Test
    void refusesEverythingElse() throws Exception {
        for (String header : new String[] { null, "", TOKEN, "Bearer", "Bearer ", "Bearer x", "Basic " + TOKEN,
                "Bearer " + TOKEN + "x", "Bearer " + TOKEN.substring(1) }) {
            Result result = run(header);
            assertThat(result.chain.getRequest()).as(String.valueOf(header)).isNull();
            assertThat(result.response.getStatus()).isEqualTo(401);
            assertThat(result.response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        }
    }

    @Test
    void prodRequiresALongEnoughToken() {
        AdminSecurityConfig config = new AdminSecurityConfig();
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> config.adminTokenFilter("", prod)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.adminTokenFilter("short", prod)).isInstanceOf(IllegalStateException.class);
        assertThat(config.adminTokenFilter(TOKEN, prod).isEnabled()).isTrue();
    }

    @Test
    void openWithoutATokenOutsideProd() {
        assertThat(new AdminSecurityConfig().adminTokenFilter(" ", new MockEnvironment()).isEnabled()).isFalse();
    }

    private record Result(MockFilterChain chain, MockHttpServletResponse response) {
    }

    private Result run(String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/import");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(chain, response);
    }
}
