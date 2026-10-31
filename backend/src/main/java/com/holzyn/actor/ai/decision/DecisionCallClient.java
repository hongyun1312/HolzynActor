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

package com.holzyn.actor.ai.decision;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.ai.decision.adapter.DecisionAdapter;
import com.holzyn.actor.ai.decision.adapter.DecisionAdapters;
import com.holzyn.actor.ai.decision.local.DecisionLocalRuntime;
import com.holzyn.actor.ai.decision.protocol.DecisionProtocol;
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
import java.util.Optional;

/**
 * 决策模型统一调用客户端（<b>适配器驱动</b>）。
 *
 * <p><b>定位</b>：决策模型是 System One 判定层——<b>不吃 messages、不生成文字</b>，
 * 输入 {@code state + questions}（choice/boolean/score 三题型）、输出完整概率分布、零解码。
 * 本客户端旁路 chat 契约（不复用 {@code AiSpringChatGateway} 的 messages 路径）。</p>
 *
 * <p><b>分层（本类只做编排）</b>：</p>
 * <ol>
 *   <li>{@link DecisionAdapters}：按配置 {@code adapter} 选一家模型；</li>
 *   <li>{@link DecisionProtocol}：报文形状（批量 / 单 state 两族，见 protocol 包）；</li>
 *   <li>本类：分批、逐批 HTTP、重试、静默回退防护、trace/用量记账、fail-closed；</li>
 *   <li>{@link DecisionLocalRuntime}：{@code mode=local} 时提供本机托管进程的端点。</li>
 * </ol>
 *
 * <p><b>fail-closed 回退骨架</b>：任何异常（未启用/未配置/网络/超时/HTTP 非 200/
 * 契约校验失败/本地进程未就绪）→ 返回 {@code null}（或空列表），<b>调用方按三级回退处置</b>
 * （同题文本模型重问 → 程序化默认值）——决策模型只是加速层，永不卡死决策链路。</p>
 *
 * <p><b>架构不变量</b>：本类公开 AI 调用方法一律<b>不带 {@code @Transactional}</b>
 * （AiCallOutsideTransactionTest 反射断言守护）。</p>
 */
@Slf4j
@Component
public class DecisionCallClient {

    /** Jackson 序列化器（健康检查模型列表解析） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 用户选择缓存 TTL（毫秒）：设置页改完 2 秒内生效，避免每次判定都读盘 */
    private static final long SELECTION_TTL_MS = 2000L;

    /** 决策模型配置（@Component + @ConfigurationProperties；可空=未装配视为禁用） */
    @Autowired(required = false)
    private DecisionProperties properties;

    /** 适配器注册表（Spring 收集全部实现） */
    @Autowired(required = false)
    private DecisionAdapters adapters;

    /** 本地托管运行时（mode=local 时提供端点；可空=未装配） */
    @Autowired(required = false)
    private DecisionLocalRuntime localRuntime;

    /** 本地选择存储（selection.json：用户在设置页选的形态与适配器；可空=未装配） */
    @Autowired(required = false)
    private com.holzyn.actor.ai.decision.local.DecisionLocalStore localStore;

    /** 主 AI 供应商配置服务（API Key 来源：复用当前文本模型供应商） */
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

    /** 选择缓存时间戳 */
    private volatile long selectionCachedAt;

    /** 选择缓存 */
    private volatile com.holzyn.actor.ai.decision.local.DecisionLocalStore.Selection selectionCache;

    /** HTTP 执行器（默认 JDK HttpClient；单测注入内存 fake） */
    private DecisionHttpExecutor executor = new DecisionHttpExecutor.HttpDecisionExecutor();

    // ==================== 状态查询（设置页/调试页/门面共用） ====================

    /** 当前适配器（未装配注册表时返回 null）。 */
    public DecisionAdapter adapter() {
        String selected = selectedAdapterId();
        String effective = selected == null || selected.isBlank()
                ? (properties == null ? null : properties.getAdapter()) : selected;
        return adapters == null ? null : adapters.resolve(effective);
    }

