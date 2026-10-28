package com.unirag.rag.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.infrastructure.persistence.entity.RetrievalObservation;
import com.unirag.infrastructure.persistence.service.RetrievalObservationService;
import com.unirag.rag.classify.ClassificationEvidence;
import com.unirag.rag.classify.ClassificationOutcome;
import com.unirag.rag.classify.ClassificationStatus;
import com.unirag.rag.classify.QueryIntent;
import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.pipeline.StageContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** ROUTE 观测行与受控 detail JSON 验收。 */
class RouteObservationWriterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void 写入一条ROUTE观测并保留目标实际路径证据与原因顺序() throws Exception {
        RetrievalObservationService service = mock(RetrievalObservationService.class);
        RouteObservationWriter writer = new RouteObservationWriter(
                new com.unirag.retrieval.observation.ObservationRecorder(service,
                        new com.unirag.retrieval.observation.ObservationProperties()),
                OBJECT_MAPPER);
        ClassificationOutcome outcome = new ClassificationOutcome(ClassificationStatus.LOW_CONFIDENCE,
                QueryIntent.MULTI_HOP_RELATION, 0.69,
                List.of(new ClassificationEvidence("MH_RELATION", QueryIntent.MULTI_HOP_RELATION,
                        0.55, "relationship wording api-key=sk-secret")),
                "LOW_CONFIDENCE", "rules-v1", 7);
        RouteDecision decision = new RouteDecision(RagParadigm.AGENTIC_RAG, RagParadigm.TRADITIONAL_RAG,
                List.of(RagParadigm.AGENTIC_RAG, RagParadigm.TRADITIONAL_RAG), true,
                List.of("LOW_CONFIDENCE", "CAPABILITY_NOT_REGISTERED"),
                List.of(new CapabilityProbe(RagParadigm.AGENTIC_RAG, CapabilityAvailability.NOT_REGISTERED,
                                "CAPABILITY_NOT_REGISTERED"),
                        new CapabilityProbe(RagParadigm.TRADITIONAL_RAG, CapabilityAvailability.READY, null)),
                7, "rules-v1");

        writer.write(context(), outcome, decision);

        ArgumentCaptor<RetrievalObservation> captor = ArgumentCaptor.forClass(RetrievalObservation.class);
        verify(service).save(captor.capture());
        RetrievalObservation row = captor.getValue();
        assertThat(row.getTraceId()).isEqualTo(context().traceId());
        assertThat(row.getTenantId()).isEqualTo(context().tenantId());
        assertThat(row.getKind()).isEqualTo(StageResult.KIND_QA);
        assertThat(row.getStage()).isEqualTo("ROUTE");
        assertThat(row.getTargetKey()).isEqualTo("AGENTIC_RAG");
        assertThat(row.getStatus()).isEqualTo("DEGRADED");
        assertThat(row.getDurationMs()).isEqualTo(7);
        assertThat(row.getHitCount()).isNull();
        assertThat(row.getTopScore()).isNull();
        assertThat(row.getItemCount()).isEqualTo(1);
        assertThat(row.getErrorMessage()).isEqualTo("LOW_CONFIDENCE");
        assertThat(row.getDetail()).doesNotContain("sk-secret");
        assertThat(row.getDetail().length()).isLessThanOrEqualTo(2000);

        JsonNode detail = OBJECT_MAPPER.readTree(row.getDetail());
        assertThat(detail.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(detail.path("ruleVersion").asText()).isEqualTo("rules-v1");
        assertThat(detail.path("classification").path("status").asText()).isEqualTo("LOW_CONFIDENCE");
        assertThat(detail.path("classification").path("intent").asText()).isEqualTo("MULTI_HOP_RELATION");
        assertThat(detail.path("classification").path("confidence").asDouble()).isEqualTo(0.69);
        assertThat(detail.path("route").path("target").asText()).isEqualTo("AGENTIC_RAG");
        assertThat(detail.path("route").path("actual").asText()).isEqualTo("TRADITIONAL_RAG");
        assertThat(detail.path("route").path("reasonCodes"))
                .extracting(JsonNode::asText)
                .containsExactly("LOW_CONFIDENCE", "CAPABILITY_NOT_REGISTERED");
        assertThat(detail.path("route").path("capabilityProbes")).hasSize(2);
    }

