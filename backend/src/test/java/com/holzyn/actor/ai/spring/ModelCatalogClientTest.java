package com.holzyn.actor.ai.spring;

import com.holzyn.actor.common.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 模型目录拉取解析单元测试。
 *
 * <p>职责：锁死三种供应商返回形态的解析与 URL 拼接——设置页"获取全部模型"是用户手动触发的，
 * 解析写错不会在启动期暴露，只会在用户点按钮时报"没有模型"或抛 500。</p>
 */
class ModelCatalogClientTest {

    /**
     * OpenAI 形态 {@code {"data":[{"id":...}]}}：按 id 收集并排序。
     */
    @Test
    void parsesOpenAiDataArray() {
        String body = "{\"data\":[{\"id\":\"gpt-4o-mini\"},{\"id\":\"deepseek-chat\"}]}";
        assertEquals(List.of("deepseek-chat", "gpt-4o-mini"), ModelCatalogClient.parse(body));
    }

    /**
     * Ollama/自建网关形态 {@code {"models":[{"name":...}]}}：按 name 收集。
     */
    @Test
    void parsesModelsArrayWithName() {
        String body = "{\"models\":[{\"name\":\"qwen2.5:7b\"},{\"name\":\"bge-m3\"}]}";
        assertEquals(List.of("bge-m3", "qwen2.5:7b"), ModelCatalogClient.parse(body));
    }

    /**
     * 裸数组形态 {@code ["..."]}。
     */
    @Test
    void parsesBareArray() {
        assertEquals(List.of("a", "b"), ModelCatalogClient.parse("[\"b\",\"a\"]"));
    }

    /**
     * 空响应体 = 空清单（不是错误）。
     */
    @Test
    void emptyBodyMeansNoModels() {
        assertEquals(List.of(), ModelCatalogClient.parse(""));
        assertEquals(List.of(), ModelCatalogClient.parse("{}"));
    }

    /**
     * 非法 JSON：给出可理解的中文错误，而不是把解析异常直接抛给前端。
     */
    @Test
    void invalidJsonThrowsBizException() {
        assertThrows(BizException.class, () -> ModelCatalogClient.parse("<html>404</html>"));
    }

    /**
     * URL 拼接：去尾斜杠、已带 /models 不重复追加。
     */
    @Test
    void modelsUrlNormalization() {
        assertEquals("https://api.example.com/v1/models", ModelCatalogClient.modelsUrl("https://api.example.com/v1"));
        assertEquals("https://api.example.com/v1/models", ModelCatalogClient.modelsUrl("https://api.example.com/v1/"));
        assertEquals("https://api.example.com/v1/models", ModelCatalogClient.modelsUrl("https://api.example.com/v1/models"));
    }
}
