package com.kiano.platform.auth;

/**
 * The authenticated user, injectable as a controller method argument.
 */
public record CurrentUser(long userId, long tenantId, Role role, String email) {
}
