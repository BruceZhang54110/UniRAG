package com.unirag;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * 多租户资源基座集成测试基类。
 *
 * <p>数据源直接复用主配置 {@code application.yml} 指向的本地 PostgreSQL（开发库），
 * Flyway 自动执行迁移；真实验证 JSONB 映射、联表 SQL 与 Bean 注册链路（research.md R12）。
 *
 * <p>测试数据约定（必须遵守）：
 * <ul>
 *   <li><b>每个测试类使用全局唯一租户码</b>（如 {@code acmeUS1}、{@code badUS3}），
 *       断言只针对本类租户码，避免与其他测试类残留的 Bean/数据冲突；</li>
 *   <li>测试数据保留在开发库中无害且可追溯；如需清理可在子类加
 *       {@code @Sql(executionPhase = AFTER_TEST_CLASS)} 删除本类租户码的数据；</li>
 *   <li>本地 PG 已安装 pgvector 扩展（{@code CREATE EXTENSION vector}，需一次性超级用户权限），
 *       EMBEDDING_STORE 测试可完整建表；未安装的库才退化为 {@code "create-table": false}。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        // 集成测试关闭 MyBatis SQL 控制台输出：防止测试数据（含 api-key）进入日志，干扰 FR-008 断言
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
})
public abstract class AbstractPostgresIntegrationTest {
}
