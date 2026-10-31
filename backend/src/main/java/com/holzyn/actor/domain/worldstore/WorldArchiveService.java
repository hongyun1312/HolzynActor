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

import com.holzyn.actor.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 存档导出 / 导入（{@code .holzyn} v3 = 世界目录 zip）。
 *
 * <p><b>为什么 v3 是"世界目录打包"而不是 v2 的"逐表 JSON"</b>：分档存储之后，一个世界就是
 * {@code worlds/<uid>/} 这一个目录（世界库 + 四类资源 + 清单）。逐表 JSON 导出/导入要枚举 113 张表、
 * 维护外键与 identity 顺序、还要把资源单独搬——任何一处漏字段就是"导入回来少一半数据"。
 * zip 整个目录则是"原样搬走再原样放回"，导入后打开的世界与导出前逐位一致（AC5 要的就是这个）。</p>
 *
 * <p><b>导出只读、且要脱敏</b>：设计写的是"导出后不改动世界目录"。但世界库里有 12 张全局镜像表，
 * 其中 {@code actor_model_provider.api_key_cipher} 是 AI 供应商密钥的密文——打进包里就等于把密钥
 * 随存档发给别人。所以流程是：<b>复制</b>一份库到临时目录 → 清空该列 → {@code SHUTDOWN COMPACT}
 * → 打包副本 → 删临时目录。原目录一个字节都不动（AC5「导出包内无 api_key_cipher」）。</p>
 *
 * <p><b>导入 = 新世界</b>：包里的 {@code project_id} 是"导出那台机器上的目录库主键"，与本机主键无关；
 * 直接放进来会变成"指向别人 id 的数据"。所以导入必须：插入本机目录库行拿新 id → 把世界库里所有
 * 带 {@code project_id} 的表改写成新 id → 清掉镜像 {@code actor_project} 行（下次挂载由
 * {@link WorldMirrorSyncService} 用目录库数据整表重建）→ 清单 {@code source=import}。</p>
 *
 * <p>uid 冲突（同一个存档导入两次）：源 uid 已存在时生成新 uid，世界目录名与清单一起改，
 * 两行索引互不影响（同名世界是合法的）。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Service
public class WorldArchiveService {

    /** 存档包后缀 */
    public static final String PACKAGE_SUFFIX = ".holzyn";

    /** 包内清单条目名（与 {@link WorldManifestService#FILE_NAME} 同源） */
    public static final String MANIFEST_ENTRY = WorldManifestService.FILE_NAME;

    /** 包内世界库条目名（与清单 {@code dbFile} 默认值同源） */
    public static final String DB_ENTRY = WorldManifest.DEFAULT_DB_FILE;

    /** 存放密钥密文的表/列（导出前必须清空；AC5 的判据） */
    private static final String SECRET_TABLE = "actor_model_provider";

    private static final String SECRET_COLUMN = "api_key_cipher";

    /** 导入临时目录名（位于数据目录下，便于人工清理与磁盘配额排查） */
    private static final String IMPORT_TMP_DIR = "import-tmp";

    /** 导出临时目录名 */
    private static final String EXPORT_TMP_DIR = "export-tmp";

    /** 文件名里的日期戳（本地日期，只用于人眼区分） */
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 文件名里允许的字符之外一律替换成下划线（Windows 禁用字符 + 路径分隔符） */
    private static final String ILLEGAL_NAME_CHARS = "[\\\\/:*?\"<>|\\r\\n\\t]";

    private final WorldPaths paths;
    private final WorldRegistryService registry;
    private final WorldIndexDao indexDao;
    private final WorldManifestService manifests;

    /**
     * 数据库账号（打开世界库副本时用）。
     *
     * <p>为什么必须跟着 {@code spring.datasource.*} 走：H2 把"库的属主"记在文件里，
     * 用错的用户名打开会直接抛 {@code Wrong user name or password [28000-240]}——
     * 实测导出第一步就撞上了（默认用户名是空的，而本应用连的是 {@code sa}）。
     * 与 {@code LegacyWorldSplitMigrator} 取法一致（{@code @Value} 带同样的默认值）。</p>
     */
    private final String username;

