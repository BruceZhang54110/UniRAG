package com.unirag.retrieval.query;

import com.unirag.infrastructure.persistence.entity.ChatMessageEntity;
import com.unirag.infrastructure.persistence.entity.Document;
import com.unirag.infrastructure.persistence.mapper.ChatMessageMapper;
import com.unirag.infrastructure.persistence.mapper.DocumentMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CitedDocumentResolver 单测（023 T004）：词表命中/未命中、8 个 reason 分支逐一断言、
 * 计数并列判定、保留重复计票口径。存储访问以内存替身替代，不触库。
 */
class CitedDocumentResolverTest {

    private static final Long TENANT = 1L;
    private static final String SESSION = "s1";

    private final ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private final CitationInheritanceProperties properties = new CitationInheritanceProperties(true, List.of(
            "这篇", "该文档", "其中", "上面提到", "刚才说"));

    private final CitedDocumentResolver resolver =
            new CitedDocumentResolver(properties, chatMessageMapper, documentMapper);

    @Test
    void noSessionSkipsBeforeAnaphora() {
        InheritDecision decision = resolver.resolve(TENANT, "  ", "这篇讲了什么");
        assertThat(decision.reason()).isEqualTo(InheritDecision.Reason.NO_SESSION);
        assertThat(decision.documentId()).isNull();
    }

    @Test
    void disabledTreatsAsNoAnaphora() {
        CitedDocumentResolver disabled = new CitedDocumentResolver(
                new CitationInheritanceProperties(false, List.of("这篇")), chatMessageMapper, documentMapper);
        InheritDecision decision = disabled.resolve(TENANT, SESSION, "这篇讲了什么");
        assertThat(decision.reason()).isEqualTo(InheritDecision.Reason.NO_ANAPHORA);
        assertThat(decision.documentId()).isNull();
    }

    @Test
    void questionWithoutAnaphoraSkips() {
        InheritDecision decision = resolver.resolve(TENANT, SESSION, "什么是多租户隔离");
        assertThat(decision.reason()).isEqualTo(InheritDecision.Reason.NO_ANAPHORA);
        assertThat(decision.matchedTerm()).isNull();
    }

    @Test
    void noPreviousRound() {
        when(chatMessageMapper.selectOne(any())).thenReturn(null);
        InheritDecision decision = resolver.resolve(TENANT, SESSION, "这篇讲了什么");
        assertThat(decision.reason()).isEqualTo(InheritDecision.Reason.NO_PREVIOUS_ROUND);
        assertThat(decision.matchedTerm()).isEqualTo("这篇");
    }

    @Test
    void previousFailedRoundNotCitable() {
        when(chatMessageMapper.selectOne(any())).thenReturn(aiRound("FAILED", null));
        InheritDecision decision = resolver.resolve(TENANT, SESSION, "这篇讲了什么");
        assertThat(decision.reason()).isEqualTo(InheritDecision.Reason.PREVIOUS_NOT_CITABLE);
    }

    @Test
    void previousRoundWithoutCitationsNotCitable() {
        when(chatMessageMapper.selectOne(any())).thenReturn(aiRound("SUCCESS", null));
        InheritDecision decision = resolver.resolve(TENANT, SESSION, "这篇讲了什么");
        assertThat(decision.reason()).isEqualTo(InheritDecision.Reason.PREVIOUS_NOT_CITABLE);
    }

    @Test
    void uniqueTopWins() {
        when(chatMessageMapper.selectOne(any())).thenReturn(aiRound("SUCCESS", "101,101,101,202"));
        when(documentMapper.selectById(101L)).thenReturn(aliveDocument(101L));
        InheritDecision decision = resolver.resolve(TENANT, SESSION, "这篇讲了什么");
        assertThat(decision.reason()).isEqualTo(InheritDecision.Reason.INHERITED);
        assertThat(decision.documentId()).isEqualTo(101L);
        assertThat(decision.matchedTerm()).isEqualTo("这篇");
    }

    @Test
    void tieIsAmbiguousAndFailsOpen() {
        when(chatMessageMapper.selectOne(any())).thenReturn(aiRound("SUCCESS", "101,202"));
        InheritDecision decision = resolver.resolve(TENANT, SESSION, "这篇讲了什么");
        assertThat(decision.reason()).isEqualTo(InheritDecision.Reason.AMBIGUOUS_CITATIONS);
        assertThat(decision.documentId()).isNull();
    }

    @Test
    void deletedDocumentIsSkipped() {
        when(chatMessageMapper.selectOne(any())).thenReturn(aiRound("SUCCESS", "101"));
        Document deleted = aliveDocument(101L);
        deleted.setDeleted(true);
        when(documentMapper.selectById(101L)).thenReturn(deleted);
        InheritDecision decision = resolver.resolve(TENANT, SESSION, "这篇讲了什么");
        assertThat(decision.reason()).isEqualTo(InheritDecision.Reason.DOCUMENT_DELETED);
        assertThat(decision.documentId()).isNull();
    }

    @Test
    void storageFailureConvergesToResolverError() {
        when(chatMessageMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));
        InheritDecision decision = resolver.resolve(TENANT, SESSION, "这篇讲了什么");
        assertThat(decision.reason()).isEqualTo(InheritDecision.Reason.RESOLVER_ERROR);
        assertThat(decision.documentId()).isNull();
    }

    @Test
    void countVotesKeepsDuplicatesAndSkipsGarbage() {
        Map<Long, Long> votes = CitedDocumentResolver.countVotes("101, 101 ,bad,202");
        assertThat(votes).containsEntry(101L, 2L).containsEntry(202L, 1L);
    }

    @Test
    void uniqueTopHandlesEmptyAndTie() {
        assertThat(CitedDocumentResolver.uniqueTop(Map.of())).isNull();
        assertThat(CitedDocumentResolver.uniqueTop(Map.of(1L, 2L, 2L, 2L))).isNull();
        assertThat(CitedDocumentResolver.uniqueTop(Map.of(1L, 2L, 2L, 1L))).isEqualTo(1L);
    }

    private ChatMessageEntity aiRound(String status, String citedDocumentIds) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setTenantId(TENANT);
        entity.setSessionId(SESSION);
        entity.setRole("AI");
        entity.setStatus(status);
        entity.setCitedDocumentIds(citedDocumentIds);
        return entity;
    }

    private Document aliveDocument(long id) {
        Document document = new Document();
        document.setId(id);
        document.setDeleted(false);
        return document;
    }
}
