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
import com.holzyn.actor.domain.settings.dto.ModelApiDTO;
import com.holzyn.actor.domain.settings.dto.ProviderModelDTO;
import com.holzyn.actor.domain.settings.vo.ModelApiVO;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户级/项目级 AI 模型 API 配置控制器。
 * <p>职责：提供 /api/model-apis 系列接口——API 列表 / 新增 / 编辑 / 删除 /
 * 设为默认 / 连通性测试，供「设置-API 配置」页（项目级/用户级）与其他 AI 业务页选用。</p>
 * <p>项目化：全部接口支持可选 projectId 参数——传 projectId 操作项目级配置（随 .holzyn 包导入导出），
 * 不传操作用户级配置（回退默认）；运行时「项目级优先、用户级回退」。</p>
 * <p>权限：/api/** 需登录（Casdoor OIDC）；归属一律以当前会话用户为准
 * （CurrentUserProvider.currentUserId），OIDC 模式下忽略前端传入的任何 userId 防越权。</p>
 */
@RestController
@RequestMapping("/api/model-apis")
@RequiredArgsConstructor
public class ModelApiController {

    /** 用户 AI API 配置服务 */
    private final ModelApiService modelApiService;

    /** 当前用户解析器（会话优先 + 演示模式兜底） */
    private final CurrentUserProvider currentUserProvider;

    /**
     * 全域 AI 就绪状态（世界画廊「还没接上模型」横幅的判据）。
     *
     * <p>为什么单开一个端点而不让前端自己调列表判断：判定要合并「用户级 + 全部世界的项目级」
     * 供应商，并逐条校验启用状态/模型角色；前端拉一遍 {@code /api/model-apis} 只能看到用户级，
     * 在世界里配好模型的人会一直看到「未接模型」的假提示。</p>
     *
     * @return {@code {configured: true|false}}
     */
    @GetMapping("/ready")
    public R<Map<String, Object>> ready() {
        Long userId = currentUserProvider.currentUserId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", modelApiService.isAiReady(userId));
        return R.ok(out);
    }

