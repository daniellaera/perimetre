package ch.perimetre.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Guards {@code /admin/**} with the bearer token from the env var ADMIN_TOKEN. Required (at least
 * {@value #MIN_LENGTH} characters) in the prod profile; elsewhere an unset token leaves /admin open, with a
 * WARN, so local runs and the context tests work without one.
 */
@Configuration
class AdminSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurityConfig.class);
    static final int MIN_LENGTH = 32;

    @Bean
    FilterRegistrationBean<AdminTokenFilter> adminTokenFilter(@Value("${ADMIN_TOKEN:}") String token,
            Environment env) {
        String trimmed = token.trim();
        if (env.matchesProfiles("prod") && trimmed.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "ADMIN_TOKEN must be set (at least " + MIN_LENGTH + " characters) in the prod profile");
        }
        FilterRegistrationBean<AdminTokenFilter> registration = new FilterRegistrationBean<>(
                new AdminTokenFilter(trimmed));
        registration.addUrlPatterns("/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        if (trimmed.isEmpty()) {
            log.warn("ADMIN_TOKEN not set: /admin/** is open (local use only)");
            registration.setEnabled(false);
        } else {
            log.info("/admin/** requires the ADMIN_TOKEN bearer token");
        }
        return registration;
    }
}
