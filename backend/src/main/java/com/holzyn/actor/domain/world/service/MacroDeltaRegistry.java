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

import com.holzyn.actor.domain.world.entity.ActorMacroDeltaRule;
import com.holzyn.actor.domain.world.repository.ActorMacroDeltaRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 宏观社会演化 · 可扩展入账协议注册表。
 * <p>职责：把 的 MacroLedgerService 硬编码规则映射迁移为「DB 配置驱动」——行动类型/关键词
 * → 宏观指标折算，插行即入账、改行即生效（热更新：启动加载 + POST/PUT 写库后内存缓存失效
 * 重建，不重启生效； 「DB 配置驱动注册」）：</p>
 * <ul>
 * <li><b>种子</b>=现有 MacroLedgerService 硬编码映射表迁移（project_id=0 全局默认：
 * trade→economy 三指标 / work→产量或市集 / fight→势力[领袖归属] / preach·social→文化议题 /
 * treat→医术 / study→法阵；首次加载时幂等落库，其后以 DB 行为准可热改）；</li>
 * <li><b>匹配语义</b>：type 包含匹配 OR 关键词命中（custom 规则=纯关键词）；
 * 同一 actionType 下「有关键词命中的规则」优先于「纯类型规则」（work 农作→产量独占，
 * 避免与 generic work 双入账）；</li>
 * <li><b>占位符</b>：indicator 支持 {{faction}}（行动者所属势力）/ {{topIssue}}（最强议题），
 * 由 MacroLedgerService 在查找后解析（势力/议题为动态值，规则表存占位）；</li>
 * <li><b>项目覆盖</b>：project_id&gt;0 行按 (action_type, keywords, indicator) 签名覆盖全局行。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroDeltaRegistry {

    /** 全局默认项目 ID（模板种子） */
    public static final long GLOBAL_PROJECT = 0L;

    /** 占位符：行动者所属势力（faction 目标专用；由调用方解析） */
    public static final String PH_FACTION = "{{faction}}";
    /** 占位符：当前最强舆论议题（culture 目标专用；由调用方解析） */
    public static final String PH_TOP_ISSUE = "{{topIssue}}";

    private final ActorMacroDeltaRuleRepository ruleRepository;

    /** 种子已落库标记（进程级；防重复插入） */
    private final AtomicBoolean seeded = new AtomicBoolean(false);

    /** 内存缓存：projectId → 生效规则（全局+项目覆盖合并；写库后失效重建） */
    private final Map<Long, List<ActorMacroDeltaRule>> cache = new ConcurrentHashMap<>();

    /** 匹配结果条目 */
    public record Hit(String targetMacro, String indicator, double delta) {
    }

    // ==================== 种子定义 ====================

    /** 种子规则定义（actionType, keywords, targetMacro, indicator, delta） */
    record Seed(String actionType, String[] keywords, String targetMacro, String indicator, double delta) {
    }

    private static final String[] TRADE_KW = {"经商", "经营", "买卖", "售卖", "贩", "进货", "出货", "交易",
            "摆摊", "卖货", "米行", "商行", "铺子", "市集", "集市", "赶集", "货栈", "账房"};
    private static final String[] WORK_KW = {"打工", "劳作", "农作", "种田", "耕地", "插秧", "收割",
            "采药", "砍柴", "打渔", "织布", "打铁", "做工", "帮工", "干活"};
    private static final String[] FARM_KW = {"农作", "种田", "耕地", "插秧", "收割", "采药"};
    /** work 非农作关键词（WORK_KW − FARM_KW；与农作规则关键词集不相交 → 无双重命中） */
    private static final String[] WORK_GENERIC_KW = {"打工", "劳作", "砍柴", "打渔", "织布", "打铁", "做工", "帮工", "干活"};
    private static final String[] FIGHT_KW = {"参战", "武斗", "比试", "切磋", "打斗", "护卫", "剿匪"};
    private static final String[] PREACH_KW = {"布道", "祭祀", "祈福", "传道", "法事", "诵经", "祭山神"};
    private static final String[] SOCIAL_KW = {"社交", "聊天", "闲谈", "串门", "叙话", "唠嗑", "茶馆"};
    private static final String[] TREAT_KW = {"治疗", "行医", "诊脉", "煎药", "配药", "看诊", "问诊"};
    private static final String[] STUDY_KW = {"研习", "练功", "修炼", "读书", "学艺", "炼气", "研究"};
    /** 移动类关键词（M3：移动行为按文本入账——纯移动措辞，避免与 trade/treat 等业务词重叠） */
    private static final String[] MOVE_KW = {"赶路", "走路", "步行", "散步", "出行", "走在路上", "回家路上", "出门"};

    /** 种子规则表 */
    static final List<Seed> SEEDS = List.of(
            // trade → 经济三指标（type OR 关键词命中即全命中三行）
            new Seed("trade", TRADE_KW, "economy", "market_activity", 0.6),
            new Seed("trade", TRADE_KW, "economy", "currency_supply", 0.8),
            new Seed("trade", TRADE_KW, "economy", "tax_revenue", 0.2),
            // work：农作关键词命中 → 产量独占（关键词集不相交 → 不双入账）
            new Seed("work", FARM_KW, "economy", "grain_production", 0.5),
            new Seed("work", WORK_GENERIC_KW, "economy", "market_activity", 0.2),
            // fight → 行动者所属势力（{{faction}} 由调用方按领袖归属解析）
            new Seed("fight", FIGHT_KW, "faction", PH_FACTION, 1.2),
            // preach/social → 文化最强议题（{{topIssue}} 由调用方解析）
            new Seed("preach", PREACH_KW, "culture", PH_TOP_ISSUE, 0.05),
            new Seed("social", SOCIAL_KW, "culture", PH_TOP_ISSUE, 0.02),
            // treat → 医术科技累积
            new Seed("treat", TREAT_KW, "tech", "medicine", 0.05),
            // study → 法阵科技累积
            new Seed("study", STUDY_KW, "tech", "array", 0.03),
            // move（M3：移动行为按文本入账——走路/赶路/前往 → 交通流动入账）
            new Seed("move", MOVE_KW, "economy", "market_activity", 0.05)
    );

    // ==================== 种子落库 + 热更新 ====================

    /** 种子幂等落库（首次加载时；已存在全局规则则跳过——以 DB 行为准可热改）。 */
    private void ensureSeeded() {
        if (seeded.get() || !ruleRepository.findAllByOrderByIdAsc().isEmpty()) {
            seeded.set(true);
            return;
        }
        try {
            for (Seed s : SEEDS) {
                ActorMacroDeltaRule row = new ActorMacroDeltaRule();
                row.setProjectId(GLOBAL_PROJECT);
                row.setActionType(s.actionType());
                row.setKeywords(toKeywordsJson(s.keywords()));
                row.setTargetMacro(s.targetMacro());
                row.setIndicator(s.indicator());
                row.setDelta(s.delta());
                row.setWeight(1.0);
                ruleRepository.save(row);
            }
            seeded.set(true);
            log.info("[宏观] 可扩展入账规则种子已落库: {} 行（全局默认，插行即入账）", SEEDS.size());
        } catch (Exception e) {
            log.warn("[宏观] 入账规则种子落库失败（下次加载重试）: {}", e.getMessage());
        }
    }

    /** 热更新：写库后失效重建缓存（POST/PUT /macro/delta-rules 后调用；不重启生效）。 */
    public void invalidate(Long projectId) {
        if (projectId == null) {
            cache.clear();
        } else {
            cache.remove(projectId);
        }
    }

    /** 生效规则加载（全局 + 项目覆盖合并；项目行按 (type,keywords,indicator) 签名覆盖全局行）。 */
    private List<ActorMacroDeltaRule> effectiveRules(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        List<ActorMacroDeltaRule> cached = cache.get(projectId);
        if (cached != null) {
            return cached;
        }
        ensureSeeded();
        try {
            Map<String, ActorMacroDeltaRule> merged = new LinkedHashMap<>();
            for (ActorMacroDeltaRule r : ruleRepository.findByProjectIdOrderByIdAsc(GLOBAL_PROJECT)) {
                merged.put(signature(r), r);
            }
            for (ActorMacroDeltaRule r : ruleRepository.findByProjectIdOrderByIdAsc(projectId)) {
                merged.put(signature(r), r); // 项目行覆盖全局行
            }
            List<ActorMacroDeltaRule> list = merged.values().stream()
                    .filter(r -> !Boolean.FALSE.equals(r.getEnabled())).toList();
            cache.put(projectId, list);
            return list;
        } catch (Exception e) {
            log.warn("[宏观] 入账规则加载失败（回退种子纯函数）: project={} : {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /** 规则行签名（项目覆盖键）。 */
    private static String signature(ActorMacroDeltaRule r) {
        return (r.getActionType() == null ? "" : r.getActionType()) + "|"
                + (r.getKeywords() == null ? "" : r.getKeywords()) + "|"
                + (r.getIndicator() == null ? "" : r.getIndicator());
    }

    // ==================== 匹配（DB 驱动） ====================

    /**
     * 行动类型/文本 → 入账条目（DB 规则表驱动；projectId 项目覆盖 + 全局默认合并）。
     *
     * @param projectId 项目 ID
     *
     * @param actionText 行动文本（决策 action）
     *
     * @param actionType 行动类型（决策 type；可空）
     *
     * @return 命中条目（indicator 可能含 {{faction}}/{{topIssue}} 占位符，调用方解析；空=不入账）
     */
    public List<Hit> lookup(Long projectId, String actionText, String actionType) {
        List<ActorMacroDeltaRule> rules = effectiveRules(projectId);
        return match(rules, actionText, actionType);
    }

    /**
     * 纯种子匹配（无 DB 兜底——单测/未装配场景与 DB 驱动语义完全一致）。
     */
    public static List<Hit> seedLookup(String actionText, String actionType) {
        List<ActorMacroDeltaRule> rules = new ArrayList<>();
        for (Seed s : SEEDS) {
            ActorMacroDeltaRule r = new ActorMacroDeltaRule();
            r.setActionType(s.actionType());
            r.setKeywords(toKeywordsJson(s.keywords()));
            r.setTargetMacro(s.targetMacro());
            r.setIndicator(s.indicator());
            r.setDelta(s.delta());
            r.setEnabled(true);
            rules.add(r);
        }
        return match(rules, actionText, actionType);
    }

    /** 匹配核心（type 包含匹配 OR 关键词命中；同一 actionType 下关键词命中优先于纯类型规则）。 */
    static List<Hit> match(List<ActorMacroDeltaRule> rules, String actionText, String actionType) {
        List<Hit> out = new ArrayList<>();
        String type = actionType == null ? "" : actionType.trim().toLowerCase();
        String text = actionText == null ? "" : actionText;

        // 第一遍：记录每 actionType 是否有「关键词命中」的规则（用于抑制纯类型规则）
        Map<String, Boolean> kwHitByType = new LinkedHashMap<>();
        for (ActorMacroDeltaRule r : rules) {
            String t = r.getActionType() == null ? "" : r.getActionType().trim().toLowerCase();
            if (t.isEmpty() || "custom".equals(t)) {
                continue;
            }
            if (r.getKeywords() != null && !r.getKeywords().isBlank()
                    && keywordHit(r.getKeywords(), text)) {
                kwHitByType.put(t, true);
            }
        }
        // 第二遍：命中收集
        for (ActorMacroDeltaRule r : rules) {
            String t = r.getActionType() == null ? "" : r.getActionType().trim().toLowerCase();
            boolean kwHit = r.getKeywords() != null && !r.getKeywords().isBlank()
                    && keywordHit(r.getKeywords(), text);
            boolean typeHit = !t.isEmpty() && !"custom".equals(t) && !type.isEmpty() && type.contains(t);
            boolean hit;
            if ("custom".equals(t)) {
                hit = kwHit; // custom 规则=纯关键词
            } else if (t.isEmpty()) {
                hit = kwHit; // 空类型规则=纯关键词
            } else {
                hit = typeHit || kwHit;
                // 同一 actionType 下有关键词命中 → 抑制纯类型命中（work 农作→产量独占）
                if (hit && !kwHit && Boolean.TRUE.equals(kwHitByType.get(t))) {
                    hit = false;
                }
            }
            if (hit) {
                out.add(new Hit(r.getTargetMacro(), r.getIndicator(), r.getDelta() == null ? 0.0 : r.getDelta()));
            }
        }
        return out;
    }

    /** 关键词 JSON 数组命中判定（任一包含即命中）。 */
    static boolean keywordHit(String keywordsJson, String text) {
        List<String> kws = parseKeywords(keywordsJson);
        if (kws.isEmpty()) {
            return false;
        }
        for (String k : kws) {
            if (!k.isBlank() && text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 关键词 JSON 数组解析（["经商","经营"]；解析失败/空返回空列表）。 */
    static List<String> parseKeywords(String keywordsJson) {
        if (keywordsJson == null || keywordsJson.isBlank()) {
            return List.of();
        }
        String s = keywordsJson.trim();
        if (!s.startsWith("[")) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try {
            tools.jackson.databind.JsonNode arr = new tools.jackson.databind.ObjectMapper().readTree(s);
            if (arr.isArray()) {
                arr.forEach(n -> out.add(n.asText()));
            }
        } catch (Exception ignored) {
            // 非法 JSON 返回空
        }
        return out;
    }

    /** 关键词数组 → JSON 数组文本。 */
    static String toKeywordsJson(String[] keywords) {
        if (keywords == null || keywords.length == 0) {
            return null;
        }
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(keywords);
        } catch (Exception e) {
            return null;
        }
    }

    /** 列表读（调试面板/管理用：全局 + 项目行）。 */
    public List<ActorMacroDeltaRule> listRules(Long projectId) {
        ensureSeeded();
        List<ActorMacroDeltaRule> out = new ArrayList<>();
        try {
            out.addAll(ruleRepository.findByProjectIdOrderByIdAsc(GLOBAL_PROJECT));
            if (projectId != null && projectId != GLOBAL_PROJECT) {
                out.addAll(ruleRepository.findByProjectIdOrderByIdAsc(projectId));
            }
        } catch (Exception e) {
            log.warn("[宏观] 入账规则列表加载失败: {}", e.getMessage());
        }
        return out;
    }
}
