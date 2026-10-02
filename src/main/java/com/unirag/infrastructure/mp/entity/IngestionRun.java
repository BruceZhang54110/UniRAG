package com.unirag.infrastructure.mp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 管线执行记录：一次摄取中一条管线的执行结果（观测载体，research.md R8）。
 *
 * @TableName ingestion_run
 */
@Data
@TableName("ingestion_run")
public class IngestionRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("document_id")
    private Long documentId;

    @TableField("pipeline_id")
    private Long pipelineId;

    /** SUCCESS / FAILED。 */
    @TableField("status")
    private String status;

    @TableField("chunk_count")
    private Integer chunkCount;

    /** 截断 500 字符，不含密钥（FR-010）。 */
    @TableField("error_message")
    private String errorMessage;

    @TableField("started_at")
    private Date startedAt;

    @TableField("finished_at")
    private Date finishedAt;
}
