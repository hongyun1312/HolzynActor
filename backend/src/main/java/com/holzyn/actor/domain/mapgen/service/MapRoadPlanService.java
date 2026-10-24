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
import com.holzyn.actor.domain.mapgen.algo.MapNetwork;
import com.holzyn.actor.domain.mapgen.algo.MapPlacer;
import com.holzyn.actor.domain.mapgen.algo.MapSites;
import com.holzyn.actor.domain.mapgen.algo.Rng;
import com.holzyn.actor.domain.mapgen.algo.RoadGrade;
import com.holzyn.actor.domain.mapgen.algo.RoadRouter;
import com.holzyn.actor.domain.mapgen.algo.StreetNetwork;
import com.holzyn.actor.domain.mapgen.algo.TerrainData;
import com.holzyn.actor.domain.mapgen.config.MapGenProperties;
import com.holzyn.actor.domain.mapgen.model.MapAssetCatalog;
import com.holzyn.actor.domain.mapgen.model.MapProfile;
import com.holzyn.actor.domain.mapgen.model.RoadTopology;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * S3 路网：AI 给<b>拓扑</b>（谁连谁 + 路性格），本类把它落到地形上求<b>走线</b>。
 *
 * <p><b>分工的理由（用户决策 #6）</b>：AI 擅长「码头到正街要有一条能过板车的路，两边是铺面」
 * 这种聚落逻辑，不擅长也不稳定的是「这条路在第 517 格往左拐」。所以这里只向 AI 要
 * {@link RoadTopology}，坐标由 {@link MapSites} 用空间语义（coast/river/high-ground…）在地形上
 * 落点，走线由 {@link RoadRouter} 最小代价寻路——坡度、过河、绕陡坡都是代价，走出来的线自然贴地形。</p>
 *
 * <p><b>三个必须守住的硬约束</b>：</p>
 * <ol>
 *   <li><b>等级不串味</b>：官道/主街/村庄道路/田埂四级的宽度与材质由 {@link RoadGrade} 唯一决定，
 *       AI 给的 width 只是「倾向」，会被夹到该等级的合理区间（否则会出现 6 米宽的田埂）；</li>
 *   <li><b>端点去重</b>：AI 常把「镇中心」和「工坊巷」都标成 settlement-edge，落到同一个点。
 *       重合的端点按种子做 20~45 米的确定性微移，让两条路真的有两段长度（否则边会被当退化边丢掉）；</li>
 *   <li><b>连通性自检</b>：拓扑求完立刻用 {@link MapNetwork#groupCount} 数分量并记日志。
 *       这里不修（修复在 S4 落位的两遍整备里做，那时才有全部路），但必须<b>可见</b>——
 *       「两条路各走各的」在俯视图上极刺眼，却在千格图上很难肉眼发现。</li>
 * </ol>
 *
 * <p><b>事务纪律</b>：含秒级 AI 调用，<b>不得</b>带 {@code @Transactional}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapRoadPlanService {

    /** AI 场景名 */
    static final String SCENE = "world_map_roads";

    /** 端点归并半径（米）：两个节点落点相距小于它就算同一处 */
    private static final double DEDUPE_MIN = 20.0;

    /** 目的地路「同走向」判定：采样步长（米） */
    private static final double COVER_STEP = 4.0;

    /** 目的地路「同走向」判定：压在上面的距离（米） */
    private static final double COVER_TOL = 5.0;

    /** 目的地路「同走向」判定：覆盖率门槛（超过即视为同一条路） */
    private static final double COVER_DUP = 0.45;

    /**
     * 「目的地已经在街上」的门限（米）：目的地离街巷骨架不到这个距离，就不再单独修一条路。
     * <p>为什么不是 12 米：一条 20 米的支线在俯视图上只是一小截从街梢伸出去的残枝，
     * 而它服务的那个地方本来就已经被街够着了。真实的村镇也不会为街边二十米外的一间屋
     * 单修一条路——它就在街面上。</p>
     */
    private static final double SERVE_ON_STREET = 28.0;

    private final MapGenAiCaller ai;
    private final PromptTemplateService promptTemplateService;
    private final MapGenProperties props;

    /** 一次 S3 的产物：拓扑原文 + 已求线的路 + 端点表 */
    public record Plan(RoadTopology topology, List<MapPlacer.RoadSpec> roads,
                       Map<String, MapSites.Site> nodes, int components) {
    }

    /**
     * 求路网（S3）：<b>先由程序定村镇骨架，再把 AI 的目的地路接到骨架边上</b>。
     *
     * <p><b>为什么是这个顺序</b>：AI 的拓扑每条边都以镇中心为端点，直接求线就是「十几条路
     * 从一个点射出去」的蜘蛛形态（用户的原话：道路与村庄越开发越乱）。真实村镇的主体是街，
     * 街的形态由地形决定、与目的地无关，所以先让程序把正街/后街/横巷长出来（{@link StreetNetwork#frame}），
     * 再让码头、灯塔、林道、田区这些<b>目的地</b>各自接到骨架的边上成 T 字口。</p>
     *
     * @param projectId 项目 ID
     * @param terrain   地形
     * @param profile   S0 档案
     * @param catalog   S2 资产目录
     * @param sites     语义点表（含地标）
     * @param seed      种子
     * @param cost      代价栅格（无建筑阶段的成本）
     * @param townRadius 聚落半径（米，必须与 S4 落位同源，见 {@link MapPlacer#townRadiusMeters}）
     * @return 路网方案
     */
    public Plan build(Long projectId, TerrainData terrain, MapProfile profile,
                      MapAssetCatalog catalog, Map<String, MapSites.Site> sites,
                      long seed, float[] cost, double townRadius) {
        // ① 街巷骨架（程序定，AI 不参与）
        MapSites.Site core = sites.get(MapSites.CORE);
        List<MapPlacer.RoadSpec> frame = core == null
                ? new ArrayList<>()
                : StreetNetwork.frame(terrain, seed, core.x(), core.z(), townRadius);
        // 退化兜底：镇心周边「可建 + 缓坡」的窗太小（镇心贴着海岸/陡坡）时，正街会被判不合格，
        // 骨架返回空表；而目的地路又全在镇内（并入骨架）→ 得到一张 stage=S5、ready、零条路的图。
        // 实测在线 1 例（地形种子导致）。放宽一次聚落半径再长一次街道，避免「生成成功却什么都没有」。
        if (frame.isEmpty() && core != null) {
            double wider = Math.max(townRadius * 1.6, townRadius + 40);
            frame = StreetNetwork.frame(terrain, seed, core.x(), core.z(), wider);
            if (!frame.isEmpty()) {
                log.warn("[mapgen] S3 骨架首轮为空，按 {} 米半径重试后得到 {} 条",
                        Math.round(wider), frame.size());
            } else {
                log.warn("[mapgen] S3 骨架为空（镇心周边无可用可建地），本次地图不会有道路");
            }
        }
        // ② 目的地路（AI 定「去哪里」，程序定「怎么接」）
        RoadTopology topology = topology(projectId, profile, catalog);
        Map<String, MapSites.Site> nodes = resolveNodes(topology, sites, seed);
        List<MapPlacer.RoadSpec> roads = new ArrayList<>(frame);
        roads.addAll(route(terrain, cost, topology, nodes, frame));
        // 静默交付一张「ready 但零条路」的地图是最坏的结果：用户以为生成成功、图上却一片空白。
        // 走到这里说明镇心周边确实没有可建地（骨架为空，目的地边又全部落在镇内而被并入空骨架），
        // 这时只能明确失败、让用户重掷，而不是伪造一个没有街道的空世界。
        if (roads.isEmpty()) {
            throw new BizException(500, "地形种子 " + seed
                    + " 的镇心周边找不到可建地（街巷骨架与目的地路均为 0），无法生成街道，请重掷地图");
        }
        int components = MapNetwork.groupCount(roads);
        if (components > 1) {
            log.warn("[mapgen] S3 路网有 {} 个连通分量（由 S4 的两遍整备修复）：{} 条路", components, roads.size());
        }
        log.info("[mapgen] S3 路网：骨架 {} 条 + 目的地路 {} 条 → 共 {} 条（分量 {}）",
                frame.size(), roads.size() - frame.size(), roads.size(), components);
        return new Plan(topology, roads, nodes, components);
    }

    // ==================== 拓扑（AI 或兜底） ====================

    /**
     * 取拓扑：AI 优先，任何异常回落 {@link RoadTopology#fallback()}。
     */
    private RoadTopology topology(Long projectId, MapProfile profile, MapAssetCatalog catalog) {
        if (!props.isAiEnabled()) {
            log.info("[mapgen] S3 跳过 AI（mapgen.ai-enabled=false），使用程序化拓扑");
            return RoadTopology.fallback();
        }
        try {
            String content = ai.content(projectId, prompt(projectId, profile, catalog));
            RoadTopology parsed = parse(content);
            if (parsed.roads().isEmpty()) {
                log.warn("[mapgen] S3 AI 拓扑无有效边，回落程序化拓扑");
                return RoadTopology.fallback();
            }
            log.info("[mapgen] S3 拓扑：{} 节点 / {} 边，aiGenerated={}",
                    parsed.nodes().size(), parsed.roads().size(), parsed.aiGenerated());
            return parsed;
        } catch (Exception e) {
            log.warn("[mapgen] S3 拓扑生成失败，回落程序化拓扑：{}", e.getMessage());
            return RoadTopology.fallback();
        }
    }

    /**
     * 拼提示词（模板渲染 + 占位符填充），重试与记账交给 {@link MapGenAiCaller}。
     */
    private MapGenAiCaller.Prompt prompt(Long projectId, MapProfile profile, MapAssetCatalog catalog) {
        Long userId = ai.currentUserId();
        var vars = new java.util.HashMap<String, Object>();
        vars.put("world_setting", profile.summary());
        vars.put("profile_summary", profile.summary());
        vars.put("asset_summary", assetSummary(catalog));
        vars.put("landmarks", landmarkText(profile));
        String systemPrompt = promptTemplateService.systemMessage(userId, projectId,
                PromptTemplateService.CODE_WORLD_MAP_ROADS, vars);
        String prompt = promptTemplateService.render(userId, projectId,
                PromptTemplateService.CODE_WORLD_MAP_ROADS, vars);
        return new MapGenAiCaller.Prompt(SCENE, systemPrompt, prompt,
                props.getRoadTemperature(), props.getRoadMaxTokens());
    }

    /**
     * 资产摘要（给拓扑提示词：AI 要知道「有码头、有盐作坊」才会规划对应生产路）。
     */
    private String assetSummary(MapAssetCatalog catalog) {
        StringBuilder sb = new StringBuilder();
        for (MapAssetTypeRef t : MapAssetTypeRef.of(catalog)) {
            sb.append("- ").append(t.name()).append("（").append(t.category()).append("）\n");
        }
        return sb.toString();
    }

    /**
     * 地标文本（拓扑可以拿地标当端点）。
     */
    private String landmarkText(MapProfile profile) {
        if (profile.landmarks() == null || profile.landmarks().isEmpty()) {
            return "（无地标）";
        }
        StringBuilder sb = new StringBuilder();
        for (MapProfile.Landmark m : profile.landmarks()) {
            sb.append("- ").append(m.name()).append("（").append(m.kind()).append("）：")
                    .append(m.sit()).append('\n');
        }
        return sb.toString();
    }

    // ==================== JSON → 拓扑 ====================

    /**
     * 解析 AI 拓扑输出（非法节点引用/非法等级一律丢弃，不抛异常）。
     *
     * @param content AI 原文
     * @return 拓扑
     * @throws Exception JSON 完全不可解析（由调用方回落兜底）
     */
    RoadTopology parse(String content) throws Exception {
        JsonNode root = MapGenAiJson.readMulti(content, "nodes", "roads");
        String json = root.toString();
        List<RoadTopology.Node> nodes = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        JsonNode narr = root.path("nodes");
        if (narr.isArray()) {
            for (JsonNode n : narr) {
                String code = text(n, "code", null);
                if (code == null || !codes.add(code)) {
                    continue;
                }
                String role = text(n, "role", "residential");
                if (!RoadTopology.ROLES.contains(role)) {
                    role = "residential";
                }
                nodes.add(new RoadTopology.Node(code, text(n, "name", code), role,
                        text(n, "prefer", null), text(n, "note", "")));
            }
        }
        List<RoadTopology.Edge> edges = new ArrayList<>();
        Set<String> edgeCodes = new HashSet<>();
        JsonNode rarr = root.path("roads");
        if (rarr.isArray()) {
            for (JsonNode n : rarr) {
                String code = text(n, "code", null);
                String from = text(n, "from", null);
                String to = text(n, "to", null);
                if (code == null || !edgeCodes.add(code) || from == null || to == null) {
                    continue;
                }
                if (!codes.contains(from) || !codes.contains(to) || from.equals(to)) {
                    log.info("[mapgen] S3 丢弃非法边：{}（{} → {}）", code, from, to);
                    continue;
                }
                String kind = text(n, "kind", "lane");
                if (!RoadTopology.EDGE_KINDS.contains(kind)) {
                    kind = "lane";
                }
                String level = text(n, "level", "minor");
                if (!RoadTopology.LEVELS.contains(level)) {
                    level = "minor";
                }
                edges.add(new RoadTopology.Edge(code, text(n, "name", code), kind, level,
                        num(n, "width", 3.0), text(n, "surface", "dirt"), from, to,
                        num(n, "bend", 0.5), (int) Math.round(num(n, "priority", 5))));
            }
        }
        return new RoadTopology(nodes, edges, json, true);
    }

    // ==================== 端点：语义 → 米坐标 ====================

    /**
     * 把拓扑节点逐条落到真实地形上，重合端点做确定性微移。
     *
     * @param topology 拓扑
     * @param sites    语义点表
     * @param seed     种子
     * @return 节点编码 → 米坐标
     */
    private Map<String, MapSites.Site> resolveNodes(RoadTopology topology,
                                                    Map<String, MapSites.Site> sites, long seed) {
        Map<String, MapSites.Site> out = new LinkedHashMap<>();
        List<String> resKeys = List.of("residential_n", "residential_e", "residential_s", "residential_w");
        int resCursor = 0;
        Rng rng = Rng.derive(seed, 5103);
        List<MapSites.Site> taken = new ArrayList<>();
        for (RoadTopology.Node node : topology.nodes()) {
            MapSites.Site s = pickByRole(node, sites, resKeys, resCursor);
            if ("residential".equals(node.role())) {
                // 民居按四个方向轮转：AI 常给 3~4 个民居节点，若全落同一个点就退化成「一条路」
                resCursor++;
            }
            if (s == null) {
                s = sites.get(MapSites.CORE);
            }
            if (s == null) {
                continue;
            }
            MapSites.Site placed = dedupe(s, taken, rng);
            taken.add(placed);
            out.put(node.code(), placed);
        }
        return out;
    }

    /**
     * 角色 → 语义点（民居按四个方向轮转，其余按角色直取）。
     */
    private MapSites.Site pickByRole(RoadTopology.Node node, Map<String, MapSites.Site> sites,
                                     List<String> resKeys, int resCursor) {
        switch (node.role() == null ? "" : node.role()) {
            case "core":
                return sites.get(MapSites.CORE);
            case "gate": {
                MapSites.Site g = sites.get(MapSites.GATE);
                if (g != null) {
                    return g;
                }
                // 门户**绝不能**兜底到 high-ground（全图最高点）：那会让「正街」变成一条
                // 从镇中心修到山顶的 5 米石铺路，两端空空如也——实测就是这样一条 465 米长、
                // 45 米内一栋房子都没有的"通往荒山的官道"，一眼就假。
                // 退路依次是：河口（渡口）→ 镇中心（此时这条边会退化成零长度并被丢弃）。
                for (Map.Entry<String, MapSites.Site> en : sites.entrySet()) {
                    if (en.getKey().startsWith("river_mouth_")) {
                        return en.getValue();
                    }
                }
                return sites.get(MapSites.CORE);
            }
            case "waterfront": {
                MapSites.Site w = sites.get(MapSites.WATERFRONT);
                if (w != null) {
                    return w;
                }
                // 没有「镇边的临水点」时，只在镇子周边 200 米内找河口当埠头；
                // 超过这个距离的河口不再兜底——它不是村里的洗衣埠，拿它当端点会修出一条
                // 四百米长、尽头什么都没有的"埠头路"（确定性路径上唯一一处真断头的根因）。
                MapSites.Site c = sites.get(MapSites.CORE);
                if (c != null) {
                    MapSites.Site mouth = MapSites.nearestRiverMouth(sites, c.x(), c.z(), 200.0);
                    if (mouth != null) {
                        return mouth;
                    }
                }
                // 退到镇心：调用方会把这条零长度的边丢掉，而不是硬修一条通往野地的路
                return c;
            }
            case "farm":
                return MapSites.pick(sites, "farmland", MapSites.CORE);
            case "workshop": {
                MapSites.Site s = sites.get(MapSites.WORKSHOP);
                return s != null ? s : MapSites.pick(sites, "settlement-edge", MapSites.CORE);
            }
            case "residential": {
                String key = resKeys.get(Math.floorMod(resCursor, resKeys.size()));
                MapSites.Site s = sites.get(key);
                return s != null ? s : MapSites.pick(sites, "settlement-edge", MapSites.CORE);
            }
            case "landmark":
                return MapSites.pick(sites, node.prefer() == null ? "high-ground" : node.prefer(),
                        MapSites.HIGH_GROUND);
            default:
                return MapSites.pick(sites, node.prefer(), MapSites.CORE);
        }
    }

    /**
     * 端点归并：两个 AI 节点落到同一处（&lt; 24 米）时，<b>让它们共用一个坐标</b>，只做亚米级抖动。
     *
     * <p><b>旧口径为什么错</b>：旧实现把重合端点按 20~45 米的随机方向「推开」，理由是怕边退化。
     * 推开的代价是——AI 常把「码头口」「月华渡」「礁石」这些语义不同、位置相同的节点各给一条边，
     * 推开之后就变成<b>一组相距二三十米、并行通往同一目的地的路</b>（实测 54 号图：镇上到东岸
     * 同时有三条同走向的路，成图上一眼就是重复）。归并之后三条边只求出一模一样的走线，
     * 由 {@link #route} 的同走向去重收敛成一条。</p>
     *
     * @param s     待落位的点
     * @param taken 已落位的点
     * @param rng   确定性随机（只做亚米级抖动，避免两条边端点完全相等而被判成退化边）
     * @return 归并后的点
     */
    private MapSites.Site dedupe(MapSites.Site s, List<MapSites.Site> taken, Rng rng) {
        for (MapSites.Site o : taken) {
            if (Math.hypot(s.x() - o.x(), s.z() - o.z()) >= DEDUPE_MIN) {
                continue;
            }
            double ang = rng.nextDouble(0, Math.PI * 2);
            double r = rng.nextDouble(0.3, 1.2);
            return new MapSites.Site(s.code(), s.name(), s.role(),
                    o.x() + Math.cos(ang) * r, o.z() + Math.sin(ang) * r, s.score());
        }
        return s;
    }

    // ==================== 拓扑 → 走线 ====================

    /**
     * 逐条边求线（A*），并按 AI 给的性格定等级/宽度/材质。
     *
     * <p><b>端点改写规则（这条规则就是「不再有蜘蛛」的全部秘密）</b>：</p>
     * <ul>
     *   <li>两端都在镇里 → 丢弃：镇内的街巷由骨架负责，AI 的射线不再单独成路；</li>
     *   <li>一端在镇里、一端在外 → 只从外端修到<b>骨架上离它最近的那个点</b>（T 字口）；</li>
     *   <li>两端都在镇外 → 按 AI 的原意直连（这是村镇之间的路，不是镇内的街）。</li>
     * </ul>
     *
     * @param terrain 地形
     * @param cost    代价栅格
     * @param topology 拓扑
     * @param nodes   端点表（米）
     * @param frame   街巷骨架（外部路的接入点）
     * @return 已求出的路（米折线）
     */
    private List<MapPlacer.RoadSpec> route(TerrainData terrain, float[] cost,
                                           RoadTopology topology, Map<String, MapSites.Site> nodes,
                                           List<MapPlacer.RoadSpec> frame) {
        List<MapPlacer.RoadSpec> out = new ArrayList<>();
        List<List<double[]>> keptLines = new ArrayList<>();
        int skipped = 0;
        int inside = 0;
        int redundant = 0;
        // 先修「重要的路」：优先级高 → 等级高 → 编号小。同走向去重时先修的那条留下，
        // 后修的重复路让位——否则留下的是哪个全看 AI 的输出顺序，成图每次都不一样。
        List<RoadTopology.Edge> ordered = new ArrayList<>(topology.roads());
        ordered.sort((x, y) -> {
            int p = Integer.compare(y.priority(), x.priority());
            if (p != 0) {
                return p;
            }
            int g = Integer.compare(gradeOf(y), gradeOf(x));
            if (g != 0) {
                return g;
            }
            return String.valueOf(x.code()).compareTo(String.valueOf(y.code()));
        });
        for (RoadTopology.Edge e : ordered) {
            // 码头/栈桥不在这里修：S4 的 placeHarbor 会依地形生成真正的栈桥
            if ("pier".equals(e.kind())) {
                skipped++;
                continue;
            }
            MapSites.Site a = nodes.get(e.from());
            MapSites.Site b = nodes.get(e.to());
            if (a == null || b == null) {
                skipped++;
                continue;
            }
            boolean aIn = isTownSite(a);
            boolean bIn = isTownSite(b);
            if (aIn && bIn) {
                inside++;
                continue;
            }
            Routed r;
            if (aIn || bIn) {
                MapSites.Site outer = aIn ? b : a;
                double[] hit = StreetNetwork.nearestOn(frame, outer.x(), outer.z());
                // 「目的地就在镇口」的门限从 12 米提到 28 米：二十几米的目的地支线在航拍上
                // 只是从街梢伸出来的一小截 L 形残枝，什么也不服务（街本来就已经到那里了），
                // 而且这样的残枝常常成对出现、互相压着横在镇口。
                if (hit == null || hit[2] < SERVE_ON_STREET) {
                    inside++;
                    continue;
                }
                r = routeTo(terrain, cost, outer.x(), outer.z(), hit[0], hit[1], e.bend());
            } else {
                r = routeTo(terrain, cost, a.x(), a.z(), b.x(), b.z(), e.bend());
            }
            if (r == null) {
                skipped++;
                continue;
            }
            // 尺度守卫：AI 给的 kind 是「这段路是什么」，它天然带量级——
            // 台阶（stairs）是码头上十几米的一段，小径（path）是田边/林缘的便道。
            // 实测 57 号图里一条 kind=stairs 的边被修成 1016 米、绕岛一圈。
            // 超过量级就不修，交给 S4 的田埂/连接路按需补（比留一条假路强）。
            double walked = pathLength(r.path());
            if (walked > kindLimit(e.kind())) {
                log.info("[mapgen] S3 丢弃超长边：{}（kind={} 走线 {} 米，上限 {} 米）", e.code(), e.kind(),
                        Math.round(walked), Math.round(kindLimit(e.kind())));
                skipped++;
                continue;
            }
            int grade = gradeOf(e);
            // 同走向去重：AI 会把「码头口」「渡口」「礁石」这类位置相同、语义不同的节点各给一条边，
            // 求出来就是一组平行通往同一目的地的路（实测 54 号图三条）。这里按覆盖率收敛成一条，
            // 判据与 S4 的田埂去重同一套（{@link MapNetwork#coverage}）。
            boolean dup = false;
            for (int i = 0; i < keptLines.size() && !dup; i++) {
                if (MapNetwork.coverage(r.path(), keptLines.get(i), COVER_TOL, COVER_STEP, 0.86)
                        >= COVER_DUP) {
                    dup = true;
                }
            }
            if (dup) {
                redundant++;
                continue;
            }
            keptLines.add(new ArrayList<>(r.path()));
            out.add(new MapPlacer.RoadSpec(uniqueCode(out, e.code()), e.name(), e.kind(), grade,
                    RoadGrade.clampWidth(grade, e.width()), surfaceOf(e, grade),
                    r.path(), r.bridgeCells()));
        }
        if (skipped > 0 || inside > 0 || redundant > 0) {
            log.info("[mapgen] S3 边处理：成路 {} / 镇内并入骨架 {} / 同走向合并 {} / 丢弃 {}",
                    out.size(), inside, redundant, skipped);
        }
        return out;
    }

    /** 折线长度（米）。 */
    private static double pathLength(List<double[]> path) {
        double sum = 0;
        for (int i = 1; i < path.size(); i++) {
            sum += Math.hypot(path.get(i)[0] - path.get(i - 1)[0], path.get(i)[1] - path.get(i - 1)[1]);
        }
        return sum;
    }

    /**
     * 各 kind 的合理长度上限（米）：超过就说明 AI 把一条"设施内部的小段"当成村镇之间的路来连了。
     *
     * <p>为什么按 kind 而不是按等级：{@code stairs} / {@code pier} 是<b>设施的一部分</b>
     * （码头的台阶、栈桥的踏板），它的长度由设施尺寸决定，跟路网等级无关；
     * 码头台阶修成 1016 米这种事，用等级判不出来，用 kind 一眼就能拦住。</p>
     *
     * @param kind AI 给的边类型
     * @return 上限（米）
     */
    static double kindLimit(String kind) {
        String k = kind == null ? "" : kind;
        return switch (k) {
            case "stairs" -> 90.0;
            case "bridge" -> 120.0;
            case "path" -> 420.0;
            case "lane" -> 620.0;
            default -> 900.0;
        };
    }

    /**
     * 编码去撞：AI 完全可能把一条镇外路也叫 {@code main_street}，而那正是街巷骨架的编码。
     *
     * <p>撞码的后果不只是重名——{@code MapPlacer.isSkeleton} 是按编码前缀认骨架的，
     * AI 的目的地路一旦叫 {@code main_street}，就被当成骨架街巷：不参与剪枝、去重时排在最前，
     * 于是一条 321 米、横穿林子的"正街"被当成镇子的主干保留下来。撞了就加 {@code dlv_} 前缀。</p>
     */
    private static String uniqueCode(List<MapPlacer.RoadSpec> out, String code) {
        String c = code == null || code.isBlank() ? "delivery" : code;
        boolean clash = c.startsWith("main_street") || c.startsWith("back_lane")
                || c.startsWith("cross_lane") || c.startsWith("ring_") || c.startsWith("alley_")
                || c.startsWith("farm_") || c.startsWith("conn_") || c.startsWith("dlv_");
        for (MapPlacer.RoadSpec r : out) {
            if (c.equals(r.code())) {
                clash = true;
                break;
            }
        }
        return clash ? "dlv_" + c : c;
    }

    /** 一次 A* 的结果（米折线 + 桥格数）。 */
    private record Routed(List<double[]> path, int bridgeCells) {
    }

    /**
     * 求一条线：端点用米，内部换成格再交给 {@link RoadRouter}。
     *
     * @return 结果；走不通返回 null
     */
    private Routed routeTo(TerrainData terrain, float[] cost,
                           double ax, double az, double bx, double bz, double bend) {
        double mpc = Math.max(0.05, terrain.metersPerCell);
        RoadRouter.Route r = RoadRouter.route(terrain, cost,
                (int) Math.round(ax / mpc), (int) Math.round(az / mpc),
                (int) Math.round(bx / mpc), (int) Math.round(bz / mpc),
                clamp(bend, 0.0, 1.0));
        if (r == null || r.path().size() < 2) {
            return null;
        }
        List<double[]> path = new ArrayList<>(r.path().size());
        for (double[] pt : r.path()) {
            path.add(new double[]{pt[0] * mpc, pt[1] * mpc});
        }
        // 拉直：A* 为了避开林/坡会绕圈，两个近邻点之间本该是直路（用户点名「绕到挺远的地方
        // 才接上」）。只有「不下水、不穿房、绕行比例明显」时才替换成直线，桥（bridgeCells>0）
        // 一律不动——那是真的跨水，不能拉直。
        if (r.bridgeCells() <= 0) {
            path = RoadRouter.straighten(terrain, cost, path);
        }
        return new Routed(path, r.bridgeCells());
    }

    /**
     * 是否「镇内端点」：核心与民居。
     * <p>只有这两类节点代表「镇子里面」；码头、田区、工坊、河埠、地标都是镇外的目的地。</p>
     */
    private static boolean isTownSite(MapSites.Site s) {
        return "core".equals(s.role()) || "residential".equals(s.role());
    }

    /**
     * 等级映射：主街 2 / 村庄道路 1 / 生产路 0（田埂级土路）。
     * <p>为什么生产路是 0 级而不是 1 级：用户明确「越高级道路越好」，而村庄到田里的路
     * 现实中就是土路（雨后泥泞、会车困难），比村里的巷道还要差一档。它和 S4 生成的田埂
     * 同属 0 级，于是「田间路网」在图上是一个连续的整体，而不是「一段村道插进田里」。</p>
     */
    static int gradeOf(RoadTopology.Edge e) {
        return switch (e.level() == null ? "minor" : e.level()) {
            case "major" -> RoadGrade.STREET;
            case "production" -> RoadGrade.TRACK;
            default -> RoadGrade.VILLAGE;
        };
    }

    /**
     * 材质：等级是硬约束（土路不能铺石板），AI 的 surface 只在高等级里有意义。
     */
    static String surfaceOf(RoadTopology.Edge e, int grade) {
        if (grade <= RoadGrade.TRACK) {
            return RoadGrade.defaultSurface(grade);
        }
        String s = e.surface() == null ? "" : e.surface().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "stone", "wood", "dirt", "gravel" -> s;
            default -> RoadGrade.defaultSurface(grade);
        };
    }

    /** 夹取。 */
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 取字符串（缺失/空白 → 默认值）。 */
    private static String text(JsonNode n, String key, String def) {
        JsonNode v = n.path(key);
        if (v.isMissingNode() || v.isNull()) {
            return def;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? def : s.trim();
    }

    /** 取数值（缺失/非数值 → 默认值）。 */
    private static double num(JsonNode n, String key, double def) {
        JsonNode v = n.path(key);
        double d = v.isNumber() ? v.asDouble() : def;
        return Double.isNaN(d) || Double.isInfinite(d) ? def : d;
    }

    /** 资产摘要用的轻量视图（避免把整个 catalog 的字段名写进提示词拼装处）。 */
    private record MapAssetTypeRef(String name, String category) {
        static List<MapAssetTypeRef> of(MapAssetCatalog catalog) {
            List<MapAssetTypeRef> out = new ArrayList<>();
            for (var t : catalog.types()) {
                out.add(new MapAssetTypeRef(t.name(), t.category()));
            }
            return out;
        }
    }

    /** 空表兜底（保留给后续扩展：按节点角色统计用）。 */
    static Map<String, Integer> roleCounts(RoadTopology topology) {
        Map<String, Integer> out = new HashMap<>();
        for (RoadTopology.Node n : topology.nodes()) {
            out.merge(n.role() == null ? "unknown" : n.role(), 1, Integer::sum);
        }
        return out;
    }
}
