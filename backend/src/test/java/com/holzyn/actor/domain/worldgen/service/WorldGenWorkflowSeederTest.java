package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenWorkflowRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldGenWorkflowSeeder（M1 引擎骨架：内置工作流定义启动 seed；M4 适配轮扩展多工作流）单元测试。
 * <p>职责：验证——资源文件可解析、build_v1 定义结构完整（M2 前向契约 + M5 并行拓扑：
 * 前 3 维并行边 → 共 12 条边）、character_v1 定义结构完整、插入/幂等/内容变更同步更新（版本 +1）。</p>
 * <p>说明：不依赖数据库（Mockito mock 仓库）；资源加载走 classpath（测试 classpath 含 main resources）。</p>
 */
class WorldGenWorkflowSeederTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 读取资源并拆出三段 JSON（镜像 Seeder 逻辑）。 */
    private String[] builtinParts(String code) throws Exception {
        String resource = WorldGenWorkflowSeeder.BUILTIN_RESOURCES.get(code);
        String json = new String(
                WorldGenWorkflowSeeder.class.getClassLoader()
                        .getResourceAsStream(resource).readAllBytes(),
                StandardCharsets.UTF_8);
        JsonNode root = MAPPER.readTree(json);
        return new String[]{
                MAPPER.writeValueAsString(root.path("nodes")),
                MAPPER.writeValueAsString(root.path("edges")),
                MAPPER.writeValueAsString(root.path("variables"))
        };
    }

    /** 构造与内置内容一致的工作流实体（幂等跳过用）。 */
    private ActorWorldgenWorkflow matching(String code, int version) throws Exception {
        String[] parts = builtinParts(code);
        ActorWorldgenWorkflow w = new ActorWorldgenWorkflow();
        w.setCode(code);
        w.setName(switch (code) {
            case WorldGenWorkflowSeeder.CODE_BUILD_V1 -> "世界观生成（6 维 · 前3并行后3链式）";
            case WorldGenWorkflowSeeder.CODE_CHARACTER_V1 -> "角色阵容生成（骨架/信念/深层/声线）";
            case WorldGenWorkflowSeeder.CODE_STORY_PLAN_V1 -> "故事规划生成（三层力学引擎 + 风格档案）";
            default -> code;
        });
        w.setVersion(version);
        w.setNodesJson(parts[0]);
        w.setEdgesJson(parts[1]);
        w.setVariablesJson(parts[2]);
        w.setEnabled(1);
        return w;
    }

    @Test
    void seedInsertsAllBuiltinDefinitions() throws Exception {
        ActorWorldgenWorkflowRepository repo = Mockito.mock(ActorWorldgenWorkflowRepository.class);
        when(repo.findByCode(any())).thenReturn(Optional.empty());
        WorldGenWorkflowSeeder seeder = new WorldGenWorkflowSeeder(repo);

        seeder.run(null);

        // 三个内置工作流都插入
        ArgumentCaptor<ActorWorldgenWorkflow> captor = ArgumentCaptor.forClass(ActorWorldgenWorkflow.class);
        verify(repo, times(3)).save(captor.capture());
        List<ActorWorldgenWorkflow> saved = captor.getAllValues();
        assertEquals(3, saved.size());
        assertTrue(saved.stream().anyMatch(w -> WorldGenWorkflowSeeder.CODE_BUILD_V1.equals(w.getCode())));
        assertTrue(saved.stream().anyMatch(w -> WorldGenWorkflowSeeder.CODE_CHARACTER_V1.equals(w.getCode())));
        assertTrue(saved.stream().anyMatch(w -> WorldGenWorkflowSeeder.CODE_STORY_PLAN_V1.equals(w.getCode())));

        // build_v1 定义完整性：9 节点（6 agent + 3 script）
        ActorWorldgenWorkflow build = saved.stream()
                .filter(w -> WorldGenWorkflowSeeder.CODE_BUILD_V1.equals(w.getCode())).findFirst().orElseThrow();
        assertEquals(1, build.getEnabled());
        JsonNode nodes = MAPPER.readTree(build.getNodesJson());
        assertEquals(9, nodes.size());
        long agents = 0, scripts = 0;
        for (JsonNode n : nodes) {
            if ("agent".equals(n.path("nodeType").asText())) agents++;
            else if ("script".equals(n.path("nodeType").asText())) scripts++;
        }
        assertEquals(6, agents, "应含 6 个 agent（6 维）");
        assertEquals(3, scripts, "应含 3 个 script（checkpoint/render/persist）");
        // M5 并行拓扑：前 3 维并行（start→corelaws/spacetime/society 三入口）+ 后 3 维链式 → 12 条边
        assertEquals(12, MAPPER.readTree(build.getEdgesJson()).size());
        // 变量：premise/genre/language
        assertEquals(3, MAPPER.readTree(build.getVariablesJson()).size());

        // character_v1 定义完整性：4 节点（2 agent + 2 script），5 条边，3 变量
        ActorWorldgenWorkflow charWf = saved.stream()
                .filter(w -> WorldGenWorkflowSeeder.CODE_CHARACTER_V1.equals(w.getCode())).findFirst().orElseThrow();
        JsonNode charNodes = MAPPER.readTree(charWf.getNodesJson());
        assertEquals(4, charNodes.size());
        assertEquals(5, MAPPER.readTree(charWf.getEdgesJson()).size());
        assertEquals(3, MAPPER.readTree(charWf.getVariablesJson()).size());
        // batch 深度节点参数（M4 前向契约）
        JsonNode depthNode = null;
        for (JsonNode n : charNodes) {
            if ("agent_character_depth".equals(n.path("id").asText())) {
                depthNode = n;
            }
        }
        assertNotNull(depthNode, "character 工作流应含 agent_character_depth 节点");
        assertEquals(true, depthNode.path("nodeParams").path("batch").asBoolean(),
                "深度节点应声明 batch=true（逐角色循环）");

        // story_plan_v1 定义完整性：4 节点（2 agent + 2 script），5 条边，4 变量
        ActorWorldgenWorkflow storyWf = saved.stream()
                .filter(w -> WorldGenWorkflowSeeder.CODE_STORY_PLAN_V1.equals(w.getCode())).findFirst().orElseThrow();
        JsonNode storyNodes = MAPPER.readTree(storyWf.getNodesJson());
        assertEquals(4, storyNodes.size());
        assertEquals(5, MAPPER.readTree(storyWf.getEdgesJson()).size());
        assertEquals(4, MAPPER.readTree(storyWf.getVariablesJson()).size());
        long storyAgents = 0, storyScripts = 0;
        for (JsonNode n : storyNodes) {
            if ("agent".equals(n.path("nodeType").asText())) storyAgents++;
            else if ("script".equals(n.path("nodeType").asText())) storyScripts++;
        }
        assertEquals(2, storyAgents, "story-plan 应含 2 个 agent（故事引擎/风格档案）");
        assertEquals(2, storyScripts, "story-plan 应含 2 个 script（两段式落库）");
    }

    @Test
    void seedIdempotentWhenUnchanged() throws Exception {
        ActorWorldgenWorkflowRepository repo = Mockito.mock(ActorWorldgenWorkflowRepository.class);
        when(repo.findByCode(WorldGenWorkflowSeeder.CODE_BUILD_V1))
                .thenReturn(Optional.of(matching(WorldGenWorkflowSeeder.CODE_BUILD_V1, 2)));
        when(repo.findByCode(WorldGenWorkflowSeeder.CODE_CHARACTER_V1))
                .thenReturn(Optional.of(matching(WorldGenWorkflowSeeder.CODE_CHARACTER_V1, 1)));
        when(repo.findByCode(WorldGenWorkflowSeeder.CODE_STORY_PLAN_V1))
                .thenReturn(Optional.of(matching(WorldGenWorkflowSeeder.CODE_STORY_PLAN_V1, 1)));

        WorldGenWorkflowSeeder seeder = new WorldGenWorkflowSeeder(repo);
        seeder.run(null);
        verify(repo, never()).save(any());
    }

    @Test
    void seedUpdatesWhenContentChanged() throws Exception {
        ActorWorldgenWorkflowRepository repo = Mockito.mock(ActorWorldgenWorkflowRepository.class);
        String[] parts = builtinParts(WorldGenWorkflowSeeder.CODE_BUILD_V1);
        ActorWorldgenWorkflow existing = new ActorWorldgenWorkflow();
        existing.setCode(WorldGenWorkflowSeeder.CODE_BUILD_V1);
        existing.setName("世界观生成（6 维 · 前3并行后3链式）");
        existing.setVersion(2);
        existing.setNodesJson(parts[0]);
        existing.setEdgesJson(parts[1]);
        existing.setVariablesJson("[]"); // 与内置不一致 → 触发同步更新
        existing.setEnabled(1);
        when(repo.findByCode(WorldGenWorkflowSeeder.CODE_BUILD_V1)).thenReturn(Optional.of(existing));
        when(repo.findByCode(WorldGenWorkflowSeeder.CODE_CHARACTER_V1))
                .thenReturn(Optional.of(matching(WorldGenWorkflowSeeder.CODE_CHARACTER_V1, 1)));
        when(repo.findByCode(WorldGenWorkflowSeeder.CODE_STORY_PLAN_V1))
                .thenReturn(Optional.of(matching(WorldGenWorkflowSeeder.CODE_STORY_PLAN_V1, 1)));

        WorldGenWorkflowSeeder seeder = new WorldGenWorkflowSeeder(repo);
        seeder.run(null);
        ArgumentCaptor<ActorWorldgenWorkflow> captor = ArgumentCaptor.forClass(ActorWorldgenWorkflow.class);
        verify(repo).save(captor.capture());
        assertEquals(3, captor.getValue().getVersion(), "内容变更应版本号 +1");
        assertEquals(parts[2], captor.getValue().getVariablesJson());
    }
}
