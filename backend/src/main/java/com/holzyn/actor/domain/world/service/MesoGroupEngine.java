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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroDelta;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorMesoGroupRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoMembershipRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroDeltaRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宏观社会演化 · 中观组引擎。
 * <p>职责：中观组生命周期——<b>三源通用初始化</b>（/：禁止硬编码现有世界，
 * 通用能力优先；测试世界势力名不作为任何代码常量）+ 自发成组 + 势力派生 + 演化写回：</p>
 * <ul>
 * <li><b>源① 世界观文本通用解析</b>（{@link #parseLore}）：从世界观设定文本（势力/历史/文化/
 * 自由文本）按组织性名词后缀（宗族/氏族/商帮/帮派/教派/门派/医馆/学院/家族/世家…）与
 * 序号标记（①②③ / 1. 等）提取组织种子行——通用解析器，可单测；</li>
 * <li><b>源② 模板语义骨架</b>（MacroTemplate.groupTemplates）：世界观文本无组织时按模板
 * （ancient/xianxia/wuxia/modern/future）通用命名骨架建组（名称/类型/标签/初始影响力）；</li>
 * <li><b>源③ 运行时自发成组</b>（{@link #scanEmergentGroups}）：共同行为聚集（同行为类 ≥3 名
 * 不同角色，窗口内）→ 自发建组（type=adhoc，如「囤积群体」）—— 涌现检测的
 * 结构侧输出；</li>
 * <li><b>首领通用解析</b>（{@link #resolveLeader}）：全名命中 → 组名「X氏」姓匹配 →
 * 组关键词邻近「X氏」姓匹配（如「巫祝白氏」→白灵犀、「游侠陆氏」→陆沉舟），
 * 解析失败 null=背景组织（无首领）；</li>
 * <li><b>势力派生</b>（{@link #deriveFactions}）：type≠adhoc 的 active 组 → MacroState.factions
 * （name/leaderRef/influence/memberCount/relations）——MacroFactionEngine 输入改由本方法
 * 派生（去硬编码）；<b>演化写回</b>（{@link #writeBackFactions}）：影响力/关系分回写
 * actor_meso_group（influence 列 + meta_json.relations），持久化可跨重启。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MesoGroupEngine {

    /** 组织性名词后缀 → 组织类型（通用解析；未知后缀按社区处理） */
    static final Map<String, String> SUFFIX_TYPE = suffixTypeMap();

    /** 序号标记（中文圈号/数字）——世界观文本组织段切分 */
    private static final String MARKERS = "①②③④⑤⑥⑦⑧⑨⑩";

    private final ActorMesoGroupRepository groupRepository;
    private final ActorMesoMembershipRepository membershipRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final ActorWorldMacroDeltaRepository deltaRepository;
    private final WorldClockService worldClockService;
    private final ObjectMapper objectMapper;

    /** 解析出的组织种子（名/类型/标签/影响力/描述段） */
    record GroupSeed(String name, String type, List<String> tags, double influence, String chunk) {
    }

    // ==================== 三源初始化（幂等：已有组跳过） ====================

    /**
     * 初始建组（源①→源② 顺序；幂等——项目已有组则直接派生势力）。
     *
     * @param projectId 项目 ID
     *
     * @param tpl 项目世界观模板（骨架兜底）
     *
     * @return 派生出的势力集（MacroState.factions 填充用；空=无组）
     */
    public Map<String, MacroState.Faction> seedInitialGroups(Long projectId, MacroTemplate tpl) {
        if (projectId == null) {
            return Map.of();
        }
        if (groupRepository.countByProjectId(projectId) > 0) {
            return deriveFactions(projectId);
        }
        List<ActorCharacter> chars = charactersOf(projectId);
        String lore = worldSettingText(projectId);
        List<GroupSeed> seeds = parseLore(lore);
        String origin = "lore";
        if (seeds.isEmpty()) {
            seeds = templateSeeds(tpl);
            origin = "template";
        }
        if (seeds.isEmpty()) {
            log.info("[中观] 无组织来源（世界观文本无组织 + 模板无骨架），跳过建组: project={}", projectId);
            return Map.of();
        }
        Long gameSecond = currentGameSecond(projectId);
        int n = 0;
        for (GroupSeed s : seeds) {
            ActorMesoGroup g = new ActorMesoGroup();
            g.setProjectId(projectId);
            g.setName(abbrev(s.name(), 100));
            g.setType(abbrev(s.type(), 30));
            g.setStatus("active");
            g.setInfluence(clamp(s.influence(), 0.0, 100.0));
            g.setOrigin(origin);
            g.setTagsJson(toJson(s.tags()));
            g.setMetaJson(toJson(Map.of("relations", new LinkedHashMap<String, Integer>())));
            g.setCreatedGameSecond(gameSecond);
            g.setLeaderRef(resolveLeader(projectId, lore, s, chars));
            groupRepository.save(g);
            // 成员：首领（role=leader）+ 普通 NPC 确定性轮派（member）
            if (g.getLeaderRef() != null) {
                addMembership(projectId, g.getId(), g.getLeaderRef(), "leader", gameSecond);
            }
            assignCrowdMembers(projectId, g.getId(), n++, seeds.size(), gameSecond);
        }
        log.info("[中观] 三源建组完成: project={} 来源={} 组数={}（{}）", projectId, origin, seeds.size(),
                seeds.stream().map(GroupSeed::name).toList());
        return deriveFactions(projectId);
    }

    // ==================== 源① 世界观文本通用解析 ====================

    /**
     * 世界观设定文本（最新版本多字段拼接：名称/题材/时代/地理/势力/力量体系/文化/历史/自由文本）。
     */
    public String worldSettingText(Long projectId) {
        if (projectId == null || worldSettingRepository == null) {
            return "";
        }
        try {
            ActorWorldSetting s = worldSettingRepository
                    .findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
            if (s == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            append(sb, s.getName());
            append(sb, s.getGenre());
            append(sb, s.getEra());
            append(sb, s.getGeography());
            append(sb, s.getFactions());
            append(sb, s.getMagicSystem());
            append(sb, s.getCulture());
            append(sb, s.getHistory());
            append(sb, s.getFreeText());
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 拼接设定段（空段跳过）。 */
    private static void append(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(part);
        }
    }

    /**
     * 通用组织解析器：按序号标记（①②③ / 1. / (1)）切段 → 段内按组织性名词后缀提取
     * 「名称+后缀」组织种子（可单测；无组织返回空列表）。
     *
     * @param lore 世界观设定全文（可空）
     *
     * @return 解析出的组织种子（名称去序号/编号/分隔符；描述段保留供首领解析）
     */
    public List<GroupSeed> parseLore(String lore) {
        List<GroupSeed> out = new ArrayList<>();
        if (lore == null || lore.isBlank()) {
            return out;
        }
        for (String seg : splitSegments(lore)) {
            String s = seg.trim();
            if (s.isEmpty()) {
                continue;
            }
            // 提取「名称 + 组织后缀」：名称=后缀前 ≤8 字符（去序号/编号/标点/空白）；
            // 多后缀取「最早出现位置」优先（同位置取更长后缀）
            String found = null;
            int bestIdx = Integer.MAX_VALUE;
            int bestLen = -1;
            for (Map.Entry<String, String> e : SUFFIX_TYPE.entrySet()) {
                int idx = s.indexOf(e.getKey());
                if (idx < 0) {
                    continue;
                }
                // 名称必须位于段首附近（组织段「① 宗族：…」——名称是段首成分）；
                // 若候选名不在段前 1/3 内且无序号前缀则跳过（防描述文本误提取）
                if (idx > 6 && !startsWithMarker(s)) {
                    continue;
                }
                if (idx < bestIdx || (idx == bestIdx && e.getKey().length() > bestLen)) {
                    bestIdx = idx;
                    bestLen = e.getKey().length();
                    found = e.getKey();
                }
            }
            if (found == null) {
                continue;
            }
            String candidate = candidateName(s, bestIdx);
            if (candidate == null) {
                continue;
            }
            String type = suffixTypeOf(candidate, s);
            if (out.stream().anyMatch(o -> o.name().equals(candidate))) {
                continue; // 名称去重（同一组织多段提及）
            }
            out.add(new GroupSeed(candidate, type, tagsOf(type), initialInfluence(type), s));
        }
        return out;
    }

    /** 段切分（序号标记 ①②③ / 数字序号 1. 2. / 顿号分号；按序号标记优先）。 */
    static List<String> splitSegments(String lore) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < lore.length(); i++) {
            char c = lore.charAt(i);
            if (MARKERS.indexOf(c) >= 0 || (c >= '0' && c <= '9' && i + 1 < lore.length()
                    && (lore.charAt(i + 1) == '.' || lore.charAt(i + 1) == '、')
                    && (i == 0 || "；;。 ".indexOf(lore.charAt(i - 1)) >= 0))) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur = new StringBuilder();
                }
                // 跳过序号本身（① / 1. / 1、）
                cur.append(c);
                if (c >= '0' && c <= '9' && i + 1 < lore.length()
                        && (lore.charAt(i + 1) == '.' || lore.charAt(i + 1) == '、')) {
                    cur.append(lore.charAt(i + 1));
                    i++;
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 候选组织名：后缀前 ≤8 字符（去序号/编号/冒号/顿号/空白）。 */
    static String candidateName(String seg, int suffixIdx) {
        int start = suffixIdx;
        int len = 0;
        while (start > 0 && len < 8) {
            char c = seg.charAt(start - 1);
            if (c == '：' || c == ':' || c == '、' || c == '，' || c == ',' || c == '；' || c == ';'
                    || c == '。' || Character.isWhitespace(c) || MARKERS.indexOf(c) >= 0) {
                break;
            }
            start--;
            len++;
        }
        if (start == suffixIdx) {
            return null;
        }
        String name = seg.substring(start, suffixIdx) + seg.substring(suffixIdx, suffixIdx + suffixLen(seg, suffixIdx));
        return name.trim();
    }

    /** 后缀实际长度（匹配多字后缀如「商帮」=2）。 */
    private static int suffixLen(String seg, int suffixIdx) {
        for (Map.Entry<String, String> e : SUFFIX_TYPE.entrySet()) {
            if (seg.startsWith(e.getKey(), suffixIdx)) {
                return e.getKey().length();
            }
        }
        return 1;
    }

    /** 段首是否以序号标记开头（组织段特征）。 */
    static boolean startsWithMarker(String seg) {
        String s = seg.trim();
        if (s.isEmpty()) {
            return false;
        }
        char c = s.charAt(0);
        if (MARKERS.indexOf(c) >= 0) {
            return true;
        }
        if (c >= '0' && c <= '9') {
            return true;
        }
        return false;
    }

    /** 组织名 → 类型（后缀查表；未知回退 community）。 */
    static String suffixTypeOf(String name, String chunk) {
        for (Map.Entry<String, String> e : SUFFIX_TYPE.entrySet()) {
            if (name.contains(e.getKey())) {
                return e.getValue();
            }
        }
        // 散人/游侠/精怪等无后缀组织名 → community
        if (name.contains("散人") || name.contains("游侠") || name.contains("精怪")
                || name.contains("野魅") || name.contains("异族")) {
            return "community";
        }
        return "community";
    }

    /** 组织类型 → 通用标签。 */
    static List<String> tagsOf(String type) {
        return switch (type) {
            case "family" -> List.of("宗族", "村务");
            case "cult" -> List.of("香火", "信仰");
            case "sect" -> List.of("武学", "门规");
            case "guild" -> List.of("商贸", "行会");
            default -> List.of("民生", "社区");
        };
    }

    /** 组织类型 → 初始影响力（解析无数值时的通用基线；0-100）。 */
    static double initialInfluence(String type) {
        return switch (type) {
            case "family" -> 55.0;
            case "cult" -> 50.0;
            case "sect" -> 50.0;
            case "guild" -> 35.0;
            default -> 30.0;
        };
    }

    /** 后缀 → 类型映射（组织性名词后缀；通用不限定任何世界）。 */
    private static Map<String, String> suffixTypeMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宗族", "family");
        m.put("氏族", "family");
        m.put("家族", "family");
        m.put("世家", "family");
        m.put("一系", "family");
        m.put("一脉", "family");
        m.put("商帮", "guild");
        m.put("帮会", "guild");
        m.put("帮派", "guild");
        m.put("商会", "guild");
        m.put("行会", "guild");
        m.put("公会", "guild");
        m.put("集团", "guild");
        m.put("公司", "guild");
        m.put("商行", "guild");
        m.put("教派", "cult");
        m.put("教会", "cult");
        m.put("门派", "sect");
        m.put("医馆", "guild");
        m.put("学院", "community");
        m.put("学府", "community");
        m.put("机构", "community");
        m.put("山寨", "community");
        m.put("部落", "community");
        m.put("散人", "community");
        m.put("精怪", "community");
        m.put("野魅", "community");
        m.put("隐士", "community");
        return m;
    }

    // ==================== 源② 模板骨架 ====================

    /** 模板骨架种子（世界观文本无组织时兜底；名称=模板语义通用命名非硬编码世界）。 */
    List<GroupSeed> templateSeeds(MacroTemplate tpl) {
        List<GroupSeed> out = new ArrayList<>();
        if (tpl == null || tpl.groupTemplates() == null) {
            return out;
        }
        for (MacroTemplate.GroupTemplate g : tpl.groupTemplates()) {
            out.add(new GroupSeed(g.name(), g.type(),
                    g.tags() == null ? tagsOf(g.type()) : g.tags(), g.influence(), ""));
        }
        return out;
    }

    // ==================== 首领通用解析 ====================

    /**
     * 首领通用解析（三级：全名命中 → 组名「X氏」姓匹配 → 组关键词邻近「X氏」姓匹配；
     * 解析失败 null=背景组织）。
     */
    String resolveLeader(Long projectId, String lore, GroupSeed seed, List<ActorCharacter> chars) {
        if (chars == null || chars.isEmpty()) {
            return null;
        }
        String name = seed.name() == null ? "" : seed.name();
        String chunk = seed.chunk() == null ? "" : seed.chunk();
        String full = (name + " " + chunk).trim();
        // ① 全名命中（描述段直接点名）
        for (ActorCharacter ch : chars) {
            if (ch.getName() != null && full.contains(ch.getName())) {
                return com.holzyn.actor.common.NpcRefs.of(ch.getId());
            }
        }
        // ② 组名「X氏」姓匹配（王氏宗族 → 王姓角色）
        for (ActorCharacter ch : chars) {
            String sur = surname(ch.getName());
            if (sur != null && name.contains(sur + "氏")) {
                return com.holzyn.actor.common.NpcRefs.of(ch.getId());
            }
        }
        // ③ 组关键词邻近「X氏」姓匹配（「庙宇祭司一系」→ 文中「祭司李氏一脉迁入」→ 李姓角色；
        // 多关键词候选：核心名 → 核心名末 2 字[≥4 字时]——描述与历史文本措辞可能不同）
        if (!lore.isBlank()) {
            String core = name;
            for (String suf : SUFFIX_TYPE.keySet()) {
                core = core.replace(suf, "");
            }
            List<String> kws = new ArrayList<>();
            if (core.length() >= 2) {
                kws.add(core);
                if (core.length() >= 4) {
                    kws.add(core.substring(core.length() - 2));
                }
            }
            for (String kw : kws) {
                for (ActorCharacter ch : chars) {
                    String sur = surname(ch.getName());
                    if (sur == null) {
                        continue;
                    }
                    int kwIdx = lore.indexOf(kw);
                    while (kwIdx >= 0 && kwIdx < lore.length()) {
                        int surnameIdx = lore.indexOf(sur + "氏", Math.max(0, kwIdx - 10));
                        if (surnameIdx >= 0 && surnameIdx <= kwIdx + 12) {
                            return com.holzyn.actor.common.NpcRefs.of(ch.getId());
                        }
                        kwIdx = lore.indexOf(kw, kwIdx + kw.length());
                    }
                }
            }
        }
        return null;
    }

    /** 角色姓（首字；空名返回 null）。 */
    static String surname(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.substring(0, 1);
    }

    // ==================== 成员装配 ====================

    /** 幂等成员装配（uk group+actor）。 */
    void addMembership(Long projectId, Long groupId, String actorRef, String role, Long gameSecond) {
        try {
            if (membershipRepository.findByGroupIdAndActorRef(groupId, actorRef).isPresent()) {
                return;
            }
            ActorMesoMembership m = new ActorMesoMembership();
            m.setProjectId(projectId);
            m.setGroupId(groupId);
            m.setActorId(actorIdOf(actorRef));
            m.setActorRef(actorRef);
            m.setRole(role);
            m.setInfluence(0.0);
            m.setJoinedGameSecond(gameSecond);
            m.setStatus("active");
            membershipRepository.save(m);
        } catch (Exception e) {
            log.warn("[中观] 成员装配失败（隔离继续）: project={} group={} ref={} : {}", projectId, groupId, actorRef, e.getMessage());
        }
    }

    /** 普通 NPC 确定性轮派入组（成员规模基数；id 取模轮派——同一项目多次初始化结果一致）。 */
    void assignCrowdMembers(Long projectId, Long groupId, int groupIndex, int groupCount, Long gameSecond) {
        if (groupCount <= 0) {
            return;
        }
        try {
            List<ActorCharacter> npcs = ordinaryNpcRepository.findByProjectIdOrderByIdAsc(projectId);
            for (ActorCharacter npc : npcs) {
                if (npc.getId() == null || npc.getId() % groupCount != groupIndex) {
                    continue;
                }
                addMembership(projectId, groupId, com.holzyn.actor.common.NpcRefs.of(npc.getId()), "member", gameSecond);
            }
        } catch (Exception e) {
            log.warn("[中观] 普通 NPC 轮派入组失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    /** actorRef → actor 表主键（special:{id} / crowd:{id}；解析失败 0）。 */
    static long actorIdOf(String actorRef) {
        if (actorRef == null) {
            return 0L;
        }
        int idx = actorRef.indexOf(':');
        if (idx < 0) {
            return 0L;
        }
        try {
            return Long.parseLong(actorRef.substring(idx + 1).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    // ==================== 源③ 运行时自发成组 ====================

    /**
     * 自发成组扫描（每日 tick 挂钩）：窗口内同行为类 ≥3 名不同角色的聚集 → 自发建组
     * （type=adhoc，名称「{行为显示名}群体」；已存在则扩组——补入新参与成员）。
     *
     * @param projectId 项目 ID
     *
     * @param toDay 扫描截止日（含）
     *
     * @param windowDays 窗口（默认 1）
     *
     * @param gameSecond 当前游戏秒
     */
    public void scanEmergentGroups(Long projectId, Long toDay, int windowDays, Long gameSecond) {
        if (projectId == null || toDay == null) {
            return;
        }
        try {
            long from = Math.max(0, toDay - windowDays);
            List<ActorWorldMacroDelta> rows = deltaRepository
                    .findByProjectIdAndGameDayBetweenOrderByGameDayAsc(projectId, from, toDay);
            Map<String, Map<String, ActorWorldMacroDelta>> byClass = new LinkedHashMap<>();
            for (ActorWorldMacroDelta d : rows) {
                if ("emergence".equals(d.getSource())) {
                    continue; // 涌现叠加入账不参与自发成组（防自举）
                }
                String cls = MacroBehaviorClass.classify(d.getActionType(), d.getActionText(), d.getIndicator());
                if (cls == null) {
                    continue;
                }
                byClass.computeIfAbsent(cls, k -> new LinkedHashMap<>())
                        .putIfAbsent(d.getNpcRef(), d);
            }
            for (Map.Entry<String, Map<String, ActorWorldMacroDelta>> e : byClass.entrySet()) {
                Map<String, ActorWorldMacroDelta> actors = e.getValue();
                if (actors.size() < 3) {
                    continue;
                }
                ensureAdhocGroup(projectId, e.getKey(), actors, gameSecond);
            }
        } catch (Exception ex) {
            log.warn("[中观] 自发成组扫描失败（隔离继续）: project={} : {}", projectId, ex.getMessage());
        }
    }

    /** 自发组幂等建组/扩组。 */
    void ensureAdhocGroup(Long projectId, String behaviorClass, Map<String, ActorWorldMacroDelta> actors,
                          Long gameSecond) {
        try {
            String name = abbrev(MacroBehaviorClass.displayOf(behaviorClass) + "群体", 100);
            ActorMesoGroup group = groupRepository.findByProjectIdAndName(projectId, name)
                    .filter(g -> "adhoc".equals(g.getType())).orElse(null);
            if (group == null) {
                group = new ActorMesoGroup();
                group.setProjectId(projectId);
                group.setName(name);
                group.setType("adhoc");
                group.setStatus("active");
                group.setInfluence(10.0);
                group.setOrigin("emergent");
                group.setTagsJson(toJson(List.of(MacroBehaviorClass.displayOf(behaviorClass))));
                group.setMetaJson(toJson(Map.of("behaviorClass", behaviorClass,
                        "relations", new LinkedHashMap<String, Integer>())));
                group.setCreatedGameSecond(gameSecond);
                groupRepository.save(group);
                log.info("[中观] 自发成组: project={} 组={} 成员={}（行为类={}）",
                        projectId, name, actors.size(), behaviorClass);
            }
            for (Map.Entry<String, ActorWorldMacroDelta> a : actors.entrySet()) {
                addMembership(projectId, group.getId(), a.getKey(), "member", gameSecond);
            }
        } catch (Exception e) {
            log.warn("[中观] 自发组装配失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    // ==================== 势力派生与写回（去硬编码） ====================

    /**
     * 势力派生：type≠adhoc 的 active 组 → MacroState.factions（name/leaderRef/influence/
     * memberCount/relations）——MacroFactionEngine 输入（tickFaction 每旬调用刷新）。
     */
    public Map<String, MacroState.Faction> deriveFactions(Long projectId) {
        Map<String, MacroState.Faction> out = new LinkedHashMap<>();
        if (projectId == null) {
            return out;
        }
        try {
            for (ActorMesoGroup g : groupRepository.findByProjectIdAndStatusOrderByIdAsc(projectId, "active")) {
                if ("adhoc".equals(g.getType())) {
                    continue;
                }
                MacroState.Faction f = new MacroState.Faction();
                f.setName(g.getName());
                f.setLeaderRef(g.getLeaderRef());
                f.setInfluence(g.getInfluence() == null ? 0.0 : g.getInfluence());
                f.setMemberCount(membershipRepository.countByGroupIdAndStatus(g.getId(), "active"));
                f.setRelations(parseRelations(g.getMetaJson()));
                out.put(g.getName(), f);
            }
        } catch (Exception e) {
            log.warn("[中观] 势力派生失败（返回已得部分）: project={} : {}", projectId, e.getMessage());
        }
        return out;
    }

    /**
     * 势力演化写回：MacroFactionEngine 演进后的影响力/关系分 → actor_meso_group
     * （influence 列 + meta_json.relations——持久化，跨重启/快照恢复后仍一致）。
     */
    public void writeBackFactions(Long projectId, Map<String, MacroState.Faction> factions) {
        if (projectId == null || factions == null) {
            return;
        }
        try {
            for (MacroState.Faction f : factions.values()) {
                groupRepository.findByProjectIdAndName(projectId, f.getName()).ifPresent(g -> {
                    g.setInfluence(f.getInfluence());
                    g.setMetaJson(mergeRelations(g.getMetaJson(), f.getRelations()));
                    groupRepository.save(g);
                });
            }
        } catch (Exception e) {
            log.warn("[中观] 势力写回失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    /** meta_json 关系分解析（缺失返回空 Map）。 */
    @SuppressWarnings("unchecked")
    Map<String, Integer> parseRelations(String metaJson) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (metaJson == null || metaJson.isBlank()) {
            return out;
        }
        try {
            tools.jackson.databind.JsonNode root = objectMapper.readTree(metaJson);
            tools.jackson.databind.JsonNode rel = root.path("relations");
            if (rel.isObject()) {
                rel.properties().forEach(e -> {
                    if (e.getValue().isNumber()) {
                        out.put(e.getKey(), e.getValue().asInt());
                    }
                });
            }
        } catch (Exception ignored) {
            // 解析失败返回空
        }
        return out;
    }

    /** meta_json 关系分合并（保留既有键，更新传入键）。 */
    String mergeRelations(String metaJson, Map<String, Integer> relations) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            if (metaJson != null && !metaJson.isBlank()) {
                tools.jackson.databind.JsonNode root = objectMapper.readTree(metaJson);
                if (root.isObject()) {
                    meta = objectMapper.convertValue(root, Map.class);
                }
            }
            Map<String, Integer> rel = parseRelations(metaJson);
            if (relations != null) {
                rel.putAll(relations);
            }
            meta.put("relations", rel);
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return metaJson;
        }
    }

    // ==================== 辅助 ====================

    /** 当前引擎推进游戏秒（缺失 0）。 */
    Long currentGameSecond(Long projectId) {
        try {
            Long sec = worldClockService.currentEngineGameSecond(projectId);
            return sec == null ? 0L : sec;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 项目特殊角色（未删除升序）。 */
    List<ActorCharacter> charactersOf(Long projectId) {
        try {
            return characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 对象 → JSON（失败 null）。 */
    String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** 文本截断。 */
    static String abbrev(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    /** 钳制。 */
    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
