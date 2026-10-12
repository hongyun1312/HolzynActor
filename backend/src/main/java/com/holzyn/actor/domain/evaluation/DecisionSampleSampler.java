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

package com.holzyn.actor.domain.evaluation;

import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 决策样本采样器。
 * <p>样本源：</p>
 * <ul>
 * <li><b>DB 采样</b>：从当前项目 {@code actor_ai_call_log} 取决策类 scene（主决策=action /
 * dialog / interrupt_resume / negotiate）× <b>step_type=ai_call</b>（排除链路级标记行
 * [thinking/learning/perception 等 scene=agent_decision 空行]）× status=success ×
 * <b>user_prompt/response 非空</b>× 时间窗内的真实 AI 调用，最新优先，取 N 条；</li>
 * <li><b>JSONL 导入</b>：解析外部 {@code ai-calls.jsonl}（长跑 ai_audit.ps1 产物，
 * ActorAiCallLog 同构行），按项目 ID + scene + step_type + status + 内容非空过滤后取 N 条
 * ——DB 还原后仍可离线评估 v2 长跑捕获数据。</li>
 * </ul>
 * <p>⚠️ 实测口径：actor_ai_call_log 中 scene=agent_decision 是<b>决策链路
 * 级标记行</b>（step_type=thinking/learning/perception/action 等，prompt/response 为空）；
 * 真正的主决策 AI 调用记录为 <b>scene=action + step_type=ai_call</b>（完整决策提示词+决策输出）——
 * 采样必须过滤 step_type 与内容非空，否则样本全是空壳。</p>
 * <p>时间窗语义：windowGameMinutes 为<b>游戏分钟</b>，经世界时钟 rate 折算为真实分钟
 * （realMin = gameMin / rate，rate=每真实小时推进的游戏小时数）——近 1 游戏日 @rate=24 ≈ 近 1
 * 真实小时，与引擎推进口径一致。</p>
 * <p>角色名解析：characterId 先查特殊角色、再查普通 NPC；均无则 npcRef/npcName 置空
 * （项目级调用样本仍可评分）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionSampleSampler {

    private final ActorAiCallLogRepository aiCallLogRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository ordinaryNpcRepository;
    private final WorldClockService worldClockService;
    private final EvaluationProperties properties;

    /** 样本文本摘录长度上限（刺激/上下文；judge 输入另有 maxInputChars 总截断）。 */
    private static final int EXCERPT_MAX_CHARS = 6000;

    /**
     * DB 采样：近 windowGameMinutes 游戏分钟内的决策类 AI 调用（success），最新优先取 N 条。
     *
     * @param projectId 项目 ID
     *
     * @param sampleCount 采样条数（<=0 用配置默认）
     *
     * @param windowGameMinutes 游戏时间窗（分钟；null/<=0 用配置默认 1440）
     *
     * @param npcRefFilter 角色过滤（special:{id}/crowd:{id}，可空=不限）
     *
     * @return 决策样本列表（按最新优先）
     */
    public List<DecisionSample> sampleFromDb(Long projectId, Integer sampleCount, Integer windowGameMinutes,
                                             String npcRefFilter) {
        int count = sampleCount == null || sampleCount <= 0 ? properties.getDefaultSampleCount() : sampleCount;
        int gameMin = windowGameMinutes == null || windowGameMinutes <= 0
                ? properties.getDefaultWindowGameMinutes() : windowGameMinutes;
        LocalDateTime cutoff = dbCutoff(projectId, gameMin);
        List<String> scenes = properties.decisionScenes();

        Specification<ActorAiCallLog> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("projectId"), projectId));
            ps.add(root.get("scene").in(scenes));
            ps.add(cb.equal(root.get("status"), "success"));
            // ⚠️ 实测口径：仅真实 AI 调用（step_type=ai_call/空）——agent_decision 链路级标记行
            // （thinking/learning/perception/action 等 step_type）prompt/response 为空必须排除
            ps.add(cb.or(cb.isNull(root.get("stepType")), cb.equal(root.get("stepType"), "ai_call")));
            ps.add(cb.isNotNull(root.get("userPrompt")));
            ps.add(cb.notEqual(root.get("userPrompt"), ""));
            ps.add(cb.isNotNull(root.get("response")));
            ps.add(cb.notEqual(root.get("response"), ""));
            ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), cutoff));
            Long filterId = refId(npcRefFilter);
            if (filterId != null) {
                ps.add(cb.equal(root.get("characterId"), filterId));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        List<ActorAiCallLog> rows = aiCallLogRepository.findAll(spec,
                PageRequest.of(0, count, Sort.by(Sort.Direction.DESC, "id"))).getContent();
        List<DecisionSample> out = new ArrayList<>();
        for (ActorAiCallLog r : rows) {
            out.add(toSample(r, "db"));
        }
        log.info("[评估采样] DB 采样：project={} 窗={}游戏分钟 过滤={} 命中={}/{} scene={}",
                projectId, gameMin, npcRefFilter == null ? "-" : npcRefFilter, rows.size(), count, scenes);
        return out;
    }

    /**
     * JSONL 导入：解析外部 ai-calls.jsonl，按项目 + scene + success 过滤取 N 条。
     *
     * @param projectId 项目 ID
     *
     * @param jsonlPath JSONL 文件路径（绝对或相对当前工作目录）
     *
     * @param sampleCount 采样条数（<=0 用配置默认）
     *
     * @return 决策样本列表（文件内最新行优先）
     */
    public List<DecisionSample> sampleFromJsonl(Long projectId, String jsonlPath, Integer sampleCount) {
        int count = sampleCount == null || sampleCount <= 0 ? properties.getDefaultSampleCount() : sampleCount;
        List<String> scenes = properties.decisionScenes();
        List<DecisionSample> out = new ArrayList<>();
        Path path = Path.of(jsonlPath);
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new com.holzyn.actor.common.BizException(400, "JSONL 文件读取失败：" + jsonlPath + "（" + e.getMessage() + "）");
        }
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        // 文件内按行序即时间序，倒序取最新 N 条
        for (int i = lines.size() - 1; i >= 0 && out.size() < count; i--) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                tools.jackson.databind.JsonNode node = mapper.readTree(line);
                Long rowProject = node.path("project_id").isNull() ? null : node.path("project_id").asLong();
                if (rowProject != null && !rowProject.equals(projectId)) {
                    continue;
                }
                String scene = node.path("scene").asText("");
                if (!scenes.contains(scene)) {
                    continue;
                }
                String status = node.path("status").asText("");
                if (!"success".equals(status)) {
                    continue;
                }
                // ⚠️ 实测口径：仅真实 AI 调用（step_type=ai_call/空）且 prompt/response 非空
                String stepType = node.path("step_type").asText("");
                if (stepType != null && !stepType.isBlank() && !"ai_call".equals(stepType)) {
                    continue;
                }
                String up = node.path("user_prompt").asText("");
                String resp = node.path("response").asText("");
                if (up.isBlank() || resp.isBlank()) {
                    continue;
                }
                Long characterId = node.path("character_id").isNull() ? null : node.path("character_id").asLong();
                String traceId = node.path("trace_id").isNull() ? null : node.path("trace_id").asText("");
                out.add(new DecisionSample(
                        "jsonl",
                        characterId == null ? null : refOf(projectId, characterId),
                        characterId == null ? null : nameOf(projectId, characterId),
                        scene,
                        status,
                        null,
                        shorten(up, EXCERPT_MAX_CHARS),
                        shorten(node.path("system_prompt").asText(""), EXCERPT_MAX_CHARS),
                        resp,
                        traceId));
            } catch (Exception e) {
                log.debug("[评估采样] JSONL 行解析跳过：第 {} 行 : {}", i + 1, e.getMessage());
            }
        }
        log.info("[评估采样] JSONL 导入：project={} 文件={} 命中={}/{}", projectId, jsonlPath, out.size(), count);
        return out;
    }

    // ==================== 内部工具 ====================

    /** actor_ai_call_log → DecisionSample（角色名/ref 解析）。 */
    private DecisionSample toSample(ActorAiCallLog r, String source) {
        Long characterId = r.getCharacterId();
        String ref = characterId == null ? null : refOf(r.getProjectId(), characterId);
        return new DecisionSample(
                source,
                ref,
                characterId == null ? null : nameOf(r.getProjectId(), characterId),
                r.getScene(),
                r.getStatus(),
                null,
                shorten(r.getUserPrompt(), EXCERPT_MAX_CHARS),
                shorten(r.getSystemPrompt(), EXCERPT_MAX_CHARS),
                r.getResponse(),
                r.getTraceId());
    }

    /** DB 采样时间窗：windowGameMinutes（游戏分钟）经 rate 折算真实分钟截止时刻。 */
    private LocalDateTime dbCutoff(Long projectId, int windowGameMinutes) {
        int rate = 24;
        try {
            com.holzyn.actor.domain.world.entity.ActorWorldClock clock =
                    worldClockService.requireClock(projectId);
            if (clock.getRate() != null && clock.getRate() > 0) {
                rate = clock.getRate();
            }
        } catch (Exception ignored) {
            // 时钟不可得按默认 rate=24
        }
        long realMinutes = Math.max(1, Math.round(windowGameMinutes / (double) rate));
        return LocalDateTime.now().minusMinutes(realMinutes);
    }

    /** characterId → ref（先特殊后普通）。 */
    private String refOf(Long projectId, Long characterId) {
        if (characterId == null) {
            return null;
        }
        if (characterRepository.findById(characterId)
                .filter(c -> projectId.equals(c.getProjectId()))
                .isPresent()) {
            return com.holzyn.actor.common.NpcRefs.of(characterId);
        }
        if (ordinaryNpcRepository.findById(characterId)
                .filter(n -> projectId.equals(n.getProjectId()))
                .isPresent()) {
            return com.holzyn.actor.common.NpcRefs.of(characterId);
        }
        return null;
    }

    /** characterId → 角色名（先特殊后普通）。 */
    private String nameOf(Long projectId, Long characterId) {
        if (characterId == null) {
            return null;
        }
        Optional<ActorCharacter> ch = characterRepository.findById(characterId)
                .filter(c -> projectId.equals(c.getProjectId()));
        if (ch.isPresent()) {
            return ch.get().getName();
        }
        Optional<ActorCharacter> npc = ordinaryNpcRepository.findById(characterId)
                .filter(n -> projectId.equals(n.getProjectId()));
        return npc.map(ActorCharacter::getName).orElse(null);
    }

    /** npcRef → 数字 ID（special:{id}/crowd:{id}；非法 null）。 */
    private Long refId(String npcRef) {
        if (npcRef == null || !npcRef.contains(":")) {
            return null;
        }
        try {
            return Long.parseLong(npcRef.substring(npcRef.indexOf(':') + 1).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 文本截断（含省略号标记）。 */
    static String shorten(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "…";
    }
}
