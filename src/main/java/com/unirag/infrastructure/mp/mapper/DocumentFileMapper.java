package com.unirag.infrastructure.mp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unirag.infrastructure.mp.entity.DocumentFile;
import org.apache.ibatis.annotations.Param;

/**
 * @Entity com.unirag.infrastructure.mp.entity.DocumentFile
 */
public interface DocumentFileMapper extends BaseMapper<DocumentFile> {

    /**
     * 按 document_id 查询原文元数据。
     */
    DocumentFile findByDocumentId(@Param("documentId") Long documentId);
}
