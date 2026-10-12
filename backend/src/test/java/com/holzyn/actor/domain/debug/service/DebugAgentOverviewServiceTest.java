package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.npc.profile.repository.ActorAgentProfileRepository;
import com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * DebugAgentOverviewService（C4 AgentRuntime 概览聚合）单元测试（与实现同包，domain/debug/service）。
 * <p>职责：验证各角色 brainProfile + 决策频率/预算使用聚合（决策调用/决策链路去重/Token/最近决策时刻）、
 * 项目过滤、六组件级状态聚合（scene/stepType 归属：thinking/roleplay/memory/runtime/action/perception/
 * 其他未映射）、空态与缺失归属跳过。</p>
 */
class DebugAgentOverviewServiceTest {

    private ActorAgentProfileRepository agentProfileRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository ordinaryNpcRepository;
    private ActorAiCallLogRepository aiCallLogRepository;
    private ActorProjectRepository projectRepository;
    private ActorWorldClockRepository worldClockRepository;
    private ActorWorldEngineRepository engineRepository;
    private DebugAgentOverviewService service;

    @BeforeEach
    void setUp() {
        agentProfileRepository = Mockito.mock(ActorAgentProfileRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        ordinaryNpcRepository = Mockito.mock(ActorCharacterRepository.class);
        aiCallLogRepository = Mockito.mock(ActorAiCallLogRepository.class);
        projectRepository = Mockito.mock(ActorProjectRepository.class);
        worldClockRepository = Mockito.mock(ActorWorldClockRepository.class);
        engineRepository = Mockito.mock(ActorWorldEngineRepository.class);
        service = new DebugAgentOverviewService(agentProfileRepository, characterRepository,
                ordinaryNpcRepository, aiCallLogRepository, projectRepository, worldClockRepository,
                engineRepository, 5, true, 180000, 30);
    }

    /**
     * 构造特殊角色。
     */
    private static ActorCharacter character(Long id, Long projectId, String name) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setProjectId(projectId);
        c.setName(name);
        return c;
    }

    /**
     * 构造普通 NPC。
     */
    private static ActorCharacter npc(Long id, Long projectId, String name) {
        ActorCharacter n = new ActorCharacter();
        n.setId(id);
        n.setProjectId(projectId);
        n.setName(name);
        // G1a：单主表下普通型必须显式 type=common（实体默认值是 special）
        n.setType("common");
        return n;
    }

    /**
     * 构造脑力配置行。
     */
    private static ActorAgentProfile profile(String type, Long refId) {
        ActorAgentProfile p = new ActorAgentProfile();
        p.setAgentType(type);
        p.setAgentRefId(refId);
        p.setBrainLevel("full");
        p.setModelTier("auto");
        p.setDecisionFrequency("realtime");
        return p;
    }

    /**
     * 构造 AI 调用行。
     */
    private static ActorAiCallLog call(Long characterId, String scene, String stepType, String traceId,
                                       int tokenIn, int tokenOut, LocalDateTime at) {
        ActorAiCallLog c = new ActorAiCallLog();
        c.setCharacterId(characterId);
        c.setProjectId(1L);
        c.setScene(scene);
        if (stepType != null) {
            c.setStepType(stepType);
        }
        c.setTraceId(traceId);
        c.setTokenIn(tokenIn);
        c.setTokenOut(tokenOut);
        c.setCreatedAt(at);
        return c;
    }

    /**
     * 默认装配：项目 1 + 角色 1/2 + 普通 NPC 3 + 三条脑力配置。
     */
    private void mockBase() {
        ActorProject p1 = new ActorProject();
        p1.setId(1L);
        p1.setName("罗小黑");
        when(projectRepository.findByUserIdAndDeletedOrderByUpdatedAtDesc(1L, 0)).thenReturn(List.of(p1));
        // G1a：单表后特殊型 + 普通型同在一个 id→实体 索引（不再查普通居民表）
        when(characterRepository.findByDeletedOrderByIdAsc(0))
                .thenReturn(List.of(character(1L, 1L, "李雷"), character(2L, 1L, "林安"), npc(3L, 1L, "居民阿明")));
        when(agentProfileRepository.findAll())
                .thenReturn(List.of(profile("special", 1L), profile("special", 2L), profile("ordinary", 3L)));
        when(worldClockRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.empty());
    }

