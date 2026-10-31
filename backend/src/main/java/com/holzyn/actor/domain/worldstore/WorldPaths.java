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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 世界存档路径的唯一权威（V1.1.2-beta.1）。
 *
 * <p>为什么必须"唯一"：分档后落盘位置从 1 处变 4 处（目录库 / 世界库 / 世界资源 / 归档），
 * 任何一处用自己拼的字符串就会造出"第二个 worlds 目录"——历史上世界地图资源就出过
 * 两处路径各拼一份、结果图生成到 A 目录而前端读 B 目录的事故。全仓库只允许经本类取路径。</p>
 *
 * <p>布局（V1.1.2-beta.1 M3 定稿）：</p>
 * <pre>
 * &lt;data-dir&gt;/
 *   holzyn-registry.mv.db        目录库：16 张索引/全局配置表，不含世界内容
 *   holzyn-actor.mv.db           旧单库：仅迁移期存在（迁移后不再被打开，保留作只读兜底）
 *   worlds/
 *     &lt;uid&gt;/
 *       manifest.json            世界自描述（名称/封面/时钟快照/统计/格式版本）
 *       world.mv.db              世界库：113 张表全量（含 12 张全局只读镜像）
 *       vector-store/            该世界的向量库（embeddings.json / memory-vectors.json）
 *       worldmap/                该世界的地图图块（composite / terrain / bundle 三类产物）
 *       worldgen/               该世界的 worldgen 产物（&lt;taskId&gt;/…）
 *       uploads/                 该世界的文件型上传（场景资产包 / cover.png 封面等）
 *   archive/
 *     pre-worldsplit-&lt;ts&gt;/      迁移前的单体库 + 旧资源目录整体归档（只增不删）
 *     deleted-worlds/&lt;uid&gt;-&lt;ts&gt;/  被归档删除的世界目录
 *   vector-store/  worldmap/  worldgen/   旧资源目录：迁移期仍在原处（读取方尚未改造）
 * </pre>
 *
 * <p>可移植性约定：{@code worlds/<uid>/} 必须自带全部信息，因此 manifest 里**不允许写绝对路径**；
 * 拷贝整个目录到另一台机器后由启动扫描重新收编（见设计文档 §3.2）。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Component
public class WorldPaths {

    /** H2 文件库连接参数（与 application.yml 的 spring.datasource.url 保持同一套方言设置） */
    public static final String H2_PARAMS =
            ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000";

    /** 世界库文件名（不含 .mv.db 后缀） */
    private static final String WORLD_DB_NAME = "world";

    /** 旧单库文件名（不含 .mv.db 后缀；仅迁移期存在） */
    private static final String LEGACY_DB_NAME = "holzyn-actor";

    /** 世界自描述文件名 */
    private static final String MANIFEST_NAME = "manifest.json";

    /** 目录库文件名后缀 */
    private static final String H2_FILE_SUFFIX = ".mv.db";

    /** H2 锁文件后缀（追加在完整库文件名之后） */
    private static final String LOCK_SUFFIX = ".lock.db";

    /**
     * 世界目录内的资源子目录名（顺序即 manifest.resources 的写法）。
     *
     * <p>与 {@code WorldManifest.fresh(...)} 的默认值必须一致：不一致的话，
     * 世界目录里放了资源而 manifest 没登记，导入/导出就会漏掉它们。</p>
     */
    public static final List<String> RESOURCE_DIR_NAMES =
            List.of("vector-store", "worldmap", "worldgen", "uploads");

    /** 世界封面文件名（PNG）：放在 {@code uploads/} 下，导出 zip 会整目录带走 */
    public static final String COVER_PNG = "cover.png";

    /** 世界封面文件名（JPG） */
    public static final String COVER_JPG = "cover.jpg";

    /** 封面在索引 {@code cover_url} 里的相对路径前缀（世界目录内） */
    public static final String COVER_REL_PREFIX = "uploads/";

