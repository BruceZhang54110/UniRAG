package com.unirag.ingestion;

import com.unirag.infrastructure.persistence.service.DocumentIngestionJobService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "unirag.ingestion.async", name = "enabled", havingValue = "true")
public class DocumentIngestionWorkerScheduler {
    private final DocumentIngestionWorker worker;
    private final DocumentIngestionJobService jobs;

    public DocumentIngestionWorkerScheduler(DocumentIngestionWorker worker, DocumentIngestionJobService jobs) {
        this.worker = worker;
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${unirag.ingestion.async.poll-interval:1s}")
    public void poll() {
        jobs.recoverExpiredLeases();
        worker.runBatch();
    }
}
