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

package com.holzyn.actor.domain.growth.stage;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.persona.PersonaDims;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.repository.ActorNpcStateRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段硬门控服务。
 * <p><b>职责</b>：</p>
 * <ul>
 * <li>启动 seed：{@code actor_growth_stage_gate} / {@code actor_growth_stage_constraint} 为空时
 * 写入 {@link GrowthStageDefaults} 默认行（表优先、代码兜底，热更新结果不被覆盖）；</li>
 * <li>{@link #gate}：按「阶段门控表 + 生理状态 + 体质弱点 + 道德底线」计算硬门控结果
 * （允许能力清单 / 禁止行为 / 软约束 / 机能曲线）；</li>
 * <li>{@link #capacityOf}：机能曲线（峰值 25~35 岁，其后线性 + 加速衰减）；</li>
 * <li>{@link #ageModifiers}：机能曲线 → 状态模块修正倍率（供 {@code actor_state.physio_coef_json}
 * 日级重算，衰老真正进入衰减计算）；</li>
 * <li>{@link #renderStageBlock}：发育阶段约束 Prompt 注入文案（【发育阶段约束】层 / 【硬约束】段）。</li>
 * </ul>
 * <p><b>硬门控不可被 AI 覆盖</b>：空间生成与决策护栏都用本服务的输出做校验——AI 只能在允许集内发明，
 * 不能把禁止行为写进候选（违反即剔除+告警）。</p>
 * <p><b>零阻断</b>：角色行不存在/档案缺阶段 → {@link HardGateResult#lenient}（不禁止任何行为），
 * 世界运行绝不被门控数据缺失卡死。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrowthGateService {

    /** 机能曲线峰值年龄（游戏年；设计文档 §4.6：峰值在 YOUTH~PRIME 约 25-35 岁） */
    public static final int PEAK_AGE = 30;

    /** 硬门控注入文案上限（字符；【硬约束】段防撑爆 Prompt） */
    private static final int CONSTRAINT_MAX_CHARS = 500;

    private final GrowthStageGateRepository gateRepository;
    private final GrowthStageConstraintRepository constraintRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorNpcStateRepository stateRepository;
    private final GrowthProperties growthProperties;

    /** 启动 seed（表为空才写，非空保留热更新结果）。 */
    @PostConstruct
    public void seedIfEmpty() {
        if (!growthProperties.isEnabled()) {
            return;
        }
        try {
            if (growthProperties.getGateTable().isSeedOnStartup()
                    && gateRepository.countByStageIsNotNull() == 0) {
                seedGates();
                log.info("[阶段门控] 能力门控表为空，已写入代码默认 seed（{} 行）", gateRepository.countByStageIsNotNull());
            } else {
                log.info("[阶段门控] 能力门控表已有 {} 行（保留热更新结果，不覆盖）",
                        gateRepository.countByStageIsNotNull());
            }
            if (growthProperties.getGateTable().isSeedOnStartup()
                    && constraintRepository.countByStageIsNotNull() == 0) {
                seedConstraints();
                log.info("[阶段门控] 行为约束表为空，已写入代码默认 seed（{} 行）",
                        constraintRepository.countByStageIsNotNull());
            } else {
                log.info("[阶段门控] 行为约束表已有 {} 行（保留热更新结果，不覆盖）",
                        constraintRepository.countByStageIsNotNull());
            }
        } catch (Exception e) {
            // seed 失败绝不阻断应用启动（世界照常跑，门控回退代码默认兜底）
            log.warn("[阶段门控] seed 失败（降级为代码默认兜底，不阻断启动）: {}", e.getMessage());
        }
    }

    /** 写入能力门控 seed（成长累积语义：逐阶段合并允许集）。 */
    private void seedGates() {
        Map<String, List<String>> allowed = GrowthStageDefaults.allowedAbilityByStage();
        Map<String, Map<String, GrowthStageDefaults.GateRow>> gates = GrowthStageDefaults.abilityGates();
        int order = 0;
        for (GrowthStage stage : GrowthStage.values()) {
            List<String> allowedAbilities = allowed.getOrDefault(stage.name(), List.of());
            for (String ability : allowedAbilities) {
                GrowthStageDefaults.GateRow row = gates.getOrDefault(stage.name(), Map.of())
                        .getOrDefault(ability, GrowthStageDefaults.GateRow.allow(""));
                if (gateRepository.existsByStageAndAbility(stage.name(), ability)) {
                    continue;
                }
                GrowthStageGate e = new GrowthStageGate();
                e.setStage(stage.name());
                e.setAbility(ability);
                e.setAllowed(1);
                e.setPromptText(row.promptText());
                e.setSortOrder(order++);
                gateRepository.save(e);
            }
            // 明确禁止的能力不落行（缺省=禁止，allowed 集才是允许集；表里只存允许行，语义清晰）
        }
    }

    /** 写入行为约束 seed。 */
    private void seedConstraints() {
        int order = 0;
        for (GrowthStage stage : GrowthStage.values()) {
            List<GrowthStageDefaults.ConstraintRow> rows =
                    GrowthStageDefaults.constraints().getOrDefault(stage.name(), List.of());
            for (GrowthStageDefaults.ConstraintRow row : rows) {
                if (constraintRepository.existsByStageAndKind(stage.name(), row.kind())) {
                    continue;
                }
                GrowthStageConstraint e = new GrowthStageConstraint();
                e.setStage(stage.name());
                e.setKind(row.kind());
                e.setContent(row.content());
                e.setPromptText(row.promptText());
                e.setSortOrder(order++);
                constraintRepository.save(e);
            }
        }
    }

    //
    // 硬门控计算（唯一策略源）
    //

    /**
     * 计算某 NPC 的硬门控结果（唯一策略源：空间生成 / 决策前置 / 护栏后置三处共用）。
     *
     * @param projectId 项目 ID（可空；仅用于状态行查找）
     *
     * @param npcRef 统一角色引用 npc:{id}
     *
     * @return 硬门控结果；行不存在返回宽松兜底（零阻断）
     */
    public HardGateResult gate(Long projectId, String npcRef) {
        Long id = NpcRefs.idOf(npcRef);
        if (id == null) {
            return HardGateResult.lenient(npcRef);
        }
        ActorCharacter ch = characterRepository.findById(id).orElse(null);
        if (ch == null) {
            return HardGateResult.lenient(npcRef);
        }
        ActorNpcState state = stateRepository
                .findByProjectIdAndNpcRef(projectId == null ? ch.getProjectId() : projectId, npcRef).orElse(null);
        PersonaProfile persona = PersonaJson.readQuietly(ch.getPersonaJson());
        return gate(ch, state, persona);
    }

    /**
     * 计算硬门控结果（内部实现，入参已就绪）。
     *
     * @param ch 角色主表行（stage/age 必读）
     *
     * @param state 状态行（可空；生理越界判定用 health/urgency）
     *
     * @param persona 11 维档案（可空；体质弱点/道德底线用）
     *
     * @return 硬门控结果
     */
    public HardGateResult gate(ActorCharacter ch, ActorNpcState state, PersonaProfile persona) {
        if (ch == null) {
            return HardGateResult.lenient(null);
        }
        String npcRef = NpcRefs.of(ch.getId());
        String stageCode = ch.getStage();
        GrowthStage stage = GrowthStage.of(stageCode);
        if (stage == null) {
            // 档案无阶段（尚未反推/初始化）：宽松兜底，零阻断
            return HardGateResult.lenient(npcRef);
        }
        int age = ch.getAge() == null ? stage.minAge() : Math.max(0, ch.getAge());
        double capacity = capacityOf(age);

        // ① 允许能力清单（表优先 + 代码兜底）
        List<String> allowedAbilities = resolveAllowedAbilities(stage);

        // ② 禁止行为（HARD 约束 + 生理越界 + 道德红线）
        List<HardGateResult.ForbiddenItem> forbidden = new ArrayList<>();
        for (GrowthStageConstraint c : constraintRepository.findByStageOrderByKindAscSortOrderAsc(stage.name())) {
            if (GrowthStageConstraint.KIND_HARD.equals(c.getKind()) && c.getContent() != null
                    && !c.getContent().isBlank()) {
                forbidden.add(new HardGateResult.ForbiddenItem(c.getContent(),
                        "阶段硬约束（" + stage.label() + "）"));
            }
        }
        // 生理越界：健康告急 → 一切活动受限；体质弱点 → 具体越界项
        if (state != null && state.getHealth() != null && state.getHealth() < 30) {
            forbidden.add(new HardGateResult.ForbiddenItem("强撑病体外出劳作",
                    "健康 " + state.getHealth() + " 告急，应卧床休养"));
        }
        if (persona != null && persona.getDims() != null && persona.getDims().getAppearance() != null) {
            List<String> weaknesses = persona.getDims().getAppearance().getConstitutionWeaknesses();
            if (weaknesses != null) {
                for (String w : weaknesses) {
                    if (w == null || w.isBlank()) {
                        continue;
                    }
                    // 体质弱点映射为可执行的越界项（确定性规则；弱点原文进 reason 供审计）
                    forbidden.add(physioBreachOf(w));
                }
            }
        }
        // 道德底线（⑤ 维）：绝不做的事直接成为红线条目（AI 不可覆盖）
        if (persona != null && persona.getDims() != null && persona.getDims().getValues() != null) {
            List<String> bottomLines = persona.getDims().getValues().getMoralBottomLines();
            if (bottomLines != null) {
                for (String b : bottomLines) {
                    if (b == null || b.isBlank()) {
                        continue;
                    }
                    forbidden.add(new HardGateResult.ForbiddenItem(b, "道德底线（本人绝不做）"));
                }
            }
        }

        // ③ 软约束文案（SOFT 约束行 → Prompt 注入段）
        List<String> soft = new ArrayList<>();
        for (GrowthStageConstraint c : constraintRepository.findByStageOrderByKindAscSortOrderAsc(stage.name())) {
            if (GrowthStageConstraint.KIND_SOFT.equals(c.getKind())) {
                String text = c.getPromptText() == null || c.getPromptText().isBlank()
                        ? c.getContent() : c.getPromptText();
                if (text != null && !text.isBlank()) {
                    soft.add(text);
                }
            }
        }

        return new HardGateResult(npcRef, stage.name(), age, capacity,
                allowedAbilities, forbidden, soft, ageModifiers(capacity));
    }

    /** 体质弱点 → 确定性越界项映射（弱点原文保留进 reason；未命中规则的弱点也作为禁止项兜底）。 */
    private HardGateResult.ForbiddenItem physioBreachOf(String weakness) {
        String w = weakness.trim();
        if (w.contains("腿") || w.contains("膝") || w.contains("关节") || w.contains("风湿") || w.contains("寒")) {
            return new HardGateResult.ForbiddenItem("冒寒涉水、久站远行", "体质弱点「" + w + "」");
        }
        if (w.contains("心") || w.contains("喘") || w.contains("肺") || w.contains("咳")) {
            return new HardGateResult.ForbiddenItem("剧烈劳作、疾行奔跑", "体质弱点「" + w + "」");
        }
        if (w.contains("腰") || w.contains("背") || w.contains("骨")) {
            return new HardGateResult.ForbiddenItem("挑担负重、弯腰久作", "体质弱点「" + w + "」");
        }
        if (w.contains("眼") || w.contains("视")) {
            return new HardGateResult.ForbiddenItem("夜间赶路、精细目力活", "体质弱点「" + w + "」");
        }
        if (w.contains("耳") || w.contains("听")) {
            return new HardGateResult.ForbiddenItem("独自警戒、远距传话", "体质弱点「" + w + "」");
        }
        return new HardGateResult.ForbiddenItem(w + "诱发的过度活动", "体质弱点「" + w + "」");
    }

    /**
     * 允许能力清单（表优先 + 代码默认兜底）。
     *
     * @param stage 阶段
     *
     * @return 允许能力码清单（恒非空；表空时用代码默认）
     */
    public List<String> resolveAllowedAbilities(GrowthStage stage) {
        List<GrowthStageGate> rows = gateRepository.findByStageOrderBySortOrderAsc(stage.name());
        if (rows != null && !rows.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (GrowthStageGate r : rows) {
                if (Integer.valueOf(1).equals(r.getAllowed()) && r.getAbility() != null) {
                    out.add(r.getAbility());
                }
            }
            return out;
        }
        // 代码默认兜底（表未 seed / 被清空）
        return GrowthStageDefaults.allowedAbilityByStage().getOrDefault(stage.name(), List.of());
    }

    /**
     * 软约束文案（表优先 + 代码默认兜底；Prompt 注入用）。
     *
     * @param stage 阶段
     *
     * @return 软约束文案清单
     */
    public List<String> softConstraintsOf(GrowthStage stage) {
        List<GrowthStageConstraint> rows = constraintRepository.findByStageOrderByKindAscSortOrderAsc(stage.name());
        if (rows != null && !rows.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (GrowthStageConstraint c : rows) {
                if (!GrowthStageConstraint.KIND_SOFT.equals(c.getKind())) {
                    continue;
                }
                String text = c.getPromptText() == null || c.getPromptText().isBlank()
                        ? c.getContent() : c.getPromptText();
                if (text != null && !text.isBlank()) {
                    out.add(text);
                }
            }
            return out;
        }
        List<String> out = new ArrayList<>();
        for (GrowthStageDefaults.ConstraintRow r
                : GrowthStageDefaults.constraints().getOrDefault(stage.name(), List.of())) {
            if (GrowthStageConstraint.KIND_SOFT.equals(r.kind()) && r.promptText() != null
                    && !r.promptText().isBlank()) {
                out.add(r.promptText());
            }
        }
        return out;
    }

    //
    // 机能曲线与年龄修正（衰老 → 状态模块）
    //

    /**
     * 机能曲线：{@code capacity(age)} 0~1。
     * <p>分段线性（对齐设计文档 §4.6「峰值在 YOUTH~PRIME（约 25-35 岁），之后线性 + 加速衰减」）：</p>
     * <ul>
     * <li>0~12 岁：0.15 → 0.50（幼年发育，力量/耐力渐增）；</li>
     * <li>12~18：0.50 → 0.75（青春期发育）；</li>
     * <li>18~30：0.75 → 1.00（步入峰值）；</li>
     * <li>30~45：1.00 → 0.95（峰值平台缓慢回落）；</li>
     * <li>45~60：0.95 → 0.78（中年线性下降）；</li>
     * <li>60~75：0.78 → 0.50（老年加速）；</li>
     * <li>75~90：0.50 → 0.25（耄耋加速）；</li>
     * <li>90+：0.25 → 0.10 地板（高龄极限）。</li>
     * </ul>
     *
     * @param age 年龄（游戏年；负数按 0）
     *
     * @return 机能 0~1
     */
    public double capacityOf(int age) {
        int a = Math.max(0, age);
        if (a < 12) {
            return lerp(a, 0, 12, 0.15, 0.50);
        }
        if (a < 18) {
            return lerp(a, 12, 18, 0.50, 0.75);
        }
        if (a < 30) {
            return lerp(a, 18, 30, 0.75, 1.00);
        }
        if (a < 45) {
            return lerp(a, 30, 45, 1.00, 0.95);
        }
        if (a < 60) {
            return lerp(a, 45, 60, 0.95, 0.78);
        }
        if (a < 75) {
            return lerp(a, 60, 75, 0.78, 0.50);
        }
        if (a < 90) {
            return lerp(a, 75, 90, 0.50, 0.25);
        }
        return Math.max(0.10, 0.25 - (a - 90) * 0.01);
    }

    /** 线性插值。 */
    private static double lerp(int x, int x0, int x1, double y0, double y1) {
        if (x <= x0) {
            return y0;
        }
        if (x >= x1) {
            return y1;
        }
        return y0 + (y1 - y0) * (x - x0) / (double) (x1 - x0);
    }

    /**
     * 机能曲线 → 状态模块修正倍率（衰老真正进入衰减计算的载体）。
     * <p>确定性规则（随 {@code capacity} 单调、可单测）：</p>
     * <ul>
     * <li>{@code recoverScale} = 0.5 + 0.8×capacity（年轻恢复快、年老恢复慢）；</li>
     * <li>{@code energyScale} = 1.45 − 0.85×capacity（成年最省力，幼老耗得快需多歇）；</li>
     * <li>{@code hungerScale} = 1.35 − 0.35×capacity（幼老代谢快/餐频高）；</li>
     * <li>{@code coldSens} = 1.6 − 0.6×capacity（年老畏寒放大）；</li>
     * <li>{@code heatSens} = 1.4 − 0.4×capacity（年老畏热略放大）；</li>
     * <li>{@code painFactor} = 1.3 − 0.3×capacity（年老健康侵蚀略快）；</li>
     * <li>{@code diseaseRisk} = 2.2 − 1.2×capacity（年老易病，供日级疾病掷点）。</li>
     * </ul>
     *
     * @param capacity 机能 0~1
     *
     * @return 倍率表（键与 {@code PhysioProfile} 系数对齐 + diseaseRisk）
     */
    public Map<String, Double> ageModifiers(double capacity) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("recoverScale", clamp(0.5 + 0.8 * capacity, 0.5, 1.3));
        m.put("energyScale", clamp(1.45 - 0.85 * capacity, 0.6, 1.4));
        m.put("hungerScale", clamp(1.35 - 0.35 * capacity, 0.7, 1.35));
        m.put("coldSens", clamp(1.6 - 0.6 * capacity, 0.9, 1.6));
        m.put("heatSens", clamp(1.4 - 0.4 * capacity, 0.9, 1.4));
        m.put("painFactor", clamp(1.3 - 0.3 * capacity, 0.9, 1.3));
        m.put("diseaseRisk", clamp(2.2 - 1.2 * capacity, 0.8, 2.2));
        return m;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    //
    // Prompt 注入文案
    //

    /**
     * 渲染【发育阶段约束】注入文案（Prompt 新层 DEVELOPMENTAL_CONSTRAINT / 决策硬约束段共用）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 统一角色引用
     *
     * @return 文案；角色无阶段返回空串（零阻断）
     */
    public String renderStageBlock(Long projectId, String npcRef) {
        HardGateResult gate = gate(projectId, npcRef);
        if (gate.stage() == null) {
            return "";
        }
        GrowthStage stage = GrowthStage.of(gate.stage());
        StringBuilder sb = new StringBuilder();
        sb.append("当前阶段：").append(stage == null ? gate.stage() : stage.label())
                .append(gate.age() == null ? "" : "（" + gate.age() + " 岁）");
        if (stage != null && !stage.summary().isBlank()) {
            sb.append("\n阶段特征：").append(stage.summary());
        }
        // 允许能力（压缩为一行，防撑爆 Prompt）
        if (gate.allowedAbilities() != null && !gate.allowedAbilities().isEmpty()) {
            List<String> labels = new ArrayList<>();
            for (String code : gate.allowedAbilities()) {
                GrowthAbility a = GrowthAbility.of(code);
                labels.add(a == null ? code : a.label());
            }
            sb.append("\n你能做（能力清单）：").append(String.join("、", labels));
        }
        String constraints = gate.renderConstraints(CONSTRAINT_MAX_CHARS);
        if (!constraints.isBlank()) {
            sb.append("\n硬性禁止与受限：\n").append(constraints);
        }
        return sb.toString();
    }

    /** 渲染上限（调试/测试断言用）。 */
    public int constraintMaxChars() {
        return CONSTRAINT_MAX_CHARS;
    }
}
