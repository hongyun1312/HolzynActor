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

package com.holzyn.actor.ai.nanojev;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.debug.service.AiCallTrace;
import com.holzyn.actor.domain.debug.service.AiCallTraceService;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NanoJev 独立 evaluate client。
 * <p><b>定位（设计《N 轨道开发路径》§8）</b>：NanoJev 是 0.6B 并行决策模型（System One 判定层）
 * ——<b>不吃 messages、不生成文字</b>，输入 {@code state + questions}（choice/boolean/score 三题型）、
 * 输出完整概率分布，零解码。本客户端<b>旁路 chat 契约</b>（不复用 {@code AiSpringChatGateway} 的
 * messages 路径），唯一入口 = {@code POST /v1/nanojev/evaluate}。</p>
 * <p><b>⚠️ 静默回退防护</b>：{@code /v1/chat/completions} 传 {@code model=nanojev} 会静默
 * 回退 qwen3.6-35b-q4-un——本客户端只走 evaluate 端点，且每次响应断言
 * {@link NanoJevEvaluateResponse#isChatFallback()} 反证，防「以为在调 NanoJev 实际调 35B」。</p>
 * <p><b>fail-closed 回退骨架</b>：任何异常（未启用/未配置 Key/网络/超时/HTTP 非 200/
 * 契约校验失败/服务不可达）→ 本客户端返回 {@code null}（或空列表），<b>调用方回退 Qwen 或程序化</b>
 * ——NanoJev 只是加速层，永不卡死决策链路。</p>
 * <p><b>记账</b>：完整走 AiCallTrace（scene=nanojev_*、model=nanojev、
 * inputRaw/outputRaw=evaluate 请求/响应原文、token 0、detail_json 增 paramsSnapshot）+ usage_log
 * 0 token 行（请求数/耗时维度可见）。</p>
 * <p><b>供应商/鉴权</b>：base-url 走 {@link NanoJevProperties}；
 * API Key <b>优先</b>取 {@link NanoJevProperties#getApiKey()} 专用配置（非空即专用），为空时经
 * {@link ModelApiService#resolveProviderConfig} 复用当前 chat provider（与 Qwen 同网关同 Key，
 * 双环境跟随 DB 配置）。 起主模型 = DeepSeek 官方（外部网关），与 NanoJev 内网网关不同源，
 * 必须配置专用 Key，否则网关 401 → 全部判定 fail-closed 静默回退。</p>
 * <p><b>架构不变量</b>：本类公开 AI 调用方法一律<b>不带 {@code @Transactional}</b>
 * （AiCallOutsideTransactionTest 反射断言守护）；记账 trace 经 AiCallContext 上下文 + 异步落库。</p>
 */
@Slf4j
@Component
public class NanoJevEvaluateClient {

    /** Jackson 3 序列化器（健康检查模型列表解析） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** NanoJev 配置（@Component + @ConfigurationProperties 注册；可空=未装配视为禁用） */
    @Autowired(required = false)
    private NanoJevProperties properties;

    /** 主 AI 供应商配置服务（API Key 来源：复用当前 chat provider） */
    @Autowired(required = false)
    private ModelApiService modelApiService;

    /** AI 调用 trace 埋点服务（actor_ai_call_log 记账） */
    @Autowired(required = false)
    private AiCallTraceService aiCallTraceService;

    /** AI 用量日志服务（usage_log 记账，0 token） */
    @Autowired(required = false)
    private UsageLogService usageLogService;

    /** 当前用户提供者（单用户 id=1；用量/trace 归属） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /** HTTP 执行器（默认 JDK HttpClient；单测注入内存 fake） */
    private NanoJevHttpExecutor executor = new NanoJevHttpExecutor.HttpNanoJevExecutor();

    /** 是否可用（enabled + base-url 已配置）。 */
    public boolean isEnabled() {
        return properties != null && properties.isEnabled()
                && properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank();
    }

    /** 配置的 base-url（未配置返回空串）。 */
    public String baseUrl() {
        return properties == null || properties.getBaseUrl() == null ? "" : properties.getBaseUrl();
    }

    /**
     * 全量评估入口：按批上限自动拆分 → 逐批调用 → 汇总响应。
     * <p><b>fail-closed</b>：任何一批失败即返回 {@code null}（不返回半截结果，避免调用方误用）；
     * 调用方据此回退 Qwen/程序化。</p>
     *
     * @param userId 归属用户 ID（可空=取当前用户）
     *
     * @param projectId 项目 ID（可空；trace/用量归属）
     *
     * @param characterId 角色 ID（可空；trace/用量归属）
     *
     * @param scene 场景码（nanojev_*，PromptSpecRegistry 已登记；trace 归属）
     *
     * @param states state 列表（非空）
     *
     * @return 每批一个响应（顺序与批序一致）；未启用/失败返回 null
     */
    public List<NanoJevEvaluateResponse> evaluate(Long userId, Long projectId, Long characterId,
                                                  String scene, List<NanoJevState> states) {
        return evaluateInternal(userId, projectId, characterId, scene, states, null);
    }

    /**
     * 单批便捷入口（单 state 或少批量场景）：返回首个响应。
     *
     * @param userId 归属用户 ID（可空）
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterId 角色 ID（可空）
     *
     * @param scene 场景码
     *
     * @param states state 列表
     *
     * @return 首个响应；未启用/失败返回 null
     */
    public NanoJevEvaluateResponse evaluateOnce(Long userId, Long projectId, Long characterId,
                                                String scene, List<NanoJevState> states) {
        List<NanoJevEvaluateResponse> list = evaluateInternal(userId, projectId, characterId, scene, states, null);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /**
     * 指定 base-url 的评估（调试冒烟用：不可达地址回退链路验证；仅测试门控接口调用）。
     *
     * @param userId 归属用户 ID（可空）
     *
     * @param projectId 项目 ID（可空）
     *
     * @param characterId 角色 ID（可空）
     *
     * @param scene 场景码
     *
     * @param states state 列表
     *
     * @param overrideBaseUrl 覆盖 base-url（可空=用配置值；仅调试冒烟调用）
     *
     * @return 首个响应；未启用/失败返回 null（fail-closed）
     */
    public NanoJevEvaluateResponse evaluateWithOverride(Long userId, Long projectId, Long characterId,
                                                        String scene, List<NanoJevState> states, String overrideBaseUrl) {
        List<NanoJevEvaluateResponse> list = evaluateInternal(userId, projectId, characterId, scene, states,
                overrideBaseUrl);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /**
     * 健康检查：GET {base}/v1/models 确认 nanojev 在列（防未部署/静默回退）。
     *
     * @param userId 归属用户 ID（可空）
     *
     * @param projectId 项目 ID（可空）
     *
     * @return 结果 Map（ready/models/latencyMs/error 字段；未启用返回 ready=false+reason）
     */
    public Map<String, Object> healthCheck(Long userId, Long projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!isEnabled()) {
            out.put("ready", false);
            out.put("reason", "nanojev 未启用（enabled=false 或未配置 base-url）");
            out.put("enabled", false);
            return out;
        }
        out.put("enabled", true);
        out.put("baseUrl", properties.getBaseUrl());
        out.put("samplingMode", properties.getSampling() == null ? "sample" : properties.getSampling().getMode());
        try {
            Long uid = userId;
            if (uid == null && currentUserProvider != null) {
                uid = currentUserProvider.currentUserId();
            }
            String key = resolveApiKey(uid, projectId);
            NanoJevHttpExecutor.HttpResult r = executor.getJson(
                    trimSlash(properties.getBaseUrl()) + NanoJevProperties.MODELS_PATH,
                    key, properties.getTimeoutConnectSeconds(), properties.getTimeoutReadSeconds());
            out.put("status", r.status());
            out.put("latencyMs", r.durationMs());
            if (r.status() != 200) {
                out.put("ready", false);
                out.put("reason", "模型列表 HTTP " + r.status());
                return out;
            }
            JsonNode root = MAPPER.readTree(r.body());
            boolean found = false;
            List<String> models = new ArrayList<>();
            JsonNode data = root.get("data");
            if (data != null && data.isArray()) {
                for (JsonNode m : data) {
                    String id = m.path("id").asText();
                    models.add(id);
                    if (NanoJevProperties.MODEL_ID.equals(id)) {
                        found = true;
                    }
                }
            }
            out.put("models", models);
            out.put("ready", found);
            out.put("reason", found ? "ok" : "模型列表未含 nanojev（请确认 HolzynLLMServer 已托管 NanoJev）");
            return out;
        } catch (Exception e) {
            out.put("ready", false);
            out.put("reason", "健康检查失败：" + e.getMessage());
            return out;
        }
    }

    // ==================== 内部实现 ====================

    /** 评估主流程（overrideBaseUrl 仅调试冒烟用）。 */
    private List<NanoJevEvaluateResponse> evaluateInternal(Long userId, Long projectId, Long characterId,
                                                           String scene, List<NanoJevState> states,
                                                           String overrideBaseUrl) {
        if (!isEnabled()) {
            log.warn("[NanoJev] 未启用（enabled=false 或未配置 base-url），判定 fail-closed 回退：scene={}", scene);
            return null;
        }
        if (states == null || states.isEmpty()) {
            log.warn("[NanoJev] 空 states，fail-closed 回退：scene={}", scene);
            return null;
        }
        Long uid = userId;
        if (uid == null && currentUserProvider != null) {
            uid = currentUserProvider.currentUserId();
        }
        if (uid == null) {
            log.warn("[NanoJev] 无法解析用户，fail-closed 回退：scene={}", scene);
            return null;
        }
        ProviderConfig keySource;
        try {
            // 专用 Key 场景
            // 无需解析主供应商：既避免无谓依赖，也避免主供应商异常导致 NanoJev 连带失效。
            keySource = isDedicatedApiKey() ? null : modelApiService.resolveProviderConfig(uid, projectId, null);
        } catch (Exception e) {
            log.warn("[NanoJev] 解析主 AI 供应商 Key 失败（fail-closed 回退 Qwen/程序化）：scene={} 原因={}",
                    scene, e.getMessage());
            return null;
        }
        List<List<NanoJevState>> batches = NanoJevBatchSplitter.split(states, properties);
        List<NanoJevEvaluateResponse> out = new ArrayList<>();
        for (List<NanoJevState> batch : batches) {
            NanoJevEvaluateResponse resp;
            try {
                resp = evaluateBatch(uid, projectId, characterId, scene, batch, keySource, overrideBaseUrl);
            } catch (Exception e) {
                // fail-closed：任何一批失败 → 整体返回 null，调用方回退（trace 已在批内标记 failed）
                log.warn("[NanoJev] 批调用失败（fail-closed 回退 Qwen/程序化）：scene={} 原因={}", scene, e.getMessage());
                return null;
            }
            if (resp == null) {
                return null;
            }
            out.add(resp);
        }
        return out;
    }

    /** 单批调用（含 trace 记账 + 重试 + 静默回退防护 + fail-closed）。 */
    private NanoJevEvaluateResponse evaluateBatch(Long uid, Long projectId, Long characterId, String scene,
                                                  List<NanoJevState> batch, ProviderConfig keySource,
                                                  String overrideBaseUrl) {
        return AiCallContext.run(scene, characterId, () -> {
            AiCallTrace trace = aiCallTraceService.begin(projectId);
            try {
                String base = (overrideBaseUrl != null && !overrideBaseUrl.isBlank())
                        ? trimSlash(overrideBaseUrl) : trimSlash(properties.getBaseUrl());
                trace.setBaseUrl(base);
                trace.setStream(false);
                trace.setModel(NanoJevProperties.MODEL_ID);
                trace.setProviderId(providerIdOf(keySource));
                NanoJevRequest request = new NanoJevRequest(batch);
                String reqJson = request.toJson();
                trace.setInputRaw(reqJson);
                trace.setNanojevParams(request.paramsSnapshot());
                long t0 = System.currentTimeMillis();
                int attempts = 1 + Math.max(0, properties.getMaxRetries());
                NanoJevHttpExecutor.HttpResult result = null;
                Exception lastError = null;
                for (int i = 0; i < attempts && result == null; i++) {
                    try {
                        result = executor.postJson(base + NanoJevProperties.EVALUATE_PATH, effectiveApiKey(keySource),
                                reqJson, properties.getTimeoutConnectSeconds(), properties.getTimeoutReadSeconds());
                    } catch (Exception e) {
                        lastError = e;
                        log.warn("[NanoJev] 第 {} 次调用异常（将重试/回退）：scene={} 原因={}", i + 1, scene, e.getMessage());
                    }
                }
                long durationMs = System.currentTimeMillis() - t0;
                if (result == null) {
                    throw new AiCallException("NanoJev 调用失败（重试 " + attempts + " 次后仍失败）："
                            + (lastError == null ? "未知" : lastError.getMessage()));
                }
                if (result.status() != 200) {
                    throw new AiCallException("NanoJev 调用失败：HTTP " + result.status()
                            + "（官方契约超限=500 Local demo limit / 服务未就绪）——请检查批上限与服务状态");
                }
                NanoJevEvaluateResponse resp = NanoJevEvaluateResponse.parse(result.body());
                if (resp == null) {
                    throw new AiCallException("NanoJev 响应解析失败（非官方契约形状）");
                }
                if (resp.isChatFallback()) {
                    throw new AiCallException("NanoJev 静默回退检测：响应为 chat 形状（choices 字段）——"
                            + "请求被路由到生成模型而非 NanoJev，按失败处置（防假调用）");
                }
                if (!resp.isForwardProof()) {
                    throw new AiCallException("NanoJev 静默回退检测：execution.forward_passes="
                            + resp.execution().forwardPasses() + "（应为 1）/decode_steps="
                            + resp.execution().decodeSteps() + "（应为 0）/cache_hit="
                            + resp.execution().cacheHit() + "——响应不是 NanoJev 一次前向，也不在"
                            + "确定性缓存命中路径（缓存命中 cache_hit=true + forward_passes=0 属合法，"
                            + "见 HolzynLLMServer《NanoJev并发故障分析与修复_V1.0.md》§10.2）");
                }
                trace.setOutputRaw(result.body());
                trace.markSuccess(result.body(), NanoJevProperties.MODEL_ID, 0, 0, 0, 0, providerIdOf(keySource));
                recordUsage(uid, projectId, characterId, providerIdOf(keySource), scene, durationMs);
                log.info("[NanoJev] scene={} states={} questions={} paths={} forwardPasses={} cacheHit={} durationMs={}",
                        scene, resp.execution().states(), resp.execution().questions(),
                        resp.execution().candidatePaths(), resp.execution().forwardPasses(),
                        resp.execution().cacheHit(), durationMs);
                return resp;
            } catch (Exception e) {
                trace.markFailure(e);
                log.warn("[NanoJev] 判定失败（fail-closed 回退 Qwen/程序化）：scene={} 原因={}", scene, e.getMessage());
                throw e;
            } finally {
                aiCallTraceService.end(trace);
            }
        });
    }

    /** 用量入账（0 token；失败仅告警）。 */
    private void recordUsage(Long uid, Long projectId, Long characterId, Long providerId, String scene,
                             long durationMs) {
        if (usageLogService == null) {
            return;
        }
        try {
            usageLogService.record(uid, projectId, characterId, providerId, NanoJevProperties.MODEL_ID,
                    scene, 0, 0, 0, 0, (int) Math.min(Integer.MAX_VALUE, durationMs));
        } catch (Exception e) {
            log.warn("[NanoJev] 用量入账失败（已忽略）：scene={} 原因={}", scene, e.getMessage());
        }
    }

    /** 解析 API Key（专用 Key 优先；否则复用当前 chat provider；失败抛 AiCallException 由调用方 fail-closed）。 */
    private String resolveApiKey(Long userId, Long projectId) {
        String dedicated = properties.getApiKey();
        if (dedicated != null && !dedicated.isBlank()) {
            return dedicated.trim();
        }
        ProviderConfig config = modelApiService.resolveProviderConfig(userId, projectId, null);
        if (config == null || config.apiKey() == null || config.apiKey().isBlank()) {
            throw new AiCallException("主 AI 供应商未配置 API Key（NanoJev 与其同 Key）");
        }
        return config.apiKey();
    }

    /**
     * 生效的 NanoJev API Key（专用配置优先，缺省沿用主 AI 供应商 Key）。
     * <p><b>为什么需要专用 Key</b>：NanoJev 的 base-url 是内网网关
     * （{@code 127.0.0.1:9100/v1}），而主模型切到 DeepSeek 官方（{@code api.deepseek.com}）后，
     * 官方 Key 在本网关无效 ——若继续复用，会把全部 NanoJev
     * 判定打成 fail-closed <b>静默</b>回退（16 个判定场景集体失效且无异常可见）。
     * 本方法默认行为与第 1/2 轮逐字节一致（专用 Key 为空时仍复用主供应商 Key）。</p>
     *
     * @param keySource 已解析的主 AI 供应商凭据（可空=专用 Key 已提供时无需解析）
     *
     * @return 生效 Key（可能为 null，由 HTTP 层/网关判 401）
     */
    private String effectiveApiKey(ProviderConfig keySource) {
        String dedicated = properties.getApiKey();
        if (dedicated != null && !dedicated.isBlank()) {
            return dedicated.trim();
        }
        return keySource == null ? null : keySource.apiKey();
    }

    /** 专用 Key 是否已配置（非空即启用，不再解析主供应商）。 */
    private boolean isDedicatedApiKey() {
        String dedicated = properties.getApiKey();
        return dedicated != null && !dedicated.isBlank();
    }

    /** 供应商主键（专用 Key 场景未解析主供应商 → null，记账字段可空）。 */
    private static Long providerIdOf(ProviderConfig keySource) {
        return keySource == null ? null : keySource.id();
    }

    /** 去尾部斜杠（URL 拼接防 //）。 */
    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
