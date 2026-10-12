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

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.MacroProperties;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 宏观社会演化 · 宏观感知上下文服务。
 * <p>职责：行为参数联动（设计 §六：宏观环境写入 NPC 感知上下文——粮价/局势/舆论，决策与
 * 对话可自然引用）：</p>
 * <ul>
 * <li><b>【宏观环境】段</b>：粮价（今日 X 文/石 + 相对基准的涨跌）·
 * 局势（影响力最高势力）· 舆论（强度最高议题）； 起按世界观模板语义渲染
 * （核心资源/计价单位/社区名——重构农耕硬编码）；</li>
 * <li><b>【生计压力】段</b>：粮价贵 → 百姓算计着吃/民怨/
 * 丰年·歉收·大旱·洪涝·暴雪等生计处境——普通 NPC 状态联动（程序化）之外的
 * 特殊 NPC 感知增强（AI 决策自然引用生计压力，行为像真人）。</li>
 * </ul>
 * <p>由 PerceptionService 在【世界动态】段后追加（决策/对话双轨均生效；只读宏观摘要，
 * 不替 NPC 做决定——世界层只提供「势」）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroContextService {

    private final MacroProperties props;
    private final MacroStateService macroStateService;
    private final ActorCharacterRepository characterRepository;

    /** 模板服务（注入；@Autowired(required=false) 保持构造器不变，null=回退默认仙侠模板） */
    @Autowired(required = false)
    private MacroTemplateService macroTemplateService;

    /** 角色名缓存（ref → 名称；进程内存态） */
    private final Map<String, String> nameCache = new ConcurrentHashMap<>();

    /** 项目模板（未装配回退默认模板）。 */
    private MacroTemplate templateOf(Long projectId) {
        if (macroTemplateService == null) {
            return MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID);
        }
        return macroTemplateService.forProject(projectId);
    }

    /**
     * 【宏观环境】段（决策/对话注入；无可注入内容返回空串）。
     *
     * @param projectId 项目 ID
     *
     * @return 段文本（空=跳过）
     */
    public String macroDynamicsSegment(Long projectId) {
        if (!props.isEnabled() || projectId == null) {
            return "";
        }
        try {
            MacroState st = macroStateService.stateOf(projectId);
            if (st == null || st.getEconomy() == null) {
                return "";
            }
            MacroTemplate tpl = templateOf(projectId);
            StringBuilder sb = new StringBuilder("【宏观环境】");
            int budget = 150;
            int used = 6;
            MacroState.Economy eco = st.getEconomy();
            double base = props.getInitial().getGrainPriceBase();
            double ratio = base > 0 ? eco.getGrainPrice() / base : 1.0;
            String priceLine = "\n· " + tpl.coreResource() + "价 " + eco.getGrainPrice()
                    + " " + tpl.priceUnit() + "（"
                    + (ratio >= 1.2 ? "较常年贵了不少" : ratio <= 0.85 ? "较常年便宜" : "与常年相仿")
                    + "）";
            if (used + priceLine.length() <= budget) {
                sb.append(priceLine);
                used += priceLine.length();
            }
            MacroState.Faction top = st.getFactions().values().stream()
                    .max(java.util.Comparator.comparingDouble(MacroState.Faction::getInfluence)).orElse(null);
            if (top != null) {
                String leader = leaderName(projectId, top);
                String line = "\n· " + tpl.communityName() + "中「" + top.getName() + "」最有权势"
                        + (leader == null ? "" : "（领袖" + leader + "）");
                if (used + line.length() <= budget) {
                    sb.append(line);
                    used += line.length();
                }
            }
            MacroState.Issue issue = st.getIssues().stream()
                    .max(java.util.Comparator.comparingDouble(MacroState.Issue::getIntensity)).orElse(null);
            if (issue != null && issue.getIntensity() >= 0.5) {
                String line = "\n· " + tpl.communityName() + "上下都在议论「" + issue.getTopic() + "」";
                if (used + line.length() <= budget) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[宏观] 【宏观环境】段组装失败（跳过）: project={} : {}", projectId, e.getMessage());
            return "";
        }
    }

    /**
     * 【生计压力】段（行为参数联动 v2：特殊 NPC 感知增强——粮价/民怨/收成/极端天气的
     * 生计处境描述；≤150 字；无可注入返回空串）。
     *
     * @param projectId 项目 ID
     *
     * @return 段文本（空=跳过）
     */
    public String livelihoodSegment(Long projectId) {
        if (!props.isEnabled() || projectId == null) {
            return "";
        }
        try {
            MacroState st = macroStateService.stateOf(projectId);
            if (st == null || st.getEconomy() == null) {
                return "";
            }
            MacroTemplate tpl = templateOf(projectId);
            StringBuilder sb = new StringBuilder("【生计压力】");
            int budget = 150;
            int used = 6;
            MacroState.Economy eco = st.getEconomy();
            double base = props.getInitial().getGrainPriceBase();
            double ratio = base > 0 ? eco.getGrainPrice() / base : 1.0;
            // 粮价 → 生计处境（核心：像真人一样为吃饭发愁/松快）
            String line;
            if (ratio >= 1.5) {
                line = "\n· " + tpl.coreResource() + "价贵得离谱，寻常人家都要算计着吃，顿顿不敢放开";
            } else if (ratio >= 1.2) {
                line = "\n· " + tpl.coreResource() + "价偏贵，家中用度紧巴了些";
            } else if (ratio <= 0.85) {
                line = "\n· " + tpl.coreResource() + "价便宜，日子过得松快";
            } else {
                line = "\n· 米价平稳，生计如常";
            }
            if (used + line.length() <= budget) {
                sb.append(line);
                used += line.length();
            }
            // 民怨（民生不满）
            if (st.getGrievance() != null && st.getGrievance() >= 60) {
                String g = "\n· 村里怨气不小，街巷间常有人叹气";
                if (used + g.length() <= budget) {
                    sb.append(g);
                    used += g.length();
                }
            }
            // 收成/极端天气（季节链处境）
            if (eco.getSeason() != null) {
                String seasonText = SeasonService.seasonDisplayName(eco.getSeason());
                String w = eco.getWeatherCondition() == null ? "" : "，" + eco.getWeatherCondition();
                if (eco.getHarvestFactor() != null && eco.getHarvestFactor() < 0.7) {
                    String h = "\n· " + seasonText + "季收成差" + w + "，地里的收成让人发愁";
                    if (used + h.length() <= budget) {
                        sb.append(h);
                        used += h.length();
                    }
                } else if (eco.getHarvestOutcome() != null && eco.getHarvestOutcome() == 1) {
                    String h = "\n· 今季收成好，仓里有粮心里不慌";
                    if (used + h.length() <= budget) {
                        sb.append(h);
                        used += h.length();
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[宏观] 【生计压力】段组装失败（跳过）: project={} : {}", projectId, e.getMessage());
            return "";
        }
    }

    /** 领袖名称（leaderRef=npc:{id} → 角色名；解析失败回退 ref 本身）。 */
    private String leaderName(Long projectId, MacroState.Faction top) {
        String ref = top.getLeaderRef();
        if (ref == null) {
            return null;
        }
        String cached = nameCache.get(ref);
        if (cached != null) {
            return cached;
        }
        try {
            // ：npc:{id} 不再编码类型，此处按意图 A 处理（引用能否解析成 actor_profile 行——
            // 只要主表有行就读名，special/common 都成立；解析失败保持原「回退 ref 本身」语义）
            Long id = NpcRefs.idOf(ref);
            if (id != null) {
                ActorCharacter ch = characterRepository.findById(id).orElse(null);
                if (ch != null && ch.getName() != null) {
                    nameCache.put(ref, ch.getName());
                    return ch.getName();
                }
            }
            nameCache.put(ref, ref);
            return ref;
        } catch (Exception e) {
            return ref;
        }
    }
}
