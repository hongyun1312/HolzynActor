package com.holzyn.actor.common;

import com.holzyn.actor.ai.langchain4j.DynamicEmbeddingModelFactory;
import com.holzyn.actor.ai.langchain4j.VectorStoreService;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.ai.spring.DynamicChatModelFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.ApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SpringAiStartupReporter 单元测试（2026-09-03 追加轮 / 2026-09-05 M3 适配 LangChain4j 段 /
 * M5 适配：两 A/B 开关字段已随旧 Provider 删除，run() 在 mock 依赖下正常输出）。
 * <p>职责：验证启动报告——依赖注入态下 run() 不抛异常（banner + Spring AI / LangChain4j 摘要输出安全）；
 * Spring AI / LangChain4j 版本解析返回非空（包实现版本或回退常量）。</p>
 */
class SpringAiStartupReporterTest {

    /**
     * run() 在 mock 依赖下均正常输出（不抛异常；M5 起无 A/B 开关字段）。
     */
    @Test
    void runDoesNotThrowWithMockDependencies() {
        SpringAiStartupReporter reporter = new SpringAiStartupReporter(
                Mockito.mock(DynamicChatModelFactory.class),
                Mockito.mock(AiSpringChatGateway.class),
                Mockito.mock(DynamicEmbeddingModelFactory.class),
                Mockito.mock(VectorStoreService.class));
        assertDoesNotThrow(() -> reporter.run(Mockito.mock(ApplicationArguments.class)));
    }

    /**
     * 版本解析：Spring AI 回退常量非空且形如 2.0.1。
     */
    @Test
    void springAiVersionResolutionReturnsNonEmpty() {
        assertFalse(SpringAiVersionReflect.version().isBlank(), "Spring AI 版本不应为空");
        assertTrue(SpringAiVersionReflect.version().matches("[0-9]+(\\.[0-9]+)+.*"),
                "Spring AI 版本应形如 2.0.1");
    }

    /**
     * 版本解析：LangChain4j 回退常量非空且形如 1.x。
     */
    @Test
    void langchain4jVersionResolutionReturnsNonEmpty() {
        assertFalse(LangChain4jVersionReflect.version().isBlank(), "LangChain4j 版本不应为空");
        assertTrue(LangChain4jVersionReflect.version().matches("[0-9]+(\\.[0-9]+)+.*"),
                "LangChain4j 版本应形如 1.19.0");
    }

    /** 反射调用私有静态版本解析（测试专属小助手，避免扩大生产可见面） */
    private static final class SpringAiVersionReflect {
        static String version() {
            try {
                var m = SpringAiStartupReporter.class.getDeclaredMethod("resolveSpringAiVersion");
                m.setAccessible(true);
                return (String) m.invoke(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** 反射调用私有静态版本解析（测试专属小助手，避免扩大生产可见面） */
    private static final class LangChain4jVersionReflect {
        static String version() {
            try {
                var m = SpringAiStartupReporter.class.getDeclaredMethod("resolveLangChain4jVersion");
                m.setAccessible(true);
                return (String) m.invoke(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
