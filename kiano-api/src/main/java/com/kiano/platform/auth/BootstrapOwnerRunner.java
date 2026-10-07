package com.kiano.platform.auth;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the initial OWNER user for the kianosmart tenant on first start,
 * when kiano.bootstrap.owner-email / owner-password are configured.
 */
@Component
public class BootstrapOwnerRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapOwnerRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final String ownerEmail;
    private final String ownerPassword;

    public BootstrapOwnerRunner(JdbcTemplate jdbcTemplate, AppUserMapper userMapper, PasswordEncoder passwordEncoder,
            @Value("${kiano.bootstrap.owner-email:}") String ownerEmail,
            @Value("${kiano.bootstrap.owner-password:}") String ownerPassword) {
        this.jdbcTemplate = jdbcTemplate;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.ownerEmail = ownerEmail == null ? "" : ownerEmail.trim();
        this.ownerPassword = ownerPassword == null ? "" : ownerPassword;
    }

    @Override
    public void run(@Nullable ApplicationArguments args) {
        if (ownerEmail.isEmpty() || ownerPassword.isEmpty()) {
            return;
        }
        Long tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
        if (tenantId == null) {
            log.warn("Bootstrap owner skipped: tenant 'kianosmart' not found");
            return;
        }
        Long existing = jdbcTemplate.queryForObject("select count(*) from app_user where tenant_id = ?", Long.class,
                tenantId);
        if (existing != null && existing > 0) {
            return;
        }
        AppUserEntity user = new AppUserEntity();
        user.setTenantId(tenantId);
        user.setEmail(ownerEmail.toLowerCase());
        user.setName(ownerEmail.substring(0, ownerEmail.indexOf('@')));
        user.setPasswordHash(passwordEncoder.encode(ownerPassword));
        user.setRole(Role.OWNER.name());
        userMapper.insert(user);
        log.info("Bootstrap owner created: {}", user.getEmail());
    }
}
