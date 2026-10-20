package com.unirag.eval.setup;

import com.unirag.eval.dataset.CorpusDocument;
import com.unirag.eval.dataset.DatasetValidationException;
import com.unirag.eval.dataset.EvaluationDataset;
import com.unirag.ingestion.IngestOutcome;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionService;

/** 清理后摄取评测语料（013 research R3/R5）。 */
public final class EvaluationIndexPreparer {

    private final IngestionService ingestionService;

    public EvaluationIndexPreparer(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    public void prepare(EvaluationDataset dataset) {
        for (CorpusDocument document : dataset.corpus()) {
            IngestOutcome outcome = ingestionService.ingest(new IngestRequest(
                    dataset.config().tenant(),
                    document.docName(),
                    document.title(),
                    document.content()));
            if (outcome.status() != IngestOutcome.Status.INGESTED) {
                throw new DatasetValidationException("V-009",
                        "摄取失败: doc=" + document.docName()
                                + ", status=" + outcome.status()
                                + ", reason=" + outcome.errorMessage());
            }
        }
    }
}
