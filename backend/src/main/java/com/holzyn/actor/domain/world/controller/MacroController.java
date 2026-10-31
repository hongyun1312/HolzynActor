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

package com.holzyn.actor.domain.world.controller;

import com.holzyn.actor.domain.world.service.MacroStateService;
import com.holzyn.actor.domain.world.service.MacroTraceService;
import com.holzyn.actor.domain.world.vo.MacroDeltaVO;
import com.holzyn.actor.domain.world.vo.MacroEventVO;
import com.holzyn.actor.domain.world.vo.MacroSnapshotVO;
import com.holzyn.actor.domain.world.vo.MacroStateVO;
import com.holzyn.actor.domain.world.vo.MacroTraceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 宏观社会演化接口。
 * <p>接口集：</p>
 * <ul>
 * <li>GET /macro/state —— 当前宏观状态摘要（核心指标 + 简报 + 全量五段 indicators）；</li>
 * <li>GET /macro/snapshot —— 宏观指标快照历史（tickType?/fromDay?/toDay?/limit?，曲线回看源）；</li>
 * <li>GET /macro/events —— 宏观事件列表（fromDay?/toDay?/severity?/limit?）；</li>
 * <li>GET /macro/deltas —— NPC 行动入账流水；</li>
 * <li>GET /macro/trace —— 全链路 trace 回放（tickType?/limit?：输入摘要→引擎参数→
 * 事件 id→回灌落点逐跳）；</li>
 * <li>POST /macro/initialize —— 初始化/重置宏观状态（body 可空覆盖参数：
 * population/grainPriceBase/grainStock/grainProductionBase/marketActivity/bankDeposit/currencySupply）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/worlds/{id}/macro")
@RequiredArgsConstructor
public class MacroController {

    private final MacroStateService macroStateService;
    private final MacroTraceService macroTraceService;

    /**
     * 当前宏观状态摘要。
     */
    @GetMapping("/state")
    public MacroStateVO state(@PathVariable("id") Long projectId) {
        return macroStateService.stateVO(projectId);
    }

    /**
     * 宏观指标快照历史（曲线回看数据源）。
     */
    @GetMapping("/snapshot")
    public List<MacroSnapshotVO> snapshot(@PathVariable("id") Long projectId,
                                          @RequestParam(required = false) String tickType,
                                          @RequestParam(required = false) Long fromDay,
                                          @RequestParam(required = false) Long toDay,
                                          @RequestParam(required = false) Integer limit) {
        return macroStateService.snapshotVOs(projectId, tickType, fromDay, toDay, limit);
    }

    /**
     * 宏观事件列表。
     */
    @GetMapping("/events")
    public List<MacroEventVO> events(@PathVariable("id") Long projectId,
                                     @RequestParam(required = false) Long fromDay,
                                     @RequestParam(required = false) Long toDay,
                                     @RequestParam(required = false) Integer severity,
                                     @RequestParam(required = false) Integer limit) {
        return macroStateService.eventVOs(projectId, fromDay, toDay, severity, limit);
    }

    /**
     * NPC 行动入账流水。
     */
    @GetMapping("/deltas")
    public List<MacroDeltaVO> deltas(@PathVariable("id") Long projectId,
                                     @RequestParam(required = false) Long day,
                                     @RequestParam(required = false) String npcRef,
                                     @RequestParam(required = false) Integer limit) {
        return macroStateService.deltaVOs(projectId, day, npcRef, limit);
    }

    /**
     * 全链路 trace 回放（tick 输入→输出→回灌逐跳；tickType=daily|faction|monthly 可空=全部）。
     */
    @GetMapping("/trace")
    public List<MacroTraceVO> trace(@PathVariable("id") Long projectId,
                                    @RequestParam(required = false) String tickType,
                                    @RequestParam(required = false) Integer limit) {
        return macroTraceService.listVOs(projectId, tickType, limit);
    }

    /**
     * 初始化/重置宏观状态（世界观默认值 + 可选覆盖参数）。
     */
    @PostMapping("/initialize")
    public MacroStateVO initialize(@PathVariable("id") Long projectId,
                                   @RequestBody(required = false) Map<String, Object> overrides) {
        macroStateService.initialize(projectId, overrides);
        return macroStateService.stateVO(projectId);
    }

    /**
     * 世界观模板：当前模板 + 可选模板全集（读接口；前端宏观面板显示
     * 模板名/资源语义用）。
     */
    @GetMapping("/template")
    public Map<String, Object> template(@PathVariable("id") Long projectId) {
        return macroStateService.templateVO(projectId);
    }

    /**
     * 手动覆盖世界观模板（POST body {"templateId":"xianxia|ancient|wuxia|modern|future"}）。
     */
    @PostMapping("/template")
    public Map<String, Object> setTemplate(@PathVariable("id") Long projectId,
                                           @RequestBody Map<String, Object> body) {
        Object t = body == null ? null : body.get("templateId");
        if (t == null || String.valueOf(t).isBlank()) {
            throw new com.holzyn.actor.common.BizException(400, "templateId 不能为空（ancient/xianxia/wuxia/modern/future）");
        }
        macroStateService.setTemplate(projectId, String.valueOf(t));
        return macroStateService.templateVO(projectId);
    }
}
