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

package com.holzyn.actor.domain.worldstore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界清单读写与兼容性校验（V1.1.2-beta.1）。
 *
 * <p>三条刻意设计：</p>
 * <ol>
 *   <li><b>原子写</b>：先写 {@code manifest.json.tmp} 再 move 覆盖。世界关闭/迁移过程中被
 *       强杀（桌面端 8s 超时会 taskkill）时，宁可保留上一份完整清单，也不要留下半截 JSON
 *       ——半截清单会让下次启动把这个世界判成"损坏"而拒之门外。</li>
 *   <li><b>手写字段映射</b>：不用注解绑定，改为逐字段读 {@code JsonNode}。原因是要给
 *       "缺字段/类型不对"精确到字段名的错误信息；反射绑定失败时只知道"反序列化异常"，
 *       对用户和排查都没用。</li>
 *   <li><b>硬门槛只有 formatVersion</b>：{@code schemaVersion} 只作诊断展示。真正的 schema
 *       兼容由挂载时的 Flyway 迁移保证（世界库会被升到 V200+），拿应用版本号当门槛只会
 *       在每次小版本升级后把所有存档判成不兼容。</li>
 * </ol>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Service
public class WorldManifestService {

    /** 清单文件名（与 {@link WorldPaths#manifestFile(String)} 同源，改一处必须改两处，测试会盯） */
    public static final String FILE_NAME = "manifest.json";

    private static final String TMP_SUFFIX = ".tmp";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 写入世界清单（原子替换）。
     *
     * @param worldDir 世界目录
     *
     * @param manifest 清单内容
     */
    public void write(Path worldDir, WorldManifest manifest) {
        try {
            Files.createDirectories(worldDir);
            Path target = worldDir.resolve(FILE_NAME);
            Path tmp = worldDir.resolve(FILE_NAME + TMP_SUFFIX);
            Files.writeString(tmp, toJson(manifest), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 部分网络盘/容器卷不支持原子 move：退化为普通替换（仍比直接 truncate 写安全）
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入世界清单失败：" + worldDir, e);
        }
    }

    /**
     * 读取世界清单。
     *
     * @param worldDir 世界目录
     *
     * @return 清单；文件不存在时返回 {@code null}（调用方据此判定"不是世界目录"）
     *
     * @throws WorldManifestException 文件存在但不可解析/字段缺失
     */
    public WorldManifest read(Path worldDir) {
        Path file = worldDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WorldManifestException("读取世界清单失败：" + file, e);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (RuntimeException e) {
            throw new WorldManifestException("世界清单不是合法 JSON：" + file, e);
        }
        if (root == null || !root.isObject()) {
            throw new WorldManifestException("世界清单根节点必须是对象：" + file);
        }
        return fromJson(file, root);
    }

    /**
     * 读取并做兼容性校验（收编/挂载前唯一入口）。
     *
     * @param worldDir 世界目录
     *
     * @return 已校验的清单
     *
     * @throws WorldManifestException 不存在或不兼容
     */
    public WorldManifest readCompatible(Path worldDir) {
        WorldManifest manifest = read(worldDir);
        if (manifest == null) {
            throw new WorldManifestException("不是世界目录（缺 " + FILE_NAME + "）：" + worldDir);
        }
        requireCompatible(worldDir, manifest);
        return manifest;
    }

    /**
     * 兼容性校验。
     *
     * @param worldDir 世界目录（用于比对目录名与 uid）
     *
     * @param manifest 待校验清单
     *
     * @throws WorldManifestException 不兼容；消息里带修法
     */
    public void requireCompatible(Path worldDir, WorldManifest manifest) {
        if (!WorldManifest.CURRENT_FORMAT_VERSION.equals(manifest.formatVersion())) {
            throw new WorldManifestException("存档格式不兼容：需要 " + WorldManifest.CURRENT_FORMAT_VERSION
                    + "，实际 " + manifest.formatVersion() + "（" + worldDir + "）。"
                    + "该存档由更旧/更新的版本创建，请用对应版本的 HolzynActor 打开后再迁移。");
        }
        String dirName = worldDir.getFileName() == null ? "" : worldDir.getFileName().toString();
        if (!dirName.isBlank() && !dirName.equals(manifest.worldUid())) {
            throw new WorldManifestException("世界身份与目录名不一致：目录=" + dirName
                    + "，清单 worldUid=" + manifest.worldUid() + "（" + worldDir + "）。"
                    + "通常是拷贝时手动改了目录名；请把目录名改回 worldUid，或在列表里用「导入」重建索引。");
        }
        String dbFile = manifest.dbFile();
        if (dbFile == null || dbFile.isBlank()) {
            throw new WorldManifestException("清单缺 dbFile 字段：" + worldDir);
        }
        if (dbFile.contains("/") || dbFile.contains("\\") || dbFile.contains(":")) {
            throw new WorldManifestException("清单 dbFile 必须是本目录内的文件名，不允许路径/盘符："
                    + dbFile + "（" + worldDir + "）——绝对路径会让存档与机器绑定，失去可搬移性。");
        }
        if (manifest.worldName() == null || manifest.worldName().isBlank()) {
            throw new WorldManifestException("清单缺世界名 worldName：" + worldDir);
        }
    }

    /**
     * 序列化（紧凑 JSON；字段顺序固定，便于 diff 与人工核对）。
     *
     * <p>对外可见是为了导出：{@code WorldArchiveService} 要把清单直接写进 zip 条目，而不是先落盘再读。
     * 反序列化仍只走 {@link #read(Path)}（一处校验、一处口径）。</p>
     *
     * @param m 清单
     *
     * @return JSON 文本
     */
    public static String toJson(WorldManifest m) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("formatVersion", m.formatVersion());
        putIfPresent(root, "schemaVersion", m.schemaVersion());
        root.put("worldUid", m.worldUid());
        root.put("worldName", m.worldName());
        putIfPresent(root, "createdAt", m.createdAt());
        putIfPresent(root, "lastOpenedAt", m.lastOpenedAt());
        putIfPresent(root, "lastClosedAt", m.lastClosedAt());

        if (m.source() != null) {
            ObjectNode src = root.putObject("source");
            putIfPresent(src, "kind", m.source().kind());
            putIfPresent(src, "from", m.source().from());
            putIfPresent(src, "fromSha256", m.source().fromSha256());
            putIfPresent(src, "at", m.source().at());
        }
        ObjectNode counts = root.putObject("counts");
        if (m.counts() != null) {
            m.counts().forEach((k, v) -> {
                if (v != null) {
                    counts.put(k, v.longValue());
                }
            });
        }
        ObjectNode checksums = root.putObject("checksums");
        if (m.checksums() != null) {
            m.checksums().forEach((k, v) -> putIfPresent(checksums, k, v));
        }
        ArrayNode resources = root.putArray("resources");
        if (m.resources() != null) {
            m.resources().forEach(r -> {
                if (r != null) {
                    resources.add(r);
                }
            });
        }
        root.put("dbFile", m.dbFile() == null ? WorldManifest.DEFAULT_DB_FILE : m.dbFile());
        return MAPPER.writeValueAsString(root);
    }

