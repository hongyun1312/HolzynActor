package com.holzyn.actor.domain.settings.controller;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.settings.dto.ModelApiDTO;
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

import java.util.List;
import java.util.Map;

/**
 * 用户级/项目级 AI 模型 API 配置控制器（P1，后端项目化改造 V2.0）。
 * <p>职责：提供 /api/model-apis 系列接口——API 列表 / 新增 / 编辑 / 删除 /
 * 设为默认 / 连通性测试，供「设置-API 配置」页（项目级/用户级）与其他 AI 业务页选用。</p>
 * <p>项目化（V2.0）：全部接口支持可选 projectId 参数——传 projectId 操作项目级配置（随 .holzyn 包导入导出），
 * 不传操作用户级配置（回退默认）；运行时「项目级优先、用户级回退」。</p>
 * <p>权限：/api/** 需登录（Casdoor OIDC）；归属一律以当前会话用户为准
 * （CurrentUserProvider.currentUserId），OIDC 模式下忽略前端传入的任何 userId 防越权。</p>
 * <p>所属模块：controller/settings（用户设置子域）</p>
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
     * 某归属的 API 列表（脱敏）；projectId 不传=用户级。
     *
     * @param projectId 项目 ID（可空）
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
     * @return 默认 API 或 null
     */
    @GetMapping("/default")
    public R<ModelApiVO> getDefault(@RequestParam(name = "projectId", required = false) Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.getDefault(userId, projectId));
    }

    /**
     * 新增 API 配置。
     *
     * @param projectId 项目 ID（可空=用户级）
     * @param dto       请求体（name/baseUrl 必填，apiKey 创建必填）
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
     * @param id        配置主键
     * @param dto       请求体
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
     * @param id        配置主键
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
     * @param id        配置主键
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
     * @param id        配置主键
     * @return 测试结果 Map
     */
    @PostMapping("/{id}/test")
    public R<Map<String, Object>> testSaved(@RequestParam(name = "projectId", required = false) Long projectId,
                                            @PathVariable("id") Long id) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(modelApiService.testConnection(userId, projectId, id));
    }
}