    /** 当前适配器 ID（未装配返回空串）。 */
    public String adapterId() {
        DecisionAdapter a = adapter();
        return a == null ? "" : a.id();
    }

    /** 当前线协议 ID（未装配返回空串）。 */
    public String protocolId() {
        DecisionAdapter a = adapter();
        return a == null || a.protocol() == null ? "" : a.protocol().id();
    }

    /** 当前运行形态（api / local）。 */
    public String mode() {
        String selected = selectedMode();
        if (selected != null && !selected.isBlank()) {
            return selected;
        }
        return properties == null ? "api" : properties.getMode();
    }

    /**
     * 用户在设置页保存的适配器选择（selection.json；空=未选过）。
     * <p>为什么读文件而不是读配置：桌面端用户没有能力改 yml/env，设置页必须能即时生效；
     * 2 秒 TTL 缓存避免每次判定都读盘（判定调用频率高，文件只在小配置改动时变化）。</p>
     */
    private String selectedAdapterId() {
        com.holzyn.actor.ai.decision.local.DecisionLocalStore.Selection s = selection();
        return s == null ? null : s.getAdapter();
    }

    private String selectedMode() {
        com.holzyn.actor.ai.decision.local.DecisionLocalStore.Selection s = selection();
        return s == null ? null : s.getMode();
    }

    /**
     * 远程形态的服务地址：设置页保存的覆盖值优先，空则回落配置文件 base-url。
     *
     * <p>为什么不能只读配置：桌面端用户改不了 yml/env，设置页里填了地址、点了「保存并启用」，
     * 真实请求就必须打到那个地址——否则这个开关只是记了个字符串，判定永远打到旧的默认端点。</p>
     */
    private String selectedBaseUrl() {
        com.holzyn.actor.ai.decision.local.DecisionLocalStore.Selection s = selection();
        if (s != null && s.getBaseUrl() != null && !s.getBaseUrl().isBlank()) {
            return s.getBaseUrl().trim();
        }
        return properties == null ? null : properties.getBaseUrl();
    }

    private com.holzyn.actor.ai.decision.local.DecisionLocalStore.Selection selection() {
        if (localStore == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - selectionCachedAt < SELECTION_TTL_MS) {
            return selectionCache;
        }
        try {
            selectionCache = localStore.readSelection();
        } catch (Exception e) {
            selectionCache = null;
        }
        selectionCachedAt = now;
        return selectionCache;
    }

    /** 是否可用（总开关 + 目标端点可解析）。 */
    public boolean isEnabled() {
        if (properties == null || !properties.isEnabled()) {
            return false;
        }
        return resolveEndpoint(null) != null;
    }

    /** 生效 base-url（本地形态返回本地端点；不可解析返回空串）。 */
    public String baseUrl() {
        DecisionEndpoint ep = resolveEndpoint(null);
        return ep == null ? "" : ep.baseUrl();
    }

    /**
     * 全量评估入口：按协议形态分批（批量协议按四轴上限切分；单 state 协议逐 state 调用后合并）。
     *
     * @param userId 归属用户 ID（可空=取当前用户）
     *
     * @param projectId 项目 ID（可空；trace/用量归属）
     *
     * @param characterId 角色 ID（可空；trace/用量归属）
     *
     * @param scene 场景码（trace 归属）
     *
     * @param states state 列表（非空）
     *
     * @return 每批一个响应；单 state 协议返回合并后的单响应；未启用/失败返回 null
     */
    public List<DecisionResponse> evaluate(Long userId, Long projectId, Long characterId,
                                                  String scene, List<DecisionState> states) {
        return evaluateInternal(userId, projectId, characterId, scene, states, null);
    }

