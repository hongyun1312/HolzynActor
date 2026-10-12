package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.domain.worldgen.config.WorldGenProperties;
import com.holzyn.actor.domain.worldgen.model.WorkflowNode;
import com.holzyn.actor.domain.worldgen.model.WorldGenStatus;
import com.holzyn.actor.domain.worldgen.vo.WorldGenPreviewVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldGenScriptNodeExecutor（M2：脚本节点按 scriptName 分发——checkpoint/render/persist 预览）单元测试。
 * <p>职责：验证——参数解析（--files/--title）、checkpoint 分发、render 分发、persist 预览分发、
 * 未注册脚本失败、总开关关闭桩回退。</p>
 */
class WorldGenScriptNodeExecutorTest {

    private LocalArchiveService archiveService;
    private WorldGenRenderer renderer;
    private WorldGenPersistService persistService;
    private WorldGenProperties props;
    private WorldGenScriptNodeExecutor executor;

    @BeforeEach
    void setUp() {
        archiveService = Mockito.mock(LocalArchiveService.class);
        renderer = Mockito.mock(WorldGenRenderer.class);
        persistService = Mockito.mock(WorldGenPersistService.class);
        props = new WorldGenProperties();
        executor = new WorldGenScriptNodeExecutor(archiveService, renderer, persistService, props);
        when(archiveService.taskWorkspace(any(), any())).thenReturn(Path.of("ws"));
    }

    private NodeExecutionContext scriptCtx(String scriptName, String scriptArgs) {
        WorkflowNode node = new WorkflowNode();
        node.setId("script_x");
        node.setLabel("脚本X");
        node.setNodeType("script");
        node.setScriptName(scriptName);
        node.setScriptArgs(scriptArgs);
        return new NodeExecutionContext(10L, 5L, "worldgen_build_v1", node, scriptArgs,
                Map.of(), Map.of(), Set.of());
    }

    @Test
    void parseArgsSplitsKeyValues() {
        Map<String, List<String>> args = WorldGenScriptNodeExecutor.parseArgs(
                "checkpoint --files world/a.json world/b.json --flag");
        assertEquals(List.of("world/a.json", "world/b.json"), args.get("--files"));
        assertTrue(args.containsKey("--flag"));
        assertTrue(args.get("--flag").isEmpty());
    }

    @Test
    void checkpointDispatchValidatesFiles() {
        when(archiveService.checkpoint(any(), anyList())).thenReturn(2);
        NodeExecutionResult result = executor.execute(
                scriptCtx("local_archive", "checkpoint --files world/a.json world/b.json"));
        assertEquals(WorldGenStatus.SUCCEEDED, result.status());
        assertTrue(result.outputJson().contains("\"checkpoint\":true"));
        assertTrue(result.outputJson().contains("filesChecked\":2"));
        verify(archiveService).checkpoint(any(), eq(List.of("world/a.json", "world/b.json")));
    }

    @Test
    void renderDispatchProducesOutput() {
        when(renderer.render(any(), anyString()))
                .thenReturn(new WorldGenRenderer.RenderResult(6, "# 世界观基础\n...", "meta/world_foundation.md"));
        NodeExecutionResult result = executor.execute(
                scriptCtx("render_worldview", "--input-dir world --output meta/world_foundation.md --title 世界观基础"));
        assertEquals(WorldGenStatus.SUCCEEDED, result.status());
        assertTrue(result.outputJson().contains("\"rendered\":true"));
        assertTrue(result.outputJson().contains("sections\":6"));
        verify(renderer).render(any(), eq("世界观基础"));
    }

    @Test
    void persistDispatchProducesPreview() {
        WorldGenPreviewVO preview = new WorldGenPreviewVO();
        preview.setTaskId(10L);
        preview.setProjectId(5L);
        preview.setSections(Map.of("magicSystem", "四阶修炼", "geography", "九洲"));
        when(persistService.preview(10L)).thenReturn(preview);
        NodeExecutionResult result = executor.execute(
                scriptCtx("persist_to_world_setting", "--project-id 5 --preview-only true"));
        assertEquals(WorldGenStatus.SUCCEEDED, result.status());
        assertTrue(result.outputJson().contains("\"preview\":true"));
        assertTrue(result.outputJson().contains("confirmEndpoint"));
        verify(persistService).preview(10L);
    }

    @Test
    void unknownScriptFails() {
        NodeExecutionResult result = executor.execute(scriptCtx("ghost_script", "--x 1"));
        assertEquals(WorldGenStatus.FAILED, result.status());
        assertTrue(result.error().contains("未注册脚本"));
    }

    @Test
    void disabledFallsBackToStub() {
        props.setEnabled(false);
        NodeExecutionResult result = executor.execute(
                scriptCtx("local_archive", "checkpoint --files world/a.json"));
        assertEquals(WorldGenStatus.SUCCEEDED, result.status());
        assertTrue(result.outputJson().contains("stub"));
    }
}
