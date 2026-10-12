package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.entity.KnowledgeCandidate;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.knowledge.repository.KnowledgeCandidateRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆-知识桥接层（P1 ②-3）单元测试。
 * <p>覆盖：AI 输出解析（合法/垃圾/空；is_knowledge 缺省 false；重要度 clamp）；规则确认
 * （内容长度/非知识拒绝）；去重（历史候选/沉淀文档/本次已写入）；「经历沉淀」文档幂等
 * upsert（新建/追加/重复跳过）；全链路（AI 判定 → 自动确认 → 落文档 → 候选留档）；
 * fail-open（未装配/AI 失败 → 零统计零阻断）。</p>
 * <p>装配：仓库 mock 直接 new 构造（构造器=仓库+候选仓库+ObjectMapper）；可选服务字段
 * {@code ReflectionTestUtils.setField} 注入。</p>
 */
class KnowledgeBridgeServiceTest {

    private static final long PROJECT_ID = 100L;
    private static final String CHAR_REF = "npc:7";

    private final ActorKnowledgeDocRepository docRepo = mock(ActorKnowledgeDocRepository.class);
    private final KnowledgeCandidateRepository candidateRepo = mock(KnowledgeCandidateRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final KnowledgeBridgeService service = new KnowledgeBridgeService(docRepo, candidateRepo, mapper);

    // ==================== AI 输出解析 ====================

    @Test
    void parseCandidatesValidJson() {
        String json = """
                {"knowledge_candidates":[
                  {"content":"沈氏宗族控制着镇上的粮市","importance":4,"is_knowledge":true},
                  {"content":"集市每逢初一休市","importance":3,"is_knowledge":true},
                  {"content":"今天心情不好","importance":2,"is_knowledge":false}
                ]}""";

        List<KnowledgeBridgeService.CandidateDraft> out = KnowledgeBridgeService.parseCandidates(json);

        assertEquals(3, out.size());
        assertTrue(out.get(0).isKnowledge());
        assertEquals(4, out.get(0).importance());
        assertFalse(out.get(2).isKnowledge(), "is_knowledge=false 保留（供拒绝留档）");
    }

    /** 重要度越界 clamp 1~5；is_knowledge 缺省 false（宁缺毋滥）。 */
    @Test
    void parseCandidatesClampsAndDefaults() {
        String json = """
                {"knowledge_candidates":[
                  {"content":"A","importance":99,"is_knowledge":true},
                  {"content":"B","importance":-3,"is_knowledge":true},
                  {"content":"C"}
                ]}""";

        List<KnowledgeBridgeService.CandidateDraft> out = KnowledgeBridgeService.parseCandidates(json);

        assertEquals(3, out.size());
        assertEquals(5, out.get(0).importance());
        assertEquals(1, out.get(1).importance());
        assertFalse(out.get(2).isKnowledge(), "缺省 is_knowledge=false");
    }

    /** 垃圾/空输出 → 空列表（fail-open）。 */
    @Test
    void parseCandidatesGarbageReturnsEmpty() {
        assertTrue(KnowledgeBridgeService.parseCandidates(null).isEmpty());
        assertTrue(KnowledgeBridgeService.parseCandidates("").isEmpty());
        assertTrue(KnowledgeBridgeService.parseCandidates("今天天气不错").isEmpty());
        assertTrue(KnowledgeBridgeService.parseCandidates("{not-json").isEmpty());
        assertTrue(KnowledgeBridgeService.parseCandidates("[]").isEmpty());
    }

    // ==================== 规则确认 ====================

    @Test
    void acceptRuleRejectsInvalid() {
        assertTrue(KnowledgeBridgeService.accept(
                new KnowledgeBridgeService.CandidateDraft("沈氏宗族控制着镇上的粮市", 3, true)));
        assertFalse(KnowledgeBridgeService.accept(null), "null 拒绝");
        assertFalse(KnowledgeBridgeService.accept(
                new KnowledgeBridgeService.CandidateDraft("沈氏宗族控制粮市", 3, false)), "非知识拒绝");
        assertFalse(KnowledgeBridgeService.accept(
                new KnowledgeBridgeService.CandidateDraft("短", 3, true)), "过短拒绝");
        assertFalse(KnowledgeBridgeService.accept(
                new KnowledgeBridgeService.CandidateDraft("长".repeat(301), 3, true)), "过长拒绝");
        assertFalse(KnowledgeBridgeService.accept(
                new KnowledgeBridgeService.CandidateDraft("   ", 3, true)), "空白拒绝");
    }

    // ==================== 去重 ====================

    /** 与历史已确认候选重复 → duplicate。 */
    @Test
    void isDuplicateAgainstHistory() {
        KnowledgeCandidate kc = new KnowledgeCandidate();
        kc.setContent("沈氏宗族控制着镇上的粮市");
        when(candidateRepo.findByProjectIdAndCharacterRefAndStatus(PROJECT_ID, CHAR_REF,
                KnowledgeCandidate.STATUS_PROMOTED)).thenReturn(List.of(kc));

        assertTrue(service.isDuplicate(PROJECT_ID, CHAR_REF, List.of(), "沈氏宗族控制着镇上的粮市"),
                "与历史候选重复");
        assertFalse(service.isDuplicate(PROJECT_ID, CHAR_REF, List.of(), "集市每逢初一休市"), "新事实不重复");
    }

    /** 与本次已写入事实重复（进程内去重）。 */
    @Test
    void isDuplicateAgainstThisRun() {
        assertTrue(service.isDuplicate(PROJECT_ID, CHAR_REF, List.of("粮价近期上涨三成"), "粮价近期上涨三成"));
    }

    /** 与既有「经历沉淀」文档内容重复。 */
    @Test
    void isDuplicateAgainstSettlementDoc() {
        ActorKnowledgeDoc doc = new ActorKnowledgeDoc();
        doc.setTitle(KnowledgeBridgeService.TITLE_SETTLEMENT);
        doc.setCharacterId(7L);
        doc.setContent("【经历沉淀】以下为该角色沉淀知识。\n- [重要事实] 李掌柜对欠账格外宽容");
        when(docRepo.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of(doc));

        assertTrue(service.isDuplicate(PROJECT_ID, CHAR_REF, List.of(), "李掌柜对欠账格外宽容"),
                "与沉淀文档内容重复");
    }

    // ==================== 沉淀文档幂等 upsert ====================

    /** 文档不存在 → 新建（引导句 + 首条事实，hot + 置信度 0.6）。 */
    @Test
    void appendSettlementDocCreatesNew() {
        when(docRepo.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of());

        int n = service.appendSettlementDoc(PROJECT_ID, 7L, "沈氏宗族控制着镇上的粮市");

        assertEquals(1, n);
        ArgumentCaptor<ActorKnowledgeDoc> cap = ArgumentCaptor.forClass(ActorKnowledgeDoc.class);
        verify(docRepo).save(cap.capture());
        ActorKnowledgeDoc saved = cap.getValue();
        assertEquals(KnowledgeBridgeService.TITLE_SETTLEMENT, saved.getTitle());
        assertEquals(7L, saved.getCharacterId());
        assertEquals(KnowledgeLifecycleService.STATE_HOT, saved.getLifecycleState());
        assertEquals(0.6, saved.getConfidence(), 1e-9);
        assertTrue(saved.getContent().contains("沈氏宗族控制着镇上的粮市"), "含首条事实");
    }

    /** 文档存在且内容未含 → 追加 + 置信度提升（幂等：不重复建文档）。 */
    @Test
    void appendSettlementDocAppendsExisting() {
        ActorKnowledgeDoc doc = new ActorKnowledgeDoc();
        doc.setId(9L);
        doc.setTitle(KnowledgeBridgeService.TITLE_SETTLEMENT);
        doc.setCharacterId(7L);
        doc.setContent(KnowledgeBridgeService.ANCHOR_SETTLEMENT + "\n- [重要事实] 老事实");
        doc.setConfidence(0.5);
        when(docRepo.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of(doc));

        int n = service.appendSettlementDoc(PROJECT_ID, 7L, "集市每逢初一休市");

        assertEquals(1, n);
        assertTrue(doc.getContent().contains("集市每逢初一休市"), "新事实追加");
        assertEquals(0.6, doc.getConfidence(), 1e-9, "置信度提升");
        verify(docRepo).save(doc);
    }

    /** 内容已含 → 0（重复，由调用方按 duplicate 处理）。 */
    @Test
    void appendSettlementDocDuplicateReturnsZero() {
        ActorKnowledgeDoc doc = new ActorKnowledgeDoc();
        doc.setId(9L);
        doc.setTitle(KnowledgeBridgeService.TITLE_SETTLEMENT);
        doc.setCharacterId(7L);
        doc.setContent(KnowledgeBridgeService.ANCHOR_SETTLEMENT + "\n- [重要事实] 老事实");
        when(docRepo.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of(doc));

        assertEquals(0, service.appendSettlementDoc(PROJECT_ID, 7L, "老事实"));
        verify(docRepo, never()).save(any());
    }

    /** 参数无效 → 0（零阻断）。 */
    @Test
    void appendSettlementDocInvalidReturnsZero() {
        assertEquals(0, service.appendSettlementDoc(null, 7L, "事实"));
        assertEquals(0, service.appendSettlementDoc(PROJECT_ID, null, "事实"));
        assertEquals(0, service.appendSettlementDoc(PROJECT_ID, 7L, "  "));
    }

    // ==================== 全链路（AI 判定 → 确认 → 落文档 → 候选留档） ====================

    /** 未装配（AI 网关/模板缺失）→ 零统计 fail-open。 */
    @Test
    void extractAndPromoteUnwiredReturnsEmpty() {
        KnowledgeBridgeService.BridgeResult r =
                service.extractAndPromote(PROJECT_ID, CHAR_REF, 1L, "角色档案", List.of("洞察一"));

        assertEquals(0, r.promoted());
        assertEquals(0, r.duplicate());
        assertEquals(0, r.rejected());
    }

    /** AI 抛异常 → 零统计 fail-open（不阻断反思链）。 */
    @Test
    void extractAndPromoteAiFailureIsFailOpen() {
        PromptTemplateService pts = mock(PromptTemplateService.class);
        AiSpringChatGateway gateway = mock(AiSpringChatGateway.class);
        UsageLogService usage = mock(UsageLogService.class);
        ReflectionTestUtils.setField(service, "promptTemplateService", pts);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usage);
        when(pts.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        when(pts.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("system");
        when(gateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenThrow(new RuntimeException("网关超时"));

        KnowledgeBridgeService.BridgeResult r =
                service.extractAndPromote(PROJECT_ID, CHAR_REF, 1L, "角色档案", List.of("洞察一"));

        assertEquals(0, r.promoted());
        assertEquals(0, r.duplicate());
        assertEquals(0, r.rejected());
    }

    /** 全链路：AI 判定 2 条知识 + 1 条非知识 → promoted=2、rejected=1、沉淀文档写入、候选留档。 */
    @Test
    void extractAndPromoteFullFlow() {
        PromptTemplateService pts = mock(PromptTemplateService.class);
        AiSpringChatGateway gateway = mock(AiSpringChatGateway.class);
        UsageLogService usage = mock(UsageLogService.class);
        ReflectionTestUtils.setField(service, "promptTemplateService", pts);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usage);
        when(pts.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        when(pts.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("system");
        when(gateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("""
                        {"knowledge_candidates":[
                          {"content":"沈氏宗族控制着镇上的粮市","importance":4,"is_knowledge":true},
                          {"content":"集市每逢初一休市","importance":3,"is_knowledge":true},
                          {"content":"今天有点烦躁","importance":2,"is_knowledge":false}
                        ]}""", "test-model", 100, 50, 150, 1L));
        when(docRepo.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of());
        when(candidateRepo.findByProjectIdAndCharacterRefAndStatus(PROJECT_ID, CHAR_REF,
                KnowledgeCandidate.STATUS_PROMOTED)).thenReturn(List.of());

        KnowledgeBridgeService.BridgeResult r =
                service.extractAndPromote(PROJECT_ID, CHAR_REF, 1L, "角色档案", List.of("洞察一", "洞察二"));

        assertEquals(2, r.promoted(), "2 条知识确认写入");
        assertEquals(0, r.duplicate());
        assertEquals(1, r.rejected(), "1 条非知识拒绝");
        // 沉淀文档：2 条知识 → 2 次 save（首条新建 + 次条追加）
        verify(docRepo, times(2)).save(any(ActorKnowledgeDoc.class));
        // 候选留档：3 条（2 promoted + 1 rejected）
        ArgumentCaptor<KnowledgeCandidate> cap = ArgumentCaptor.forClass(KnowledgeCandidate.class);
        verify(candidateRepo, times(3)).save(cap.capture());
        long promoted = cap.getAllValues().stream()
                .filter(kc -> KnowledgeCandidate.STATUS_PROMOTED.equals(kc.getStatus())).count();
        long rejected = cap.getAllValues().stream()
                .filter(kc -> KnowledgeCandidate.STATUS_REJECTED.equals(kc.getStatus())).count();
        assertEquals(2, promoted, "2 条 promoted 留档");
        assertEquals(1, rejected, "1 条 rejected 留档");
        assertEquals("npc:7", cap.getAllValues().get(0).getCharacterRef());
        assertNotNull(cap.getAllValues().get(0).getConfirmedAt(), "确认时间留档");
    }

    /** 全链路：AI 判定与历史重复 → duplicate 留档、不写文档。 */
    @Test
    void extractAndPromoteDuplicateFlow() {
        PromptTemplateService pts = mock(PromptTemplateService.class);
        AiSpringChatGateway gateway = mock(AiSpringChatGateway.class);
        UsageLogService usage = mock(UsageLogService.class);
        ReflectionTestUtils.setField(service, "promptTemplateService", pts);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usage);
        when(pts.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        when(pts.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("system");
        when(gateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult("""
                        {"knowledge_candidates":[
                          {"content":"沈氏宗族控制着镇上的粮市","importance":4,"is_knowledge":true}
                        ]}""", "test-model", 100, 50, 150, 1L));
        // 历史已确认过同一事实
        KnowledgeCandidate kc = new KnowledgeCandidate();
        kc.setContent("沈氏宗族控制着镇上的粮市");
        when(candidateRepo.findByProjectIdAndCharacterRefAndStatus(PROJECT_ID, CHAR_REF,
                KnowledgeCandidate.STATUS_PROMOTED)).thenReturn(List.of(kc));

        KnowledgeBridgeService.BridgeResult r =
                service.extractAndPromote(PROJECT_ID, CHAR_REF, 1L, "角色档案", List.of("洞察一"));

        assertEquals(0, r.promoted());
        assertEquals(1, r.duplicate());
        verify(docRepo, never()).save(any(ActorKnowledgeDoc.class));
        ArgumentCaptor<KnowledgeCandidate> cap = ArgumentCaptor.forClass(KnowledgeCandidate.class);
        verify(candidateRepo).save(cap.capture());
        assertEquals(KnowledgeCandidate.STATUS_DUPLICATE, cap.getValue().getStatus(), "duplicate 留档");
    }

    /** 空洞察/开关关 → 零操作。 */
    @Test
    void extractAndPromoteNoInputs() {
        KnowledgeBridgeService.BridgeResult r1 =
                service.extractAndPromote(PROJECT_ID, CHAR_REF, 1L, null, List.of());
        assertEquals(0, r1.promoted());

        ReflectionTestUtils.setField(service, "enabled", false);
        KnowledgeBridgeService.BridgeResult r2 =
                service.extractAndPromote(PROJECT_ID, CHAR_REF, 1L, null, List.of("洞察"));
        assertEquals(0, r2.promoted());
        verify(candidateRepo, never()).save(any());
    }

    // ==================== 批6 收口：AI 判定请求前置记忆链段位（段1A+1C+2D+2E） ====================

    /** knowledge_bridge AI 判定：请求前置 memoryChainContext；原始提示保留在后。 */
    @Test
    void aiJudgeRequestPrependsMemoryChainContext() {
        PromptTemplateService pts = mock(PromptTemplateService.class);
        AiSpringChatGateway gateway = mock(AiSpringChatGateway.class);
        UsageLogService usage = mock(UsageLogService.class);
        ReflectionTestUtils.setField(service, "promptTemplateService", pts);
        ReflectionTestUtils.setField(service, "aiChatGateway", gateway);
        ReflectionTestUtils.setField(service, "usageLogService", usage);
        AiContextInjector injector = mock(AiContextInjector.class);
        when(injector.memoryChainContext(eq(PROJECT_ID), eq(CHAR_REF), any())).thenReturn("【世界观】青禾村【我的身份与处境】族长");
        ReflectionTestUtils.setField(service, "aiContextInjector", injector);
        when(pts.render(anyLong(), anyLong(), anyString(), any())).thenReturn("prompt");
        when(pts.systemMessage(anyLong(), anyLong(), anyString(), any())).thenReturn("system");
        when(gateway.chatCompletion(anyLong(), anyLong(), any(), any(AiChatRequest.class)))
                .thenReturn(new AiChatResult("{\"knowledge_candidates\":[]}", "test-model", 100, 50, 150, 1L));
        when(candidateRepo.findByProjectIdAndCharacterRefAndStatus(PROJECT_ID, CHAR_REF,
                KnowledgeCandidate.STATUS_PROMOTED)).thenReturn(List.of());
        when(docRepo.findByProjectIdOrderByIdAsc(PROJECT_ID)).thenReturn(List.of());

        service.extractAndPromote(PROJECT_ID, CHAR_REF, 1L, "角色档案", List.of("洞察一"));

        ArgumentCaptor<AiChatRequest> cap = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(gateway).chatCompletion(anyLong(), anyLong(), any(), cap.capture());
        String user = cap.getValue().messages().get(1).content();
        assertTrue(user.startsWith("【世界观】青禾村"), "前置记忆链段位 1A：" + user);
        assertTrue(user.endsWith("\n\nprompt"), "原始提示保留在后：" + user);
    }
}
