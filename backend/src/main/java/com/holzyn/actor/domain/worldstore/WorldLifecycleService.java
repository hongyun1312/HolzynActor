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
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 存档生命周期编排（V1.1.2-beta.1：{@code /api/worlds} 的唯一实现）。
 *
 * <p>本类<b>不做</b>底层动作：挂载/卸载在 {@link WorldSessionService}，镜像刷新在
 * {@link WorldMirrorSyncService}，索引在 {@link WorldIndexDao}。它只负责三件"跨层"的事：</p>
 * <ol>
 *   <li><b>准入判定</b>：状态是否可挂载、是否已有别的世界占着（错误码见设计 §4.3）；</li>
 *   <li><b>摘要在关闭前读取</b>：关闭会销毁连接池，之后再想读世界时间就没机会了；</li>
 *   <li><b>索引回写</b>：把"最近打开/关闭时间、游戏内时间、角色数、体积、状态"落到目录库，
 *       让存档列表<b>不打开任何世界库</b>也能显示完整卡片。</li>
 * </ol>
 *
 * <p>顺序固定为"先读摘要 → 再关库 → 最后回写"。反过来会出现"库已关、摘要没读到、索引被写成
 * 0"的存档，用户下次打开会看到世界时间归零——对世界模拟类应用是最刺眼的那种 bug。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Service
public class WorldLifecycleService {

    private final WorldSessionService sessions;

    private final WorldRegistryService registry;

    private final WorldIndexDao indexDao;

    private final WorldDataSourceFactory dataSources;

    private final WorldSummaryReader summaryReader;

    private final WorldPaths paths;

    private final WorldManifestService manifests;

    public WorldLifecycleService(WorldSessionService sessions, WorldRegistryService registry,
                                 WorldIndexDao indexDao, WorldDataSourceFactory dataSources,
                                 WorldSummaryReader summaryReader, WorldPaths paths,
                                 WorldManifestService manifests) {
        this.sessions = sessions;
        this.registry = registry;
        this.indexDao = indexDao;
        this.dataSources = dataSources;
        this.summaryReader = summaryReader;
        this.paths = paths;
        this.manifests = manifests;
    }

    /**
     * 打开/挂载结果。
     *
     * @param worldId     世界主键（目录库 id）
     * @param worldUid    世界身份
     * @param newlyOpened 是否本次真正打开（false = 之前已打开）
     * @param mirrorTables 镜像刷新表数（未刷新为 0）
     * @param mirrorRows  镜像刷新行数
     * @param elapsedMs   耗时（验收 AC2：空世界打开 ≤3s）
     */
    public record OpenOutcome(long worldId, String worldUid, boolean newlyOpened,
                              int mirrorTables, long mirrorRows, long elapsedMs) {
    }

    /**
     * 关闭/卸载结果。
     *
     * @param worldId        世界主键
     * @param worldUid       世界身份
     * @param elapsedMs      耗时
     * @param gameSecond     回写的世界时间（null = 本次没读到，沿用旧值）
     * @param characterCount 回写的角色数
     * @param sizeBytes      回写的目录体积
     * @param lockResidual   关闭后是否仍残留 H2 锁文件（正常应为 false）
     */
    public record CloseOutcome(long worldId, String worldUid, long elapsedMs, Long gameSecond,
                               Long characterCount, Long sizeBytes, boolean lockResidual) {
    }

