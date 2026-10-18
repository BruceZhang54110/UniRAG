package com.unirag.retrieval.web;

import com.unirag.retrieval.QaService;
import com.unirag.retrieval.RetrievalProperties;
import com.unirag.upload.support.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 问答接口（FR-004，contracts/retrieval-api.md §3）：POST /api/v1/ask。
 * 租户由既有 {@code TenantContextInterceptor} 定位，错误统一走 {@code GlobalExceptionHandler}。
 */
@RestController
@RequestMapping("/api/v1")
public class QaController {

    private final QaService qaService;
    private final RetrievalProperties properties;

    public QaController(QaService qaService, RetrievalProperties properties) {
        this.qaService = qaService;
        this.properties = properties;
    }

    @PostMapping("/ask")
    public ApiResponse<AskResponse> ask(@RequestBody AskRequest request) {
        return ApiResponse.ok(AskResponse.of(qaService.ask(request.toModel(properties.defaultMinScore()))));
    }
}
