package com.unirag.infrastructure.persistence.entity;

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

    @TableField("document_version_id")
    private Long documentVersionId;

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

    /** 切片层级：PARENT=父块（不向量化）；CHILD=子块。 */
    @TableField("chunk_level")
    private String chunkLevel;

    /** 子块→父块自引用（同 document+pipeline+version 作用域）；父块与单级切片为 NULL。 */
    @TableField("parent_chunk_id")
    private Long parentChunkId;

    /** 非持久化：findParentsByChildSeqIndexes 查询回带的子块 seq（回填映射键）。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Integer childSeq;

    @TableField("created_at")
    private Date createdAt;
}
