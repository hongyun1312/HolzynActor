package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.ai.AiChatRequest;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldGenAiNodeExecutor（M2：真实 Agent 节点执行——模板+首消息合并 / json_mode / Schema 校验 /
 * 定向重试 / 检查点写盘 / 桩回退 / 模拟失败钩子）单元测试。
 * <p>说明：AiSpringChatGateway/PromptTemplateService 等依赖 Mockito mock；校验器用真实实现；
 * 检查点写盘用临时目录。</p>
 */
class WorldGenAiNodeExecutorTest {

    private AiSpringChatGateway aiChatGateway;
    private PromptTemplateService promptTemplateService;
    private CurrentUserProvider currentUserProvider;
    private UsageLogService usageLogService;
    private WorldGenSchemaValidator schemaValidator;
    private LocalArchiveService archiveService;
    private WorldGenProperties props;
    private WorldGenAiNodeExecutor executor;
    private Path dataDir;

    /** 合法 core_laws 输出（mock router 返回） */
    private static final String VALID_OUTPUT = """
            {"core_laws":{"essence":"灵气循雾脉流转，离脉则力竭","power_system":"四阶修炼体系","axioms":[{"name":"雾脉守恒","statement":"总量守恒","cost":"施法耗灵","boundary":"禁区无效","enforcement":"雾脉自行纠正"}],"taboos":["弑杀雾脉之灵"],"power_manifestation":"雾光环绕"}}
            """;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = Files.createTempDirectory("worldgen-ai-test");
        aiChatGateway = Mockito.mock(AiSpringChatGateway.class);
        promptTemplateService = Mockito.mock(PromptTemplateService.class);
        currentUserProvider = Mockito.mock(CurrentUserProvider.class);
        usageLogService = Mockito.mock(UsageLogService.class);
        schemaValidator = new WorldGenSchemaValidator();
        archiveService = new LocalArchiveService();
        ReflectionTestUtils.setField(archiveService, "dataDir", dataDir.toString());
        props = new WorldGenProperties();
        // 重置 AiCallContext sink（防其他测试类残留的静态 sink 干扰）
        AiCallContext.setSink(null);

        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(promptTemplateService.render(anyLong(), anyLong(), anyString(), any()))
                .thenReturn("【角色模板】你是一位世界底层规则架构师。\n【Schema】{\"core_laws\":{...}}");
        when(promptTemplateService.systemMessage(anyLong(), anyLong(), anyString(), any()))
                .thenReturn("你只输出严格的 JSON 对象，不输出任何其他文字。");

