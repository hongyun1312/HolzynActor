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

package com.holzyn.actor.ai.route;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场景-模型路由器。
 * <p>职责：把「场景 → (供应商选择规则 + 模型参数档位)」的路由决策统一收敛，供
 * {@code AiSpringChatGateway} 在每次 AI 调用时按当前场景（{@code AiCallContext} 线程上下文）
 * 解析生效档位并应用到请求：</p>
 * <ul>
 * <li><b>参数档位</b>：{@link #resolve}——场景覆盖 &gt; 类别档位 &gt; 全局默认；
 * {@link #applyToRequest} 仅在调用方未显式设置温度/长度时补档位值（显式调用零侵入）；</li>
 * <li><b>供应商规则</b>：{@link #effectiveProviderId}——调用方显式 providerId 优先，
 * 否则路由场景覆盖的 provider-id（预留，当前全指默认 chat provider=本地 Qwen3.6-35B），
 * 再否则默认 chat provider；</li>
 * <li><b>运行时覆盖表</b>：调试页热更新（内存态，重启还原 yml），优先级高于 yml 场景覆盖；</li>
 * <li><b>生效表</b>：{@link #effectiveTable} 全场景生效档位导出（调试页预览，含来源标记）。</li>
 * </ul>
 * <p>职责边界：本对象只做路由解析（纯读配置 + 记录覆盖），供应商凭据解析复用
 * {@link ModelApiService#resolveProviderConfig}（项目级优先、用户级回退、绝不选中
 * embedding 专用供应商）。judge 调用 独立于本路由，不参与。</p>
 */
@Slf4j
@Component
public class SceneRouter {

    /** 来源标记（调试页展示优先级来源） */
    public static final String SOURCE_RUNTIME = "runtime";
    public static final String SOURCE_SCENE = "scene";
    public static final String SOURCE_TIER = "tier";
    public static final String SOURCE_DEFAULT = "default";

    private final SceneRoutingProperties props;
    private final ModelApiService modelApiService;

    /** 运行时覆盖表（scene → 覆盖；热更新，重启还原） */
    private final Map<String, SceneRoutingProperties.SceneRouteOverride> runtimeOverrides = new ConcurrentHashMap<>();

    public SceneRouter(SceneRoutingProperties props, ModelApiService modelApiService) {
        this.props = props;
        this.modelApiService = modelApiService;
    }

    /** 路由总开关（false=网关按调用方原样参数与默认供应商调用）。 */
    public boolean enabled() {
        return props.isEnabled();
    }

    /**
     * 解析场景的生效路由（覆盖 &gt; 类别 &gt; 全局默认）。
     *
     * @param scene 场景（null/空 → 全局默认）
     *
     * @return 生效档位（温度/长度/供应商 + 来源标记）
     */
    public Route resolve(String scene) {
        String safe = scene == null || scene.isBlank() ? "default" : scene;
        SceneRoutingProperties.SceneRouteOverride runtime = runtimeOverrides.get(safe);
        if (runtime != null) {
            return merge(runtime, SOURCE_RUNTIME, null);
        }
        SceneRoutingProperties.SceneRouteOverride sceneOverride = props.getScenes().get(safe);
        if (sceneOverride != null) {
            return merge(sceneOverride, SOURCE_SCENE, null);
        }
        String category = props.getCategoryMap().get(safe);
        SceneRoutingProperties.Tier tier = category == null ? null : props.getTiers().get(category);
        if (tier != null) {
            return new Route(tier.getTemperature(), tier.getMaxTokens(), null, SOURCE_TIER, category);
        }
        SceneRoutingProperties.Tier def = props.getDefaultTier();
        return new Route(def == null ? null : def.getTemperature(), def == null ? null : def.getMaxTokens(),
                null, SOURCE_DEFAULT, null);
    }

    private Route merge(SceneRoutingProperties.SceneRouteOverride o, String source, String category) {
        SceneRoutingProperties.Tier def = props.getDefaultTier();
        Double temperature = o.getTemperature() != null ? o.getTemperature()
                : (def == null ? null : def.getTemperature());
        Integer maxTokens = o.getMaxTokens() != null ? o.getMaxTokens()
                : (def == null ? null : def.getMaxTokens());
        return new Route(temperature, maxTokens, o.getProviderId(), source, category);
    }

    /**
     * 把路由档位应用到请求（<b>路由优先</b>——档位值无条件覆盖调用方参数，
     * 统一全场景采样策略，让档位真正生效；档位字段为空时保留调用方原值）。
     *
     * @param request 原始请求
     *
     * @param route 路由档位（可空=不应用）
     *
     * @return 应用后的请求（无变化时返回原对象）
     */
    public AiChatRequest applyToRequest(AiChatRequest request, Route route) {
        if (request == null || route == null) {
            return request;
        }
        Double temperature = route.temperature() != null ? route.temperature() : request.temperature();
        Integer maxTokens = route.maxTokens() != null ? route.maxTokens() : request.maxTokens();
        if (temperature == null && maxTokens == null) {
            return request;
        }
        return new AiChatRequest(request.model(), request.messages(), temperature, maxTokens, request.jsonMode());
    }

    /**
     * 解析生效供应商主键：调用方显式指定优先 → 路由场景覆盖 provider-id → 默认（null）。
     *
     * @param callerProviderId 调用方显式 providerId（可空）
     *
     * @param route 路由档位（可空）
     *
     * @return 生效 providerId（null=默认 chat provider）
     */
    public Long effectiveProviderId(Long callerProviderId, Route route) {
        if (callerProviderId != null) {
            return callerProviderId;
        }
        return route != null && route.providerId() != null ? route.providerId() : null;
    }

    /**
     * 解析供应商运行时凭据（经 {@link ModelApiService}，项目级优先、用户级回退）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（可空）
     *
     * @param providerId 生效供应商主键（可空=默认）
     *
     * @return 含解密 Key 的运行时配置
     */
    public ProviderConfig resolveProviderConfig(Long userId, Long projectId, Long providerId) {
        return modelApiService.resolveProviderConfig(userId, projectId, providerId);
    }

    // ==================== 运行时覆盖表（热更新） ====================

    /**
     * 设置单场景运行时路由覆盖（优先级最高；null=清空该场景）。
     *
     * @param scene 场景
     *
     * @param override 覆盖参数（任一字段为空=沿用类别/默认）
     */
    public void putOverride(String scene, SceneRoutingProperties.SceneRouteOverride override) {
        if (scene == null || scene.isBlank()) {
            throw new IllegalArgumentException("场景不能为空");
        }
        if (override == null) {
            runtimeOverrides.remove(scene);
            return;
        }
        runtimeOverrides.put(scene, override);
    }

    /** 移除单场景运行时覆盖。 */
    public void removeOverride(String scene) {
        if (scene != null) {
            runtimeOverrides.remove(scene);
        }
    }

    /** 运行时覆盖表快照。 */
    public Map<String, SceneRoutingProperties.SceneRouteOverride> overrides() {
        return new LinkedHashMap<>(runtimeOverrides);
    }

    // ==================== 生效表（调试页预览） ====================

    /**
     * 全场景生效档位表（调试页预览）：覆盖/类别/默认合并后的最终值与来源标记，
     * 按场景名排序；含类别摘要与默认回退行。
     *
     * @return 生效表（每行=场景 + 类别 + 温度/长度/供应商 + 来源）
     */
    public List<EffectiveRow> effectiveTable() {
        Map<String, EffectiveRow> rows = new LinkedHashMap<>();
        // 已知场景 = categoryMap 键 ∪ scenes 键 ∪ runtimeOverrides 键
        java.util.Set<String> scenes = new java.util.LinkedHashSet<>(props.getCategoryMap().keySet());
        scenes.addAll(props.getScenes().keySet());
        scenes.addAll(runtimeOverrides.keySet());
        List<EffectiveRow> out = new ArrayList<>();
        for (String scene : scenes) {
            Route r = resolve(scene);
            out.add(new EffectiveRow(scene, r.category() == null ? "-" : r.category(),
                    r.temperature(), r.maxTokens(), r.providerId(), r.source()));
        }
        Route def = resolve(null);
        out.add(new EffectiveRow("(default)", "-", def.temperature(), def.maxTokens(), def.providerId(),
                SOURCE_DEFAULT));
        out.sort(Comparator.comparing(EffectiveRow::scene));
        return out;
    }

    /**
     * 生效档位（解析结果）。
     *
     * @param temperature 采样温度（可空）
     *
     * @param maxTokens 最大输出 token（可空）
     *
     * @param providerId 供应商主键（可空=默认）
     *
     * @param source 来源标记（runtime/scene/tier/default）
     *
     * @param category 命中的类别（无=null）
     */
    public record Route(Double temperature, Integer maxTokens, Long providerId, String source, String category) {
    }

    /**
     * 生效表行（调试页 VO）。
     *
     * @param scene 场景
     *
     * @param category 类别
     *
     * @param temperature 温度（可空）
     *
     * @param maxTokens 长度（可空）
     *
     * @param providerId 供应商（可空）
     *
     * @param source 来源
     */
    public record EffectiveRow(String scene, String category, Double temperature, Integer maxTokens,
                               Long providerId, String source) {
    }
}
