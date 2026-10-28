package com.unirag.upload;

import com.unirag.infrastructure.persistence.entity.UploadSession;
import com.unirag.ingestion.AsyncIngestionProperties;
import com.unirag.upload.dto.AsyncDocumentUploadResponse;
import com.unirag.upload.dto.DocumentUploadResponse;
import com.unirag.common.web.ApiResponse;
import com.unirag.common.tenant.TenantContext;
import com.unirag.upload.support.UploadAssembler;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块上传（断点续传）接口（contracts §3）：初始化 → 分块（raw body）→ 完成。
 * 租户经 {@link TenantContext} 定位；uploadId 仅创建者租户可见。
 */
@RestController
@RequestMapping("/api/v1/uploads")
public class ChunkUploadController {

    private final UploadSessionService sessionService;
    private final UploadAssembler assembler;
    private final AsyncIngestionProperties asyncProperties;

    public ChunkUploadController(UploadSessionService sessionService, UploadAssembler assembler,
                                 AsyncIngestionProperties asyncProperties) {
        this.sessionService = sessionService;
        this.assembler = assembler;
        this.asyncProperties = asyncProperties;
    }

    /** ① 初始化上传会话（JSON 元数据 + 全文 SHA-256 声明）。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Object>> init(@RequestBody UploadInitRequest request) {
        UploadSession session = sessionService.create(request.docName(), request.title(),
                request.totalSize(), request.chunkSize(), request.totalChunks(), request.contentSha256());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("uploadId", session.getId());
        data.put("expiresAt", session.getExpiresAt().getTime());
        data.put("receivedChunks", List.of());
        return ApiResponse.ok(data);
    }

    /** ② 上传分块：原始二进制 body；同 seq 重传 = 幂等覆盖；返回已收块清单。 */
    @PutMapping(value = "/{uploadId}/chunks/{seq}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<Map<String, Object>> putChunk(@PathVariable long uploadId,
                                                     @PathVariable int seq,
                                                     @RequestBody byte[] body) {
        List<Integer> received = sessionService.putChunk(uploadId, seq, body);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("uploadId", uploadId);
        data.put("receivedChunks", received);
        return ApiResponse.ok(data);
    }

    /** ③ 完成：收齐 + 摘要比对 → 拼装落库并触发摄取（响应同一次性上传契约 §2）。 */
    @PostMapping("/{uploadId}/complete")
    public ResponseEntity<?> complete(@PathVariable long uploadId) {
        if (asyncProperties.isEnabled()) {
            return ResponseEntity.accepted().body(ApiResponse.ok(assembler.completeAsync(uploadId)));
        }
        return ResponseEntity.ok(ApiResponse.ok(assembler.complete(uploadId)));
    }

    /** 初始化请求体（contracts §3-①）。 */
    public record UploadInitRequest(String docName, String title, long totalSize, int chunkSize,
                                    int totalChunks, String contentSha256) {
    }
}
