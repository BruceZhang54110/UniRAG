package com.unirag.upload.support;

import com.unirag.infrastructure.persistence.entity.UploadSession;
import com.unirag.upload.UploadSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 过期上传会话清理（spec 边界情况，data-model.md）：删除 expires_at 到点的 INIT 会话及其暂存块。
 * 扫描间隔固定 1h（@Scheduled），保留时长 session-ttl 可配置（默认 24h）。
 */
@Slf4j
@Component
public class UploadSessionJanitor {

    private final UploadSessionService sessionService;

    public UploadSessionJanitor(UploadSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 每小时扫描一次；启动后首扫由 Spring 立即调度执行。 */
    @Scheduled(fixedRate = 60 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void purgeExpiredSessions() {
        List<UploadSession> expired = sessionService.findExpiredSessions();
        for (UploadSession session : expired) {
            try {
                sessionService.deleteSessionWithChunks(session.getId());
                log.info("已清理过期上传会话: sessionId={}, tenantId={}, doc={}",
                        session.getId(), session.getTenantId(), session.getDocName());
            } catch (Exception e) {
                log.warn("清理过期上传会话失败（跳过）: sessionId={}", session.getId(), e);
            }
        }
    }
}
