package com.unirag.ingestion;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "unirag.ingestion.async")
public class AsyncIngestionProperties {

    private boolean enabled = false;
    private int maxAttempts = 3;
    private Duration backoffBase = Duration.ofSeconds(30);
    private Duration backoffMax = Duration.ofMinutes(15);
    private Duration lease = Duration.ofMinutes(5);
    private Duration pollInterval = Duration.ofSeconds(1);
    private int batchSize = 10;
}
