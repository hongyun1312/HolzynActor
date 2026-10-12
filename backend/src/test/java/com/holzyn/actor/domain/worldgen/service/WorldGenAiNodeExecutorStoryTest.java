package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.worldgen.config.WorldGenProperties;
import com.holzyn.actor.domain.worldgen.model.WorkflowNode;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldGenAiNodeExecutor 的 story-plan 接线（M4 story-plan 工作流，2026-09-29_06）单元测试。
 * <p>职责：验证——novel-story-planner / novel-style-profiler 语义名 → 模板编码映射、
 * 输出校验分支（故事引擎三层 / 风格档案）、非 story agent 不受影响。</p>
 */
class WorldGenAiNodeExecutorStoryTest {

    private AiSpringChatGateway aiChatGateway;
    private PromptTemplateService promptTemplateService;
    private WorldGenAiNodeExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        Path dataDir = Files.createTempDirectory("worldgen-story-test");
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        UsageLogService usageLogService = Mockito.mock(UsageLogService.class);
        LocalArchiveService archiveService = new LocalArchiveService();
        org.springframework.test.util.ReflectionTestUtils.setField(archiveService, "dataDir", dataDir.toString());
        WorldGenProperties props = new WorldGenProperties();
        AiCallContext.setSink(null);

        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any()))
                .thenReturn("【模板】你是一位世界叙事引擎设计师。");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any()))
                .thenReturn("你只输出严格的 JSON 对象，不输出任何其他文字。");

        executor = new WorldGenAiNodeExecutor(aiChatGateway, promptTemplateService, currentUserProvider,
                usageLogService, new WorldGenSchemaValidator(), archiveService, props);
    }

    private NodeExecutionContext ctx(String agentType, String firstMessage) {
        WorkflowNode node = new WorkflowNode();
        node.setId("agent_story_engine");
        node.setLabel("故事宏观规划");
        node.setNodeType("agent");
        node.setAgentType(agentType);
        node.setFirstMessage(firstMessage);
        node.setOutputVariable("agent_story_engine_output");
        node.setSaveOutputToFile(false);
        Map<String, String> inputs = Map.of("language", "中文");
        return new NodeExecutionContext(10L, 5L, "worldgen_story_plan_v1", node,
                firstMessage, inputs, Map.of(), Set.of());
    }

    private static final String PLAN_OK = """
            {"story_plan": {
              "surface": {"essence": "雾隐仙村", "expanded_premise": "p", "core_conflict": "c",
                "world_experience": "w", "tone_reference": "t", "world_tags": ["雾隐"]},
              "engine": {"essence": "E", "living_trap": "l", "conflict_engine": "ce",
                "conflict_layers": {"external": "a", "internal": "b", "relational": "c"},
                "mystery_box": "m", "rhythm_cycle": "r", "dual_story": {"foreground": "f", "background": "b"}},
              "payoff": {"essence": "P", "tension_archetypes": ["t1"], "dramatic_moments": "d",
                "emotional_rhythm": "e", "atmosphere_notes": "a"}
            }}
            """;

    @Test
    @DisplayName("novel-story-planner：模板映射 + 故事引擎校验 + 成功")
    void storyPlanner_success() {
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(PLAN_OK, "qwen", 200, 80, 280, null, null, 1L));
        NodeExecutionResult result = executor.execute(ctx("novel-story-planner",
                "【世界设定】青禾村\n请为这个世界构建三层故事力学引擎。"));
        assertEquals(WorldGenStatus.SUCCEEDED, result.status());
        assertTrue(result.outputJson().contains("\"story_plan\""));
        // 模板编码正确（story 模板）
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptTemplateService).render(anyLong(), anyLong(), codeCaptor.capture(), any());
        assertTrue(codeCaptor.getAllValues().stream()
                .anyMatch(PromptTemplateService.CODE_WORLDGEN_STORY_PLAN::equals));
    }

    @Test
    @DisplayName("novel-story-planner：输出缺段 → 校验失败")
    void storyPlanner_validationFail() {
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("{\"story_plan\":{\"surface\":{\"essence\":\"A\"}}}", "qwen",
                        100, 20, 120, null, null, 1L));
        NodeExecutionResult result = executor.execute(ctx("novel-story-planner", "请构建。"));
        assertEquals(WorldGenStatus.FAILED, result.status());
        assertTrue(result.error().contains("引擎层"));
    }

    @Test
    @DisplayName("novel-style-profiler：模板映射 + 风格校验 + 成功")
    void styleProfiler_success() {
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("{\"style_profile\":{\"tone\":\"冷调\",\"narrative_persona\":\"说书人\"}}",
                        "qwen", 100, 30, 130, null, null, 1L));
        NodeExecutionResult result = executor.execute(ctx("novel-style-profiler",
                "【故事引擎】{{agent_story_engine_output}}\n请生成风格档案。"));
        assertEquals(WorldGenStatus.SUCCEEDED, result.status());
        assertTrue(result.outputJson().contains("\"style_profile\""));
    }
}
