package com.unirag.retrieval.web;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.AbstractRetrievalIT;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.tenant.resource.ResourceType;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US1 验收（tasks.md T009，FR-001/002/003/009，SC-002）：流式端点返回 SSE 事件序列
 * message×N → done，done 为全量权威结果（与 message 拼接逐字一致），X-Trace-Id 双透出，
 * 观测链完整（含 CHAT_MEMORY），记忆照常累积。
 *
 * <p>租户码全局唯一：{@code sseUS1}。
 */
class AskStreamEndpointIT extends AbstractRetrievalIT {

    private static final String TENANT = "sseUS1";
    private static final String TABLE = "embeddings_sseus1_main";
    private static final String DOC = "sse-us1-doc";
    private static final String SESSION = "s-sse-1";

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        cleanupObservations(TENANT);
        jdbcTemplate.update("DELETE FROM chat_memory WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?)",
                TENANT);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "mainStore", TABLE, 1024);
        insertPipeline(tenantId, "main", 300, 30, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT, "mainStore")));
        ingestionService.ingest(new IngestRequest(
                TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));

        OpenAiStreamingChatModel streamingModel = org.mockito.Mockito.mock(OpenAiStreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("根据资料，");
            handler.onPartialResponse("重排模型支持 BGE。[1]");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("根据资料，重排模型支持 BGE。[1]"))
                    .build());
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(tenantResourceRouter.getPrimary(eq(TENANT), eq(ResourceType.STREAMING_LLM),
                eq(OpenAiStreamingChatModel.class))).thenReturn(streamingModel);
    }

    @Test
    void 流式端点按契约输出事件序列且done为权威结果() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/v1/ask/stream")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "重排模型有哪些？", "sessionId": "%s", "topK": 3}
                                """.formatted(SESSION)))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult async = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn();
        // 响应头随异步写回生效
        String traceId = async.getResponse().getHeader("X-Trace-Id");
        assertThat(traceId).isNotBlank();
        String body = async.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        List<String> deltas = extractDeltas(body);
        assertThat(deltas).containsExactly("根据资料，", "重排模型支持 BGE。[1]");
        Map<String, Object> done = extractEvent(body, "done");
        assertThat(done.get("traceId")).isEqualTo(traceId);
        assertThat(done.get("answer")).isEqualTo(String.join("", deltas));
        assertThat(done.get("degraded")).isEqualTo(false);
        assertThat(done.get("citations")).asList().isNotEmpty();

        // 观测链完整且同 traceId（FR-009）
        assertThat(jdbcTemplate.queryForList(
                "SELECT stage FROM retrieval_observation WHERE trace_id = ? ORDER BY id",
                String.class, traceId))
                .contains(StageName.CHAT_MEMORY.name(), StageName.CHAT_GENERATION.name());
        // 记忆照常累积（FR-007/008）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(messages::jsonb) FROM chat_memory WHERE memory_id = ?",
                Integer.class, tenantId(TENANT) + ":" + SESSION))
                .isEqualTo(2);
    }

    @Test
    void 检索无命中时done直接携带降级文案() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/v1/ask/stream")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "完全不相关的问题xyz", "sessionId": "%s", "topK": 3, "minScore": 0.99}
                                """.formatted(SESSION)))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult async = mockMvc.perform(asyncDispatch(started)).andReturn();
        String body = async.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("event:message");
        Map<String, Object> done = extractEvent(body, "done");
        assertThat(done.get("degraded")).isEqualTo(true);
        assertThat(done.get("degradeReason")).isEqualTo("NO_CONTEXT");
        assertThat(done.get("answer")).isEqualTo("知识库中未找到相关内容，无法基于现有资料回答该问题。");
    }

    private static List<String> extractDeltas(String body) {
        List<String> deltas = new ArrayList<>();
        Map<String, Object> event;
        for (int i = 0; (event = nthEvent(body, "message", i)) != null; i++) {
            deltas.add((String) event.get("delta"));
        }
        return deltas;
    }

    private static Map<String, Object> extractEvent(String body, String name) {
        return nthEvent(body, name, 0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nthEvent(String body, String name, int n) {
        List<String> events = new CopyOnWriteArrayList<>(List.of(body.split("\n\n")));
        int seen = -1;
        for (String block : events) {
            if (block.contains("event:" + name)) {
                seen++;
                if (seen == n) {
                    String data = block.lines().filter(line -> line.startsWith("data:")).findFirst()
                            .orElseThrow().substring(5).trim();
                    try {
                        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(data, Map.class);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        return null;
    }
}
