package com.tucanoo.davkit.boot;

import com.tucanoo.davkit.auth.OfbaSessionResolver;
import com.tucanoo.davkit.spi.DavPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.Map;

/**
 * Turns the Spring Security context stored in the HTTP session into a {@link DavPrincipal} —
 * the OFBA adapter. It reads the session directly through
 * {@link HttpSessionSecurityContextRepository} because {@code davkit.path} is outside the host's
 * security filter chain: the chain never ran for this request, so
 * {@code SecurityContextHolder} is empty, but the session Office replays still holds the login.
 */
final class SpringSecuritySessionResolver implements OfbaSessionResolver {

    private final HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    @Override
    public DavPrincipal resolve(HttpServletRequest request) {
        SecurityContext context = repository.loadDeferredContext(request).get();
        Authentication authentication = context == null ? null : context.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return DavPrincipal.ANONYMOUS;
        }
        return new DavPrincipal(authentication.getName(), authentication.getName(), Map.of());
    }
}
