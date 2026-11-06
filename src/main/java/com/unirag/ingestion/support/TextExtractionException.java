package com.unirag.ingestion.support;

import com.unirag.common.web.ApiException;

/**
 * 文本抽取失败（spec FR-004 / data-model 错误码分类）：携带契约错误码，
 * 同步路径经 {@link ApiException} 全局映射为请求级 4xx；异步路径由
 * {@code DocumentIngestionWorker} 凭码判定为确定性失败、直接终态不占用重试预算。
 * 码值：{@code PARSE_FAILED}（损坏/截断/加密/容器不符）、{@code EMPTY_EXTRACTED_TEXT}（扫描件/纯图片）。
 */
public class TextExtractionException extends ApiException {

    public static final String CODE_PARSE_FAILED = "PARSE_FAILED";
    public static final String CODE_EMPTY_EXTRACTED_TEXT = "EMPTY_EXTRACTED_TEXT";

    public TextExtractionException(String code, String message) {
        super(code, message);
    }
}
