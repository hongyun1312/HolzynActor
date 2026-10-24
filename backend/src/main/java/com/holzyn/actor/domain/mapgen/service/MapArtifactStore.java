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

package com.holzyn.actor.domain.mapgen.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.mapgen.algo.MapCompositePainter;
import com.holzyn.actor.domain.mapgen.algo.TerrainPainter;
import com.holzyn.actor.domain.mapgen.config.MapGenProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 地图产物落盘（地形贴图 / 合成自检图）。
 *
 * <p>为什么贴图不存数据库：1024² 的 PNG 约 1~2 MB，存进 H2 的 TEXT/BLOB 会让每次备份和
 * 每次 SELECT 都变慢；它本质是<b>可重建的派生产物</b>（同种子同参数必得同图），所以只存
 * 相对路径 + 版本号，文件放在数据目录下。丢了就重跑 S1，不影响任何业务数据。</p>
 *
 * <p>目录结构：{@code <outputDir>/<mapId>/terrain.png}、{@code composite.png}、
 * {@code height.png}（调试用）。{@code terrainVersion} 自增即破缓存——前端用
 * {@code ?v=<version>} 拿到的一定是新图。</p>
 */
@Slf4j
@Service
public class MapArtifactStore {

    /** 产物根目录下的图集子目录名（{@code mapgen.output-dir} 未配置时，根即 {@code <data-dir>/worldmap}） */
    private static final String PREFIX = "worldmap";

    /** 地形贴图文件名 */
    private static final String TERRAIN = "terrain.png";

    /** 合成自检图文件名（4096² 主图：4 像素 1 米，近景能看到屋脊、田埂与院墙） */
    private static final String COMPOSITE = "composite.png";

    /** 夜景合成图文件名 */
    private static final String COMPOSITE_NIGHT = "composite-night.png";

    /** 中景 LOD（2048²）：整岛视野用它，加载快、内存小 */
    private static final String COMPOSITE_2048 = "composite-2048.png";

    /** 远景 LOD（1024²）：缩略图/小地图 */
    private static final String COMPOSITE_1024 = "composite-1024.png";

    private static final String NIGHT_2048 = "composite-night-2048.png";

    private static final String NIGHT_1024 = "composite-night-1024.png";

    /** 高度图文件名 */
    private static final String HEIGHT = "height.png";

    private final Path root;

    /** 主图分辨率倍率（相对 1 米 1 格的地形栅格；默认 4 像素 1 米，可配置）。 */
    private final double compositeScale;

    /**
     * 构造。
     *
     * @param dataDir 本地数据目录（{@code holzyn.actor.data-dir}）
     * @param props   管线配置（{@code mapgen.output-dir} 可覆盖根目录）
     */
    public MapArtifactStore(@Value("${holzyn.actor.data-dir:./data}") String dataDir,
                            MapGenProperties props) {
        String dir = props.getOutputDir();
        this.root = (dir == null || dir.isBlank() ? Paths.get(dataDir, PREFIX) : Paths.get(dir))
                .toAbsolutePath();
        this.compositeScale = Math.max(1.0, Math.min(8.0, props.getCompositeScale()));
        log.info("[mapgen] 地图产物目录：{}（写实底图倍率 {} px/m）", root, compositeScale);
    }

    /**
     * 写地形贴图。
     *
     * @param mapId 地图 ID
     * @param img   地形图
     * @return 相对路径（存 {@code actor_world_map.terrain_path}）
     * @throws IOException 写失败
     */
    public String writeTerrain(Long mapId, BufferedImage img) throws IOException {
        Path dir = dirOf(mapId);
        write(img, dir.resolve(TERRAIN));
        // 返回的是**相对 root 的路径**（root 本身已经是产物根），读回时由 safeResolve 按同一根解析。
        // 早期版本这里多带了一层 PREFIX，导致库里存 worldmap/1/terrain.png、按 root 解析成
        // worldmap/worldmap/1/terrain.png —— 文件明明在、GET /terrain.png 却恒 404。
        return mapId + "/" + TERRAIN;
    }

    /**
     * 写高度图（调试用）。
     *
     * <p>说明：旧的 {@code writeComposite}/{@code writeNight} 已删除——M6 起底图由
     * {@link #renderAndStoreComposite} 一次画出「4096² 主图 + 三档 LOD + 夜版」，
     * 再单独写一张固定尺寸的 composite 只会留下两份互相矛盾的产物。</p>
     *
     * @param mapId 地图 ID
     * @param img   高度图
     * @throws IOException 写失败
     */
    public void writeHeight(Long mapId, BufferedImage img) throws IOException {
        write(img, dirOf(mapId).resolve(HEIGHT));
    }

