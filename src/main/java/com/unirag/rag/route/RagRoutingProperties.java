package com.unirag.rag.route;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 查询路由部署级配置（research.md §R4）。 */
@ConfigurationProperties(prefix = "unirag.rag.routing")
public record RagRoutingProperties(@DefaultValue("0.70") double lowConfidenceThreshold,
                                   @DefaultValue("200ms") Duration classificationTimeout) {

    public RagRoutingProperties {
        if (lowConfidenceThreshold < 0.0 || lowConfidenceThreshold > 1.0) {
            throw new IllegalArgumentException("unirag.rag.routing.low-confidence-threshold must be in [0, 1]");
        }
        if (classificationTimeout == null || classificationTimeout.isNegative() || classificationTimeout.isZero()) {
            throw new IllegalArgumentException("unirag.rag.routing.classification-timeout must be positive");
        }
        if (classificationTimeout.toMillis() > 5_000) {
            throw new IllegalArgumentException("unirag.rag.routing.classification-timeout must be <= 5000ms");
        }
    }
}
