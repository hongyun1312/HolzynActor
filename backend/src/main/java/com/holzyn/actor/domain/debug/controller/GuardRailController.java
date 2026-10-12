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

import com.holzyn.actor.ai.guard.GuardRailPolicy;
import com.holzyn.actor.ai.guard.GuardRailProperties;
import com.holzyn.actor.ai.guard.GuardRailTracker;
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
import java.util.Map;

/**
 * 统一护栏调试接口。
 * <p>职责：护栏状态观测 + 运行时覆盖表热更新——</p>
 * <ul>
 * <li>GET /status：总开关/生效默认参数/运行时覆盖表/各场景统计/最近告警；</li>
 * <li>GET|PUT|DELETE /overrides[/{scene}]：单场景护栏参数热更新（内存态，重启还原 yml，
 * 优先级高于 yml scenes 表）。</li>
 * </ul>
 * <p>；仅内部调试，无鉴权，勿公网暴露）</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/guard-rails")
@RequiredArgsConstructor
public class GuardRailController {

    private final GuardRailPolicy guardRail;
    private final GuardRailTracker guardTracker;

    /**
     * 护栏状态聚合：开关/生效默认参数/运行时覆盖/统计/告警。
     *
     * @return 状态 Map
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", guardRail.enabled());
        GuardRailPolicy.Effective def = guardRail.effective(null);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("maxRetries", def.maxRetries());
        defaults.put("minOutputChars", def.minOutputChars());
        defaults.put("duplicateEnabled", def.duplicateEnabled());
        defaults.put("duplicateWindow", def.duplicateWindow());
        defaults.put("duplicateDiceThreshold", def.duplicateDiceThreshold());
        defaults.put("maxConsecutiveFailures", def.maxConsecutiveFailures());
        out.put("defaults", defaults);
        out.put("runtimeOverrides", guardTracker.overrides());
        Map<String, Object> stats = new LinkedHashMap<>();
        guardTracker.stats().forEach((scene, c) -> stats.put(scene, Map.of(
                "retries", c.retries(),
                "blankOutputs", c.blankOutputs(),
                "duplicates", c.duplicates(),
                "failures", c.failures(),
                "timeouts", c.timeouts(),
                "degraded", c.degraded())));
        out.put("stats", stats);
        out.put("recentAlerts", guardTracker.recentAlerts());
        return out;
    }

    /** 运行时覆盖表（GET）。 */
    @GetMapping("/overrides")
    public Map<String, GuardRailProperties.SceneGuard> overrides() {
        return guardTracker.overrides();
    }

    /**
     * 运行时覆盖设置（PUT；任一字段缺省=沿用默认/yml 场景值）。
     *
     * @return 设置后的覆盖表
     */
    @PutMapping("/overrides")
    public Map<String, GuardRailProperties.SceneGuard> putOverride(@RequestBody Map<String, Object> body) {
        Object sceneObj = body.get("scene");
        if (sceneObj == null || String.valueOf(sceneObj).isBlank()) {
            throw new IllegalArgumentException("scene 不能为空");
        }
        String scene = String.valueOf(sceneObj).trim();
        GuardRailProperties.SceneGuard guard = new GuardRailProperties.SceneGuard();
        guard.setMaxRetries(intOrNull(body.get("maxRetries")));
        guard.setMinOutputChars(intOrNull(body.get("minOutputChars")));
        guard.setDuplicateEnabled(boolOrNull(body.get("duplicateEnabled")));
        guard.setDuplicateWindow(intOrNull(body.get("duplicateWindow")));
        guard.setDuplicateDiceThreshold(doubleOrNull(body.get("duplicateDiceThreshold")));
        guard.setMaxConsecutiveFailures(intOrNull(body.get("maxConsecutiveFailures")));
        guardTracker.putOverride(scene, guard);
        log.info("[护栏] 运行时覆盖设置 scene={} guard={}", scene, guard);
        return guardTracker.overrides();
    }

    /** 运行时覆盖移除（DELETE；回到 yml/默认）。 */
    @DeleteMapping("/overrides/{scene}")
    public Map<String, GuardRailProperties.SceneGuard> removeOverride(@PathVariable("scene") String scene) {
        guardTracker.removeOverride(scene);
        log.info("[护栏] 运行时覆盖移除 scene={}", scene);
        return guardTracker.overrides();
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

    private static Boolean boolOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        return Boolean.valueOf(String.valueOf(o).trim());
    }
}
