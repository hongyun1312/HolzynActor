package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.agent.runtime.AiIntensityGate;
import com.holzyn.actor.domain.world.config.MacroTemplate;
import com.holzyn.actor.domain.world.config.MesoProperties;
import com.holzyn.actor.domain.world.entity.ActorMesoDirective;
import com.holzyn.actor.domain.world.entity.ActorMesoGroup;
import com.holzyn.actor.domain.world.entity.ActorMesoMembership;
import com.holzyn.actor.domain.world.entity.ActorMesoResponse;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import com.holzyn.actor.domain.world.repository.ActorMesoDirectiveRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoGroupRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoMembershipRepository;
import com.holzyn.actor.domain.world.repository.ActorMesoResponseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 中观协调服务单元测试（宏观社会演化系统 M6.5a，domain/world/service，同包）。
 * <p>职责：验证规则指令载荷（事件码×组织类型/标签 → 三通道）、响应分类（comply/partial/
 * defy/ignore）、宏观事件指令下发（severity 阈值）、例行指令、响应回收、手动建组/解散。</p>
 */
class MesoCoordinationServiceTest {

    private MesoProperties props;
    private ActorMesoGroupRepository groupRepository;
    private ActorMesoMembershipRepository membershipRepository;
    private ActorMesoDirectiveRepository directiveRepository;
    private ActorMesoResponseRepository responseRepository;
    private MacroStateService macroStateService;
    private AiIntensityGate aiIntensityGate;
    private WorldClockService worldClockService;
    private MesoGroupEngine mesoGroupEngine;
    private MesoCoordinationService service;

    @BeforeEach
    void setUp() {
        props = new MesoProperties();
        groupRepository = Mockito.mock(ActorMesoGroupRepository.class);
        membershipRepository = Mockito.mock(ActorMesoMembershipRepository.class);
        directiveRepository = Mockito.mock(ActorMesoDirectiveRepository.class);
        responseRepository = Mockito.mock(ActorMesoResponseRepository.class);
        macroStateService = Mockito.mock(MacroStateService.class);
        aiIntensityGate = Mockito.mock(AiIntensityGate.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        mesoGroupEngine = Mockito.mock(MesoGroupEngine.class);
        service = new MesoCoordinationService(props, mesoGroupEngine,
                new MesoDirectiveRouter(props, membershipRepository, groupRepository,
                        directiveRepository, new ObjectMapper()),
                groupRepository, membershipRepository,
                directiveRepository, responseRepository, macroStateService,
                Mockito.mock(MacroAiService.class), aiIntensityGate, worldClockService,
                new ObjectMapper());
        when(macroStateService.templateOf(anyLong())).thenReturn(
                MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID));
        when(directiveRepository.save(any(ActorMesoDirective.class))).thenAnswer(inv -> inv.getArgument(0));
        when(worldClockService.currentEngineGameSecond(anyLong())).thenReturn(86400L * 9L);
    }

    private ActorMesoGroup group(String name, String type, String tags) {
        ActorMesoGroup g = new ActorMesoGroup();
        g.setId(5L);
        g.setName(name);
        g.setType(type);
        g.setStatus("active");
        g.setProjectId(100L);
        g.setTagsJson(tags);
        return g;
    }

