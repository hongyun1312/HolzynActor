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

package com.holzyn.actor.domain.debug.controller;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.evolution.GrowthShiftLog;
import com.holzyn.actor.domain.growth.evolution.GrowthShiftLogRepository;
import com.holzyn.actor.domain.growth.evolution.PersonalityShiftService;
import com.holzyn.actor.domain.growth.lod.BrainTierService;
import com.holzyn.actor.domain.growth.mask.MaskService;
import com.holzyn.actor.domain.growth.metrics.GrowthMetricsService;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestone;
import com.holzyn.actor.domain.growth.milestone.GrowthMilestoneRepository;
import com.holzyn.actor.domain.growth.space.GrowthSpace;
import com.holzyn.actor.domain.growth.space.GrowthSpaceLog;
import com.holzyn.actor.domain.growth.space.GrowthSpaceService;
import com.holzyn.actor.domain.growth.space.GrowthSpaceTrigger;
import com.holzyn.actor.domain.growth.stage.DevelopmentalStageService;
import com.holzyn.actor.domain.growth.stage.GrowthAbility;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.growth.stage.GrowthStageConstraint;
import com.holzyn.actor.domain.growth.stage.GrowthStageConstraintRepository;
import com.holzyn.actor.domain.growth.stage.GrowthStageGate;
import com.holzyn.actor.domain.growth.stage.GrowthStageGateRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.evaluation.TestRunService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轨道 「全生命周期阶段机 + 此刻可发生空间」调试接口。
 * <p>职责：发育阶段/LOD/空间/里程碑/门控表的观测 + 热更新 + 测试操作——</p>
 * <ul>
 * <li><b>只读（调试页常开）</b>：overview / stages / space / metrics / lod / milestones / config；</li>
 * <li><b>测试操作（{@code holzyn.actor.evaluation.test-enabled=true} 才可用，默认 403）</b>：
 * space/regenerate（手动重算）、age-fastforward（年龄快进）、force-death（强制死亡）、
 * daily-maintenance（日检立即执行）、gate/constraint 热更新（表优先读语义）、lod override。</li>
 * </ul>
 * <p>安全约定：本地单用户、无鉴权（纯本地调试工具，勿公网暴露）；写操作一律走
 * {@link TestRunService#requireTestEnabled()} 门控（与既有测试接口同口径）。</p>
 * <p>； 数据源=domain/growth/*）</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/growth")
@RequiredArgsConstructor
public class GrowthDebugController {

    private final GrowthProperties growthProperties;
    private final DevelopmentalStageService developmentalStageService;
    private final GrowthSpaceService growthSpaceService;
    private final GrowthMetricsService growthMetrics;
    private final BrainTierService brainTierService;
    private final GrowthStageGateRepository gateRepository;
    private final GrowthStageConstraintRepository constraintRepository;
    private final GrowthMilestoneRepository milestoneRepository;
    private final ActorCharacterRepository characterRepository;
    private final WorldClockService worldClockService;
    private final TestRunService testRunService;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    /** 人格演化（shift/assess 测试操作 + shift-log 只读） */
    private final PersonalityShiftService personalityShiftService;
    /** 人格偏移日志仓库（只读回放） */
    private final GrowthShiftLogRepository shiftLogRepository;
    /** 社会面具（mask/status 只读 + mask 测试操作） */
    private final MaskService maskService;
    /** 谱系（家族树只读） */
    private final com.holzyn.actor.domain.growth.lineage.LineageService lineageService;
    /** 基因（基因/表观只读） */
    private final com.holzyn.actor.domain.growth.genetic.ActorGrowthGenomeRepository genomeRepository;
    /** 婚育流程（心动/求婚/婚礼测试操作） */
    private final com.holzyn.actor.domain.growth.romance.MarriageService marriageService;
    /** 孕期（受孕测试操作） */
    private final com.holzyn.actor.domain.growth.romance.PregnancyService pregnancyService;
    /** 新生儿（强制分娩测试操作） */
    private final com.holzyn.actor.domain.growth.genetic.OffspringGenerator offspringGenerator;
    /** 婴儿行为（状态机推进测试操作） */
    private final com.holzyn.actor.domain.growth.infant.InfantBehaviorService infantBehaviorService;
    /** 身后事（遗产转移测试操作） */
    private final com.holzyn.actor.domain.growth.legacy.LegacyService legacyService;
    /** 心动判定插拔点（AI 主 + 程序化兜底；N 轨道可替换实现） */
    private final com.holzyn.actor.domain.growth.romance.RomanceDecisionSource romanceDecisionSource;
    /** 求婚判定插拔点 */
    private final com.holzyn.actor.domain.growth.romance.ProposalDecisionSource proposalDecisionSource;
    /** 社会关系仓库（求婚候选枚举） */
    private final com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository relationRepository;

    /** 认知注意力（每 tick 注意力/微决策内存观测数据源；字段注入，构造器不变） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.attention.CognitiveAttentionService cognitiveAttentionService;

    /** 事件驱动重算触发（触发判定轨迹数据源；字段注入，构造器不变） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.space.SpaceTriggerJudge spaceTriggerJudge;

    // ==================== 只读观测 ====================

    /** 项目全 NPC 的发育阶段/LOD/空间概览。 */
    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(required = false) Long projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", growthProperties.isEnabled());
        out.put("config", config());
        List<Map<String, Object>> npcs = new ArrayList<>();
        if (projectId != null) {
            for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", ch.getId());
                m.put("name", ch.getName());
                m.put("type", ch.getType());
                m.put("age", ch.getAge());
                m.put("stage", ch.getStage());
                m.put("stageLabel", stageLabel(ch.getStage()));
                m.put("birthGameSecond", ch.getBirthGameSecond());
                m.put("importance", ch.getImportance());
                m.put("tier", brainTierService.tierOf(projectId, ch.npcRef()));
                m.put("currentActivity", ch.getCurrentActivity());
                GrowthSpace space = growthSpaceService.currentSpace(projectId, ch.npcRef());
                m.put("spaceSource", space == null ? null : space.getSource());
                m.put("spaceTrigger", space == null ? null : space.getTriggerCode());
                m.put("spaceGameSecond", space == null ? null : space.getGameSecond());
                m.put("spaceTtl", space == null ? null : space.getTtlGameSecond());
                npcs.add(m);
            }
        }
        out.put("npcs", npcs);
        out.put("metrics", growthMetrics.snapshot());
        return out;
    }

    /** 阶段机全量配置（开关/周期/死亡/空间/LOD/调度/反推/护栏）。 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        try {
            tools.jackson.databind.JsonNode node = objectMapper.valueToTree(growthProperties);
            return objectMapper.convertValue(node, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    /** 阶段枚举 + 门控表 + 约束表（热更新后的当前生效值=表优先）。 */
    @GetMapping("/stages")
    public Map<String, Object> stages() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> stages = new ArrayList<>();
        for (GrowthStage s : GrowthStage.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", s.name());
            m.put("label", s.label());
            m.put("ageRange", s.minAge() + "-" + s.maxAge() + " 岁");
            m.put("summary", s.summary());
            stages.add(m);
        }
        out.put("stages", stages);
        List<Map<String, Object>> abilities = new ArrayList<>();
        for (GrowthAbility a : GrowthAbility.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", a.name());
            m.put("label", a.label());
            abilities.add(m);
        }
        out.put("abilities", abilities);
        out.put("gates", gateRepository.findAllByOrderByStageAscSortOrderAsc().stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("stage", g.getStage());
            m.put("ability", g.getAbility());
            m.put("allowed", g.getAllowed());
            m.put("promptText", g.getPromptText());
            m.put("sortOrder", g.getSortOrder());
            return m;
        }).toList());
        List<Map<String, Object>> constraints = new ArrayList<>();
        for (GrowthStage stage : GrowthStage.values()) {
            for (GrowthStageConstraint c : constraintRepository.findByStageOrderByKindAscSortOrderAsc(stage.name())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("stage", c.getStage());
                m.put("kind", c.getKind());
                m.put("content", c.getContent());
                m.put("promptText", c.getPromptText());
                m.put("sortOrder", c.getSortOrder());
                constraints.add(m);
            }
        }
        out.put("constraints", constraints);
        return out;
    }

    /** 指定角色的此刻可发生空间（当前空间 + 历史）。 */
    @GetMapping("/space")
    public Map<String, Object> space(@RequestParam Long projectId, @RequestParam String npcRef) {
        Map<String, Object> out = new LinkedHashMap<>();
        GrowthSpace current = growthSpaceService.currentSpace(projectId, npcRef);
        out.put("current", current);
        out.put("history", growthSpaceService.historyOf(projectId, npcRef));
        return out;
    }

    /** 空间/护栏/决策命中指标快照。 */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return growthMetrics.snapshot();
    }

    /** LOD 档位概览 + 手动覆盖表。 */
    @GetMapping("/lod")
    public Map<String, Object> lod(@RequestParam(required = false) Long projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", growthProperties.getLod().isEnabled());
        out.put("overrides", brainTierService.overrides());
        if (projectId != null) {
            Map<String, String> tiers = new LinkedHashMap<>();
            for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
                tiers.put(ch.npcRef(), brainTierService.tierOf(projectId, ch.npcRef()));
            }
            out.put("tiers", tiers);
        }
        return out;
    }

    /**
     * 认知 LOD + 高频微决策 + 事件驱动重算触发观测（，调试三卡数据源：
     * 注意力卡 / 微决策卡 / 重算触发卡；纯内存统计 + 低频记忆，零副作用只读）。
     * <p><b>深化</b>：同端点追加——参与度卡（群聊参与度统计 + 回灌注意力开关）、
     * 相机聚焦卡（有相机时各角色相机注意力档位）、记忆加权配置（配额表 + 层间优先级修正开关）。</p>
     */
    @GetMapping("/n5")
    public Map<String, Object> n5(@RequestParam(required = false) Long projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        GrowthProperties.Attention att = growthProperties.getAttention();
        out.put("attentionEnabled", cognitiveAttentionService != null && growthProperties.isEnabled()
                && att != null && att.isEnabled());
        Map<String, Object> attentionConfig = new LinkedHashMap<>();
        if (att != null) {
            attentionConfig.put("periodTicks", att.getPeriodTicks());
            attentionConfig.put("interruptProbability", att.getInterruptProbability());
            attentionConfig.put("memoryWriteProbability", att.getMemoryWriteProbability());
            attentionConfig.put("eventRelevancePromoteLevel", att.getEventRelevancePromoteLevel());
            attentionConfig.put("eventRelevanceMemoryLevel", att.getEventRelevanceMemoryLevel());
            attentionConfig.put("stateTextMaxChars", att.getStateTextMaxChars());
            // ：参与度 / 记忆加权 / 相机聚焦配置
            attentionConfig.put("participationEnabled", att.isParticipationEnabled());
            attentionConfig.put("participationWindowGameHours", att.getParticipationWindowGameHours());
            attentionConfig.put("participationLevelStep", att.getParticipationLevelStep());
            attentionConfig.put("participationBoostEnabled", att.isParticipationBoostEnabled());
            attentionConfig.put("desireBoostEnabled", att.isDesireBoostEnabled());
            attentionConfig.put("participationRecordingEnabled", att.isParticipationRecordingEnabled());
            attentionConfig.put("memoryWeightEnabled", att.isMemoryWeightEnabled());
            attentionConfig.put("memoryTierAdjustEnabled", att.isMemoryTierAdjustEnabled());
            attentionConfig.put("memoryL1CapByTier", att.getMemoryL1CapByTier());
            attentionConfig.put("memoryL2TopKByTier", att.getMemoryL2TopKByTier());
            attentionConfig.put("spaceMemoryTopKByTier", att.getSpaceMemoryTopKByTier());
            attentionConfig.put("cameraFocusEnabled", att.isCameraFocusEnabled());
            attentionConfig.put("cameraNearMeters", growthProperties.getLod().getCameraNearMeters());
            attentionConfig.put("cameraMidMeters", att.getCameraMidMeters());
            attentionConfig.put("cameraFarMeters", att.getCameraFarMeters());
        }
        out.put("attentionConfig", attentionConfig);
        out.put("attentionTierChanges", cognitiveAttentionService == null ? List.of()
                : cognitiveAttentionService.recentAttentionStats(100));
        out.put("micro", Map.of(
                "counters", cognitiveAttentionService == null ? Map.of()
                        : cognitiveAttentionService.microCounters(),
                "trail", cognitiveAttentionService == null ? List.of()
                        : cognitiveAttentionService.recentMicroStats(100)));
        out.put("triggers", spaceTriggerJudge == null ? List.of()
                : spaceTriggerJudge.recentTriggers(100));
        // 参与度卡：窗口内加权计数快照 + 按 NPC 分档（回灌注意力的对话侧信号源）
        out.put("participation", Map.of(
                "enabled", att != null && att.isParticipationEnabled(),
                "snapshot", cognitiveAttentionService == null ? Map.of()
                        : cognitiveAttentionService.participationSnapshot()));
        // 相机聚焦卡：projectId 存在且有活动相机时，全 NPC 相机注意力档位
        Map<String, Object> camera = new LinkedHashMap<>();
        camera.put("enabled", att != null && att.isCameraFocusEnabled());
        camera.put("hasActiveCamera", false);
        if (projectId != null && cognitiveAttentionService != null) {
            Map<String, Integer> levels = new LinkedHashMap<>();
            for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
                int lv = cognitiveAttentionService.cameraAttentionLevel(projectId, ch.npcRef());
                levels.put(ch.npcRef(), lv);
                if (lv > 0) {
                    camera.put("hasActiveCamera", true);
                }
            }
            camera.put("levels", levels);
        }
        out.put("camera", camera);
        if (projectId != null) {
            Map<String, String> tiers = new LinkedHashMap<>();
            for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
                tiers.put(ch.npcRef(), brainTierService.tierOf(projectId, ch.npcRef()));
            }
            out.put("tiers", tiers);
        }
        return out;
    }

    /** 里程碑（阶段跨越/死亡/出生/标记）列表，最多 200 条。 */
    @GetMapping("/milestones")
    public Map<String, Object> milestones(@RequestParam(required = false) Long projectId,
                                          @RequestParam(required = false) String kind) {
        List<GrowthMilestone> all = projectId == null
                ? milestoneRepository.findTop200ByOrderByIdDesc()
                : milestoneRepository.findTop200ByProjectIdOrderByIdDesc(projectId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (GrowthMilestone m : all) {
            if (kind != null && !kind.isBlank() && !kind.equals(m.getKind())) {
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", m.getId());
            x.put("npcRef", m.getNpcRef());
            x.put("kind", m.getKind());
            x.put("title", m.getTitle());
            x.put("detail", m.getDetail());
            x.put("stageFrom", m.getStageFrom());
            x.put("stageTo", m.getStageTo());
            x.put("importance", m.getImportance());
            x.put("gameSecond", m.getGameSecond());
            list.add(x);
        }
        return Map.of("list", list);
    }

    /** 人格偏移日志。 */
    @GetMapping("/shift-log")
    public Map<String, Object> shiftLog(@RequestParam Long projectId,
                                        @RequestParam(required = false) String npcRef) {
        List<GrowthShiftLog> all = npcRef == null || npcRef.isBlank()
                ? shiftLogRepository.findTop200ByProjectIdOrderByIdDesc(projectId)
                : shiftLogRepository.findTop200ByProjectIdAndNpcRefOrderByIdDesc(projectId, npcRef);
        List<Map<String, Object>> list = new ArrayList<>();
        for (GrowthShiftLog s : all) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", s.getId());
            x.put("npcRef", s.getNpcRef());
            x.put("gameSecond", s.getGameSecond());
            x.put("triggerCode", s.getTriggerCode());
            x.put("triggerLabel", triggerLabel(s.getTriggerCode()));
            x.put("dim", s.getDim());
            x.put("dimLabel", dimLabel(s.getDim()));
            x.put("field", s.getField());
            x.put("oldValue", s.getOldValue());
            x.put("newValue", s.getNewValue());
            x.put("delta", s.getDelta());
            x.put("stageCap", s.getStageCap());
            x.put("source", s.getSource());
            x.put("aiReason", s.getAiReason());
            x.put("evidenceJson", s.getEvidenceJson());
            x.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
            list.add(x);
        }
        return Map.of("list", list);
    }

    /** 社会面具状态。 */
    @GetMapping("/mask")
    public Map<String, Object> mask(@RequestParam Long projectId, @RequestParam String npcRef) {
        return maskService.statusOf(projectId, npcRef);
    }

    // ==================== 只读观测（遗传婚育谱系） ====================

    /** 三代家族树。 */
    @GetMapping("/lineage")
    public Map<String, Object> lineage(@RequestParam Long projectId, @RequestParam String npcRef) {
        return lineageService.familyTree(projectId, npcRef);
    }

    /** 基因/表观视图。 */
    @GetMapping("/genome")
    public Map<String, Object> genome(@RequestParam Long projectId, @RequestParam String npcRef) {
        var row = genomeRepository.findByProjectIdAndNpcRef(projectId, npcRef);
        if (row.isEmpty()) {
            return Map.of("npcRef", npcRef, "present", false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("npcRef", npcRef);
        out.put("present", true);
        out.put("source", row.get().getSource());
        out.put("sourceParentA", row.get().getSourceParentA());
        out.put("sourceParentB", row.get().getSourceParentB());
        try {
            out.put("geneLoci", objectMapper.readTree(row.get().getGeneLociJson()));
        } catch (Exception e) {
            out.put("geneLoci", null);
        }
        try {
            out.put("epigenetics", objectMapper.readTree(row.get().getEpigeneticsJson()));
        } catch (Exception e) {
            out.put("epigenetics", null);
        }
        return out;
    }

    // ==================== 测试操作（holzyn.actor.evaluation.test-enabled 门控） ====================

    /** 心动判定：对指定 NPC 与候选对象立即执行一次 AI 心动判定（不落 ROMANTIC 边）。 */
    @PostMapping("/romance/attract")
    public Map<String, Object> romanceAttract(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        String npcRef = strOf(body.get("npcRef"));
        String partnerRef = strOf(body.get("partnerRef"));
        if (projectId == null || npcRef == null || partnerRef == null) {
            throw new IllegalArgumentException("projectId/npcRef/partnerRef 不能为空");
        }
        Long gameSecond = gameSecondOf(projectId);
        var npc = characterRepository.findById(NpcRefs.idOf(npcRef)).orElse(null);
        var partner = characterRepository.findById(NpcRefs.idOf(partnerRef)).orElse(null);
        if (npc == null || partner == null) {
            throw new IllegalArgumentException("npcRef/partnerRef 对应的角色不存在");
        }
        int affinity = intOf(body.get("affinity"), 60);
        var ctx = new com.holzyn.actor.domain.growth.romance.RomanceDecisions.AttractionContext(
                projectId, npcRef, npc.getName(), partnerRef, partner.getName(),
                "调试手动判定（好感 " + affinity + "）", affinity, gameSecond);
        var decision = romanceDecisionSource.decide(ctx);
        return Map.of("decision", decision);
    }

    /** 强制求婚：指定 NPC 对恋爱对象立即执行求婚判定（成功则走完整婚礼流程）。 */
    @PostMapping("/romance/propose")
    public Map<String, Object> romancePropose(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        String npcRef = strOf(body.get("npcRef"));
        if (projectId == null || npcRef == null) {
            throw new IllegalArgumentException("projectId/npcRef 不能为空");
        }
        Long gameSecond = gameSecondOf(projectId);
        // 直接以候选列表驱动求婚判定（复用真实代码路径）
        var npc = characterRepository.findById(NpcRefs.idOf(npcRef)).orElse(null);
        if (npc == null) {
            throw new IllegalArgumentException("npcRef 对应的角色不存在");
        }
        java.util.List<String> candidates = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        for (var rel : relationRepository.findByProjectId(projectId)) {
            if (rel == null || !"ROMANTIC".equals(rel.getRelationType()) || !npcRef.equals(rel.getFromRef())) {
                continue;
            }
            candidates.add(rel.getToRef());
            if (summary.length() > 0) {
                summary.append("、");
            }
            summary.append(rel.getToRef());
        }
        if (candidates.isEmpty()) {
            return Map.of("ok", false, "reason", "该角色无恋爱对象（先走心动流程或手动建 ROMANTIC 边）");
        }
        var ctx = new com.holzyn.actor.domain.growth.romance.RomanceDecisions.ProposalContext(
                projectId, npcRef, npc.getName(), candidates, summary.toString(), 30, gameSecond);
        var decision = proposalDecisionSource.decide(ctx);
        if (decision != null && decision.propose()) {
            var partner = characterRepository.findById(NpcRefs.idOf(decision.targetRef())).orElse(null);
            if (partner != null) {
                marriageService.performWedding(projectId, npc, partner, gameSecond);
                return Map.of("ok", true, "decision", decision);
            }
        }
        return Map.of("ok", false, "decision", decision);
    }

    /** 强制婚礼：指定两人直接走婚礼流程（谱系婚姻边 + 事件 + 里程碑 + 叙事）。 */
    @PostMapping("/marriage/force")
    public Map<String, Object> marriageForce(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        String aRef = strOf(body.get("npcRefA"));
        String bRef = strOf(body.get("npcRefB"));
        if (projectId == null || aRef == null || bRef == null) {
            throw new IllegalArgumentException("projectId/npcRefA/npcRefB 不能为空");
        }
        Long gameSecond = gameSecondOf(projectId);
        var a = characterRepository.findById(NpcRefs.idOf(aRef)).orElse(null);
        var b = characterRepository.findById(NpcRefs.idOf(bRef)).orElse(null);
        if (a == null || b == null) {
            throw new IllegalArgumentException("角色不存在");
        }
        marriageService.performWedding(projectId, a, b, gameSecond);
        return Map.of("ok", true, "lineage", lineageService.familyTree(projectId, aRef));
    }

    /** 强制受孕：指定母亲与父亲直接启动孕期（280 游戏日预产期）。 */
    @PostMapping("/pregnancy/start")
    public Map<String, Object> pregnancyStart(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        String motherRef = strOf(body.get("motherRef"));
        String fatherRef = strOf(body.get("fatherRef"));
        if (projectId == null || motherRef == null) {
            throw new IllegalArgumentException("projectId/motherRef 不能为空");
        }
        Long gameSecond = gameSecondOf(projectId);
        boolean ok = pregnancyService.startPregnancy(projectId, motherRef, fatherRef, gameSecond);
        log.info("[G4 调试] 强制受孕：project={} mother={} father={} → {}", projectId, motherRef, fatherRef, ok);
        return Map.of("ok", ok);
    }

    /** 强制分娩：直接生成新生儿（绕过孕期；父母谱系边 + 档案 + 运行态 + 广播全链路）。 */
    @PostMapping("/offspring/force")
    public Map<String, Object> offspringForce(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        String motherRef = strOf(body.get("motherRef"));
        String fatherRef = strOf(body.get("fatherRef"));
        if (projectId == null || motherRef == null) {
            throw new IllegalArgumentException("projectId/motherRef 不能为空");
        }
        Long gameSecond = gameSecondOf(projectId);
        var child = offspringGenerator.createOffspring(projectId, motherRef, fatherRef, gameSecond);
        log.info("[G4 调试] 强制分娩：project={} mother={} father={} → {}", projectId, motherRef, fatherRef,
                child == null ? "失败" : child.npcRef());
        if (child == null) {
            return Map.of("ok", false);
        }
        return Map.of("ok", true, "childRef", child.npcRef(), "childName", child.getName(),
                "lineage", lineageService.familyTree(projectId, motherRef));
    }

    /** 婴儿状态机推进：立即执行一次婴儿行为推进（需求衰减/啼哭刺激/位置跟随）。 */
    @PostMapping("/infant/advance")
    public Map<String, Object> infantAdvance(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        Long gameSecond = gameSecondOf(projectId);
        int handled = infantBehaviorService.advanceInfants(projectId, gameSecond);
        return Map.of("ok", true, "handled", handled);
    }

    /** 强制身后事：对指定角色立即执行遗产转移链路（供 death 关闭态验证）。 */
    @PostMapping("/legacy/transfer")
    public Map<String, Object> legacyTransfer(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        String npcRef = strOf(body.get("npcRef"));
        if (projectId == null || npcRef == null) {
            throw new IllegalArgumentException("projectId/npcRef 不能为空");
        }
        Long gameSecond = gameSecondOf(projectId);
        var ch = characterRepository.findById(NpcRefs.idOf(npcRef)).orElse(null);
        if (ch == null) {
            throw new IllegalArgumentException("角色不存在");
        }
        legacyService.transferOnDeath(projectId, ch, gameSecond);
        return Map.of("ok", true, "lineage", lineageService.familyTree(projectId, npcRef));
    }

    // ==================== 测试操作（holzyn.actor.evaluation.test-enabled 门控） ====================

    /** 手动重算某角色的此刻可发生空间（MANUAL 触发码，无去重窗口限制）。 */
    @PostMapping("/space/regenerate")
    public Map<String, Object> regenerateSpace(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        String npcRef = strOf(body.get("npcRef"));
        Long gameSecond = gameSecondOf(projectId);
        GrowthSpace space = growthSpaceService.triggerRegenerate(projectId, npcRef,
                GrowthSpaceTrigger.MANUAL, gameSecond);
        log.info("[G2 调试] 手动空间重算：project={} npcRef={}", projectId, npcRef);
        return Map.of("space", space);
    }

    /** 年龄快进（测试接口）：把角色拨到目标年龄，触发阶段重算/里程碑/生理系数刷新。 */
    @PostMapping("/age-fastforward")
    public Map<String, Object> ageFastForward(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        Long profileId = longOf(body.get("profileId"));
        int targetAge = intOf(body.get("targetAge"), 30);
        Long gameSecond = gameSecondOf(projectId);
        String result = developmentalStageService.fastForwardAge(projectId, profileId, targetAge, gameSecond);
        log.info("[G2 调试] 年龄快进：project={} profileId={} → {} 岁", projectId, profileId, targetAge);
        return Map.of("result", result);
    }

    /** 强制死亡（测试接口）：立即执行完整死亡链路（DECEASED/空间清除/里程碑/叙事/事件/丧亲记忆）。 */
    @PostMapping("/force-death")
    public Map<String, Object> forceDeath(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        Long profileId = longOf(body.get("profileId"));
        String reason = strOf(body.get("reason"));
        Long gameSecond = gameSecondOf(projectId);
        boolean ok = developmentalStageService.forceDeath(projectId, profileId, gameSecond, reason);
        log.info("[G2 调试] 强制死亡：project={} profileId={} 原因={} → {}", projectId, profileId, reason, ok);
        return Map.of("ok", ok);
    }

    /** 日检立即执行（反推/年龄刷新/阶段检查/机能系数/死亡掷点）。 */
    @PostMapping("/daily-maintenance")
    public Map<String, Object> dailyMaintenance(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        Long gameSecond = gameSecondOf(projectId);
        developmentalStageService.dailyMaintenance(projectId, gameSecond);
        log.info("[G2 调试] 全生命周期日检立即执行：project={}", projectId);
        return Map.of("ok", true);
    }

    /** 人格偏移手动评估。 */
    @PostMapping("/shift/evaluate")
    public Map<String, Object> shiftEvaluate(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        String npcRef = strOf(body.get("npcRef"));
        if (projectId == null || npcRef == null) {
            throw new IllegalArgumentException("projectId/npcRef 不能为空");
        }
        Long gameSecond = gameSecondOf(projectId);
        Map<String, Object> result = personalityShiftService.assess(projectId, npcRef,
                GrowthShiftLog.TRIGGER_MANUAL, gameSecond);
        log.info("[G3 调试] 人格偏移手动评估：project={} npcRef={} → {}", projectId, npcRef, result);
        return result == null ? Map.of("error", "评估未执行（特性关闭/角色缺失/已死亡）") : result;
    }

    /**
     * 人格演化周期检查：等价世界引擎跨日边界触发的 cycleCheck——
     * 游戏日达 30 整数倍时全项目证据充分者异步评估（含面具固化）。
     * <p><b>世界长跑加速段（验收⑨）</b>：真实引擎受 24-tick/轮上限约束，30 游戏日全量 AI 推进
     * 不在 空跑；本端点与引擎 ②-i 日级任务调用同一方法（{@code cycleCheck}），
     * body 可带 {@code gameSecond} 指定目标时刻（缺省=引擎当前时刻）。</p>
     */
    @PostMapping("/shift/cycle-check")
    public Map<String, Object> shiftCycleCheck(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        Long gameSecond = longOf(body.get("gameSecond")) != null ? longOf(body.get("gameSecond"))
                : gameSecondOf(projectId);
        long day = gameSecond == null ? 0 : gameSecond / 86400L;
        personalityShiftService.cycleCheck(projectId, gameSecond);
        log.info("[G3 调试] 人格演化周期检查：project={} 游戏日={}（每 {} 日一评）", projectId, day,
                growthProperties.getShift().getAssessCycleGameDays());
        return Map.of("ok", true, "gameSecond", gameSecond, "gameDay", day,
                "cycleDays", growthProperties.getShift().getAssessCycleGameDays());
    }

    /** 面具破绽模拟。 */
    @PostMapping("/mask/violation")
    public Map<String, Object> maskViolation(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        Long projectId = longOf(body.get("projectId"));
        String npcRef = strOf(body.get("npcRef"));
        if (projectId == null || npcRef == null) {
            throw new IllegalArgumentException("projectId/npcRef 不能为空");
        }
        Long gameSecond = gameSecondOf(projectId);
        maskService.evolveOnViolation(projectId, npcRef, gameSecond, "调试模拟失态（违背禁止表达）");
        log.info("[G3 调试] 面具破绽模拟：project={} npcRef={}", projectId, npcRef);
        return maskService.statusOf(projectId, npcRef);
    }

    // ==================== 门控表热更新（表优先读、代码兜底；热更新即时生效） ====================

    /** 门控行新增/更新（stage+ability 唯一；allowed=1 允许 / 0 禁止）。 */
    @PostMapping("/gate/upsert")
    public Map<String, Object> upsertGate(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        String stage = strOf(body.get("stage"));
        String ability = strOf(body.get("ability"));
        if (stage == null || ability == null) {
            throw new IllegalArgumentException("stage/ability 不能为空");
        }
        GrowthStageGate row = gateRepository.findAllByOrderByStageAscSortOrderAsc().stream()
                .filter(g -> stage.equals(g.getStage()) && ability.equals(g.getAbility()))
                .findFirst()
                .orElseGet(GrowthStageGate::new);
        row.setStage(stage);
        row.setAbility(ability);
        Object allowed = body.get("allowed");
        row.setAllowed(allowed == null ? 1 : (Boolean.TRUE.equals(allowed) || Integer.valueOf(1).equals(intOf(allowed, 1)) ? 1 : 0));
        row.setPromptText(strOf(body.get("promptText")));
        row.setSortOrder(intOf(body.get("sortOrder"), row.getSortOrder() == null ? 0 : row.getSortOrder()));
        GrowthStageGate saved = gateRepository.save(row);
        log.info("[G2 调试] 门控热更新：stage={} ability={} allowed={}", stage, ability, saved.getAllowed());
        return Map.of("gate", saved);
    }

    /** 门控行删除（按 id；删除后该能力回退代码兜底/缺省禁止）。 */
    @DeleteMapping("/gate/{id}")
    public Map<String, Object> deleteGate(@PathVariable("id") Long id) {
        testRunService.requireTestEnabled();
        gateRepository.deleteById(id);
        log.info("[G2 调试] 门控行删除：id={}", id);
        return Map.of("ok", true);
    }

    /** 约束行新增/更新（kind=HARD 硬约束 / SOFT 软约束）。 */
    @PostMapping("/constraint/upsert")
    public Map<String, Object> upsertConstraint(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        String stage = strOf(body.get("stage"));
        if (stage == null || strOf(body.get("content")) == null) {
            throw new IllegalArgumentException("stage/content 不能为空");
        }
        GrowthStageConstraint row = new GrowthStageConstraint();
        Long id = longOf(body.get("id"));
        if (id != null) {
            row = constraintRepository.findById(id).orElse(row);
        }
        row.setStage(stage);
        row.setKind("HARD".equalsIgnoreCase(strOf(body.get("kind"))) ? GrowthStageConstraint.KIND_HARD
                : GrowthStageConstraint.KIND_SOFT);
        row.setContent(strOf(body.get("content")));
        row.setPromptText(strOf(body.get("promptText")));
        row.setSortOrder(intOf(body.get("sortOrder"), row.getSortOrder() == null ? 0 : row.getSortOrder()));
        GrowthStageConstraint saved = constraintRepository.save(row);
        log.info("[G2 调试] 约束热更新：stage={} kind={} id={}", stage, saved.getKind(), saved.getId());
        return Map.of("constraint", saved);
    }

    /** 约束行删除（按 id）。 */
    @DeleteMapping("/constraint/{id}")
    public Map<String, Object> deleteConstraint(@PathVariable("id") Long id) {
        testRunService.requireTestEnabled();
        constraintRepository.deleteById(id);
        log.info("[G2 调试] 约束行删除：id={}", id);
        return Map.of("ok", true);
    }

    // ==================== LOD 手动覆盖 ====================

    /** LOD 手动覆盖（npcRef → dormant/ambient/focus；覆盖优先于计算）。 */
    @PostMapping("/lod/override")
    public Map<String, Object> lodOverride(@RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        String npcRef = strOf(body.get("npcRef"));
        String tier = strOf(body.get("tier"));
        if (npcRef == null || tier == null) {
            throw new IllegalArgumentException("npcRef/tier 不能为空");
        }
        brainTierService.setOverride(npcRef, tier);
        log.info("[G2 调试] LOD 覆盖：{} → {}", npcRef, tier);
        return Map.of("overrides", brainTierService.overrides());
    }

    /** LOD 手动覆盖移除（回到计算档位；setOverride(null) = 清除覆盖，语义见 BrainTierService）。 */
    @DeleteMapping("/lod/override/{npcRef}")
    public Map<String, Object> lodOverrideRemove(@PathVariable("npcRef") String npcRef) {
        testRunService.requireTestEnabled();
        brainTierService.setOverride(npcRef, null);
        log.info("[G2 调试] LOD 覆盖移除：{}", npcRef);
        return Map.of("overrides", brainTierService.overrides());
    }

    // ==================== 辅助 ====================

    private static String stageLabel(String stage) {
        GrowthStage s = GrowthStage.of(stage);
        return s == null ? stage : s.label();
    }

    private static String triggerLabel(String code) {
        return switch (code == null ? "" : code) {
            case GrowthShiftLog.TRIGGER_CYCLE -> "周期评估";
            case GrowthShiftLog.TRIGGER_STAGE_TRANSITION -> "阶段跨越";
            case GrowthShiftLog.TRIGGER_MAJOR_EVENT -> "重大事件";
            case GrowthShiftLog.TRIGGER_MANUAL -> "手动";
            case GrowthShiftLog.TRIGGER_BREACH -> "面具破绽";
            default -> code;
        };
    }

    private static String dimLabel(String dim) {
        return switch (dim == null ? "" : dim) {
            case GrowthShiftLog.DIM_OCEAN -> "气质人格";
            case GrowthShiftLog.DIM_VALUES -> "价值观";
            case GrowthShiftLog.DIM_MASK -> "社会面具";
            default -> dim;
        };
    }

    /** 引擎推进位置（秒；读取失败 null → 空间服务按无基准视为有效，零阻断）。 */
    private Long gameSecondOf(Long projectId) {
        if (projectId == null) {
            return null;
        }
        try {
            return worldClockService.currentEngineGameSecond(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    private static Long longOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(String.valueOf(o).trim());
    }

    private static Integer intOf(Object o, int def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String strOf(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