    /**
     * 读地形贴图原始字节（直接给 HTTP 响应，不经过 ImageIO 解码再编码）。
     *
     * @param terrainPath 相对路径（{@code actor_world_map.terrain_path}）
     * @return PNG 字节；文件不存在返回 null
     */
    public byte[] readTerrain(String terrainPath) {
        if (terrainPath == null || terrainPath.isBlank()) {
            return null;
        }
        Path file = safeResolve(terrainPath);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            log.warn("[mapgen] 读地形贴图失败：{}（{}）", terrainPath, e.getMessage());
            return null;
        }
    }

    /**
     * 读 PNG 字节为图片（合成阶段复用已存的地形图时用）。
     *
     * @param terrainPath 相对路径
     * @return 图片；不存在返回 null
     */
    public BufferedImage readTerrainImage(String terrainPath) {
        byte[] bytes = readTerrain(terrainPath);
        if (bytes == null) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 读地形图的 PNG 字节（内存版，给「不想落盘、直接返回」的场景用）。
     *
     * @param img 图片
     * @return PNG 字节
     * @throws IOException 编码失败
     */
    public byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ==================== 内部 ====================

    /** 该图的产物目录（不存在则创建）。 */
    private Path dirOf(Long mapId) throws IOException {
        Path dir = root.resolve(String.valueOf(mapId));
        Files.createDirectories(dir);
        return dir;
    }

    /** 写一张 PNG（原子替换：先写临时文件再 move，避免前端读到半张图）。 */
    private void write(BufferedImage img, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (var out = Files.newOutputStream(tmp)) {
            if (!ImageIO.write(img, "png", out)) {
                throw new IOException("PNG 编码失败（无可用 writer）");
            }
        }
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 相对路径 → 绝对路径（<b>必须在根目录内</b>，防路径穿越）。
     *
     * <p>为什么这里要做一次校验：{@code terrain_path} 是数据库里的字符串，虽然只有本服务写入，
     * 但「读文件接口接受库里的路径」天然是个穿越面——多写四行 normalize + startsWith，
     * 比日后被一个 {@code ../../} 打穿强。</p>
     */
    private Path safeResolve(String relative) {
        try {
            Path p = root.resolve(stripLegacyPrefix(relative)).normalize();
            if (!p.startsWith(root)) {
                log.warn("[mapgen] 非法贴图路径被拒绝：{}", relative);
                return null;
            }
            return p;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 剥离历史数据里多出来的 {@code worldmap/} 前缀。
     *
     * <p>为什么读取侧要容忍而不是"只改写入侧"：已经生成的图（用户机器上的
     * {@code actor_world_map.terrain_path}）是按旧格式写下的，只改写入侧会让所有存量地图
     * 永远拿不到底图——而重跑 S1 是分钟级操作、还会连带失效下游。这里剥掉前缀就能直接读回，
     * 不需要用户重掷地图。</p>
     *
     * @param relative 库里的相对路径
     * @return 相对 root 的路径
     */
    private String stripLegacyPrefix(String relative) {
        String normalized = relative.replace('\\', '/');
        String head = PREFIX + "/";
        return normalized.startsWith(head) ? normalized.substring(head.length()) : normalized;
    }

    /**
     * 删除某图全部产物（重新生成时清理旧图，避免目录无限增长）。
     *
     * @param mapId 地图 ID
     */
    public void delete(Long mapId) {
        Path dir = root.resolve(String.valueOf(mapId));
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("[mapgen] 清理产物失败：{}", p);
                }
            });
        } catch (IOException e) {
            log.warn("[mapgen] 清理产物目录失败：{}（{}）", dir, e.getMessage());
        }
    }

    /**
     * 渲染一张地形图并落盘（S1 的收尾动作，抽出方法便于单阶段重跑复用）。
     *
     * @param mapId 地图 ID
     * @param t     地形
     * @return 相对路径
     * @throws IOException 写失败
     */
    public String renderAndStoreTerrain(Long mapId,
                                        com.holzyn.actor.domain.mapgen.algo.TerrainData t)
            throws IOException {
        BufferedImage img = TerrainPainter.render(t);
        if (img == null) {
            throw new BizException(500, "地形渲染失败");
        }
        return writeTerrain(mapId, img);
    }

    /**
     * 读任意产物 PNG（写实底图与其 LOD 都走这里）。
     *
     * @param relative 相对路径（如 {@code 18/composite-2048.png}）
     * @return PNG 字节；不存在返回 null
     */
    public byte[] readPng(String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path file = safeResolve(relative);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            log.warn("[mapgen] 读产物失败：{}（{}）", relative, e.getMessage());
            return null;
        }
    }

    /**
     * 写实底图的产物路径（白天 + 夜景）。
     *
     * @param day   白天主图相对路径
     * @param night 夜景主图相对路径
     */
    public record CompositePaths(String day, String night) {
    }

    /**
     * 渲染写实合成底图并落盘（S5 的收尾动作）。
     *
     * <p><b>为什么是 4096²（4 像素 1 米）</b>：2048²（2 px/m）在整岛视野够用，
     * 但一放大到「一个院子」的尺度，屋脊、院墙厚度、菜畦的垄就只剩一两个像素，
     * 整张图立刻糊成色块。4 px/m 时一栋 9 米的民居有 36 像素宽，细节才立得住。
     * 同时导出 2048/1024 两档：远景加载 1024 就够，前端按缩放切档。</p>
     *
     * <p>顺序：渲染（含阴影/道路/建筑/田/广场）→ 地面细粒噪声（打散放大痕迹）
     * → 环境分级（季节/天气/潮汐/湿气）→ 落盘主图 → 降采样出两档 LOD
     * → 用同一张白天图生成夜景（不重画，省一半时间）→ 落盘夜景三档。</p>
     *
     * @param mapId    地图 ID
     * @param t        地形
     * @param roads    道路
     * @param features 要素
     * @param ambient  环境快照（可为 null=不做环境分级）
     * @return 产物相对路径
     * @throws IOException 写失败
     */
    public CompositePaths renderAndStoreComposite(
            Long mapId, com.holzyn.actor.domain.mapgen.algo.TerrainData t,
            java.util.List<com.holzyn.actor.domain.mapgen.algo.MapPlacer.RoadSpec> roads,
            java.util.List<com.holzyn.actor.domain.mapgen.algo.MapPlacer.PlacedFeature> features,
            com.holzyn.actor.domain.mapgen.model.MapAmbient ambient) throws IOException {
        BufferedImage day = MapCompositePainter.render(t, roads, features, 0, compositeScale);
        if (day == null) {
            throw new BizException(500, "写实底图渲染失败");
        }
        MapCompositePainter.groundDetail(day, t);
        if (ambient != null) {
            MapCompositePainter.applyAmbient(t, day, ambient.season(), ambient.weather(),
                    ambient.tide(), ambient.wetness());
        }
        Path dir = dirOf(mapId);
        write(day, dir.resolve(COMPOSITE));
        writeLod(day, dir.resolve(COMPOSITE_2048), 2048);
        writeLod(day, dir.resolve(COMPOSITE_1024), 1024);
        // 夜景：同一张白天图重新打光。画失败不能连累白天那张，所以单独 try。
        try {
            BufferedImage night = MapCompositePainter.renderNightFrom(t, roads, features, day);
            if (night != null) {
                write(night, dir.resolve(COMPOSITE_NIGHT));
                writeLod(night, dir.resolve(NIGHT_2048), 2048);
                writeLod(night, dir.resolve(NIGHT_1024), 1024);
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            log.warn("[mapgen] 夜景图渲染失败（不影响地图数据）：{}", e.getMessage());
        }
        return new CompositePaths(mapId + "/" + COMPOSITE, mapId + "/" + COMPOSITE_NIGHT);
    }

    /** 降采样写一档 LOD（双线性 + 高质量缩放：最近邻会把树冠与屋脊变成锯齿）。 */
    private void writeLod(BufferedImage src, Path target, int size) throws IOException {
        int w = Math.min(size, src.getWidth());
        BufferedImage out = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, w, null);
        g.dispose();
        write(out, target);
    }

    /**
     * 取某档 LOD 的相对路径。
     *
     * @param mapId 地图 ID
     * @param night 是否夜景
     * @param size  档位（1024 / 2048 / 4096）
     * @return 相对路径
     */
    public static String lodPath(Long mapId, boolean night, int size) {
        if (size >= 4096) {
            return mapId + "/" + (night ? COMPOSITE_NIGHT : COMPOSITE);
        }
        if (size >= 2048) {
            return mapId + "/" + (night ? NIGHT_2048 : COMPOSITE_2048);
        }
        return mapId + "/" + (night ? NIGHT_1024 : COMPOSITE_1024);
    }
}
