package com.unirag.retrieval.web;

import com.unirag.rag.web.AskRequest;
import com.unirag.retrieval.RetrievalProperties;
import com.unirag.retrieval.model.RetrievalConstraints;
import com.unirag.retrieval.model.RetrievalRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalRequestDefaultsTest {

    @Test
    void 省略minScore时使用传入的默认值() {
        assertThat(new RetrieveRequest("查询", null, null, null, null).toModel(0.70).minScore())
                .isEqualTo(0.70);
        assertThat(new AskRequest("问题", null, null, null, null).toModel(0.70).minScore())
                .isEqualTo(0.70);
    }

    @Test
    void 显式minScore覆盖默认值() {
        assertThat(new RetrieveRequest("查询", null, null, null, 0.2).toModel(0.70).minScore())
                .isEqualTo(0.2);
        assertThat(new AskRequest("问题", null, null, 0.2, null).toModel(0.70).minScore())
                .isEqualTo(0.2);
    }

    @Test
    void 省略topK时仍使用默认条数() {
        RetrievalRequest request = new RetrieveRequest("查询", null, null, null, null).toModel(0.70);

        assertThat(request.topK()).isEqualTo(RetrievalConstraints.DEFAULT_TOP_K);
    }

    @Test
    void 未配置时使用校准默认阈值() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RetrievalProperties.class).defaultMinScore()).isEqualTo(0.70);
        });
    }

    @Test
    void 配置越界时启动失败() {
        contextRunner()
                .withPropertyValues("unirag.retrieval.default-min-score=1.5")
                .run(context -> assertThat(context).hasFailed());
    }

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RetrievalProperties.class)
    static class PropertiesConfig {
    }
}