    /** 数据库密码（同上） */
    private final String password;

    /**
     * 构造器（显式写出来而不是 {@code @RequiredArgsConstructor}：两个账号参数要带 {@code @Value} 默认值）。
     *
     * @param paths     路径派生
     *
     * @param registry  存档列表（导出前取世界名与挂载状态）
     *
     * @param indexDao  目录库索引（导入时插入新行/回写摘要）
     *
     * @param manifests 清单读写
     *
     * @param username  数据库账号（{@code spring.datasource.username}，默认 {@code sa}）
     *
     * @param password  数据库密码（默认空）
     */
    public WorldArchiveService(WorldPaths paths,
                              WorldRegistryService registry,
                              WorldIndexDao indexDao,
                              WorldManifestService manifests,
                              @org.springframework.beans.factory.annotation.Value("${spring.datasource.username:sa}") String username,
                              @org.springframework.beans.factory.annotation.Value("${spring.datasource.password:}") String password) {
        this.paths = paths;
        this.registry = registry;
        this.indexDao = indexDao;
        this.manifests = manifests;
        this.username = username;
        this.password = password;
    }

    /**
     * 打开某个库文件（世界库副本 / 包内世界库 / 已落位世界库）。
     *
     * @param dbFile 库文件
     *
     * @return 连接（调用方负责关闭）
     *
     * @throws SQLException 打不开（密码不对、被占用、文件损坏）
     */
    private Connection openDb(Path dbFile) throws SQLException {
        return DriverManager.getConnection(WorldPaths.dbUrl(dbFile), username, password);
    }

    /**
     * 导出结果。
     *
     * @param fileName    建议的文件名（{@code <世界名>-<uid8>-<yyyyMMdd>.holzyn}）
     * @param worldUid    世界标识
     * @param bytes       包体字节数
     * @param dbSha256    包内世界库的 sha256（导出后可与导入包对照）
     * @param entries     写入的 zip 条目数（清单 + 库 + 资源文件）
     * @param elapsedMs   耗时
     */
    public record ExportResult(String fileName, String worldUid, long bytes, String dbSha256,
                               int entries, long elapsedMs) {
    }

    /**
     * 导入结果。
     *
     * @param worldId       本机目录库主键（新世界的 id）
     * @param worldUid      本机世界 uid（冲突时为新生成的 uid）
     * @param name          世界名（取清单/包内镜像行）
     * @param uidRewritten  是否因为 uid 冲突而改了身份
     * @param bytes         包体字节数
     * @param entries       解出的 zip 条目数
     * @param gameSecond    世界时间（取自包内 {@code actor_world_clock}，可空）
     * @param characterCount 角色数（取自包内镜像行，可空）
     * @param elapsedMs     耗时
     */
    public record ImportResult(long worldId, String worldUid, String name, boolean uidRewritten,
                               long bytes, int entries, Long gameSecond, Long characterCount, long elapsedMs) {
    }

    /**
     * 建议的下载文件名（不含路径）。
     *
     * @param worldId 目录库主键
     *
     * @return {@code <世界名>-<uid8>-<yyyyMMdd>.holzyn}（世界名里非法字符替换为下划线）
     */
    public String fileName(long worldId) {
        WorldSummaryVO summary = registry.requireById(worldId);
        String uid = summary.worldUid() == null ? "" : summary.worldUid();
        String uid8 = uid.length() >= 8 ? uid.substring(0, 8) : uid;
        String name = summary.name() == null || summary.name().isBlank() ? "world" : summary.name();
        return name.replaceAll(ILLEGAL_NAME_CHARS, "_").trim() + "-" + uid8 + "-"
                + LocalDate.now().format(FILE_STAMP) + PACKAGE_SUFFIX;
    }

