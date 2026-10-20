package com.unirag.eval;

/** 评测运行模式（013 data-model §2.1）。 */
public enum RunMode {
    LIVE,
    RECORD,
    REPLAY;

    public static RunMode parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("eval.mode 必填：live | record | replay");
        }
        return switch (value.trim().toLowerCase()) {
            case "live" -> LIVE;
            case "record" -> RECORD;
            case "replay" -> REPLAY;
            default -> throw new IllegalArgumentException("未知 eval.mode: " + value + "（支持 live | record | replay）");
        };
    }

    public boolean usesExternalModel() {
        return this == LIVE || this == RECORD;
    }
}
