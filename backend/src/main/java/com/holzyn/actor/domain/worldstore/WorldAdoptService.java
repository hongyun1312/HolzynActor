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

import com.holzyn.actor.domain.account.service.LocalAccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 启动收编：把 {@code worlds/*}{@code /manifest.json} 反推成目录库索引（V1.1.2-beta.1）。
 *
 * <p><b>为什么必须有这一步</b>：分档的卖点是"世界 = 一个能整目录搬走的文件夹"。如果索引只能靠
 * 原来的目录库带过来，那么这个目录库就成了"必须原样搬走的账本"，搬存档就变成搬整个数据库——
 * 等于没分。收编让目录库退化成<b>可由磁盘重建的缓存</b>：把 {@code worlds/<uid>/} 拷到
 * 另一台机器的数据目录下重启，世界就出现在列表里。</p>
 *
 * <p>四种不一致的处理（与设计 §3.1.1 表逐行对应）：</p>
 * <table border="1">
 *   <caption>磁盘 / 索引对照</caption>
 *   <tr><th>磁盘</th><th>索引</th><th>动作</th></tr>
 *   <tr><td>有目录 + manifest</td><td>无行</td><td>收编：插入索引行（不自动挂载）</td></tr>
 *   <tr><td>有目录 + manifest</td><td>有行</td><td>按清单兼容性刷新状态</td></tr>
 *   <tr><td>无目录</td><td>有行</td><td>标 {@code missing}，<b>不删行</b></td></tr>
 *   <tr><td>有目录无 manifest</td><td>—</td><td>忽略（{@code resources/} 之类不会被误认成世界）</td></tr>
 * </table>
 *
 * <p>两条刻意的保守设计：</p>
 * <ol>
 *   <li><b>不删行</b>：目录可能只是被用户临时挪走/换了个盘挂载，删行等于丢身份（uid 与用量汇总
 *       都在行上）。标 missing 可恢复，删除不可恢复。</li>
 *   <li><b>{@code worlds/} 整个不存在时不做任何"缺失"判定</b>：M2 过渡态里应用还跑在旧的单体库上，
 *       此时磁盘上一个世界目录都没有，若无脑把所有行标成 missing，用户会在升级途中看到
 *       "所有存档都不见了"。只有在"这个部署确实启用了分档（worlds/ 存在）"时才做缺失比对。</li>
 * </ol>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Service
public class WorldAdoptService {

    private final WorldPaths paths;

    private final WorldManifestService manifests;

    private final WorldIndexDao indexDao;

    public WorldAdoptService(WorldPaths paths, WorldManifestService manifests, WorldIndexDao indexDao) {
        this.paths = paths;
        this.manifests = manifests;
        this.indexDao = indexDao;
    }

    /**
     * 收编报告。
     *
     * @param scanned       磁盘上扫描到的世界目录数
     * @param adopted       新收编（索引里原本没有）的世界数
     * @param incompatible  版本不兼容的世界数
     * @param missing       被标记为"目录缺失"的索引行数
     * @param resetMounted  被从 {@code mounted} 归零为 {@code closed} 的行数（上次异常退出）
     * @param problems      逐条问题描述（供启动日志与后续 UI 展示）
     */
    public record AdoptReport(int scanned, int adopted, int incompatible, int missing,
                              int resetMounted, List<String> problems) {

        /** 是否一切正常 */
        public boolean clean() {
            return problems.isEmpty();
        }
    }

    /**
     * 执行收编（幂等：反复调用不会重复插入）。
     *
     * @return 收编报告
     */
    public AdoptReport adopt() {
        LocalDateTime now = LocalDateTime.now();
        List<String> problems = new ArrayList<>();

        // 0. 索引列未迁移（M2 过渡态/迁移失败）：什么都不做。
        //    放在最前面而不是"先尝试归零再跳过"——后者会在每次启动打一条
        //    「归零失败」WARN，把"正常过渡态"伪装成"出故障"，日志一乱就没人看了。
        Set<String> missingColumns = indexDao.missingColumns();
        if (!missingColumns.isEmpty()) {
            log.info("[world-store] 跳过启动收编：目录库 actor_project 缺少存档索引列 {}（V101 未执行）", missingColumns);
            return new AdoptReport(0, 0, 0, 0, 0, problems);
        }

        // 1. 上次异常退出残留的 mounted 归零（否则列表永远显示"运行中"且点不开）
        int reset = 0;
        try {
            reset = indexDao.resetMountedOnStartup(now);
        } catch (RuntimeException e) {
            problems.add("归零残留挂载状态失败：" + e.getMessage());
            log.warn("[world-store] 归零残留挂载状态失败（继续收编）：{}", e.toString());
        }

        // 2. worlds/ 不存在 → 本部署还没启用分档，不做缺失判定（见类注释）
        if (!Files.isDirectory(paths.worldsRoot())) {
            log.info("[world-store] 世界根目录不存在，跳过分档收编：{}", paths.worldsRoot());
            return new AdoptReport(0, 0, 0, 0, reset, problems);
        }

        List<Path> dirs = paths.listWorldDirs();
        Set<String> onDisk = new LinkedHashSet<>();
        int adopted = 0;
        int incompatible = 0;

        for (Path dir : dirs) {
            String uid = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (uid.isBlank()) {
                continue;
            }
            onDisk.add(uid);
            String name = null;
            String schemaVersion = null;
            String manifestJson = null;
            WorldStatus status = WorldStatus.CLOSED;
            try {
                WorldManifest manifest = manifests.read(dir);
                if (manifest == null) {
                    continue;
                }
                name = manifest.worldName();
                schemaVersion = manifest.schemaVersion();
                manifestJson = rawManifestText(dir);
                try {
                    manifests.requireCompatible(dir, manifest);
                } catch (WorldManifestException e) {
                    status = WorldStatus.INCOMPATIBLE;
                    incompatible++;
                    problems.add("版本不兼容：" + uid + " — " + e.getMessage());
                }
            } catch (WorldManifestException | IllegalArgumentException e) {
                status = WorldStatus.BROKEN;
                problems.add("清单损坏：" + uid + " — " + e.getMessage());
            }

            Optional<WorldIndexDao.WorldIndexRow> existing = indexDao.readByUid(uid);
            if (existing.isEmpty()) {
                try {
                    long id = indexDao.insertIndexRow(uid, name, LocalAccountService.LOCAL_USER_ID,
                            status, schemaVersion, manifestJson, now);
                    adopted++;
                    log.info("[world-store] 已收编世界 uid={} name={} id={} 状态={}", uid, name, id, status.code());
                } catch (RuntimeException e) {
                    problems.add("收编失败：" + uid + " — " + e.getMessage());
                    log.warn("[world-store] 收编世界失败 uid={}：{}", uid, e.toString());
                }
            } else if (existing.get().status() != status) {
                // 状态修正：版本被换了、目录被改坏、或上次标了 incompatible 而现在兼容了
                try {
                    indexDao.markStatus(existing.get().id(), status, now);
                    log.info("[world-store] 世界状态修正 uid={} {} → {}", uid, existing.get().status().code(), status.code());
                } catch (RuntimeException e) {
                    problems.add("状态修正失败：" + uid + " — " + e.getMessage());
                }
            }
        }

        // 3. 索引里有、磁盘上没有 → 标 missing（不删行）
        int missing = 0;
        for (WorldIndexDao.WorldIndexRow row : indexDao.readAll()) {
            String uid = row.worldUid();
            if (uid == null || uid.isBlank() || onDisk.contains(uid)) {
                continue;
            }
            if (row.status() == WorldStatus.MISSING) {
                missing++;
                continue;
            }
            try {
                indexDao.markStatus(row.id(), WorldStatus.MISSING, now);
                missing++;
                problems.add("目录缺失：" + row.name() + "（" + uid + "）");
            } catch (RuntimeException e) {
                problems.add("标记目录缺失失败：" + uid + " — " + e.getMessage());
            }
        }

        AdoptReport report = new AdoptReport(dirs.size(), adopted, incompatible, missing, reset, List.copyOf(problems));
        log.info("[world-store] 启动收编完成：扫描 {} 目录 / 新收编 {} / 不兼容 {} / 目录缺失 {} / 状态归零 {}",
                report.scanned(), report.adopted(), report.incompatible(), report.missing(), report.resetMounted());
        for (String p : report.problems()) {
            log.warn("[world-store]   {}", p);
        }
        return report;
    }

    /** 读取清单原文（写入索引做快照，列表页不必再读磁盘） */
    private static String rawManifestText(Path dir) {
        try {
            Path file = dir.resolve(WorldManifestService.FILE_NAME);
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            log.debug("[world-store] 读取清单原文失败，快照留空：{}：{}", dir, e.toString());
            return null;
        }
    }
}
