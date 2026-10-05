package com.reconcile.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads {@code Authorization: Bearer <token>} and populates the security context.
 *
 * <p>Separate from {@link SecurityConfig} so that the token comparison — the part with a security
 * property — is one short class with one job. It never rejects on its own: an absent or unknown
 * token simply leaves the context empty, and the authorisation rules decide what an anonymous
 * request may do. A filter that answered 401 itself would have to duplicate the permitAll list, and
 * the two copies would drift.
 */
final class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final SecurityConfig config;

    BearerTokenAuthenticationFilter(SecurityConfig config) {
        this.config = config;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (log.isDebugEnabled()) {
            log.debug("bearer filter invoked for {}: headerPresent={} bearerPrefix={} existingAuth={}",
                    request.getRequestURI(), header != null,
                    header != null && header.startsWith(BEARER),
                    SecurityContextHolder.getContext().getAuthentication());
        }
        // Deliberately NOT gated on `getAuthentication() == null`. An AnonymousAuthenticationToken is
        // already established by the time this filter runs, so such a guard skips the body entirely
        // and every bearer request is refused. Replacing the anonymous token is the normal job of an
        // authentication filter.
        var existing = SecurityContextHolder.getContext().getAuthentication();
        boolean alreadyAuthenticated = existing != null
                && existing.isAuthenticated()
                && !(existing instanceof AnonymousAuthenticationToken);

        if (header != null && header.startsWith(BEARER) && !alreadyAuthenticated) {

            String token = header.substring(BEARER.length()).trim();
            java.util.Optional<List<GrantedAuthority>> authorities = config.authoritiesFor(token);

            if (authorities.isPresent()) {
                // The principal name is deliberately opaque: the token itself is a secret and must
                // never reach an audit row, a log line or an exception message.
                //
                // The three-argument constructor marks the token authenticated itself. Calling
                // setAuthenticated(true) afterwards throws IllegalArgumentException ("Cannot set
                // this token to trusted"), which ExceptionTranslationFilter catches and converts to
                // a 401 - so every authenticated request was refused while the unit and
                // integration suites stayed green, because no test ever issued one.
                var authentication = new UsernamePasswordAuthenticationToken(
                        "bearer-token", null, authorities.get());
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("bearer token accepted for {} on {}", authorities.get(), request.getRequestURI());
            } else {
                // Length only. Logging the token, or a hash of it, would put a usable credential in
                // the log; the length is enough to tell "header truncated" from "wrong token".
                log.warn("bearer token of length {} did not match any configured token for {}",
                        token.length(), request.getRequestURI());
            }
        }
        chain.doFilter(request, response);
    }
}