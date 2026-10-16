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

package com.holzyn.actor.domain.assistant.service;

import com.holzyn.actor.domain.assistant.vo.AssistantCardVO;
import com.holzyn.actor.domain.assistant.vo.AssistantSourceVO;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.debug.service.DebugTraceService;
import com.holzyn.actor.domain.usage.service.UsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 助手运行观测数据源（能力域 E，M3）——面向对话解释的只读聚合。
 * <p>职责：把 {@code actor_ai_call_log}（场景/状态/Token/耗时/链路）聚合为<b>模型可读的文本
 * detail + 前端数据卡片</b>，供助手「解释某次决策」「查看 AI 调用统计/排障」两类问题使用。
 * 纯只读（复用 {@link DebugTraceService} 与 {@link ActorAiCallLogRepository}），零 AI、零写入。</p>
 * <p>聚合口径：<ul>
 * <li>{@link #traceChain}：trace_id 直查单链路（感知→思考→行动→学习 全环节）；无 trace_id 时
 * 按角色最近 N 条决策链路或项目最近链路列表；</li>
 * <li>{@link #aiCallStats}：窗口内按场景/状态二维聚合（次数/成功/失败/Token/耗时），含成功率与
 * 平均耗时。</li>
 * </ul></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantObservationService {

    /** 决策链路聚合服务（复用调试域既有实现）。 */
    private final DebugTraceService debugTraceService;

    /** AI 调用日志仓库（窗口统计用投影字段）。 */
    private final ActorAiCallLogRepository aiCallLogRepository;

    /** 默认统计窗口（天）。 */
    private static final int DEFAULT_DAYS = 1;

    /** 统计窗口上限（天）。 */
    private static final int MAX_DAYS = 90;

    /** 链路列表上限。 */
    private static final int DEFAULT_LIMIT = 3;

    /**
     * 观测结果（executor 组装 ToolResult 的中间载体）。
     *
     * @param summary 一句话摘要（SSE tool 事件）
     * @param detail  模型可读的详情文本（注入上下文）
     * @param sources 来源标注
     * @param card    数据卡片（可空）
     */
    public record ObsResult(String summary, String detail, List<AssistantSourceVO> sources,
                            AssistantCardVO card) {
    }

    /**
     * 决策链路查询：trace_id 直查单链路；否则按角色/项目取最近链路列表。
     *
     * @param projectId   项目 ID
     * @param traceId     决策链路 trace_id（可空；非空优先）
     * @param characterId 角色 ID（可空；traceId 为空时按角色）
     * @param characterName 角色名（展示用；可空）
     * @param limit       链路条数上限（默认 3）
     *
     * @return 观测结果
     */
    @SuppressWarnings("unchecked")
    public ObsResult traceChain(Long projectId, String traceId, Long characterId, String characterName, int limit) {
        int n = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, 10));
        // ① trace_id 直查单链路
        if (traceId != null && !traceId.isBlank()) {
            Map<String, Object> detail = debugTraceService.traceDetail(traceId.trim());
            List<Map<String, Object>> steps = (List<Map<String, Object>>) detail.get("steps");
            if (steps == null || steps.isEmpty()) {
                return new ObsResult("未找到该决策链路", "未找到 traceId=" + traceId + " 的决策链路（可能已不存在或未包裹链路）。",
                        sources("actor_ai_call_log·决策链路", null), null);
            }
            return new ObsResult("决策链路已获取（" + steps.size() + " 个环节）",
                    chainDetail(traceId.trim(), steps),
                    sources("actor_ai_call_log·决策链路", "traceId=" + traceId),
                    chainCard("决策链路 · " + shortTraceId(traceId), steps));
        }
        // ② 按角色最近 N 条决策轨迹
        if (characterId != null) {
            Map<String, Object> r = debugTraceService.characterTraces(characterId, n);
            List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");
            return new ObsResult("已获取「" + name(characterName, characterId) + "」最近 " + items.size() + " 次决策轨迹",
                    chainsDetail(items, "角色「" + name(characterName, characterId) + "」最近决策轨迹"),
                    sources("actor_ai_call_log·决策链路", "按角色 npc:" + characterId),
                    chainsCard("角色决策轨迹 · " + name(characterName, characterId), items));
        }
        // ③ 项目最近决策链路列表
        Map<String, Object> list = debugTraceService.traceList(projectId, null, null, null, null, null, null, 1, n);
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.get("items");
        if (items.isEmpty()) {
            return new ObsResult("暂无决策链路记录", "该项目暂无 AI 调用记录（埋点开启后出现）。",
                    sources("actor_ai_call_log·决策链路", null), null);
        }
        return new ObsResult("项目最近 " + items.size() + " 条决策链路",
                chainsDetail(items, "项目最近决策链路"),
                sources("actor_ai_call_log·决策链路", "项目 " + projectId),
                chainsCard("最近决策链路", items));
    }

    /**
     * AI 调用统计（窗口内按场景/状态聚合）。
     *
     * @param projectId 项目 ID
     * @param days      统计窗口（天，默认 1，上限 90）
     *
     * @return 观测结果
     */
    public ObsResult aiCallStats(Long projectId, int days) {
        int rangeDays = days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        LocalDateTime from = LocalDate.now().minusDays(rangeDays - 1L).atStartOfDay();
        List<ActorAiCallLog> logs;
        try {
            logs = aiCallLogRepository.findByProjectIdAndCreatedAtGreaterThanEqual(projectId, from);
        } catch (Exception e) {
            log.warn("[助手·观测] AI 调用统计查询失败（降级）: {}", e.getMessage());
            return new ObsResult("统计暂不可用", "AI 调用统计查询失败（数据源不可用）。",
                    sources("actor_ai_call_log·统计", null), null);
        }
        if (logs.isEmpty()) {
            return new ObsResult("近 " + rangeDays + " 天无 AI 调用",
                    "该项目近 " + rangeDays + " 天（自 " + from.toLocalDate() + "）无 AI 调用记录。",
                    sources("actor_ai_call_log·统计", "窗口 " + rangeDays + " 天"), null);
        }
        long count = logs.size();
        long success = logs.stream().filter(l -> "success".equals(l.getStatus())).count();
        long failed = logs.stream().filter(l -> "failed".equals(l.getStatus())).count();
        long degraded = logs.stream().filter(l -> "degraded".equals(l.getStatus())).count();
        long tokenIn = logs.stream().mapToLong(l -> nz(l.getTokenIn())).sum();
        long tokenOut = logs.stream().mapToLong(l -> nz(l.getTokenOut())).sum();
        long duration = logs.stream().mapToLong(l -> nz(l.getDurationMs())).sum();
        long avgMs = count == 0 ? 0 : Math.round(duration * 10.0 / count) / 10;

        // 按场景聚合
        Map<String, Scn> byScene = new LinkedHashMap<>();
        for (ActorAiCallLog l : logs) {
            byScene.computeIfAbsent(nzStr(l.getScene()), k -> new Scn()).add(l);
        }
        List<Map<String, Object>> scenes = byScene.values().stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("scene", s.scene);
                    m.put("sceneName", UsageService.sceneName(s.scene));
                    m.put("count", s.count);
                    m.put("success", s.success);
                    m.put("failed", s.failed);
                    m.put("tokenIn", s.tokenIn);
                    m.put("tokenOut", s.tokenOut);
                    m.put("avgDurationMs", s.count == 0 ? 0 : Math.round(s.duration * 10.0 / s.count) / 10.0);
                    return m;
                })
                .sorted(Comparator.comparingLong((Map<String, Object> m) -> (long) m.get("count")).reversed())
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("AI 调用统计（近 ").append(rangeDays).append(" 天，自 ").append(from.toLocalDate()).append("）：\n");
        sb.append("总调用 ").append(count).append(" 次：成功 ").append(success)
                .append(" / 失败 ").append(failed).append(" / 降级 ").append(degraded)
                .append("；成功率 ").append(pct(success, count)).append('\n');
        sb.append("Token：输入 ").append(tokenIn).append(" / 输出 ").append(tokenOut)
                .append("；平均耗时 ").append(avgMs).append("ms\n");
        sb.append("按场景（").append(scenes.size()).append(" 类）：\n");
        for (Map<String, Object> s : scenes) {
            sb.append("- ").append(s.get("sceneName")).append("：").append(s.get("count")).append(" 次")
                    .append("（成功 ").append(s.get("success")).append("/失败 ").append(s.get("failed")).append("）")
                    .append("，Token ").append(s.get("tokenIn")).append("/").append(s.get("tokenOut"))
                    .append("，均耗 ").append(s.get("avgDurationMs")).append("ms\n");
        }
        if (failed > 0 || degraded > 0) {
            sb.append("最近失败/降级调用：\n");
            logs.stream().filter(l -> !"success".equals(l.getStatus())).limit(3).forEach(l -> {
                sb.append("- ").append(UsageService.sceneName(l.getScene())).append("（")
                        .append(nzStr(l.getStatus())).append("）：")
                        .append(truncate(l.getErrorMessage(), 120)).append('\n');
            });
        }
        List<AssistantCardVO.Field> fields = new ArrayList<>();
        fields.add(new AssistantCardVO.Field("窗口", rangeDays + " 天"));
        fields.add(new AssistantCardVO.Field("总调用 / 成功率", count + " 次 / " + pct(success, count)));
        fields.add(new AssistantCardVO.Field("成功 / 失败 / 降级", success + " / " + failed + " / " + degraded));
        fields.add(new AssistantCardVO.Field("Token 入 / 出", tokenIn + " / " + tokenOut));
        fields.add(new AssistantCardVO.Field("平均耗时", avgMs + "ms"));
        fields.add(new AssistantCardVO.Field("场景数", String.valueOf(scenes.size())));
        return new ObsResult("AI 调用统计已获取（近 " + rangeDays + " 天）", sb.toString(),
                sources("actor_ai_call_log·统计", "窗口 " + rangeDays + " 天 · 更新于 " + LocalDateTime.now().toLocalDate()),
                new AssistantCardVO("stats", "AI 调用统计 · 近 " + rangeDays + " 天", fields));
    }

    // ==================== 文本/卡片组装 ====================

    /** 单链路（steps）→ 文本。 */
    private String chainDetail(String traceId, List<Map<String, Object>> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("决策链路 ").append(traceId).append("（").append(steps.size()).append(" 个环节）：\n");
        appendSteps(sb, steps);
        return sb.toString();
    }

    /** 链路列表 → 文本。 */
    private String chainsDetail(List<Map<String, Object>> chains, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("（").append(chains.size()).append(" 条）：\n");
        for (int i = 0; i < chains.size(); i++) {
            Map<String, Object> c = chains.get(i);
            sb.append(i + 1).append(". ").append(timeOf(c.get("time"))).append(" · ")
                    .append(sceneOf(c.get("scene"), c.get("sceneName")))
                    .append(" · ").append(c.get("stepCount")).append(" 环节 · ")
                    .append(statusLabel(c.get("status")))
                    .append(" · Token ").append(c.get("tokenIn")).append("/").append(c.get("tokenOut"))
                    .append(" · ").append(c.get("durationMs")).append("ms");
            if (c.get("characterName") != null) {
                sb.append(" · ").append(c.get("characterName"));
            }
            if (c.get("traceId") != null && !String.valueOf(c.get("traceId")).isBlank()) {
                sb.append("（trace ").append(shortTraceId(String.valueOf(c.get("traceId")))).append("）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** steps → 追加到 sb（每个环节一行）。 */
    private void appendSteps(StringBuilder sb, List<Map<String, Object>> steps) {
        for (Map<String, Object> s : steps) {
            String stepType = nzStr(s.get("stepType"));
            String stage = stageLabel(stepType, nzStr(s.get("scene")));
            sb.append("- ").append(stage).append("：");
            if ("ai_call".equals(stepType)) {
                sb.append(sceneOf(s.get("scene"), s.get("sceneName")))
                        .append(" · ").append(nzStr(s.get("status")))
                        .append(" · Token ").append(nz(s.get("tokenIn"))).append("/").append(nz(s.get("tokenOut")))
                        .append(" · ").append(nz(s.get("durationMs"))).append("ms");
            } else {
                sb.append(sceneOf(s.get("scene"), s.get("sceneName")));
            }
            String content = s.get("content") == null ? null : String.valueOf(s.get("content"));
            if (content != null && !content.isBlank()) {
                sb.append(" · ").append(truncate(content, 80));
            }
            sb.append('\n');
        }
    }

    /** 单链路 steps → 卡片。 */
    private AssistantCardVO chainCard(String title, List<Map<String, Object>> steps) {
        List<AssistantCardVO.Field> fields = new ArrayList<>();
        for (Map<String, Object> s : steps) {
            String stage = stageLabel(nzStr(s.get("stepType")), nzStr(s.get("scene")));
            String line = sceneOf(s.get("scene"), s.get("sceneName"));
            if ("ai_call".equals(s.get("stepType"))) {
                line += " · " + nzStr(s.get("status")) + " · " + nz(s.get("tokenIn")) + "/" + nz(s.get("tokenOut"));
            }
            fields.add(new AssistantCardVO.Field(stage, truncate(line, 46)));
        }
        return new AssistantCardVO("trace", title, fields);
    }

    /** 链路列表 → 卡片。 */
    private AssistantCardVO chainsCard(String title, List<Map<String, Object>> chains) {
        List<AssistantCardVO.Field> fields = new ArrayList<>();
        for (int i = 0; i < chains.size(); i++) {
            Map<String, Object> c = chains.get(i);
            String label = timeOf(c.get("time")) + " · " + sceneOf(c.get("scene"), c.get("sceneName"));
            String value = c.get("stepCount") + " 环节 · " + statusLabel(c.get("status"))
                    + " · " + c.get("tokenIn") + "/" + c.get("tokenOut") + " · " + c.get("durationMs") + "ms";
            fields.add(new AssistantCardVO.Field(label, value));
        }
        return new AssistantCardVO("trace", title, fields);
    }

    // ==================== 工具函数 ====================

    /** 阶段中文标签（对齐调试前端五段）。 */
    private String stageLabel(String stepType, String scene) {
        String st = stepType == null ? "" : stepType;
        return switch (st) {
            case "perception" -> "感知";
            case "action" -> "行动";
            case "event" -> "事件";
            case "memory" -> "学习";
            default -> {
                if ("action".equals(scene)) {
                    yield "思考";
                }
                if ("memory".equals(scene) || "reflection".equals(scene) || "goal_gen".equals(scene)
                        || "plan_day".equals(scene)) {
                    yield "学习";
                }
                yield "扮演";
            }
        };
    }

    /** 场景显示名（优先中文名）。 */
    private String sceneOf(Object scene, Object sceneName) {
        String sn = sceneName == null ? null : String.valueOf(sceneName);
        if (sn != null && !sn.isBlank()) {
            return sn;
        }
        return scene == null ? "—" : String.valueOf(scene);
    }

    /** 时间显示（ISO → 短格式；空 → —）。 */
    private String timeOf(Object iso) {
        if (iso == null) {
            return "—";
        }
        String s = String.valueOf(iso);
        return s.length() > 19 ? s.substring(0, 19).replace('T', ' ') : s;
    }

    /** 状态中文标签。 */
    private String statusLabel(Object status) {
        String s = status == null ? "" : String.valueOf(status);
        return switch (s) {
            case "success" -> "成功";
            case "failed" -> "失败";
            case "degraded" -> "降级";
            default -> s.isEmpty() ? "—" : s;
        };
    }

    /** trace_id 短显。 */
    private String shortTraceId(String id) {
        if (id == null) {
            return "—";
        }
        return id.length() > 14 ? id.substring(0, 14) + "…" : id;
    }

    /** 角色名显示（无则 npc:id）。 */
    private String name(String characterName, Long characterId) {
        if (characterName != null && !characterName.isBlank()) {
            return characterName;
        }
        return characterId == null ? "—" : "npc:" + characterId;
    }

    /** 百分比。 */
    private String pct(long part, long total) {
        if (total <= 0) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.1f%%", part * 100.0 / total);
    }

    /** 截断。 */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** int 空值安全。 */
    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /** Object(int) 空值安全。 */
    private long nz(Object v) {
        if (v == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Object 空值安全串。 */
    private String nzStr(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 来源列表。 */
    private List<AssistantSourceVO> sources(String source, String detail) {
        List<AssistantSourceVO> list = new ArrayList<>();
        list.add(new AssistantSourceVO(source, detail));
        return list;
    }

    /** 场景聚合累加器。 */
    private static final class Scn {
        String scene = "";
        long count;
        long success;
        long failed;
        long tokenIn;
        long tokenOut;
        long duration;

        void add(ActorAiCallLog l) {
            if (scene.isEmpty()) {
                scene = l.getScene() == null ? "" : l.getScene();
            }
            count++;
            if ("success".equals(l.getStatus())) {
                success++;
            } else if ("failed".equals(l.getStatus())) {
                failed++;
            }
            tokenIn += l.getTokenIn() == null ? 0 : l.getTokenIn();
            tokenOut += l.getTokenOut() == null ? 0 : l.getTokenOut();
            duration += l.getDurationMs() == null ? 0 : l.getDurationMs();
        }
    }
}
