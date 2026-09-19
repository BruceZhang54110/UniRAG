package com.unirag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.unirag.infrastructure.mp.mapper")
public class UniRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(UniRagApplication.class, args);
    }

}
