package com.unirag.retrieval.web;

import com.unirag.common.tenant.TenantContext;
import com.unirag.common.web.ApiException;
import com.unirag.common.web.ApiResponse;
import com.unirag.infrastructure.persistence.entity.ChatMessageEntity;
import com.unirag.retrieval.history.ChatHistoryWriter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话消息历史查询接口（specs/017-chat-history/contracts/chat-history-endpoint.md，FR-003）：
 * {@code GET /api/v1/chat-memories/{sessionId}/messages} 分页正序回放完整对话历史。
 *
 * <p>只读：不修改记忆或历史；会话不存在返回 404（FR-002），存在但无消息返回 200 空列表。
 */
@RestController
@RequestMapping("/api/v1")
public class ChatHistoryController {

    static final int MAX_SIZE = 200;

    private final TenantContext tenantContext;
    private final ChatHistoryWriter historyWriter;

    public ChatHistoryController(TenantContext tenantContext, ChatHistoryWriter historyWriter) {
        this.tenantContext = tenantContext;
        this.historyWriter = historyWriter;
    }

    @GetMapping("/chat-memories/{sessionId}/messages")
    public ApiResponse<Map<String, Object>> messages(@PathVariable String sessionId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1) {
            throw new ApiException("INVALID_ARGUMENT", "page 需 ≥ 0，size 需 ≥ 1");
        }
        Long tenantId = tenantContext.getTenantId();
        if (!historyWriter.existsSession(tenantId, sessionId)) {
            throw new ApiException("SESSION_NOT_FOUND", "会话不存在或已删除");
        }
        int effectiveSize = Math.min(size, MAX_SIZE);
        ChatHistoryWriter.PageResult result = historyWriter.findMessages(tenantId, sessionId,
                page, effectiveSize);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("total", result.total());
        data.put("page", page);
        data.put("size", effectiveSize);
        data.put("items", result.items().stream().map(ChatHistoryController::toItem).toList());
        return ApiResponse.ok(data);
    }

    private static Map<String, Object> toItem(ChatMessageEntity entity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("roundSeq", entity.getRoundSeq());
        item.put("role", entity.getRole());
        item.put("content", entity.getContent());
        item.put("status", entity.getStatus());
        item.put("degradeReason", entity.getDegradeReason());
        item.put("traceId", entity.getTraceId());
        item.put("createdAt", entity.getCreatedAt() == null ? null : entity.getCreatedAt().getTime());
        return item;
    }
}
