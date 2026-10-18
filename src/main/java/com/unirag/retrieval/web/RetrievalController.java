package com.unirag.retrieval.web;

import com.unirag.retrieval.RetrievalProperties;
import com.unirag.retrieval.RetrievalService;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.upload.support.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 向量检索接口（FR-001，contracts/retrieval-api.md §2）：POST /api/v1/retrieve。
 * 租户由既有 {@code TenantContextInterceptor} 定位，错误统一走 {@code GlobalExceptionHandler}。
 */
@RestController
@RequestMapping("/api/v1")
public class RetrievalController {

    private final RetrievalService retrievalService;
    private final RetrievalProperties properties;

    public RetrievalController(RetrievalService retrievalService, RetrievalProperties properties) {
        this.retrievalService = retrievalService;
        this.properties = properties;
    }

    @PostMapping("/retrieve")
    public ApiResponse<RetrieveResponse> retrieve(@RequestBody RetrieveRequest request) {
        RetrievalRequest model = request.toModel(properties.defaultMinScore());
        return ApiResponse.ok(RetrieveResponse.of(retrievalService.retrieve(model), model));
    }
}
