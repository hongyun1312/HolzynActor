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

package com.holzyn.actor.domain.growth.persona;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.knowledge.service.CharacterKnowledgeCardSyncService;
import com.holzyn.actor.domain.npc.goal.entity.ActorCharacterGoal;
import com.holzyn.actor.domain.npc.goal.repository.ActorCharacterGoalRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 档案投影服务。
 * <p><b>职责</b>：把「权威在既有模块」的字段装配成 {@link PersonaRenderContext}，供
 * {@link PersonaRenderer} 在渲染时补齐档案缺省的维度。这是「档案消费既有模块数据、而不是另起一套平行数据」
 * 的落点——渲染链路上<b>没有任何字段被复制两份</b>。</p>
 * <p><b>投影来源对照</b>：</p>
 * <ul>
 * <li>① 身份与社会 → {@code actor_profile} 结构列（name/gender/race/sub_race/title/affiliation/
 * occupation/location/current_activity）</li>
 * <li>⑤ 价值观与动机（目标）→ {@code actor_goal}（长期 priority=2 / 中期 priority=4）</li>
 * <li>⑥ 能力与知识 → {@code actor_knowledge_doc}（知识边界文档）</li>
 * <li>⑧ 习惯与日常（日程）→ {@code actor_state.daily_plan_json}（计划模块产物）</li>
 * <li>⑩ 发育·遗传·谱系 → {@code actor_profile.birth_game_second/stage} + {@code actor_relation} 谱系边
 * + 世界时钟（年龄折算）</li>
 * <li>③ 生理基线 → {@code actor_state.physio_json}（权威在状态模块；投影给"生理基线"维渲染用）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaProjectionService {

    /** 角色主表仓库 */
    private final ActorCharacterRepository characterRepository;
    /** 目标仓库（⑤ 目标投影） */
    private final ActorCharacterGoalRepository goalRepository;
    /** 知识文档仓库（⑥ 知识领域/盲区投影） */
    private final ActorKnowledgeDocRepository knowledgeDocRepository;
    /** 关系仓库（⑩ 谱系投影） */
    private final ActorCharacterRelationRepository relationRepository;
    /** 状态仓库（③ 生理基线 / ⑧ 日程投影） */
    private final ActorNpcStateRepository stateRepository;
    /** 世界时钟（年龄折算；可空=未装配时跳过年龄派生） */
    private final WorldClockService worldClockService;

    /** 登录周期（游戏秒/年；与 holzyn.actor.growth.aging.game-seconds-per-year 对齐） */
    private static final long GAME_SECONDS_PER_YEAR = 31_536_000L;

    /**
     * 谱系关系类型 → 投影键。
     * <p>注：条目超过 10 组，故用 {@code Map.ofEntries}（{@code Map.of} 上限为 10 组键值对）。</p>
     */
    private static final Map<String, String> LINEAGE_KEYS = Map.ofEntries(
            Map.entry("FATHER", "father"), Map.entry("MOTHER", "mother"),
            Map.entry("PARENT_CHILD", "children"), Map.entry("SPOUSE", "spouse"),
            Map.entry("MARRIAGE", "spouse"), Map.entry("SIBLING", "siblings"),
            Map.entry("父", "father"), Map.entry("母", "mother"),
            Map.entry("配偶", "spouse"), Map.entry("子女", "children"),
            Map.entry("兄弟", "siblings"));

    /**
     * 装配某 NPC 的渲染投影上下文。
     *
     * @return 投影上下文（NPC 不存在时返回空上下文，不抛异常——零阻断）
     */
    @Transactional(readOnly = true)
    public PersonaRenderContext contextOf(Long profileId) {
        PersonaRenderContext ctx = new PersonaRenderContext();
        if (profileId == null) {
            return ctx;
        }
        ActorCharacter ch = characterRepository.findById(profileId).orElse(null);
        if (ch == null) {
            return ctx;
        }
        // ---------- ① 身份与社会：结构列 ----------
        ctx.setName(ch.getName());
        ctx.setGender(ch.getGender());
        ctx.setRace(ch.getRace());
        ctx.setSubRace(ch.getSubRace());
        ctx.setTitle(ch.getTitle());
        ctx.setAffiliation(ch.getAffiliation());
        ctx.setOccupation(ch.getOccupation());
        ctx.setLocation(ch.getLocation());
        ctx.setCurrentActivity(ch.getCurrentActivity());

        // ---------- ⑩ 发育·遗传·谱系：出生/阶段/年龄/谱系 ----------
        ctx.setStageLabel(stageLabel(ch.getStage()));
        fillAge(ctx, ch);
        fillLineage(ctx, ch.getProjectId(), profileId);

        // ---------- ⑤ 目标 ----------
        fillGoals(ctx, ch);

        // ---------- ⑥ 知识与盲区 ----------
        fillKnowledge(ctx, ch);

        // ---------- ③⑧ 状态模块投影（生理基线权威 + 日程） ----------
        ActorNpcState st = stateRepository.findByProjectIdAndNpcRef(ch.getProjectId(), NpcRefs.of(profileId))
                .orElse(null);
        if (st != null) {
            ctx.setSchedule(parseSchedule(st.getDailyPlanJson()));
            ctx.setPhysio(parsePhysio(st.getPhysioJson()));
        }
        return ctx;
    }

    //
    // 各维投影
    //

    /**
     * 年龄与出生标签派生。
     * <p>年龄 = (当前游戏秒 − 出生游戏秒) / 游戏年；出生标签由世界日历服务格式化（未装配时退化为相对描述）。</p>
     */
    private void fillAge(PersonaRenderContext ctx, ActorCharacter ch) {
        Long birth = ch.getBirthGameSecond();
        if (birth == null) {
            // 无出生时刻：退化为档案缓存列 age
            ctx.setAgeYears(ch.getAge());
            return;
        }
        Long now = currentGameSecond(ch.getProjectId());
        if (now == null) {
            ctx.setAgeYears(ch.getAge());
            return;
        }
        double years = (now - birth) / (double) GAME_SECONDS_PER_YEAR;
        if (years < 0) {
            years = 0;
        }
        ctx.setAgeYears((int) Math.floor(years));
        ctx.setBirthLabel("青禾历前 " + Math.max(0, (int) Math.round(GAME_SECONDS_PER_YEAR > 0
                ? Math.abs(birth) / (double) GAME_SECONDS_PER_YEAR : 0)) + " 年（世界纪元相对值）");
    }

    /**
     * 谱系投影：从 {@code actor_relation} 取该 NPC 的关系边，按关系类型归入父/母/配偶/子女/兄弟姐妹。
     * <p>权威在关系模块——档案里<b>不再复制</b>谱系文本。</p>
     */
    private void fillLineage(PersonaRenderContext ctx, Long projectId, Long profileId) {
        if (projectId == null) {
            return;
        }
        String selfRef = NpcRefs.of(profileId);
        List<ActorCharacterRelation> rows;
        try {
            rows = relationRepository.findByProjectId(projectId);
        } catch (RuntimeException e) {
            return;
        }
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (ActorCharacterRelation r : rows) {
            if (r == null) {
                continue;
            }
            boolean fromSelf = selfRef.equals(r.getFromRef());
            boolean toSelf = selfRef.equals(r.getToRef());
            if (!fromSelf && !toSelf) {
                continue;
            }
            String key = LINEAGE_KEYS.get(r.getRelationType() == null ? "" : r.getRelationType().toUpperCase());
            if (key == null) {
                // 也接受中文/自定义类型直接命中
                key = LINEAGE_KEYS.get(r.getRelationType() == null ? "" : r.getRelationType());
            }
            if (key == null) {
                continue;
            }
            String other = fromSelf ? r.getToName() : r.getFromName();
            if (other == null || other.isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(other);
        }
        Map<String, Object> lineage = new LinkedHashMap<>();
        grouped.forEach((k, v) -> {
            if ("children".equals(k) || "siblings".equals(k)) {
                lineage.put(k, v);
            } else {
                lineage.put(k, String.join("、", v));
            }
        });
        ctx.setLineage(lineage);
    }

    /** 目标投影：actor_goal 的中长期目标文本（权威在目标模块）。 */
    private void fillGoals(PersonaRenderContext ctx, ActorCharacter ch) {
        if (ch.getProjectId() == null || ch.getId() == null) {
            return;
        }
        List<ActorCharacterGoal> goals;
        try {
            goals = goalRepository.findByProjectIdAndAgentRefAndDeleted(ch.getProjectId(), NpcRefs.of(ch.getId()), 0);
        } catch (RuntimeException e) {
            return;
        }
        if (goals == null || goals.isEmpty()) {
            return;
        }
        List<String> life = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (ActorCharacterGoal g : goals) {
            if (g == null || g.getTitle() == null || "done".equalsIgnoreCase(g.getStatus())) {
                continue;
            }
            int prio = g.getPriority() == null ? 3 : g.getPriority();
            if (prio <= 2) {
                life.add(g.getTitle());
            } else {
                current.add(g.getTitle());
            }
        }
        ctx.setLifeGoals(life);
        ctx.setCurrentGoals(current);
    }

    /** 知识投影：知识模块文档 → 知识领域/盲区文本（权威在知识模块）。 */
    private void fillKnowledge(PersonaRenderContext ctx, ActorCharacter ch) {
        if (ch.getProjectId() == null || ch.getId() == null) {
            return;
        }
        List<ActorKnowledgeDoc> docs;
        try {
            docs = knowledgeDocRepository.findByProjectIdOrderByIdAsc(ch.getProjectId()).stream()
                    .filter(d -> d != null && ch.getId().equals(d.getCharacterId()))
                    .toList();
        } catch (RuntimeException e) {
            return;
        }
        if (docs == null || docs.isEmpty()) {
            return;
        }
        List<String> domains = new ArrayList<>();
        List<String> blind = new ArrayList<>();
        for (ActorKnowledgeDoc d : docs) {
            if (d == null || d.getTitle() == null) {
                continue;
            }
            // 人物知识卡（知识检索质量深化）不进领域/盲区投影——结构化卡是 RAG 检索用文档，
            // 若整卡文本投影进「知识领域」会污染角色渲染（S5 行变一长串），权威仍是知识领域/盲区两文档
            if (CharacterKnowledgeCardSyncService.TITLE_CARD.equals(d.getTitle())) {
                continue;
            }
            if (d.getTitle().contains("盲区") || d.getTitle().contains("不知")) {
                blind.add(d.getContent());
            } else {
                domains.add(d.getContent() == null ? d.getTitle() : d.getContent());
            }
        }
        ctx.setKnowledgeDomains(domains);
        ctx.setKnowledgeBlindSpots(blind);
    }

    //
    // 工具
    //

    /** 当前游戏秒（世界时钟未装配或异常时返回 null）。 */
    private Long currentGameSecond(Long projectId) {
        if (worldClockService == null || projectId == null) {
            return null;
        }
        try {
            return worldClockService.currentEngineGameSecond(projectId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 阶段码 → 中文标签。 */
    private static String stageLabel(String stage) {
        if (stage == null || stage.isBlank()) {
            return null;
        }
        return switch (stage) {
            case "INFANT" -> "婴儿期（INFANT）";
            case "TODDLER" -> "幼儿期（TODDLER）";
            case "CHILD" -> "学龄期（CHILD）";
            case "ADOLESCENT" -> "青少年期（ADOLESCENT）";
            case "YOUTH" -> "青年期（YOUTH）";
            case "PRIME" -> "壮年期（PRIME）";
            case "MIDDLE" -> "中年期（MIDDLE）";
            case "ELDER" -> "老年期（ELDER）";
            case "ANCIENT" -> "耄耋期（ANCIENT）";
            case "DECEASED" -> "已故（DECEASED）";
            default -> stage;
        };
    }

    /**
     * 解析 {@code actor_state.physio_json} 为档案 ③ 维投影（失败返回 null——该维整段跳过，不阻断渲染）。
     *
     * @param physioJson 状态模块中的生理参数 JSON
     *
     * @return ③ 维投影对象或 null
     */
    private static PersonaDims.Physio parsePhysio(String physioJson) {
        if (physioJson == null || physioJson.isBlank()) {
            return null;
        }
        try {
            return PersonaJson.mapper().readValue(physioJson, PersonaDims.Physio.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 解析当日计划 JSON 的条目文本（失败返回空列表）。 */
    @SuppressWarnings("unchecked")
    private static List<String> parseSchedule(String dailyPlanJson) {
        if (dailyPlanJson == null || dailyPlanJson.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> m = PersonaJson.mapper().readValue(dailyPlanJson, Map.class);
            Object items = m == null ? null : m.get("items");
            if (!(items instanceof List<?> list)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> item) {
                    Object hour = item.get("hour");
                    Object action = item.get("action");
                    if (action != null) {
                        out.add((hour == null ? "" : hour + "时 ") + action);
                    }
                }
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}