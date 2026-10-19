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

package com.holzyn.actor.domain.settings.controller;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.settings.entity.ActorPromptTemplate;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 模板控制器。
 * <p>职责：提供模板接口（非 /api/admin）——列表（项目覆盖 ∪ 用户覆盖 ∪ 内置）、
 * 保存覆盖、重置回退；支持可选 projectId（传=项目级覆盖，随 .holzyn 包导入导出；
 * 不传=用户级覆盖）。归属一律以当前会话用户为准。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PromptTemplateController {

    /** Prompt 模板服务 */
    private final PromptTemplateService templateService;

    /** 当前用户解析器 */
    private final CurrentUserProvider currentUserProvider;

    /**
     * 有效模板列表（项目覆盖 ∪ 用户覆盖 ∪ 内置，含 isOverride/projectScope 标记）。
     *
     * @param projectId 项目 ID（可空=仅用户覆盖/内置）
     *
     * @return 模板列表
     */
    @GetMapping("/prompt-templates")
    public R<List<Map<String, Object>>> list(@RequestParam(name = "projectId", required = false) Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(templateService.effectiveList(userId, projectId).stream().map(t -> toVO(userId, t)).toList());
    }

    /**
     * 模板分类列表（有序，含各分类模板数）——设置页横向 Tab 分组展示用。
     *
     * @param projectId 项目 ID（可空=仅用户覆盖/内置）
     *
     * @return 有序分类列表 [{key,label,count}]
     */
    @GetMapping("/prompt-templates/categories")
    public R<List<Map<String, Object>>> categories(@RequestParam(name = "projectId", required = false) Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(templateService.categories(userId, projectId));
    }

    /**
     * 保存覆盖模板（不存在则新建）。projectId 非空=项目级覆盖，空=用户级覆盖。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param code 模板编码
     *
     * @param body 请求体：{name?, template}
     *
     * @return 保存后的模板
     */
    @PutMapping("/prompt-templates/{code}")
    public R<Map<String, Object>> save(@RequestParam(name = "projectId", required = false) Long projectId,
                                       @PathVariable("code") String code, @RequestBody Map<String, Object> body) {
        Long userId = currentUserProvider.currentUserId();
        String name = body == null ? null : str(body.get("name"));
        String template = body == null ? null : str(body.get("template"));
        String systemMessage = body == null ? null : str(body.get("systemMessage"));
        ActorPromptTemplate t = templateService.saveOverride(userId, projectId, code, name, template, systemMessage);
        return R.ok(toVO(userId, t));
    }

    /**
     * 重置为低一级：删除项目级/用户级覆盖行，回退用户覆盖或内置模板。
     *
     * @param projectId 项目 ID（可空=删除用户级覆盖）
     *
     * @param code 模板编码
     *
     * @return 重置后的有效模板
     */
    @DeleteMapping("/prompt-templates/{code}")
    public R<Map<String, Object>> reset(@RequestParam(name = "projectId", required = false) Long projectId,
                                        @PathVariable("code") String code) {
        Long userId = currentUserProvider.currentUserId();
        templateService.reset(userId, projectId, code);
        return templateService.effectiveTemplate(userId, projectId, code)
                .map(t -> R.ok(toVO(userId, t)))
                .orElse(R.fail(404, "模板不存在：" + code));
    }

    /**
     * 模板实体 → 视图 Map（isOverride 标记是否覆盖、projectScope 标记是否项目级）。
     *
     * @param userId 当前用户 ID
     *
     * @param t 模板实体
     *
     * @return 视图 Map
     */
    private Map<String, Object> toVO(Long userId, ActorPromptTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", t.getCode());
        m.put("name", t.getName());
        m.put("template", t.getTemplate());
        m.put("systemMessage", t.getSystemMessage());
        m.put("version", t.getVersion());
        m.put("isOverride", t.getUserId() != null && userId.equals(t.getUserId()) && t.getUserId() > 0);
        m.put("projectScope", t.getProjectId() != null);
        m.put("userId", t.getUserId());
        // 功能分类（V3：设置页横向 Tab 分组；实体分类可能为空（旧数据），按编码兜底）
        String category = PromptTemplateService.categoryOf(t.getCode());
        m.put("category", category);
        m.put("categoryLabel", PromptTemplateService.categoryLabel(category));
        return m;
    }

    /**
     * 取值辅助：Object 转字符串。
     *
     * @param v 原始对象
     *
     * @return 字符串
     */
    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
