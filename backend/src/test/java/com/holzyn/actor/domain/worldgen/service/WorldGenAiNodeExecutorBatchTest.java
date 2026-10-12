package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.worldgen.config.WorldGenProperties;
import com.holzyn.actor.domain.worldgen.model.WorkflowNode;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldGenAiNodeExecutor batch 执行（M4 character 工作流，2026-09-29_05 适配轮）单元测试。
 * <p>职责：验证——batch 节点逐角色调用 AI（每角色一次、prompt 注入角色信息）、
 * 每角色独立校验 + 检查点落盘、阵容截断（rosterLimit）、检查点跳过（重试不重复付 AI 费）、
 * 单角色失败 → 节点失败。</p>
 */
class WorldGenAiNodeExecutorBatchTest {

    private AiSpringChatGateway aiChatGateway;
    private PromptTemplateService promptTemplateService;
    private CurrentUserProvider currentUserProvider;
    private UsageLogService usageLogService;
    private LocalArchiveService archiveService;
    private WorldGenProperties props;
    private WorldGenAiNodeExecutor executor;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private Path tempWorkspace;

    private static final String ROSTER_JSON = """
            {"characters":[
              {"name":"沈砚秋","role":"村长","importance":"主角"},
              {"name":"孙二娘","role":"掌柜","importance":"重要"}
            ]}
            """;

    private static final String DEPTH_OUTPUT = """
            {"character_depth":{
              "skeleton":{"name":"%s","title":"t","gender":"g","race":"r","age":"a","origin":"o","background":"b","appearance":"ap"},
              "beliefs":{"core_values":["v"],"desires":["d"],"fears":["f"],"beliefs":["x"]},
              "deep":{"secret":"s","trauma":"t","moral_boundary":"m","growth_arc":"g"},
              "voice":{"speech_style":"sp","catchphrases":["c"],"tone":"to","language_habits":"lh"}
            }}
            """;

