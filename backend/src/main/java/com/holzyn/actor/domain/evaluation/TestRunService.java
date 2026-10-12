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

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.agent.runtime.AgentRuntime;
import com.holzyn.actor.domain.agent.runtime.WorldEngine;
import com.holzyn.actor.domain.agent.roleplay.AutonomousChatService;
import com.holzyn.actor.domain.evaluation.entity.ActorTestRun;
import com.holzyn.actor.domain.evaluation.entity.ActorTestRunEvent;
import com.holzyn.actor.domain.evaluation.repository.ActorTestRunEventRepository;
import com.holzyn.actor.domain.evaluation.repository.ActorTestRunRepository;
import com.holzyn.actor.domain.evaluation.vo.TestRunEventVO;
import com.holzyn.actor.domain.evaluation.vo.TestRunVO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.dto.WorldClockDTO;
import com.holzyn.actor.domain.world.service.TimelineService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 专门测试接口·测试运行门面（五能力：① 阶段控制 + ③ 干预驱动 + ⑤ test_run_log 完整回放，
 * ，domain/evaluation）。
 * <p>职责：把一次「测试会话」组织为一条可回放的时间线——</p>
 * <ul>
 * <li><b>① 阶段控制</b>：phase_pause / phase_resume（世界暂停/恢复）、phase_advance（手动补推一轮）、
 * phase_fastforward、phase_intensity（切 AI 强度四档）；</li>
 * <li><b>③ 干预驱动</b>：intervene_event（注入事件）、intervene_action（触发行动，仅特殊角色）、
 * trigger_chat；</li>
 * <li><b>⑤ test_run_log 完整回放</b>：每次命令/干预落一条 {@link ActorTestRunEvent}
 * （command/intervene 类型）+ 命令后自动附一条状态快照（snapshot 类型，四块聚合），
 * 评估批次经 eval_ref 关联（②④ 为独立只读/批跑能力）；</li>
 * <li><b>test.enabled 门控</b>：默认 false，关闭时全部测试接口抛 403「测试接口未启用」。。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestRunService {

    private final ActorTestRunRepository runRepository;
    private final ActorTestRunEventRepository eventRepository;
    private final WorldClockService worldClockService;
    private final WorldEngine worldEngine;
    private final AiIntensityGate aiIntensityGate;
    private final TimelineService timelineService;
    private final AgentRuntime agentRuntime;
    private final AutonomousChatService autonomousChatService;
    private final TestStateAggregator stateAggregator;
    private final EvaluationProperties properties;
    private final CurrentUserProvider currentUserProvider;
    private final ActorCharacterRepository characterRepository;

    /**
     * NPC 类型解析器（：npc:{id} 不再编码类型，"是否特殊型"改查 actor_profile.type；
     * 字段注入避免改构造器破坏存量单测，null=回退特殊型——与历史前缀判定行为一致）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.npc.profile.service.NpcTypeResolver npcTypeResolver;

    
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 创建 test run。 */
    @Transactional
    public TestRunVO createRun(Long projectId, String name, String purpose) {
        requireTestEnabled();
        ActorTestRun run = new ActorTestRun();
        run.setProjectId(projectId);
        run.setName(name == null || name.isBlank() ? "未命名测试" : name);
        run.setPurpose(purpose);
        run.setStatus("active");
        run.setStartGameSecond(engineSecond(projectId));
        run.setStartRealMs(System.currentTimeMillis());
        run = runRepository.save(run);
        log.info("[测试接口] 创建 test run：project={} run={} name={}", projectId, run.getId(), run.getName());
        return TestRunVO.of(run);
    }

    /**
     * 执行一条命令（① 阶段控制 / ③ 干预驱动 / eval_ref 关联），自动落时间线 + 状态快照。
     *
     * @param projectId 项目 ID
     *
     * @param action 动作名
     *
     * @param params 动作参数（可空 Map）
     *
     * @return 事件 VO（command/intervene 行；快照行随后落库）
     */
    @Transactional
    public TestRunEventVO executeCommand(Long projectId, Long runId, String action, Map<String, Object> params) {
        requireTestEnabled();
        ActorTestRun run = requireRun(projectId, runId);
        if (!"active".equals(run.getStatus())) {
            throw new BizException(400, "test run 已结束（status=" + run.getStatus() + "），不能继续执行命令");
        }
        if (action == null || action.isBlank()) {
            throw new BizException(400, "action 不能为空");
        }
        Map<String, Object> safeParams = params == null ? Map.of() : new LinkedHashMap<>(params);
        Map<String, Object> result = new LinkedHashMap<>();
        String eventType;
        Long userId = currentUserProvider.currentUserId();

        switch (action) {
            // ==================== ① 阶段控制 ====================
            case "phase_pause" -> {
                eventType = "command";
                worldClockService.updateClock(projectId, userId,
                        new WorldClockDTO(null, true, null, null, null));
                result.put("message", "世界已暂停");
            }
            case "phase_resume" -> {
                eventType = "command";
                worldClockService.updateClock(projectId, userId,
                        new WorldClockDTO(null, false, null, null, null));
                result.put("message", "世界已恢复推进");
            }
            case "phase_advance" -> {
                eventType = "command";
                Map<String, Object> r = worldEngine.advanceProjectForce(projectId, true);
                result.put("status", r.get("status"));
                result.put("advancedHours", r.get("advancedHours"));
                result.put("gameHour", r.get("gameHour"));
                result.put("summary", r.get("summary"));
                result.put("message", "手动补推一轮完成");
            }
            case "phase_fastforward" -> {
                eventType = "command";
                Long target = longOf(safeParams.get("targetGameSecond"));
                Long plusMin = longOf(safeParams.get("plusGameMinutes"));
                Long plusHour = longOf(safeParams.get("plusGameHours"));
                Long plusDay = longOf(safeParams.get("plusGameDays"));
                if (target == null && plusMin == null && plusHour == null && plusDay == null) {
                    throw new BizException(400, "phase_fastforward 需参数：targetGameSecond 或 plusGameMinutes/Hours/Days 至少一项");
                }
                var vo = worldClockService.fastForward(projectId, target, plusMin, plusHour, plusDay);
                result.put("gameSecond", vo.getEngineGameSecond());
                result.put("gameHour", vo.getGameHour());
                result.put("gameTimeText", vo.getGameTimeText());
                result.put("message", "时钟已快进到 " + vo.getGameTimeText());
            }
            case "phase_intensity" -> {
                eventType = "command";
                String level = str(safeParams.get("level"));
                if (level == null || level.isBlank()) {
                    throw new BizException(400, "phase_intensity 需参数 level（programmatic/light/full/max）");
                }
                String normalized = AiIntensityGate.normalize(level);
                aiIntensityGate.setIntensity(projectId, normalized);
                result.put("aiIntensity", normalized);
                result.put("aiIntensityLabel", AiIntensityGate.label(normalized));
                result.put("message", "AI 强度已切换为 " + AiIntensityGate.label(normalized));
            }
            // ==================== ③ 干预驱动 ====================
            case "intervene_event" -> {
                eventType = "intervene";
                String content = str(safeParams.get("content"));
                if (content == null || content.isBlank()) {
                    throw new BizException(400, "intervene_event 需参数 content（事件内容）");
                }
                Map<String, Object> r = timelineService.createEvent(projectId, safeParams);
                result.put("eventId", r.get("id"));
                result.put("message", "事件已注入（importance 缺省按时间线默认）");
            }
            case "intervene_action" -> {
                eventType = "intervene";
                String npcRef = str(safeParams.get("npcRef"));
                // ：npc:{id} 不再编码类型，此处按意图 B 处理（仅特殊型支持手动触发行动；解析器未装配按引用合法即放行）
                if (npcRef == null || !NpcRefs.isNpcRef(npcRef)
                        || (npcTypeResolver != null && !npcTypeResolver.isSpecial(projectId, npcRef))) {
                    throw new BizException(400, "intervene_action 需参数 npcRef（npc:{id}，仅特殊角色）");
                }
                Long characterId = NpcRefs.idOf(npcRef);
                ActorCharacter ch = characterRepository.findById(characterId)
                        .filter(c -> projectId.equals(c.getProjectId()))
                        .filter(c -> Integer.valueOf(0).equals(c.getDeleted()))
                        .orElseThrow(() -> new BizException(404, "角色不存在或不属于该项目"));
                String reason = str(safeParams.get("reason"));
                var plan = agentRuntime.triggerAction(characterId, userId, "manual", null, reason, null, null);
                result.put("planId", plan.getId());
                result.put("characterName", ch.getName());
                result.put("message", "行动已触发（思考决策 + 计划持久化）");
            }
            case "trigger_chat" -> {
                eventType = "intervene";
                Long locationId = longOf(safeParams.get("locationId"));
                String locationName = str(safeParams.get("locationName"));
                Map<String, Object> r = autonomousChatService.triggerEpisodeForTest(
                        projectId, userId, locationId, locationName);
                result.putAll(r);
            }
            // ==================== ⑤ 评估批次关联 ====================
            case "eval_ref" -> {
                eventType = "eval_ref";
                Long evalRunId = longOf(safeParams.get("evalRunId"));
                if (evalRunId == null) {
                    throw new BizException(400, "eval_ref 需参数 evalRunId");
                }
                result.put("evalRunId", evalRunId);
                result.put("message", "评估批次已关联到本 test run");
            }
            default -> throw new BizException(400, "未知 action：" + action
                    + "（支持 phase_pause/phase_resume/phase_advance/phase_fastforward/phase_intensity/"
                    + "intervene_event/intervene_action/trigger_chat/eval_ref）");
        }

        // 计数（阶段控制命令 vs 干预动作）
        if ("command".equals(eventType)) {
            run.setCommandCount((run.getCommandCount() == null ? 0 : run.getCommandCount()) + 1);
        } else if ("intervene".equals(eventType)) {
            run.setInterveneCount((run.getInterveneCount() == null ? 0 : run.getInterveneCount()) + 1);
        }
        if ("eval_ref".equals(eventType)) {
            appendEvalRef(run, longOf(safeParams.get("evalRunId")));
        }
        runRepository.save(run);

        // 落命令事件行
        long gameSecond = engineSecond(projectId);
        ActorTestRunEvent ev = new ActorTestRunEvent();
        ev.setRunId(runId);
        ev.setSeq(nextSeq(runId));
        ev.setEventType(eventType);
        ev.setAction(action);
        ev.setParamsJson(toJson(safeParams));
        ev.setResultJson(toJson(result));
        ev.setGameSecond(gameSecond);
        ev = eventRepository.save(ev);
        // 命令后状态快照（完整回放：每次命令后自动附四块聚合快照）
        if (!"eval_ref".equals(eventType)) {
            snapshotEvent(runId, projectId);
        }
        return TestRunEventVO.of(ev);
    }

    /** 收尾 test run（⑤：status=finished + 汇总 JSON）。 */
    @Transactional
    public TestRunVO finishRun(Long projectId, Long runId) {
        requireTestEnabled();
        ActorTestRun run = requireRun(projectId, runId);
        run.setStatus("finished");
        run.setEndRealMs(System.currentTimeMillis());
        run.setEndGameSecond(engineSecond(projectId));
        // 汇总 JSON
        ObjectNode summary = MAPPER.createObjectNode();
        long startSecond = run.getStartGameSecond() == null ? 0L : run.getStartGameSecond();
        long endSecond = engineSecond(projectId);
        summary.put("startRealMs", run.getStartRealMs());
        summary.put("endRealMs", run.getEndRealMs());
        summary.put("durationMs", (run.getEndRealMs() == null ? 0L : run.getEndRealMs())
                - (run.getStartRealMs() == null ? 0L : run.getStartRealMs()));
        summary.put("startGameSecond", startSecond);
        summary.put("endGameSecond", endSecond);
        summary.put("advancedGameSeconds", endSecond - startSecond);
        summary.put("commandCount", run.getCommandCount());
        summary.put("interveneCount", run.getInterveneCount());
        summary.put("eventCount", eventRepository.countByRunId(runId));
        summary.put("note", "test_run_log 完整回放：GET /test/runs/" + runId + " 查看命令/干预/快照时间线");
        run.setSummaryJson(summary.toString());
        runRepository.save(run);
        log.info("[测试接口] 收尾 test run：project={} run={} 命令={} 干预={}",
                projectId, runId, run.getCommandCount(), run.getInterveneCount());
        return TestRunVO.of(run);
    }

    /** 中止 test run。 */
    @Transactional
    public TestRunVO abortRun(Long projectId, Long runId) {
        requireTestEnabled();
        ActorTestRun run = requireRun(projectId, runId);
        run.setStatus("aborted");
        run.setEndRealMs(System.currentTimeMillis());
        runRepository.save(run);
        return TestRunVO.of(run);
    }

    /** test run 列表。 */
    public List<TestRunVO> listRuns(Long projectId) {
        requireTestEnabled();
        return runRepository.findTop20ByProjectIdOrderByIdDesc(projectId).stream()
                .map(TestRunVO::of).toList();
    }

    /** test run 完整回放（⑤：run + 命令/干预/快照时间线）。 */
    public Map<String, Object> runDetail(Long projectId, Long runId) {
        requireTestEnabled();
        ActorTestRun run = requireRun(projectId, runId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", TestRunVO.of(run));
        out.put("events", eventRepository.findByRunIdOrderBySeqAsc(runId).stream()
                .map(TestRunEventVO::of).toList());
        return out;
    }

    /** 状态聚合（②，独立读接口 + 命令后快照共用）。 */
    public Map<String, Object> state(Long projectId, Integer windowGameMinutes) {
        requireTestEnabled();
        return stateAggregator.aggregate(projectId, windowGameMinutes);
    }

    // ==================== 内部工具 ====================

    /** test.enabled 门控。 */
    public void requireTestEnabled() {
        if (!properties.isTestEnabled()) {
            throw new BizException(403, "测试接口未启用（holzyn.actor.evaluation.test-enabled=false）——开启后重试");
        }
    }

    /** 命令后的状态快照事件（完整回放：四块聚合落 state_snapshot_json）。 */
    private void snapshotEvent(Long runId, Long projectId) {
        ActorTestRunEvent snap = new ActorTestRunEvent();
        snap.setRunId(runId);
        snap.setSeq(nextSeq(runId));
        snap.setEventType("snapshot");
        snap.setAction("state_snapshot");
        snap.setParamsJson("{}");
        try {
            snap.setStateSnapshotJson(toJson(stateAggregator.aggregate(projectId, null)));
        } catch (Exception e) {
            snap.setStateSnapshotJson("{\"error\":\"" + e.getMessage() + "\"}");
        }
        snap.setGameSecond(engineSecond(projectId));
        eventRepository.save(snap);
    }

    /** 关联评估批次到 run（eval_run_ids_json 追加，幂等）。 */
    private void appendEvalRef(ActorTestRun run, Long evalRunId) {
        if (evalRunId == null) {
            return;
        }
        try {
            ArrayNode arr = MAPPER.createArrayNode();
            if (run.getEvalRunIdsJson() != null && !run.getEvalRunIdsJson().isBlank()) {
                var node = MAPPER.readTree(run.getEvalRunIdsJson());
                if (node.isArray()) {
                    arr = (ArrayNode) node;
                }
            }
            boolean exists = false;
            for (var n : arr) {
                if (n.isNumber() && n.asLong() == evalRunId) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                arr.add(evalRunId);
            }
            run.setEvalRunIdsJson(arr.toString());
        } catch (Exception e) {
            log.warn("[测试接口] eval_ref 关联失败（不影响命令执行）: run={} evalRunId={} : {}",
                    run.getId(), evalRunId, e.getMessage());
        }
    }

    /** 校验 run 存在且属于项目。 */
    private ActorTestRun requireRun(Long projectId, Long runId) {
        ActorTestRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BizException(404, "test run 不存在：" + runId));
        if (!projectId.equals(run.getProjectId())) {
            throw new BizException(404, "test run 不属于该项目");
        }
        return run;
    }

    /** 引擎当前游戏秒位置（不可得 0）。 */
    private long engineSecond(Long projectId) {
        Long s = worldClockService.currentEngineGameSecond(projectId);
        return s == null ? 0L : s;
    }

    /** 下一个事件序号。 */
    private int nextSeq(Long runId) {
        return (int) eventRepository.countByRunId(runId) + 1;
    }

    
    static String toJson(Map<?, ?> map) {
        try {
            return MAPPER.writeValueAsString(map == null ? Map.of() : map);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Map 取字符串（null 安全）。 */
    private String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    /** Map 取 Long（null 安全）。 */
    private Long longOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
