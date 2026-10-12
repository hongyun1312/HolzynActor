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

package com.holzyn.actor.domain.debug.controller;

import com.holzyn.actor.ai.route.SceneRouter;
import com.holzyn.actor.ai.route.SceneRoutingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景-模型路由调试接口。
 * <p>职责：路由生效表预览 + 运行时覆盖表热更新——</p>
 * <ul>
 * <li>GET /effective：全场景生效档位表（场景/类别/温度/长度/供应商 + 来源标记 runtime|scene|tier|default）；</li>
 * <li>GET|PUT|DELETE /overrides[/{scene}]：单场景路由热更新（内存态，重启还原 yml，
 * 优先级高于 yml scenes 表）。</li>
 * </ul>
 * <p>；仅内部调试，无鉴权，勿公网暴露）</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/scene-routing")
@RequiredArgsConstructor
public class SceneRoutingController {

    private final SceneRouter sceneRouter;

    /**
     * 全场景生效档位表。
     */
    @GetMapping("/effective")
    public Map<String, Object> effective() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", sceneRouter.enabled());
        List<Map<String, Object>> rows = sceneRouter.effectiveTable().stream()
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("scene", r.scene());
                    row.put("category", r.category());
                    row.put("temperature", r.temperature());
                    row.put("maxTokens", r.maxTokens());
                    row.put("providerId", r.providerId());
                    row.put("source", r.source());
                    return row;
                })
                .toList();
        out.put("rows", rows);
        return out;
    }

    /** 运行时覆盖表（GET）。 */
    @GetMapping("/overrides")
    public Map<String, SceneRoutingProperties.SceneRouteOverride> overrides() {
        return sceneRouter.overrides();
    }

    /**
     * 运行时覆盖设置（PUT；任一字段缺省=沿用类别/默认档位）。
     *
     * @return 设置后的覆盖表
     */
    @PutMapping("/overrides")
    public Map<String, SceneRoutingProperties.SceneRouteOverride> putOverride(@RequestBody Map<String, Object> body) {
        Object sceneObj = body.get("scene");
        if (sceneObj == null || String.valueOf(sceneObj).isBlank()) {
            throw new IllegalArgumentException("scene 不能为空");
        }
        String scene = String.valueOf(sceneObj).trim();
        SceneRoutingProperties.SceneRouteOverride override = new SceneRoutingProperties.SceneRouteOverride(
                doubleOrNull(body.get("temperature")),
                intOrNull(body.get("maxTokens")),
                longOrNull(body.get("providerId")));
        sceneRouter.putOverride(scene, override);
        log.info("[场景路由] 运行时覆盖设置 scene={} override={}", scene, override);
        return sceneRouter.overrides();
    }

    /** 运行时覆盖移除（DELETE；回到 yml/类别/默认）。 */
    @DeleteMapping("/overrides/{scene}")
    public Map<String, SceneRoutingProperties.SceneRouteOverride> removeOverride(@PathVariable("scene") String scene) {
        sceneRouter.removeOverride(scene);
        log.info("[场景路由] 运行时覆盖移除 scene={}", scene);
        return sceneRouter.overrides();
    }

    private static Integer intOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        return Integer.valueOf(String.valueOf(o).trim());
    }

    private static Double doubleOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return Double.valueOf(String.valueOf(o).trim());
    }

    private static Long longOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(String.valueOf(o).trim());
    }
}