    private final Path dataDir;
    private final Path worldsRoot;
    private final Path archiveRoot;
    private final String registryName;

    public WorldPaths(WorldStoreProperties properties,
                      @Value("${holzyn.actor.data-dir:./data}") String dataDir) {
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
        String configuredRoot = properties.getRootDir();
        this.worldsRoot = (configuredRoot == null || configuredRoot.isBlank())
                ? this.dataDir.resolve("worlds")
                : Path.of(configuredRoot).toAbsolutePath().normalize();
        String configuredArchive = properties.getArchiveDir();
        this.archiveRoot = (configuredArchive == null || configuredArchive.isBlank())
                ? this.dataDir.resolve("archive")
                : Path.of(configuredArchive).toAbsolutePath().normalize();
        String name = properties.getRegistryFile();
        this.registryName = (name == null || name.isBlank()) ? "holzyn-registry" : name.trim();
    }

    /** 数据根目录（H2 目录库与 worlds/ 的父目录） */
    public Path dataDir() {
        return dataDir;
    }

    /** 世界根目录（默认 {@code <data-dir>/worlds}） */
    public Path worldsRoot() {
        return worldsRoot;
    }

    /** 归档根目录（迁移前单体库等，只增不删） */
    public Path archiveRoot() {
        return archiveRoot;
    }

    /**
     * 被"归档删除"的世界目录根（{@code <archive-root>/deleted-worlds}）。
     *
     * <p>为什么删世界是"移走"而不是"删掉"：世界目录里是用户唯一的存档（H2 库 + 资源 + 清单），
     * 误删一次就是永久数据丢失；移进归档目录后仍可人工救回，而列表页只需把索引行标
     * {@code deleted=1} 就看不到了。归档目录与迁移归档同源（{@link #archiveRoot()}），
     * 便于"所有历史产物都在一处"。</p>
     *
     * @return 归档删除根目录
     */
    public Path deletedWorldsDir() {
        return archiveRoot.resolve("deleted-worlds");
    }

    /**
     * 由"库文件路径"给出 H2 连接串（导出/导入时打开临时副本用）。
     *
     * <p>为什么要有它：`WorldPaths` 是路径派生唯一权威（AGENTS §8.1.7）。导出要把世界库复制到临时目录再清洗
     * `api_key_cipher`、导入要校验/改写临时目录里的库——这两处的库都不在 `worlds/&lt;uid&gt;/` 下，
     * 不能用 {@link #worldDbUrl(String)}。仍然只允许在这一个地方把路径翻译成连接串，
     * 避免各处自己拼 `jdbc:h2:...` 而漏掉 {@link #H2_PARAMS}（漏了会报 `Schema "public" not found`）。</p>
     *
     * @param dbFile 库文件（{@code xxx.mv.db}）
     *
     * @return H2 连接串
     */
    public static String dbUrl(Path dbFile) {
        Path base = dbFile.toAbsolutePath().normalize();
        String s = base.toString();
        if (s.endsWith(H2_FILE_SUFFIX)) {
            base = Path.of(s.substring(0, s.length() - H2_FILE_SUFFIX.length()));
        }
        return fileUrl(base);
    }

    /** 目录库实际文件（{@code <data-dir>/<registry-file>.mv.db}） */
    public Path registryDbFile() {
        return dataDir.resolve(registryName + H2_FILE_SUFFIX);
    }

    /** 目录库 H2 连接串 */
    public String registryDbUrl() {
        return fileUrl(dataDir.resolve(registryName));
    }

    /** 某世界的目录（{@code worlds/<uid>}） */
    public Path worldDir(String uid) {
        requireSafeUid(uid);
        return worldsRoot.resolve(uid);
    }

    /** 某世界的自描述文件（{@code worlds/<uid>/manifest.json}） */
    public Path manifestFile(String uid) {
        return worldDir(uid).resolve(MANIFEST_NAME);
    }

