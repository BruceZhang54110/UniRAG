package com.unirag.embedding.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author zhw
 * Configuration for EmbeddingStore
 */
@EnableConfigurationProperties(value = EmbeddingStoreProperties.class)
@Configuration
public class EmbeddingStoreConfig {

//    @Bean
//    public EmbeddingStore embeddingStore(EmbeddingStoreProperties embeddingStoreProperties) {
//        return PgVectorEmbeddingStore.builder()
//                .host(embeddingStoreProperties.getHost())
//                .port(embeddingStoreProperties.getPort())
//                .database(embeddingStoreProperties.getDatabase())
//                .user(embeddingStoreProperties.getUser())
//                .password(embeddingStoreProperties.getPassword())
//                .table(embeddingStoreProperties.getTable())
//                .dimension(embeddingStoreProperties.getDimension())
//                .build();
//    }
}
