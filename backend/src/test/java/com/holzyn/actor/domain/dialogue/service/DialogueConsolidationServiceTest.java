package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.dialogue.dto.DialogueConsolidationApplyDTO;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.dialogue.entity.ActorPlayerRelation;
import com.holzyn.actor.domain.dialogue.repository.ActorPlayerRelationRepository;
import com.holzyn.actor.domain.dialogue.vo.DialogueConsolidationResultVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueConsolidationVO;
import com.holzyn.actor.domain.dialogue.vo.DialogueGroupMemberVO;
import com.holzyn.actor.domain.dialogue.vo.PlayerRelationVO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

/**
 * 对谈沉淀服务单元测试（AI mock，零真实调用）。
 * <p>职责：验证——preview 确认卡片（单聊范围/群聊成员范围/AI 抽取解析[delta 钳制·proposed 计算]/
 * 内容过少拒绝/AI 失败 fail-open/空 relations/用量记账 scene=dialogue_consolidation）；
 * parseRelations 静态解析（越界角色过滤·边界收敛·要点截断上限）；apply 应用（关系写回 +
 * NPC 记忆 + 会话版本留痕/越界候选拒绝/空候选拒绝/记忆单条失败跳过继续）。</p>
 */
class DialogueConsolidationServiceTest {

    private DialogueSessionService sessionService;
    private DialogueMessageService messageService;
    private DialogueGroupService groupService;
    private PlayerProfileService playerProfileService;
    private DialogueRelationService relationService;
    private AgentMemoryService agentMemoryService;
    private ActorPlayerRelationRepository relationRepository;
    private ActorCharacterRepository characterRepository;
    private AiSpringChatGateway aiChatGateway;
    private DialogueConsolidationService service;

    @BeforeEach
    void setUp() {
        sessionService = mock(DialogueSessionService.class);
        messageService = mock(DialogueMessageService.class);
        groupService = mock(DialogueGroupService.class);
        playerProfileService = mock(PlayerProfileService.class);
        relationService = mock(DialogueRelationService.class);
        agentMemoryService = mock(AgentMemoryService.class);
        relationRepository = mock(ActorPlayerRelationRepository.class);
        characterRepository = mock(ActorCharacterRepository.class);
        aiChatGateway = mock(AiSpringChatGateway.class);
        service = new DialogueConsolidationService();
        ReflectionTestUtils.setField(service, "sessionService", sessionService);
        ReflectionTestUtils.setField(service, "messageService", messageService);
        ReflectionTestUtils.setField(service, "groupService", groupService);
        ReflectionTestUtils.setField(service, "playerProfileService", playerProfileService);
        ReflectionTestUtils.setField(service, "relationService", relationService);
        ReflectionTestUtils.setField(service, "agentMemoryService", agentMemoryService);
        ReflectionTestUtils.setField(service, "relationRepository", relationRepository);
        ReflectionTestUtils.setField(service, "characterRepository", characterRepository);
        ReflectionTestUtils.setField(service, "aiChatGateway", aiChatGateway);

        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        stubCharacter(1L, "沈守拙");
        stubCharacter(2L, "林听竹");
    }

    private void stubCharacter(Long id, String name) {
        ActorCharacter ch = new ActorCharacter();
        ch.setId(id);
        ch.setName(name);
        when(characterRepository.findById(id)).thenReturn(Optional.of(ch));
    }

    private ActorDialogueSession singleSession() {
        ActorDialogueSession s = new ActorDialogueSession();
        s.setId(7L);
        s.setProjectId(1L);
        s.setType("single");
        s.setCharacterRef("npc:1");
        s.setPlayerRelationVersion(0);
        return s;
    }

    private ActorDialogueSession groupSession() {
        ActorDialogueSession s = new ActorDialogueSession();
        s.setId(7L);
        s.setProjectId(1L);
        s.setType("group");
        s.setLocationId(10L);
        s.setLocationName("村口");
        return s;
    }

    private DialogueGroupMemberVO member(Long id, String ref, String name) {
        return new DialogueGroupMemberVO(id, 7L, ref, name, "present", LocalDateTime.now());
    }