    /**
     * 打开世界（HTTP 层唯一入口）。
     *
     * @param worldId 目录库主键
     *
     * @return 打开结果
     *
     * @throws BizException 404 不存在；409 状态不可挂载 / 已有其它世界挂载
     */
    public OpenOutcome open(long worldId) {
        long start = System.currentTimeMillis();
        WorldSummaryVO summary = registry.requireById(worldId);
        String uid = summary.worldUid();
        if (uid == null || uid.isBlank()) {
            throw new BizException(409, "该存档缺少世界标识（project_uid），无法挂载：" + worldId);
        }
        // 单活动世界：按接口契约拒绝，而不是悄悄把另一个世界关掉（那会让用户丢未保存的状态）
        sessions.current().ifPresent(cur -> {
            if (!cur.equals(uid)) {
                throw new BizException(409, "已有其它世界挂载（uid=" + cur + "），请先关闭当前世界（WORLD_BUSY）");
            }
        });
        if (!summary.mountable() && !summary.mounted()) {
            throw new BizException(409, "世界当前状态为「" + summary.worldStatusLabel() + "」，不能打开"
                    + (summary.dirPresent() ? "" : "（世界目录不存在：" + paths.worldDir(uid) + "）"));
        }
        WorldSessionService.OpenResult result;
        try {
            result = sessions.open(uid);
        } catch (WorldManifestException e) {
            throw new BizException(409, "世界清单不兼容（WORLD_BROKEN）：" + e.getMessage());
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("[world-store] 打开世界失败 uid={}", uid, e);
            throw new BizException(409, "世界打不开（WORLD_BROKEN）：" + e.getMessage());
        }
        try {
            indexDao.markMounted(worldId, uid, LocalDateTime.now());
        } catch (RuntimeException e) {
            // 世界确实已经打开：索引写失败只是列表显示滞后，由下次启动收编修正
            log.warn("[world-store] 回写挂载状态失败 uid={}（世界已打开，继续）：{}", uid, e.toString());
        }
        long elapsed = System.currentTimeMillis() - start;
        int tables = result.mirrorReport() == null ? 0 : result.mirrorReport().tables();
        long rows = result.mirrorReport() == null ? 0 : result.mirrorReport().rows();
        log.info("[world-store] 打开完成 id={} uid={} 新打开={} 耗时={}ms", worldId, uid, result.newlyOpened(), elapsed);
        return new OpenOutcome(worldId, uid, result.newlyOpened(), tables, rows, elapsed);
    }

    /**
     * 关闭世界（HTTP 层唯一入口）。
     *
     * @param worldId 目录库主键
     *
     * @return 关闭结果
     *
     * @throws BizException 404 不存在；409 当前没有挂载世界/挂载的不是这个世界
     */
    public CloseOutcome close(long worldId) {
        long start = System.currentTimeMillis();
        WorldSummaryVO summary = registry.requireById(worldId);
        String uid = summary.worldUid();
        Optional<String> current = sessions.current();
        if (current.isEmpty()) {
            throw new BizException(409, "当前没有挂载任何世界（WORLD_NOT_OPEN）");
        }
        if (!current.get().equals(uid)) {
            throw new BizException(409, "路径里的世界与当前挂载的世界不是同一个（WORLD_MISMATCH）：当前="
                    + current.get() + "，请求=" + uid);
        }
        return closeMounted(worldId, uid, "user", start);
    }

    /**
     * 关停/切换时静默关闭当前世界（不抛异常、不阻塞退出）。
     *
     * @param reason 关闭原因（shutdown / switch）
     *
     * @return 关闭结果；未挂载时为空
     */
    public Optional<CloseOutcome> closeCurrentQuietly(String reason) {
        Optional<String> current = sessions.current();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        String uid = current.get();
        long start = System.currentTimeMillis();
        try {
            Optional<Long> id = indexDao.readByUid(uid).map(WorldIndexDao.WorldIndexRow::id);
            if (id.isEmpty()) {
                sessions.close(reason);
                return Optional.empty();
            }
            return Optional.of(closeMounted(id.get(), uid, reason, start));
        } catch (RuntimeException e) {
            log.warn("[world-store] 关闭当前世界时出错 uid={}（继续退出）：{}", uid, e.toString());
            try {
                sessions.close(reason);
            } catch (RuntimeException ignored) {
                log.warn("[world-store] 强制卸载世界失败 uid={}", uid);
            }
            return Optional.empty();
        }
    }

