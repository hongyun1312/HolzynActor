/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.common;

import com.holzyn.actor.ai.langchain4j.DynamicEmbeddingModelFactory;
import com.holzyn.actor.ai.langchain4j.VectorStoreService;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.ai.spring.DynamicChatModelFactory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * AI 接入层初始化启动报告（轮 Spring AI / 追加 LangChain4j 段，
 * 用户需求：控制台输出 Spring AI / LangChain4j 的初始化进程/日志，像 Spring Boot 的 banner 一样；
 * 用户再调整：删除 ASCII banner 艺术字，把版本行放到 {@code:: Spring Boot::} 正下方）。
 * <p><b>版本行位置</b>：已迁入 {@code backend/src/main/resources/banner.txt}（保留 Spring Boot 原生
 * ASCII 艺术 + {@code:: Spring Boot::} 行，其下依次追加 {@code:: Spring AI:: (v${spring-ai.version})}
 * 与 {@code:: LangChain4j:: (v${langchain4j.version})} 行；占位符解析自 {@code application.yml} 的
 * {@code spring-ai.version} / {@code langchain4j.version}，与 {@code backend/pom.xml} 保持同步；
 * LangChain4j 无原生 banner（已核查 jar 无 banner 资源），按同模式追加版本行 + 摘要段）。</p>
 * <p><b>本组件职责</b>：启动完成后经 {@code log.info} 输出 AI 接入层初始化状态摘要
 * （Spring AI 段：动态模型工厂缓存数 / 对话网关[非流式+流式] / 观测 Advisor / 调用路由开关；
 * LangChain4j 段：DynamicEmbeddingModelFactory / VectorStoreService / 检索侧 待办），
 * 进控制台与日志文件，让使用者直观看到 AI 接入层已就绪。</p>
 * <p><b>版本来源（摘要内）</b>：优先取类包实现版本，失败回退常量（与 {@code backend/pom.xml}
 * 的 {@code spring-ai.version} / {@code langchain4j.version} 保持同步）。</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SpringAiStartupReporter implements ApplicationRunner {

    /** Spring AI 版本回退常量（与 backend/pom.xml spring-ai.version 保持同步） */
    private static final String SPRING_AI_VERSION_FALLBACK = "2.0.1";

    /** LangChain4j 版本回退常量（与 backend/pom.xml langchain4j.version 保持同步） */
    private static final String LANGCHAIN4J_VERSION_FALLBACK = "1.19.0";

    /** 动态模型工厂（缓存状态可观测） */
    private final DynamicChatModelFactory chatModelFactory;

    /** Spring AI 对话网关 */
    private final AiSpringChatGateway chatGateway;

    /** LangChain4j 动态 embedding 模型工厂 */
    private final DynamicEmbeddingModelFactory embeddingModelFactory;

    /** LangChain4j 向量库服务 */
    private final VectorStoreService vectorStoreService;

    /**
     * 启动完成后输出 AI 接入层初始化状态摘要（版本行已由 banner.txt 在启动 banner 中输出）。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            printSummary();
        } catch (Exception e) {
            // 报告失败不影响启动（纯展示）
            log.warn("[SpringAI] 初始化报告输出失败（不影响启动）: {}", e.getMessage());
        }
    }

    /**
     * 输出 AI 接入层初始化状态摘要（log.info，进控制台与日志文件）。
     * <ul>
     * <li>Spring AI 段：动态模型工厂 / 对话网关（业务方直接注入）/ 观测 Advisor；</li>
     * <li>LangChain4j 段：DynamicEmbeddingModelFactory / VectorStoreService / RAG 检索侧直连。</li>
     * </ul>
     */
    private void printSummary() {
        log.info("[SpringAI] ==============================================================");
        log.info("[SpringAI] AI 接入层初始化完成（Spring AI v{} / LangChain4j v{}）",
                resolveSpringAiVersion(), resolveLangChain4jVersion());
        log.info("[SpringAI]   · 动态模型工厂 DynamicChatModelFactory 就绪（缓存模型 {} 个，providerId 键 + 配置快照惰性失效）",
                chatModelFactory == null ? 0 : chatModelFactory.cachedCount());
        log.info("[SpringAI]   · 对话网关 AiSpringChatGateway 就绪（非流式 chatCompletion / chatCompletionJson + 流式 chatCompletionStream + 语义缓存[非流式]，网关主体记账）");
        log.info("[SpringAI]   · 观测 Advisor HolzynTraceAdvisor 已挂载（CallAdvisor/StreamAdvisor 观测 + DeepSeek/OpenAI 缓存命中双兼容用量解析）");
        log.info("[SpringAI]   · 调用入口：业务方直接注入 AiSpringChatGateway（M5 起旧 OpenAiCompatibleProvider / AiProviderRouter 兼容壳已物理删除，0 引用）");
        log.info("[SpringAI]   · LangChain4j embedding 工厂 DynamicEmbeddingModelFactory 就绪（缓存模型 {} 个）",
                embeddingModelFactory == null ? 0 : embeddingModelFactory.cachedCount());
        log.info("[SpringAI]   · LangChain4j 向量库 VectorStoreService 已挂载（data/vector-store/<project>/embeddings.json，懒加载 + 原子写 + 损坏兜底 + docId 索引）");
        log.info("[SpringAI]   · RAG 检索侧直连 RagSearchService（M5 起无 A/B 开关：query 向量化 + 向量库相似度 + 双粒度合并 + 文本降级兜底）");
        log.info("[SpringAI] ==============================================================");
    }

    /**
     * 解析 Spring AI 版本：优先包实现版本，失败回退常量。
     *
     * @return 版本字符串（永不为 null/空）
     */
    private static String resolveSpringAiVersion() {
        try {
            String v = OpenAiChatModel.class.getPackage() == null ? null
                    : OpenAiChatModel.class.getPackage().getImplementationVersion();
            if (v != null && !v.isBlank()) {
                return v;
            }
        } catch (Exception ignored) {
            // 包版本解析失败（个别类加载器）回退常量
        }
        return SPRING_AI_VERSION_FALLBACK;
    }

    /**
     * 解析 LangChain4j 版本：优先包实现版本，失败回退常量。
     *
     * @return 版本字符串（永不为 null/空）
     */
    private static String resolveLangChain4jVersion() {
        try {
            String v = EmbeddingModel.class.getPackage() == null ? null
                    : EmbeddingModel.class.getPackage().getImplementationVersion();
            if (v != null && !v.isBlank()) {
                return v;
            }
        } catch (Exception ignored) {
            // 包版本解析失败（个别类加载器）回退常量
        }
        return LANGCHAIN4J_VERSION_FALLBACK;
    }
}