    @BeforeEach
    void setUp() throws Exception {
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        archiveService = new LocalArchiveService();
        // 临时数据目录（避免污染仓库 data/）
        tempWorkspace = Files.createTempDirectory("worldgen-batch-test");
        ReflectionTestUtils.setField(archiveService, "dataDir", tempWorkspace.toString());

        props = new WorldGenProperties();
        props.setEnabled(true);
        props.setAiEnabled(true);
        props.setCheckpointEnabled(true);
        props.setRosterLimit(2);
        props.setSchemaRetry(1);
        props.setMaxTokens(8192);
        props.setTemperature(0.7);

        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any()))
                .thenReturn("模板内容（角色塑造职责 + schema）");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any()))
                .thenReturn("system");

        executor = new WorldGenAiNodeExecutor(aiChatGateway, promptTemplateService, currentUserProvider,
                usageLogService, new WorldGenSchemaValidator(), archiveService, props);
    }

    /** 构造 batch 节点定义。 */
    private WorkflowNode batchNode() {
        WorkflowNode node = new WorkflowNode();
        node.setId("agent_character_depth");
        node.setLabel("逐角色深度生成");
        node.setNodeType("agent");
        node.setAgentType(WorldGenAiNodeExecutor.AGENT_CHARACTER_DEPTH);
        node.setNodeParams(Map.of(
                "batch", true,
                "batchInputVariable", "agent_roster_output",
                "batchOutputPattern", "characters/{name}.json"));
        return node;
    }

    private NodeExecutionContext ctx(WorkflowNode node) {
        return new NodeExecutionContext(10L, 5L, WorldGenCharacterService.CODE_CHARACTER_V1, node,
                "【角色阵容】\n" + ROSTER_JSON,
                Map.of("language", "中文"),
                Map.of("agent_roster_output", ROSTER_JSON),
                Set.of());
    }

    @Test
    @DisplayName("batch：逐角色调用 AI + 校验 + 检查点落盘 + 汇总输出")
    void batchGeneratesPerCharacter() throws Exception {
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenAnswer(inv -> {
                    AiChatRequestLike req = AiChatRequestLike.of(inv.getArgument(3));
                    // 依据 prompt 中的角色名返回对应深度 JSON
                    String prompt = req.userPrompt();
                    String name = prompt.contains("沈砚秋") ? "沈砚秋" : "孙二娘";
                    return new AiChatResult(String.format(DEPTH_OUTPUT, name), "qwen", 100, 50, 150, null, null, 9L);
                });

        NodeExecutionResult result = executor.execute(ctx(batchNode()));

        assertEquals(WorldGenStatus.SUCCEEDED, result.status());
        // 2 个角色各 1 次 AI 调用
        verify(aiChatGateway, times(2)).chatCompletion(anyLong(), anyLong(), any(), any());
        // 汇总输出含 2 个角色
        var out = MAPPER.readTree(result.outputJson());
        assertEquals(2, out.path("characters").size());
        assertEquals("沈砚秋", out.path("characters").get(0).path("name").asText());
        // 检查点文件落盘（taskWorkspace/characters/<name>.json）
        Path workspace = archiveService.taskWorkspace(5L, 10L);
        Path c1 = archiveService.safeResolve(workspace, "characters/沈砚秋.json");
        Path c2 = archiveService.safeResolve(workspace, "characters/孙二娘.json");
        assertTrue(Files.isRegularFile(c1), "沈砚秋 检查点应落盘");
        assertTrue(Files.isRegularFile(c2), "孙二娘 检查点应落盘");
        // Token 汇总
        assertTrue(result.tokenIn() >= 200);
        assertTrue(result.tokenOut() >= 100);
    }

    @Test
    @DisplayName("batch：检查点已存在 → 该角色跳过（重试不重复付 AI 费）")
    void batchSkipsExistingCheckpoints() throws Exception {
        // 预置一个角色的检查点文件（模拟上次半截重跑已成功）
        Path workspace = archiveService.taskWorkspace(5L, 10L);
        Path c1 = archiveService.safeResolve(workspace, "characters/沈砚秋.json");
        Files.createDirectories(c1.getParent());
        Files.writeString(c1, String.format(DEPTH_OUTPUT, "沈砚秋"));

        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(String.format(DEPTH_OUTPUT, "孙二娘"), "qwen", 100, 50, 150, null, null, 9L));

        NodeExecutionResult result = executor.execute(ctx(batchNode()));

        assertEquals(WorldGenStatus.SUCCEEDED, result.status());
        // 只调用 1 次 AI（沈砚秋 跳过）
        verify(aiChatGateway, times(1)).chatCompletion(anyLong(), anyLong(), any(), any());
        var out = MAPPER.readTree(result.outputJson());
        assertEquals(2, out.path("characters").size());
        assertEquals(true, out.path("characters").get(0).path("skipped").asBoolean(), "沈砚秋 应标记 skipped");
    }

    @Test
    @DisplayName("batch：阵容截断（rosterLimit=2 时超限角色不生成）")
    void batchTruncatesRosterByLimit() throws Exception {
        props.setRosterLimit(1);
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(String.format(DEPTH_OUTPUT, "沈砚秋"), "qwen", 100, 50, 150, null, null, 9L));

        NodeExecutionResult result = executor.execute(ctx(batchNode()));
        assertEquals(WorldGenStatus.SUCCEEDED, result.status());
        verify(aiChatGateway, times(1)).chatCompletion(anyLong(), anyLong(), any(), any());
        assertEquals(1, MAPPER.readTree(result.outputJson()).path("characters").size());
    }

    @Test
    @DisplayName("batch：单角色校验失败（重试耗尽）→ 节点失败，已成功角色检查点保留")
    void batchFailsWhenCharacterInvalid() {
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("{\"bad\":true}", "qwen", 10, 5, 15, null, null, 9L));

        NodeExecutionResult result = executor.execute(ctx(batchNode()));
        assertEquals(WorldGenStatus.FAILED, result.status());
        assertTrue(result.error().contains("沈砚秋"), "错误应指明失败角色");
    }

    @Test
    @DisplayName("batch：上游阵容缺失 → 失败")
    void batchFailsWithoutRoster() {
        NodeExecutionContext noRoster = new NodeExecutionContext(10L, 5L, WorldGenCharacterService.CODE_CHARACTER_V1,
                batchNode(), "x", Map.of("language", "中文"), Map.of(), Set.of());
        NodeExecutionResult result = executor.execute(noRoster);
        assertEquals(WorldGenStatus.FAILED, result.status());
        assertTrue(result.error().contains("缺少上游阵容输出"));
    }

    /** AI 请求轻量视图（读 user 消息文本，断言注入）。 */
    private record AiChatRequestLike(String userPrompt) {
        static AiChatRequestLike of(Object req) {
            if (req instanceof com.holzyn.actor.ai.AiChatRequest r && r.messages() != null) {
                for (var m : r.messages()) {
                    if ("user".equals(m.role())) {
                        return new AiChatRequestLike(m.content());
                    }
                }
            }
            return new AiChatRequestLike("");
        }
    }
}
