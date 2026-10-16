package com.holzyn.actor.domain.assistant.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiUsage;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.assistant.config.AssistantProperties;
import com.holzyn.actor.domain.assistant.entity.ActorAssistantIntervention;
import com.holzyn.actor.domain.assistant.entity.ActorAssistantMessage;
import com.holzyn.actor.domain.assistant.vo.AssistantCardVO;
import com.holzyn.actor.domain.assistant.vo.AssistantSourceVO;
import com.holzyn.actor.domain.assistant.vo.AssistantSessionVO;
import com.holzyn.actor.domain.world.service.WorldGraphQueryService;
import com.holzyn.actor.domain.world.vo.WorldGraphVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 助手对话编排单元测试（function calling 工具循环 + SSE 流式 + 双模式 + 组装）。
 * <p>职责：验证——工具决策 JSON 触发工具执行并回喂、最终回答流式 token 推送、done 载荷
 * （content/card/sources/quickFollows/toolCalls）、无工具直接回答、最终回答失败降级 error。</p>
 */
class AssistantChatServiceTest {

    private AssistantSessionService sessionService;
    private AssistantToolExecutor toolExecutor;
    private AssistantModeResolver modeResolver;
    private AiSpringChatGateway aiChatGateway;
    private WorldGraphQueryService worldGraph;
    private CurrentUserProvider currentUserProvider;
    private AssistantProperties properties;
    private AssistantInterventionService interventionService;
    private AssistantChatService chatService;

    private final List<Object[]> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sessionService = Mockito.mock(AssistantSessionService.class);
        toolExecutor = Mockito.mock(AssistantToolExecutor.class);
        modeResolver = Mockito.mock(AssistantModeResolver.class);
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        worldGraph = Mockito.mock(WorldGraphQueryService.class);
        currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        properties = new AssistantProperties();
        properties.setEnabled(true);
        properties.setMaxToolRounds(3);
        chatService = new AssistantChatService(sessionService, toolExecutor, modeResolver, aiChatGateway,
                worldGraph, currentUserProvider, properties);
        // M4：干预服务为字段注入（不改变构造签名），测试经反射注入 mock
        interventionService = Mockito.mock(AssistantInterventionService.class);
        ReflectionTestUtils.setField(chatService, "interventionService", interventionService);

        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(sessionService.get(any(), any())).thenReturn(
                new AssistantSessionVO(1L, 1L, "t", "narrative", "standard", 0L, null, null));
        when(sessionService.recentMessages(any(), anyInt())).thenReturn(List.of());
        when(modeResolver.resolve(anyString(), anyString(), any()))
                .thenReturn(new AssistantModeResolver.ModeDecision("narrative", "沈砚秋在做什么"));
        when(worldGraph.time(any())).thenReturn(new WorldGraphVO.TimeNode(100L, 27L, 1L, 1, 0, "青禾村历", "第 1 天"));
        when(worldGraph.events(any(), isNull(), isNull(), any())).thenReturn(List.of());
        ActorAssistantMessage saved = new ActorAssistantMessage();
        saved.setId(99L);
        when(sessionService.appendAssistantMessage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(saved);
    }

    private AssistantChatService.SseSink sink() {
        return (name, data) -> {
            events.add(new Object[]{name, data});
            return true;
        };
    }

