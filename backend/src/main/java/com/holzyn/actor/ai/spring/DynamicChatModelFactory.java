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

package com.holzyn.actor.ai.spring;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.ProviderConfig;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import com.openai.core.http.HttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring AI 动态模型工厂。
 * <p>职责：按运行时解析出的 {@link ProviderConfig}（baseUrl/apiKey/model）构建 {@link OpenAiChatModel}，
 * 并以<b>逻辑配置主键（providerId）</b>缓存复用（{@code ConcurrentHashMap}，尺寸有界=已配置供应商数）。</p>
 * <p><b>缓存失效策略</b>：不侵入 {@code ModelApiService} 的 CRUD——
 * 每次 {@link #get} 时把当前 ProviderConfig 与缓存条目内保存的配置快照比对，
 * baseUrl/apiKey/model 任一变化即重建替换（Key 轮换/地址变更天然生效）；并发下允许重复构建（模型无状态，
 * 后写覆盖，成本可接受）。{@link #invalidate} 提供显式逐键清除能力（供测试/未来事件钩子）。</p>
 * <p><b>桩/离线语义保留</b>：工厂只负责「配置→模型」；AI 总开关/各域开关的桩回退在业务域（如 worldgen
 * ai-enabled=false 回退桩执行器）与 Router 兼容壳（spring-ai-enabled=false 走旧 Provider），本工厂不做桩。</p>
 */
@Slf4j
@Component
public class DynamicChatModelFactory {

    /**
     * 构建 ChatModel 所需的配置快照（用于惰性失效比对）。
     *
     * @param apiKey 明文 Key（仅存内存；模型实例本身也持有该 Key，不新增暴露面）
     *
     * @param model 默认模型名
     */
    private record Snapshot(String baseUrl, String apiKey, String model) {

        /** 从运行时配置取快照 */
        static Snapshot of(ProviderConfig c) {
            return new Snapshot(c.baseUrl(), c.apiKey(), c.model());
        }

        /** 与当前配置比对：任一关键字段变化即视为失效需重建 */
        boolean matches(ProviderConfig c) {
            return eq(baseUrl, c.baseUrl()) && eq(apiKey, c.apiKey()) && eq(model, c.model());
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    /** 缓存条目：配置快照 + 构建好的模型 */
    private static final class Entry {
        final Snapshot snapshot;
        final OpenAiChatModel model;

        Entry(Snapshot snapshot, OpenAiChatModel model) {
            this.snapshot = snapshot;
            this.model = model;
        }
    }

    /** 模型缓存：providerId → 条目 */
    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    /**
     * AI HTTP 读超时（秒，默认 120：一次网络挂起的 AI 调用超过该时长即抛错走兜底，不阻塞引擎；
     * 正常 AI 调用 3~15s 远低于此—— 已知问题修复，见 build() 注释）
     */
    @Value("${holzyn.actor.ai.timeout-read-seconds:120}")
    private long timeoutReadSeconds = 120;

    /** AI HTTP 建连超时（秒，默认 30） */
    @Value("${holzyn.actor.ai.timeout-connect-seconds:30}")
    private long timeoutConnectSeconds = 30;

    /**
     * AI HTTP 单次请求总时长上限（秒，默认 130：> 读超时，作为硬性兜底覆盖整次调用
     * [含 HTTP/2 流等待]，防止 readTimeout 因实现细节失效时仍无限等待）
     */
    @Value("${holzyn.actor.ai.timeout-request-seconds:130}")
    private long timeoutRequestSeconds = 130;

    /**
     * 按运行时配置取（懒构建 + 惰性失效）ChatModel。
     *
     * @param config 已解析的供应商运行时配置（含解密 Key；id 为全局唯一主键）
     *
     * @return 缓存复用或新建的 OpenAiChatModel
     */
    public OpenAiChatModel get(ProviderConfig config) {
        Long key = config.id();
        if (key == null) {
            throw new AiCallException("无法构建 Spring AI ChatModel：ProviderConfig 缺少配置主键");
        }
        Entry entry = cache.get(key);
        if (entry != null && entry.snapshot.matches(config)) {
            return entry.model;
        }
        OpenAiChatModel model = build(config);
        cache.put(key, new Entry(Snapshot.of(config), model));
        log.info("[SpringAI] 构建/重建 ChatModel: providerId={} baseUrl={} model={}", key, config.baseUrl(), config.model());
        return model;
    }

    /**
     * 显式逐键失效（测试/未来 ModelApiService 事件钩子用；惰性校验下通常无需调用）。
     *
     * @param providerId 配置主键（null 忽略）
     */
    public void invalidate(Long providerId) {
        if (providerId != null) {
            cache.remove(providerId);
        }
    }

    /** 当前缓存模型数（测试/概览用） */
    public int cachedCount() {
        return cache.size();
    }

    /**
     * 构建 OpenAiChatModel（2.0 API：OpenAiChatModel.Builder 无 baseUrl/apiKey 直设，
     * 经 options() 传入 OpenAiChatOptions[baseUrl/apiKey/model]）。
     * <p>模型名缺失 → AiCallException（与旧 Provider「未指定模型」口径一致）。
     * 温度/maxTokens/jsonMode 为每请求参数，不在工厂 defaultOptions 固化（业务调用各异）。
     * 包内可见供 {@link ChatModelProbe} 对<b>未保存配置</b>（id=null，如设置页 DTO 测试连接）直接构建不缓存。</p>
     *
     * @param config 供应商运行时配置
     *
     * @return 构建好的模型
     */
    OpenAiChatModel build(ProviderConfig config) {
        String model = config.model();
        if (model == null || model.isBlank()) {
            throw new AiCallException("未指定模型：请在该 API 配置中填写默认模型名");
        }
        try {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .baseUrl(config.baseUrl())
                    .apiKey(config.apiKey())
                    .model(model)
                    .build();
            // ⚠️⚠️⚠️ 已知问题修复·最终版（第三轮 jstack 实证，用户指示优先修复 +
            // 用户判断根因「AI 生成太慢」）：AI HTTP 调用无有效超时 → 网络挂起的 AI 调用把引擎扫描
            // 线程永久阻塞（世界引擎永久停，违反 24h 不间断运行目标）。三轮演进：
            // 第一轮：httpClientBuilderCustomizer(b -> b.timeout(120s))——无效（HTTP/2 不吃
            // socket 超时）；第二轮：SpringAiOpenAiHttpClient.builder().timeout(Timeout) +
            // per-call Interceptor（chain.call().timeout()）——仍无效（jstack 实证 214s/351s/
            // 1335s 全卡 Http2Stream.waitForIo）：HTTP/2 连接 SO_TIMEOUT 被 OkHttp 清零，callTimeout
            // 到期仅发 RST_STREAM 不关 socket，「服务端半死」时 waitForIo 的 Object.wait 永不唤醒。
            // 最终修复：见 Http11OpenAiHttpClient——**禁用 HTTP/2 强制 HTTP/1.1**，readTimeout（SO_TIMEOUT）
            // 直接作用于请求线程的 socket read，悬挂 120s 必断；callTimeout 双保险。jstack 层面不再
            // 存在 waitForIo 等待死角。
            Timeout timeout = Timeout.builder()
                    .connect(Duration.ofSeconds(Math.max(1, timeoutConnectSeconds)))
                    .read(Duration.ofSeconds(Math.max(1, timeoutReadSeconds)))
                    .write(Duration.ofSeconds(Math.max(1, timeoutReadSeconds)))
                    .request(Duration.ofSeconds(Math.max(1, timeoutRequestSeconds)))
                    .build();
            HttpClient httpClient = new Http11OpenAiHttpClient(
                    Duration.ofSeconds(Math.max(1, timeoutConnectSeconds)),
                    Duration.ofSeconds(Math.max(1, timeoutReadSeconds)),
                    Duration.ofSeconds(Math.max(1, timeoutReadSeconds)),
                    Duration.ofSeconds(Math.max(1, timeoutRequestSeconds)));
            ClientOptions clientOptions = ClientOptions.builder()
                    .baseUrl(config.baseUrl())
                    .apiKey(config.apiKey())
                    .httpClient(httpClient)
                    // ⚠️ 冒烟抓修（第二轮，jstack 实证）：maxRetries 必须为 0——
                    // openai-java RetryingHttpClient 对超时抛出的 IOException 会重试（maxRetries=2 时
                    // 一次网络挂起的 AI 调用=3 次尝试×130s≈390s 引擎停顿，24h 运行不可接受）；
                    // 置 0 后单次调用 130s 超时立即抛错 → 既有 AI 失败兜底链路（程序化决策）快速恢复，
                    // 业务侧（对话等）自有重试逻辑不受影响
                    .maxRetries(0)
                    .timeout(timeout)
                    .build();
            return OpenAiChatModel.builder()
                    .options(options)
                    .openAiClient(new OpenAIClientImpl(clientOptions))
                    .build();
        } catch (AiCallException e) {
            throw e;
        } catch (Exception e) {
            // 构建失败（非法 baseUrl/Key 格式等）：包装为带中文提示的 AiCallException，不泄漏堆栈
            throw new AiCallException("Spring AI ChatModel 构建失败：请检查 Base URL / Key / 模型名（" + e.getMessage() + "）");
        }
    }
}
