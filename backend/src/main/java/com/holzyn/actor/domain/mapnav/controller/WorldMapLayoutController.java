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

package com.holzyn.actor.domain.mapnav.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.mapnav.entity.ActorMapFeatureOverride;
import com.holzyn.actor.domain.mapnav.service.LayoutEditService;
import com.holzyn.actor.domain.mapnav.service.LayoutOverrideService;
import com.holzyn.actor.domain.mapnav.vo.LayoutEditRequestVO;
import com.holzyn.actor.domain.mapnav.vo.LayoutEditResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 布局编辑接口（{@code /api/worlds/{projectId}/map/layout/**}）。
 *
 * <p><b>为什么提交用 409 表达「整批被拒」</b>：这是服务端拒绝了前端的一次写意图（因为几何不合法），
 * 而且前端必须能看到逐条原因——200 + {@code ok:false} 会被很多拦截器当作成功、
 * 也会让浏览器缓存策略误判。409 让「没写进去」这件事在网络面板上一眼可见。</p>
 */
@RestController
@RequestMapping("/api/worlds/{projectId}/map/layout")
@RequiredArgsConstructor
public class WorldMapLayoutController {

    private final LayoutEditService editService;

    private final LayoutOverrideService overrideService;

    /**
     * 提交一批编辑（整批原子）。
     *
     * @param projectId 项目
     * @param req       请求体
     * @return 成功 200 / 全部被拒 409
     */
    /**
     * 提交一批编辑（整批原子）。
     *
     * <p><b>为什么「整批被拒」也用 HTTP 200</b>：拒绝原因是<b>业务数据</b>（逐条 code + 原因），
     * 前端要拿它给每栋房子打红标。如果回 409，统一响应拦截器会把它变成一句 Error，
     * 逐条原因就在路上丢光了。所以「没写进去」由 {@code ok:false} 表达，
     * 前端据 {@code ok} 决定提示，据 {@code rejected} 画红标。</p>
     *
     * @param projectId 项目
     * @param req       请求体
     * @return 编辑结果（{@code ok=false} 表示整批未写入）
     */
    @PostMapping("/edits")
    public R<LayoutEditResultVO> apply(@PathVariable Long projectId,
                                       @RequestBody(required = false) LayoutEditRequestVO req) {
        LayoutEditResultVO result = editService.apply(projectId,
                req == null ? null : req.getBaseRevision(),
                req == null || req.getEdits() == null ? List.of() : req.getEdits());
        return R.ok(result);
    }

    /**
     * 列出手改（前端画「已修改」角标 + 提供单条清除）。
     *
     * @param projectId 项目
     * @return {@code {revision, overrides:[...]}}
     */
    @GetMapping("/overrides")
    public R<Map<String, Object>> overrides(@PathVariable Long projectId) {
        List<ActorMapFeatureOverride> rows = overrideService.listView(projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revision", overrideService.revision(projectId));
        out.put("count", rows.size());
        out.put("overrides", rows.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", o.getFeatureCode());
            m.put("x", o.getPosX());
            m.put("z", o.getPosZ());
            m.put("rotation", o.getRotation());
            m.put("doorX", o.getDoorX());
            m.put("doorZ", o.getDoorZ());
            m.put("revision", o.getRevision());
            m.put("note", o.getNote());
            return m;
        }).toList());
        return R.ok(out);
    }

    /**
     * 清除单条手改（立即恢复 AI 原版）。
     *
     * @param projectId 项目
     * @param code      要素 code
     * @return {@code {cleared, revision}}
     */
    @DeleteMapping("/overrides/{code}")
    public R<Map<String, Object>> clearOne(@PathVariable Long projectId, @PathVariable String code) {
        int n = overrideService.clear(projectId, code);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleared", n);
        out.put("revision", overrideService.revision(projectId));
        return R.ok(out);
    }

    /**
     * 清除全部手改。
     *
     * @param projectId 项目
     * @return {@code {cleared, revision}}
     */
    @DeleteMapping("/overrides")
    public R<Map<String, Object>> clearAll(@PathVariable Long projectId) {
        int n = overrideService.clear(projectId, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleared", n);
        out.put("revision", overrideService.revision(projectId));
        return R.ok(out);
    }
}
