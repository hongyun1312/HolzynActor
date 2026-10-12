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

package com.holzyn.actor.domain.sandbox.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.sandbox.entity.ActorSceneAsset;
import com.holzyn.actor.domain.sandbox.repository.ActorSceneAssetRepository;
import com.holzyn.actor.domain.sandbox.vo.SceneAssetImportVO;
import com.holzyn.actor.domain.sandbox.vo.SceneAssetVO;
import com.holzyn.actor.domain.sandbox.vo.ScenePackVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 沙盘资产数据包服务（世界沙盘 S3 补全，domain/sandbox/service）。
 * <p>职责：资产数据包（uploads/scene-assets/&lt;packId&gt;/，manifest.json 为权威「目录即包」）
 * 的导入登记与查询——把 manifest.assets 逐条 upsert 进 {@code actor_sandbox_asset}
 * （<b>project_id=0 全局内置包</b>，所有项目可用；同 pack+asset 幂等重跑），
 * 并提供数据包列表（扫描目录）与资产清单查询。GLB/预览文件经 {@code /uploads/**} 静态映射服务。</p>
 * <p>与主方案关系：对应 SC1「资产数据包接入」基础管线——S5/SC3 AI 布局生成同样依赖
 * 本登记表选资产，后期 AI 接入本服务无需废弃。</p>
 */
@Slf4j
@Service
public class SceneAssetService {

    /** 数据包根目录（upload-dir 下固定子目录 scene-assets，与主方案 §9 一致） */
    public static final String PACK_ROOT = "scene-assets";

    /** packId 合法字符（防路径穿越：仅字母数字下划线连字符） */
    private static final Pattern PACK_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final ActorSceneAssetRepository assetRepository;
    private final ObjectMapper objectMapper;
    private final Path uploadDir;

    public SceneAssetService(ActorSceneAssetRepository assetRepository, ObjectMapper objectMapper,
                             @Value("${holzyn.actor.upload-dir:./uploads}") String uploadDir) {
        this.assetRepository = assetRepository;
        this.objectMapper = objectMapper;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    // ==================== 数据包导入 ====================

    /**
     * 导入一个资产数据包：解析 manifest.json → 逐条 upsert 进 actor_sandbox_asset
     * （project_id=0 全局；同 packId+assetId 幂等更新字段，可安全重跑）。
     *
     * @param packId 数据包 ID（目录名，如 sandbox-xianxia-v1）
     *
     * @return 导入统计 VO
     */
    public SceneAssetImportVO importPack(String packId) {
        requirePackId(packId);
        Path manifestPath = packDir(packId).resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            throw new BizException(404, "资产数据包不存在或缺少 manifest.json：" + packId);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(manifestPath.toFile());
        } catch (Exception e) {
            throw new BizException(400, "manifest.json 解析失败（应为合法 JSON）：" + e.getMessage());
        }
        JsonNode packNode = root.path("pack");
        JsonNode assetsNode = root.path("assets");
        if (!assetsNode.isArray() || assetsNode.isEmpty()) {
            throw new BizException(400, "manifest.assets 为空数组，数据包无效：" + packId);
        }
        SceneAssetImportVO out = new SceneAssetImportVO();
        out.setPackId(packId);
        out.setPackName(text(packNode, "name"));
        out.setTotal(assetsNode.size());
        int registered = 0;
        int updated = 0;
        int skipped = 0;
        for (JsonNode a : assetsNode) {
            String assetId = text(a, "asset_id");
            if (assetId == null || assetId.isBlank()) {
                skipped++;
                continue;
            }
            UpsertOutcome outcome = upsertAsset(packId, a, assetId);
            if (outcome == null) {
                skipped++;
            } else if (outcome.created) {
                registered++;
            } else {
                updated++;
            }
        }
        out.setRegistered(registered);
        out.setUpdated(updated);
        out.setSkipped(skipped);
        log.info("[sandbox-assets] 导入数据包 packId={} 完成：registered={}, updated={}, skipped={}, total={}",
                packId, registered, updated, skipped, out.getTotal());
        return out;
    }

    /** upsert 结果：created=true 新建 / false 更新；解析失败返回 null（计入 skipped） */
    private record UpsertOutcome(boolean created) {
    }

    /** upsert 单条资产（幂等：同 project+pack+asset 存在则更新字段）；失败返回 null */
    private UpsertOutcome upsertAsset(String packId, JsonNode a, String assetId) {
        try {
            Optional<ActorSceneAsset> existing = assetRepository
                    .findFirstByProjectIdAndPackIdAndAssetId(0L, packId, assetId);
            ActorSceneAsset e = existing.orElseGet(ActorSceneAsset::new);
            e.setProjectId(0L);
            e.setPackId(packId);
            e.setAssetId(assetId);
            e.setName(text(a, "name"));
            e.setCategory(text(a, "category"));
            e.setDescription(text(a, "description"));
            e.setSizeJson(json(a, "size"));
            e.setFootprintJson(json(a, "footprint"));
            e.setStyleJson(json(a, "style"));
            e.setTagsJson(json(a, "tags"));
            e.setFilePath(urlOf(packId, text(a, "file")));
            e.setCompressed(bool(a, "compressed"));
            e.setPreview(urlOf(packId, text(a, "preview")));
            e.setManual(bool(a, "manual"));
            assetRepository.save(e);
            return new UpsertOutcome(existing.isEmpty());
        } catch (Exception ex) {
            log.warn("[sandbox-assets] 资产登记失败 assetId={}：{}", assetId, ex.getMessage());
            return null;
        }
    }

    // ==================== 查询 ====================

    /**
     * 扫描 uploads/scene-assets/ 下列出全部数据包（含资产数；按包 ID 排序）。
     *
     * @return 数据包摘要列表（无包时为空列表）
     */
    public List<ScenePackVO> listPacks() {
        List<ScenePackVO> out = new ArrayList<>();
        Path root = uploadDir.resolve(PACK_ROOT);
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path dir : ds) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                Path manifest = dir.resolve("manifest.json");
                if (!Files.isRegularFile(manifest)) {
                    continue;
                }
                try {
                    JsonNode rootNode = objectMapper.readTree(manifest.toFile());
                    JsonNode pack = rootNode.path("pack");
                    JsonNode assets = rootNode.path("assets");
                    ScenePackVO vo = new ScenePackVO();
                    vo.setPackId(dir.getFileName().toString());
                    vo.setName(text(pack, "name"));
                    vo.setVersion(text(pack, "version"));
                    vo.setDescription(text(pack, "description"));
                    vo.setAuthor(text(pack, "author"));
                    vo.setUnit(text(pack, "unit"));
                    vo.setAssetCount(assets.isArray() ? assets.size() : 0);
                    out.add(vo);
                } catch (Exception e) {
                    log.warn("[sandbox-assets] 数据包清单解析失败：{}（{}）", dir.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[sandbox-assets] 扫描数据包目录失败：{}", e.getMessage());
        }
        out.sort(Comparator.comparing(ScenePackVO::getPackId));
        return out;
    }

    /**
     * 资产清单（含全局内置包 project_id=0 与项目级资产；可按数据包过滤）。
     *
     * @param projectId 项目 ID（查询该项目可用资产 = 项目级 ∪ 全局内置包）
     *
     * @param packId 可选：数据包 ID 过滤
     *
     * @return 资产 VO 列表
     */
    public List<SceneAssetVO> listAssets(Long projectId, String packId) {
        List<ActorSceneAsset> rows;
        if (packId != null && !packId.isBlank()) {
            requirePackId(packId);
            rows = assetRepository.findByPackIdOrderByIdAsc(packId);
        } else {
            rows = assetRepository.findByProjectIdInOrderByIdAsc(List.of(projectId, 0L));
        }
        return rows.stream().map(SceneAssetVO::of).toList();
    }

    // ==================== 内部 ====================

    /** packId 合法性校验（防路径穿越） */
    private void requirePackId(String packId) {
        if (packId == null || !PACK_ID_PATTERN.matcher(packId).matches()) {
            throw new BizException(400, "数据包 ID 非法（仅字母/数字/下划线/连字符）：" + packId);
        }
    }

    /** 数据包目录（uploads/scene-assets/{packId}） */
    private Path packDir(String packId) {
        return uploadDir.resolve(PACK_ROOT).resolve(packId);
    }

    /** 包内相对路径 → HTTP 相对 URL（uploads/scene-assets/{packId}/{rel}；null 安全） */
    private String urlOf(String packId, String rel) {
        if (rel == null || rel.isBlank()) {
            return null;
        }
        return "uploads/" + PACK_ROOT + "/" + packId + "/" + rel;
    }

    /** JSON 节点取字符串 */
    private String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** JSON 节点序列化（数组/对象 → JSON 字符串；null/缺失 → null） */
    private String json(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON 节点取布尔（缺省 false） */
    private boolean bool(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v != null && v.isBoolean() && v.asBoolean();
    }
}
