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

package com.holzyn.actor.domain.worldmap.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.worldmap.service.MoveEncounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 途中交互（move_encounter）单发调试触发器（批3 决策与感知 · 途中交互实测载体）。
 * <p>用途：<b>世界暂停/冻结期间单个触发一次 move_encounter AI 调用</b>，读取实际的
 * AI 请求输入（段1A+1C 角色视角简报 + 段2D 当前世界动态 + 段2E 当前状态 + 模板）——
 * 供治理文档/验收核对真实装配，避免整世界推进时本地模型输出性能被占满。</p>
 * <p>语义：直接调用 {@link MoveEncounterService#maybeEncounter}（与引擎 tick 同入口，
 * 冷却/预算/附近命中逻辑一致，失败 fail-open）；异步虚拟线程执行，本接口立即返回。</p>
 * <p>入参：{projectId, npcRef, x, z, gameSecond?}（缺省 gameSecond=引擎当前游戏秒）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/move-encounter")
@RequiredArgsConstructor
public class MoveEncounterDebugController {

    private final MoveEncounterService moveEncounterService;
    private final WorldClockService worldClockService;

    /** 单发触发一次 move_encounter AI 判定（世界冻结可用；异步返回）。 */
    @PostMapping("/trigger")
    public Map<String, Object> trigger(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            throw new BizException(400, "请求体不能为空（{projectId, npcRef, x, z, gameSecond?}）");
        }
        Long projectId = num(body.get("projectId"));
        String npcRef = body.get("npcRef") == null ? null : String.valueOf(body.get("npcRef"));
        Double x = dbl(body.get("x"));
        Double z = dbl(body.get("z"));
        if (projectId == null || npcRef == null || npcRef.isBlank() || x == null || z == null) {
            throw new BizException(400, "projectId/npcRef/x/z 均不能为空");
        }
        Long gameSecond = body.get("gameSecond") == null
                ? worldClockService.currentEngineGameSecond(projectId) : num(body.get("gameSecond"));
        log.info("[途中交互-调试] 单发触发: project={} npcRef={} ({},{}) gameSecond={}",
                projectId, npcRef, x, z, gameSecond);
        // 与引擎 tick 同入口（冷却/附近/预算一致；异步虚拟线程，本接口立即返回）
        moveEncounterService.maybeEncounter(projectId, npcRef, x, z, gameSecond);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("npcRef", npcRef);
        out.put("x", x);
        out.put("z", z);
        out.put("gameSecond", gameSecond);
        out.put("message", "move_encounter 单发已触发（异步 AI 判定；失败 fail-open）");
        return out;
    }

    private static Long num(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double dbl(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
