package com.unirag.tenant.resource;

import com.unirag.AbstractPostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1 验收（spec.md FR-001/FR-002/FR-008）：V3 迁移幂等启用 pgvector 扩展、重复迁移无副作用、
 * 空配置启动 health UP。本类不插业务数据，租户码约定不适用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // 集成测试关闭 MyBatis SQL 控制台输出：防止测试数据（含 api-key）进入日志，干扰 FR-008 断言
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
})
class PgVectorMigrationIT extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Flyway flyway;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void vectorExtensionIsInstalled() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT extname FROM pg_extension WHERE extname = 'vector'", String.class))
                .containsExactly("vector");
    }

    @Test
    void repeatedMigrateIsIdempotent() {
        flyway.migrate();

        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied())
                .anySatisfy(migration -> assertThat(migration.getVersion().toString()).isEqualTo("3"));
    }

    @Test
    void healthEndpointIsUpWithEmptyConfig() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
