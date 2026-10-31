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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 一次性迁移器：旧单库 → 「目录库 + 每世界一库」（V1.1.2-beta.1 M3）。
 *
 * <p>这是本版本<b>唯一会移动用户数据</b>的代码，所以三个原则是硬的：</p>
 * <ol>
 *   <li><b>先归档，再动手</b>：先把旧库文件与两个真实世界的资源整份复制进
 *       {@code archive/pre-worldsplit-<ts>/} 并校验 sha256，之后所有写只发生在新文件上。
 *       归档失败 = 迁移不开始。</li>
 *   <li><b>不写源库</b>：迁移只对旧库发 select。"回填 {@code project_id}" 不是改旧库，
 *       而是把归属算出来、在<b>世界库</b>里写实（见 {@link WorldSplitRules.RowRule#needsStamp()}）。</li>
 *   <li><b>全过才算过</b>：逐世界逐表行数 + 关键表内容校验和 + 时钟三字段逐位比对，
 *       任一失败即整体失败并留报告；不允许"部分成功"——那会让用户以为一半数据还在。</li>
 * </ol>
 *
 * <p><b>为什么是 {@code @Order(0)}</b>：必须早于 {@link WorldStartupRunner}（收编扫描）、
 * {@code DatabaseStructureValidator}（校验目录库表）与各类种子/回填 Runner；否则启动收编会
 * 在"目录库还空着、旧库还没拆"的状态下把 {@code worlds/} 判成缺失，写出错误的
 * {@code missing} 状态。</p>
 *
 * <p><b>幂等与"半成品"防护</b>：状态写进 {@code <data-dir>/worldsplit.state}。</p>
 * <ul>
 *   <li>{@code done} → 永不再跑（即使用户后来删了目录库，也应从 {@code worlds/} 收编重建，
 *       而不是再拆一次旧库）；</li>
 *   <li>{@code in-progress} / {@code failed} → <b>启动即失败</b>并给出处置说明。半成品状态最危险：
 *       目录库已有行、世界库已建好一部分，此时"跳过迁移继续启动"会让用户看到残缺的存档列表。</li>
 * </ul>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Component
@Order(0)
public class LegacyWorldSplitMigrator implements ApplicationRunner {

    /** 归档目录名前缀（{@code pre-worldsplit-<yyyyMMdd-HHmmss>}） */
    public static final String ARCHIVE_PREFIX = "pre-worldsplit-";

    /** 迁移状态文件名（位于 data-dir 下） */
    public static final String STATE_FILE = "worldsplit.state";

    /** 归档里的清单名 */
    public static final String ARCHIVE_MANIFEST = "archive-manifest.json";

    /** 关键表：内容校验和必须逐位一致（AC3） */
    public static final List<String> CHECKSUM_TABLES = List.of(
            "actor_profile", "actor_memory", "actor_event", "actor_world_chronicle",
            "actor_world_location", "actor_state", "actor_world_clock");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final WorldPaths paths;

    private final WorldStoreProperties properties;

    private final DataSource registry;

    private final WorldDataSourceFactory dataSources;

    private final WorldMirrorSyncService mirrors;

    private final WorldManifestService manifests;

    private final WorldIndexDao indexDao;

    /**
     * 世界摘要读取器（迁移后一次性把世界的用量总量抄进目录库，M4）。
     *
     * <p>为什么字段注入而不进构造器：AGENTS §8.1.2 禁止改既有服务的构造器签名
     * （测试构造点会全部被打断）；{@code required=false} 让单测里可以缺省，
     * 缺省时只跳过"写用量摘要"这一步，不影响迁移正确性。</p>
     */
    @Autowired(required = false)
    private WorldSummaryReader summaryReader;

    private final String username;

    private final String password;

    public LegacyWorldSplitMigrator(WorldPaths paths,
                                    WorldStoreProperties properties,
                                    @Qualifier("registryDataSource") DataSource registry,
                                    WorldDataSourceFactory dataSources,
                                    WorldMirrorSyncService mirrors,
                                    WorldManifestService manifests,
                                    WorldIndexDao indexDao,
                                    @Value("${spring.datasource.username:sa}") String username,
                                    @Value("${spring.datasource.password:}") String password) {
        this.paths = paths;
        this.properties = properties;
        this.registry = registry;
        this.dataSources = dataSources;
        this.mirrors = mirrors;
        this.manifests = manifests;
        this.indexDao = indexDao;
        this.username = username;
        this.password = password;
    }

    /**
     * 启动入口：满足条件才迁移，失败即让启动失败（不留半可用状态）。
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        migrateIfNeeded();
    }

    /**
     * 迁移结果。
     *
     * @param migrated  是否真的执行了迁移（false = 不满足触发条件而跳过）
     * @param dryRun    是否演练（未写盘）
     * @param pass      是否全部校验通过
     * @param reportJson 报告文件（dry-run 或跳过时为 null）
     * @param archiveDir 归档目录（dry-run 或跳过时为 null）
     * @param worlds    逐世界结果
     * @param warnings  全局告警
     * @param errors    全局错误
     */
    public record Outcome(boolean migrated, boolean dryRun, boolean pass, Path reportJson,
                          String archiveDir, List<WorldSplitReport.WorldPart> worlds,
                          List<String> warnings, List<String> errors) {
    }

    /** 旧库里的一个世界行（迁移输入） */
    record LegacyWorld(long id, String uid, String name, Integer deleted) {
    }

    /**
     * 触发判定 + 迁移（启动调用）。
     *
     * @return 迁移结果；不满足条件时 {@code migrated=false}
     *
     * @throws IllegalStateException 上次迁移未完成/失败、或本次迁移校验失败（启动必须失败）
     */
    public Outcome migrateIfNeeded() {
        Path legacy = paths.legacyDbFile();
        Optional<String> state = readState();
        if (state.isPresent() && state.get().startsWith("done")) {
            log.info("[world-store] 旧库迁移已完成（{}），跳过：{}", state.get(), stateFile());
            return new Outcome(false, false, true, null, null, List.of(), List.of(), List.of());
        }
        if (state.isPresent() && (state.get().startsWith("in-progress") || state.get().startsWith("failed"))) {
            // 半成品最危险：继续启动会让用户看到"部分世界"，而漏掉的世界没有任何提示
            throw new IllegalStateException("上次世界数据分存档迁移未完成或失败（" + stateFile() + "："
                    + state.get() + "）。处置：确认旧库归档在 " + paths.archiveRoot()
                    + " 之后，删除 " + paths.registryDbFile() + "、清除 " + paths.worldsRoot()
                    + " 与本状态文件，再重启重试。");
        }
        if (!properties.isMigrateLegacy()) {
            log.info("[world-store] 旧库自动迁移已关闭（holzyn.actor.world-store.migrate-legacy=false），跳过");
            return new Outcome(false, false, true, null, null, List.of(), List.of(), List.of());
        }
        if (!Files.isRegularFile(legacy)) {
            log.debug("[world-store] 未发现旧单库，无需迁移：{}", legacy);
            return new Outcome(false, false, true, null, null, List.of(), List.of(), List.of());
        }
        long existing = registryProjectCount();
        if (existing > 0) {
            log.info("[world-store] 目录库已有 {} 个世界索引，跳过旧库迁移（避免重复拆库）", existing);
            return new Outcome(false, false, true, null, null, List.of(), List.of(), List.of());
        }
        requireRegistryIsNotLegacy();
        log.warn("[world-store] 检测到旧单库 {} 且目录库为空 → 开始一次性迁移（先归档，再拆库）", legacy);
        return migrate(false);
    }

    /**
     * 执行迁移（幂等性由调用方的状态文件与目录库判断保证；本方法自身不重复检查）。
     *
     * <p>dry-run 语义：不建世界库、不归档、不写目录库、不写报告与状态文件。
     * <b>唯一例外</b>是「旧库表名升级」——幂等的纯改名（{@code actor_nanojev_ab_*} →
     * {@code actor_decision_ab_*}），不搬一行数据；不先改名，按新表名统计源行数会直接找不到表。</p>
     *
     * @param dryRun true = 只统计不落盘（用于真实库演练：能提前看到逐表行数，且不改动任何数据）
     *
     * @return 迁移结果
     *
     * @throws IllegalStateException 校验失败（此时报告已写出，归档已就绪）
     */
    public Outcome migrate(boolean dryRun) {
        long started = System.currentTimeMillis();
        Path legacy = paths.legacyDbFile();
        if (!Files.isRegularFile(legacy)) {
            throw new IllegalStateException("旧单库不存在，无法迁移：" + legacy);
        }
        String generatedAt = LocalDateTime.now().format(ISO);
        long sourceBytes = sizeOf(legacy);
        String sha256 = sha256(legacy);
        String tag = ARCHIVE_PREFIX + LocalDateTime.now().format(TS);

        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<WorldSplitReport.WorldPart> worldParts = new ArrayList<>();
        String archiveDir = paths.archiveDir(tag).toString();

        if (!dryRun) {
            writeState("in-progress " + generatedAt);
            // 归档旧库文件必须赶在 openLegacy 之前：H2 2.x 会给自己的 mv.db 加文件锁，
            // 连接开着时复制在 Windows 上直接报"另一个程序已锁定文件的一部分"（实测）。
            archiveLegacyDb(legacy, tag, sha256);
        }

        try (Connection legacyConn = openLegacy(legacy)) {
            // 0. 旧库表名升级（决策模型通用化：actor_nanojev_ab_* → actor_decision_ab_*）。
            //    必须在任何搬表之前：搬表按 worldsplit-tables.json 的新表名取列，
            //    旧库若仍是旧名，commonColumns 取不到 → 「世界库缺少表」直接阻断启动。
            //    dry-run 也要做：统计源行数用的同样是新表名，不改名连"预计行数"都算不出来。
            //    它是幂等的纯改名（不动一行数据），重复执行是空操作。
            upgradeLegacyTableNames(legacyConn, warnings);
            List<LegacyWorld> worlds = readLegacyWorlds(legacyConn);
            if (worlds.isEmpty()) {
                warnings.add("旧库里没有任何世界行（actor_project 为空）：无需拆库，仅归档");
            }

            // 1. 归档世界资源（旧库文件已在打开连接前归档）。dry-run 只算 sha256，不写盘。
            if (dryRun) {
                log.info("[world-store] dry-run：仅统计，不归档、不写库（旧库 sha256={}）", sha256);
            } else {
                archiveResources(paths.archiveDir(tag), legacy, sha256, sourceBytes, worlds, legacyConn, warnings);
            }

            // 2. 目录库：15 张权威/观测表 + actor_project 索引行
            if (!dryRun) {
                copyRegistryTables(legacyConn, warnings);
                insertProjectRows(legacyConn);
            }

            // 3. 逐世界建库、搬行、资源、镜像、清单、校验
            for (LegacyWorld w : worlds) {
                long t0 = System.currentTimeMillis();
                String uid = w.uid();
                if (uid == null || uid.isBlank()) {
                    uid = UUID.randomUUID().toString();
                    if (!dryRun) {
                        assignUid(w.id(), uid);
                    }
                }
                try {
                    WorldSplitReport.WorldPart part = dryRun
                            ? dryRunWorld(legacyConn, w, uid)
                            : migrateWorld(legacyConn, w, uid, sha256, generatedAt);
                    worldParts.add(part);
                } catch (RuntimeException e) {
                    log.error("[world-store] 世界迁移失败 id={} uid={}", w.id(), uid, e);
                    errors.add("世界 " + w.name() + "（id=" + w.id() + "）迁移失败：" + e.getMessage());
                    worldParts.add(failedWorld(w, uid, e));
                }
            }

            // 4. 孤儿行审计（不静默丢弃：子表/回填表里选不出来的行必须留证）
            auditOrphans(legacyConn, worldParts, warnings);
        } catch (SQLException e) {
            errors.add("迁移过程中数据库操作失败：" + e.getMessage());
        }

        if (!dryRun) {
            // 迁移完把世界库全部关掉（COMPACT 落盘 + 释放锁），应用以"无挂载世界"状态继续启动
            dataSources.releaseAll();
        }

        WorldSplitReport.Meta meta = new WorldSplitReport.Meta(generatedAt, legacy.toString(), sourceBytes,
                sha256, dryRun ? "（dry-run 未归档）" : archiveDir, paths.registryDbFile().toString(),
                paths.worldsRoot().toString(), dryRun, System.currentTimeMillis() - started);
        boolean pass = errors.isEmpty()
                && (dryRun || worldParts.stream().allMatch(WorldSplitReport.WorldPart::pass));

        Path reportJson = null;
        if (!dryRun) {
            reportJson = WorldSplitReport.write(reportDir(), meta, worldParts, warnings, errors);
            if (pass) {
                writeState("done " + generatedAt);
            } else {
                writeState("failed " + generatedAt + " " + String.join("；", errors));
                throw new IllegalStateException("世界数据分存档迁移校验失败（报告：" + reportJson
                        + "）。归档与旧库仍在，可修复后重试；若不修，应用不可用是刻意的——"
                        + "半迁移状态比彻底失败更难排查。");
            }
        }

        log.info("[world-store] 旧库迁移完成：世界 {} 个 / 行数 {} / 耗时 {}ms / 归档 {}",
                worldParts.size(), worldParts.stream().mapToLong(WorldSplitReport.WorldPart::totalRows).sum(),
                System.currentTimeMillis() - started, dryRun ? "（dry-run）" : archiveDir);
        return new Outcome(true, dryRun, pass, reportJson, dryRun ? null : archiveDir,
                List.copyOf(worldParts), List.copyOf(warnings), List.copyOf(errors));
    }

    // ------------------------------------------------------------------ 归档

    /**
     * 归档旧库文件本身（边复制边算哈希，写完 fsync，再核对与源文件一致）。
     *
     * <p><b>调用时机是硬约束</b>：必须在 {@link #openLegacy(Path)} 之前。H2 2.x 会给自己的
     * {@code mv.db} 加文件锁，连接开着时 {@code Files.copy} 在 Windows 上抛
     * {@code 另一个程序已锁定文件的一部分}——这不是"偶发 IO 错误"，是必然。</p>
     */
    private void archiveLegacyDb(Path legacy, String tag, String sha256) {
        Path dir = paths.archiveDir(tag);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(legacy.getFileName().toString());
            String copied = copyAndHash(legacy, target);
            if (!copied.equals(sha256)) {
                throw new IllegalStateException("旧库归档校验失败（复制后 sha256 不一致）：" + target
                        + "，源=" + sha256 + "，副本=" + copied);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("归档失败（迁移不开始）：" + dir, e);
        }
        log.info("[world-store] 旧库文件已归档：{}（sha256={}）", dir, sha256);
    }

    /**
     * 归档每个被迁移世界的资源（向量库/地图图块/worldgen 产物），并写出归档清单。
     *
     * <p>为什么保留 {@code legacyConn} 参数：worldgen 产物目录是按 <b>任务 id</b> 命名的，
     * 只有查库才能知道某个世界有哪些任务（目录名与归属之间没有别的推导路径）。</p>
     */
    private void archiveResources(Path dir, Path legacy, String sha256, long sourceBytes,
                                  List<LegacyWorld> worlds, Connection legacyConn, List<String> warnings)
            throws SQLException {
        try {
            Files.createDirectories(dir);
            List<Map<String, Object>> resources = new ArrayList<>();
            for (LegacyWorld w : worlds) {
                String uid = w.uid() == null || w.uid().isBlank() ? "id-" + w.id() : w.uid();
                archiveResource(paths.legacyVectorStoreRoot().resolve(String.valueOf(w.id())),
                        dir.resolve("vector-store").resolve(uid), "vector-store/" + uid, resources);
                archiveResource(paths.legacyWorldmapRoot().resolve(String.valueOf(w.id())),
                        dir.resolve("worldmap").resolve(uid), "worldmap/" + uid, resources);
                for (Long taskId : worldgenTaskIds(legacyConn, w.id())) {
                    archiveResource(paths.legacyWorldgenRoot().resolve(String.valueOf(taskId)),
                            dir.resolve("worldgen").resolve(String.valueOf(taskId)),
                            "worldgen/" + taskId, resources);
                }
            }
            // 留档：原地未复制的资源残骸（不删、不搬，只列名）
            List<String> leftovers = leftoverResourceDirs(worlds);
            if (!leftovers.isEmpty()) {
                warnings.add("旧资源目录里另有 " + leftovers.size() + " 个不属于现存世界的残骸目录，"
                        + "已原地保留并在 " + dir.resolve("resource-leftovers.json") + " 列出（未复制，节省 "
                        + "约 " + humanBytes(sumBytes(paths.legacyWorldmapRoot())) + "）");
            }
            writeArchiveManifest(dir, legacy, sha256, sourceBytes, resources, leftovers);
        } catch (IOException e) {
            throw new UncheckedIOException("归档失败（迁移不开始）：" + dir, e);
        }
        log.info("[world-store] 世界资源已归档：{}", dir);
    }

    private void archiveResource(Path src, Path dst, String name, List<Map<String, Object>> out) {
        if (!Files.isDirectory(src)) {
            return;
        }
        try {
            long files = copyDir(src, dst);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", name);
            entry.put("files", files);
            entry.put("bytes", dirBytes(dst));
            out.add(entry);
        } catch (IOException e) {
            throw new UncheckedIOException("归档资源失败：" + src + " → " + dst, e);
        }
    }

    /** 写出归档清单（旧库 sha256/大小/时间 + 逐资源文件数与字节数 + 未复制的残骸） */
    private void writeArchiveManifest(Path dir, Path legacy, String sha256, long sourceBytes,
                                      List<Map<String, Object>> resources, List<String> leftovers)
            throws IOException {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("archiveKind", "pre-worldsplit");
        root.put("generatedAt", LocalDateTime.now().format(ISO));
        var db = root.putObject("legacyDb");
        db.put("fileName", legacy.getFileName().toString());
        db.put("bytes", sourceBytes);
        db.put("sha256", sha256);
        var res = root.putArray("resources");
        for (Map<String, Object> entry : resources) {
            var n = res.addObject();
            n.put("path", String.valueOf(entry.get("path")));
            n.put("files", ((Number) entry.get("files")).longValue());
            n.put("bytes", ((Number) entry.get("bytes")).longValue());
        }
        var left = root.putArray("leftInPlace");
        leftovers.forEach(left::add);
        Path manifest = dir.resolve(ARCHIVE_MANIFEST);
        Files.writeString(manifest, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        Path leftoversFile = dir.resolve("resource-leftovers.json");
        var arr = mapper.createArrayNode();
        leftovers.forEach(arr::add);
        Files.writeString(leftoversFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(arr),
                StandardCharsets.UTF_8);
    }

    /**
     * 不属于现存世界的旧资源目录（只列名，不动）。
     *
     * <p>存活集合取自<b>本次要迁移的世界</b>而不是目录库索引：归档发生在写索引之前，
     * 那时目录库还是空的——用它比对会把每个真实世界的资源目录都误判成残骸。</p>
     */
    private List<String> leftoverResourceDirs(List<LegacyWorld> worlds) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> liveIds = new java.util.HashSet<>();
        for (LegacyWorld w : worlds) {
            liveIds.add(String.valueOf(w.id()));
        }
        collectLeftovers(paths.legacyVectorStoreRoot(), liveIds, "vector-store/", out);
        collectLeftovers(paths.legacyWorldmapRoot(), liveIds, "worldmap/", out);
        return out;
    }

    private void collectLeftovers(Path root, java.util.Set<String> liveIds, String prefix, List<String> out) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !liveIds.contains(name))
                    .sorted()
                    .forEach(name -> out.add(prefix + name));
        } catch (IOException e) {
            log.debug("[world-store] 扫描旧资源目录失败：{}", root, e);
        }
    }

    // ------------------------------------------------------------------ 目录库

    /**
     * 目录库数据：12 张权威表全量 + 3 张观测表的 NULL 行。
     *
     * <p>{@code actor_project} 与 {@code actor_schema_meta} 单独处理：前者要补 12 个索引列，
     * 后者按设计"两库各一份、各自维护"不搬（旧库里的 {@code schema_version=3} 是旧 Flyway
     * 基线的编号，搬进目录库只会让 {@code /api/system/schema} 报一个不存在的版本）。</p>
     */
    private void copyRegistryTables(Connection legacyConn, List<String> warnings) {
        List<String> tables = new ArrayList<>(MirrorTables.ALL);
        tables.remove(MirrorTables.OWN_ROW_TABLE);
        tables.addAll(WorldSplitRules.observationTables());
        try (Connection regConn = registry.getConnection()) {
            boolean oldAutoCommit = regConn.getAutoCommit();
            regConn.setAutoCommit(false);
            try {
                for (String table : tables) {
                    List<String> columns = JdbcRowCopier.commonColumns(regConn, legacyConn, table);
                    if (columns.isEmpty()) {
                        throw new IllegalStateException("目录库缺少表或列：" + table);
                    }
                    String select = "select " + String.join(", ", columns) + " from " + table;
                    if (WorldSplitRules.observationTables().contains(table)) {
                        // 观测表：project_id 非空的行属于世界，NULL 行留在目录库（U4）
                        select += " where project_id is null";
                    }
                    JdbcRowCopier.deleteAll(regConn, table);
                    JdbcRowCopier.CopyResult copied = JdbcRowCopier.copy(legacyConn, select, regConn, table, columns);
                    reportCoercion(copied, table, warnings);
                    log.info("[world-store] 目录库表已复制 {} 行={}", table, copied.rows());
                }
                regConn.commit();
            } catch (SQLException | RuntimeException e) {
                regConn.rollback();
                throw e;
            } finally {
                regConn.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("复制目录库数据失败", e);
        }
    }

    /** 插入 {@code actor_project} 行（保留 id；新列由 {@link WorldIndexDao} 随后回写） */
    private void insertProjectRows(Connection legacyConn) {
        List<String> columns = List.of("id", "code", "cover_url", "created_at", "deleted", "name",
                "project_uid", "status", "summary", "updated_at", "user_id");
        long rows;
        try (Connection regConn = registry.getConnection()) {
            boolean oldAutoCommit = regConn.getAutoCommit();
            regConn.setAutoCommit(false);
            try {
                JdbcRowCopier.CopyResult copied = JdbcRowCopier.copy(legacyConn,
                        "select " + String.join(", ", columns) + " from actor_project",
                        regConn, MirrorTables.OWN_ROW_TABLE, columns);
                rows = copied.rows();
                reportCoercion(copied, MirrorTables.OWN_ROW_TABLE, null);
                regConn.commit();
            } catch (SQLException | RuntimeException e) {
                regConn.rollback();
                throw e;
            } finally {
                regConn.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入目录库 actor_project 索引行失败", e);
        }
        log.info("[world-store] 目录库已写入 {} 行世界索引（actor_project）", rows);
        // 目录库的自增主键也要跟着走，否则后续「新建世界」可能与保留的历史 id 撞车
        resetIdentity(registry, MirrorTables.OWN_ROW_TABLE);
    }

    /** 旧库某世界缺 uid 时补生成（写目录库，不写旧库） */
    private void assignUid(long id, String uid) {
        try (Connection c = registry.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "update actor_project set project_uid = ?, data_dir_name = ? where id = ?")) {
            ps.setString(1, uid);
            ps.setString(2, uid);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("回写世界 uid 失败：id=" + id, e);
        }
    }

    // ------------------------------------------------------------------ 单世界迁移

    /** 真实迁移一个世界（建库 → 搬行 → 资源 → 镜像 → 清单 → 校验） */
    private WorldSplitReport.WorldPart migrateWorld(Connection legacyConn, LegacyWorld w, String uid,
                                                    String sourceSha256, String generatedAt) {
        long t0 = System.currentTimeMillis();
        Path worldDir = paths.worldDir(uid);
        if (Files.exists(paths.worldDbFile(uid))) {
            throw new IllegalStateException("世界库已存在，拒绝覆盖（请先移走再重试）：" + paths.worldDbFile(uid));
        }
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> resources = new ArrayList<>();

        // 1. 建库 + 世界 Flyway（113 张表）；失败即抛
        DataSource worldDs = dataSources.worldDataSource(uid);

        // 2. 搬行（100 条规则）；回填桶复制后把 project_id 补成本世界 id
        List<WorldSplitReport.TablePart> parts = new ArrayList<>();
        Map<String, Long> counts = new TreeMap<>();
        try (Connection wc = worldDs.getConnection()) {
            boolean oldAutoCommit = wc.getAutoCommit();
            wc.setAutoCommit(false);
            try {
                for (WorldSplitRules.RowRule rule : WorldSplitRules.allRowRules()) {
                    List<String> columns = JdbcRowCopier.commonColumns(wc, legacyConn, rule.table());
                    if (columns.isEmpty()) {
                        throw new IllegalStateException("世界库缺少表：" + rule.table());
                    }
                    String select = WorldSplitRules.withColumns(rule, columns);
                    JdbcRowCopier.deleteAll(wc, rule.table());
                    JdbcRowCopier.CopyResult copied = JdbcRowCopier.copy(legacyConn, select, w.id(),
                            wc, rule.table(), columns);
                    reportCoercion(copied, rule.table(), warnings);
                    long target = copied.rows();
                    if (rule.needsStamp()) {
                        stampOwnership(wc, rule.table(), w.id());
                    }
                    long source = countByRule(legacyConn, rule, w.id());
                    String note = target == source ? null : "复制行数与源不一致";
                    parts.add(new WorldSplitReport.TablePart(rule.table(), rule.bucket(), source, target,
                            target == source, note));
                    if (target > 0) {
                        counts.put(rule.table(), target);
                    }
                    if (target != source) {
                        errors.add(rule.table() + "：源行数 " + source + "，目标行数 " + target);
                    }
                }
                wc.commit();
            } catch (SQLException | RuntimeException e) {
                wc.rollback();
                throw e;
            } finally {
                wc.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("复制世界数据失败：uid=" + uid, e);
        }

        // 3. 归属污染检查：世界库里不允许出现别的世界的行
        assertNoForeignRows(worldDs, w.id());

        // 4. 自增主键重置（否则后续插入会撞已有 id）
        resetWorldIdentities(worldDs);

        // 5. 资源（向量库 / 地图图块 / worldgen 产物）
        resources.addAll(copyWorldResources(legacyConn, uid, w.id(), warnings));

        // 6. 12 张全局镜像（复用 open 时的同一服务，禁止第二套）
        mirrors.sync(registry, worldDs, w.id());

        // 7. 时钟 + 校验和
        WorldSplitReport.ClockPart clock = readClock(legacyConn, worldDs, w.id());
        if (!clock.pass()) {
            errors.add("世界时钟不一致（last_game_second/paused/rate 必须逐位相同）");
        }
        Map<String, String> checksums = new LinkedHashMap<>();
        boolean checksumPass = true;
        for (String table : CHECKSUM_TABLES) {
            // 列序必须同一份：两库各自按自己的 ordinal_position 取列，一旦历史库与生成的基线
            // 列序不同，同一份数据会算出不同哈希，把"校验和不一致"变成误报。
            List<String> cols = checksumColumns(legacyConn, worldDs, table);
            String from = checksum(legacyConn, table, whereOf(table), w.id(),
                    checksumSourceExprs(worldDs, table, cols));
            String to = checksum(worldDs, table, "", w.id(), cols);
            checksums.put(table, "sha256:" + to);
            if (!from.equals(to)) {
                checksumPass = false;
                errors.add("关键表校验和不一致：" + table + "（源 " + from + "，世界库 " + to + "）");
            }
        }

        // 8. 清单
        String worldName = w.name() == null || w.name().isBlank() ? uid : w.name();
        WorldManifest manifest = new WorldManifest(WorldManifest.CURRENT_FORMAT_VERSION,
                WorldManifest.SCHEMA_VERSION, uid, worldName, generatedAt, null, null,
                new WorldManifest.Source("legacy-split", paths.legacyDbFile().getFileName().toString(),
                        sourceSha256, generatedAt),
                counts, checksums, List.copyOf(resources), WorldManifest.DEFAULT_DB_FILE);
        manifests.write(worldDir, manifest);
        String manifestPath = worldDir.relativize(paths.manifestFile(uid)).toString();

        // 9. 目录库索引摘要（关闭态 + 游戏内时间/角色数/体积/清单快照）
        Long characterCount = counts.getOrDefault("actor_profile", 0L);
        try {
            indexDao.markMigrated(w.id(), uid,
                    new WorldIndexDao.ClosedSummary(LocalDateTime.now(), clock.targetGameSecond(),
                            characterCount, paths.worldSizeBytes(uid), rawManifest(worldDir),
                            WorldManifest.SCHEMA_VERSION),
                    LocalDateTime.now());
        } catch (RuntimeException e) {
            // 索引写失败不影响世界库正确性：下次启动收编会补
            warnings.add("回写目录库索引失败（下次启动收编会补）：" + e.getMessage());
            log.warn("[world-store] 回写目录库索引失败 id={}", w.id(), e);
        }

        // 9b. 跨世界用量摘要（actor_usage_log 是世界表，目录库没有；迁移完就地算一次，
        //     否则首页用量 Tab 在世界从未关闭过之前一直是 0）
        if (summaryReader != null) {
            try {
                WorldSummaryReader.Usage usage = summaryReader.readUsage(worldDs, w.id());
                indexDao.updateUsageSummary(w.id(), usage.tokenIn(), usage.tokenOut(), usage.aiCalls());
            } catch (RuntimeException e) {
                warnings.add("回写用量摘要失败（该世界下次关闭时会补）：" + e.getMessage());
                log.warn("[world-store] 回写用量摘要失败 id={}", w.id(), e);
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[world-store] 世界已迁移 id={} name={} uid={} 行数={} 资源={} 耗时={}ms",
                w.id(), worldName, uid, parts.stream().mapToLong(WorldSplitReport.TablePart::targetRows).sum(),
                resources, elapsed);
        return new WorldSplitReport.WorldPart(w.id(), uid, worldName, List.copyOf(parts), clock,
                checksums, checksumPass, List.copyOf(resources), manifestPath, elapsed,
                List.copyOf(warnings), List.copyOf(errors));
    }

    /** dry-run：只按规则统计行数（不建库、不写盘、不打开世界库） */
    private WorldSplitReport.WorldPart dryRunWorld(Connection legacyConn, LegacyWorld w, String uid) {
        long t0 = System.currentTimeMillis();
        List<WorldSplitReport.TablePart> parts = new ArrayList<>();
        long rows = 0;
        for (WorldSplitRules.RowRule rule : WorldSplitRules.allRowRules()) {
            long source = countByRule(legacyConn, rule, w.id());
            rows += source;
            parts.add(new WorldSplitReport.TablePart(rule.table(), rule.bucket(), source, 0,
                    false, "dry-run 未写库"));
        }
        WorldSplitReport.ClockPart clock = readClock(legacyConn, null, w.id());
        log.info("[world-store] dry-run 世界 id={} name={} 预计行数={}", w.id(), w.name(), rows);
        return new WorldSplitReport.WorldPart(w.id(), uid == null ? "（缺 uid）" : uid,
                w.name() == null ? "（无名）" : w.name(), List.copyOf(parts), clock, Map.of(), true,
                List.of(), "", System.currentTimeMillis() - t0, List.of(), List.of("dry-run：未写盘"));
    }

    /**
     * 失败世界的占位结果（报告里要能看见它，而不是静默少一个世界）。
     *
     * <p>必须带根因：包装消息（"复制世界数据失败"）只说明阶段，真正能修的永远是
     * 底层那句 H2 报错（例如缺列、约束冲突），漏掉它报告就等于没写。</p>
     */
    private static WorldSplitReport.WorldPart failedWorld(LegacyWorld w, String uid, RuntimeException e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        String root = rootMessage(e);
        if (root != null && !root.isBlank() && !message.contains(root)) {
            message = message + "（根因：" + root + "）";
        }
        return new WorldSplitReport.WorldPart(w.id(), uid, w.name() == null ? "（无名）" : w.name(),
                List.of(), null, Map.of(), false, List.of(), "", 0L, List.of(), List.of(message));
    }

    /** 最内层 cause 的消息（H2 的真实报错在这里，外层消息只是阶段标签） */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage();
    }

    // ------------------------------------------------------------------ 校验辅助

    /**
     * 把"源库 NULL / 目标库 NOT NULL"的补值记账并告警。
     *
     * @param copied   复制结果
     * @param table    表名
     * @param warnings 告警输出（null = 只记日志）
     */
    private void reportCoercion(JdbcRowCopier.CopyResult copied, String table, List<String> warnings) {
        if (copied.coercedNulls().isEmpty()) {
            return;
        }
        copied.coercedNulls().forEach((column, times) -> {
            String note = "列 " + table + "." + column + " 有 " + times
                    + " 行在旧库里是 NULL，目标库声明 NOT NULL：已按实体默认值补 0（不静默改写，已记账）";
            log.warn("[world-store] {}", note);
            if (warnings != null) {
                warnings.add(note);
            }
        });
    }

    /** 回填桶：把复制进来的空 project_id 补成本世界 id（世界库内写实，不改旧库） */
    private void stampOwnership(Connection worldConn, String table, long projectId) throws SQLException {
        try (Statement st = worldConn.createStatement()) {
            int n = st.executeUpdate("update " + table + " set " + WorldSplitRules.PROJECT_COLUMN
                    + " = " + projectId + " where " + WorldSplitRules.PROJECT_COLUMN + " is null");
            if (n > 0) {
                log.info("[world-store] 回填归属表 {} 的 {} 行 project_id={}", table, n, projectId);
            }
        }
    }

    /** 某条规则在旧库里的行数 */
    private static long countByRule(Connection conn, WorldSplitRules.RowRule rule, long projectId) {
        try (PreparedStatement ps = conn.prepareStatement(WorldSplitRules.countSql(rule))) {
            ps.setLong(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计源行数失败：" + rule.table() + "（" + e.getMessage() + "）", e);
        }
    }

    /** 世界库里不允许出现别的世界的行（串世界是分档最严重的错误，必须当场失败） */
    private void assertNoForeignRows(DataSource worldDs, long projectId) {
        try (Connection c = worldDs.getConnection()) {
            for (WorldSplitRules.RowRule rule : WorldSplitRules.allRowRules()) {
                if (WorldSplitRules.BUCKET_CHILD.equals(rule.bucket())) {
                    continue; // 子表没有 project_id
                }
                String sql = "select count(*) from " + rule.table() + " where "
                        + WorldSplitRules.PROJECT_COLUMN + " is not null and "
                        + WorldSplitRules.PROJECT_COLUMN + " <> " + projectId;
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    long foreign = rs.next() ? rs.getLong(1) : 0L;
                    if (foreign > 0) {
                        throw new IllegalStateException("表 " + rule.table() + " 含 " + foreign
                                + " 行属于其它世界（迁移串世界，必须中止）");
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("归属污染检查失败", e);
        }
    }

    /** 读出时钟对照（target 为 null 时只读源，用于 dry-run） */
    private WorldSplitReport.ClockPart readClock(Connection legacyConn, DataSource worldDs, long projectId) {
        ClockValues src = readClockValues(legacyConn, projectId);
        ClockValues tgt = worldDs == null ? null : readClockValues(worldDs, projectId);
        return new WorldSplitReport.ClockPart(
                src.gameSecond(), tgt == null ? src.gameSecond() : tgt.gameSecond(),
                src.paused(), tgt == null ? src.paused() : tgt.paused(),
                src.rate(), tgt == null ? src.rate() : tgt.rate());
    }

    private record ClockValues(Long gameSecond, Integer paused, Integer rate) {
    }

    private static ClockValues readClockValues(Connection conn, long projectId) {
        String sql = "select last_game_second, paused, rate from actor_world_clock where project_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new ClockValues(null, null, null);
                }
                return new ClockValues(nullableLong(rs, 1), nullableInt(rs, 2), nullableInt(rs, 3));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取世界时钟失败：projectId=" + projectId, e);
        }
    }

    private static ClockValues readClockValues(DataSource ds, long projectId) {
        try (Connection c = ds.getConnection()) {
            return readClockValues(c, projectId);
        } catch (SQLException e) {
            throw new IllegalStateException("读取世界库时钟失败：projectId=" + projectId, e);
        }
    }

    /**
     * 校验和用的列集合：以旧库列序为准，剔除 {@code project_id}，并与世界库取交集。
     *
     * <p>剔除 {@code project_id} 是刻意的：回填桶在源库里是 NULL、在世界库里被写成本世界 id，
     * 这一列本来就应当不同（归属正确性由"逐表行数 + 归属污染检查"保证，不靠哈希）。</p>
     */
    private static List<String> checksumColumns(Connection legacyConn, DataSource worldDs, String table) {
        List<String> legacyCols;
        List<String> worldCols;
        try (Connection wc = worldDs.getConnection()) {
            legacyCols = JdbcRowCopier.columnsOf(legacyConn, table);
            worldCols = JdbcRowCopier.columnsOf(wc, table);
        } catch (SQLException e) {
            throw new IllegalStateException("读取校验和列信息失败：" + table, e);
        }
        List<String> out = new ArrayList<>();
        for (String c : legacyCols) {
            if (!WorldSplitRules.PROJECT_COLUMN.equals(c) && worldCols.contains(c)) {
                out.add(c);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("校验和表没有可比对的列：" + table);
        }
        return out;
    }

    /** 内容校验和（DataSource 版：从池里借一个连接） */
    private static String checksum(DataSource ds, String table, String where, long projectId,
                                   List<String> cols) {
        try (Connection c = ds.getConnection()) {
            return checksum(c, table, where, projectId, cols);
        } catch (SQLException e) {
            throw new IllegalStateException("计算校验和失败（取连接）：" + table, e);
        }
    }

    /**
     * 源侧的校验和列表达式：目标库"NOT NULL 的数值列"在源侧先 {@code coalesce(..., 0)}。
     *
     * <p>为什么必须这么做：历史库比基线宽松（本机实测 24 列可空性漂移），复制时按策略把
     * 数值 NULL 补成 0 并记账。若源侧仍按原始 NULL 算哈希，这份"已告知的补值"会被报成
     * "校验和不一致"，从而把每一次真实迁移都判 FAIL——校验和必须与复制策略同源，
     * 否则它守的不是数据，而是自己的口径。</p>
     */
    private static List<String> checksumSourceExprs(DataSource worldDs, String table, List<String> cols) {
        try (Connection wc = worldDs.getConnection()) {
            java.util.Set<String> padded = JdbcRowCopier.numericNotNullWithoutDefault(wc, table);
            List<String> out = new ArrayList<>(cols.size());
            for (String col : cols) {
                out.add(padded.contains(col) ? "coalesce(" + col + ", 0)" : col);
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("读取校验和列类型失败：" + table, e);
        }
    }

    /**
     * 内容校验和：按 id 排序后逐行逐列（列集合由调用方给定，两库必须同一份）。
     *
     * @param exprs 选择列表表达式（普通列名，或源侧的 coalesce 包装）
     */
    private static String checksum(Connection conn, String table, String where, long projectId,
                                   List<String> exprs) {
        String sql = "select " + String.join(", ", exprs) + " from " + table
                + (where == null || where.isBlank() ? "" : " " + where)
                + " order by id";
        MessageDigest md = sha256Digest();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (where != null && where.contains("?")) {
                ps.setLong(1, projectId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    for (int i = 1; i <= exprs.size(); i++) {
                        md.update(canonicalBytes(rs, meta, i));
                        md.update((byte) 0x1F);
                    }
                    md.update((byte) 0x1E);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("计算校验和失败：" + table, e);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * 一列值的规范化字节：文本/数值/时间统一走 {@code getString}（同一驱动同一 JVM 下稳定），
     * 二进制走 {@code getBytes}；NULL 用单个 0 字节区分（避免与空串混淆）。
     */
    private static byte[] canonicalBytes(ResultSet rs, ResultSetMetaData meta, int index) throws SQLException {
        int type = meta.getColumnType(index);
        if (type == java.sql.Types.BLOB || type == java.sql.Types.LONGVARBINARY
                || type == java.sql.Types.VARBINARY || type == java.sql.Types.BINARY) {
            byte[] bytes = rs.getBytes(index);
            return bytes == null ? new byte[]{(byte) 0} : bytes;
        }
        String text = rs.getString(index);
        return text == null ? new byte[]{(byte) 0} : text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 旧库表名升级：把历史上以单一模型命名的表改成通用名（{@code actor_nanojev_ab_*} →
     * {@code actor_decision_ab_*}）。
     *
     * <p><b>为什么必须在搬表之前做</b>：搬表按 {@code db/worldsplit-tables.json} 的新表名
     * 逐表取公共列，旧库仍是旧名时取不到列 → 抛「世界库缺少表」→ 启动失败。
     * 旧库本身随后即被归档，改表名不会影响任何仍在运行的实例。</p>
     *
     * <p>语句带 {@code IF EXISTS}：新装（V203 已重命名）或重复执行时是空操作。</p>
     *
     * @param legacyConn 旧库连接
     *
     * @param warnings 告警收集
     */
    private void upgradeLegacyTableNames(Connection legacyConn, List<String> warnings) {
        String[][] renames = {
                {"actor_nanojev_ab_run", "actor_decision_ab_run"},
                {"actor_nanojev_ab_sample", "actor_decision_ab_sample"}
        };
        for (String[] rename : renames) {
            String sql = "alter table if exists " + rename[0] + " rename to " + rename[1];
            try (Statement st = legacyConn.createStatement()) {
                st.executeUpdate(sql);
                log.info("[world-store] 旧库表名升级：{} → {}", rename[0], rename[1]);
            } catch (SQLException e) {
                // 旧库原本就没有这张表（更早期的版本）也算正常：只记警告，不阻断迁移
                warnings.add("旧库表名升级跳过：" + rename[0] + "（" + e.getMessage() + "）");
            }
        }
        // 列名同样要升级：表名改完后搬表用的是公共列交集，列名不跟着改会让
        // latency/status/response 三列在迁移后静默丢失（只剩行、没有内容）
        String[][] columnRenames = {
                {"actor_decision_ab_sample", "nanojev_latency_ms", "decision_latency_ms"},
                {"actor_decision_ab_sample", "nanojev_status", "decision_status"},
                {"actor_decision_ab_sample", "nanojev_response_json", "decision_response_json"}
        };
        for (String[] rename : columnRenames) {
            String sql = "alter table if exists " + rename[0]
                    + " alter column if exists " + rename[1] + " rename to " + rename[2];
            try (Statement st = legacyConn.createStatement()) {
                st.executeUpdate(sql);
                log.info("[world-store] 旧库列名升级：{}.{} → {}", rename[0], rename[1], rename[2]);
            } catch (SQLException e) {
                warnings.add("旧库列名升级跳过：" + rename[0] + "." + rename[1] + "（" + e.getMessage() + "）");
            }
        }
    }

    /** 校验和表在旧库里的归属条件（与 {@link WorldSplitRules} 保持同源） */
    private static String whereOf(String table) {
        for (WorldSplitRules.RowRule rule : WorldSplitRules.allRowRules()) {
            if (rule.table().equals(table)) {
                String sql = WorldSplitRules.countSql(rule);
                return sql.substring(sql.indexOf(" where ") + 1);
            }
        }
        throw new IllegalStateException("校验和表没有切分规则（会既漏行又难定位）：" + table);
    }

    /** 重置世界库全部 identity 主键（{@code ALTER COLUMN id RESTART WITH max+1}） */
    private void resetWorldIdentities(DataSource worldDs) {
        try (Connection c = worldDs.getConnection()) {
            for (String table : WorldSplitRules.worldTables()) {
                resetIdentity(c, table);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("重置世界库自增主键失败", e);
        }
    }

    private void resetIdentity(DataSource ds, String table) {
        try (Connection c = ds.getConnection()) {
            resetIdentity(c, table);
        } catch (SQLException e) {
            throw new IllegalStateException("重置自增主键失败：" + table, e);
        }
    }

    /** 单表重置（非 identity 表自动跳过） */
    private void resetIdentity(Connection c, String table) {
        String identitySql = "select count(*) from information_schema.columns "
                + "where upper(table_schema) = 'PUBLIC' and lower(table_name) = ? "
                + "and lower(column_name) = 'id' and is_identity = 'YES'";
        try (PreparedStatement ps = c.prepareStatement(identitySql)) {
            ps.setString(1, table.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getLong(1) == 0) {
                    return;
                }
            }
            long max = 0;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("select max(id) from " + table)) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    max = rs.wasNull() ? 0 : v;
                }
            }
            if (max <= 0) {
                return;
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate("alter table " + table + " alter column id restart with " + (max + 1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("重置自增主键失败：" + table, e);
        }
    }

    // ------------------------------------------------------------------ 资源

    /** 把世界的资源复制进 {@code worlds/<uid>/}（源目录原地保留，读取方在 M4 才切过来） */
    private List<String> copyWorldResources(Connection legacyConn, String uid, long projectId,
                                            List<String> warnings) {
        List<String> done = new ArrayList<>();
        Path vectorSrc = paths.legacyVectorStoreRoot().resolve(String.valueOf(projectId));
        if (Files.isDirectory(vectorSrc)) {
            copyResourceIfPresent(vectorSrc, paths.worldVectorStoreDir(uid), "vector-store", warnings);
            done.add("vector-store");
        }
        Path mapSrc = paths.legacyWorldmapRoot().resolve(String.valueOf(projectId));
        if (Files.isDirectory(mapSrc)) {
            copyResourceIfPresent(mapSrc, paths.worldWorldmapDir(uid), "worldmap", warnings);
            done.add("worldmap");
        }
        try {
            for (Long taskId : worldgenTaskIds(legacyConn, projectId)) {
                Path src = paths.legacyWorldgenRoot().resolve(String.valueOf(taskId));
                if (!Files.isDirectory(src)) {
                    continue;
                }
                copyResourceIfPresent(src, paths.worldWorldgenDir(uid).resolve(String.valueOf(taskId)),
                        "worldgen/" + taskId, warnings);
                if (!done.contains("worldgen")) {
                    done.add("worldgen");
                }
            }
        } catch (SQLException e) {
            warnings.add("读取 worldgen 任务清单失败（产物未复制）：" + e.getMessage());
        }
        return done;
    }

    private void copyResourceIfPresent(Path src, Path dst, String label, List<String> warnings) {
        if (!Files.isDirectory(src)) {
            return;
        }
        try {
            long files = copyDir(src, dst);
            if (files == 0) {
                warnings.add("资源目录为空（已建空目录）：" + label);
            }
            log.info("[world-store] 资源已复制 {} → {} 文件={}", src, dst, files);
        } catch (IOException e) {
            warnings.add("资源复制失败 " + label + "：" + e.getMessage());
            log.warn("[world-store] 资源复制失败 {} → {}", src, dst, e);
        }
    }

    /** 某世界在旧库里的 worldgen 任务 id */
    private static List<Long> worldgenTaskIds(Connection conn, long projectId) throws SQLException {
        List<Long> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "select id from actor_worldgen_task where project_id = ? order by id")) {
            ps.setLong(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
        }
        return out;
    }

    /** 递归复制目录（返回复制文件数） */
    private static long copyDir(Path src, Path dst) throws IOException {
        long[] count = {0L};
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file).toString()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                count[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    private static long dirBytes(Path dir) throws IOException {
        long[] total = {0L};
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                try {
                    total[0] += Files.size(f);
                } catch (IOException ignored) {
                    // 统计用途，读不到就跳过
                }
            });
        }
        return total[0];
    }

    private static long sumBytes(Path root) {
        try {
            return Files.isDirectory(root) ? dirBytes(root) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024);
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    // ------------------------------------------------------------------ IO / 状态

    /** 打开旧库（只读用途；本类不对它发任何 DML） */
    private Connection openLegacy(Path legacy) {
        try {
            return DriverManager.getConnection(paths.legacyDbUrl(), username, password);
        } catch (SQLException e) {
            throw new IllegalStateException("旧单库打不开（可能仍被上一个进程/DataGrip 占用）：" + legacy
                    + "。请先停掉占用它的进程再重启应用（AGENTS §8.5.4：H2 文件独占锁）。", e);
        }
    }

    /** 旧库里的世界行（按 id 升序，顺序稳定） */
    private static List<LegacyWorld> readLegacyWorlds(Connection conn) throws SQLException {
        List<LegacyWorld> out = new ArrayList<>();
        String sql = "select id, project_uid, name, deleted from actor_project order by id";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String uid = rs.getString("project_uid");
                String name = rs.getString("name");
                int deleted = rs.getInt("deleted");
                out.add(new LegacyWorld(id, uid, name, rs.wasNull() ? null : deleted));
            }
        }
        return out;
    }

    /** 目录库里现有的世界数（0 = 还没迁移过） */
    private long registryProjectCount() {
        try (Connection c = registry.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from actor_project")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("读取目录库 actor_project 失败", e);
        }
    }

    /**
     * 拒绝"把旧库当目录库"的错配。
     *
     * <p>如果目录库里能查到 {@code actor_profile}，说明 {@code spring.datasource.url} 还指着旧单体库，
     * 此时继续迁移会一边读旧库一边往旧库里写，把源数据搅坏。必须立刻停。</p>
     */
    private void requireRegistryIsNotLegacy() {
        try (Connection c = registry.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "select count(*) from information_schema.tables "
                             + "where upper(table_schema) = 'PUBLIC' and lower(table_name) = 'actor_profile'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next() && rs.getLong(1) > 0) {
                throw new IllegalStateException("目录库文件里出现了世界表（actor_profile）："
                        + paths.registryDbFile() + " 其实还是旧单体库。请检查 spring.datasource.url / "
                        + "HOLOZYN_ACTOR_DB_URL，确认为 'world-store.registry-file' 指向的 holzyn-registry。");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检查目录库身份失败", e);
        }
    }

    /** 状态文件（data-dir 下；见类注释的三种状态） */
    private Path stateFile() {
        return paths.dataDir().resolve(STATE_FILE);
    }

    private Optional<String> readState() {
        Path f = stateFile();
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (IOException e) {
            // 读不到状态不要当成"没迁移过"：那会导致重复拆库
            throw new IllegalStateException("读取迁移状态文件失败：" + f, e);
        }
    }

    private void writeState(String value) {
        Path f = stateFile();
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, value + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("写入迁移状态文件失败：" + f, e);
        }
    }

    /** 报告目录：开发态（存在 {@code target/}）写 target，否则写数据目录 */
    /**
     * 报告落地位置：<b>始终是数据目录</b>，与归档、状态文件同处。
     *
     * <p>为什么不是"target/ 存在就写 target"：报告是 AC3/AC8 的唯一凭证，而 {@code mvn clean}
     * 会把 target 整个删掉——实测本轮真实迁移的报告就落在 {@code backend/target} 里，
     * 一次 clean 就没了。数据目录是唯一"事后还能翻到"的位置；真实 jar 运行也没有 target。</p>
     */
    private Path reportDir() {
        return paths.dataDir();
    }

    /** manifest 原文快照（列表页免读磁盘） */
    private String rawManifest(Path worldDir) {
        try {
            Path f = worldDir.resolve(WorldManifestService.FILE_NAME);
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 孤儿审计

    /**
     * 孤儿行审计：子表/回填表里"任何世界都选不出来"的行必须留证（不静默丢弃）。
     *
     * @param legacyConn 旧库连接
     * @param parts      已完成的世界
     * @param warnings   告警输出
     */
    private void auditOrphans(Connection legacyConn, List<WorldSplitReport.WorldPart> parts, List<String> warnings) {
        if (parts.stream().anyMatch(p -> !p.pass())) {
            // 有世界没迁成时，各表 targetRows 是 0 或缺失，算出来的"孤儿"会把整库都算进去——
            // 那种数字比没有更糟（会让人以为数据丢了）。宁可明说审计跳过。
            warnings.add("有世界迁移失败，孤儿行审计跳过（此时逐表行数不可信）");
            return;
        }
        List<WorldSplitRules.RowRule> auditRules = new ArrayList<>(WorldSplitRules.childRules());
        auditRules.addAll(WorldSplitRules.backfillRules());
        for (WorldSplitRules.RowRule rule : auditRules) {
            long total;
            try (Statement st = legacyConn.createStatement();
                 ResultSet rs = st.executeQuery("select count(*) from " + rule.table())) {
                total = rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                warnings.add("孤儿审计跳过（表不可读）：" + rule.table() + " — " + e.getMessage());
                continue;
            }
            long accounted = 0;
            for (WorldSplitReport.WorldPart p : parts) {
                for (WorldSplitReport.TablePart t : p.tables()) {
                    if (t.table().equals(rule.table())) {
                        accounted += t.targetRows();
                    }
                }
            }
            long orphans = total - accounted;
            if (orphans > 0) {
                warnings.add("孤儿行：" + rule.table() + " 共 " + total + " 行，其中 " + orphans
                        + " 行不属于任何世界（父行缺失或 project_id 为空），已留在归档旧库里，未静默丢弃");
            }
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件大小失败：" + file, e);
        }
    }

    /** 整文件 sha256（分块读，避免 46MB 一次进内存） */
    private static String sha256(Path file) {
        MessageDigest md = sha256Digest();
        byte[] buf = new byte[1 << 20];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("计算 sha256 失败：" + file, e);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** 复制并同时算 sha256，写完 fsync（归档必须真落盘，不能只进页缓存） */
    private static String copyAndHash(Path src, Path dst) {
        MessageDigest md = sha256Digest();
        byte[] buf = new byte[1 << 20];
        try {
            Files.createDirectories(dst.getParent());
            try (InputStream in = Files.newInputStream(src);
                 OutputStream out = Files.newOutputStream(dst, StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            try (var ch = java.nio.channels.FileChannel.open(dst, java.nio.file.StandardOpenOption.WRITE)) {
                ch.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("复制归档失败：" + src + " → " + dst, e);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static Long nullableLong(ResultSet rs, int index) throws SQLException {
        long v = rs.getLong(index);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, int index) throws SQLException {
        int v = rs.getInt(index);
        return rs.wasNull() ? null : v;
    }
}