    /** 灾害事件 × 商贸组织 → 囤积倾向（核心资源取模板语义，不硬编码「粮」）。 */
    @Test
    void disasterEventTradeGroupHoards() {
        ActorMesoGroup g = group("往来商帮", "guild", "[\"商贸\"]");
        String payload = service.rulePayload("drought", g, MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID));
        assertTrue(payload.contains("囤积"), "灾害×商贸 → 囤积倾向");
        assertTrue(payload.contains("粮"), "核心资源=模板语义（默认仙侠=粮）");
        assertTrue(payload.contains("贱卖"), "约束含不贱卖");
    }

    /** 灾害 × 医馆 → 备药义诊；× 庙宇 → 祈禳；× 宗族 → 省俭；× 普通组 → 少买多存。 */
    @Test
    void disasterEventByGroupType() {
        MacroTemplate tpl = MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID);
        assertTrue(service.rulePayload("flood", group("行医世家", "guild", "[\"医馆\",\"诊治\"]"), tpl).contains("备药义诊"));
        assertTrue(service.rulePayload("blizzard", group("庙宇祭司一系", "cult", "[\"香火\"]"), tpl).contains("祈禳"));
        assertTrue(service.rulePayload("harvest_bad", group("村中宗族", "family", "[\"宗祠\"]"), tpl).contains("省着用度"));
        assertTrue(service.rulePayload("price_surge", group("乡邻结社", "community", "[]"), tpl).contains("少买多存"));
    }

    /** 民变风险 × 宗族 → 约束族人；× 普通组 → null（不接指令）。 */
    @Test
    void revoltRiskOnlyFamilyAndSect() {
        MacroTemplate tpl = MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID);
        assertTrue(service.rulePayload("revolt_risk", group("村中宗族", "family", "[]"), tpl).contains("约束族人"));
        assertTrue(service.rulePayload("revolt_risk", group("武林宗门", "sect", "[]"), tpl).contains("约束族人"));
        assertNull(service.rulePayload("revolt_risk", group("乡邻结社", "community", "[]"), tpl));
    }

    /** 瘟疫 × 医馆/商贸 → 备药防疫；其余 → null；丰年 × 商贸 → 多进多销；开仓 × 宗族/商贸。 */
    @Test
    void plagueAndHarvestAndReliefMapping() {
        MacroTemplate tpl = MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID);
        assertTrue(service.rulePayload("plague", group("行医世家", "guild", "[\"医馆\"]"), tpl).contains("备药防疫"));
        assertNull(service.rulePayload("plague", group("村中宗族", "family", "[]"), tpl));
        assertTrue(service.rulePayload("harvest_good", group("往来商帮", "guild", "[\"商贸\"]"), tpl).contains("多进多销"));
        assertNull(service.rulePayload("harvest_good", group("村中宗族", "family", "[]"), tpl));
        assertTrue(service.rulePayload("relief", group("村中宗族", "family", "[]"), tpl).contains("带头开仓"));
        assertNull(service.rulePayload("relief", group("游侠散人", "community", "[]"), tpl));
    }

    /** 技艺突破 × 门派/行会 → 精研技艺；未知事件码 → null（模板未覆盖事件安全兜底）。 */
    @Test
    void techBreakthroughAndUnknownEvent() {
        MacroTemplate tpl = MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID);
        assertTrue(service.rulePayload("tech_breakthrough", group("武林宗门", "sect", "[]"), tpl).contains("精研技艺"));
        assertTrue(service.rulePayload("tech_breakthrough", group("同业行会", "guild", "[]"), tpl).contains("精研技艺"));
        assertNull(service.rulePayload("unknown_event_code", group("村中宗族", "family", "[]"), tpl),
                "未覆盖事件码 → null=该组不接指令（不崩溃）");
        assertNull(service.rulePayload(null, group("村中宗族", "family", "[]"), tpl));
    }

    /** 响应分类：约束命中→defy；倾向 ≥2 token→comply；1→partial；0→ignore；非法→ignore。 */
    @Test
    void classifyResponseSemantics() {
        String payload = "{\"tendency\":{\"bias\":\"囤积粮食，谨慎出货\"},"
                + "\"constraint\":{\"forbidden\":[\"贱卖粮食\",\"开仓\"]}}";
        assertEquals("defy", MesoCoordinationService.classifyResponse(payload, "在集市贱卖粮食"));
        assertEquals("comply", MesoCoordinationService.classifyResponse(payload, "囤积粮食，谨慎出货"));
        // 仅命中倾向的一个 token（连续子串匹配）→ partial；"囤积一点粮食" 不连续包含 "囤积粮食"，故不算命中
        assertEquals("partial", MesoCoordinationService.classifyResponse(payload, "囤积粮食，余下观望"));
        assertEquals("ignore", MesoCoordinationService.classifyResponse(payload, "出门散步"));
        assertEquals("ignore", MesoCoordinationService.classifyResponse("{broken", "囤积粮食"));
        assertEquals("ignore", MesoCoordinationService.classifyResponse(null, "囤积粮食"));
        assertEquals("ignore", MesoCoordinationService.classifyResponse(payload, null));
    }

    /** 宏观事件指令下发：severity≥阈值 → 每组织一条规则指令（源=rule，directive_type=react）。 */
    @Test
    void onMacroEventIssuesDirectivesBySeverity() {
        when(groupRepository.findByProjectIdAndStatusOrderByIdAsc(100L, "active"))
                .thenReturn(List.of(group("往来商帮", "guild", "[\"商贸\"]")));
        when(aiIntensityGate.intensityOf(anyLong())).thenReturn(AiIntensityGate.PROGRAMMATIC);
        ActorWorldMacroEvent ev = new ActorWorldMacroEvent();
        ev.setId(42L);
        ev.setProjectId(100L);
        ev.setEventType("drought");
        ev.setSeverity(4);
        ev.setGameDay(9L);
        service.onMacroEvent(100L, ev, 9L * 86400L);
        ArgumentCaptor<ActorMesoDirective> cap = ArgumentCaptor.forClass(ActorMesoDirective.class);
        verify(directiveRepository).save(cap.capture());
        ActorMesoDirective d = cap.getValue();
        assertEquals("react", d.getDirectiveType());
        assertEquals("rule", d.getSource());
        assertEquals(5L, d.getGroupId());
        assertTrue(d.getPayloadJson().contains("囤积"));
    }

    /** severity 低于阈值 → 不下发；adhoc 自发群体不接事件指令。 */
    @Test
    void onMacroEventSkipsLowSeverityAndAdhoc() {
        when(groupRepository.findByProjectIdAndStatusOrderByIdAsc(100L, "active"))
                .thenReturn(List.of(group("囤积群体", "adhoc", "[\"囤积\"]")));
        when(aiIntensityGate.intensityOf(anyLong())).thenReturn(AiIntensityGate.PROGRAMMATIC);
        ActorWorldMacroEvent low = new ActorWorldMacroEvent();
        low.setSeverity(2);
        service.onMacroEvent(100L, low, 1L);
        ActorWorldMacroEvent high = new ActorWorldMacroEvent();
        high.setSeverity(4);
        high.setEventType("drought");
        service.onMacroEvent(100L, high, 1L);
        verify(directiveRepository, never()).save(any(ActorMesoDirective.class));
    }

    /** 例行指令：每组织一条 routine 指令（维持组织存在感）。 */
    @Test
    void routineDirectivesIssuedPerGroup() {
        when(groupRepository.findByProjectIdAndStatusOrderByIdAsc(100L, "active"))
                .thenReturn(List.of(group("村中宗族", "family", "[]"), group("往来商帮", "guild", "[]")));
        service.issueRoutineDirectives(100L, 9L * 86400L);
        verify(directiveRepository, Mockito.times(2)).save(any(ActorMesoDirective.class));
        var cap = ArgumentCaptor.forClass(ActorMesoDirective.class);
        verify(directiveRepository, Mockito.times(2)).save(cap.capture());
        assertTrue(cap.getAllValues().stream().allMatch(d -> "routine".equals(d.getDirectiveType())));
        // 开关关闭 → 不下发
        props.setRoutineEnabled(false);
        service.issueRoutineDirectives(100L, 9L * 86400L);
        verify(directiveRepository, Mockito.times(2)).save(any(ActorMesoDirective.class));
    }

    /** 响应回收：无有效指令 → 零落库；有指令 → 按行动判定类型落库。 */
    @Test
    void recordActionResponseClassifies() {
        when(membershipRepository.findByProjectIdAndActorRefAndStatus(100L, "special:1", "active"))
                .thenReturn(List.of(member(5L)));
        ActorMesoDirective d = new ActorMesoDirective();
        d.setId(7L);
        d.setPayloadJson("{\"tendency\":{\"bias\":\"囤积粮食，谨慎出货\"},"
                + "\"constraint\":{\"forbidden\":[\"贱卖粮食\"]}}");
        when(directiveRepository.findByProjectIdAndGroupIdAndStatusOrderByIdDesc(100L, 5L, "active"))
                .thenReturn(List.of(d));
        when(directiveRepository.findByProjectIdAndGroupIdIsNullAndStatusOrderByIdDesc(100L, "active"))
                .thenReturn(List.of());
        service.recordActionResponse(100L, "special:1", 1L, "在集市贱卖粮食", "trade", 9L * 86400L);
        ArgumentCaptor<ActorMesoResponse> cap = ArgumentCaptor.forClass(ActorMesoResponse.class);
        verify(responseRepository).save(cap.capture());
        assertEquals("defy", cap.getValue().getResponseType(), "约束命中 → 违抗");
        // 无指令 → 零回收
        when(directiveRepository.findByProjectIdAndGroupIdAndStatusOrderByIdDesc(100L, 5L, "active"))
                .thenReturn(List.of());
        service.recordActionResponse(100L, "special:1", 1L, "囤积粮食", "trade", 9L * 86400L);
        verify(responseRepository, Mockito.times(1)).save(any(ActorMesoResponse.class));
    }

    /** 手动建组（开关开）：组落库 + 首领成员（委托中观引擎）；开关关 → 业务异常。 */
    @Test
    void createGroupWithLeaderAndGate() {
        when(groupRepository.findByProjectIdAndName(100L, "临时行会")).thenReturn(Optional.empty());
        when(groupRepository.save(any(ActorMesoGroup.class))).thenAnswer(inv -> {
            ActorMesoGroup g = inv.getArgument(0);
            g.setId(77L);
            return g;
        });
        ActorMesoGroup g = service.createGroup(100L, "临时行会", "guild", "special:1",
                List.of("商贸"), 100L);
        assertEquals(77L, g.getId());
        assertEquals("manual", g.getOrigin());
        verify(mesoGroupEngine).addMembership(100L, 77L, "special:1", "leader", 100L);
        props.setManualGroupEnabled(false);
        assertThrows(com.holzyn.actor.common.BizException.class,
                () -> service.createGroup(100L, "另一行会", "guild", null, List.of(), 100L));
    }

    /** 解散组织：组/成员/指令软删。 */
    @Test
    void dissolveGroupSoftDeletes() {
        ActorMesoGroup g = group("临时行会", "guild", "[]");
        g.setId(77L);
        when(groupRepository.findById(77L)).thenReturn(Optional.of(g));
        when(membershipRepository.findByGroupIdOrderByIdAsc(77L)).thenReturn(List.of(member(77L)));
        when(directiveRepository.findByProjectIdAndGroupIdOrderByIdDesc(100L, 77L))
                .thenReturn(List.of(new ActorMesoDirective()));
        service.dissolveGroup(100L, 77L);
        assertEquals("dissolved", g.getStatus());
        verify(membershipRepository).save(org.mockito.ArgumentMatchers.argThat(m ->
                m instanceof ActorMesoMembership mm && "left".equals(mm.getStatus())));
        verify(directiveRepository).save(org.mockito.ArgumentMatchers.argThat(d ->
                d instanceof ActorMesoDirective dd && "expired".equals(dd.getStatus())));
        // 项目不匹配 → 404
        when(groupRepository.findById(78L)).thenReturn(Optional.of(group("别的组", "family", "[]")));
        assertThrows(com.holzyn.actor.common.BizException.class,
                () -> service.dissolveGroup(200L, 78L));
    }

    private ActorMesoMembership member(Long groupId) {
        ActorMesoMembership m = new ActorMesoMembership();
        m.setGroupId(groupId);
        m.setActorRef("special:1");
        m.setStatus("active");
        return m;
    }
}