    /**
     * 单批便捷入口：返回首个响应。
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
    public DecisionResponse evaluateOnce(Long userId, Long projectId, Long characterId,
                                                String scene, List<DecisionState> states) {
        List<DecisionResponse> list = evaluateInternal(userId, projectId, characterId, scene, states, null);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /**
     * 指定 base-url 的评估（调试页「不可达地址回退链路」验证用）。
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
     * @param overrideBaseUrl 覆盖 base-url（可空=用配置值）
     *
     * @return 首个响应；未启用/失败返回 null（fail-closed）
     */
    public DecisionResponse evaluateWithOverride(Long userId, Long projectId, Long characterId,
                                                        String scene, List<DecisionState> states, String overrideBaseUrl) {
        List<DecisionResponse> list = evaluateInternal(userId, projectId, characterId, scene, states,
                overrideBaseUrl);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /**
     * 健康检查：优先打协议健康端点；批量协议额外在模型列表里确认模型名。
     *
     * @param userId 归属用户 ID（可空）
     *
     * @param projectId 项目 ID（可空）
     *
     * @return 结果 Map（ready/reason/enabled/baseUrl/adapter/protocol/status/latencyMs/models）
     */
    public Map<String, Object> healthCheck(Long userId, Long projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        DecisionAdapter adapter = adapter();
        DecisionEndpoint endpoint = resolveEndpoint(null);
        out.put("adapter", adapter == null ? "" : adapter.id());
        out.put("protocol", adapter == null || adapter.protocol() == null ? "" : adapter.protocol().id());
        out.put("mode", mode());
        if (adapter == null) {
            out.put("ready", false);
            out.put("enabled", false);
            out.put("reason", "决策模型适配器未装配");
            return out;
        }
        if (properties == null || !properties.isEnabled()) {
            out.put("ready", false);
            out.put("enabled", false);
            out.put("reason", "决策模型未启用（enabled=false）");
            return out;
        }
        if (endpoint == null) {
            out.put("ready", false);
            out.put("enabled", true);
            out.put("reason", "local".equalsIgnoreCase(mode())
                    ? "本地决策模型未就绪（未安装或未启动）"
                    : "未配置决策模型 base-url");
            return out;
        }
        out.put("enabled", true);
        out.put("baseUrl", endpoint.baseUrl());
        out.put("samplingMode", properties.getSampling() == null ? "sample" : properties.getSampling().getMode());
        String healthPath = adapter.protocol() == null ? null : adapter.protocol().healthPath();
        if (healthPath == null || healthPath.isBlank()) {
            out.put("ready", true);
            out.put("reason", "ok（协议无独立健康端点，按端点可解析判定）");
            return out;
        }
        try {
            Long uid = userId;
            if (uid == null && currentUserProvider != null) {
                uid = currentUserProvider.currentUserId();
            }
            String key = resolveApiKey(uid, projectId, false);
            DecisionHttpExecutor.HttpResult r = executor.getJson(
                    endpoint.baseUrl() + healthPath, key,
                    properties.getTimeoutConnectSeconds(), properties.getTimeoutReadSeconds());
            out.put("status", r.status());
            out.put("latencyMs", r.durationMs());
            if (r.status() == 200) {
                out.put("ready", true);
                out.put("reason", "ok");
                return out;
            }
            out.put("ready", false);
            out.put("reason", "健康端点 HTTP " + r.status() + "（" + endpoint.baseUrl() + healthPath + "）");
            return out;
        } catch (Exception e) {
            out.put("ready", false);
            out.put("reason", "健康检查失败：" + e.getMessage());
            return out;
        }
    }

    // ==================== 端点解析 ====================

    /**
     * 解析本次调用的端点（overrideBaseUrl 仅调试用）。
     *
     * @param overrideBaseUrl 覆盖 base-url（可空）
     *
     * @return 端点；不可解析返回 null（调用方 fail-closed）
     */
    private DecisionEndpoint resolveEndpoint(String overrideBaseUrl) {
        DecisionAdapter adapter = adapter();
        if (adapter == null || properties == null) {
            return null;
        }
        String modelId = properties.getModelId() == null || properties.getModelId().isBlank()
                ? adapter.defaultModelId() : properties.getModelId().trim();
        if (overrideBaseUrl != null && !overrideBaseUrl.isBlank()) {
            return new DecisionEndpoint(adapter.id(), DecisionEndpoint.trimSlash(overrideBaseUrl),
                    evaluatePathOf(adapter), modelId, false);
        }
        if ("local".equalsIgnoreCase(mode())) {
            if (localRuntime == null) {
                log.warn("[Decision] mode=local 但本地运行时未装配，判定 fail-closed");
                return null;
            }
            Optional<DecisionEndpoint> ep = localRuntime.activeEndpoint();
            if (ep.isEmpty()) {
                log.warn("[Decision] mode=local 但本地决策模型未就绪（未安装/未启动/启动失败），判定 fail-closed");
                return null;
            }
            DecisionEndpoint local = ep.get();
            return new DecisionEndpoint(adapter.id(), local.baseUrl(), local.evaluatePath(), modelId, true);
        }
        String base = selectedBaseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        return new DecisionEndpoint(adapter.id(), DecisionEndpoint.trimSlash(base),
                evaluatePathOf(adapter), modelId, false);
    }

    /** evaluate 路径：配置覆盖 > 适配器默认 > 协议默认。 */
    private String evaluatePathOf(DecisionAdapter adapter) {
        String configured = properties == null ? null : properties.getEvaluatePath();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String def = adapter.defaultEvaluatePath();
        if (def != null && !def.isBlank()) {
            return def;
        }
        return adapter.protocol() == null ? "" : adapter.protocol().defaultEvaluatePath();
    }

    // ==================== 内部实现 ====================

    /** 评估主流程（overrideBaseUrl 仅调试冒烟用）。 */
    private List<DecisionResponse> evaluateInternal(Long userId, Long projectId, Long characterId,
                                                           String scene, List<DecisionState> states,
                                                           String overrideBaseUrl) {
        DecisionAdapter adapter = adapter();
        if (adapter == null || properties == null || !properties.isEnabled()) {
            log.warn("[Decision] 未启用（enabled=false 或适配器未装配），判定 fail-closed 回退：scene={}", scene);
            return null;
        }
        DecisionProtocol protocol = adapter.protocol();
        if (protocol == null) {
            log.warn("[Decision] 适配器 {} 未绑定协议，判定 fail-closed 回退：scene={}", adapter.id(), scene);
            return null;
        }
        DecisionEndpoint endpoint = resolveEndpoint(overrideBaseUrl);
        if (endpoint == null) {
            return null;
        }
        if (states == null || states.isEmpty()) {
            log.warn("[Decision] 空 states，fail-closed 回退：scene={}", scene);
            return null;
        }
        Long uid = userId;
        if (uid == null && currentUserProvider != null) {
            uid = currentUserProvider.currentUserId();
        }
        if (uid == null) {
            log.warn("[Decision] 无法解析用户，fail-closed 回退：scene={}", scene);
            return null;
        }
        ProviderConfig keySource;
        try {
            // 本地形态/无 Key 网关：不发 Authorization，也不解析主供应商（避免连带失效）
            keySource = isDedicatedApiKey() || endpoint.local() ? null
                    : modelApiService.resolveProviderConfig(uid, projectId, null);
        } catch (Exception e) {
            log.warn("[Decision] 解析文本模型供应商 Key 失败（fail-closed 回退）：scene={} 原因={}",
                    scene, e.getMessage());
            return null;
        }
        // 分批：批量协议按四轴切分；单 state 协议每个 state 一次调用（在单次 trace 内合并）
        List<List<DecisionState>> chunks = protocol.batched()
                ? DecisionBatchSplitter.split(states, properties)
                : states.stream().map(List::of).toList();
        List<DecisionResponse> out = new ArrayList<>();
        for (List<DecisionState> chunk : chunks) {
            DecisionResponse resp;
            try {
                resp = callChunk(uid, projectId, characterId, scene, chunk, keySource, endpoint, adapter, protocol);
            } catch (Exception e) {
                log.warn("[Decision] 批调用失败（fail-closed 回退）：scene={} 原因={}", scene, e.getMessage());
                return null;
            }
            if (resp == null) {
                return null;
            }
            out.add(resp);
        }
        if (!protocol.batched() && out.size() > 1) {
            return List.of(DecisionResponse.merged(out, null));
        }
        return out;
    }

    /** 单块调用（含 trace 记账 + 重试 + 静默回退防护 + fail-closed）。 */
    private DecisionResponse callChunk(Long uid, Long projectId, Long characterId, String scene,
                                       List<DecisionState> chunk, ProviderConfig keySource,
                                       DecisionEndpoint endpoint, DecisionAdapter adapter,
                                       DecisionProtocol protocol) {
        return AiCallContext.run(scene, characterId, () -> {
            AiCallTrace trace = aiCallTraceService.begin(projectId);
            try {
                trace.setBaseUrl(endpoint.baseUrl());
                trace.setStream(false);
                trace.setModel(endpoint.modelId());
                trace.setProviderId(providerIdOf(keySource));
                trace.setDecisionParams(protocol.paramsSnapshot(chunk));
                long t0 = System.currentTimeMillis();
                List<DecisionResponse> parts = new ArrayList<>();
                StringBuilder rawJoined = new StringBuilder();
                for (List<DecisionState> single : chunkUnits(chunk, protocol)) {
                    String body = protocol.buildBody(single);
                    if (rawJoined.length() > 0) {
                        rawJoined.append('\n');
                    }
                    rawJoined.append(body);
                    DecisionHttpExecutor.HttpResult result = postWithRetry(scene, endpoint, keySource, body);
                    DecisionResponse parsed = protocol.parse(result.body(), single);
                    if (parsed == null) {
                        throw new AiCallException("决策模型响应解析失败（协议 " + protocol.id()
                                + " 非预期报文形状，端点 " + endpoint.evaluateUrl() + "）");
                    }
                    if (parsed.isChatFallback()) {
                        throw new AiCallException("决策模型静默回退检测：响应为 chat 形状（choices 字段）——"
                                + "请求被路由到生成模型而非决策模型，按失败处置（防假调用）");
                    }
                    if (!parsed.isForwardProof()) {
                        throw new AiCallException("决策模型静默回退检测：execution.forward_passes="
                                + parsed.execution().forwardPasses() + "（应为 1）/decode_steps="
                                + parsed.execution().decodeSteps() + "——响应不是决策模型一次前向，"
                                + "也不在确定性缓存命中路径（cache_hit=true 合法）");
                    }
                    parts.add(parsed);
                }
                long durationMs = System.currentTimeMillis() - t0;
                DecisionResponse resp = parts.size() == 1 ? parts.get(0)
                        : DecisionResponse.merged(parts, rawJoined.toString());
                trace.setInputRaw(rawJoined.toString());
                trace.setOutputRaw(resp.raw() == null ? "" : resp.raw());
                trace.markSuccess(resp.raw(), endpoint.modelId(), 0, 0, 0, 0, providerIdOf(keySource));
                recordUsage(uid, projectId, characterId, providerIdOf(keySource), scene, durationMs, endpoint.modelId());
                log.info("[Decision] adapter={} protocol={} local={} scene={} states={} questions={} paths={} "
                                + "forwardPasses={} cacheHit={} durationMs={}",
                        adapter.id(), protocol.id(), endpoint.local(), scene,
                        resp.execution().states(), resp.execution().questions(),
                        resp.execution().candidatePaths(), resp.execution().forwardPasses(),
                        resp.execution().cacheHit(), durationMs);
                return resp;
            } catch (Exception e) {
                trace.markFailure(e);
                log.warn("[Decision] 判定失败（fail-closed 回退）：scene={} 原因={}", scene, e.getMessage());
                throw e;
            } finally {
                aiCallTraceService.end(trace);
            }
        });
    }

    /** 单块内的调用单元：批量协议=整块一次；单 state 协议=逐 state。 */
    private static List<List<DecisionState>> chunkUnits(List<DecisionState> chunk, DecisionProtocol protocol) {
        if (protocol.batched()) {
            return List.of(chunk);
        }
        List<List<DecisionState>> units = new ArrayList<>(chunk.size());
        for (DecisionState s : chunk) {
            units.add(List.of(s));
        }
        return units;
    }

    /** 带重试的 POST（HTTP 非 200 抛 AiCallException，由上层 fail-closed）。 */
    private DecisionHttpExecutor.HttpResult postWithRetry(String scene, DecisionEndpoint endpoint,
                                                          ProviderConfig keySource, String body) {
        int attempts = 1 + Math.max(0, properties.getMaxRetries());
        DecisionHttpExecutor.HttpResult result = null;
        Exception lastError = null;
        for (int i = 0; i < attempts && result == null; i++) {
            try {
                result = executor.postJson(endpoint.evaluateUrl(), effectiveApiKey(keySource, endpoint.local()),
                        body, properties.getTimeoutConnectSeconds(), properties.getTimeoutReadSeconds());
            } catch (Exception e) {
                lastError = e;
                log.warn("[Decision] 第 {} 次调用异常（将重试/回退）：scene={} 原因={}", i + 1, scene, e.getMessage());
            }
        }
        if (result == null) {
            throw new AiCallException("决策模型调用失败（重试 " + attempts + " 次后仍失败）："
                    + (lastError == null ? "未知" : lastError.getMessage()));
        }
        if (result.status() != 200) {
            throw new AiCallException("决策模型调用失败：HTTP " + result.status()
                    + "（端点 " + endpoint.evaluateUrl() + "；批上限/服务未就绪/鉴权失败均可导致）");
        }
        return result;
    }

    /** 用量入账（0 token；失败仅告警）。 */
    private void recordUsage(Long uid, Long projectId, Long characterId, Long providerId, String scene,
                             long durationMs, String modelId) {
        if (usageLogService == null) {
            return;
        }
        try {
            usageLogService.record(uid, projectId, characterId, providerId, modelId,
                    scene, 0, 0, 0, 0, (int) Math.min(Integer.MAX_VALUE, durationMs));
        } catch (Exception e) {
            log.warn("[Decision] 用量入账失败（已忽略）：scene={} 原因={}", scene, e.getMessage());
        }
    }

    /** 解析 API Key（专用 Key 优先；否则复用当前文本模型供应商；失败由调用方 fail-closed）。 */
    private String resolveApiKey(Long userId, Long projectId, boolean local) {
        if (local) {
            return null;
        }
        String dedicated = properties.getApiKey();
        if (dedicated != null && !dedicated.isBlank()) {
            return dedicated.trim();
        }
        ProviderConfig config = modelApiService.resolveProviderConfig(userId, projectId, null);
        if (config == null || config.apiKey() == null || config.apiKey().isBlank()) {
            throw new AiCallException("文本模型供应商未配置 API Key（决策模型与其同 Key）");
        }
        return config.apiKey();
    }

    /**
     * 生效的决策模型 API Key（专用配置优先，缺省沿用文本模型供应商 Key；本地形态不鉴权）。
     *
     * @param keySource 已解析的供应商凭据（可空=专用 Key/本地形态）
     *
     * @param local 是否本地托管端点
     *
     * @return 生效 Key（可空）
     */
    private String effectiveApiKey(ProviderConfig keySource, boolean local) {
        if (local) {
            return null;
        }
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

    /** 供应商主键（专用 Key/本地形态未解析主供应商 → null，记账字段可空）。 */
    private static Long providerIdOf(ProviderConfig keySource) {
        return keySource == null ? null : keySource.id();
    }

    /** 测试注入 HTTP 执行器（包级可见，单测用内存 fake）。 */
    void setExecutor(DecisionHttpExecutor executor) {
        this.executor = executor;
    }

    /** 健康检查用：模型列表解析（保留给调试页展示可用模型名）。 */
    static List<String> parseModelIds(String body) {
        List<String> models = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode data = root.get("data");
            if (data != null && data.isArray()) {
                for (JsonNode m : data) {
                    String id = m.path("id").asText();
                    if (!id.isBlank()) {
                        models.add(id);
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败返回空列表（调用方按不可用处置）
        }
        return models;
    }
}
