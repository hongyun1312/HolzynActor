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

package com.holzyn.actor.domain.observability.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.observability.service.AiRuntimeStatusService;
import com.holzyn.actor.domain.observability.service.GlobalSearchService;
import com.holzyn.actor.domain.observability.vo.AiRuntimeStatusVO;
import com.holzyn.actor.domain.observability.vo.GlobalSearchVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目观测域只读聚合控制器（前端布局重构批次③的后端配套）。
 *
 * <p>职责：为前端「AI 运行状态条 / AI 任务中心」与「全局搜索」提供两个**纯只读**端点。
 * 二者均零 AI 调用、零写入、零新表，只做既有数据的聚合与检索。</p>
 *
 * <ul>
 * <li>GET /ai-runtime/status?windowMinutes= —— 窗口内 AI 调用聚合（总量/Token/缓存命中/时延分位/
 * 场景与状态分布/最近调用/失败明细）；</li>
 * <li>GET /search?q=&types=&limit= —— 全局搜索（角色/地点/事件/知识/项目 五类分组命中）。</li>
 * </ul>
 *
 * <p>与调试域的分工：调试台（debug-frontend）的 {@code /api/debug/**} 面向「单机排查」
 * （可看 Prompt 全文、DB 浏览、日志流）；本控制器面向「项目内使用」，只暴露可安全展示的
 * 摘要级信息（不含 Prompt / 响应正文、不含密钥）。</p>
 */
@RestController
@RequestMapping("/api/worlds/{projectId}")
@RequiredArgsConstructor
public class ProjectObservabilityController {

    private final AiRuntimeStatusService aiRuntimeStatusService;
    private final GlobalSearchService globalSearchService;

    /**
     * AI 运行时状态（只读聚合）。
     *
     * @param projectId     项目 ID
     *
     * @param windowMinutes 统计窗口（分钟，默认 30，钳制 1~1440）
     *
     * @return 状态视图；trace 未开启时返回结构完整的空状态（{@code traceEnabled=false}）
     */
    @GetMapping("/ai-runtime/status")
    public R<AiRuntimeStatusVO> aiRuntimeStatus(@PathVariable Long projectId,
                                               @RequestParam(required = false) Integer windowMinutes) {
        return R.ok(aiRuntimeStatusService.status(projectId, windowMinutes));
    }

    /**
     * 全局搜索（只读）。
     *
     * @param projectId 项目 ID
     *
     * @param q         关键词（空白 → 400）
     *
     * @param types     类型过滤（逗号分隔 character,location,event,knowledge,project；缺省全部）
     *
     * @param limit     总条数上限（默认 30，上限 100）
     *
     * @return 分组命中结果
     */
    @GetMapping("/search")
    public R<GlobalSearchVO> search(@PathVariable Long projectId,
                                   @RequestParam String q,
                                   @RequestParam(required = false) String types,
                                   @RequestParam(required = false) Integer limit) {
        return R.ok(globalSearchService.search(projectId, q, types, limit));
    }
}