    /**
     * 导出世界为 zip 流（写入调用方给的 {@link OutputStream}，通常是 HTTP 响应体）。
     *
     * <p>准入：只有"未挂载"的世界能导出。挂载中导出会读到还在写的事务态（H2 会锁文件，直接复制失败），
     * 且存档列表页本身就是"世界已关闭"的视角，这条限制对用户没有额外成本。</p>
     *
     * @param worldId 目录库主键
     * @param out     输出流（本方法不关闭它，由调用方负责）
     *
     * @return 导出摘要（条目数、库 sha256、字节数）
     *
     * @throws BizException 404 世界不存在；409 世界正在使用中 / 世界库缺失 / 清洗失败
     */
    public ExportResult export(long worldId, OutputStream out) {
        long start = System.currentTimeMillis();
        WorldSummaryVO summary = registry.requireById(worldId);
        if (summary.mounted()) {
            throw new BizException(409, "世界正在使用中，请先关闭再导出（WORLD_OPEN）：" + summary.name());
        }
        String uid = summary.worldUid();
        if (uid == null || uid.isBlank()) {
            throw new BizException(409, "该存档缺少世界标识（project_uid），无法导出：" + worldId);
        }
        Path worldDir = paths.worldDir(uid);
        Path dbFile = paths.worldDbFile(uid);
        if (!Files.isDirectory(worldDir) || !Files.isRegularFile(dbFile)) {
            throw new BizException(409, "世界目录或世界库不存在，无法导出：" + worldDir);
        }

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory(exportTmpRoot(), uid + "-");
            Path dbCopy = tmpDir.resolve(DB_ENTRY);
            Files.copy(dbFile, dbCopy, StandardCopyOption.REPLACE_EXISTING);
            int scrubbed = scrubSecrets(dbCopy);
            String dbSha = sha256(dbCopy);
            long dbBytes = Files.size(dbCopy);
            WorldManifest manifest = exportManifest(worldDir, summary, uid, dbSha, dbBytes);

            int entries = 0;
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                entries += writeBytes(zip, MANIFEST_ENTRY, WorldManifestService.toJson(manifest)
                        .getBytes(StandardCharsets.UTF_8));
                entries += writeFile(zip, DB_ENTRY, dbCopy);
                for (String res : WorldPaths.RESOURCE_DIR_NAMES) {
                    Path dir = worldDir.resolve(res);
                    if (Files.isDirectory(dir)) {
                        entries += zipTree(zip, dir, res);
                    }
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("[world-archive] 导出完成 id={} uid={} 条目={} 库={}KB 清洗密文行={} 耗时={}ms",
                    worldId, uid, entries, dbBytes / 1024, scrubbed, elapsed);
            return new ExportResult(fileName(worldId), uid, dbBytes, dbSha, entries, elapsed);
        } catch (IOException e) {
            throw new BizException(409, "导出失败（世界目录未改动，可重试）：" + e.getMessage());
        } finally {
            deleteTreeQuietly(tmpDir);
        }
    }

