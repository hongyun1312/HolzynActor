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

package com.holzyn.actor.domain.mapnav.service;

import com.holzyn.actor.domain.mapgen.entity.ActorMapFeature;
import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目标引用解析器（服务端一半）：<b>AI 只给「去哪」，坐标由这里算</b>。
 *
 * <p><b>为什么要这一层</b>：让模型直接吐 {@code (x,z)} 是这一类系统最经典的事故源——模型没有
 * 地图坐标系，它给的数字看起来合理（村里、路上、房子附近），但落到图上是墙里、水里或隔壁村。
 * 本版把契约反过来：<b>模型只说引用</b>（{@code f_house_fisher_003} / {@code loc:集市} /
 * {@code npc:12} / {@code plaza}），引用到坐标的换算、合法性判定、就近纠正全部在服务端做，
 * 并且**每一次解析都留 {@code resolvedBy}**，让「模型守不守契约」变成可统计的数。</p>
 *
 * <p><b>为什么还要接受坐标文本</b>：本地 35B 级模型实际很难稳定守格式。硬拒绝会让世界停摆，
 * 所以坐标文本也收，但一律标 {@code resolvedBy=coordinate_fallback}——收留它，同时把它记账。
 * 若坐标落点不可走（水里/墙里），再经 {@link NpcNavigationService#nearestWalkable} 纠正为
 * {@code nearest_walkable}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TargetResolver {

    /** 地图边长（米）：V207 约定 0..1024，原点西南角 */
    public static final double MAP_SPAN_M = 1024.0;

    /** 就近纠偏的搜索半径（格=米） */
    private static final int SNAP_RADIUS = 12;

    /** 解析结果的来源标记 */
    public static final String BY_FEATURE = "feature";

    /** 地点表 */
    public static final String BY_LOCATION = "location";

    /** 角色当前位置 */
    public static final String BY_NPC = "npc";

    /** 广场/地图中心 */
    public static final String BY_PLAZA = "plaza";

    /** 模型直接给了坐标（契约未遵守） */
    public static final String BY_COORDINATE = "coordinate_fallback";

    /** 落点不可走，已就近纠正 */
    public static final String BY_SNAPPED = "nearest_walkable";

    /** 模糊命中要素名（「去米行」→ 周家米行；AI 从不说全名，只说它记得的那半句） */
    public static final String BY_FEATURE_NAME = "feature_name";

    /** 模糊命中地点名（「回铺子」→ 铁匠铺） */
    public static final String BY_LOCATION_FUZZY = "location_fuzzy";

    /**
     * 口语里包在地方名外面的壳：动词前缀 + 指代后缀 + 语气词。
     * <p>实测模型给的 target 是「顺路去米行讨碗水喝」这种整句里的片段，不是要素 code——
     * 只做 code 精确匹配等于把绝大多数真实目标判成「无法解析」，人就不动了。</p>
     */
    private static final String[] VERB_PREFIXES = {
            "顺路去", "顺路到", "顺道去", "先去", "再去", "赶去", "赶回", "回到", "回", "走去", "走回",
            "前往", "去往", "过去", "去", "到", "往", "赴", "朝", "向", "在", "从", "找", "上", "回"
    };

    /** 目标后缀/量词：剥掉才能和要素名对上（「米行那边」→「米行」）。 */
    private static final String[] TAIL_WORDS = {
            "那边", "这儿", "那儿", "一趟", "一下", "看看", "瞧瞧", "找找", "去", "走", "吧", "呢", "。", "，"
    };

    /** 位移动词：动作文本里出现它，说明这句话在说「去哪」。 */
    private static final String[] MOVE_VERBS = {"前往", "去往", "走到", "走向", "赶往", "回到", "返回", "顺路去", "顺路到",
            "去", "到", "往", "回", "赴", "朝", "向"};

    /**
     * 目的地短语的截止字：出现在地方名之后就是「到那儿干什么」的开始。
     * <p>例：「顺路去米行讨碗水喝」→ 在「讨」处截断，「米行」才是地名。</p>
     */
    private static final String STOP_CHARS = "，。；、,.;!?！？ \t讨找买吃喝睡坐歇聊说问拿取借还送给帮干做补卖换收捡看望瞧转等排打";

    /**
     * 从动作文本里剥出目的地（{@code target} 字段为空时的兜底）。
     *
     * <p><b>为什么必须兜底</b>：实测世界 143 的行动计划里，{@code type=move} 的
     * 「顺路去米行讨碗水喝」<b>target 是空串</b>——执行侧看到空目标就直接跳过移动，
     * 于是 AI 说去哪、地图上的人一步不动，而感知/记忆/叙事全都当真了。
     * 目的地就在动作文本里，剥一次比再调一次模型便宜得多，也不会引入新的不确定性。</p>
     *
     * @param action 动作文本（可空）
     *
     * @return 目的地短语（空串=这句话没说去哪）
     */
    public static String destinationText(String action) {
        if (action == null || action.isBlank()) {
            return "";
        }
        String s = action.trim();
        int at = -1;
        int verbLen = 0;
        for (String v : MOVE_VERBS) {
            int i = s.indexOf(v);
            if (i >= 0 && (at < 0 || i < at)) {
                at = i;
                verbLen = v.length();
            }
        }
        if (at < 0) {
            return "";
        }
        String rest = s.substring(at + verbLen);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            if (STOP_CHARS.indexOf(rest.charAt(i)) >= 0) {
                end = i;
                break;
            }
        }
        String phrase = normalizeTarget(rest.substring(0, end));
        // 「去那边」「去一趟」这类没有地名义的短语，长度会掉到 2 以下
        return phrase.length() < 2 ? "" : phrase;
    }

    /** 坐标文本（容错口径：`(12.3,45.6)` / `12.3,45.6` / `12.3 45.6`） */
    private static final Pattern COORD_TEXT = Pattern.compile(
            "\\(?\\[?\\s*(-?\\d{1,4}(?:\\.\\d+)?)\\s*[,， ]\\s*(-?\\d{1,4}(?:\\.\\d+)?)\\s*\\)?\\]?");

    private final ActorMapFeatureRepository featureRepository;

    private final ActorWorldMapRepository mapRepository;

    private final WorldLocationRepository locationRepository;

    private final NpcSpatialView spatialView;

    private final NpcNavigationService navigationService;

    /**
     * 解析一个目标引用。
     *
     * @param projectId 项目
     *
     * @param targetRef 目标引用（要素 code / {@code f:} / {@code loc:名} / {@code npc:ref} / {@code plaza} / 坐标文本）
     *
     * @return 解析结果；无法解析返回 empty（调用方保留原行为）
     */
    public Optional<Resolved> resolve(Long projectId, String targetRef) {
        if (projectId == null || targetRef == null || targetRef.isBlank()) {
            return Optional.empty();
        }
        String raw = targetRef.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        try {
            if (lower.equals("plaza") || raw.equals("广场") || lower.equals("center")) {
                return Optional.of(plaza(projectId));
            }
            if (lower.startsWith("npc:")) {
                return npc(projectId, raw.substring(4).trim());
            }
            if (lower.startsWith("loc:") || lower.startsWith("location:")) {
                int at = raw.indexOf(':');
                return location(projectId, raw.substring(at + 1).trim(), raw);
            }
            if (lower.startsWith("f:") || lower.startsWith("feature:")) {
                int at = raw.indexOf(':');
                return feature(projectId, raw.substring(at + 1).trim(), raw);
            }
            // 无前缀：先按要素 code 找（AI 最常给的形态），再地点表，再角色，最后坐标文本
            Optional<Resolved> byFeature = feature(projectId, raw, raw);
            if (byFeature.isPresent()) {
                return byFeature;
            }
            Optional<Resolved> byLoc = location(projectId, raw, "loc:" + raw);
            if (byLoc.isPresent()) {
                return byLoc;
            }
            Optional<Resolved> byNpc = npc(projectId, raw.replace("special:", "").replace("crowd:", ""));
            if (byNpc.isPresent()) {
                return byNpc;
            }
            Matcher m = COORD_TEXT.matcher(raw);
            if (m.find()) {
                double x = Double.parseDouble(m.group(1));
                double z = Double.parseDouble(m.group(2));
                return Optional.of(legality(projectId, raw, null, x, z, BY_COORDINATE));
            }
            // 最后一档：口语名模糊匹配（「米行」→ 周家米行 / 「铺子」→ 铁匠铺）。
            // 放在坐标文本之后：有数字就以数字为准，没有数字才按名字猜。
            Optional<Resolved> byName = fuzzy(projectId, raw);
            if (byName.isPresent()) {
                return byName;
            }
        } catch (Exception e) {
            log.debug("[mapnav] 目标引用解析失败：{} : {}", raw, e.getMessage());
        }
        // 解析不到就说清楚：这条 INFO 是「模型说了去哪、人却没动」的唯一可统计信号
        log.info("[mapnav] 目标解析失败 raw={}（角色保持原位）", raw);
        return Optional.empty();
    }

    // ==================== 内部 ====================

    /**
     * 口语名模糊匹配：「去米行那边」→ 周家米行。
     *
     * <p><b>为什么必须有这一档</b>：本地模型给 target 的形态是「米行」「自家铺子」「河边」，
     * 而要素表里是「周家米行」「铁匠铺」。精确匹配等于把 AI 的意图全部丢掉——实测世界 143
     * 里 AI 说「去米行讨碗水喝」，人一步没动，感知、渲染、记忆却都当真。模糊命中后仍按
     * {@link #legality} 走可站格纠正，所以「猜错名字」不会把人送进墙里。</p>
     *
     * <p>评分：全名相等 100 ＞ 要素名包含关键词 80+关键词长度 ＞ 关键词包含要素名 60+要素名长度
     * ＞ 要素 code 包含关键词 40。同分取先出现者（要素按 sort_order，地图生成的顺序稳定）。</p>
     *
     * @param projectId 项目
     *
     * @param raw       原始引用
     *
     * @return 解析结果；匹配不到返回 empty
     */
    private Optional<Resolved> fuzzy(Long projectId, String raw) {
        // 候选核心词：整句剥壳（「去米行」→「米行」）+ 从句子里剥目的地（「顺路去米行讨碗水喝」→「米行」）
        // + 各自的方位后缀剥离变体。任何一条命中就够——多给几个候选比漏掉整句话便宜。
        List<String> keys = new java.util.ArrayList<>(4);
        addKey(keys, normalizeTarget(raw));
        addKey(keys, destinationText(raw));
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        ActorMapFeature best = null;
        int bestScore = 0;
        for (ActorMapFeature f : features(projectId)) {
            String name = normalizeTarget(f.getName());
            String code = f.getCode() == null ? "" : f.getCode().trim().toLowerCase(Locale.ROOT);
            int score = 0;
            for (String k : keys) {
                int s = 0;
                if (!name.isEmpty()) {
                    if (name.equals(k)) {
                        s = 100;
                    } else if (name.contains(k)) {
                        s = 80 + k.length();
                    } else if (k.contains(name)) {
                        s = 60 + name.length();
                    }
                }
                if (s == 0 && !code.isEmpty() && code.contains(k)) {
                    s = 40;
                }
                score = Math.max(score, s);
            }
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        if (best != null) {
            double[] p = spotOf(best);
            if (p != null) {
                log.info("[mapnav] 目标解析 raw={} → by={} code={} ({},{}) 分={}",
                        raw, BY_FEATURE_NAME, best.getCode(), (long) p[0], (long) p[1], bestScore);
                return Optional.of(legality(projectId, raw, best.getCode(), p[0], p[1], BY_FEATURE_NAME));
            }
        }
        if (locationRepository == null) {
            return Optional.empty();
        }
        List<ActorWorldLocation> locs = locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
        if (locs == null) {
            return Optional.empty();
        }
        for (ActorWorldLocation l : locs) {
            String name = normalizeTarget(l.getName());
            if (name.isEmpty() || !matchesAny(keys, name)) {
                continue;
            }
            if (l.getCenterX() != null && l.getCenterZ() != null) {
                log.info("[mapnav] 目标解析 raw={} → by={} name={} ({},{})",
                        raw, BY_LOCATION_FUZZY, l.getName(), (long) (double) l.getCenterX(), (long) (double) l.getCenterZ());
                return Optional.of(legality(projectId, raw, null,
                        l.getCenterX(), l.getCenterZ(), BY_LOCATION_FUZZY));
            }
        }
        return Optional.empty();
    }

    /**
     * 目标口语规范化：剥动词前缀、指代后缀、语气词与空白，得到能和要素名比对的核心词。
     *
     * <p>循环剥离而不是一次性剥离：「顺路去周家米行那边」要先摘「顺路去」再摘「那边」。</p>
     *
     * @param text 原始文本（可空）
     *
     * @return 规范化后的核心词（空串=全被剥掉）
     */
    static String normalizeTarget(String text) {
        if (text == null) {
            return "";
        }
        String s = text.trim().replaceAll("[\\s\"'“”‘’《》()（）\\[\\]]", "");
        for (int round = 0; round < 4; round++) {
            String before = s;
            for (String p : VERB_PREFIXES) {
                if (s.length() > p.length() && s.startsWith(p)) {
                    s = s.substring(p.length());
                }
            }
            for (String t : TAIL_WORDS) {
                if (s.length() > t.length() && s.endsWith(t)) {
                    s = s.substring(0, s.length() - t.length());
                }
            }
            while (s.length() > 2 && (s.endsWith("的") || s.endsWith("了"))) {
                s = s.substring(0, s.length() - 1);
            }
            if (s.equals(before)) {
                break;
            }
        }
        return s;
    }

    /**
     * 候选键是否与某个名字互相包含（双向：「米行」⊂「周家米行」、「周家米行」⊃「米行」）。
     *
     * @param keys 候选键表
     *
     * @param name 要素/地点名（已规范化）
     *
     * @return true=至少一个候选键命中
     */
    private static boolean matchesAny(List<String> keys, String name) {
        for (String k : keys) {
            if (name.contains(k) || k.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集一个候选核心词及其方位剥离变体（长度 < 2 的丢弃：单字地名匹配等于乱猜）。
     *
     * @param keys 候选表
     *
     * @param text 候选文本
     */
    private static void addKey(List<String> keys, String text) {
        if (text == null) {
            return;
        }
        String k = text.trim();
        if (k.length() >= 2 && !keys.contains(k)) {
            keys.add(k);
        }
        String trimmed = stripLocative(k);
        if (trimmed.length() >= 2 && !keys.contains(trimmed)) {
            keys.add(trimmed);
        }
    }

    /**
     * 剥方位/处所后缀：「广场上」→「广场」、「铁匠铺里」→「铁匠铺」。
     *
     * <p>只剥到长度 ≥ 2 为止：再剥就把「渡口」剥成「渡」了，宁可留一层也别造出单字地名。</p>
     *
     * @param text 核心词
     *
     * @return 去掉尾部方位字的核心词
     */
    static String stripLocative(String text) {
        String s = text == null ? "" : text;
        while (s.length() > 2) {
            char c = s.charAt(s.length() - 1);
            if ("上下里中边口外内前后".indexOf(c) < 0) {
                break;
            }
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 要素 code → 门坐标（无门用几何中心）。 */
    private Optional<Resolved> feature(Long projectId, String code, String label) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        ActorMapFeature hit = null;
        for (ActorMapFeature f : features(projectId)) {
            if (code.equalsIgnoreCase(String.valueOf(f.getCode()))) {
                hit = f;
                break;
            }
        }
        if (hit == null) {
            return Optional.empty();
        }
        double[] p = spotOf(hit);
        if (p == null) {
            return Optional.empty();
        }
        return Optional.of(legality(projectId, label, hit.getCode(), p[0], p[1], BY_FEATURE));
    }

    /** 地点名 → 区域中心（地点表没有坐标时按要素同名兜底）。 */
    private Optional<Resolved> location(Long projectId, String name, String label) {
        if (name == null || name.isBlank() || locationRepository == null) {
            return Optional.empty();
        }
        List<ActorWorldLocation> locs =
                locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
        if (locs == null || locs.isEmpty()) {
            return Optional.empty();
        }
        ActorWorldLocation hit = null;
        for (ActorWorldLocation l : locs) {
            if (l.getName() != null && l.getName().trim().equals(name)) {
                hit = l;
                break;
            }
        }
        if (hit == null) {
            for (ActorWorldLocation l : locs) {
                if (l.getName() != null && l.getName().contains(name)) {
                    hit = l;
                    break;
                }
            }
        }
        if (hit == null) {
            return Optional.empty();
        }
        if (hit.getCenterX() != null && hit.getCenterZ() != null) {
            return Optional.of(legality(projectId, label, null,
                    hit.getCenterX(), hit.getCenterZ(), BY_LOCATION));
        }
        // 地点表没有坐标：找同名要素
        for (ActorMapFeature f : features(projectId)) {
            if (f.getName() != null && f.getName().trim().equals(hit.getName())) {
                double[] p = spotOf(f);
                if (p != null) {
                    return Optional.of(legality(projectId, label, f.getCode(), p[0], p[1], BY_LOCATION));
                }
            }
        }
        return Optional.empty();
    }

    /** 角色引用 → 该角色此刻的位置（精确取权威，不做随机偏移）。 */
    private Optional<Resolved> npc(Long projectId, String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String full = ref.startsWith("npc:") ? ref : "npc:" + ref;
        com.holzyn.actor.domain.mapnav.vo.SpatialFix fix =
                spatialView.resolve(projectId, full).orElse(null);
        if (fix == null) {
            return Optional.empty();
        }
        Resolved r = legality(projectId, full, fix.getFeatureCode(), fix.getX(), fix.getZ(), BY_NPC);
        return Optional.of(r);
    }

    /** 广场：优先带 plaza 的要素，否则地图中心。 */
    private Resolved plaza(Long projectId) {
        for (ActorMapFeature f : features(projectId)) {
            String kind = f.getKind() == null ? "" : f.getKind().toLowerCase(Locale.ROOT);
            String code = f.getCode() == null ? "" : f.getCode().toLowerCase(Locale.ROOT);
            if (kind.contains("plaza") || code.contains("plaza") || code.contains("square")) {
                double[] p = spotOf(f);
                if (p != null) {
                    return legality(projectId, "plaza", f.getCode(), p[0], p[1], BY_PLAZA);
                }
            }
        }
        return legality(projectId, "plaza", null, MAP_SPAN_M / 2, MAP_SPAN_M / 2, BY_PLAZA);
    }

    /**
     * 合法性判定：先钳到图内，不可走则就近纠正。
     *
     * <p>纠正失败不抛异常——保留原坐标并标 {@code nearest_walkable}，让对账探针自己去发现
     * 「这个落点在水里」。静默改写坐标比坐标错更糟：错还能查，改写后连错都在哪都不知道。</p>
     */
    private Resolved legality(Long projectId, String label, String featureCode,
                              double x, double z, String resolvedBy) {
        Resolved r = new Resolved();
        r.setTargetRef(label);
        r.setFeatureCode(featureCode);
        double cx = Math.max(0, Math.min(MAP_SPAN_M, x));
        double cz = Math.max(0, Math.min(MAP_SPAN_M, z));
        // 越界即「被改写过」，无论后面还能不能寻路——先记账，避免早期 return 把痕迹吞掉
        r.setSnapped(cx != x || cz != z);
        r.setX(cx);
        r.setZ(cz);
        r.setResolvedBy(resolvedBy);
        if (navigationService == null) {
            return r;
        }
        try {
            if (navigationService.walkable(projectId, cx, cz)) {
                return r;
            }
            double[] near = navigationService.nearestWalkable(projectId, cx, cz, SNAP_RADIUS);
            if (near != null) {
                r.setX(near[0]);
                r.setZ(near[1]);
                r.setResolvedBy(BY_SNAPPED);
            }
            r.setSnapped(true);
        } catch (Exception e) {
            log.debug("[mapnav] 落点合法性判定失败（保留原坐标）：{}", e.getMessage());
        }
        return r;
    }

    /** 最新图的要素（无图返回空表）。 */
    private List<ActorMapFeature> features(Long projectId) {
        ActorWorldMap map = latestMap(projectId);
        if (map == null) {
            return List.of();
        }
        try {
            List<ActorMapFeature> rows = featureRepository.findByMapIdOrderBySortOrderAsc(map.getId());
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.debug("[mapnav] 读要素失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 最新图。 */
    private ActorWorldMap latestMap(Long projectId) {
        try {
            return mapRepository.findFirstByProjectIdOrderByIdDesc(projectId).orElse(null);
        } catch (Exception e) {
            log.debug("[mapnav] 读地图失败：{}", e.getMessage());
            return null;
        }
    }

    /** 要素落点：门优先，其次几何中心。 */
    private static double[] spotOf(ActorMapFeature f) {
        if (f == null) {
            return null;
        }
        if (f.getDoorX() != null && f.getDoorZ() != null) {
            return new double[]{f.getDoorX(), f.getDoorZ()};
        }
        if (f.getPosX() != null && f.getPosZ() != null) {
            return new double[]{f.getPosX(), f.getPosZ()};
        }
        return null;
    }

    /** 解析结果（对账/留痕用）。 */
    @Data
    public static class Resolved {

        /** 原始引用（或规范化后的 label） */
        private String targetRef;

        /** 落点 X（米） */
        private double x;

        /** 落点 Z（米） */
        private double z;

        /** 落点所属要素 code（可空） */
        private String featureCode;

        /** 解析来源（见本类 {@code BY_*} 常量） */
        private String resolvedBy;

        /** 是否经过钳制/就近纠正 */
        private boolean snapped;
    }
}
