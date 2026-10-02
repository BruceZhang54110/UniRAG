package com.unirag.ingestion;

import java.util.List;

/**
 * 摄取结果（contracts §1）：同步返回，全部管线执行完毕才返回。
 *
 * @param documentId    文档主键
 * @param docName       业务文档名
 * @param status        INGESTED / PARTIAL / FAILED / NOOP（NOOP = 同内容重复摄取）
 * @param version       文档版本（替换时 +1；NOOP 不变）
 * @param runs          每管线一条执行结果
 * @param errorMessage  顶层失败原因（如"无可用摄取管线"，不含密钥）；成功时为 null
 */
public record IngestOutcome(long documentId, String docName, Status status, int version,
                            List<RunOutcome> runs, String errorMessage) {

    /** 摄取结果状态（含 NOOP 语义状态，区别于 document.status 生命周期状态）。 */
    public enum Status {
        INGESTED,
        PARTIAL,
        FAILED,
        NOOP
    }
}
