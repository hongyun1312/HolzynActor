package com.holzyn.actor.domain.dialogue.service;

import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.conversation.service.ConversationContextService;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueMessage;
import com.holzyn.actor.domain.dialogue.entity.ActorDialogueSession;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对谈上下文装配器单元测试（纯文本组装，零 AI）。
 * <p>职责：验证——L0 稳定前缀段位（角色锚定+护栏/世界观/身份处境/关系段）、L1 动态段
 * （冻结时刻/相关记忆/对话历史/本轮任务）、缺数据整段跳过 fail-open、无装配器降级。</p>
 */
class DialogueContextAssemblerTest {

    private AiContextInjector injector;
    private DialogueRelationService relationService;
    private ConversationContextService conversationContextService;
    private ActorCharacterRepository characterRepository;
    private DialogueContextAssembler assembler;

    @BeforeEach
    void setUp() {
        injector = mock(AiContextInjector.class);
        relationService = mock(DialogueRelationService.class);
        conversationContextService = mock(ConversationContextService.class);
        characterRepository = mock(ActorCharacterRepository.class);
        assembler = new DialogueContextAssembler();
        ReflectionTestUtils.setField(assembler, "injector", injector);
        ReflectionTestUtils.setField(assembler, "relationService", relationService);
        ReflectionTestUtils.setField(assembler, "conversationContextService", conversationContextService);
        ReflectionTestUtils.setField(assembler, "characterRepository", characterRepository);

        ActorCharacter ch = new ActorCharacter();
        ch.setId(1L);
        ch.setName("沈守拙");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(ch));
    }

    private ActorDialogueSession session() {
        ActorDialogueSession s = new ActorDialogueSession();
        s.setProjectId(1L);
        s.setCharacterRef("npc:1");
        s.setFrozenSnapshotJson("{\"game_second\":13044175200,"
                + "\"world_dynamics\":\"青禾村历 0420-05-15 06时00分 · 初夏季 · 天气晴\","
                + "\"character_location\":\"村东水田\",\"character_mood\":\"平静\"}");
        return s;
    }

    private ActorDialogueMessage msg(String role, String content) {
        ActorDialogueMessage m = new ActorDialogueMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    void stableSystemContainsL0Segments() {
        when(injector.worldBrief(1L)).thenReturn("这是世界观");
        when(injector.characterCard(1L, "npc:1")).thenReturn("这是角色卡");
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        when(relationService.relationSegmentOf(1L, "npc:1", "阿岚")).thenReturn("【与阿岚的关系】好感 60");
        String system = assembler.stableSystem(1L, "npc:1", "沈守拙", "阿岚");
        assertTrue(system.contains("沈守拙"));
        assertTrue(system.contains("【世界观】"));
        assertTrue(system.contains("这是世界观"));
        assertTrue(system.contains("【我的身份与处境】"));
        assertTrue(system.contains("这是角色卡"));
        assertTrue(system.contains("【与阿岚的关系】"));
    }

    @Test
    void stableSystemSkipsEmptySegments() {
        when(injector.worldBrief(1L)).thenReturn("");
        when(injector.characterCard(1L, "npc:1")).thenReturn("");
        when(relationService.relationSegmentOf(1L, "npc:1", "阿岚")).thenReturn("");
        String system = assembler.stableSystem(1L, "npc:1", "沈守拙", "阿岚");
        // 只有角色锚定段，世界观/身份/关系段整段跳过
        assertTrue(system.contains("沈守拙"));
        assertFalse(system.contains("【世界观】"));
        assertFalse(system.contains("【我的身份与处境】"));
    }

    @Test
    void stableSystemContainsPlayerPersonaSegment() {
        when(injector.worldBrief(1L)).thenReturn("这是世界观");
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        when(relationService.playerPersonaSegmentOf(1L, "阿岚")).thenReturn("【关于阿岚】身份：旅行编辑，说话风格：慢条斯理");
        String system = assembler.stableSystem(1L, "npc:1", "沈守拙", "阿岚");
        assertTrue(system.contains("【关于阿岚】"));
        assertTrue(system.contains("身份：旅行编辑"));
        assertTrue(system.contains("说话风格：慢条斯理"));
    }

    @Test
    void stableSystemSkipsEmptyPlayerPersona() {
        when(relationService.playerPersonaSegmentOf(1L, "阿岚")).thenReturn("");
        String system = assembler.stableSystem(1L, "npc:1", "沈守拙", "阿岚");
        assertFalse(system.contains("【关于阿岚】"));
    }

    @Test
    void stableSystemEmptyWithoutInjector() {
        ReflectionTestUtils.setField(assembler, "injector", null);
        ReflectionTestUtils.setField(assembler, "relationService", null);
        ReflectionTestUtils.setField(assembler, "characterRepository", null);
        String system = assembler.stableSystem(1L, "npc:1", "沈守拙", "阿岚");
        assertTrue(system.contains("沈守拙"));
    }

    @Test
    void composeSingleBuildsL1Segments() {
        when(injector.worldBrief(1L)).thenReturn("世界观");
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        when(conversationContextService.buildContextByRef(anyLong(), anyLong(), anyString(), anyString(), anyList()))
                .thenReturn("【长期记忆】相关记忆");
        List<ActorDialogueMessage> history = List.of(msg("user", "你好"), msg("assistant", "你好呀"));
        DialogueContextAssembler.ComposedContext ctx = assembler.composeSingle(1L, 1L, session(), "今天天气不错", history);
        assertTrue(ctx.system().contains("【世界观】"));
        assertTrue(ctx.user().contains("【冻结时刻】"));
        assertTrue(ctx.user().contains("青禾村历"));
        assertTrue(ctx.user().contains("【长期记忆】相关记忆"));
        assertTrue(ctx.user().contains("【对话历史】"));
        assertTrue(ctx.user().contains("阿岚：你好"));
        assertTrue(ctx.user().contains("沈守拙：你好呀"));
        assertTrue(ctx.user().contains("【本轮】"));
        assertTrue(ctx.user().contains("今天天气不错"));
    }

    @Test
    void frozenContextParsesSnapshot() {
        DialogueContextAssembler.ComposedContext ctx = assembler.composeSingle(1L, 1L, session(), "在吗", List.of());
        assertTrue(ctx.user().contains("【冻结时刻】"));
        assertTrue(ctx.user().contains("青禾村历 0420-05-15"));
        assertTrue(ctx.user().contains("你在村东水田"));
        assertTrue(ctx.user().contains("心情平静"));
    }

    @Test
    void frozenContextFallsBackToLiveWhenSnapshotMissing() {
        ActorDialogueSession s = session();
        s.setFrozenSnapshotJson(null);
        when(injector.worldDynamicsLine(1L)).thenReturn("青禾村历 0420-05-16 · 初夏季 · 天气晴");
        DialogueContextAssembler.ComposedContext ctx = assembler.composeSingle(1L, 1L, s, "在吗", List.of());
        assertTrue(ctx.user().contains("【冻结时刻】"));
        assertTrue(ctx.user().contains("0420-05-16"));
    }

    @Test
    void memoryContextMissingServiceSkips() {
        ReflectionTestUtils.setField(assembler, "conversationContextService", null);
        DialogueContextAssembler.ComposedContext ctx = assembler.composeSingle(1L, 1L, session(), "在吗", List.of());
        assertFalse(ctx.user().contains("【长期记忆】"));
        // 其余段仍在
        assertTrue(ctx.user().contains("【冻结时刻】"));
        assertTrue(ctx.user().contains("【本轮】"));
    }

    @Test
    void historyExcludesBlankAndLabelsSpeakers() {
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        List<ActorDialogueMessage> history = List.of(msg("user", "  "), msg("user", "第一条"), msg("assistant", ""));
        DialogueContextAssembler.ComposedContext ctx = assembler.composeSingle(1L, 1L, session(), "现在", history);
        assertTrue(ctx.user().contains("【对话历史】"));
        assertTrue(ctx.user().contains("阿岚：第一条"));
        // 空白行被剔除：历史段中「阿岚：」只出现一次（仅第一条实质消息）
        assertEquals(1, countOccurrences(ctx.user(), "阿岚："));
    }

    @Test
    void historyLongLineIsClipped() {
        // 第 61 轮冒烟抓修·上下文压缩：超长单条历史裁剪到 160 字并标注省略（不再全文注入）
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        String longReply = "甲乙".repeat(180); // 360 字，远超 160 上限（尾部「乙」可区分）
        List<ActorDialogueMessage> history = List.of(msg("user", "你好"), msg("assistant", longReply));
        DialogueContextAssembler.ComposedContext ctx = assembler.composeSingle(1L, 1L, session(), "现在", history);
        String hist = ctx.user();
        assertTrue(hist.contains("（前文过长已略）"), "超长历史应被裁剪并标注省略");
        assertTrue(hist.contains("甲乙".repeat(80)), "裁剪后保留前 160 字");
        assertFalse(hist.contains("甲乙".repeat(160)), "超长尾部不应整体注入");
    }

    /** 统计子串出现次数（断言辅助）。 */
    private static int countOccurrences(String s, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void greetingPromptContainsSceneNotMemory() {
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        String prompt = assembler.greetingPrompt(1L, session(), "沈守拙", "阿岚");
        assertTrue(prompt.contains("【冻结时刻】"));
        assertTrue(prompt.contains("【场景】"));
        assertTrue(prompt.contains("开场白"));
        assertFalse(prompt.contains("【对话历史】"));
        assertFalse(prompt.contains("【本轮】"));
    }

    // ==================== M4：世界书 / 摘要 / 作者注释 ====================

    @Test
    void worldbookSegmentInjectedWhenKeywordHits() {
        DialogueWorldbookService wb = mock(DialogueWorldbookService.class);
        ReflectionTestUtils.setField(assembler, "worldbookService", wb);
        ActorDialogueSession s = session();
        s.setId(7L);
        when(wb.worldbookContext(eq(7L), anyList(), any()))
                .thenReturn("【世界书片段】\n- 〔关键词「灵脉」〕灵脉是青禾村的灵气源头");
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        DialogueContextAssembler.ComposedContext ctx =
                assembler.composeSingle(1L, 1L, s, "灵脉在哪", List.of(msg("user", "灵脉在哪")));
        assertTrue(ctx.user().contains("【世界书片段】"));
        assertTrue(ctx.user().contains("〔关键词「灵脉」〕"));
    }

    @Test
    void worldbookSkippedWhenNoHitsOrServiceNull() {
        ActorDialogueSession s = session();
        s.setId(7L);
        DialogueContextAssembler.ComposedContext ctx =
                assembler.composeSingle(1L, 1L, s, "灵脉在哪", List.of(msg("user", "灵脉在哪")));
        assertFalse(ctx.user().contains("【世界书片段】"));
    }

    @Test
    void historyPrependsSummaryWhenPresent() {
        ActorDialogueSession s = session();
        s.setSummary("前情摘要：去过村东水田，答应帮忙收麦");
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        List<ActorDialogueMessage> history = List.of(msg("user", "你好"), msg("assistant", "早"));
        DialogueContextAssembler.ComposedContext ctx = assembler.composeSingle(1L, 1L, s, "现在", history);
        assertTrue(ctx.user().contains("【对话摘要】前情摘要：去过村东水田，答应帮忙收麦"));
        assertTrue(ctx.user().contains("阿岚：你好"));
    }

    @Test
    void authorNoteDepth0PrependsToSystem() {
        ActorDialogueSession s = session();
        s.setAuthorNote("本次对话氛围偏轻松");
        s.setAuthorNoteDepth(0);
        s.setAuthorNoteFrequency(1);
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        DialogueContextAssembler.ComposedContext ctx = assembler.composeSingle(1L, 1L, s, "嗨", List.of());
        assertTrue(ctx.system().startsWith("【作者注释】本次对话氛围偏轻松"));
        assertTrue(ctx.system().contains("沈守拙"));
    }

    @Test
    void authorNoteDepth1AppendsToSystem() {
        ActorDialogueSession s = session();
        s.setAuthorNote("保持古风说话方式");
        s.setAuthorNoteDepth(1);
        s.setAuthorNoteFrequency(1);
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        DialogueContextAssembler.ComposedContext ctx = assembler.composeSingle(1L, 1L, s, "嗨", List.of());
        assertTrue(ctx.system().endsWith("【作者注释】保持古风说话方式"));
    }

    @Test
    void authorNoteDepth2PrependsToUser() {
        ActorDialogueSession s = session();
        s.setAuthorNote("引导走向离别话题");
        s.setAuthorNoteDepth(2);
        s.setAuthorNoteFrequency(1);
        DialogueContextAssembler.ComposedContext ctx = assembler.composeSingle(1L, 1L, s, "嗨", List.of());
        assertTrue(ctx.user().startsWith("【作者注释】引导走向离别话题"));
        assertFalse(ctx.system().contains("【作者注释】"));
    }

    @Test
    void authorNoteFrequencyGateControlsInjection() {
        ActorDialogueSession s = session();
        s.setAuthorNote("每三条注入一次");
        s.setAuthorNoteDepth(1);
        s.setAuthorNoteFrequency(3);
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        // 2 条历史：2%3=2 → 不注入
        DialogueContextAssembler.ComposedContext ctx = assembler.composeSingle(1L, 1L, s, "嗨",
                List.of(msg("user", "a"), msg("assistant", "b")));
        assertFalse(ctx.system().contains("【作者注释】"));
        // 3 条历史：3%3=0 → 注入
        DialogueContextAssembler.ComposedContext ctx2 = assembler.composeSingle(1L, 1L, s, "嗨",
                List.of(msg("user", "a"), msg("assistant", "b"), msg("user", "c")));
        assertTrue(ctx2.system().contains("【作者注释】每三条注入一次"));
    }

    @Test
    void authorNoteBlankSkipsEverywhere() {
        ActorDialogueSession s = session();
        s.setAuthorNote("   ");
        s.setAuthorNoteFrequency(1);
        DialogueContextAssembler.ComposedContext ctx = assembler.composeSingle(1L, 1L, s, "嗨", List.of());
        assertFalse(ctx.system().contains("【作者注释】"));
        assertFalse(ctx.user().contains("【作者注释】"));
    }

    @Test
    void authorNoteSegmentRespectsFrequencyAndDefaults() {
        ActorDialogueSession s = session();
        s.setAuthorNote("注");
        s.setAuthorNoteFrequency(2);
        assertEquals("【作者注释】注", assembler.authorNoteSegment(s, 4));
        assertEquals("", assembler.authorNoteSegment(s, 3));
        // null/<=0 = 恒注入
        s.setAuthorNoteFrequency(null);
        assertEquals("【作者注释】注", assembler.authorNoteSegment(s, 0));
        s.setAuthorNoteFrequency(0);
        assertEquals("【作者注释】注", assembler.authorNoteSegment(s, 5));
    }

    @Test
    void withSystemAuthorNotePlacesByDepth() {
        ActorDialogueSession s = session();
        s.setAuthorNote("注");
        s.setAuthorNoteDepth(0);
        s.setAuthorNoteFrequency(1);
        assertEquals("【作者注释】注\n\n原system", assembler.withSystemAuthorNote("原system", s, 0));
        s.setAuthorNoteDepth(1);
        assertEquals("原system\n\n【作者注释】注", assembler.withSystemAuthorNote("原system", s, 0));
        // depth 2 不入 system（调用方并入 user）
        s.setAuthorNoteDepth(2);
        assertEquals("原system", assembler.withSystemAuthorNote("原system", s, 0));
        // 无作者注释 → 原样
        s.setAuthorNote(" ");
        assertEquals("原system", assembler.withSystemAuthorNote("原system", s, 0));
    }

    // ==================== M5：群聊上下文（composeGroup） ====================

    private ActorDialogueSession groupSession() {
        ActorDialogueSession s = new ActorDialogueSession();
        s.setId(7L);
        s.setProjectId(1L);
        s.setType("group");
        s.setLocationId(10L);
        s.setLocationName("村口");
        s.setFrozenSnapshotJson("{\"world_dynamics\":\"青禾村历 0420-05-15 06时00分 · 初夏季 · 天气晴\"}");
        return s;
    }

    @Test
    void composeGroupBuildsSceneCounterpartHistoryAndTask() {
        ActorCharacter ch2 = new ActorCharacter();
        ch2.setId(2L);
        ch2.setName("林听竹");
        when(characterRepository.findById(2L)).thenReturn(Optional.of(ch2));
        when(injector.worldBrief(1L)).thenReturn("这是世界观");
        when(relationService.playerNameOf(1L)).thenReturn("阿岚");
        List<ActorDialogueMessage> history = List.of(
                msg("group", "诸位今日气色不错"),
                msg("group", "风大，坐屋里说话罢"));
        history.get(0).setCharacterRef("npc:2");
        history.get(1).setCharacterRef("npc:1");

        DialogueContextAssembler.ComposedContext ctx = assembler.composeGroup(1L, 1L, groupSession(),
                "npc:1", "沈守拙", "阿岚", List.of("沈守拙", "林听竹"), "大家今天忙吗", history);

        // L0 稳定前缀：本人角色卡
        assertTrue(ctx.system().contains("沈守拙"));
        assertTrue(ctx.system().contains("【世界观】"));
        // L1 场景段：地点 + 在场名单 + 冻结时刻
        assertTrue(ctx.user().contains("【场景】"));
        assertTrue(ctx.user().contains("你们在村口"));
        assertTrue(ctx.user().contains("在场的有：沈守拙、林听竹"));
        assertTrue(ctx.user().contains("青禾村历 0420-05-15"));
        // 【对家】：非本人（npc:2 林听竹）的最近发言，本人（npc:1）发言被排除
        assertTrue(ctx.user().contains("【对家】最近你听到："));
        assertTrue(ctx.user().contains("林听竹"));
        assertFalse(ctx.user().contains("沈守拙：诸位今日气色不错"));
        // 群聊历史：逐消息标注说话人
        assertTrue(ctx.user().contains("【对话历史】"));
        assertTrue(ctx.user().contains("沈守拙：风大，坐屋里说话罢"));
        // 本轮任务：不替他人说话
        assertTrue(ctx.user().contains("【本轮】"));
        assertTrue(ctx.user().contains("阿岚对大家说：大家今天忙吗"));
        assertTrue(ctx.user().contains("不要替其他角色"));
    }

    @Test
    void composeGroupSceneSkipsWhenNoLocationOrMembers() {
        ActorDialogueSession s = groupSession();
        s.setLocationId(null);
        s.setLocationName(null);
        DialogueContextAssembler.ComposedContext ctx = assembler.composeGroup(1L, 1L, s,
                "npc:1", "沈守拙", "阿岚", List.of(), "在吗", List.of());
        assertFalse(ctx.user().contains("你们在"));
        assertFalse(ctx.user().contains("在场的有"));
    }

    @Test
    void composeGroupCounterPartSkipsWhenNoOtherSpeaker() {
        List<ActorDialogueMessage> history = List.of(msg("group", "自己说的"));
        history.get(0).setCharacterRef("npc:1");
        DialogueContextAssembler.ComposedContext ctx = assembler.composeGroup(1L, 1L, groupSession(),
                "npc:1", "沈守拙", "阿岚", List.of("沈守拙"), "在吗", history);
        // 历史只有本人发言 → 【对家】段整段跳过（任务段提示语「见【对家】」不视为注入）
        assertFalse(ctx.user().contains("【对家】最近你听到："));
        assertTrue(ctx.user().contains("【对话历史】"));
    }

    @Test
    void composeGroupAppliesAuthorNoteByDepth() {
        ActorDialogueSession s = groupSession();
        s.setAuthorNote("群聊氛围轻松些");
        s.setAuthorNoteDepth(1);
        s.setAuthorNoteFrequency(1);
        DialogueContextAssembler.ComposedContext ctx = assembler.composeGroup(1L, 1L, s,
                "npc:1", "沈守拙", "阿岚", List.of("沈守拙"), "在吗", List.of());
        assertTrue(ctx.system().endsWith("【作者注释】群聊氛围轻松些"));
        s.setAuthorNoteDepth(2);
        DialogueContextAssembler.ComposedContext ctx2 = assembler.composeGroup(1L, 1L, s,
                "npc:1", "沈守拙", "阿岚", List.of("沈守拙"), "在吗", List.of());
        assertTrue(ctx2.user().startsWith("【作者注释】群聊氛围轻松些"));
        assertFalse(ctx2.system().contains("【作者注释】"));
    }

    @Test
    void groupGreetingPromptContainsSceneOnly() {
        String prompt = assembler.groupGreetingPrompt(groupSession(), "沈守拙", "阿岚", List.of("沈守拙", "林听竹"));
        assertTrue(prompt.contains("【场景】"));
        assertTrue(prompt.contains("你们在村口"));
        assertTrue(prompt.contains("在场的有：沈守拙、林听竹"));
        assertTrue(prompt.contains("开场白"));
        assertFalse(prompt.contains("【对话历史】"));
        assertFalse(prompt.contains("【本轮】"));
    }
}