    /**
     * 导入 {@code .holzyn} 包（v3 = 世界目录 zip）。
     *
     * @param in       包输入流（multipart 的 InputStream，方法内读完，不关闭）
     * @param fileName 原始文件名（只用于日志与清单 {@code source.from}）
     * @param userId   归属用户
     *
     * @return 导入结果（新世界 id / uid / 摘要）
     *
     * @throws BizException 400 包损坏/格式不兼容/缺世界库；409 落位失败
     */
    public ImportResult importPackage(InputStream in, String fileName, Long userId) {
        long start = System.currentTimeMillis();
        Path tmpDir = null;
        Path staged = null;
        Path landedDir = null;
        Long landedId = null;
        try {
            tmpDir = Files.createTempDirectory(importTmpRoot(), "pkg-");
            staged = tmpDir.resolve("world");
            Files.createDirectories(staged);
            int entries = extractZip(in, staged);
            long bytes = dirSize(staged);

            WorldManifest manifest = manifests.read(staged);
            if (manifest == null) {
                throw new BizException(400, "不是 HolzynActor 存档包：缺 " + MANIFEST_ENTRY);
            }
            requireImportable(manifest, staged);

            Path stagedDb = staged.resolve(manifest.dbFile() == null ? DB_ENTRY : manifest.dbFile());
            if (!Files.isRegularFile(stagedDb)) {
                throw new BizException(400, "存档包缺世界库：" + stagedDb.getFileName());
            }
            requireWorldSchema(stagedDb);

            // 1) 读取包内世界自述（摘要/封面/时钟/角色数）：必须在改写 project_id 之前
            ImportedWorld imported = readImportedWorld(stagedDb, manifest);

            // 2) uid 冲突 → 生成新 uid（"导入 = 新世界"，不覆盖本机已有存档）
            String uid = manifest.worldUid();
            boolean uidRewritten = false;
            if (indexDao.readByUid(uid).isPresent()) {
                uid = UUID.randomUUID().toString();
                uidRewritten = true;
            }
            WorldPaths.requireSafeUid(uid);

            // 3) 插入目录库行（拿到本机新 id）
            LocalDateTime now = LocalDateTime.now();
            long worldId = indexDao.insertIndexRow(uid, imported.name(), userId, WorldStatus.CLOSED,
                    WorldManifest.SCHEMA_VERSION, null, now);
            landedId = worldId;

            // 4) 落位 worlds/<uid>/（先移动再改库：库要按最终路径打开，改完即可直接挂载）
            Path target = paths.worldDir(uid);
            if (Files.exists(target)) {
                throw new BizException(409, "世界目录已存在，导入中止（未删除任何数据）：" + target);
            }
            Files.createDirectories(paths.worldsRoot());
            moveTree(staged, target);
            landedDir = target;
            Path targetDb = target.resolve(manifest.dbFile() == null ? DB_ENTRY : manifest.dbFile());

            // 5) 把世界库内 project_id 改写成本机新 id + 清掉镜像行（下次挂载整表重建）
            int rewritten = rewriteOwnership(targetDb, worldId);

            // 6) 清单改成"导入来源 + 新 uid"
            WorldManifest landed = new WorldManifest(WorldManifest.CURRENT_FORMAT_VERSION,
                    manifest.schemaVersion() == null ? WorldManifest.SCHEMA_VERSION : manifest.schemaVersion(),
                    uid, imported.name(), manifest.createdAt() == null ? now.toString() : manifest.createdAt(),
                    null, null,
                    new WorldManifest.Source("import", fileName, manifest.source() == null ? null : manifest.source().fromSha256(),
                            now.toString()),
                    imported.counts(), imported.checksums(),
                    WorldPaths.RESOURCE_DIR_NAMES, DB_ENTRY);
            manifests.write(target, landed);

            // 7) 回写索引摘要（列表卡片不打开世界库也要能显示时间/角色数/体积）
            String manifestJson = WorldManifestService.toJson(landed);
            indexDao.updateMeta(worldId, imported.name(), imported.summary(), imported.coverUrl(), now);
            indexDao.markClosed(worldId, new WorldIndexDao.ClosedSummary(now, imported.gameSecond(),
                    imported.characterCount(), dirSize(target), manifestJson, landed.schemaVersion()));

            long elapsed = System.currentTimeMillis() - start;
            log.info("[world-archive] 导入完成 id={} uid={} 条目={} 改写行={} uid冲突={} 耗时={}ms",
                    worldId, uid, entries, rewritten, uidRewritten, elapsed);
            return new ImportResult(worldId, uid, imported.name(), uidRewritten, bytes, entries,
                    imported.gameSecond(), imported.characterCount(), elapsed);
        } catch (BizException e) {
            rollbackImport(landedId, landedDir);
            throw e;
        } catch (IOException e) {
            rollbackImport(landedId, landedDir);
            throw new BizException(400, "导入失败（未改动已有存档）：" + e.getMessage());
        } finally {
            deleteTreeQuietly(tmpDir);
        }
    }

    // ------------------------------------------------------------------ 导出内部

    /** 导出临时目录（{@code <data-dir>/export-tmp}；不存在则建） */
    private Path exportTmpRoot() throws IOException {
        Path dir = paths.dataDir().resolve(EXPORT_TMP_DIR);
        Files.createDirectories(dir);
        return dir;
    }

