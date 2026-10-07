package com.kiano.platform.auth;

import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CurrentUser} controller method arguments from the JWT principal.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(@Nullable MethodParameter parameter) {
        return parameter != null && CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(@Nullable MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
            @Nullable NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.getToken() instanceof Jwt jwt) {
            Object tidClaim = jwt.getClaim("tid");
            long tenantId = tidClaim instanceof Number number ? number.longValue() : 0L;
            String role = jwt.getClaimAsString("role");
            return new CurrentUser(Long.parseLong(jwt.getSubject()), tenantId,
                    role == null ? null : Role.valueOf(role), jwt.getClaimAsString("email"));
        }
        throw new IllegalStateException("No authenticated JWT user available for @CurrentUser parameter");
    }
}
