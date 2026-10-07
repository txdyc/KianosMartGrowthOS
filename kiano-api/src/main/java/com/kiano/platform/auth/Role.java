package com.kiano.platform.auth;

/**
 * Application roles. Permission hierarchy: OWNER ⊃ OPERATOR ⊃ VIEWER
 * (enforced by the Spring Security RoleHierarchy).
 */
public enum Role {
    OWNER,
    OPERATOR,
    VIEWER
}