    @SuppressWarnings("unchecked")
    @Test
    void toolLoopExecutesAndStreamsAnswer() {
        // 第一次工具决策返回工具调用，第二轮返回 no_tool（模拟模型收敛）
        when(aiChatGateway.chatCompletionJson(any(), any(), isNull(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(
                                "{\"tool_calls\":[{\"name\":\"character_state\",\"arguments\":{\"character\":\"沈砚秋\"}}]}",
                                "qwen", 10, 20, 30, 1L),
                        new AiChatResult("{\"no_tool\":true}", "qwen", 5, 10, 15, 1L));
        when(toolExecutor.execute(any(), any(), any())).thenReturn(new AssistantToolExecutor.ToolResult(
                "character_state", "已获取角色状态",
                "沈砚秋此刻正在村东水田附近察看稻情。",
                List.of(new AssistantSourceVO("NpcStateService·角色状态", "更新于 0420-05-15")),
                new AssistantCardVO("character", "角色状态 · 沈砚秋",
                        List.of(new AssistantCardVO.Field("位置", "村东水田")))));
        Mockito.doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("沈砚秋");
            onToken.accept("此刻");
            Consumer<AiUsage> onUsage = inv.getArgument(5);
            onUsage.accept(new AiUsage(10, 20, 0, 0));
            return null;
        }).when(aiChatGateway).chatCompletionStream(any(), any(), isNull(), any(AiChatRequest.class),
                any(Consumer.class), any(Consumer.class));

        chatService.chat(1L, 1L, "沈砚秋在做什么", null, sink());

        // meta
        assertTrue(events.stream().anyMatch(e -> "meta".equals(e[0])));
        // tool 事件（工具循环执行）
        assertTrue(events.stream().anyMatch(e -> "tool".equals(e[0])
                && "character_state".equals(((Map<?, ?>) e[1]).get("name"))));
        // token 流式
        assertEquals("沈砚秋此刻", events.stream()
                .filter(e -> "token".equals(e[0]))
                .map(e -> (String) ((Map<?, ?>) e[1]).get("delta"))
                .reduce("", String::concat));
        // done 载荷
        Map<?, ?> done = (Map<?, ?>) events.stream()
                .filter(e -> "done".equals(e[0])).map(e -> e[1]).findFirst().orElse(null);
        assertNotNull(done);
        assertEquals("沈砚秋此刻", done.get("content"));
        assertEquals("narrative", done.get("mode"));
        assertNotNull(done.get("card"));
        assertNotNull(done.get("sources"));
        assertNotNull(done.get("quickFollows"));
        assertEquals(1, ((List<?>) done.get("toolCalls")).size());
    }

    @Test
    void noToolDecisionStreamsDirectAnswer() {
        when(aiChatGateway.chatCompletionJson(any(), any(), isNull(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"no_tool\":true}", "qwen", 5, 10, 15, 1L));
        Mockito.doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("你好，我是世界助手");
            return null;
        }).when(aiChatGateway).chatCompletionStream(any(), any(), isNull(), any(AiChatRequest.class),
                any(Consumer.class), any(Consumer.class));

        chatService.chat(1L, 1L, "你好", null, sink());

