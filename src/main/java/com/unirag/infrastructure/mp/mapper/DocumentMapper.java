package com.unirag.infrastructure.mp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unirag.infrastructure.mp.entity.Document;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * @description 针对表【document】的数据库操作Mapper
 * @Entity com.unirag.infrastructure.mp.entity.Document
 */
public interface DocumentMapper extends BaseMapper<Document> {

    /**
     * 按 (tenant_id, doc_name) 查询未软删除文档（replace 语义的裁决查询，research.md R5）。
     */
    Document findByTenantAndName(@Param("tenantId") Long tenantId, @Param("docName") String docName);

    /**
     * 上传文档列表（document LEFT JOIN document_file，contracts §4）：
     * 本租户未软删除文档按创建时间倒序分页，键为列名（doc_name/chunk_count/...）。
     */
    IPage<Map<String, Object>> pageUploadList(Page<Map<String, Object>> page, @Param("tenantId") Long tenantId);

    /**
     * 上传文档详情（document LEFT JOIN document_file，contracts §4）：
     * 含 file_name/mime_type/size_bytes/storage_path 等原文元数据，无结果返回 null。
     */
    Map<String, Object> findUploadDetailByTenantAndName(@Param("tenantId") Long tenantId, @Param("docName") String docName);
}