    private static void putIfPresent(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank()) {
            node.put(key, value);
        }
    }

    private static WorldManifest fromJson(Path file, JsonNode root) {
        String format = text(root, "formatVersion", file);
        String uid = text(root, "worldUid", file);
        String name = text(root, "worldName", file);

        JsonNode srcNode = root.get("source");
        WorldManifest.Source source = null;
        if (srcNode != null && srcNode.isObject()) {
            source = new WorldManifest.Source(
                    optText(srcNode, "kind"), optText(srcNode, "from"),
                    optText(srcNode, "fromSha256"), optText(srcNode, "at"));
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        JsonNode countsNode = root.get("counts");
        if (countsNode != null && countsNode.isObject()) {
            countsNode.properties().forEach(e -> {
                if (e.getValue() != null && e.getValue().isNumber()) {
                    counts.put(e.getKey(), e.getValue().asLong());
                }
            });
        }

        Map<String, String> checksums = new LinkedHashMap<>();
        JsonNode sumsNode = root.get("checksums");
        if (sumsNode != null && sumsNode.isObject()) {
            sumsNode.properties().forEach(e -> {
                if (e.getValue() != null && !e.getValue().isNull()) {
                    checksums.put(e.getKey(), e.getValue().asText());
                }
            });
        }

        List<String> resources = new ArrayList<>();
        JsonNode resNode = root.get("resources");
        if (resNode != null && resNode.isArray()) {
            resNode.forEach(n -> {
                if (n != null && !n.isNull()) {
                    resources.add(n.asText());
                }
            });
        }

        String dbFile = optText(root, "dbFile");
        return new WorldManifest(format, optText(root, "schemaVersion"), uid, name,
                optText(root, "createdAt"), optText(root, "lastOpenedAt"), optText(root, "lastClosedAt"),
                source, counts, checksums, resources,
                dbFile == null || dbFile.isBlank() ? WorldManifest.DEFAULT_DB_FILE : dbFile);
    }

    /** 必填字段（缺失即抛，错误信息带文件路径与字段名） */
    private static String text(JsonNode root, String key, Path file) {
        String v = optText(root, key);
        if (v == null || v.isBlank()) {
            throw new WorldManifestException("世界清单缺必填字段 " + key + "：" + file);
        }
        return v;
    }

    private static String optText(JsonNode root, String key) {
        JsonNode node = root.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }
}
