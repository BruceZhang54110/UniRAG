package com.unirag.infrastructure.mp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 文档切片：文档经一条管线切出的一个片段（回链核心：embedding_id → 租户向量表记录）。
 *
 * @TableName document_chunk
 */
@Data
@TableName("document_chunk")
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("document_id")
    private Long documentId;

    @TableField("pipeline_id")
    private Long pipelineId;

    /** 片段序号（langchain4j splitter 产生的 index，0 起）。 */
    @TableField("seq_index")
    private Integer seqIndex;

    @TableField("content")
    private String content;

    /** 计量（IngestionTokenizer）。 */
    @TableField("token_count")
    private Integer tokenCount;

    /** 向量表记录 id（addAll 返回；跨表引用非 DB 外键）。 */
    @TableField("embedding_id")
    private String embeddingId;

    @TableField("created_at")
    private Date createdAt;
}
