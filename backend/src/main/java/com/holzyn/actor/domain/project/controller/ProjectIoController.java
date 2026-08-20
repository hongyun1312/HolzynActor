package com.holzyn.actor.domain.project.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.project.service.HolzynImportService;
import com.holzyn.actor.domain.project.service.ProjectExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.Map;

/**
 * .holzyn 项目包导入/导出控制器（V2.0 设计文档，导入/导出能力）。
 * <p>职责：提供项目导出为 .holzyn 包（可选敏感数据 / 可选密码加密）与 .holzyn 包导入还原
 * （幂等检测 + id 映射 + 敏感配置可选解密）。</p>
 * <p>所属模块：controller/project（项目导入导出子域）</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProjectIoController {

    /** 项目导出服务 */
    private final ProjectExportService exportService;

    /** .holzyn 项目包导入服务 */
    private final HolzynImportService importService;

    /**
     * 导出项目为 .holzyn 包（下载）。
     *
     * @param projectId        项目 ID
     * @param includeSensitive 是否包含 API 等敏感数据（默认 false）
     * @param password         密码（可选；提供时对敏感文件做密码加密）
     * @return .holzyn 二进制流
     */
    @PostMapping("/projects/{projectId}/export")
    public ResponseEntity<byte[]> export(@PathVariable("projectId") Long projectId,
                                         @RequestParam(name = "includeSensitive", required = false, defaultValue = "false") boolean includeSensitive,
                                         @RequestParam(name = "password", required = false) String password) {
        byte[] bytes = exportService.export(projectId, includeSensitive, password);
        String filename = "project-" + projectId + ".holzyn";
        try {
            filename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 编码失败使用默认名
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    /**
     * 导入 .holzyn 包（multipart：file + password?）。
     *
     * @param file     .holzyn 文件
     * @param password 密码（包为 password-encrypted 时提供）
     * @return 导入结果（projectId/统计/敏感状态）
     */
    @PostMapping("/projects/import/holzyn")
    public R<Map<String, Object>> importPackage(@RequestParam("file") MultipartFile file,
                                                @RequestParam(name = "password", required = false) String password) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要导入的 .holzyn 文件");
        }
        try {
            return R.ok(importService.importPackage(file.getBytes(), password));
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw new BizException(400, "导入失败：" + e.getMessage());
        }
    }
}
