package com.unirag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan({"com.unirag.infrastructure.storage", "com.unirag.upload", "com.unirag.ingestion",
        "com.unirag.retrieval", "com.unirag.rag"})
@MapperScan("com.unirag.infrastructure.persistence.mapper")
public class UniRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(UniRagApplication.class, args);
    }

}