    private ActorDialogueMessage msg(Long id, String role, String ref, String content) {
        ActorDialogueMessage m = new ActorDialogueMessage();
        m.setId(id);
        m.setSessionId(7L);
        m.setRole(role);
        m.setCharacterRef(ref);
        m.setContent(content);
        m.setSeq(id.intValue());
        return m;
    }

    private ActorPlayerRelation relation(int affinity, int trust, String stage) {
        ActorPlayerRelation r = new ActorPlayerRelation();
        r.setId(3L);
        r.setProjectId(1L);
        r.setCharacterRef("npc:1");
        r.setAffinity(affinity);
        r.setTrust(trust);
        r.setStage(stage);
        r.setVersion(1);
        return r;
    }

    /** 标准单聊会话 + 对话窗桩（玩家 + 角色各一条）。 */
    private void stubSingleWithDialog() {
        when(sessionService.require(1L, 7L)).thenReturn(singleSession());
        when(messageService.listEntities(1L, 7L))
                .thenReturn(List.of(msg(1L, "user", null, "你好"), msg(2L, "assistant", "npc:1", "你也好")));
        when(relationRepository.findByProjectIdAndCharacterRef(1L, "npc:1"))
                .thenReturn(Optional.of(relation(40, 35, "相识")));
    }

    /** 网关 AI 抽取返回（jsonMode 契约：{"relations":[...]}）。 */
    private void gatewayReturns(String json) {
        when(aiChatGateway.chatCompletion(eq(1L), eq(1L), eq(null), any()))
                .thenReturn(new AiChatResult(json, "deepseek", 100, 50, 150, 1L));
    }

    // ==================== preview：确认卡片 ====================

    @Test
    void previewSingleBuildsCardFromAiExtraction() {
        stubSingleWithDialog();
        gatewayReturns("{\"relations\":[{\"character_ref\":\"npc:1\",\"affinity_delta\":5,\"trust_delta\":2,"
                + "\"stage\":\"熟络\",\"note\":\"承诺帮修屋顶\",\"state_summary\":\"关系渐熟，信任其承诺\","
                + "\"memory_points\":[\"玩家答应帮忙修屋顶\",\"玩家来自外地\"]}]}");

        DialogueConsolidationVO vo = service.preview(1L, 1L, 7L);

        assertEquals(7L, vo.getSessionId());
        assertEquals("single", vo.getType());
        assertEquals(1, vo.getCharacters().size());
        DialogueConsolidationVO.CharacterPreview c = vo.getCharacters().get(0);
        assertEquals("npc:1", c.getCharacterRef());
        assertEquals("沈守拙", c.getCharacterName());
        assertEquals(40, c.getCurrentAffinity());
        assertEquals(35, c.getCurrentTrust());
        assertEquals("相识", c.getCurrentStage());
        assertEquals(5, c.getAffinityDelta());
        assertEquals(2, c.getTrustDelta());
        assertEquals(45, c.getProposedAffinity());
        assertEquals(37, c.getProposedTrust());
        assertEquals("熟络", c.getStage());
        assertEquals("承诺帮修屋顶", c.getNote());
        assertEquals("关系渐熟，信任其承诺", c.getStateSummary());
        assertEquals(2, c.getMemoryPoints().size());
    }