        assertTrue(events.stream().noneMatch(e -> "tool".equals(e[0])));
        Map<?, ?> done = (Map<?, ?>) events.stream()
                .filter(e -> "done".equals(e[0])).map(e -> e[1]).findFirst().orElse(null);
        assertNotNull(done);
        assertEquals("你好，我是世界助手", done.get("content"));
        assertEquals(0, ((List<?>) done.get("toolCalls")).size());
    }

    @Test
    void answerFailureSendsErrorEvent() {
        when(aiChatGateway.chatCompletionJson(any(), any(), isNull(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"no_tool\":true}", "qwen", 5, 10, 15, 1L));
        Mockito.doThrow(new com.holzyn.actor.ai.AiCallException("模拟网关失败"))
                .when(aiChatGateway).chatCompletionStream(any(), any(), isNull(), any(AiChatRequest.class),
                        any(Consumer.class), any(Consumer.class));

        chatService.chat(1L, 1L, "你好", null, sink());

        assertTrue(events.stream().anyMatch(e -> "error".equals(e[0])));
        assertTrue(events.stream().noneMatch(e -> "done".equals(e[0])));
    }

    @Test
    void toolDecisionParseFailureFallsBackToAnswer() {
        // 非法 JSON → 按无工具处理，直接走回答
        when(aiChatGateway.chatCompletionJson(any(), any(), isNull(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("这不是 JSON", "qwen", 5, 10, 15, 1L));
        Mockito.doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("回答");
            return null;
        }).when(aiChatGateway).chatCompletionStream(any(), any(), isNull(), any(AiChatRequest.class),
                any(Consumer.class), any(Consumer.class));

        chatService.chat(1L, 1L, "沈砚秋在做什么", null, sink());

        Map<?, ?> done = (Map<?, ?>) events.stream()
                .filter(e -> "done".equals(e[0])).map(e -> e[1]).findFirst().orElse(null);
        assertNotNull(done);
        assertEquals("回答", done.get("content"));
    }

    @Test
    void confirmPendingToolEmitsConfirmAndStopsGeneration() {
        // 模型声明 world_intervene → 执行器返回 confirm 卡片 → 推 confirm SSE + done.awaitingConfirm，不再生成回答
        when(aiChatGateway.chatCompletionJson(any(), any(), isNull(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult(
                        "{\"tool_calls\":[{\"name\":\"world_intervene\",\"arguments\":{\"operation\":\"weather\",\"params\":{\"condition\":\"雨\"}}}]}",
                        "qwen", 10, 20, 30, 1L));
        when(toolExecutor.execute(any(), any(), any())).thenReturn(new AssistantToolExecutor.ToolResult(
                "world_intervene", "待确认：切换天气为「雨」",
                "需要用户确认的干预计划：操作=切换天气，详情=切换天气为「雨」，影响=项目 1 世界，风险=低。",
                List.of(new AssistantSourceVO("沙盘干预（确认后经沙盘既有端点）", null)),
                new AssistantCardVO("confirm", "干预确认 · 切换天气",
                        List.of(new AssistantCardVO.Field("操作类型", "切换天气"),
                                new AssistantCardVO.Field("操作详情", "切换天气为「雨」")))));
        ActorAssistantIntervention pending = new ActorAssistantIntervention();
        pending.setId(7L);
        pending.setProjectId(1L);
        pending.setSessionId(1L);
        pending.setOperation("weather");
        pending.setDetail("切换天气为「雨」");
        pending.setImpact("项目 1 世界（全境天气）");
        pending.setRisk("low");
        pending.setStatus("pending");
        pending.setParamsJson("{\"condition\":\"雨\"}");
        pending.setCreatedAt(LocalDateTime.now());
        when(interventionService.latestPending(anyLong(), anyLong())).thenReturn(Optional.of(pending));

        chatService.chat(1L, 1L, "把天气切成雨天", null, sink());

        // confirm SSE 事件（含 planId + params 供修改后执行表单预填）
        Map<?, ?> confirm = (Map<?, ?>) events.stream()
                .filter(e -> "confirm".equals(e[0])).map(e -> e[1]).findFirst().orElse(null);
        assertNotNull(confirm);
        assertEquals(7L, confirm.get("planId"));
        assertEquals("weather", confirm.get("operation"));
        assertNotNull(confirm.get("params"));
        assertEquals("雨", ((Map<?, ?>) confirm.get("params")).get("condition"));
        // done：awaitingConfirm=true，不产生 token 流式
        Map<?, ?> done = (Map<?, ?>) events.stream()
                .filter(e -> "done".equals(e[0])).map(e -> e[1]).findFirst().orElse(null);
        assertNotNull(done);
        assertEquals(Boolean.TRUE, done.get("awaitingConfirm"));
        assertNotNull(done.get("confirm"));
        assertTrue(events.stream().noneMatch(e -> "token".equals(e[0])));
    }

    @Test
    void modeOverridePassedToResolverSeparately() {
        // M5：modeOverride 独立传入判定（不拼入问题文本），返回的 fact 模式驱动回答
        when(modeResolver.resolve(anyString(), anyString(), any()))
                .thenReturn(new AssistantModeResolver.ModeDecision("fact", "沈砚秋在做什么"));
        when(aiChatGateway.chatCompletionJson(any(), any(), isNull(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"no_tool\":true}", "qwen", 5, 10, 15, 1L));
        Mockito.doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("事实回答");
            return null;
        }).when(aiChatGateway).chatCompletionStream(any(), any(), isNull(), any(AiChatRequest.class),
                any(Consumer.class), any(Consumer.class));

        chatService.chat(1L, 1L, "沈砚秋在做什么", "fact", sink());

        Map<?, ?> done = (Map<?, ?>) events.stream()
                .filter(e -> "done".equals(e[0])).map(e -> e[1]).findFirst().orElse(null);
        assertNotNull(done);
        assertEquals("fact", done.get("mode"));
        // 三参判定被调用，覆盖值独立传入（不拼进问题）
        Mockito.verify(modeResolver).resolve(anyString(), anyString(), Mockito.eq("fact"));
    }

    @Test
    void historyDropsJustAppendedCurrentQuestion() {
        // M5 收尾抓修：chat() 开头已把当前问题落库（appendUserMessage），recentMessages 取到的
        // 最后一条就是本轮问题；history() 必须剔除它，否则当前问题在消息列表中重复出现两次，
        // 模型会把「上一条提问」误判为自身。此处断言：发送给 AI 的消息里当前问题只出现一次。
        ActorAssistantMessage q1 = msg(1L, "user", "讲讲沈守拙现在的情况");
        ActorAssistantMessage a1 = msg(2L, "assistant", "沈守拙此刻在守脉堂正院。");
        ActorAssistantMessage q2 = msg(3L, "user", "刚才回答里的信息来源是什么？");
        when(sessionService.recentMessages(any(), anyInt())).thenReturn(List.of(q1, a1, q2));
        // 模式判定返回净化后问题 == 当前问题（与真实对话一致）
        when(modeResolver.resolve(anyString(), anyString(), any()))
                .thenReturn(new AssistantModeResolver.ModeDecision("narrative", "刚才回答里的信息来源是什么？"));
        when(aiChatGateway.chatCompletionJson(any(), any(), isNull(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"no_tool\":true}", "qwen", 5, 10, 15, 1L));
        Mockito.doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("来源在上一条回答的清单中。");
            return null;
        }).when(aiChatGateway).chatCompletionStream(any(), any(), isNull(), any(AiChatRequest.class),
                any(Consumer.class), any(Consumer.class));

        chatService.chat(1L, 1L, "刚才回答里的信息来源是什么？", null, sink());

        Mockito.verify(aiChatGateway, Mockito.atLeastOnce()).chatCompletionJson(any(), any(), isNull(),
                Mockito.argThat((AiChatRequest req) -> {
                    List<AiChatRequest.ChatMessage> msgs = req.messages();
                    // 历史中不应再出现当前问题的重复副本
                    long occurrences = msgs.stream()
                            .filter(m -> "user".equals(m.role()))
                            .filter(m -> "刚才回答里的信息来源是什么？".equals(m.content()))
                            .count();
                    // 最后一条必须是当前问题（唯一一次出现）
                    AiChatRequest.ChatMessage last = msgs.get(msgs.size() - 1);
                    return occurrences == 1 && "user".equals(last.role())
                            && "刚才回答里的信息来源是什么？".equals(last.content())
                            // 历史中应保留上一轮的 user/assistant 消息
                            && msgs.stream().anyMatch(m -> "讲讲沈守拙现在的情况".equals(m.content()))
                            && msgs.stream().anyMatch(m -> "沈守拙此刻在守脉堂正院。".equals(m.content()));
                }));
    }

    @SuppressWarnings("unchecked")
    @Test
    void toolDecisionToleratesPreambleAndTrailingText() {
        // 第 61 轮实测：qwen3.6-35B 会在工具决策 JSON 前追加时间戳/自然语言前缀、末尾追加解释
        // （如「青禾村历0420年5月15日清晨{...}（已按世界历输出）」）。严格解析曾按无工具降级，
        // 本轮修复：提取首个 { 到最后一个 } 的子串再解析，应仍识别出 world_intervene。
        String messy = "青禾村历0420年5月15日清晨{\"tool_calls\":[{\"name\":\"world_intervene\",\"arguments\":{\"operation\":\"weather\",\"params\":{\"condition\":\"雨\"}}}]}（已按世界历输出）";
        List<AssistantToolExecutor.ToolCall> calls = (List<AssistantToolExecutor.ToolCall>) ReflectionTestUtils
                .invokeMethod(AssistantChatService.class, "parseToolCalls", messy);
        assertNotNull(calls);
        assertEquals(1, calls.size());
        assertEquals("world_intervene", calls.get(0).name());
        assertEquals("雨", ((Map<String, Object>) calls.get(0).arguments().get("params")).get("condition"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void toolDecisionKeepsCleanPathAndRejectsGarbage() {
        // 干净 JSON 仍走原路径正常解析
        List<AssistantToolExecutor.ToolCall> clean = (List<AssistantToolExecutor.ToolCall>) ReflectionTestUtils
                .invokeMethod(AssistantChatService.class, "parseToolCalls",
                        "{\"tool_calls\":[{\"name\":\"character_state\",\"arguments\":{\"character\":\"沈砚秋\"}}]}");
        assertNotNull(clean);
        assertEquals(1, clean.size());
        assertEquals("character_state", clean.get(0).name());
        // 纯垃圾（无 JSON 对象）→ 空（按无工具处理），不抛异常
        List<AssistantToolExecutor.ToolCall> garbage = (List<AssistantToolExecutor.ToolCall>) ReflectionTestUtils
                .invokeMethod(AssistantChatService.class, "parseToolCalls", "没有任何工具，直接回答。");
        assertNotNull(garbage);
        assertEquals(0, garbage.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void toolDecisionAcceptsParamsKeyAsArgumentsFallback() {
        // 第 61 轮实测：模型偶发把工具参数键写成 params（规范为 arguments）——
        // 如 {"tool_calls":[{"name":"world_intervene","params":{...}}]}。应同样识别。
        String withParams = "{\"tool_calls\":[{\"name\":\"world_intervene\",\"params\":{\"operation\":\"weather\",\"params\":{\"condition\":\"雨\"}}}]}";
        List<AssistantToolExecutor.ToolCall> calls = (List<AssistantToolExecutor.ToolCall>) ReflectionTestUtils
                .invokeMethod(AssistantChatService.class, "parseToolCalls", withParams);
        assertNotNull(calls);
        assertEquals(1, calls.size());
        assertEquals("world_intervene", calls.get(0).name());
        assertEquals("weather", calls.get(0).arguments().get("operation"));
    }

    @Test
    void metaQuestionSkipsWorldContextAndUsesQaMode() {
        // M5 打磨：自我介绍/寒暄等与当前世界无关的问题——systemPrompt 不注入世界上下文
        // （不出现「游戏时间」）、模式约束为问答模式（不出现「世界叙事」），并提示 no_tool。
        when(modeResolver.resolve(anyString(), anyString(), any()))
                .thenReturn(new AssistantModeResolver.ModeDecision("narrative", "介绍一下你自己"));
        when(aiChatGateway.chatCompletionJson(any(), any(), isNull(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"no_tool\":true}", "qwen", 5, 10, 15, 1L));
        Mockito.doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(4);
            onToken.accept("我是 HolzynActor 的项目级 AI 助手。");
            return null;
        }).when(aiChatGateway).chatCompletionStream(any(), any(), isNull(), any(AiChatRequest.class),
                any(Consumer.class), any(Consumer.class));

        chatService.chat(1L, 1L, "介绍一下你自己", null, sink());

        // 工具决策请求的 system 提示：含「元问题」与 no_tool 指引，不含世界上下文段（【世界上下文】）
        Mockito.verify(aiChatGateway, Mockito.atLeastOnce()).chatCompletionJson(any(), any(), isNull(),
                Mockito.argThat((AiChatRequest req) -> {
                    String system = req.messages().stream()
                            .filter(m -> "system".equals(m.role()))
                            .map(AiChatRequest.ChatMessage::content)
                            .reduce("", String::concat);
                    return system.contains("元问题")
                            && system.contains("no_tool")
                            && !system.contains("【世界上下文】");
                }));
    }

    private static ActorAssistantMessage msg(long id, String role, String content) {
        ActorAssistantMessage m = new ActorAssistantMessage();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        return m;
    }
}
