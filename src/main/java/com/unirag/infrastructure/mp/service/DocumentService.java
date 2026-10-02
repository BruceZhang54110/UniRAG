package com.unirag.infrastructure.mp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.unirag.infrastructure.mp.entity.Document;

public interface DocumentService extends IService<Document> {

    /**
     * 按 (tenant_id, doc_name) 查询未软删除文档（contracts §5 重摄取语义的裁决查询）。
     */
    Document findByTenantAndName(Long tenantId, String docName);
}