    @Test
    void previewSystemPromptEmbedsJsonContract() {
        // 第 61 轮冒烟抓修★：契约必须内联进 system（旧实现只说「见模板契约」→ 模型输出中文键变体
        // {"赵万金":{"关系_delta":{…}}} → parseRelations 严格读 relations 数组全部丢弃 → 空卡片）
        stubSingleWithDialog();
        when(aiChatGateway.chatCompletion(eq(1L), eq(1L), eq(null), any()))
                .thenReturn(new AiChatResult("{\"relations\":[]}", "deepseek", 100, 50, 150, 1L));

        service.preview(1L, 1L, 7L);

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiChatGateway).chatCompletion(eq(1L), eq(1L), eq(null), cap.capture());
        String system = cap.getValue().messages().get(0).content();
        assertTrue(system.contains("relations"), "system 必须内联 relations 契约键");
        assertTrue(system.contains("affinity_delta"), "system 必须内联 affinity_delta 契约键");
        assertTrue(system.contains("character_ref"), "system 必须内联 character_ref 契约键");
        assertTrue(system.contains("memory_points"), "system 必须内联 memory_points 契约键");
    }

    @Test
    void previewGroupScopesToMembers() {
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(messageService.listEntities(1L, 7L))
                .thenReturn(List.of(msg(1L, "user", null, "大家好"), msg(2L, "group", "npc:1", "诸位安好")));
        when(groupService.members(1L, 7L))
                .thenReturn(List.of(member(1L, "npc:1", "沈守拙"), member(2L, "npc:2", "林听竹")));
        when(relationRepository.findByProjectIdAndCharacterRef(1L, "npc:1"))
                .thenReturn(Optional.of(relation(20, 20, "陌生")));
        when(relationRepository.findByProjectIdAndCharacterRef(1L, "npc:2")).thenReturn(Optional.empty());
        gatewayReturns("{\"relations\":["
                + "{\"character_ref\":\"npc:1\",\"affinity_delta\":2,\"trust_delta\":1,\"memory_points\":[\"玩家打招呼\"]},"
                + "{\"character_ref\":\"npc:2\",\"affinity_delta\":1,\"trust_delta\":0,\"memory_points\":[]}]}");

        DialogueConsolidationVO vo = service.preview(1L, 1L, 7L);

        assertEquals("group", vo.getType());
        assertEquals(2, vo.getCharacters().size());
        assertEquals("npc:2", vo.getCharacters().get(1).getCharacterRef());
        // npc:2 无关系行 → 当前值 0/0/陌生
        assertEquals(0, vo.getCharacters().get(1).getCurrentAffinity());
        assertEquals("陌生", vo.getCharacters().get(1).getCurrentStage());
    }

    @Test
    void previewTooFewMessagesRejected() {
        when(sessionService.require(1L, 7L)).thenReturn(singleSession());
        when(messageService.listEntities(1L, 7L)).thenReturn(List.of(msg(1L, "user", null, "你好")));
        BizException e = assertThrows(BizException.class, () -> service.preview(1L, 1L, 7L));
        assertTrue(e.getMessage().contains("对话内容太少"));
    }

    @Test
    void previewGroupEmptyMembersRejected() {
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(groupService.members(1L, 7L)).thenReturn(List.of());
        BizException e = assertThrows(BizException.class, () -> service.preview(1L, 1L, 7L));
        assertTrue(e.getMessage().contains("还没有成员"));
    }

    @Test
    void previewAiFailureFailsOpen() {
        stubSingleWithDialog();
        when(aiChatGateway.chatCompletion(eq(1L), eq(1L), eq(null), any()))
                .thenThrow(new AiCallException("网关超时"));
        BizException e = assertThrows(BizException.class, () -> service.preview(1L, 1L, 7L));
        assertTrue(e.getMessage().contains("沉淀分析失败"));
    }

    @Test
    void previewEmptyAiResultYieldsEmptyCard() {
        stubSingleWithDialog();
        gatewayReturns("{\"relations\":[]}");
        DialogueConsolidationVO vo = service.preview(1L, 1L, 7L);
        assertTrue(vo.getCharacters().isEmpty());
    }

    @Test
    void previewRecordsUsageSceneConsolidation() {
        stubSingleWithDialog();
        gatewayReturns("{\"relations\":[{\"character_ref\":\"npc:1\",\"affinity_delta\":0,\"trust_delta\":0}]}");
        UsageLogService usageLogService = mock(UsageLogService.class);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);

        service.preview(1L, 1L, 7L);

        verify(usageLogService).record(eq(1L), eq(1L), eq(1L), any(), any(),
                eq(DialogueConsolidationService.SCENE_CONSOLIDATION), any(), any(), any(), any(), any());
    }

    // ==================== parseRelations：AI JSON 解析 ====================

    @Test
    void parseRelationsFiltersScopeAndClampsBoundaries() {
        String json = "{\"relations\":["
                + "{\"character_ref\":\"npc:1\",\"affinity_delta\":99,\"trust_delta\":-99,"
                + "\"stage\":\"\",\"memory_points\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"]},"
                + "{\"character_ref\":\"npc:9\",\"affinity_delta\":5,\"trust_delta\":5}]}";
        List<DialogueConsolidationService.ExtractedRelation> out =
                DialogueConsolidationService.parseRelations(json, Set.of("npc:1"));

        assertEquals(1, out.size());
        DialogueConsolidationService.ExtractedRelation r = out.get(0);
        assertEquals("npc:1", r.characterRef());
        assertEquals(30, r.affinityDelta());  // 越界钳制
        assertEquals(-30, r.trustDelta());
        assertEquals(null, r.stage());        // 空串 → null
        assertEquals(5, r.memoryPoints().size()); // 要点上限
    }

    @Test
    void parseRelationsNormalizesNameQualifiedRef() {
        // 第 61 轮冒烟抓修★：模型输出「赵万金（npc:3）」带名变体 → 容错归一为纯引用 npc:3
        String json = "{\"relations\":["
                + "{\"character_ref\":\"赵万金（npc:3）\",\"affinity_delta\":10,\"trust_delta\":5,"
                + "\"memory_points\":[\"玩家赠灵茶\"]},"
                + "{\"character_ref\":\"npc:9\",\"affinity_delta\":1,\"trust_delta\":1}]}";
        List<DialogueConsolidationService.ExtractedRelation> out =
                DialogueConsolidationService.parseRelations(json, Set.of("npc:3"));

        assertEquals(1, out.size());
        DialogueConsolidationService.ExtractedRelation r = out.get(0);
        assertEquals("npc:3", r.characterRef());
        assertEquals(10, r.affinityDelta());
        assertEquals(5, r.trustDelta());
        assertEquals(1, r.memoryPoints().size());
    }

    @Test
    void parseRelationsInvalidJsonYieldsEmpty() {
        assertTrue(DialogueConsolidationService.parseRelations("not json", Set.of("npc:1")).isEmpty());
        assertTrue(DialogueConsolidationService.parseRelations(null, Set.of("npc:1")).isEmpty());
        assertTrue(DialogueConsolidationService.parseRelations("{}", Set.of("npc:1")).isEmpty());
    }

    // ==================== apply：应用沉淀 ====================

    private PlayerRelationVO relationVO(int affinity, int trust, String stage, int version) {
        PlayerRelationVO vo = new PlayerRelationVO();
        vo.setProjectId(1L);
        vo.setCharacterRef("npc:1");
        vo.setAffinity(affinity);
        vo.setTrust(trust);
        vo.setStage(stage);
        vo.setVersion(version);
        return vo;
    }

    @Test
    void applyAppliesRelationMemoryAndVersion() {
        when(sessionService.require(1L, 7L)).thenReturn(singleSession());
        when(playerProfileService.applyConsolidatedRelation(eq(1L), eq(1L), eq("npc:1"),
                eq(5), eq(2), eq("熟络"), eq("备注"), eq("近况")))
                .thenReturn(relationVO(45, 37, "熟络", 2));

        DialogueConsolidationApplyDTO.CharacterCandidate c =
                new DialogueConsolidationApplyDTO.CharacterCandidate("npc:1", 5, 2, "熟络", "备注", "近况",
                        List.of("玩家答应修屋顶", "玩家来自外地"));
        DialogueConsolidationResultVO result = service.apply(1L, 1L, 7L, List.of(c));

        verify(playerProfileService).applyConsolidatedRelation(1L, 1L, "npc:1", 5, 2, "熟络", "备注", "近况");
        verify(agentMemoryService).recordObservation(1L, "npc:1", "与阿岚的对谈：玩家答应修屋顶", 3);
        verify(agentMemoryService).recordObservation(1L, "npc:1", "与阿岚的对谈：玩家来自外地", 3);
        verify(sessionService).markConsolidated(1L, 7L, 2);
        assertEquals(1, result.getCharacters().size());
        assertEquals(2, result.getVersion());
        assertEquals(2, result.getCharacters().get(0).getMemoriesWritten());
        assertEquals("沈守拙", result.getCharacters().get(0).getCharacterName());
    }

    @Test
    void applySkipsOutOfScopeCandidate() {
        when(sessionService.require(1L, 7L)).thenReturn(singleSession());
        DialogueConsolidationApplyDTO.CharacterCandidate c =
                new DialogueConsolidationApplyDTO.CharacterCandidate("npc:9", 5, 0, null, null, null, List.of());
        BizException e = assertThrows(BizException.class, () -> service.apply(1L, 1L, 7L, List.of(c)));
        assertTrue(e.getMessage().contains("没有可应用的对谈角色"));
        verify(playerProfileService, never()).applyConsolidatedRelation(any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void applyEmptyCandidatesRejected() {
        when(sessionService.require(1L, 7L)).thenReturn(singleSession());
        assertThrows(BizException.class, () -> service.apply(1L, 1L, 7L, List.of()));
        assertThrows(BizException.class, () -> service.apply(1L, 1L, 7L, null));
    }

    @Test
    void applyMemoryFailureSkipsPointStillApplies() {
        when(sessionService.require(1L, 7L)).thenReturn(singleSession());
        when(playerProfileService.applyConsolidatedRelation(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(relationVO(45, 37, "熟络", 2));
        // 第一条记忆要点写入失败 → 跳过继续；第二条成功
        doThrow(new RuntimeException("写库失败"))
                .doNothing()
                .when(agentMemoryService).recordObservation(any(), any(), any(), any());

        DialogueConsolidationApplyDTO.CharacterCandidate c =
                new DialogueConsolidationApplyDTO.CharacterCandidate("npc:1", 5, 2, "熟络", null, null,
                        List.of("要点一", "要点二"));
        DialogueConsolidationResultVO result = service.apply(1L, 1L, 7L, List.of(c));

        verify(agentMemoryService, times(2)).recordObservation(any(), any(), any(), any());
        assertEquals(1, result.getCharacters().get(0).getMemoriesWritten());
        assertEquals(2, result.getVersion());
        verify(sessionService).markConsolidated(1L, 7L, 2);
    }

    @Test
    void applyGroupAppliesEachMember() {
        when(sessionService.require(1L, 7L)).thenReturn(groupSession());
        when(groupService.members(1L, 7L))
                .thenReturn(List.of(member(1L, "npc:1", "沈守拙"), member(2L, "npc:2", "林听竹")));
        when(playerProfileService.applyConsolidatedRelation(eq(1L), eq(1L), eq("npc:1"), any(), any(), any(), any(), any()))
                .thenReturn(relationVO(45, 37, "熟络", 2));
        when(playerProfileService.applyConsolidatedRelation(eq(1L), eq(1L), eq("npc:2"), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    PlayerRelationVO vo = new PlayerRelationVO();
                    vo.setProjectId(1L);
                    vo.setCharacterRef("npc:2");
                    vo.setAffinity(21);
                    vo.setTrust(20);
                    vo.setStage("相识");
                    vo.setVersion(3);
                    return vo;
                });

        DialogueConsolidationApplyDTO.CharacterCandidate c1 =
                new DialogueConsolidationApplyDTO.CharacterCandidate("npc:1", 5, 2, "熟络", null, null, List.of());
        DialogueConsolidationApplyDTO.CharacterCandidate c2 =
                new DialogueConsolidationApplyDTO.CharacterCandidate("npc:2", 1, 0, "相识", null, null, List.of());
        DialogueConsolidationResultVO result = service.apply(1L, 1L, 7L, List.of(c1, c2));

        assertEquals(2, result.getCharacters().size());
        // 会话版本 = 本轮最高角色版本（npc:2 → 3）
        verify(sessionService).markConsolidated(1L, 7L, 3);
        assertEquals(3, result.getVersion());
    }
}