    @Test
    void detail超长时经ObservationRecorder截断到2000字符() {
        RetrievalObservationService service = mock(RetrievalObservationService.class);
        RouteObservationWriter writer = new RouteObservationWriter(
                new com.unirag.retrieval.observation.ObservationRecorder(service,
                        new com.unirag.retrieval.observation.ObservationProperties()),
                OBJECT_MAPPER);
        List<ClassificationEvidence> evidence = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            evidence.add(new ClassificationEvidence("LONG_RULE_" + index, QueryIntent.COMPLEX_REASONING,
                    0.35, "long rule description " + ("x".repeat(240)) + index));
        }
        ClassificationOutcome outcome = new ClassificationOutcome(ClassificationStatus.SUCCESS,
                QueryIntent.COMPLEX_REASONING, 0.99, evidence, "RULE_MATCH", "rules-v1", 1);
        RouteDecision decision = new RouteDecision(RagParadigm.AGENTIC_RAG, RagParadigm.TRADITIONAL_RAG,
                List.of(RagParadigm.AGENTIC_RAG, RagParadigm.TRADITIONAL_RAG), true,
                List.of("CAPABILITY_NOT_REGISTERED"),
                List.of(new CapabilityProbe(RagParadigm.AGENTIC_RAG, CapabilityAvailability.NOT_REGISTERED,
                                "CAPABILITY_NOT_REGISTERED"),
                        new CapabilityProbe(RagParadigm.TRADITIONAL_RAG, CapabilityAvailability.READY, null)),
                1, "rules-v1");

        writer.write(context(), outcome, decision);

        ArgumentCaptor<RetrievalObservation> captor = ArgumentCaptor.forClass(RetrievalObservation.class);
        verify(service).save(captor.capture());
        assertThat(captor.getValue().getDetail().length()).isLessThanOrEqualTo(2000);
    }

    @Test
    void detail序列化失败时failOpen且不影响调用方() throws Exception {
        RetrievalObservationService service = mock(RetrievalObservationService.class);
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        org.mockito.Mockito.when(brokenMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("broken") {});
        RouteObservationWriter writer = new RouteObservationWriter(
                new com.unirag.retrieval.observation.ObservationRecorder(service,
                        new com.unirag.retrieval.observation.ObservationProperties()),
                brokenMapper);

        assertThatCode(() -> writer.write(context(), outcome(), decision())).doesNotThrowAnyException();
        verify(service, never()).save(any());
    }

    private static ClassificationOutcome outcome() {
        return new ClassificationOutcome(ClassificationStatus.SUCCESS, QueryIntent.SIMPLE_FACT, 0.91,
                List.of(new ClassificationEvidence("SF_DIRECT_FACT", QueryIntent.SIMPLE_FACT,
                        0.55, "direct fact wording")), "RULE_MATCH", "rules-v1", 1);
    }

    private static RouteDecision decision() {
        return new RouteDecision(RagParadigm.TRADITIONAL_RAG, RagParadigm.TRADITIONAL_RAG,
                List.of(RagParadigm.TRADITIONAL_RAG), false, List.of(), List.of(
                        new CapabilityProbe(RagParadigm.TRADITIONAL_RAG, CapabilityAvailability.READY, null)),
                1, "rules-v1");
    }

    private static StageContext context() {
        return new StageContext("0123456789abcdef0123456789abcdef", StageResult.KIND_QA,
                "routeObs", 42L, "session-1", "什么是租户隔离？", null);
    }
}
