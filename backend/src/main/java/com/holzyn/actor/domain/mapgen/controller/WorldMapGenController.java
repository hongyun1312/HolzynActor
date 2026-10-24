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

package com.holzyn.actor.domain.mapgen.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.mapgen.config.MapGenProperties;
import com.holzyn.actor.domain.mapgen.entity.ActorMapAssetType;
import com.holzyn.actor.domain.mapgen.repository.ActorMapAssetTypeRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository;
import com.holzyn.actor.domain.mapgen.service.MapArtifactStore;
import com.holzyn.actor.domain.mapgen.service.MapBundleQueryService;
import com.holzyn.actor.domain.mapgen.service.MapGenOrchestrator;
import com.holzyn.actor.domain.mapgen.service.MapGenProgressHub;
import com.holzyn.actor.domain.mapgen.vo.MapBundleVO;
import com.holzyn.actor.domain.mapgen.vo.WorldMapOverviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界地图生成管线 V1.1 的 REST / SSE 入口（{@code /api/projects/{projectId}/map/**}）。
 *
 * <p><b>为什么路径是 {@code /map} 而不是 {@code /world-map}</b>：旧管线（{@code domain/worldmap}）
 * 已经占用了 {@code /world-map} 上的 {@code POST /generate} 与 {@code GET /}，两套同时在跑、
 * 互相冲突（Spring 启动期直接 {@code Ambiguous mapping} 起不来）。在旧渲染器被替换掉之前，
 * 两套接口必须能并存：旧接口继续服务现网页面，新接口用独立命名空间。
 * 等前端切到 {@code bundle} 之后，删掉旧的 {@code WorldMapController}，这条路径就是唯一的。</p>
 *
 * <p><b>接口分工（为什么是这几个而不是更多）</b>：</p>
 * <ul>
 *   <li>{@code POST /generate}：只建行 + 起异步任务，立刻返回 mapId（生成要几分钟）；</li>
 *   <li>{@code GET  /}（概览）：首页/侧栏用的小对象，轮询也廉价；</li>
 *   <li>{@code GET  /bundle}：画图用的全量几何（道路/要素/院落/调色板），一次拿完；</li>
 *   <li>{@code GET  /terrain.png}：地形底图，带 {@code ETag}/{@code max-age}，由
 *       {@code terrainVersion} 破缓存；</li>
 *   <li>{@code GET  /stream}：SSE 进度（stage/progress/log/result/error），按项目分频道，
 *       页面刷新/重跑都不用重新订阅；</li>
 *   <li>{@code POST /stages/{stage}/rerun}：单阶段重跑（改一个参数不必重花 3 次 AI 调用）；</li>
 *   <li>{@code GET  /asset-policy}：资产类型清单（前端「这一带有哪些房子」面板）。</li>
 * </ul>
 *
 * <p>控制器本身<b>不含业务逻辑</b>：只做参数校验、把服务结果包成 {@link R}，
 * 以及把图片字节包成带缓存头的响应。所有 AI/算法/落库都在 service 层。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/projects/{projectId}/map")
@RequiredArgsConstructor
public class WorldMapGenController {

    /**
     * 地形贴图 URL 模板（{@code %d} = 项目 ID，{@code %d} = 版本号）。
     * <p>放在控制器上是为了让「URL 长什么样」只有一个出处——概览 VO 里的 {@code terrainUrl}
     * 由 {@link MapBundleQueryService} 生成，直接引用这个常量，避免前端拿到的 URL 与实际
     * 映射不一致（这种错最典型的表现是「地图页一直是空白底图」）。</p>
     */
    public static final String TERRAIN_URL_TEMPLATE = "/api/projects/%d/map/terrain.png?v=%d";

    /**
     * 写实底图 URL 模板（{@code %d}=项目，{@code %d}=版本，{@code %d}=像素档 1024/2048/4096）。
     */
    public static final String COMPOSITE_URL_TEMPLATE =
            "/api/projects/%d/map/composite.png?v=%d&size=%d";

    /** 夜景写实底图 URL 模板。 */
    public static final String COMPOSITE_NIGHT_URL_TEMPLATE =
            "/api/projects/%d/map/composite-night.png?v=%d&size=%d";

    private final MapGenOrchestrator orchestrator;
    private final MapBundleQueryService query;
    private final MapArtifactStore artifacts;
    private final MapGenProgressHub hub;
    private final MapGenProperties props;
    private final ActorWorldMapRepository mapRepository;
    private final ActorMapAssetTypeRepository assetTypeRepository;

    /**
     * 生成请求（两个字段都可空：缺省用配置里的尺度与派生的种子）。
     *
     * @param seed 种子（给了就是「复现某张图」，不给就是「重掷」）
     * @param size 边长（格；缺省取 {@code mapgen.size}，仅当与配置不同才生效，便于实验）
     */
    public record GenerateRequest(Long seed, Integer size) {
    }

    /**
     * 启动生成（异步）。
     *
     * @param projectId 项目 ID
     * @param req       生成请求（可空）
     * @return {@code {mapId, status:"running"}}
     */
    @PostMapping("/generate")
    public R<Map<String, Object>> generate(@PathVariable Long projectId,
                                           @RequestBody(required = false) GenerateRequest req) {
        Long seed = req == null ? null : req.seed();
        Long mapId = orchestrator.generate(projectId, seed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mapId", mapId);
        out.put("status", "running");
        out.put("seed", seed);
        return R.ok(out);
    }

    /**
     * 地图概览（最新一张）。
     *
     * @param projectId 项目 ID
     * @return 概览；没有地图时 data=null（前端据此显示「生成地图」按钮）
     */
    @GetMapping
    public R<WorldMapOverviewVO> overview(@PathVariable Long projectId) {
        return R.ok(query.overview(projectId));
    }

    /**
     * 渲染包（地形之外的矢量数据 + 调色板）。
     *
     * @param projectId 项目 ID
     * @return 渲染包；没有地图时 data=null
     */
    @GetMapping("/bundle")
    public R<MapBundleVO> bundle(@PathVariable Long projectId) {
        return R.ok(query.bundle(projectId));
    }

    /**
     * 地形贴图（PNG）。
     * <p>缓存策略：带版本号的 URL 浏览器可长期缓存，但这里仍给 {@code max-age=0, must-revalidate}
     * + ETag——地图是「随时可能重掷」的数据，缓存太久会让用户看到旧岛。</p>
     *
     * @param projectId 项目 ID
     * @return PNG 字节；未生成时 404
     */
    @GetMapping(value = "/terrain.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> terrain(@PathVariable Long projectId) {
        var map = mapRepository.findFirstByProjectIdOrderByIdDesc(projectId)
                .orElseThrow(() -> new BizException(404, "项目还没有地图"));
        byte[] png = artifacts.readTerrain(map.getTerrainPath());
        if (png == null) {
            throw new BizException(404, "地形贴图尚未生成（stage=" + map.getStage() + "）");
        }
        int version = map.getTerrainVersion() == null ? 1 : map.getTerrainVersion();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noCache().mustRevalidate())
                .eTag("\"" + map.getId() + "-" + version + "\"")
                .body(png);
    }

    /**
     * 写实底图（PNG，4096² 主图 / 2048 / 1024 三档）。
     *
     * <p>为什么用 {@code size} 查询参数而不是三条路径：URL 模板只有一个出处，
     * 前端切档只改一个整数；缓存键里带 {@code v}，重掷地图后版本号自增即天然破缓存。</p>
     *
     * @param projectId 项目 ID
     * @param size      像素档（1024/2048/4096；其他值就近取整）
     * @return PNG 字节；未生成时 404
     */
    @GetMapping(value = "/composite.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> composite(@PathVariable Long projectId,
                                            @RequestParam(defaultValue = "4096") int size) {
        return pngResponse(projectId, size, false);
    }

    /**
     * 夜景写实底图（PNG）。
     *
     * @param projectId 项目 ID
     * @param size      像素档
     * @return PNG 字节
     */
    @GetMapping(value = "/composite-night.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> compositeNight(@PathVariable Long projectId,
                                                 @RequestParam(defaultValue = "4096") int size) {
        return pngResponse(projectId, size, true);
    }

    /** 底图响应（白天/夜景共用；找不到时给一个明确的 404，而不是空图）。 */
    private ResponseEntity<byte[]> pngResponse(Long projectId, int size, boolean night) {
        var map = mapRepository.findFirstByProjectIdOrderByIdDesc(projectId)
                .orElseThrow(() -> new BizException(404, "项目还没有地图"));
        int px = size >= 4096 ? 4096 : size >= 2048 ? 2048 : 1024;
        byte[] png = artifacts.readPng(MapArtifactStore.lodPath(map.getId(), night, px));
        if (png == null) {
            throw new BizException(404, "写实底图尚未生成（stage=" + map.getStage() + "）");
        }
        int version = map.getTerrainVersion() == null ? 1 : map.getTerrainVersion();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noCache().mustRevalidate())
                .eTag("\"" + map.getId() + "-" + version + "-" + px + (night ? "-n" : "") + "\"")
                .body(png);
    }

    /**
     * 进度流（SSE）。
     *
     * @param projectId 项目 ID
     * @return SSE 发射器
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long projectId) {
        return hub.subscribe(projectId);
    }

    /**
     * 单阶段重跑（自动失效下游数据）。
     *
     * @param projectId 项目 ID
     * @param stage     阶段码（S0..S5）
     * @return {@code {mapId, stage, status:"running"}}
     */
    @PostMapping("/stages/{stage}/rerun")
    public R<Map<String, Object>> rerun(@PathVariable Long projectId, @PathVariable String stage) {
        Long mapId = orchestrator.rerunStage(projectId, stage);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mapId", mapId);
        out.put("stage", stage.toUpperCase(java.util.Locale.ROOT));
        out.put("status", "running");
        return R.ok(out);
    }

    /**
     * 资产类型清单（S2 产物；前端「这一带有哪些房子」面板 + 禁忌核查）。
     *
     * @param projectId 项目 ID
     * @param category  大类过滤（可空）
     * @return 资产类型列表
     */
    /**
     * 世界→地图契约（S0b 产物，调试/参数面板用）。
     *
     * <p>为什么单独开一个接口：契约决定「这张图为什么只有 40 栋房、公共设施是哪几个」，
     * 出问题时必须能一眼看到 AI 当时给了什么，而不是去翻日志或拆 JSON。</p>
     *
     * @param projectId 项目 ID
     * @return {@code {brief: {...}}}（无地图/无契约时为空对象）
     */
    @GetMapping("/brief")
    public R<java.util.Map<String, Object>> brief(@PathVariable("projectId") Long projectId) {
        var ov = query.overview(projectId);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (ov == null) {
            return R.ok(out);
        }
        var row = mapRepository.findById(ov.getId()).orElse(null);
        if (row == null) {
            return R.ok(out);
        }
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            // V5 起契约有独立列；旧地图只有 params_json.brief，按新→旧顺序两路都读。
            if (row.getBriefJson() != null && !row.getBriefJson().isBlank()) {
                out.put("brief", mapper.convertValue(mapper.readTree(row.getBriefJson()),
                        java.util.Map.class));
                return R.ok(out);
            }
            if (row.getParamsJson() == null || row.getParamsJson().isBlank()) {
                return R.ok(out);
            }
            var root = mapper.readTree(row.getParamsJson());
            var brief = root.path("brief");
            if (!brief.isMissingNode()) {
                out.put("brief", mapper.convertValue(brief, java.util.Map.class));
            }
        } catch (Exception e) {
            throw new BizException(500, "读取地图契约失败：" + e.getMessage());
        }
        return R.ok(out);
    }

    @GetMapping("/asset-policy")
    public R<List<Map<String, Object>>> assetPolicy(@PathVariable Long projectId,
                                                    @RequestParam(required = false) String category) {
        var map = mapRepository.findFirstByProjectIdOrderByIdDesc(projectId)
                .orElseThrow(() -> new BizException(404, "项目还没有地图"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ActorMapAssetType t : assetTypeRepository.findByMapIdOrderBySortOrderAsc(map.getId())) {
            if (category != null && !category.isBlank() && !category.equals(t.getCategory())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", t.getCode());
            m.put("name", t.getName());
            m.put("category", t.getCategory());
            m.put("kind", t.getKind());
            m.put("baseW", t.getBaseW());
            m.put("baseD", t.getBaseD());
            m.put("floorsMin", t.getFloorsMin());
            m.put("floorsMax", t.getFloorsMax());
            m.put("wallColor", t.getWallColor());
            m.put("roofColor", t.getRoofColor());
            m.put("roofShape", t.getRoofShape());
            m.put("material", t.getMaterial());
            m.put("styleTags", t.getStyleTags());
            m.put("weight", t.getWeight());
            m.put("description", t.getDescription());
            out.add(m);
        }
        return R.ok(out);
    }

    /**
     * 管线配置（前端不需要硬编码尺度：{@code size}/{@code metersPerCell} 都从这里取）。
     *
     * @param projectId 项目 ID（路径保持统一，便于前端按项目拼 URL）
     * @return 配置摘要
     */
    @GetMapping("/params")
    public R<Map<String, Object>> params(@PathVariable Long projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.isEnabled());
        out.put("aiEnabled", props.isAiEnabled());
        out.put("pipelineVersion", props.getPipelineVersion());
        out.put("size", props.getSize());
        out.put("metersPerCell", props.getMetersPerCell());
        out.put("widthMeters", props.getSize() * props.getMetersPerCell());
        out.put("stageTotal", MapGenProgressHub.STAGE_TOTAL);
        return R.ok(out);
    }
}
