package com.ltc.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LTC 知识库服务入口。
 *
 * <p>能力边界：文档入库 → 混合检索 → 带引用的问答 → 给其它 Agent 的同步/流式/异步 API（含回调）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