    /**
     * 某归属的 API 列表（脱敏）；projectId 不传=用户级。
     *
     * @param projectId 项目 ID（可空）
     *
     * @return 脱敏后的 API 列表
     */
    @GetMapping
    public R<List<ModelApiVO>> list(@RequestParam(name = "projectId", required = false) Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.list(userId, projectId));
    }

    /**
     * 某归属的默认 API（未设置时 data=null）。
     *
     * @param projectId 项目 ID（可空）
     *
     * @return 默认 API 或 null
     */
    @GetMapping("/default")
    public R<ModelApiVO> getDefault(@RequestParam(name = "projectId", required = false) Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.getDefault(userId, projectId));
    }

    /**
     * API 配置。
     *
     * @param projectId 项目 ID（可空=用户级）
     *
     * @param dto 请求体（name/baseUrl 必填，apiKey 创建必填）
     *
     * @return 新增后的视图对象
     */
    @PostMapping
    public R<ModelApiVO> create(@RequestParam(name = "projectId", required = false) Long projectId,
                                @Valid @RequestBody ModelApiDTO dto) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.create(userId, projectId, dto));
    }

    /**
     * 编辑 API 配置（apiKey 传空=保持原 Key）。
     *
     * @param projectId 项目 ID（可空=用户级）
     *
     * @param id 配置主键
     *
     * @param dto 请求体
     *
     * @return 更新后的视图对象
     */
    @PutMapping("/{id}")
    public R<ModelApiVO> update(@RequestParam(name = "projectId", required = false) Long projectId,
                                @PathVariable("id") Long id, @Valid @RequestBody ModelApiDTO dto) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.update(userId, projectId, id, dto));
    }

    /**
     * 删除 API 配置。
     *
     * @param projectId 项目 ID（可空=用户级）
     *
     * @param id 配置主键
     *
     * @return 成功响应
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@RequestParam(name = "projectId", required = false) Long projectId,
                          @PathVariable("id") Long id) {
        Long userId = currentUserProvider.currentUserId();
        modelApiService.delete(userId, projectId, id);
        return R.ok(null);
    }

    /**
     * 设为默认（同归属内互斥）。
     *
     * @param projectId 项目 ID（可空=用户级）
     *
     * @param id 配置主键
     *
     * @return 置为默认后的视图对象
     */
    @PutMapping("/{id}/default")
    public R<ModelApiVO> setDefault(@RequestParam(name = "projectId", required = false) Long projectId,
                                    @PathVariable("id") Long id) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.setDefault(userId, projectId, id));
    }

    /**
     * 未保存前连通性测试（新增表单内使用，Key 不入库）。
     *
     * @param dto 请求体（含 baseUrl/apiKey/model）
     *
     * @return 测试结果 Map（connected/method/status/message/latencyMs）
     */
    @PostMapping("/test")
    public R<Map<String, Object>> test(@Valid @RequestBody ModelApiDTO dto) {
        return R.ok(modelApiService.testConnection(dto));
    }

    /**
     * 已保存配置连通性测试（使用解密后的真实 Key）。
     *
     * @param projectId 项目 ID（可空=用户级）
     *
     * @param id 配置主键
     *
     * @return 测试结果 Map
     */
    @PostMapping("/{id}/test")
    public R<Map<String, Object>> testSaved(@RequestParam(name = "projectId", required = false) Long projectId,
                                            @PathVariable("id") Long id) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.testConnection(userId, projectId, id));
    }

    // ==================== 供应商下的模型（文本 / 向量 / 决策） ====================

    /**
     * 在供应商下新增模型。
     *
     * @param projectId 项目 ID（可空=用户级）
     *
     * @param id 供应商主键
     *
     * @param dto 模型请求体（name/role/isDefault/enabled/remark）
     *
     * @return 更新后的供应商视图（含全部模型）
     */
    @PostMapping("/{id}/models")
    public R<ModelApiVO> addModel(@RequestParam(name = "projectId", required = false) Long projectId,
                                  @PathVariable("id") Long id,
                                  @Valid @RequestBody ProviderModelDTO dto) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.addModel(userId, projectId, id, dto));
    }

    /**
     * 修改供应商下的模型（改名 / 改角色 / 启停 / 改默认）。
     *
     * @param projectId 项目 ID（可空=用户级）
     *
     * @param id 供应商主键
     *
     * @param modelId 模型条目 id
     *
     * @param dto 模型请求体
     *
     * @return 更新后的供应商视图（含全部模型）
     */
    @PutMapping("/{id}/models/{modelId}")
    public R<ModelApiVO> updateModel(@RequestParam(name = "projectId", required = false) Long projectId,
                                     @PathVariable("id") Long id,
                                     @PathVariable("modelId") String modelId,
                                     @Valid @RequestBody ProviderModelDTO dto) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.updateModel(userId, projectId, id, modelId, dto));
    }

    /**
     * 删除供应商下的模型。
     *
     * @param projectId 项目 ID（可空=用户级）
     *
     * @param id 供应商主键
     *
     * @param modelId 模型条目 id
     *
     * @return 更新后的供应商视图（含全部模型）
     */
    @DeleteMapping("/{id}/models/{modelId}")
    public R<ModelApiVO> deleteModel(@RequestParam(name = "projectId", required = false) Long projectId,
                                     @PathVariable("id") Long id,
                                     @PathVariable("modelId") String modelId) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.deleteModel(userId, projectId, id, modelId));
    }

    /**
     * 把模型设为它所属角色的默认模型。
     *
     * @param projectId 项目 ID（可空=用户级）
     *
     * @param id 供应商主键
     *
     * @param modelId 模型条目 id
     *
     * @return 更新后的供应商视图（含全部模型）
     */
    @PutMapping("/{id}/models/{modelId}/default")
    public R<ModelApiVO> setDefaultModel(@RequestParam(name = "projectId", required = false) Long projectId,
                                         @PathVariable("id") Long id,
                                         @PathVariable("modelId") String modelId) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.setDefaultModel(userId, projectId, id, modelId));
    }

    /**
     * 拉取该供应商接口上的全部模型（用已保存的 Key 调 {@code GET {baseUrl}/models}）。
     *
     * @param projectId 项目 ID（可空=用户级）
     *
     * @param id 供应商主键
     *
     * @return 模型 ID 列表（排序去重）
     */
    @GetMapping("/{id}/models/remote")
    public R<List<String>> remoteModels(@RequestParam(name = "projectId", required = false) Long projectId,
                                        @PathVariable("id") Long id) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.fetchRemoteModels(userId, projectId, id));
    }

    /**
     * 拉取模型清单（供应商尚未保存时用：body 传 baseUrl + apiKey）。
     *
     * @param body 请求体：{@code {"baseUrl":"...","apiKey":"..."}}
     *
     * @return 模型 ID 列表（排序去重）
     */
    @PostMapping("/models/remote")
    public R<List<String>> previewRemoteModels(@RequestBody Map<String, String> body) {
        return R.ok(modelApiService.fetchRemoteModels(body.get("baseUrl"), body.get("apiKey")));
    }

    /**
     * 测试供应商下某个具体模型（不是默认文本模型）。
     *
     * @param projectId 项目 ID（可空=用户级）
     *
     * @param id 供应商主键
     *
     * @param modelId 模型条目 id
     *
     * @return 探测结果 Map（connected/method/status/message/latencyMs）
     */
    @PostMapping("/{id}/models/{modelId}/test")
    public R<Map<String, Object>> testModel(@RequestParam(name = "projectId", required = false) Long projectId,
                                            @PathVariable("id") Long id,
                                            @PathVariable("modelId") String modelId) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.testProviderModel(userId, projectId, id, modelId));
    }
}