    /** 导入临时目录（{@code <data-dir>/import-tmp}；不存在则建） */
    private Path importTmpRoot() throws IOException {
        Path dir = paths.dataDir().resolve(IMPORT_TMP_DIR);
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * 清空世界库副本里的 AI 密钥密文（AC5：导出包内不得有 {@code api_key_cipher}）。
     *
     * @param dbCopy 世界库副本（会被打开、改写并 {@code SHUTDOWN COMPACT}）
     *
     * @return 被清空的行数（0 表示本来就没有密钥，正常）
     */
    private int scrubSecrets(Path dbCopy) {
        try (Connection c = openDb(dbCopy);
             Statement st = c.createStatement()) {
            if (!tableExists(c, SECRET_TABLE)) {
                return 0;
            }
            int rows = st.executeUpdate("update " + SECRET_TABLE + " set " + SECRET_COLUMN + " = null");
            // 压缩：临时副本里留着旧的 MVStore 页，脱敏值可能仍可被取回（vacuum 语义）
            st.execute("shutdown compact");
            return rows;
        } catch (SQLException e) {
            throw new BizException(409, "导出前清洗世界库失败（原目录未改动）：" + e.getMessage());
        }
    }

    /** 组装导出清单（沿用原清单的创建时间/来源，补上本次的库 sha256 与资源清单） */
    private WorldManifest exportManifest(Path worldDir, WorldSummaryVO summary, String uid,
                                         String dbSha, long dbBytes) {
        WorldManifest base;
        try {
            base = manifests.read(worldDir);
        } catch (RuntimeException e) {
            log.warn("[world-archive] 原清单不可解析，导出时重建：{}", e.toString());
            base = null;
        }
        Map<String, Long> counts = base == null || base.counts() == null ? Map.of() : base.counts();
        Map<String, String> checksums = new java.util.LinkedHashMap<>();
        if (base != null && base.checksums() != null) {
            checksums.putAll(base.checksums());
        }
        checksums.put(DB_ENTRY, dbSha);
        return new WorldManifest(WorldManifest.CURRENT_FORMAT_VERSION,
                base == null || base.schemaVersion() == null ? WorldManifest.SCHEMA_VERSION : base.schemaVersion(),
                uid, summary.name(),
                base == null || base.createdAt() == null ? LocalDateTime.now().toString() : base.createdAt(),
                null, null,
                new WorldManifest.Source("export", DB_ENTRY, dbSha, LocalDateTime.now().toString()),
                counts, checksums, WorldPaths.RESOURCE_DIR_NAMES, DB_ENTRY);
    }

    // ------------------------------------------------------------------ 导入内部

    /** 导入前校验：格式版本、schema 主次版本、uid 合法、库文件名相对路径 */
    private void requireImportable(WorldManifest manifest, Path staged) {
        if (!WorldManifest.CURRENT_FORMAT_VERSION.equals(manifest.formatVersion())) {
            throw new BizException(400, "存档格式不兼容：需要 " + WorldManifest.CURRENT_FORMAT_VERSION
                    + "，实际 " + manifest.formatVersion() + "（本版本不再支持 v2 逐表 JSON 包）");
        }
        if (manifest.worldUid() == null || manifest.worldUid().isBlank()) {
            throw new BizException(400, "存档包清单缺 worldUid");
        }
        String dbFile = manifest.dbFile() == null ? DB_ENTRY : manifest.dbFile();
        if (dbFile.contains("/") || dbFile.contains("\\") || dbFile.contains("..")) {
            throw new BizException(400, "存档包清单的 dbFile 必须是相对文件名：" + dbFile);
        }
        String from = manifest.schemaVersion();
        if (from != null && !from.isBlank() && !sameMajorMinor(from, WorldManifest.SCHEMA_VERSION)) {
            throw new BizException(400, "存档版本不兼容：包=" + from + "，本机=" + WorldManifest.SCHEMA_VERSION
                    + "（主次版本必须一致；同主次的小版本差异会在首次打开时由 Flyway 补迁移）");
        }
        if (!Files.isDirectory(staged)) {
            throw new BizException(400, "存档包解包失败：" + staged);
        }
    }

    /** 校验包内世界库确实是"世界库"（有 actor_schema_meta 或至少 actor_world_clock） */
    private void requireWorldSchema(Path dbFile) {
        try (Connection c = openDb(dbFile)) {
            if (!tableExists(c, "actor_world_clock") && !tableExists(c, "actor_schema_meta")) {
                throw new BizException(400, "存档包里的库不是 HolzynActor 世界库（找不到 actor_world_clock）："
                        + dbFile.getFileName());
            }
        } catch (SQLException e) {
            throw new BizException(400, "存档包里的世界库打不开：" + e.getMessage());
        }
    }

    /**
     * 读包内世界自述（摘要/封面/时钟/角色数）。
     *
     * <p>为什么要读镜像 {@code actor_project} 行：清单里没有摘要与封面字段，而存档卡片需要它们；
     * 镜像行正是导出时该世界的索引行，是唯一还带着摘要的地方。</p>
     */
    private ImportedWorld readImportedWorld(Path dbFile, WorldManifest manifest) {
        String name = manifest.worldName() == null || manifest.worldName().isBlank() ? "导入的世界" : manifest.worldName();
        String summary = null;
        String coverUrl = null;
        Long characterCount = null;
        Long gameSecond = null;
        try (Connection c = openDb(dbFile);
             Statement st = c.createStatement()) {
            if (tableExists(c, "actor_project")) {
                try (ResultSet rs = st.executeQuery(
                        "select name, summary, cover_url, character_count from actor_project")) {
                    if (rs.next()) {
                        String n = rs.getString(1);
                        if (n != null && !n.isBlank()) {
                            name = n;
                        }
                        summary = rs.getString(2);
                        coverUrl = rs.getString(3);
                        long cc = rs.getLong(4);
                        characterCount = rs.wasNull() ? null : cc;
                    }
                }
            }
            if (tableExists(c, "actor_world_clock")) {
                try (ResultSet rs = st.executeQuery("select last_game_second from actor_world_clock")) {
                    if (rs.next()) {
                        long gs = rs.getLong(1);
                        gameSecond = rs.wasNull() ? null : gs;
                    }
                }
            }
            Map<String, Long> counts = new java.util.LinkedHashMap<>();
            if (gameSecond != null) {
                counts.put("gameSecond", gameSecond);
            }
            if (characterCount != null) {
                counts.put("characters", characterCount);
            }
            return new ImportedWorld(name, summary, coverUrl, gameSecond, characterCount, counts, Map.of());
        } catch (SQLException e) {
            throw new BizException(400, "读取存档包内世界信息失败：" + e.getMessage());
        }
    }

    /**
     * 把世界库里所有 {@code project_id} 改成本机新 id，并清空镜像 {@code actor_project} 行。
     *
     * <p>逐列探测而不是写死 88 张表：包可能来自更老的 schema（表少几张、或某表当时还没有
     * {@code project_id}），写死清单会在"表不存在"上失败。探测口径与 M3 迁移器一致——
     * 以世界库自己的 {@code information_schema} 为准。</p>
     *
     * <p>镜像 {@code actor_project} 直接清空：它是"目录库自己那一行"的副本，下次挂载时
     * {@link WorldMirrorSyncService} 会按目录库数据整表重建（{@code deleteAll} + 拷贝），
     * 在这里改它的 identity 主键反而容易撞约束。</p>
     *
     * @param dbFile 已落位的世界库
     * @param newId  本机目录库主键
     *
     * @return 被改写的行数（便于日志核对"是不是同一个世界的行"）
     */
    private int rewriteOwnership(Path dbFile, long newId) {
        try (Connection c = openDb(dbFile)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                int total = 0;
                for (String table : tablesWithProjectId(c)) {
                    total += st.executeUpdate("update " + table + " set project_id = " + newId
                            + " where project_id is not null and project_id <> " + newId);
                }
                if (tableExists(c, MirrorTables.OWN_ROW_TABLE)) {
                    total += st.executeUpdate("delete from " + MirrorTables.OWN_ROW_TABLE);
                }
                c.commit();
                return total;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new BizException(409, "改写世界归属失败（该存档不会出现在列表里，可重新导入）：" + e.getMessage());
        }
    }

    /** 世界库里带 {@code project_id} 列的表名（按名字排序，日志可复现） */
    private static List<String> tablesWithProjectId(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "select table_name from information_schema.columns "
                             + "where table_schema = 'PUBLIC' and lower(column_name) = 'project_id' "
                             + "order by table_name")) {
            while (rs.next()) {
                out.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** 表是否存在（大小写不敏感标识符下统一用小写比较） */
    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (java.sql.PreparedStatement ps = c.prepareStatement(
                "select 1 from information_schema.tables where table_schema = 'PUBLIC' and lower(table_name) = ?")) {
            ps.setString(1, table.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 主次版本（前两段）是否一致 */
    private static boolean sameMajorMinor(String a, String b) {
        return majorMinor(a).equals(majorMinor(b));
    }

    private static String majorMinor(String version) {
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    // ------------------------------------------------------------------ 包内自述

    /** 从包内世界读出的自述信息（索引行与摘要回写用） */
    private record ImportedWorld(String name, String summary, String coverUrl, Long gameSecond,
                                 Long characterCount, Map<String, Long> counts, Map<String, String> checksums) {
    }

    // ------------------------------------------------------------------ zip 工具

    /** 写一个内存条目 */
    private static int writeBytes(ZipOutputStream zip, String entryName, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(data);
        zip.closeEntry();
        return 1;
    }

    /** 写一个文件条目 */
    private static int writeFile(ZipOutputStream zip, String entryName, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zip);
        zip.closeEntry();
        return 1;
    }

    /** 递归写目录（条目名统一用 `/`，Windows 上解出来也能被普遍工具读） */
    private static int zipTree(ZipOutputStream zip, Path root, String prefix) throws IOException {
        int entries = 0;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                entries += writeFile(zip, prefix + "/" + rel, p);
            }
        }
        return entries;
    }

    /**
     * 解包到目标目录（防 zip-slip：条目解析后的规范化路径必须仍在目标目录内）。
     *
     * @return 解出的文件条目数
     */
    private static int extractZip(InputStream in, Path destDir) throws IOException {
        Path root = destDir.toAbsolutePath().normalize();
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.isBlank() || name.startsWith("/") || name.contains("..")) {
                    throw new IOException("存档包含非法路径条目：" + name);
                }
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("存档包含越权路径条目：" + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                entries++;
            }
        }
        return entries;
    }

