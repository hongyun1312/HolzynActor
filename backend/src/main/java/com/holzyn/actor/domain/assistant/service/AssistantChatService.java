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

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiUsage;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.assistant.config.AssistantProperties;
import com.holzyn.actor.domain.assistant.entity.ActorAssistantMessage;
import com.holzyn.actor.domain.assistant.vo.AssistantCardVO;
import com.holzyn.actor.domain.assistant.vo.AssistantSourceVO;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.world.service.WorldGraphQueryService;
import com.holzyn.actor.domain.world.vo.WorldGraphVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 助手对话编排（能力域 A/B/C/D/G 的主链路）——「function calling 工具循环 + SSE 流式 + 会话持久化」。
 * <p><b>流程</b>：用户消息落库 → 双模式判定 → 世界上下文轻量注入 → 工具决策循环
 * （jsonMode 调用产出 {@code {"tool_calls":[...]}}，逐工具执行并回喂结果，≤ maxToolRounds 轮）→
 * 最终回答<b>流式</b>调用（PLAIN_TEXT，SSE 逐 token 推送）→ 卡片/来源/快速追问组装 → 消息落库 → done。</p>
 * <p><b>架构不变量（强制）</b>：本服务含 AI 调用，<b>不得带 {@code @Transactional}</b>
 * （由 {@code AiCallOutsideTransactionTest} 反射断言守护）；落库全部委托
 * {@link AssistantSessionService}（其自身短事务）；AI 调用包在 {@link AiCallContext#run} 内
 * 使 scene=assistant_tool/assistant_chat 进入 trace 记账与场景路由。</p>
 * <p><b>失败即降级</b>：工具执行失败仅降级该工具；工具决策调用失败直接进入无工具回答；
 * 最终回答调用失败 → SSE error 事件（friendly 提示），不编造。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantChatService {

    /** Jackson 3 序列化器（工具决策 JSON 解析 / 结构化载荷序列化）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具决策场景码（trace/路由/模板版本留痕）。 */
    public static final String SCENE_TOOL = "assistant_tool";

    /** 最终回答场景码。 */
    public static final String SCENE_ANSWER = "assistant_chat";

    private final AssistantSessionService sessionService;
    private final AssistantToolExecutor toolExecutor;
    private final AssistantModeResolver modeResolver;
    private final AiSpringChatGateway aiChatGateway;
    private final WorldGraphQueryService worldGraph;
    private final CurrentUserProvider currentUserProvider;
    private final AssistantProperties properties;

    /**
     * 世界干预服务（能力域 F，M4）——字段注入（不改变构造签名；confirm SSE 载荷查询待确认计划用）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AssistantInterventionService interventionService;

    /**
     * SSE 事件输出口（控制器适配 SseEmitter；测试用捕获型假实现）。
     */
    @FunctionalInterface
    public interface SseSink {
        /** @return false=连接已断开（调用方停止后续推送） */
        boolean send(String name, Object data);
    }

    /**
     * 一轮完整对话（SSE 流式）。
     *
     * @param projectId   项目 ID
     * @param sessionId   会话 ID
     * @param question    用户问题（非空）
     * @param modeOverride 逐轮模式覆盖（narrative/fact；可空=按会话默认 + 意图判定）
     * @param sink        SSE 输出口
     */
    public void chat(Long projectId, Long sessionId, String question, String modeOverride, SseSink sink) {
        if (!properties.isEnabled()) {
            sink.send("error", Map.of("message", "项目级 AI 助手未启用（holzyn.actor.assistant.enabled=false）"));
            return;
        }
        Long userId = currentUserProvider.currentUserId();
        // ① 用户消息落库（标题首条生成）
        sessionService.appendUserMessage(projectId, sessionId, question);
        // ② 双模式判定（API 层覆盖 > 显式指令/意图 > 会话默认；modeOverride 独立传入不污染问题）
        String sessionDefault = sessionService.get(projectId, sessionId).defaultMode();
        AssistantModeResolver.ModeDecision decision = modeResolver.resolve(question, sessionDefault, modeOverride);
        String mode = decision.mode();
        String cleanQuestion = decision.cleanQuestion();
        // M5 打磨：元问题/闲聊判定——自我介绍、寒暄、项目功能询问等与当前世界状态无关的问题，
        // 不注入世界上下文、用问答式回答（避免「介绍一下自己」被套上游戏时间/场景描写）。
        boolean metaQuestion = isMetaQuestion(cleanQuestion);
        sink.send("meta", Map.of("mode", mode, "sessionId", sessionId));
        // ③ 工具循环
        List<AssistantToolExecutor.ToolResult> executed = new ArrayList<>();
        List<Map<String, Object>> toolRecord = new ArrayList<>();
        List<AiChatRequest.ChatMessage> toolResultMessages = new ArrayList<>();
        // 本轮内「工具名+参数」去重：模型可能在同一轮重复声明同一工具（实测 macro_overview ×3），
        // 重复执行浪费 AI 且无新信息——命中重复直接复用上次结果并跳过执行（工具循环仍是模型收敛，
        // 去重只省执行；提示词同时明示「已有数据直接答」减少冗余轮）。
        java.util.Map<String, AssistantToolExecutor.ToolResult> executedIndex = new java.util.LinkedHashMap<>();
        int maxRounds = Math.max(1, properties.getMaxToolRounds());
        boolean loopBroken = false;
        // M4：是否命中「待确认干预」（world_intervene 返回 confirm 卡片 → 终止生成，等待用户确认）
        boolean awaitingConfirm = false;
        Map<String, Object> confirmPayload = null;
        for (int round = 1; round <= maxRounds && !loopBroken; round++) {
            List<AssistantToolExecutor.ToolCall> calls;
            try {
                calls = decideTools(projectId, sessionId, userId, cleanQuestion, mode, metaQuestion, toolResultMessages);
            } catch (Exception e) {
                log.warn("[助手] 工具决策调用失败（round {}），进入无工具回答: {}", round, e.getMessage());
                break;
            }
            if (calls.isEmpty()) {
                loopBroken = true;
                break;
            }
            for (AssistantToolExecutor.ToolCall call : calls) {
                // 世界干预需要会话上下文（确认粒度来源）——把 _sessionId 注入模型不可见的内部参数
                Map<String, Object> callArgs = call.arguments();
                if ("world_intervene".equals(call.name())) {
                    callArgs = new java.util.LinkedHashMap<>(callArgs == null ? Map.of() : callArgs);
                    callArgs.put("_sessionId", sessionId);
                }
                // 去重签名 = 工具名 + 递归排序键后的规范化参数（防同参因键序不同漏判）
                String signature = call.name() + "|" + canonicalArgs(callArgs);
                AssistantToolExecutor.ToolResult tr = executedIndex.get(signature);
                boolean duplicate = tr != null;
                if (tr == null) {
                    tr = toolExecutor.execute(projectId, call.name(), callArgs);
                    executedIndex.put(signature, tr);
                }
                if (duplicate) {
                    log.info("[助手] 工具重复调用已跳过（复用上次结果）: {}", signature);
                    continue;
                }
                executed.add(tr);
                toolRecord.add(Map.of("name", tr.name(), "summary", tr.summary()));
                if (!sink.send("tool", Map.of("name", tr.name(), "summary", tr.summary()))) {
                    log.info("[助手] SSE 连接已断开，终止生成: project={} session={}", projectId, sessionId);
                    return;
                }
                // M4：确认拦截——工具返回 confirm 卡片（待确认干预）→ 推 confirm 事件并终止本轮生成
                if (tr.card() != null && "confirm".equals(tr.card().type())) {
                    awaitingConfirm = true;
                    confirmPayload = confirmPayload(projectId, sessionId);
                    if (!sink.send("confirm", confirmPayload == null ? Map.of() : confirmPayload)) {
                        log.info("[助手] SSE 连接已断开，终止生成: project={} session={}", projectId, sessionId);
                        return;
                    }
                    loopBroken = true;
                    break;
                }
                toolResultMessages.add(new AiChatRequest.ChatMessage("user",
                        "【工具结果 " + tr.name() + "】\n" + tr.detail()));
            }
        }
        // ④ 待确认路径：终止生成，落一条「需确认」助手消息 + done（awaitingConfirm）
        if (awaitingConfirm) {
            AssistantToolExecutor.ToolResult pending = executed.stream()
                    .filter(t -> t.card() != null && "confirm".equals(t.card().type()))
                    .reduce((a, b) -> b)
                    .orElse(null);
            String content = pending == null ? "需要你确认一项世界干预。" : pending.detail();
            try {
                sessionService.appendAssistantMessage(projectId, sessionId, content, mode,
                        pending == null ? null : json(pending.card()),
                        pending == null ? null : json(pending.sources()),
                        json(toolRecord), null);
            } catch (Exception e) {
                log.warn("[助手] 确认消息落库失败: {}", e.getMessage());
            }
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("content", content);
            done.put("mode", mode);
            done.put("awaitingConfirm", true);
            done.put("confirm", confirmPayload);
            if (pending != null) {
                done.put("card", pending.card());
                done.put("sources", pending.sources());
            }
            done.put("toolCalls", toolRecord);
            sink.send("done", done);
            return;
        }
        // ⑤ 最终回答流式
        StringBuilder answer = new StringBuilder();
        try {
            streamAnswer(projectId, sessionId, userId, cleanQuestion, mode, metaQuestion, toolResultMessages, delta -> {
                answer.append(delta);
                sink.send("token", Map.of("delta", delta));
            });
        } catch (Exception e) {
            String msg = e instanceof AiCallException ae && ae.getMessage() != null
                    ? ae.getMessage() : "回答生成失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
            log.warn("[助手] 最终回答流式失败: project={} session={} : {}", projectId, sessionId, e.getMessage());
            sink.send("error", Map.of("message", msg));
            return;
        }
        // ⑤ 组装：卡片（最后一个带卡片的工具）、来源（去重）、快速追问（启发式）
        AssistantCardVO card = executed.stream()
                .filter(t -> t.card() != null)
                .reduce((a, b) -> b)
                .map(AssistantToolExecutor.ToolResult::card)
                .orElse(null);
        List<AssistantSourceVO> sources = dedupeSources(executed);
        List<String> quickFollows = quickFollows(executed);
        // ⑥ 消息落库 + done
        try {
            ActorAssistantMessage saved = sessionService.appendAssistantMessage(projectId, sessionId,
                    answer.toString(), mode, json(card), json(sources), json(toolRecord), json(quickFollows));
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("messageId", saved.getId());
            done.put("content", answer.toString());
            done.put("mode", mode);
            done.put("card", card);
            done.put("sources", sources);
            done.put("quickFollows", quickFollows);
            done.put("toolCalls", toolRecord);
            sink.send("done", done);
        } catch (Exception e) {
            log.warn("[助手] 消息落库失败（回答已生成）: {}", e.getMessage());
            sink.send("done", Map.of("content", answer.toString(), "mode", mode));
        }
    }

    // ==================== 内部：AI 调用（全部在 AiCallContext 内，无事务） ====================

    /**
     * 工具决策调用（jsonMode）：返回模型声明的工具调用列表（无工具=空列表）。
     */
    private List<AssistantToolExecutor.ToolCall> decideTools(Long projectId, Long sessionId, Long userId,
                                                             String question, String mode, boolean metaQuestion,
                                                             List<AiChatRequest.ChatMessage> toolResults) {
        PromptSpecRegistry.PromptSpec spec = PromptSpecRegistry.spec(SCENE_TOOL);
        String system = systemPrompt(projectId, mode, true, metaQuestion);
        List<AiChatRequest.ChatMessage> messages = new ArrayList<>();
        messages.add(new AiChatRequest.ChatMessage("system", system));
        messages.addAll(history(sessionId));
        messages.addAll(toolResults);
        messages.add(new AiChatRequest.ChatMessage("user", question));
        AiChatRequest request = new AiChatRequest(null, messages, spec.temperature(), spec.maxOutputTokens(), true);
        AiChatResult result = AiCallContext.run(SCENE_TOOL, null, () ->
                aiChatGateway.chatCompletionJson(userId, projectId, null, request));
        return parseToolCalls(result.content());
    }

    /**
     * 最终回答流式调用（PLAIN_TEXT；逐 token 回调）。
     */
    private void streamAnswer(Long projectId, Long sessionId, Long userId, String question, String mode,
                              boolean metaQuestion,
                              List<AiChatRequest.ChatMessage> toolResults, java.util.function.Consumer<String> onToken) {
        PromptSpecRegistry.PromptSpec spec = PromptSpecRegistry.spec(SCENE_ANSWER);
        String system = systemPrompt(projectId, mode, false, metaQuestion);
        List<AiChatRequest.ChatMessage> messages = new ArrayList<>();
        messages.add(new AiChatRequest.ChatMessage("system", system));
        messages.addAll(history(sessionId));
        messages.addAll(toolResults);
        messages.add(new AiChatRequest.ChatMessage("user", question));
        AiChatRequest request = new AiChatRequest(null, messages, spec.temperature(), spec.maxOutputTokens(), false);
        AiCallContext.run(SCENE_ANSWER, null, (Supplier<Void>) () -> {
            aiChatGateway.chatCompletionStream(userId, projectId, null, request,
                    onToken::accept, (AiUsage u) -> {
                    });
            return null;
        });
    }

    // ==================== 内部：提示词 / 历史 / 解析 ====================

    /** System prompt 组装（工具决策 / 回答共用基底 + 模式约束 + 按调用类型追加输出要求）。 */
    private String systemPrompt(Long projectId, String mode, boolean toolDecision, boolean metaQuestion) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 HolzynActor 的项目级 AI 助手「世界与你之间的对话入口」。")
                .append("你能讲解项目本身（功能/用法）、讲述用户所在世界此刻正在发生的一切（角色/地点/事件/宏观），并检索世界知识与项目文档。\n");
        sb.append("【零 OOC 铁律】你是元层工具：你绝不进入世界叙事体系，任何角色都不感知你的存在；")
                .append("你的对话不写入世界编年史；你只读查询世界数据，绝不修改任何世界状态（修改须经干预工具 + 用户确认）。\n");
        if (metaQuestion) {
            // M5 打磨：元问题/闲聊（自我介绍/寒暄/项目功能询问等）——不注入世界上下文，问答式直接回答
            sb.append("【问答场景】本次问题与当前世界状态无关（如自我介绍、打招呼、寒暄、询问助手能力或项目用法）。")
                    .append("请以<b>直接的问答方式</b>回答：简洁、礼貌、口语化，聚焦问题本身；")
                    .append("不要输出游戏时间、天气、场景或角色描写，不要代入世界叙事口吻，不要编造世界设定。\n");
        } else {
            sb.append("【世界上下文】").append(worldContext(projectId)).append('\n');
        }
        sb.append("【当前回答模式】").append(modeMode(mode, metaQuestion)).append('\n');
        if (toolDecision) {
            sb.append("【可用工具】\n").append(toolsJson()).append('\n');
            sb.append("【意图路由】按问题类型选工具：角色状态/位置/活动→character_state；介绍角色/关系→character_profile；"
                    + "世界概况/时间/事件→world_overview；地点→location_overview；宏观/粮价/势力→macro_overview；"
                    + "最近事件→recent_events；项目功能/入口→project_guide；世界知识→search_world_knowledge；"
                    + "项目文档→search_project_docs；解释某次决策/查看链路→trace_chain；调用统计/排障→ai_call_stats；"
                    + "改变世界状态→world_intervene。\n");
            sb.append("【输出要求】判断是否需要查询数据：需要 → 只输出 JSON：{\"tool_calls\":[{\"name\":\"工具名\",\"arguments\":{参数}}]}；")
                    .append("不需要（闲聊/已有足够信息）→ 只输出 JSON：{\"no_tool\":true}。");
            if (metaQuestion) {
                // 元问题/闲聊：直接 no_tool（问题与世界状态无关，无需查询任何工具）
                sb.append("【元问题】本次问题与当前世界状态无关（自我介绍/寒暄/能力或项目用法询问），"
                        + "直接输出 {\"no_tool\":true}，不要调用任何工具。");
            }
            sb.append("若前一轮工具结果已包含所需数据，请直接输出 {\"no_tool\":true} 并据此作答，不要重复调用已查询过的工具。")
                    .append("一轮最多调用 1~2 个工具，一次能查清就不要连环调用。")
                    .append("【来源可回溯】若用户只是询问上一条回答的信息来源/出处（如「信息来源是什么」「数据从哪来」「出处是哪个」），")
                    .append("直接输出 {\"no_tool\":true}——基于对话历史上一条助手回答与其已展示的来源标注说明即可，不要调用新工具。")
                    .append("示例：{\"tool_calls\":[{\"name\":\"world_overview\",\"arguments\":{}}]}。")
                    .append("【干预铁律】当用户明确要求改变世界状态（切换天气、注入事件、推进/暂停时间、触发角色行动、给角色插话）时，")
                    .append("必须调用 world_intervene 工具生成干预计划，绝不能只描述当前状态而不执行用户的干预要求。")
                    .append("不要输出任何其他文字。\n");
        } else {
            sb.append("【输出要求】直接输出回答正文（叙事或事实风格按当前模式），不要输出任何 JSON 外壳；")
                    .append("末尾不要添加「来源：」清单（来源由系统另行标注）。\n");
            sb.append("【来源可回溯】若用户追问「信息来源/出处/依据/数据从哪来」：若问的是<b>上一条回答</b>，"
                    + "优先引用上一条回答中已展示的数据源（每条助手消息末尾有「来源」清单），逐条说明"
                    + "（工具名 + 数据源 + 更新时间，如「角色状态来自 actor_state，更新于第 X 天 Y 时」）；"
                    + "基于已有对话内容说明即可，不需要重新调用工具。\n");
        }
        return sb.toString();
    }

    /** 模式约束文本（M5 强化：事实模式逐项标源 / 叙事模式示例 + 可回溯 / 元问题问答式）。 */
    private String modeMode(String mode, boolean metaQuestion) {
        if (metaQuestion) {
            // 元问题/闲聊：不套叙事或事实约束，直接问答式（与 systemPrompt 问答场景段呼应）
            return "问答模式：直接回答用户问题本身，语言自然简洁；不要引用世界时间/天气/角色状态，不要使用世界叙事口吻。";
        }
        if ("fact".equals(mode)) {
            return "严格事实模式：逐项引用数据源，输出结构化字段与数值，每条数据旁标注来源"
                    + "（如「饥饿 32/100〔来源：actor_state〕」）；数值必须来自工具结果，不得编造；"
                    + "不输出精确坐标；数据缺失或过期时明确说明「暂无相关记录」；"
                    + "回答用条目化排版（标签：值），少用修饰语。";
        }
        return "叙事模式（默认）：以世界叙事的方式组织语言——事实为骨、文学为肉。"
                + "核心数据（数值/地点/事件）必须来自工具结果，不得编造；描述角色用词须与其性格一致（稳重者不写急躁、寡言者不写滔滔不绝）；"
                + "时间一律用游戏时间表述（如「青禾村历 0420 年 5 月 15 日清晨」，不要用「刚才/今天早上」等现实时间）；"
                + "数据缺失或过期时明确说「暂无相关记录」。"
                + "示例：问「沈守拙在做什么」→「沈守拙此刻正在村东水田察看稻情，腹中微饥却不愿放下手头的农活，神情平静。」";
    }

    /**
     * 元问题/闲聊判定（M5 打磨）。
     * <p>命中自我介绍、寒暄、助手能力/项目用法询问等<b>与当前世界状态无关</b>的问题 →
     * 不注入世界上下文、用问答式回答（避免「介绍一下自己」被套上游戏时间/场景描写）。
     * 判定为纯关键词启发（无 AI）：世界实体/状态/角色名出现在问题中即视为世界相关问题。</p>
     */
    private static boolean isMetaQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        // 世界相关信号：命中任一即视为世界相关问题（不降级为问答式）
        String[] worldSignals = {
                "世界", "村庄", "村", "镇", "地点", "场景", "天气", "季节", "时间", "事件", "最近",
                "发生了什么", "宏观", "粮价", "势力", "物资", "角色", "沈守拙", "林听竹", "npc:",
                "在做什么", "在哪", "位置", "状态", "情况", "决策", "统计", "数据", "天气",
        };
        for (String s : worldSignals) {
            if (question.contains(s)) {
                return false;
            }
        }
        // 元问题信号：命中任一即问答式
        String[] metaSignals = {
                "介绍一下你", "介绍下你", "介绍一下自己", "介绍下自己", "你是谁", "你是什么",
                "你能做什么", "你会什么", "你的功能", "你是干嘛的", "你有什么用", "你叫什么",
                "你好", "您好", "嗨", "哈喽", "hello", "hi", "谢谢", "感谢", "再见",
                "这个项目", "项目怎么用", "怎么用", "如何使用", "帮忙", "help",
        };
        for (String s : metaSignals) {
            if (question.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /** 世界上下文轻量注入（时间 + 运行状态 + 最近事件，预算 worldContextChars）。 */
    private String worldContext(Long projectId) {
        try {
            WorldGraphVO.TimeNode t = worldGraph.time(projectId);
            StringBuilder sb = new StringBuilder();
            if (t != null) {
                sb.append("游戏时间 ").append(t.gameTimeText() == null ? "未知" : t.gameTimeText())
                        .append("；运行状态：").append(t.worldRunning() != null && t.worldRunning() == 1 ? "运行中" : "静止");
            }
            List<WorldGraphVO.EventNode> events = worldGraph.events(projectId, null, null, 3);
            if (!events.isEmpty()) {
                sb.append("；最近事件：");
                for (WorldGraphVO.EventNode e : events) {
                    sb.append("「").append(e.title() == null ? "?" : e.title()).append("」 ");
                }
            }
            String s = sb.toString();
            int budget = Math.max(100, properties.getWorldContextChars());
            return s.length() <= budget ? s : s.substring(0, budget) + "…";
        } catch (Exception e) {
            return "（世界状态暂不可用）";
        }
    }

    /**
     * 会话历史（最近 N 条 user/assistant 消息对）。
     * <p><b>M5 收尾抓修</b>：chat() 开头已 {@code appendUserMessage} 把本轮问题落库，
     * 故这里取到的最后一条恒为本轮问题；而消息列表末尾又会单独追加一次当前问题，
     * 若不排除会导致当前问题<b>重复出现两次</b>——模型会把「上一条提问」误判为自身
     * （实测「请复述上一条提问」答成复述自己）。此处剔除末尾刚落库的 user 消息。</p>
     */
    private List<AiChatRequest.ChatMessage> history(Long sessionId) {
        List<AiChatRequest.ChatMessage> out = new ArrayList<>();
        List<ActorAssistantMessage> msgs = sessionService.recentMessages(sessionId, properties.getHistoryWindow());
        if (!msgs.isEmpty() && "user".equals(msgs.get(msgs.size() - 1).getRole())) {
            msgs = msgs.subList(0, msgs.size() - 1);
        }
        for (ActorAssistantMessage m : msgs) {
            if (m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            out.add(new AiChatRequest.ChatMessage(m.getRole(), m.getContent()));
        }
        return out;
    }

    /**
     * 待确认干预载荷（confirm SSE 事件；供前端渲染确认卡片并调用确认/取消接口）。
     */
    private Map<String, Object> confirmPayload(Long projectId, Long sessionId) {
        if (interventionService == null) {
            return Map.of();
        }
        var p = interventionService.latestPending(projectId, sessionId).orElse(null);
        if (p == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", p.getId());
        m.put("sessionId", sessionId);
        m.put("operation", p.getOperation());
        m.put("operationLabel", AssistantToolExecutor.opLabel(p.getOperation()));
        m.put("detail", p.getDetail());
        m.put("impact", p.getImpact());
        m.put("risk", p.getRisk());
        m.put("riskLabel", AssistantToolExecutor.riskLabel(p.getRisk()));
        m.put("status", "pending");
        // M5：修改后执行需要当前参数（前端编辑表单预填）
        m.put("params", AssistantInterventionService.parseParams(p.getParamsJson()));
        return m;
    }

    /** 工具清单 JSON（内联到 system prompt）。 */
    private String toolsJson() {
        List<String> parts = new ArrayList<>();
        for (AssistantToolExecutor.ToolSpec s : toolExecutor.specs()) {
            parts.add("{\"name\":" + json(s.name()) + ",\"description\":" + json(s.description())
                    + ",\"parameters\":" + s.params() + "}");
        }
        return "[" + String.join(",", parts) + "]";
    }

    /**
     * 解析工具决策 JSON：{@code {"tool_calls":[{"name":..,"arguments":{..}}]}}。
     * <p>容错：① arguments 可为对象或 JSON 字符串；解析失败按空参数处理；
     * ② 模型偶发在 JSON 前/后追加自然语言前缀或时间戳（如「青禾村历0420年5月15日清晨{...}」），
     * 直接解析失败时提取首个 {@code {} 到最后一个 {@code }} 的子串再试，整体非法按无工具处理。</p>
     *
     * @return 工具调用列表（空=无需工具）
     */
    private static List<AssistantToolExecutor.ToolCall> parseToolCalls(String content) {
        List<AssistantToolExecutor.ToolCall> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        try {
            parseCalls(MAPPER.readTree(content), out);
        } catch (Exception directFail) {
            String span = extractJsonSpan(content);
            if (span == null) {
                log.warn("[助手] 工具决策 JSON 解析失败（按无工具处理）: {}", directFail.getMessage());
                return out;
            }
            try {
                parseCalls(MAPPER.readTree(span), out);
            } catch (Exception e) {
                log.warn("[助手] 工具决策 JSON 解析失败（按无工具处理）: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 从 root 提取 tool_calls 数组并追加到 out（共用主路径与容错路径）。 */
    private static void parseCalls(JsonNode root, List<AssistantToolExecutor.ToolCall> out) {
        JsonNode calls = root.path("tool_calls");
        if (!calls.isArray()) {
            return;
        }
        for (JsonNode c : calls) {
            String name = c.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            // 容错：模型偶发把工具参数键写成 params（规范为 arguments），二者皆可
            JsonNode argsNode = c.has("arguments") ? c.path("arguments") : c.path("params");
            out.add(new AssistantToolExecutor.ToolCall(name, argumentsOf(argsNode)));
        }
    }

    /** 提取首个 {@code {} 到最后一个 {@code }} 的子串；无对象包裹返回 null。 */
    private static String extractJsonSpan(String s) {
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return s.substring(start, end + 1);
    }

    /** arguments 节点 → Map（对象 / JSON 字符串双兼容；失败空 Map）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> argumentsOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        if (node.isObject()) {
            return MAPPER.convertValue(node, Map.class);
        }
        if (node.isTextual()) {
            try {
                JsonNode n = MAPPER.readTree(node.asText());
                if (n.isObject()) {
                    return MAPPER.convertValue(n, Map.class);
                }
            } catch (Exception e) {
                // 忽略：非法 JSON 字符串按空参数
            }
        }
        return Map.of();
    }

    // ==================== 内部：组装 / 序列化 ====================

    /** 来源去重（source+detail 相同只留一条，保持顺序）。 */
    private static List<AssistantSourceVO> dedupeSources(List<AssistantToolExecutor.ToolResult> executed) {
        List<AssistantSourceVO> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (AssistantToolExecutor.ToolResult t : executed) {
            for (AssistantSourceVO s : t.sources()) {
                String key = (s.source() == null ? "" : s.source()) + "|" + (s.detail() == null ? "" : s.detail());
                if (seen.add(key)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** 快速追问（启发式：按执行过的工具生成建议追问；无工具=通用引导）。 */
    private static List<String> quickFollows(List<AssistantToolExecutor.ToolResult> executed) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (AssistantToolExecutor.ToolResult t : executed) {
            switch (t.name()) {
                case "character_state", "character_profile" -> {
                    set.add("还想了解他的关系？");
                    set.add("他最近经历了什么？");
                }
                case "location_overview" -> {
                    set.add("这里最近还有什么大事？");
                    set.add("都有谁常在这里？");
                }
                case "macro_overview" -> {
                    set.add("这些指标最近的趋势怎么样？");
                    set.add("谁在世界里最有影响力？");
                }
                case "world_overview" -> {
                    set.add("最近发生了什么重要事件？");
                    set.add("给我讲讲某个角色现在的状态？");
                }
                case "recent_events" -> {
                    set.add("这背后有什么原因？");
                    set.add("还有谁参与了？");
                }
                case "search_world_knowledge", "search_project_docs" -> {
                    set.add("还有相关的内容吗？");
                    set.add("换个角度再查查看？");
                }
                case "trace_chain" -> {
                    set.add("这背后还有什么原因？");
                    set.add("还有别的决策链路吗？");
                }
                case "ai_call_stats" -> {
                    set.add("失败/降级的具体原因是什么？");
                    set.add("近 7 天的情况怎么样？");
                }
                case "world_intervene" -> {
                    set.add("先看看执行结果");
                    set.add("把刚才的操作再试一次？");
                }
                case "project_guide" -> {
                    set.add("那具体怎么操作？");
                    set.add("这个功能在哪个页面？");
                }
                default -> {
                }
            }
        }
        if (set.isEmpty()) {
            set.add("介绍一下这个世界？");
            set.add("现在世界处于什么状态？");
        }
        return List.copyOf(set).stream().limit(3).toList();
    }

    /** 对象 → JSON 字符串（null→null）。 */
    private static String json(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 参数 → 规范化签名（递归按键排序，消除键序差异——同参数无论键顺序如何签名一致）。
     *
     * @param args 参数字典
     *
     * @return 稳定签名（null/空 → "{}"）
     */
    @SuppressWarnings("unchecked")
    private static String canonicalArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
        args.forEach((k, v) -> sorted.put(k, normalizeValue(v)));
        return json(sorted);
    }

    /** 递归规范化值：Map 排序键、List 逐项规范化。 */
    @SuppressWarnings("unchecked")
    private static Object normalizeValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            java.util.TreeMap<String, Object> out = new java.util.TreeMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), normalizeValue(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(AssistantChatService::normalizeValue).toList();
        }
        return v;
    }

    /** 字符串 → JSON 字符串字面量（转义）。 */
    private static String json(String s) {
        if (s == null) {
            return "null";
        }
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