    /** 已挂载世界的关闭实现（摘要 → 关库 → 回写索引） */
    private CloseOutcome closeMounted(long worldId, String uid, String reason, long start) {
        // 1. 关闭前读摘要（关库之后世界库不可访问）
        DataSource world = dataSources.peek(uid);
        WorldSummaryReader.Snapshot snapshot = summaryReader.read(world, worldId);
        // 1b. 跨世界用量摘要（actor_usage_log 是世界表，只能在关库前算；见 WorldSummaryReader#readUsage）
        WorldSummaryReader.Usage usage = summaryReader.readUsage(world, worldId);

        // 2. 关库（含 SHUTDOWN COMPACT 与连接池释放）
        sessions.close(reason);

        // 3. 体积在关库后统计（关库时 COMPACT 会改变文件大小）
        Long sizeBytes = null;
        try {
            long size = registry.measureSize(uid);
            sizeBytes = size > 0 ? size : null;
        } catch (RuntimeException e) {
            log.debug("[world-store] 统计世界体积失败 uid={}：{}", uid, e.toString());
        }

        boolean lockResidual = Files.exists(paths.worldLockFile(uid));
        if (lockResidual) {
            log.warn("[world-store] 关闭后仍残留锁文件（H2 可能未干净落盘）：{}", paths.worldLockFile(uid));
        }

        try {
            indexDao.markClosed(worldId, new WorldIndexDao.ClosedSummary(
                    LocalDateTime.now(), snapshot.gameSecond(), snapshot.characterCount(),
                    sizeBytes, manifestText(uid), schemaVersion(uid)));
        } catch (RuntimeException e) {
            log.warn("[world-store] 回写关闭摘要失败 uid={}（世界已关闭，继续）：{}", uid, e.toString());
        }

        // 用量摘要单独一次写：它来自世界库、与存档卡片快照不同源，失败互不影响（M4）
        try {
            indexDao.updateUsageSummary(worldId, usage.tokenIn(), usage.tokenOut(), usage.aiCalls());
        } catch (RuntimeException e) {
            log.warn("[world-store] 回写用量摘要失败 uid={}（世界已关闭，继续）：{}", uid, e.toString());
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[world-store] 关闭完成 id={} uid={} 耗时={}ms 体积={} 锁残留={}",
                worldId, uid, elapsed, sizeBytes, lockResidual);
        return new CloseOutcome(worldId, uid, elapsed, snapshot.gameSecond(), snapshot.characterCount(),
                sizeBytes, lockResidual);
    }