    /**
     * 各角色聚合：决策调用计数/决策链路去重/Token/最近决策时刻/brainProfile 字段透传。
     */
    @Test
    void agentRowsAggregateDecisionStats() {
        mockBase();
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 12, 0);
        // 角色 1：3 次决策 AI 调用（2 次同 trace_id 重试）+ 1 次对话 + 1 次记忆
        when(aiCallLogRepository.findByCreatedAtGreaterThanEqual(Mockito.any()))
                .thenReturn(List.of(
                        call(1L, "action", null, "traceA", 100, 20, now.minusMinutes(30)),
                        call(1L, "action", null, "traceA", 90, 15, now.minusMinutes(29)), // 同链路重试
                        call(1L, "action", null, "traceB", 80, 10, now.minusMinutes(10)),
                        call(1L, "dialog", null, "traceC", 200, 50, now.minusMinutes(5)),
                        call(1L, "memory", null, "traceD", 50, 10, now.minusMinutes(1))));

        Map<String, Object> out = service.runtimeOverview(null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) out.get("agents");
        assertEquals(3, agents.size(), "3 条脑力配置应全量展示");
        Map<String, Object> liLei = agents.stream()
                .filter(a -> "李雷".equals(a.get("name"))).findFirst().orElseThrow();
        assertEquals("special", liLei.get("agentType"));
        assertEquals("full", liLei.get("brainLevel"));
        assertEquals(5, liLei.get("aiCalls30d"), "角色 1 近窗口全部 AI 调用 5 次");
        assertEquals(3, liLei.get("decisionCalls30d"), "决策 AI 调用（scene=action）3 次");
        assertEquals(2, liLei.get("traces30d"), "决策链路按 trace_id 去重为 2 条");
        assertEquals(520, liLei.get("tokenIn30d"), "Token 用量累计 100+90+80+200+50");
        assertEquals(105, liLei.get("tokenOut30d"));
        assertEquals(now.minusMinutes(10).toString(), liLei.get("lastDecisionAt"),
                "最近决策调用时刻=最近一次 scene=action（now-10m），对话/记忆调用不计入");
    }

    /**
     * 项目过滤：projectId 传入后仅返回该项目角色。
     */
    @Test
    void runtimeOverviewFiltersByProject() {
        mockBase();
        // 角色 2 属于项目 2，项目过滤 1 时不应出现
        ActorProject p2 = new ActorProject();
        p2.setId(2L);
        p2.setName("另一项目");
        when(projectRepository.findByUserIdAndDeletedOrderByUpdatedAtDesc(1L, 0))
                .thenReturn(List.of(project1(), p2));
        when(characterRepository.findByDeletedOrderByIdAsc(0))
                .thenReturn(List.of(character(1L, 1L, "李雷"), character(2L, 2L, "他乡角色"), npc(3L, 1L, "居民阿明")));
        when(agentProfileRepository.findAll())
                .thenReturn(List.of(profile("special", 1L), profile("special", 2L), profile("ordinary", 3L)));

        when(aiCallLogRepository.findByProjectIdAndCreatedAtGreaterThanEqual(Mockito.eq(1L), Mockito.any()))
                .thenReturn(List.of());
        when(worldClockRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(engineRepository.findByProjectId(1L)).thenReturn(Optional.empty());

        Map<String, Object> out = service.runtimeOverview(1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) out.get("agents");
        assertEquals(2, agents.size(), "项目 1 仅含李雷 + 居民阿明");
        assertTrue(agents.stream().noneMatch(a -> "他乡角色".equals(a.get("name"))), "项目过滤应排除他项目角色");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projects = (List<Map<String, Object>>) out.get("projects");
        assertEquals(1, projects.size(), "项目摘要应过滤到项目 1");
    }

    /**
     * 组件级状态聚合：scene/stepType 归属正确（thinking/roleplay/memory/runtime/action/perception/其他）。
     */
    @Test
    void componentRowsMapBySceneAndStepType() {
        mockBase();
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 12, 0);
        when(aiCallLogRepository.findByCreatedAtGreaterThanEqual(Mockito.any()))
                .thenReturn(List.of(
                        call(1L, "action", null, "t1", 10, 1, now),           // thinking（决策 AI）
                        call(1L, "dialog", null, "t2", 10, 1, now),          // roleplay
                        call(1L, "memory", null, "t3", 10, 1, now),          // memory
                        call(1L, "world_event_polish", null, "t4", 10, 1, now), // runtime
                        call(null, "action", "action", "t5", 0, 0, now),     // action（非 AI 环节）
                        call(null, "dialog", "perception", "t6", 0, 0, now), // perception（非 AI 环节）
                        call(null, "agent_decision", "thinking", "t8", 0, 0, now), // thinking（非 AI 思考环节）
                        call(null, "agent_decision", "memory", "t9", 0, 0, now),   // memory（非 AI 记忆环节）
                        call(null, "card_gen", null, "t7", 10, 1, now)));    // 其他（未映射）

        Map<String, Object> out = service.runtimeOverview(null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comps = (List<Map<String, Object>>) out.get("components");
        assertEquals(9, comps.size(), "六组件 + 2 扩展 + 其他");
        assertTrue(((Integer) comp(comps, "thinking").get("aiCalls30d")) == 1, "thinking=决策 AI 1 次");
        assertTrue(((Integer) comp(comps, "thinking").get("steps30d")) == 1, "thinking=思考环节 1 步");
        assertTrue(((Integer) comp(comps, "roleplay").get("aiCalls30d")) == 1, "roleplay=对话 1 次");
        assertTrue(((Integer) comp(comps, "memory").get("aiCalls30d")) == 1, "memory=记忆 AI 1 次");
        assertTrue(((Integer) comp(comps, "memory").get("steps30d")) == 1, "memory=记忆环节 1 步");
        assertTrue(((Integer) comp(comps, "runtime").get("aiCalls30d")) == 1, "runtime=跨日润色 1 次");
        assertTrue(((Integer) comp(comps, "action").get("steps30d")) == 1, "action=非 AI 行动环节 1 步");
        assertTrue(((Integer) comp(comps, "perception").get("steps30d")) == 1, "perception=感知环节 1 步");
        assertTrue(((Integer) comp(comps, "other").get("aiCalls30d")) == 1, "其他=未映射 card_gen 1 次");
        assertTrue(((Integer) comp(comps, "other").get("steps30d")) == 0, "thinking/memory 环节不再落入其他");
        assertTrue(((Integer) comp(comps, "learning").get("aiCalls30d")) == 0, "learning 无调用");
    }

    /**
     * 空态：无 AI 调用时角色行仍展示（统计为 0），组件行全 0。
     */
    @Test
    void runtimeOverviewEmptyState() {
        mockBase();
        when(aiCallLogRepository.findByCreatedAtGreaterThanEqual(Mockito.any())).thenReturn(List.of());

        Map<String, Object> out = service.runtimeOverview(null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) out.get("agents");
        assertEquals(3, agents.size(), "无调用仍展示全部脑力配置角色");
        assertTrue(agents.stream().allMatch(a -> ((Integer) a.get("decisionCalls30d")) == 0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comps = (List<Map<String, Object>>) out.get("components");
        assertTrue(comps.stream().allMatch(c -> ((Integer) c.get("aiCalls30d")) == 0
                && ((Integer) c.get("steps30d")) == 0));
    }

    /**
     * 缺失归属跳过：脑力配置对应的角色已删除/不存在时不展示。
     */
    @Test
    void agentRowsSkipMissingOwners() {
        mockBase();
        // 角色 2 不在未删除列表（模拟已删除），其脑力配置应被跳过
        when(characterRepository.findByDeletedOrderByIdAsc(0))
                .thenReturn(List.of(character(1L, 1L, "李雷"), npc(3L, 1L, "居民阿明")));
        when(aiCallLogRepository.findByCreatedAtGreaterThanEqual(Mockito.any())).thenReturn(List.of());

        Map<String, Object> out = service.runtimeOverview(null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) out.get("agents");
        assertEquals(2, agents.size(), "已删除角色 2 的脑力配置应跳过（剩李雷 + 普通居民阿明）");
        assertTrue(agents.stream().noneMatch(a -> "林安".equals(a.get("name"))));
    }

    /**
     * 取组件行。
     */
    private static Map<String, Object> comp(List<Map<String, Object>> comps, String key) {
        return comps.stream().filter(c -> key.equals(c.get("key"))).findFirst().orElseThrow();
    }

    /**
     * 构造项目 1 实体。
     */
    private static ActorProject project1() {
        ActorProject p = new ActorProject();
        p.setId(1L);
        p.setName("罗小黑");
        return p;
    }
}
