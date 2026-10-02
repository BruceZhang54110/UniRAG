package com.unirag.infrastructure.mp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unirag.infrastructure.mp.entity.Document;
import org.apache.ibatis.annotations.Param;

/**
 * @description 针对表【document】的数据库操作Mapper
 * @Entity com.unirag.infrastructure.mp.entity.Document
 */
public interface DocumentMapper extends BaseMapper<Document> {

    /**
     * 按 (tenant_id, doc_name) 查询未软删除文档（replace 语义的裁决查询，research.md R5）。
     */
    Document findByTenantAndName(@Param("tenantId") Long tenantId, @Param("docName") String docName);
}
