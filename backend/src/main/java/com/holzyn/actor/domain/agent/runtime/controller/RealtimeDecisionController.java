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

package com.holzyn.actor.domain.agent.runtime.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实时决策激活控制器。
 * <p>需求（用户指示）：后端启动不自动运行实时行为决策系统——<b>默认关</b>，
 * 只有打开前端页面（世界沙盘 / 世界演化）或手动常开时才激活世界推进。本控制器提供：</p>
 * <ul>
 * <li><b>状态查询</b>：GET 返回当前激活态（active=手动常开 ∪ 沙盘页打开 ∪ 演化会话进行）；</li>
 * <li><b>页面级开/关</b>：PUT {action: open|close, reason: sandbox|evolution}——前端沙盘页挂载
 * 时 open(sandbox)、离开时 close(sandbox)；演化页同理（页面生命周期驱动，幂等）；</li>
 * <li><b>手动常开</b>：PUT {action: set, enabled: true|false}——用户手动开关（沙盘页/仪表盘），
 * 持久化 actor_world_engine.realtime_decision_enabled，优先于页面自动态。</li>
 * </ul>
 * <p>归属校验：服务层经 world-clock 懒创建/校验由引擎侧完成；本控制器仅操作激活态，项目存在性由
 * {@link RealtimeDecisionActivationService} 惰性建行兜底（引擎推进时再行项目校验）。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/realtime-decision")
@RequiredArgsConstructor
public class RealtimeDecisionController {

    /** 实时决策激活闸门服务 */
    private final RealtimeDecisionActivationService activationService;

    /**
     * 查询项目实时决策激活状态。
     *
     * @param projectId 项目 ID
     */
    @GetMapping
    public R<Map<String, Object>> get(@PathVariable("projectId") Long projectId) {
        return R.ok(state(projectId));
    }

    /**
     * 项目实时决策激活状态。
     *
     * @param projectId 项目 ID
     *
     * @return 更新后的激活状态
     */
    @PutMapping
    public R<Map<String, Object>> update(@PathVariable("projectId") Long projectId,
                                         @RequestBody(required = false) Map<String, Object> body) {
        String action = body == null ? null : str(body.get("action"));
        if (action == null || action.isBlank()) {
            throw new BizException(400, "请指定 action（open/close/set）");
        }
        switch (action) {
            case "open" -> {
                String reason = requireReason(body);
                activationService.markOpen(projectId, reason);
            }
            case "close" -> {
                String reason = requireReason(body);
                activationService.markClose(projectId, reason);
            }
            case "set" -> {
                Object enabled = body.get("enabled");
                if (enabled == null) {
                    throw new BizException(400, "set 需要 enabled（true/false）");
                }
                activationService.setManual(projectId, Boolean.TRUE.equals(enabled)
                        || "true".equalsIgnoreCase(String.valueOf(enabled)));
            }
            default -> throw new BizException(400, "未知 action：" + action);
        }
        return R.ok(state(projectId));
    }

    /** 激活状态视图。 */
    private Map<String, Object> state(Long projectId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", projectId);
        m.put("active", activationService.isActive(projectId));
        m.put("manualEnabled", activationService.isManualEnabled(projectId));
        m.put("sandboxActive", activationService.isSandboxActive(projectId));
        m.put("evolutionActive", activationService.isEvolutionActive(projectId));
        return m;
    }

    /** reason 必填校验。 */
    private String requireReason(Map<String, Object> body) {
        String reason = str(body == null ? null : body.get("reason"));
        if (reason == null || reason.isBlank()) {
            throw new BizException(400, "open/close 需要 reason（sandbox/evolution）");
        }
        return reason;
    }

    /** 字符串取值辅助。 */
    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
