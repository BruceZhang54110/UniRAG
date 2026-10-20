package com.unirag.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/** 评测薄启动器（013 tasks T024）。默认质量门通过 @Tag("eval") 排除。 */
@Tag("eval")
class RagEvaluationIT extends AbstractEvaluationIT {

    @Test
    void runEvaluation() {
        EvaluationOptions options = EvaluationOptions.fromSystemProperties();
        Path report = runner().run(options);
        System.out.println("Evaluation report: " + report.toAbsolutePath());
    }
}
