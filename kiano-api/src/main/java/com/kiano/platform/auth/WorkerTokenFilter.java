package com.kiano.platform.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the kiano-worker process on the /api/v1/worker/** chain: the
 * bearer token is compared (constant time, via SHA-256) against
 * {@code kiano.worker.token-sha256}. A matching token becomes ROLE_WORKER; a
 * valid user JWT is authenticated with its own role so that it gets a clear
 * 403 instead of an anonymous 401; anything else stays anonymous.
 */
public class WorkerTokenFilter extends OncePerRequestFilter {

    private final String expectedTokenSha256;
    private final JwtService jwtService;
    private final BearerTokenResolver bearerTokenResolver;

    public WorkerTokenFilter(String expectedTokenSha256, JwtService jwtService,
            BearerTokenResolver bearerTokenResolver) {
        this.expectedTokenSha256 = expectedTokenSha256 == null ? "" : expectedTokenSha256.trim().toLowerCase();
        this.jwtService = jwtService;
        this.bearerTokenResolver = bearerTokenResolver;
    }

    @Override
    protected void doFilterInternal(@Nullable HttpServletRequest request,
            @Nullable HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (request == null || response == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = bearerTokenResolver.resolve(request);
            Authentication authentication = authenticate(token);
            if (authentication != null) {
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }

    private @Nullable Authentication authenticate(@Nullable String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        if (!expectedTokenSha256.isEmpty() && MessageDigest.isEqual(
                expectedTokenSha256.getBytes(StandardCharsets.UTF_8),
                sha256Hex(token).getBytes(StandardCharsets.UTF_8))) {
            return new UsernamePasswordAuthenticationToken("worker", token,
                    List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
        }
        // Not the worker token: if it is a valid user JWT, authenticate the user so
        // the authorization layer returns 403 FORBIDDEN instead of 401.
        try {
            Jwt jwt = jwtService.jwtDecoder().decode(token);
            String role = jwt.getClaimAsString("role");
            if (role != null) {
                return new UsernamePasswordAuthenticationToken(jwt.getSubject(), token,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            }
        } catch (JwtException ex) {
            // Invalid token: stays anonymous, entry point answers 401.
        }
        return null;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
