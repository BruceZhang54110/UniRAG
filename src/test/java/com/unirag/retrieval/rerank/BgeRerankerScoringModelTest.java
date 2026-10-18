package com.unirag.retrieval.rerank;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BgeRerankerScoringModelTest {

    private static final String URL = "http://rerank.test/rerank";

    @Test
    void 按index回填到输入顺序() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BgeRerankerScoringModel model = new BgeRerankerScoringModel(
                builder.build(), URL, "tenant-key", "BAAI/bge-reranker-v2-m3", 32);

        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tenant-key"))
                .andRespond(withSuccess("""
                        {"results":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.1}]}
                        """, MediaType.APPLICATION_JSON));

        Response<List<Double>> response = model.scoreAll(
                List.of(TextSegment.from("a"), TextSegment.from("b")), "查询");

        assertThat(response.content()).containsExactly(0.1, 0.9);
        server.verify();
    }

    @Test
    void 超过批量上限时分批并保持顺序() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BgeRerankerScoringModel model = new BgeRerankerScoringModel(
                builder.build(), URL, "tenant-key", "BAAI/bge-reranker-v2-m3", 2);

        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"results":[{"index":0,"relevance_score":0.1},{"index":1,"relevance_score":0.2}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"results":[{"index":0,"relevance_score":0.3}]}
                        """, MediaType.APPLICATION_JSON));

        Response<List<Double>> response = model.scoreAll(
                List.of(TextSegment.from("a"), TextSegment.from("b"), TextSegment.from("c")), "查询");

        assertThat(response.content()).containsExactly(0.1, 0.2, 0.3);
        server.verify();
    }

    @Test
    void 返回分数不完整时抛异常() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BgeRerankerScoringModel model = new BgeRerankerScoringModel(
                builder.build(), URL, "tenant-key", "BAAI/bge-reranker-v2-m3", 32);

        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"results":[{"index":0,"relevance_score":0.1}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> model.scoreAll(
                List.of(TextSegment.from("a"), TextSegment.from("b")), "查询"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 非2xx响应抛异常() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BgeRerankerScoringModel model = new BgeRerankerScoringModel(
                builder.build(), URL, "tenant-key", "BAAI/bge-reranker-v2-m3", 32);

        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> model.scoreAll(List.of(TextSegment.from("a")), "查询"))
                .isInstanceOf(RuntimeException.class);
    }
}
