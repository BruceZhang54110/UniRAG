package com.unirag.retrieval.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阶段流水线编排（FR-012，contracts/pipeline-extension.md §3/§5）。
 *
 * <p>执行序保证观察型看到的永远是阶段的最终输入与最终输出：增强型改写输入 → 观察型 before →
 * 阶段执行 → 增强型改写输出 → 观察型 after。扩展异常一律在此收敛（按各自 {@code failurePolicy()}），
 * MUST NOT 冒泡到 Controller；阶段自身异常按原样上抛以保留业务错误码。
 */
@Slf4j
@Component
public class DefaultPipeline {

    private final List<ObservationExtension> observationExtensions;
    /** (Bean 名 → 增强型扩展)，已按 order() 升序、Bean 名兜底排定。 */
    private final List<Map.Entry<String, EnhancementExtension>> enhancementExtensions;

    public DefaultPipeline(List<ObservationExtension> observationExtensions,
                           Map<String, EnhancementExtension> enhancementExtensions) {
        this.observationExtensions = List.copyOf(observationExtensions);
        this.enhancementExtensions = enhancementExtensions.entrySet().stream()
                .sorted(Comparator
                        .comparingInt((Map.Entry<String, EnhancementExtension> entry) -> entry.getValue().order())
                        .thenComparing(Map.Entry::getKey))
                .toList();
    }

    /**
     * 按序执行阶段链，返回最后一个阶段的最终输出。
     *
     * @param stages 有序阶段链（类型由调用方保证首尾相接）
     * @param input  链路输入
     */
    public Object run(List<Stage<?, ?>> stages, Object input, StageContext ctx) {
        Object current = input;
        for (Stage<?, ?> stage : stages) {
            StageName name = stage.name();
            long startedAt = System.nanoTime();
            Object output;
            try {
                Object effectiveInput = applyBeforeEnhancements(name, current, ctx);
                notifyObserversBefore(name, effectiveInput, ctx);
                output = applyAfterEnhancements(name, execute(stage, effectiveInput, ctx), ctx);
            } catch (ShortCircuit shortCircuit) {
                notifyObserversAfter(name, shortCircuit.output(),
                        StageOutcome.success(elapsedMillis(startedAt)), ctx);
                return shortCircuit.output();
            } catch (RuntimeException e) {
                // 阶段失败（含 FAIL_CLOSED 的扩展失败）也要留痕，运维才能定位失败阶段与原因
                notifyObserversAfter(name, null, StageOutcome.failed(elapsedMillis(startedAt), describe(e)), ctx);
                throw e;
            }
            notifyObserversAfter(name, output, StageOutcome.success(elapsedMillis(startedAt)), ctx);
            current = output;
        }
        return current;
    }

    private Object applyBeforeEnhancements(StageName stage, Object input, StageContext ctx) {
        Object current = input;
        for (Map.Entry<String, EnhancementExtension> entry : applicableEnhancements(stage)) {
            try {
                current = entry.getValue().beforeStage(stage, current, ctx);
            } catch (Exception e) {
                failOrSkip(entry.getKey(), entry.getValue().failurePolicy(), stage, e);
            }
        }
        return current;
    }

    private Object applyAfterEnhancements(StageName stage, Object output, StageContext ctx) {
        Object current = output;
        for (Map.Entry<String, EnhancementExtension> entry : applicableEnhancements(stage)) {
            try {
                current = entry.getValue().afterStage(stage, current, ctx);
            } catch (ShortCircuit shortCircuit) {
                throw shortCircuit;
            } catch (Exception e) {
                failOrSkip(entry.getKey(), entry.getValue().failurePolicy(), stage, e);
            }
        }
        return current;
    }

    private void notifyObserversBefore(StageName stage, Object input, StageContext ctx) {
        for (ObservationExtension extension : applicableObservations(stage)) {
            try {
                extension.beforeStage(stage, input, ctx);
            } catch (Exception e) {
                failOrSkip(extension.getClass().getSimpleName(), extension.failurePolicy(), stage, e);
            }
        }
    }

    private void notifyObserversAfter(StageName stage, Object output, StageOutcome outcome, StageContext ctx) {
        for (ObservationExtension extension : applicableObservations(stage)) {
            try {
                extension.afterStage(stage, output, outcome, ctx);
            } catch (Exception e) {
                failOrSkip(extension.getClass().getSimpleName(), extension.failurePolicy(), stage, e);
            }
        }
    }

    /** 扩展失败按自身声明的策略处理：FAIL_OPEN 记录后继续，FAIL_CLOSED 使该阶段失败（§4）。 */
    private void failOrSkip(String extensionName, FailurePolicy policy, StageName stage, Exception cause) {
        // 只记录扩展名与异常类型，避免异常 message 携带配置原文或密钥（原则 V）
        log.warn("Pipeline extension failed: extension={}, stage={}, policy={}, type={}",
                extensionName, stage, policy, cause.getClass().getName());
        if (policy == FailurePolicy.FAIL_CLOSED) {
            throw new StageFailureException(stage,
                    "stage " + stage + " failed in extension " + extensionName, cause);
        }
    }

    private List<Map.Entry<String, EnhancementExtension>> applicableEnhancements(StageName stage) {
        return enhancementExtensions.stream().filter(entry -> applies(entry.getValue().stages(), stage)).toList();
    }

    private List<ObservationExtension> applicableObservations(StageName stage) {
        return observationExtensions.stream().filter(extension -> applies(extension.stages(), stage)).toList();
    }

    private static boolean applies(Set<StageName> stages, StageName stage) {
        return stages.isEmpty() || stages.contains(stage);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object execute(Stage stage, Object input, StageContext ctx) {
        return stage.execute(input, ctx);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String describe(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }
}