    /** 清单原文（关闭后写入索引做快照；读不到返回 null，由 coalesce 保留旧值） */
    private String manifestText(String uid) {
        Path file = paths.manifestFile(uid);
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            log.debug("[world-store] 读取清单原文失败 uid={}：{}", uid, e.toString());
            return null;
        }
    }

    /** 清单里的 schemaVersion（读不到返回 null） */
    private String schemaVersion(String uid) {
        try {
            WorldManifest m = manifests.read(paths.worldDir(uid));
            return m == null ? null : m.schemaVersion();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 新建 / 归档删除（M5）

    /**
     * 新建世界结果。
     *
     * @param worldId   目录库主键
     * @param worldUid  世界身份（= 目录名）
     * @param name      落库的世界名（入参为空时已归一成"未命名世界"，回显给前端不必再猜）
     * @param elapsedMs 建目录 + 清单 + 索引耗时（不含首次挂载）
     */
    public record CreateOutcome(long worldId, String worldUid, String name, long elapsedMs) {
    }

    /**
     * 归档删除结果。
     *
     * @param worldId    目录库主键
     * @param worldUid   世界身份
     * @param archivedTo 世界目录被移到哪里（相对数据目录；跨盘时为绝对路径）
     * @param elapsedMs  耗时
     */
    public record DeleteOutcome(long worldId, String worldUid, String archivedTo, long elapsedMs) {
    }

    /** 清单时间戳格式（与 {@link WorldManifestService} 一致） */
    private static final DateTimeFormatter MANIFEST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 归档目录名里的时间戳（本地时间，秒级足够区分同一世界的重复删除） */
    private static final DateTimeFormatter ARCHIVE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 新建世界（只建"世界外壳"：目录 + manifest + 目录库索引行；世界库在首次挂载时由 Flyway 建）。
     *
     * <p>为什么不在这里就把世界库建出来：建库是 {@link WorldDataSourceFactory} 的职责（它同时负责
     * 连接池、迁移、关闭时的 {@code SHUTDOWN COMPACT}），这里再写一套建库逻辑就是第二套入口；
     * 而"建了库但没挂载"的世界在列表里与"从没打开过"没有区别。</p>
     *
     * <p>失败回滚：这一行是<b>刚插进去的垃圾</b>，必须硬删（{@link WorldIndexDao#deleteRow}）并把目录一起清掉，
     * 否则列表里会留下一个永远打不开的"空世界"。</p>
     *
     * @param name   世界名（空则用"未命名世界"）
     * @param userId 归属用户
     *
     * @return 新建结果
     */
    public CreateOutcome create(String name, Long userId) {
        long start = System.currentTimeMillis();
        String display = (name == null || name.isBlank()) ? "未命名世界" : name.trim();
        String uid = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        Path dir = paths.worldDir(uid);
        long id = indexDao.insertIndexRow(uid, display, userId, WorldStatus.CLOSED,
                WorldManifest.SCHEMA_VERSION, null, now);
        boolean ok = false;
        try {
            Files.createDirectories(dir);
            manifests.write(dir, WorldManifest.fresh(uid, display, WorldManifest.SCHEMA_VERSION,
                    now.format(MANIFEST_TIME)));
            ok = true;
        } catch (IOException | RuntimeException e) {
            throw new BizException(409, "新建世界失败（已回滚）：" + e.getMessage());
        } finally {
            if (!ok) {
                try {
                    indexDao.deleteRow(id);
                } catch (RuntimeException e) {
                    log.warn("[world-store] 新建世界回滚索引失败 id={}（需人工清理）：{}", id, e.toString());
                }
                deleteTreeQuietly(dir);
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("[world-store] 世界已新建 id={} uid={} name={} 耗时={}ms", id, uid, display, elapsed);
        return new CreateOutcome(id, uid, display, elapsed);
    }

    /**
     * 把"老流程刚插进目录库的行"补成可分档存储的世界壳，并挂载它（新建世界的兼容入口）。
     *
     * <p>为什么存在：{@code ProjectImportService}（文件解析确认）与 {@code WorldParseService}（工作流新建）
     * 走的是老的 {@code ProjectService.create}——JPA 直接往 {@code actor_project} 插实体，插出来的行没有
     * {@code project_uid}／{@code data_dir_name}，分档存储下**永远打不开**（挂载时缺 uid 直接 409）。
     * 与其把两条流程的全部代码改成 {@link #create}，不如在这里补齐壳（uid + 目录 + 清单 + 索引列）
     * 再挂载：两条流程后续写世界库的动作照旧，只是它们脚下终于有了一个真正的世界。</p>
     *
     * <p>单活动世界：新建是显式动作，所以先把当前挂载的世界静默关掉（{@code close} 会压盘并回写摘要，
     * 不丢数据），再挂新世界。若这里也照 {@code open} 的契约抛 409，用户点"新建世界"就会莫名失败。</p>
     *
     * @param worldId 目录库主键（已存在的索引行）
     *
     * @return 新建结果（worldUid 为本次补齐或既有的 uid）
     *
     * @throws BizException 404 行不存在；409 补壳失败（目录/清单写不进去）
     */
    public CreateOutcome mountNewWorld(long worldId) {
        long start = System.currentTimeMillis();
        WorldSummaryVO summary = registry.requireById(worldId);
        String uid = summary.worldUid();
        String display = summary.name() == null || summary.name().isBlank() ? "未命名世界" : summary.name();
        if (uid == null || uid.isBlank()) {
            uid = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now();
            Path dir = paths.worldDir(uid);
            try {
                Files.createDirectories(dir);
                manifests.write(dir, WorldManifest.fresh(uid, display, WorldManifest.SCHEMA_VERSION,
                        now.format(MANIFEST_TIME)));
            } catch (IOException | RuntimeException e) {
                throw new BizException(409, "新建世界失败（补写世界目录）：" + e.getMessage());
            }
            indexDao.bindShell(worldId, uid, WorldManifest.SCHEMA_VERSION, now);
        }
        // 已有世界先静默关掉（内部已压盘 + 回写摘要；未挂载时是 no-op）
        closeCurrentQuietly("switch");
        open(worldId);
        long elapsed = System.currentTimeMillis() - start;
        log.info("[world-store] 新建流程已补壳并挂载 id={} uid={} name={} 耗时={}ms", worldId, uid, display, elapsed);
        return new CreateOutcome(worldId, uid, display, elapsed);
    }

    /**
     * 归档删除世界（把世界目录整体移进 {@code archive/deleted-worlds/<uid>-<ts>/}，索引行软删）。
     *
     * <p>为什么是"移走"不是"删掉"：世界目录里是用户唯一的存档；误删一次就是永久丢失。
     * 移进归档后列表看不见（{@code deleted=1}），但人工仍能救回。真正删盘留给用户自己动手。</p>
     *
     * <p>为什么要求"未挂载"：H2 文件库独占锁，挂载中移动 `mv.db` 会让连接池指向一个不存在的文件；
     * 且 UI 的删除入口本来就在存档列表（世界里没有删除按钮）。</p>
     *
     * @param worldId 目录库主键
     *
     * @return 归档结果
     *
     * @throws BizException 404 不存在；409 世界正在使用中 / 归档移动失败
     */
    public DeleteOutcome delete(long worldId) {
        long start = System.currentTimeMillis();
        WorldSummaryVO summary = registry.requireById(worldId);
        String uid = summary.worldUid();
        if (uid == null || uid.isBlank()) {
            throw new BizException(409, "该存档缺少世界标识（project_uid），无法归档删除：" + worldId);
        }
        if (sessions.current().filter(uid::equals).isPresent()) {
            throw new BizException(409, "世界正在使用中，请先关闭再删除（WORLD_OPEN）");
        }
        Path dir = paths.worldDir(uid);
        Path target = paths.deletedWorldsDir().resolve(uid + "-" + LocalDateTime.now().format(ARCHIVE_STAMP));
        String archivedTo = null;
        if (Files.isDirectory(dir)) {
            try {
                Files.createDirectories(target.getParent());
                moveTree(dir, target);
                archivedTo = displayPath(target);
            } catch (IOException | RuntimeException e) {
                throw new BizException(409, "归档世界目录失败（未删除任何数据，可重试）：" + e.getMessage());
            }
        }
        // 目录已移走：即使索引回写失败，世界也不在列表里了（dirPresent=false → missing），不会误挂载
        indexDao.markDeleted(worldId, LocalDateTime.now());
        long elapsed = System.currentTimeMillis() - start;
        log.info("[world-store] 世界已归档删除 id={} uid={} → {} 耗时={}ms", worldId, uid, archivedTo, elapsed);
        return new DeleteOutcome(worldId, uid, archivedTo, elapsed);
    }

    /**
     * 改名 / 封面 / 概要（只写目录库索引，不动世界库）。
     *
     * @param worldId  目录库主键
     * @param name     新世界名（可空 = 不改）
     * @param summary  新概要（可空 = 不改）
     * @param coverUrl 新封面（可空 = 不改）
     *
     * @return 更新后的存档卡片
     *
     * @throws BizException 404 不存在
     */
    public WorldSummaryVO updateMeta(long worldId, String name, String summary, String coverUrl) {
        registry.requireById(worldId);
        indexDao.updateMeta(worldId, name, summary, coverUrl, LocalDateTime.now());
        return registry.requireById(worldId);
    }

    /** 封面文件名（世界目录内、相对路径）：PNG（权威定义在 {@link WorldPaths}） */
    public static final String COVER_PNG = WorldPaths.COVER_PNG;

    /** 封面文件名（世界目录内、相对路径）：JPG（权威定义在 {@link WorldPaths}） */
    public static final String COVER_JPG = WorldPaths.COVER_JPG;

    /** 封面上传上限（8MB）：再大就不是封面而是壁纸，画廊列表会被拖慢 */
    private static final long COVER_MAX_BYTES = 8L * 1024 * 1024;

    /** 封面最长边（超过则等比缩小；卡片显示宽约 300px，存 4K 原图纯属浪费带宽与磁盘） */
    private static final int COVER_MAX_EDGE = 1280;

    /**
     * 上传 / 替换世界封面（写入 {@code worlds/<uid>/uploads/cover.png|jpg} 并回写索引）。
     *
     * <p>为什么封面文件放在<b>世界目录的 uploads/ 里</b>而不是全局 uploads：世界目录是"拷走即带走"
     * 的最小单位，而 .holzyn 导出只打包 manifest + 世界库 + {@link WorldPaths#RESOURCE_DIR_NAMES}
     * （含 uploads），封面放 uploads 才能随导出/导入自动往返。索引里的 {@code cover_url} 只存
     * 相对路径（如 {@code uploads/cover.png}），不存绝对 URL——存 URL 会在导入重编号后指回旧世界。</p>
     *
     * <p>格式只收 PNG / JPG：两者 JDK 自带编解码器，不引第三方依赖；WebP 会被 ImageIO 判为
     * 无法识别（400），而不是存进去之后前端显示空白。</p>
     *
     * @param worldId 目录库主键
     * @param file    用户上传的图片（multipart）
     *
     * @return 更新后的存档卡片（{@code coverUrl} = 相对文件名）
     *
     * @throws BizException 404 世界不存在 / 400 文件为空、过大、非图片 / 409 写盘失败
     */
    public WorldSummaryVO uploadCover(long worldId, MultipartFile file) {
        WorldSummaryVO summary = registry.requireById(worldId);
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要上传的封面图片");
        }
        if (file.getSize() > COVER_MAX_BYTES) {
            throw new BizException(400, "封面图片过大（上限 8MB）");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(400, "读取封面图片失败：" + e.getMessage());
        }
        BufferedImage image = decodeImage(bytes);
        if (image == null) {
            throw new BizException(400, "无法识别的图片格式，请上传 PNG / JPG");
        }
        boolean png = isPng(bytes);
        String fileName = png ? COVER_PNG : COVER_JPG;
        Path dir = paths.worldUploadsDir(summary.worldUid());
        Path target = dir.resolve(fileName);
        try {
            Files.createDirectories(dir);
            BufferedImage out = scaleDown(image);
            Path tmp = Files.createTempFile(dir, "cover-", ".tmp");
            try {
                BufferedImage encoded = png ? out : toRgb(out);
                if (!ImageIO.write(encoded, png ? "png" : "jpg", tmp.toFile())) {
                    throw new IOException("没有可用的 " + (png ? "PNG" : "JPG") + " 编码器");
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            // 换格式时删掉旧后缀，避免 cover.png / cover.jpg 同时存在（读取时按 PNG 优先会读到旧图）
            Files.deleteIfExists(dir.resolve(png ? COVER_JPG : COVER_PNG));
        } catch (IOException e) {
            throw new BizException(409, "保存封面失败：" + e.getMessage());
        }
        String coverUrl = WorldPaths.COVER_REL_PREFIX + fileName;
        indexDao.setCover(worldId, coverUrl, LocalDateTime.now());
        log.info("[world-store] 封面已更新 id={} uid={} file={} ({}×{})", worldId, summary.worldUid(),
                coverUrl, image.getWidth(), image.getHeight());
        return registry.requireById(worldId);
    }

    /**
     * 移除世界封面（删文件 + 清空索引 {@code cover_url}）。
     *
     * <p>必须两个动作都做：只删文件会让卡片继续请求 404 的封面 URL；只清索引会让孤儿文件
     * 永远占着世界目录体积（并且导出包白带一张没人用的图）。</p>
     *
     * @param worldId 目录库主键
     *
     * @return 更新后的存档卡片（{@code coverUrl} = null，前端回到预备背面）
     *
     * @throws BizException 404 世界不存在 / 409 删文件失败
     */
    public WorldSummaryVO removeCover(long worldId) {
        WorldSummaryVO summary = registry.requireById(worldId);
        Path dir = paths.worldUploadsDir(summary.worldUid());
        try {
            Files.deleteIfExists(dir.resolve(COVER_PNG));
            Files.deleteIfExists(dir.resolve(COVER_JPG));
        } catch (IOException e) {
            throw new BizException(409, "删除封面失败：" + e.getMessage());
        }
        indexDao.setCover(worldId, null, LocalDateTime.now());
        log.info("[world-store] 封面已移除 id={} uid={}", worldId, summary.worldUid());
        return registry.requireById(worldId);
    }

    /**
     * 定位某世界当前的封面文件（PNG 优先，其次 JPG）。
     *
     * <p>不信任索引里的 {@code cover_url} 去拼路径：那一列可能是导入包里的旧值，
     * 而文件才是事实。这里按固定文件名探测，索引只用于前端"有没有封面"的快速判断。</p>
     *
     * @param worldUid 世界标识
     *
     * @return 封面文件；不存在返回 null
     */
    public Path coverFile(String worldUid) {
        return paths.coverFile(worldUid);
    }

    /** 解码图片字节；不是图片（或解码失败）返回 null */
    private static BufferedImage decodeImage(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** 是否 PNG（按魔数判断，不信客户端 Content-Type） */
    private static boolean isPng(byte[] bytes) {
        return bytes.length > 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
    }

    /** 等比缩小到最长边 {@link #COVER_MAX_EDGE}；本就在范围内则原样返回 */
    private static BufferedImage scaleDown(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        int max = Math.max(width, height);
        if (max <= COVER_MAX_EDGE) {
            return src;
        }
        double ratio = (double) COVER_MAX_EDGE / max;
        int newWidth = Math.max(1, (int) Math.round(width * ratio));
        int newHeight = Math.max(1, (int) Math.round(height * ratio));
        BufferedImage out = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, newWidth, newHeight, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 转 RGB（JPEG 不支持透明通道；直接写 ARGB 会抛 "Bogus input colorspace"） */
    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, src.getWidth(), src.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 归档路径展示：优先相对数据目录（可读），跨盘时退回绝对路径 */
    private String displayPath(Path target) {
        try {
            return paths.dataDir().relativize(target).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return target.toAbsolutePath().toString().replace('\\', '/');
        }
    }

    /** 移动目录树：先试原子移动（同盘秒完成），跨盘时退回"复制 + 删源" */
    private void moveTree(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException | RuntimeException e) {
            log.debug("[world-store] 原子移动失败（尝试复制+删除）：{} → {}：{}", from, to, e.toString());
        }
        copyTree(from, to);
        deleteTreeQuietly(from);
    }

    /** 递归复制目录树（保留目录结构，不保留权限/时间戳——存档目录不需要） */
    private void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
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

    /** 静默删目录树（回滚用：删不掉只记日志，绝不把失败升级成第二个异常） */
    private void deleteTreeQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[world-store] 清理目录失败（需人工清理）：{}：{}", dir, e.toString());
        }
    }
}
