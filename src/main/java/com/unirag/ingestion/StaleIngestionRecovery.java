package com.unirag.ingestion;

import com.unirag.infrastructure.mp.entity.Document;
import com.unirag.infrastructure.mp.entity.IngestionRun;
import com.unirag.infrastructure.mp.service.DocumentService;
import com.unirag.infrastructure.mp.service.IngestionRunService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 摄取中断恢复任务（research.md R6）：启动时把滞留 INGESTING 超过 5 分钟的文档
 * 与其未完结 run 标记 FAILED（原因 "interrupted"），允许重新摄取（spec 边界情况：
 * 摄取中途进程重启，文档状态不得停留在"进行中"误导后续重试）。
 *
 * <p>低优先级 {@link Order}：在租户资源注册（Registrar）等启动任务之后执行。
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StaleIngestionRecovery implements ApplicationRunner {

    /** INGESTING 滞留阈值：超过即判定为中断（research.md R6）。 */
    static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);

    static final String INTERRUPTED_REASON = "interrupted";

    private final DocumentService documentService;
    private final IngestionRunService runService;

    public StaleIngestionRecovery(DocumentService documentService, IngestionRunService runService) {
        this.documentService = documentService;
        this.runService = runService;
    }

    @Override
    public void run(ApplicationArguments args) {
        recover();
    }

    /**
     * 恢复滞留文档：INGESTING 且 updated_at 早于阈值 → 文档 FAILED；
     * 其未完结（finished_at 为空）run 同步 FAILED("interrupted")。
     */
    public void recover() {
        Date cutoff = Date.from(Instant.now().minus(STALE_THRESHOLD));
        List<Document> staleDocuments = documentService.lambdaQuery()
                .eq(Document::getStatus, DocumentStatus.INGESTING.name())
                .lt(Document::getUpdatedAt, cutoff)
                .list();
        if (staleDocuments.isEmpty()) {
            log.info("Stale ingestion recovery: no stale INGESTING document found");
            return;
        }
        List<Long> documentIds = staleDocuments.stream().map(Document::getId).toList();
        Date now = new Date();
        List<IngestionRun> unfinishedRuns = runService.findUnfinishedByDocumentIds(documentIds);
        for (IngestionRun run : unfinishedRuns) {
            run.setStatus(RunStatus.FAILED.name());
            run.setErrorMessage(INTERRUPTED_REASON);
            run.setFinishedAt(now);
            runService.updateById(run);
        }
        for (Document document : staleDocuments) {
            document.setStatus(DocumentStatus.FAILED.name());
            document.setUpdatedAt(now);
            documentService.updateById(document);
        }
        log.info("Stale ingestion recovery: {} stale document(s) marked FAILED, {} unfinished run(s) interrupted",
                staleDocuments.size(), unfinishedRuns.size());
    }
}
