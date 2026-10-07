package com.kiano.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BootstrapOwnerRunnerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppUserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void createsOwnerOnce_whenNoUsers() {
        jdbcTemplate.update("delete from app_user");
        BootstrapOwnerRunner runner = new BootstrapOwnerRunner(jdbcTemplate, userMapper, passwordEncoder,
                "boot@example.test", "boot-pass-123");
        runner.run(null);
        runner.run(null);
        Integer owners = jdbcTemplate.queryForObject("select count(*) from app_user where role = 'OWNER'",
                Integer.class);
        assertThat(owners).isEqualTo(1);
    }

    @Test
    void skipsCreation_whenEmailMissing() {
        jdbcTemplate.update("delete from app_user");
        BootstrapOwnerRunner runner = new BootstrapOwnerRunner(jdbcTemplate, userMapper, passwordEncoder,
                "", "boot-pass-123");
        runner.run(null);
        Integer users = jdbcTemplate.queryForObject("select count(*) from app_user", Integer.class);
        assertThat(users).isZero();
    }
}
