package com.unirag.ingestion;

/**
 * 文档生命周期状态（document.status，data-model.md 状态机）。
 */
public enum DocumentStatus {

    /** 摄取进行中（先落库拿 id 供 metadata 回链用）。 */
    INGESTING,

    /** 全部管线 SUCCESS。 */
    INGESTED,

    /** 部分管线 SUCCESS。 */
    PARTIAL,

    /** 全部管线 FAILED 或异常中断。 */
    FAILED
}
