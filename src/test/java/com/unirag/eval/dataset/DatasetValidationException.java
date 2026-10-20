package com.unirag.eval.dataset;

/** 数据集/配置校验失败（013 data-model §5）。消息包含规则编号与可定位信息，不包含凭据。 */
public class DatasetValidationException extends RuntimeException {

    private final String rule;

    public DatasetValidationException(String rule, String message) {
        super(rule + ": " + message);
        this.rule = rule;
    }

    public String rule() {
        return rule;
    }
}
