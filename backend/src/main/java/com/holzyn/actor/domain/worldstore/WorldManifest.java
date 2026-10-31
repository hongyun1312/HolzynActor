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

import java.util.List;
import java.util.Map;

/**
 * 世界自描述清单（{@code worlds/<uid>/manifest.json}，格式版本 3.0）。
 *
 * <p>为什么需要它：世界库本身是 H2 文件，能读出"有什么表"，但读不出"这是哪个世界、哪个版本
 * 建的、多大、迁移自哪里"。这些正是"拷贝目录到另一台机器后被正确收编"所需的元信息，
 * 也是导入前做兼容性拒绝（而不是打开后才爆）的唯一依据。</p>
 *
 * <p>铁律：<b>不写绝对路径</b>。{@code dbFile} 只能是相对本目录的文件名；一旦出现绝对路径，
 * 整个世界目录就与某台机器绑定，"拷文件夹即带走存档"立刻失效。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 *
 * @param formatVersion 清单格式版本（当前 {@link #CURRENT_FORMAT_VERSION}；不匹配即拒绝打开）
 * @param schemaVersion 建库时的应用/schema 版本（只作诊断展示，不作硬门槛——真正的列兼容由 Flyway 保证）
 * @param worldUid      世界身份（= 目录名 = {@code actor_project.project_uid}）
 * @param worldName     世界显示名（可中文，仅展示，不参与路径）
 * @param createdAt     创建时间（ISO-8601 本地时间字符串）
 * @param lastOpenedAt  最近一次 open 时间（可空）
 * @param lastClosedAt  最近一次 close 时间（可空）
 * @param source        来源（新建 / 旧库迁移 / 导入），空表示新建
 * @param counts        关键表行数快照（迁移/导入校验用）
 * @param checksums     关键表 sha256 快照（迁移/导入校验用）
 * @param resources     该世界目录下的资源子目录名（vector-store / worldmap / worldgen / uploads）
 * @param dbFile        世界库文件名（相对本目录，默认 {@code world.mv.db}）
 */
public record WorldManifest(
        String formatVersion,
        String schemaVersion,
        String worldUid,
        String worldName,
        String createdAt,
        String lastOpenedAt,
        String lastClosedAt,
        Source source,
        Map<String, Long> counts,
        Map<String, String> checksums,
        List<String> resources,
        String dbFile) {

    /** 当前清单格式版本（升到 4.0 时必须同时提供迁移或明确拒绝旧档） */
    public static final String CURRENT_FORMAT_VERSION = "3.0";

    /**
     * 当前应用/schema 版本（写入 {@code schemaVersion}）。
     *
     * <p>只作诊断展示，不作硬门槛（见 {@link WorldManifestService}）；M7 统一版本号时随
     * {@code backend/pom.xml}、{@code VERSION}、{@code package.json} 一起改。</p>
     */
    public static final String SCHEMA_VERSION = "1.1.2-beta.1";

    /** 世界库默认文件名 */
    public static final String DEFAULT_DB_FILE = "world.mv.db";

    /**
     * 来源描述。
     *
     * @param kind       来源类型：{@code new} / {@code legacy-split} / {@code import}
     * @param from       来源文件或世界标识（如 {@code holzyn-actor.mv.db}）
     * @param fromSha256 来源文件 sha256（迁移留证，可空）
     * @param at         发生时间（ISO-8601，可空）
     */
    public record Source(String kind, String from, String fromSha256, String at) {
    }

    /** 便捷构造：新建世界的 manifest（其余计数/校验和先空，由挂载后回写） */
    public static WorldManifest fresh(String uid, String name, String schemaVersion, String now) {
        return new WorldManifest(CURRENT_FORMAT_VERSION, schemaVersion, uid, name, now, null, null,
                new Source("new", null, null, now), Map.of(), Map.of(),
                List.of("vector-store", "worldmap", "worldgen", "uploads"), DEFAULT_DB_FILE);
    }

    /** 便捷派生：把运行态三字段（最近打开/关闭时间）替换掉 */
    public WorldManifest withTimes(String openedAt, String closedAt) {
        return new WorldManifest(formatVersion, schemaVersion, worldUid, worldName, createdAt,
                openedAt, closedAt, source, counts, checksums, resources, dbFile);
    }
}
