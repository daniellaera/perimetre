package ch.perimetre.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Lets a request through only with {@code Authorization: Bearer <ADMIN_TOKEN>}, otherwise 401. Both sides
 * are hashed before {@link MessageDigest#isEqual}, so the comparison takes the same time whatever the
 * presented value, its length included.
 */
final class AdminTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final byte[] expected;

    AdminTokenFilter(String token) {
        this.expected = sha256(token);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
                && MessageDigest.isEqual(expected, sha256(header.substring(PREFIX.length()).trim()))) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