    /** 目录总字节数（不跟随符号链接） */
    private static long dirSize(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 文件 sha256（十六进制小写） */
    private static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new UncheckedIOException("计算 sha256 失败：" + file, new IOException(e));
        }
    }

    /** 目录移动（跨盘/不支持原子移动时退化为复制 + 删源） */
    private static void moveTree(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 跨盘移动、容器卷不支持原子 move、目标已存在等：退化为复制 + 删源（最坏情况多占一份磁盘）
            copyTree(from, to);
            deleteTreeQuietly(from);
        }
    }

    /** 递归复制目录 */
    private static void copyTree(Path from, Path to) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(from)) {
            for (Path src : walk.toList()) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 静默删目录（清理临时目录用；失败只记 debug） */
    private static void deleteTreeQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.debug("[world-archive] 清理临时目录失败：{}（{}）", dir, e.toString());
        }
    }

    /**
     * 导入失败后的回滚：删掉已落位的世界目录，并硬删刚插入的索引行。
     *
     * <p>为什么必须删干净：落位之后的世界目录会被下次启动的 {@link WorldAdoptService} 当成
     * "用户手工拷进来的世界"重新登记——用户会看到一个点开就报错、删也删不利索的僵尸存档。</p>
     *
     * @param worldId 已插入的索引行主键（可空）
     * @param dir     已落位的世界目录（可空）
     */
    private void rollbackImport(Long worldId, Path dir) {
        if (dir != null) {
            deleteTreeQuietly(dir);
            log.warn("[world-archive] 导入失败，已清理落位目录：{}", dir);
        }
        if (worldId != null) {
            try {
                indexDao.deleteRow(worldId);
            } catch (RuntimeException e) {
                log.warn("[world-archive] 导入失败后清理索引行失败 id={}（需人工清理）：{}", worldId, e.toString());
            }
        }
    }
}
