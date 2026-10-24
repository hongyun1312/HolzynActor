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

import com.holzyn.actor.domain.mapgen.config.MapGenProperties;
import com.holzyn.actor.domain.mapgen.model.MapAssetCatalog;
import com.holzyn.actor.domain.mapgen.model.MapAssetType;
import com.holzyn.actor.domain.mapgen.model.MapProfile;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * S2 建筑资产策略：产出「这一带该有什么房子」的类型库，并做三道闸门。
 *
 * <p><b>三道闸门（用户决策 #20「禁忌清单三层拦截」）</b>：</p>
 * <ol>
 *   <li><b>大类白名单</b>：{@code category} 必须落在 民居/商铺/工坊/公共/生产/水工/自然 七类里，
 *       写「军事」「宗教」这类世界观不该有的类别直接丢弃——这是最硬的一层，因为落位算法只认这七类；</li>
 *   <li><b>禁忌清单</b>：S0 档案给的世界观禁忌（「武器铺/监牢/赌坊/现代设施」）逐条比对
 *       <b>名字 + 大类 + 描述</b>，命中即丢弃并记日志。这一层挡的是「AI 觉得该有个铁匠铺很合理」；</li>
 *   <li><b>必要类型兜底</b>：过滤完如果缺了「住人的、卖东西的、种地的、砍柴的、靠船的」这五样，
 *       从内置海岛库里补齐——否则 S4 要么空转（没房子可放），要么把商铺当民居用（一眼假）。</li>
 * </ol>
 *
 * <p><b>尺寸/层数夹取</b>：AI 很爱写「面宽 24 米的农舍」。这里的夹取不是审美洁癖，而是
 * S4 的落位约束建立在「民居开间 6~12 米、进深 5~9 米」这个真实量纲上——数字离谱会直接把
 * 「贴街排布 + 留院」算崩，出来就是一排仓库压在街上。</p>
 *
 * <p><b>事务纪律</b>：含秒级 AI 调用，<b>不得</b>带 {@code @Transactional}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapAssetPolicyService {

    /** AI 场景名 */
    static final String SCENE = "world_map_assets";

    /** 必要类型的最小集合（缺哪类就补哪类）：大类 → 兜底 code */
    private static final List<String[]> NECESSARY = List.of(
            new String[]{"民居", "house_fisher"},
            new String[]{"商铺", "shop_grocery"},
            new String[]{"生产", "farm_plot"},
            new String[]{"自然", "wood_lot"},
            new String[]{"水工", "water_pier"});

    /**
     * 第一层：全局硬禁（代码常量，<b>AI 不可修改</b>）。
     *
     * <p>为什么必须有这一层：第二层禁忌来自 AI 画像，实测它会把「海神庙」写成「海神小祠」、
     * 把「神殿」从清单里漏掉——只靠 AI 的清单，图里就会长出 3 座祠庙。这一层是世界观无关的
     * 通用底线：武装/监禁/赌博/宗教祭祀/现代工业/高层，多数生活向题材都不该出现。</p>
     *
     * <p>每组带中文同义词：AI 不会老老实实用「神殿」这个词，它会写庙、祠、寺、观、神社。
     * 同义词只用通用汉语词，<b>不含任何具体地名</b>。</p>
     */
    private static final java.util.Map<String, List<String>> HARD_BANNED = java.util.Map.of(
            "武器铺", List.of("武器", "兵器", "军械", "刀剑", "弓弩", "火器", "弹药"),
            "监牢", List.of("监牢", "牢房", "牢狱", "监狱", "拘留", "刑房", "囚室"),
            "赌坊", List.of("赌坊", "赌场", "博彩", "骰", "牌局", "押注", "彩票"),
            "神殿", List.of("神殿", "寺庙", "寺院", "庙", "祠", "宫殿", "道观", "教堂", "神社",
                    "祭坛", "神龛", "塔庙", "香火"),
            "现代设施", List.of("电力", "电灯", "自来", "消防栓", "路灯", "电话", "汽车",
                    "火车站", "加油站", "工厂", "水泥", "玻璃幕墙"),
            "摩天楼", List.of("摩天", "大厦", "写字楼", "高层公寓", "电梯楼"));

    /** 硬禁日志前缀（排查时一眼看出是哪一层挡的）。 */
    private static final String HARD_BAN_TAG = "[mapgen] S2 硬禁命中：";

    /** 尺寸夹取区间（米）：民居/商铺的真实开间进深都在这附近 */
    private static final double MIN_W = 3.0;
    private static final double MAX_W = 40.0;
    private static final double MIN_D = 3.0;
    private static final double MAX_D = 40.0;

    private final MapGenAiCaller ai;
    private final PromptTemplateService promptTemplateService;
    private final MapGenProperties props;

    /**
     * 生成资产目录（S2）。
     *
     * @param projectId 项目 ID
     * @param profile   S0 世界档案（提供禁忌清单与摘要）
     * @return 资产目录（AI 失败/关闭时为确定性兜底目录）
     */
    public MapAssetCatalog build(Long projectId, MapProfile profile) {
        return build(projectId, profile, null);
    }

    /**
     * 生成资产目录（S2，带世界→地图契约）。
     *
     * @param projectId 项目 ID
     * @param profile   S0 世界档案（提供禁忌清单与摘要）
     * @param brief     S0b 世界→地图契约（提供设施清单/人口，可为 null）
     * @return 资产目录（AI 失败/关闭时为确定性兜底目录）
     */
    public MapAssetCatalog build(Long projectId, MapProfile profile,
                                 com.holzyn.actor.domain.mapgen.model.WorldMapBrief brief) {
        if (!props.isAiEnabled()) {
            log.info("[mapgen] S2 跳过 AI（mapgen.ai-enabled=false），使用海岛兜底资产库");
            return fallbackClean(profile);
        }
        try {
            long start = System.currentTimeMillis();
            String content = ai.content(projectId, prompt(projectId, profile, brief));
            MapAssetCatalog parsed = withFacilities(parse(content, profile), brief);
            log.info("[mapgen] S2 资产库：{} 个类型 / {} 个街区，{}ms，aiGenerated={}",
                    parsed.types().size(), parsed.districts().size(),
                    System.currentTimeMillis() - start, parsed.aiGenerated());
            return parsed;
        } catch (Exception e) {
            log.warn("[mapgen] S2 资产库生成失败，回落确定性兜底：{}", e.getMessage());
            return withFacilities(fallbackClean(profile), brief);
        }
    }

    // ==================== 契约设施 → 资产类型 ====================

    /**
     * 补齐「世界→地图契约」里点名要求的设施类型。
     *
     * <p><b>为什么必须在 S2 做</b>：契约里的设施（灯塔、渡口、议事堂、水井…）是世界观要求
     * 「这张图上必须有」的东西，而 S2 是 AI 自由发挥的——它可能只给了几张民居皮，
     * 结果图上没有灯塔，落位器只能拿别的类型顶替（于是「长明灯塔」变成一栋普通房子）。
     * 这里就地把缺失的设施类型<b>按契约字段合成</b>出来（尺寸/层数都来自契约），
     * 保证 S4 能按 {@code assetCode == facility.code} 精确匹配到它该盖的那一栋。</p>
     *
     * @param catalog AI/兜底产出的资产目录
     * @param brief   世界→地图契约（可为 null）
     * @return 补齐后的目录（必要时新建目录对象）
     */
    MapAssetCatalog withFacilities(MapAssetCatalog catalog,
                                   com.holzyn.actor.domain.mapgen.model.WorldMapBrief brief) {
        if (catalog == null || brief == null || brief.facilities() == null
                || brief.facilities().isEmpty()) {
            return catalog;
        }
        List<MapAssetType> types = new ArrayList<>(catalog.types());
        Set<String> codes = new HashSet<>();
        for (MapAssetType t : types) {
            codes.add(t.code());
        }
        int added = 0;
        for (com.holzyn.actor.domain.mapgen.model.WorldMapBrief.Facility f : brief.facilities()) {
            if (f == null || f.code() == null || f.code().isBlank()) {
                continue;
            }
            if (codes.contains(f.code())) {
                continue;
            }
            // 注意：这里**不做**「已有同类就跳过」的省事优化。
            // 契约点名的设施必须有一份 code 完全一致的资产类型，S4 才能按 code 精确锚定它；
            // 靠「语义相似」去复用 AI 随手起的名字，结局就是灯塔/议事堂/水井全锚不上，
            // 只能在街上随机落位（实测 9 个设施里 6 个「无类型」）。
            String key = facilityKey(f.code(), f.name(), f.kind());
            MapAssetType t = synthesize(f, key);
            if (t == null) {
                continue;
            }
            types.add(t);
            codes.add(t.code());
            added++;
        }
        if (added > 0) {
            log.info("[mapgen] S2 按契约补齐设施类型 {} 个（世界数据里有、AI 没给）", added);
        }
        return added == 0 ? catalog
                : new MapAssetCatalog(types, catalog.districts(), catalog.rawJson(), catalog.aiGenerated());
    }

    /**
     * 设施的通用语义键（<b>只用通用词</b>：灯/塔、码头、集市、堂、学、井、磨、仓、船屋、盐、铺、坊）。
     *
     * @param code 编码
     * @param name 名字
     * @param kind 类型
     * @return 语义键（无则空串）
     */
    private static String facilityKey(String code, String name, String kind) {
        // 统一口径：S2 与 S4 必须用同一份语义键，否则会出现「S2 认为有、S4 找不到」
        return com.holzyn.actor.domain.mapgen.model.AssetSemantics.key(code, name, kind);
    }
    /**
     * 由契约设施合成一个资产类型（尺寸/层数取契约值，配色取该大类的通用色板）。
     *
     * <p><b>不含任何世界观内容</b>：颜色是按大类给的通用色（公共偏灰白石材、生产偏木土色…），
     * 名字/编码全部来自契约本身。</p>
     *
     * @param f   契约设施
     * @param key 语义键
     * @return 资产类型（参数非法时 null）
     */
    private static MapAssetType synthesize(com.holzyn.actor.domain.mapgen.model.WorldMapBrief.Facility f,
                                           String key) {
        String category = switch (f.role() == null ? "" : f.role()) {
            case "商铺" -> "商铺";
            case "工坊" -> "工坊";
            case "生产" -> "生产";
            case "水工" -> "水工";
            default -> "公共";
        };
        double w = clamp(f.frontageM(), 3.5, 32);
        double d = clamp(f.depthM(), 3.5, 28);
        int floors = (int) Math.round(clamp(f.floors(), 1, 4));
        String wall = switch (category) {
            case "商铺" -> "#e0d3b6";
            case "工坊" -> "#cfc2a8";
            case "生产" -> "#c6bba4";
            case "水工" -> "#cfcbc0";
            default -> "#dcd6c6";
        };
        String roof = switch (category) {
            case "商铺" -> "#7d5b3f";
            case "工坊" -> "#5d5348";
            case "生产" -> "#6a5a44";
            case "水工" -> "#5a5f66";
            default -> "#6b6f73";
        };
        String shape = switch (key) {
            case "lighthouse" -> "spire";
            case "well", "pier" -> "flat";
            case "salt", "workshop" -> "shed";
            default -> "gable";
        };
        String kind = "pier".equals(key) ? "pier" : "building";
        String material = "生产".equals(category) || "水工".equals(category) ? "wood" : "stone";
        return new MapAssetType(f.code(), f.name(), category, kind, w, d,
                floors, Math.max(floors, Math.min(4, floors + 1)),
                wall, roof, shape, material, List.of(), 1,
                "由世界数据指定的设施");
    }

    // ==================== AI 调用 ====================

    /**
     * 拼提示词（模板渲染 + 占位符填充），重试与记账交给 {@link MapGenAiCaller}。
     */
    private MapGenAiCaller.Prompt prompt(Long projectId, MapProfile profile,
                                         com.holzyn.actor.domain.mapgen.model.WorldMapBrief brief) {
        Long userId = ai.currentUserId();
        var vars = new java.util.HashMap<String, Object>();
        vars.put("world_setting", profile.summary());
        vars.put("profile_summary", profile.summary());
        vars.put("forbidden", String.join("、", safe(profile.forbidden())));
        if (brief != null) {
            // 让 AI 按「世界数据推导出的设施清单」出类型，而不是凭空发明一套资产库
            vars.put("facility_list", String.join("、", brief.facilities() == null ? List.<String>of()
                    : brief.facilities().stream().map(f -> f.name() + "(" + f.role() + ")").toList()));
        }
        String systemPrompt = promptTemplateService.systemMessage(userId, projectId,
                PromptTemplateService.CODE_WORLD_MAP_ASSETS, vars);
        String prompt = promptTemplateService.render(userId, projectId,
                PromptTemplateService.CODE_WORLD_MAP_ASSETS, vars);
        return new MapGenAiCaller.Prompt(SCENE, systemPrompt, prompt,
                props.getTemperature(), props.getAssetMaxTokens());
    }

    // ==================== JSON → 资产目录（含三道闸门） ====================

    /**
     * 解析 + 过滤 + 补齐。
     *
     * @param content AI 原文
     * @param profile S0 档案（禁忌清单）
     * @return 资产目录
     * @throws Exception JSON 完全不可解析（由调用方回落兜底）
     */
    MapAssetCatalog parse(String content, MapProfile profile) throws Exception {
        JsonNode root = MapGenAiJson.read(content, "assetTypes");
        String json = root.toString();
        Set<String> forbidden = new HashSet<>();
        for (String f : safe(profile.forbidden())) {
            forbidden.add(f);
        }
        List<MapAssetType> types = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        int accepted = 0;
        JsonNode arr = root.path("assetTypes");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                MapAssetType t = toType(n);
                if (t == null) {
                    continue;
                }
                // 闸门⓪：全局硬禁（代码常量 + 同义词，AI 不可改）——必须先于白名单与 AI 层
                String hardHit = hardBanHit(t);
                if (hardHit != null) {
                    log.info(HARD_BAN_TAG + "{}（{}）← 词「{}」", t.name(), t.code(), hardHit);
                    continue;
                }
                // 闸门①：大类白名单
                if (!MapAssetType.CATEGORIES.contains(t.category())) {
                    log.info("[mapgen] S2 丢弃非白名单大类：{}（{}）", t.name(), t.category());
                    continue;
                }
                // 闸门②：禁忌清单（名字 / 大类 / 描述）
                String hit = forbiddenHit(t, forbidden);
                if (hit != null) {
                    log.info("[mapgen] S2 丢弃禁忌类型：{}（命中「{}」）", t.name(), hit);
                    continue;
                }
                // code 去重（AI 偶尔会重复）
                String code = t.code();
                int dup = 2;
                while (!codes.add(code)) {
                    code = t.code() + "_" + dup++;
                }
                types.add(code.equals(t.code()) ? t : new MapAssetType(code, t.name(), t.category(),
                        t.kind(), t.baseW(), t.baseD(), t.floorsMin(), t.floorsMax(), t.wallColor(),
                        t.roofColor(), t.roofShape(), t.material(), t.styleTags(), t.weight(),
                        t.description()));
                accepted++;
            }
        }
        // AI 一条都没活下来（空产出 / 全被两道闸门拍掉）：整份目录回落兜底。
        // 不在这里补必要类型——那会得到一份「大部分是兜底、却标着 AI 生成」的混合目录，
        // 排查时最难查（看不出到底是 AI 的锅还是兜底的锅）。
        if (accepted == 0) {
            log.warn("[mapgen] S2 AI 资产库无任何类型通过闸门，回落确定性兜底资产库");
            return fallbackClean(profile);
        }
        // 闸门③：必要类型补齐
        types = ensureNecessary(types, profile);
        if (types.isEmpty()) {
            log.warn("[mapgen] S2 AI 产出为空，回落确定性兜底资产库");
            return fallbackClean(profile);
        }
        List<MapAssetCatalog.District> districts = new ArrayList<>();
        JsonNode darr = root.path("districts");
        if (darr.isArray()) {
            for (JsonNode n : darr) {
                String name = text(n, "name", null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                String code = text(n, "code", "district_" + districts.size());
                districts.add(new MapAssetCatalog.District(code, name, text(n, "character", "")));
            }
        }
        return new MapAssetCatalog(types, districts, json, true);
    }

    /**
     * 单条 JSON → 类型（字段全部夹取/归一；缺关键字段返回 null）。
     */
    private MapAssetType toType(JsonNode n) {
        String code = text(n, "code", null);
        String name = text(n, "name", null);
        if (code == null || name == null) {
            return null;
        }
        String category = text(n, "category", "民居");
        String kind = text(n, "kind", "building");
        if (!MapAssetType.KINDS.contains(kind)) {
            kind = "building";
        }
        String shape = text(n, "roofShape", "gable");
        if (!MapAssetType.ROOF_SHAPES.contains(shape)) {
            shape = "gable";
        }
        double w = clamp(num(n, "baseW", 9.0), MIN_W, MAX_W);
        double d = clamp(num(n, "baseD", 7.5), MIN_D, MAX_D);
        int f0 = (int) Math.round(clamp(num(n, "floorsMin", 1), 1, 4));
        int f1 = (int) Math.round(clamp(num(n, "floorsMax", Math.max(1, f0)), 1, 4));
        if (f1 < f0) {
            int tmp = f0;
            f0 = f1;
            f1 = tmp;
        }
        int weight = (int) Math.round(clamp(num(n, "weight", 10), 1, 20));
        List<String> tags = new ArrayList<>();
        JsonNode tarr = n.path("styleTags");
        if (tarr.isArray()) {
            for (JsonNode t : tarr) {
                String s = t.asText();
                if (s != null && !s.isBlank()) {
                    tags.add(s.trim());
                }
            }
        }
        return new MapAssetType(code, name, category, kind, w, d, f0, f1,
                color(n, "wallColor", "#d9c9a8"), color(n, "roofColor", "#8a5a3c"), shape,
                text(n, "material", "wood"), tags, weight, text(n, "description", ""));
    }

    /**
     * 全局硬禁命中（第一层，AI 不可改）。
     *
     * <p>匹配范围：名字 + 描述 + 大类（全部做通用同义词展开）。这一层挡的是「AI 觉得海神小祠很合理」——
     * 它连「祠」这个字都不该出现在图里。</p>
     *
     * @param t 资产类型
     * @return 命中的词；未命中返回 null
     */
    private String hardBanHit(MapAssetType t) {
        String text = ((t.name() == null ? "" : t.name()) + " "
                + (t.description() == null ? "" : t.description()) + " "
                + (t.category() == null ? "" : t.category())).toLowerCase(Locale.ROOT);
        for (var e : HARD_BANNED.entrySet()) {
            if (text.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                return e.getKey();
            }
            for (String syn : e.getValue()) {
                if (text.contains(syn.toLowerCase(Locale.ROOT))) {
                    return e.getKey() + "/" + syn;
                }
            }
        }
        return null;
    }

    /**
     * 禁忌命中检测（名字包含 / 大类相等 / 描述包含），并展开第一层的同义词组。
     *
     * @return 命中的禁忌词；未命中返回 null
     */
    private String forbiddenHit(MapAssetType t, Set<String> forbidden) {
        if (forbidden.isEmpty()) {
            return null;
        }
        String name = t.name() == null ? "" : t.name();
        String desc = t.description() == null ? "" : t.description();
        for (String f : forbidden) {
            if (f == null || f.isBlank()) {
                continue;
            }
            if (name.contains(f) || t.category().equals(f) || desc.contains(f)) {
                return f;
            }
            for (String syn : HARD_BANNED.getOrDefault(f, List.of())) {
                if (name.contains(syn) || desc.contains(syn)) {
                    return f + "/" + syn;
                }
            }
        }
        return null;
    }

    /**
     * 必要类型补齐（缺「住人的/卖东西的/种地的/砍柴的/靠船的」就从内置库补）。
     */
    private List<MapAssetType> ensureNecessary(List<MapAssetType> types, MapProfile profile) {
        Set<String> have = new HashSet<>();
        for (MapAssetType t : types) {
            have.add(t.category());
        }
        List<MapAssetType> out = new ArrayList<>(types);
        // 补齐用「同题材」的内置库：高原集镇不该被补一间「海带棚屋」。
        List<MapAssetType> library = MapAssetType.fallbackFor(
                profile == null ? null : profile.genreProfile());
        List<String> fallback = library.stream().map(MapAssetType::code).toList();
        for (String[] need : NECESSARY) {
            if (have.contains(need[0])) {
                continue;
            }
            // 该大类整类缺失：从内置库挑一条补上（仍然要过禁忌/白名单，但内置库本身是干净的）
            int idx = fallback.indexOf(need[1]);
            if (idx >= 0) {
                MapAssetType add = library.get(idx);
                out.add(add);
                have.add(need[0]);
                log.info("[mapgen] S2 补齐必要大类「{}」：{}", need[0], add.name());
            }
        }
        return out;
    }

    // ==================== 小工具 ====================

    /**
     * 兜底资产库也过一遍闸门。
     *
     * <p>内置库本身是干净的，但「硬禁」是代码常量——将来改内置库或 AI 部分失败时，
     * 不经过闸门的兜底会成为漏网路径。这里让兜底与 AI 产出走同一套判定。</p>
     *
     * @param profile 世界档案
     * @return 过滤后的目录
     */
    private MapAssetCatalog fallbackClean(MapProfile profile) {
        Set<String> forbidden = new HashSet<>(safe(profile == null ? null : profile.forbidden()));
        List<MapAssetType> types = new ArrayList<>();
        // 按题材挑兜底库：这是「AI 关闭时也不会串味」的关键一步
        for (MapAssetType t : MapAssetType.fallbackFor(
                profile == null ? null : profile.genreProfile())) {
            if (!MapAssetType.CATEGORIES.contains(t.category())) {
                continue;
            }
            if (hardBanHit(t) != null) {
                continue;
            }
            if (forbiddenHit(t, forbidden) != null) {
                continue;
            }
            types.add(t);
        }
        return new MapAssetCatalog(types, List.of(), null, false);
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

    /** 取颜色（只接受 #rrggbb；其它写法归一成 #rrggbb，失败回落默认色）。 */
    private static String color(JsonNode n, String key, String def) {
        String s = text(n, key, def);
        if (s == null) {
            return def;
        }
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("#") && (s.length() == 7 || s.length() == 4)) {
            return s;
        }
        if (s.length() == 6 && s.chars().allMatch(Character::isLetterOrDigit)) {
            return "#" + s;
        }
        return def;
    }

    /** 夹取。 */
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 空集合兜底。 */
    private static List<String> safe(List<String> l) {
        return l == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(l));
    }
}
