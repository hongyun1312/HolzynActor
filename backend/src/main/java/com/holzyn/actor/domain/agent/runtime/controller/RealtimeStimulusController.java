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

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.agent.runtime.StimulusLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 实时刺激日志控制器 ——
 * 调试前端「刺激日志页」数据源。
 * <p>数据来自 {@link StimulusLogService} 进程内环形缓冲（刺激事件 + 护栏命中记录：
 * triggered / cooldown / budget / daily_budget / not_active / disabled / crowd / idle_skip / crowd_ai），
 * 非业务事实源（业务事实仍以 actor_event / actor_ai_call_log / actor_plan 为准）。</p>
 * <ul>
 * <li><b>GET log</b>：刺激时间线（项目过滤 + 倒序 + limit 截断）；</li>
 * <li><b>GET stats</b>：护栏命中统计（按 result 分组 + total）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/realtime-stimulus")
@RequiredArgsConstructor
public class RealtimeStimulusController {

    /** 刺激日志服务 */
    private final StimulusLogService stimulusLogService;

    /**
     * 最近刺激日志（刺激时间线：类型/对象/刺激文本/结果/命中护栏）。
     *
     * @param projectId 项目 ID（可空=全部项目）
     *
     * @param limit 条数上限（可空=50，上限 200）
     *
     * @return 日志条目列表（倒序）
     */
    @GetMapping("/log")
    public R<List<StimulusLogService.Entry>> log(@RequestParam(value = "projectId", required = false) Long projectId,
                                                 @RequestParam(value = "limit", required = false) Integer limit) {
        return R.ok(stimulusLogService.recent(projectId, limit));
    }

    /**
     * 护栏命中统计（调试页可视化护栏生效）。
     *
     * @param projectId 项目 ID（可空=全部项目）
     *
     * @return {total, byResult: {result → 计数}}
     */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam(value = "projectId", required = false) Long projectId) {
        return R.ok(stimulusLogService.stats(projectId));
    }
}
