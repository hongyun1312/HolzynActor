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

package com.holzyn.actor.domain.unity.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.agent.runtime.RealtimeDecisionActivationService;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.sandbox.controller.SandboxController;
import com.holzyn.actor.domain.sandbox.vo.WeatherVO;
import com.holzyn.actor.domain.unity.config.UnityProperties;
import com.holzyn.actor.domain.unity.service.CoordinateTransformer;
import com.holzyn.actor.domain.unity.service.ExternalClockService;
import com.holzyn.actor.domain.unity.service.UnityInitService;
import com.holzyn.actor.domain.unity.service.UnitySessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unity 对接 REST 门面（U 轨道 U1 服务端对接层，）——
 * /api/projects/{id}/unity/*（查询/初始化/干预）。
 * <p>端点：</p>
 * <ul>
 * <li>GET /init —— 全量初始化（场景布局/摆放/NPC/地点/时钟/坐标基准，U2 场景构建）；</li>
 * <li>GET /status —— 通道状态（连接数/seq/坐标配置/外部时钟激活/时钟源，调试页）；</li>
 * <li>POST /intervene/{talk|action|event|clock} + /weather —— <b>薄代理</b>：委托
 * {@link SandboxController} 同源方法（行为与沙盘干预端点完全一致，零新业务逻辑），
 * Unity 客户端不依赖沙盘域路径（U3 Web 沙盘降级后干预能力入口迁移到 Unity 端）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/projects/{projectId}/unity")
@RequiredArgsConstructor
public class UnityBridgeController {

    private final UnityInitService unityInitService;
    private final UnitySessionRegistry sessionRegistry;
    private final ExternalClockService externalClockService;
    private final CoordinateTransformer coordinateTransformer;
    private final RealtimeDecisionActivationService activationService;
    private final UnityProperties props;
    private final ActorProjectRepository projectRepository;
    /** 干预薄代理目标（同源委托：行为与沙盘干预端点完全一致） */
    private final SandboxController sandboxController;

    /**
     * 全量初始化（U2 场景构建入口）。
     *
     * @param projectId 项目 ID
     *
     * @return init 载荷（clock/coordinate/scenes/placements/npcs/locations/counts）
     */
    @GetMapping("/init")
    public R<Map<String, Object>> init(@PathVariable Long projectId) {
        requireProject(projectId);
        return R.ok(unityInitService.init(projectId));
    }

    /**
     * 通道状态（调试页/联调自检）。
     *
     * @param projectId 项目 ID
     *
     * @return 状态 Map（enabled/wsPath/连接数/seq/坐标配置/外部时钟激活/激活源）
     */
    @GetMapping("/status")
    public R<Map<String, Object>> status(@PathVariable Long projectId) {
        requireProject(projectId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.isEnabled());
        m.put("wsPath", props.getWsPath() + "/" + projectId);
        m.put("connected", sessionRegistry.isConnected(projectId));
        m.put("sessionCount", sessionRegistry.sessionCount(projectId));
        m.put("lastSeq", sessionRegistry.lastSeqOf(projectId));
        m.put("externalClockActive", externalClockService.isExternalClockActive(projectId));
        m.put("clockSource", externalClockService.isExternalClockActive(projectId) ? "unity" : "internal");
        m.put("coordinate", Map.of(
                "meterPerCell", coordinateTransformer.meterPerCell(),
                "originOffsetX", coordinateTransformer.originOffsetX(),
                "originOffsetZ", coordinateTransformer.originOffsetZ()));
        m.put("unityActive", activationService.isUnityConnected(projectId));
        m.put("worldActive", activationService.isActive(projectId));
        m.put("connectedProjectCount", sessionRegistry.connectedProjectCount());
        m.put("totalSessionCount", sessionRegistry.totalSessionCount());
        return R.ok(m);
    }

    // ==================== 干预薄代理（委托 SandboxController 同源方法） ====================

    /**
     * 干预-插话（薄代理，同沙盘端点语义）。
     *
     * @param projectId 项目 ID
     *
     * @param body {npcRef, content 必填}
     */
    @PostMapping("/intervene/talk")
    public R<Map<String, Object>> interveneTalk(@PathVariable Long projectId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        return sandboxController.interveneTalk(projectId, body);
    }

    /**
     * 干预-触发行动（薄代理，仅特殊角色）。
     *
     * @param projectId 项目 ID
     */
    @PostMapping("/intervene/action")
    public R<Map<String, Object>> interveneAction(@PathVariable Long projectId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        return sandboxController.interveneAction(projectId, body);
    }

    /**
     * 干预-手动事件（薄代理）。
     *
     * @param projectId 项目 ID
     *
     * @param body {title?, content 必填, kind?}
     */
    @PostMapping("/intervene/event")
    public R<Map<String, Object>> interveneEvent(@PathVariable Long projectId,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        return sandboxController.interveneEvent(projectId, body);
    }

    /**
     * 干预-时间控制（薄代理：速率/暂停/常驻运行/手动补推）。
     *
     * @param projectId 项目 ID
     */
    @PostMapping("/intervene/clock")
    public R<Map<String, Object>> interveneClock(@PathVariable Long projectId,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        return sandboxController.interveneClock(projectId, body);
    }

    /**
     * 干预-天气切换（薄代理：晴/雨/雪/阴）。
     *
     * @param projectId 项目 ID
     *
     * @param body {condition: 晴|雨|雪|阴 必填}
     */
    @PostMapping("/weather")
    public R<WeatherVO> switchWeather(@PathVariable Long projectId,
                                      @RequestBody(required = false) Map<String, Object> body) {
        requireProject(projectId);
        return sandboxController.switchWeather(projectId, body);
    }

    /** 项目校验（不存在/已删除 → 404）。 */
    private void requireProject(Long projectId) {
        projectRepository.findById(projectId)
                .filter(p -> Integer.valueOf(0).equals(p.getDeleted()))
                .orElseThrow(() -> new BizException(404, "项目不存在或已删除"));
    }
}
