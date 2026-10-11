package com.kiano.content.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.batch.ReviewSessionService.ReviewTimeStats;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * ReviewSessionService (C6 Task 5, Review Focus 5): reported active time is
 * clamped per heartbeat (at most 20 s, silent deltas count 0), only the user's
 * own open session can be updated, and the per-SKU average only counts SKUs
 * whose FACT and ASSETS sessions both finished.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReviewSessionServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReviewSessionService sessions;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;
    private long storeId;
    private long batchId;
    private long productA;
    private long productB;
    private CurrentUser user;
    private CurrentUser other;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from review_session");
        jdbcTemplate.update("delete from content_batch_item");
        // batch jobs and runs point at a batch/product: delete them first
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from content_batch");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user where email like '%session.test'");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        batchId = jdbcTemplate.queryForObject(
                "insert into content_batch (tenant_id, name, status) "
                        + "values (?, 'Session batch', 'RUNNING') returning id",
                Long.class, tenantId);
        productA = product("MG-SESS1");
        productB = product("MG-SESS2");
        long userId = user("op@session.test", "OPERATOR");
        long otherId = user("other@session.test", "OPERATOR");
        user = new CurrentUser(userId, tenantId, Role.OPERATOR, "op@session.test");
        other = new CurrentUser(otherId, tenantId, Role.OPERATOR, "other@session.test");
    }

    /** Sessions reference products, users and batches: never leave them behind. */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from review_session");
        jdbcTemplate.update("delete from content_batch_item");
        jdbcTemplate.update("delete from content_batch");
    }

    @Test
    void heartbeat_clampsDeltaTo20s_andIgnoresAfterSilenceOver60s() {
        long session = sessions.start(user, productA, batchId, "FACT");

        assertThat(sessions.heartbeat(user, session, 15).activeSeconds()).isEqualTo(15.0);
        assertThat(sessions.heartbeat(user, session, 99).activeSeconds()).isEqualTo(35.0);
        assertThat(sessions.heartbeat(user, session, -5).activeSeconds()).isEqualTo(35.0);

        // the tab was suspended: the back-filled delta does not count
        jdbcTemplate.update("update review_session set last_heartbeat_at = now() "
                + "- interval '61 seconds' where id = ?", session);
        assertThat(sessions.heartbeat(user, session, 15).activeSeconds()).isEqualTo(35.0);

        // the next beat is measured from that heartbeat again
        assertThat(sessions.heartbeat(user, session, 10).activeSeconds()).isEqualTo(45.0);
    }

    @Test
    void heartbeat_otherUsersSession_404_finished_409() {
        long session = sessions.start(user, productA, batchId, "FACT");

        assertThatThrownBy(() -> sessions.heartbeat(other, session, 10))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                    assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
                });

        sessions.finish(user, session, 5, "SKIPPED");
        assertThatThrownBy(() -> sessions.heartbeat(user, session, 10))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getCode()).isEqualTo("SESSION_FINISHED");
                });
        assertThatThrownBy(() -> sessions.finish(user, session, 5, "LEFT"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SESSION_FINISHED"));

        // an unknown outcome is rejected up front
        long fresh = sessions.start(user, productA, batchId, "ASSETS");
        assertThatThrownBy(() -> sessions.finish(user, fresh, 5, "DONE"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void stats_sumsFactAndAssetsPerSku_onlySkusWithBothFinished() {
        finished(productA, "FACT", 100);
        finished(productA, "ASSETS", 50);
        // B only ever finished a fact session: it does not count
        finished(productB, "FACT", 200);
        open(productB, "ASSETS", 40);

        ReviewTimeStats stats = sessions.stats(tenantId, batchId);

        assertThat(stats.skuCount()).isEqualTo(1);
        assertThat(stats.avgFactSeconds()).isEqualTo(100.0);
        assertThat(stats.avgAssetSeconds()).isEqualTo(50.0);
        assertThat(stats.avgTotalSeconds()).isEqualTo(150.0);
        assertThat(stats.p90TotalSeconds()).isEqualTo(150.0);
        assertThat(stats.targetSeconds()).isEqualTo(180);
    }

    @Test
    void stats_multipleSessionsSameSku_areSummed() {
        finished(productA, "FACT", 60);
        finished(productA, "FACT", 40);
        finished(productA, "ASSETS", 30);
        finished(productB, "FACT", 90);
        finished(productB, "ASSETS", 60);

        ReviewTimeStats stats = sessions.stats(tenantId, batchId);

        assertThat(stats.skuCount()).isEqualTo(2);
        assertThat(stats.avgFactSeconds()).isEqualTo(95.0);
        assertThat(stats.avgAssetSeconds()).isEqualTo(45.0);
        assertThat(stats.avgTotalSeconds()).isEqualTo(140.0);
        assertThat(stats.p90TotalSeconds()).isEqualTo(150.0);
    }

    @Test
    void unfinishedSessions_excludedFromAverage() {
        finished(productA, "FACT", 60);
        open(productA, "ASSETS", 40);

        ReviewTimeStats stats = sessions.stats(tenantId, batchId);

        assertThat(stats.skuCount()).isZero();
        assertThat(stats.avgTotalSeconds()).isZero();
    }

    /** A session of this user whose time is already booked, then finished. */
    private void finished(long productId, String kind, double activeSeconds) {
        long id = sessions.start(user, productId, batchId, kind);
        jdbcTemplate.update("update review_session set active_seconds = ?, finished_at = now(), "
                + "outcome = 'LEFT' where id = ?", activeSeconds, id);
    }

    /** A session that is still open with some active time booked. */
    private void open(long productId, String kind, double activeSeconds) {
        long id = sessions.start(user, productId, batchId, kind);
        jdbcTemplate.update("update review_session set active_seconds = ? where id = ?",
                activeSeconds, id);
    }

    private long product(String sku) {
        return jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, "
                        + "synced_at) values (?, ?, ?, 'simple', ?, ?, 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku, "Product " + sku);
    }

    private long user(String email, String role) {
        return jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, ?, 'Op', ?, ?) returning id",
                Long.class, tenantId, email, passwordEncoder.encode("op-pass-123"), role);
    }
}