    /** 某世界的向量库目录（{@code worlds/<uid>/vector-store}） */
    public Path worldVectorStoreDir(String uid) {
        return worldDir(uid).resolve(RESOURCE_DIR_NAMES.get(0));
    }

    /** 某世界的地图图块目录（{@code worlds/<uid>/worldmap}） */
    public Path worldWorldmapDir(String uid) {
        return worldDir(uid).resolve(RESOURCE_DIR_NAMES.get(1));
    }

    /** 某世界的 worldgen 产物目录（{@code worlds/<uid>/worldgen}） */
    public Path worldWorldgenDir(String uid) {
        return worldDir(uid).resolve(RESOURCE_DIR_NAMES.get(2));
    }

    /** 某世界的上传目录（{@code worlds/<uid>/uploads}） */
    public Path worldUploadsDir(String uid) {
        return worldDir(uid).resolve(RESOURCE_DIR_NAMES.get(3));
    }

    /**
     * 定位某世界的封面文件（PNG 优先，其次 JPG）。
     *
     * <p>为什么在 {@code uploads/} 而不是世界目录根：导出/导入（{@code .holzyn} v3）只打包
     * {@link #RESOURCE_DIR_NAMES} 这几个资源目录 + manifest + 世界库，根目录文件会被静默漏掉
     * ——封面丢了不会报错，只会在换台机器后变成预备背面。放 uploads 里就自动随包走。</p>
     *
     * @param uid 世界标识
     *
     * @return 封面文件；没有封面返回 null
     */
    public Path coverFile(String uid) {
        Path dir = worldUploadsDir(uid);
        for (String name : List.of(COVER_PNG, COVER_JPG)) {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    /** 某世界的库文件（{@code worlds/<uid>/world.mv.db}） */
    public Path worldDbFile(String uid) {
        return worldDir(uid).resolve(WORLD_DB_NAME + H2_FILE_SUFFIX);
    }

    /** 某世界库的 H2 连接串 */
    public String worldDbUrl(String uid) {
        requireSafeUid(uid);
        return fileUrl(worldDir(uid).resolve(WORLD_DB_NAME));
    }

    /**
     * 某世界库的 H2 锁文件（{@code world.mv.db.lock.db}）。
     *
     * <p>正常 {@code close} 后必须消失。残留说明上次进程被强杀（桌面端 8 秒超时会 taskkill），
     * 这正是启动自检要报告的痕迹——H2 会靠自身超时回收，但用户需要知道"这个存档上次没干净退出"。</p>
     *
     * @param uid 世界标识
     *
     * @return 锁文件路径（不保证存在）
     */
    public Path worldLockFile(String uid) {
        return Path.of(worldDbFile(uid).toString() + LOCK_SUFFIX);
    }

    /**
     * 统计某世界目录的总体积（含库文件与资源）。
     *
     * <p>只在列表里用来显示"多大"，不参与任何判断；读失败按 0 处理（体积显示错误不该阻止列表渲染）。</p>
     *
     * @param uid 世界标识
     *
     * @return 字节数（目录不存在返回 0）
     */
    public long worldSizeBytes(String uid) {
        Path dir = worldDir(uid);
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        final long[] total = {0L};
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                try {
                    total[0] += Files.size(f);
                } catch (IOException ignored) {
                    // 单文件读不到（正被占用/已删除）不影响总量估算
                }
            });
        } catch (IOException e) {
            log.debug("统计世界目录体积失败：{}（按 0 处理）", dir, e);
            return 0L;
        }
        return total[0];
    }

    /**
     * 归档目录（{@code <data-dir>/archive/<tag>}）。
     *
     * @param tag 归档标签（如 {@code pre-worldsplit-20261024-2210}），会做安全校验
     *
     * @return 归档目录路径（不保证已存在）
     */
    public Path archiveDir(String tag) {
        requireSafeUid(tag);
        return archiveRoot.resolve(tag);
    }

    /**
     * 旧单库文件（{@code <data-dir>/holzyn-actor.mv.db}）。
     *
     * <p>只在一次性迁移里被读一次；迁移之后应用任何代码路径都不再打开它
     * （这正是"目录库换文件名"的第二个好处：旧库不可能被误开成目录库）。</p>
     *
     * @return 旧库文件路径（不保证存在）
     */
    public Path legacyDbFile() {
        return dataDir.resolve(LEGACY_DB_NAME + H2_FILE_SUFFIX);
    }

    /**
     * 旧单库的 H2 连接串（迁移读源）。
     *
     * <p>刻意<b>不加</b>只读参数：H2 的 {@code ACCESS_MODE_DATA=r} 在库文件不存在时会直接报错，
     * 而迁移器需要先判断"文件是否存在"。读源是否可写由调用方保证（迁移器只发 select）。</p>
     *
     * @return 旧库连接串
     */
    public String legacyDbUrl() {
        return fileUrl(dataDir.resolve(LEGACY_DB_NAME));
    }

    /** 旧向量库根目录（{@code <data-dir>/vector-store}，内含 {@code <projectId>/}） */
    public Path legacyVectorStoreRoot() {
        return dataDir.resolve(RESOURCE_DIR_NAMES.get(0));
    }

    /** 旧地图图块根目录（{@code <data-dir>/worldmap}，内含 {@code <projectId>/}） */
    public Path legacyWorldmapRoot() {
        return dataDir.resolve(RESOURCE_DIR_NAMES.get(1));
    }

    /** 旧 worldgen 根目录（{@code <data-dir>/worldgen}，内含 {@code <taskId>/}） */
    public Path legacyWorldgenRoot() {
        return dataDir.resolve(RESOURCE_DIR_NAMES.get(2));
    }

    /** 旧上传根目录（{@code <data-dir>/uploads}，可能不存在） */
    public Path legacyUploadsRoot() {
        return dataDir.resolve(RESOURCE_DIR_NAMES.get(3));
    }

    /** 列出全部"看起来是世界目录"的路径（含 manifest.json 的子目录），按名字排序 */
    public List<Path> listWorldDirs() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(worldsRoot)) {
            return out;
        }
        try (Stream<Path> children = Files.list(worldsRoot)) {
            children.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(MANIFEST_NAME)))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(out::add);
        } catch (IOException e) {
            // 扫描失败不让启动炸：上层按"没有世界"处理，并在日志里留下证据
            log.warn("扫描世界目录失败：{}（按无世界处理）", worldsRoot, e);
        }
        return out;
    }

    /**
     * 校验世界标识可用作目录名。
     *
     * <p>uid 会参与路径拼接，且来源包含 URL 参数；不校验就是路径穿越（{@code ../../}）。
     * 只允许字母/数字/连字符/下划线，长度 1~64。</p>
     *
     * @param uid 世界标识
     *
     * @return 原值（便于链式使用）
     *
     * @throws IllegalArgumentException uid 为空或含非法字符
     */
    public static String requireSafeUid(String uid) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("世界标识为空");
        }
        String v = uid.trim();
        if (v.length() > 64) {
            throw new IllegalArgumentException("世界标识过长（>64）：" + v);
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!ok) {
                throw new IllegalArgumentException("世界标识含非法字符 '" + c + "'：" + v);
            }
        }
        if (v.equals(".") || v.equals("..")) {
            throw new IllegalArgumentException("世界标识非法：" + v);
        }
        return v;
    }

    /** 由 H2 文件基名（不含 .mv.db）生成连接串；Windows 反斜杠统一成正斜杠 */
    private static String fileUrl(Path base) {
        String p = base.toAbsolutePath().normalize().toString().replace('\\', '/');
        return "jdbc:h2:file:" + p + H2_PARAMS;
    }
}
