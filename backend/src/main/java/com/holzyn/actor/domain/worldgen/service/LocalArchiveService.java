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

package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地存档服务。
 * <p>职责：管理「世界观生成任务工作区」的文件读写与检查点校验（自写实现，仅借鉴其设计语义，不复制代码）：</p>
 * <ul>
 * <li><b>工作区</b>：{@code data/worldgen/&lt;projectId&gt;/&lt;taskId&gt;/}——Agent 产物写
 * {@code world/*.json}、渲染脚本写 {@code meta/world_foundation.md}（对齐 bishu build 的落盘惯例）；</li>
 * <li><b>路径安全</b>：仅允许工作区内相对路径（拒绝绝对路径与 {@code ..} 越权，对齐 local_archive._workspace_path）；</li>
 * <li><b>checkpoint 语义</b>：校验指定相对文件均存在且非空（对齐 local_archive._require_files），
 * 任一缺失/空文件 → 抛异常使脚本节点失败（断点不完整则停止，防下游渲染残缺产物）。</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalArchiveService {

    /** 数据目录（@Value 字段注入，取 holzyn.actor.data-dir；工作区根 = &lt;data-dir&gt;/worldgen） */
    @Value("${holzyn.actor.data-dir:./data}")
    private String dataDir = "./data";

    /** 静态 JSON 解析器（Jackson 3 / tools.jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 任务工作区根目录（data/worldgen/&lt;projectId&gt;/&lt;taskId&gt;）。
     *
     * @param projectId 项目 ID
     *
     * @param taskId 任务 ID
     *
     * @return 工作区目录（不存在时也返回路径，写入时自动建目录）
     */
    public Path taskWorkspace(Long projectId, Long taskId) {
        return Path.of(dataDir == null || dataDir.isBlank() ? "./data" : dataDir)
                .resolve("worldgen")
                .resolve(String.valueOf(projectId))
                .resolve(String.valueOf(taskId));
    }

    /**
     * 在工作区内安全解析相对路径（拒绝绝对路径与 {@code ..} 越权）。
     *
     * @param workspace 工作区根目录
     *
     * @param relative 工作区内相对路径（如 world/core_laws.json）
     *
     * @return 解析后的绝对路径
     *
     * @throws BizException 路径非法（越出工作区）
     */
    public Path safeResolve(Path workspace, String relative) {
        if (relative == null || relative.isBlank()) {
            throw new BizException(400, "存档相对路径为空");
        }
        Path p = Path.of(relative.replace('\\', '/'));
        if (p.isAbsolute() || p.getNameCount() == 0 || p.startsWith("..")) {
            throw new BizException(400, "存档路径非法（仅允许工作区内相对路径）：" + relative);
        }
        for (Path part : p) {
            if ("..".equals(part.toString())) {
                throw new BizException(400, "存档路径非法（不允许越出工作区）：" + relative);
            }
        }
        Path resolved = workspace.resolve(p).normalize();
        if (!resolved.startsWith(workspace.normalize())) {
            throw new BizException(400, "存档路径非法（不允许越出工作区）：" + relative);
        }
        return resolved;
    }

    /**
     * 写 JSON 检查点文件（pretty 格式 + UTF-8，目录自动创建）。
     *
     * @param workspace 工作区根目录
     *
     * @param relative 工作区内相对路径（如 world/core_laws.json）
     *
     * @param jsonText 待写入的 JSON 文本（先做一次合法化校验）
     */
    public void writeJson(Path workspace, String relative, String jsonText) {
        // 先解析校验：写入前必须是合法 JSON（否则是执行器 bug，早暴露）
        try {
            MAPPER.readTree(jsonText == null ? "" : jsonText);
        } catch (Exception e) {
            throw new BizException(500, "检查点 JSON 非法，拒绝写盘：" + relative + "：" + e.getMessage());
        }
        Path target = safeResolve(workspace, relative);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(MAPPER.readTree(jsonText)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(500, "检查点写盘失败：" + target + "：" + e.getMessage());
        }
    }

    /**
     * 写文本文件（UTF-8，目录自动创建；供渲染脚本写 world_foundation.md）。
     *
     * @param workspace 工作区根目录
     *
     * @param relative 工作区内相对路径
     *
     * @param text 待写入文本
     */
    public void writeText(Path workspace, String relative, String text) {
        Path target = safeResolve(workspace, relative);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, text == null ? "" : text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(500, "存档写盘失败：" + target + "：" + e.getMessage());
        }
    }

    /**
     * 读取工作区 JSON 文件（解析失败抛异常）。
     *
     * @param workspace 工作区根目录
     *
     * @param relative 工作区内相对路径
     *
     * @return 解析后的 JSON 节点
     */
    public tools.jackson.databind.JsonNode readJson(Path workspace, String relative) {
        Path target = safeResolve(workspace, relative);
        try {
            return MAPPER.readTree(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BizException(500, "存档读取失败：" + relative + "：" + e.getMessage());
        }
    }

    /**
     * 检查点校验（对齐 local_archive checkpoint --files）：全部指定相对文件存在且非空。
     *
     * @param workspace 工作区根目录
     *
     * @param files 相对路径列表（逗号分隔或列表）
     *
     * @return 校验通过的文件数
     *
     * @throws BizException 任一文件缺失/为空 → 检查点不完整
     */
    public int checkpoint(Path workspace, List<String> files) {
        List<String> missing = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        int count = 0;
        for (String raw : files) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Path p = safeResolve(workspace, raw.trim());
            if (!Files.isRegularFile(p)) {
                missing.add(raw.trim());
            } else {
                try {
                    if (Files.size(p) == 0) {
                        empty.add(raw.trim());
                    } else {
                        count++;
                    }
                } catch (IOException e) {
                    missing.add(raw.trim());
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new BizException(400, "检查点不完整，缺少存档文件：" + String.join("、", missing));
        }
        if (!empty.isEmpty()) {
            throw new BizException(400, "检查点不完整，存档文件为空：" + String.join("、", empty));
        }
        return count;
    }
}
