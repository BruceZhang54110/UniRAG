package com.unirag.retrieval.web;

import com.unirag.retrieval.ObservationQueryService;
import com.unirag.upload.support.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一观测查询接口（T039 / FR-006，contracts/retrieval-api.md §4）：GET /api/v1/observations。
 * 全部参数可选、可组合；租户由既有 {@code TenantContextInterceptor} 定位并强制隔离。
 */
@RestController
@RequestMapping("/api/v1")
public class ObservationController {

    private final ObservationQueryService queryService;

    public ObservationController(ObservationQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/observations")
    public ApiResponse<ObservationPage> list(@RequestParam(required = false) String traceId,
                                             @RequestParam(required = false) Long documentId,
                                             @RequestParam(required = false) String stage,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queryService.query(traceId, documentId, stage, status, from, to, page, size));
    }
}