        executor = new WorldGenAiNodeExecutor(aiChatGateway, promptTemplateService, currentUserProvider,
                usageLogService, schemaValidator, archiveService, props);
    }

    private NodeExecutionContext corelawsCtx(boolean withFailHook) {
        WorkflowNode node = new WorkflowNode();
        node.setId("agent_corelaws");
        node.setLabel("维度一：核心法则");
        node.setNodeType("agent");
        node.setAgentType("novel-worldbuilder-corelaws");
        node.setFirstMessage("【题材创意】{{premise}}\n请生成世界观维度一：核心法则。\n输出语言：{{language}}。");
        node.setOutputVariable("agent_corelaws_output");
        node.setSaveOutputToFile(true);
        node.setOutputFilePath("world/core_laws.json");
        Map<String, String> inputs = Map.of("premise", "废土之上", "genre", "科幻末世", "language", "中文");
        Set<String> fail = withFailHook ? Set.of("agent_corelaws") : Set.of();
        return new NodeExecutionContext(10L, 5L, "worldgen_build_v1", node,
                "【题材创意】废土之上\n请生成世界观维度一：核心法则。\n输出语言：中文。", inputs, Map.of(), fail);
    }

    @Test
    void successPathCallsAiWithJsonModeAndWritesCheckpoint() {
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(VALID_OUTPUT, "doubao", 100, 50, 150, null, null, 9L));

        NodeExecutionResult result = executor.execute(corelawsCtx(false));

        assertEquals(WorldGenStatus.SUCCEEDED, result.status());
        assertEquals(100, result.tokenIn());
        assertEquals(50, result.tokenOut());
        // 请求 json_mode=true 且用户消息 = 模板 + 首消息（合并策略）
        ArgumentCaptor<AiChatRequest> reqCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(anyLong(), anyLong(), any(), reqCaptor.capture());
        assertTrue(reqCaptor.getValue().jsonMode());
        String userMsg = reqCaptor.getValue().messages().get(1).content();
        assertTrue(userMsg.contains("【角色模板】你是一位世界底层规则架构师"));
        assertTrue(userMsg.contains("【题材创意】废土之上"));
        // 检查点写盘
        Path checkpoint = dataDir.resolve("worldgen").resolve("5").resolve("10").resolve("world/core_laws.json");
        assertTrue(Files.exists(checkpoint));
        // 用量记录
        verify(usageLogService).record(anyLong(), anyLong(), any(), any(), anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    void schemaFailureThenRetrySucceeds() {
        // 首次输出非法（taboos 是字符串），第二次合法
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("{\"core_laws\":{\"essence\":\"x\",\"power_system\":\"y\","
                                + "\"axioms\":[],\"taboos\":\"not array\",\"power_manifestation\":\"z\"}}",
                        "doubao", 10, 10, 20, null, null, 9L))
                .thenReturn(new AiChatResult(VALID_OUTPUT, "doubao", 20, 20, 40, null, null, 9L));

        NodeExecutionResult result = executor.execute(corelawsCtx(false));

        assertEquals(WorldGenStatus.SUCCEEDED, result.status());
        verify(aiChatGateway, times(2)).chatCompletion(anyLong(), anyLong(), any(), any());
        // 重试提示已带上次错误回传
        ArgumentCaptor<AiChatRequest> captor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway, times(2)).chatCompletion(anyLong(), anyLong(), any(), captor.capture());
        assertTrue(captor.getAllValues().get(1).messages().get(1).content().contains("上次输出未通过校验"));
    }

    @Test
    void allSchemaFailuresYieldFailure() {
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("{\"core_laws\":{\"essence\":\"x\"}}", "doubao", 5, 5, 10, null, null, 9L));

        NodeExecutionResult result = executor.execute(corelawsCtx(false));

        assertEquals(WorldGenStatus.FAILED, result.status());
        assertTrue(result.error().contains("校验失败"));
        // 总尝试 = schemaRetry(2) + 1 = 3
        verify(aiChatGateway, times(3)).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    @Test
    void aiDisabledFallsBackToStub() {
        props.setAiEnabled(false);
        NodeExecutionResult result = executor.execute(corelawsCtx(false));
        assertEquals(WorldGenStatus.SUCCEEDED, result.status());
        assertTrue(result.outputJson().contains("stub"));
        verify(aiChatGateway, Mockito.never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    @Test
    void simulateFailHookStillWorks() {
        NodeExecutionResult result = executor.execute(corelawsCtx(true));
        assertEquals(WorldGenStatus.FAILED, result.status());
        assertTrue(result.error().contains("simulateFailNodeIds"));
        verify(aiChatGateway, Mockito.never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    @Test
    void unknownAgentTypeFails() {
        WorkflowNode node = new WorkflowNode();
        node.setId("agent_x");
        node.setLabel("未知维度");
        node.setNodeType("agent");
        node.setAgentType("novel-worldbuilder-unknown");
        node.setFirstMessage("x");
        NodeExecutionContext ctx = new NodeExecutionContext(10L, 5L, "worldgen_build_v1", node,
                "x", Map.of(), Map.of(), Set.of());
        NodeExecutionResult result = executor.execute(ctx);
        assertEquals(WorldGenStatus.FAILED, result.status());
        assertTrue(result.error().contains("未配置维度提示词模板"));
    }
}
