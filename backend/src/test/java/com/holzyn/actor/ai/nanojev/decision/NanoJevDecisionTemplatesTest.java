package com.holzyn.actor.ai.nanojev.decision;

import com.holzyn.actor.ai.nanojev.NanoJevQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N3 判定类落地单测：运行时场景模板（NanoJevDecisionTemplates）。
 *
 * <p>测试纪律（V1.3 第六层）：纯静态构造断言——三题型契约合法（choice 2-255 / boolean
 * false·true / score 档位数）、instructions/criteria 非空、关键判据关键词在场（自包含）。</p>
 *
 * <p>所属模块：ai/nanojev/decision（N 轨道 N3）</p>
 */
class NanoJevDecisionTemplatesTest {

    @Test
    @DisplayName("社交意愿 boolean 题：契约合法且判据自包含（N2 v2 口径）")
    void socialWillingnessQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.socialWillingnessQuestion();
        assertEquals(NanoJevQuestion.TYPE_BOOLEAN, q.type());
        assertNotNull(q.criteria());
        assertEquals(2, q.criteria().size());
        assertTrue(q.criteria().containsKey("false"));
        assertTrue(q.criteria().containsKey("true"));
        assertTrue(q.instructions().contains("愿意"));
        assertTrue(q.criteria().get("false").contains("疲惫"));
        assertTrue(q.criteria().get("true").contains("愿意"));
        assertEquals(1, q.pathCount()); // boolean 计 1 路径
    }

    @Test
    @DisplayName("crowd choice 题：候选合法且通用项恒在（continue/rest/chat）")
    void crowdQuestion() {
        Map<String, String> candidates = NanoJevDecisionTemplates.commonCrowdCandidates();
        assertTrue(candidates.containsKey("continue_current"));
        assertTrue(candidates.containsKey("rest"));
        assertTrue(candidates.containsKey("chat"));
        NanoJevQuestion q = NanoJevDecisionTemplates.crowdQuestion(candidates);
        assertEquals(NanoJevQuestion.TYPE_CHOICE, q.type());
        assertEquals(3, q.criteria().size());
        assertEquals(3, q.pathCount());
        assertTrue(q.instructions().contains("最可能"));
    }

    @Test
    @DisplayName("G4 心动 boolean 题：判据含互动/好感")
    void romanceAttractQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.romanceAttractQuestion();
        assertEquals(NanoJevQuestion.TYPE_BOOLEAN, q.type());
        assertTrue(q.criteria().get("true").contains("动心"));
        assertTrue(q.criteria().get("false").contains("不动心"));
    }

    @Test
    @DisplayName("G4 求婚 choice 题：propose/wait/give_up 三态自包含")
    void proposalQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.proposalQuestion();
        assertEquals(NanoJevQuestion.TYPE_CHOICE, q.type());
        assertEquals(3, q.criteria().size());
        assertTrue(q.criteria().containsKey("propose"));
        assertTrue(q.criteria().containsKey("wait"));
        assertTrue(q.criteria().containsKey("give_up"));
    }

    @Test
    @DisplayName("G4 生育 score 题：3 档自包含（0 弱/1 一般/2 强）")
    void fertilityQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.fertilityQuestion();
        assertEquals(NanoJevQuestion.TYPE_SCORE, q.type());
        assertEquals(3, q.scoreCriteria().size());
        assertEquals(3, q.pathCount()); // score 按档位数计路径
        assertTrue(q.scoreCriteria().get(0).contains("不想要"));
        assertTrue(q.scoreCriteria().get(2).contains("想要"));
    }

    @Test
    @DisplayName("G3 面具情境 choice 题：8 情境码候选")
    void maskContextQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.maskContextQuestion();
        assertEquals(NanoJevQuestion.TYPE_CHOICE, q.type());
        assertEquals(8, q.criteria().size());
        assertTrue(q.criteria().containsKey("public_place"));
        assertTrue(q.criteria().containsKey("private_place"));
        assertTrue(q.criteria().containsKey("before_authority"));
    }

    @Test
    @DisplayName("G3 面具破绽 boolean 题：判据含触发物")
    void maskBreachQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.maskBreachQuestion();
        assertEquals(NanoJevQuestion.TYPE_BOOLEAN, q.type());
        assertTrue(q.criteria().get("true").contains("破绽"));
        assertTrue(q.criteria().get("false").contains("无事"));
    }

    @Test
    @DisplayName("打断仲裁 choice 题：proceed/wait/yield/redirect 四态")
    void negotiateQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.negotiateQuestion();
        assertEquals(NanoJevQuestion.TYPE_CHOICE, q.type());
        assertEquals(4, q.criteria().size());
        assertTrue(q.criteria().containsKey("proceed"));
        assertTrue(q.criteria().containsKey("wait"));
        assertTrue(q.criteria().containsKey("yield"));
        assertTrue(q.criteria().containsKey("redirect"));
    }

    @Test
    @DisplayName("护栏语义 boolean 题：判据含越界/合规")
    void guardSemanticQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.guardSemanticQuestion();
        assertEquals(NanoJevQuestion.TYPE_BOOLEAN, q.type());
        assertTrue(q.criteria().get("true").contains("越界"));
        assertTrue(q.criteria().get("false").contains("合规"));
    }

    @Test
    @DisplayName("N4 空间前置条件 boolean 题：条件原文自包含（false=不满足/true=满足）")
    void spacePreconditionQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.spacePreconditionQuestion("白天且精力不低于30");
        assertEquals(NanoJevQuestion.TYPE_BOOLEAN, q.type());
        assertEquals(2, q.criteria().size());
        assertTrue(q.criteria().containsKey("false"));
        assertTrue(q.criteria().containsKey("true"));
        assertTrue(q.instructions().contains("白天且精力不低于30"));
        assertTrue(q.criteria().get("true").contains("满足"));
        assertTrue(q.criteria().get("false").contains("不满足"));
        assertEquals(1, q.pathCount());
    }

    @Test
    @DisplayName("N4 空间行动 choice 题：候选键合法且判据自包含（只选此刻真正可能做的）")
    void spaceActionQuestion() {
        Map<String, String> candidates = NanoJevDecisionTemplates.commonCrowdCandidates();
        candidates.put("space0", "去河边挑水（倾向 80%）");
        NanoJevQuestion q = NanoJevDecisionTemplates.spaceActionQuestion(candidates);
        assertEquals(NanoJevQuestion.TYPE_CHOICE, q.type());
        assertEquals(4, q.criteria().size());
        assertEquals(4, q.pathCount());
        assertTrue(q.criteria().containsKey("space0"));
        assertEquals("去河边挑水（倾向 80%）", q.criteria().get("space0"));
        assertTrue(q.instructions().contains("真正可能"));
    }

    // ==================== N5 认知 LOD + 高频微决策（2026-09-26） ====================

    @Test
    @DisplayName("N5 注意力状态 score 题：4 档自包含（无/低/中/高）")
    void attentionStateQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.attentionStateQuestion();
        assertEquals(NanoJevQuestion.TYPE_SCORE, q.type());
        assertEquals(4, q.scoreCriteria().size());
        assertEquals(4, q.pathCount());
        assertTrue(q.scoreCriteria().get(0).contains("无"));
        assertTrue(q.scoreCriteria().get(3).contains("高"));
        assertTrue(q.instructions().contains("注意力"));
    }

    @Test
    @DisplayName("N5 事件相关度 score 题：事件原文注入且 4 档自包含")
    void eventRelevanceQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.eventRelevanceQuestion("集市上张屠户与人起了争执");
        assertEquals(NanoJevQuestion.TYPE_SCORE, q.type());
        assertEquals(4, q.scoreCriteria().size());
        assertTrue(q.instructions().contains("集市上张屠户与人起了争执"));
        assertTrue(q.scoreCriteria().get(0).contains("无关"));
        assertTrue(q.scoreCriteria().get(3).contains("高"));
    }

    @Test
    @DisplayName("N5 即时意向 choice 题：6 候选且「继续当下事」恒在（绝对多数项）")
    void microIntentQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.microIntentQuestion();
        assertEquals(NanoJevQuestion.TYPE_CHOICE, q.type());
        assertEquals(6, q.criteria().size());
        assertEquals(6, q.pathCount());
        assertTrue(q.criteria().containsKey("continue_current"));
        assertTrue(q.criteria().containsKey("drift"));
        assertTrue(q.criteria().containsKey("look_around"));
        assertTrue(q.criteria().containsKey("fidget"));
        assertTrue(q.criteria().containsKey("chat"));
        assertTrue(q.criteria().containsKey("move_about"));
    }

    @Test
    @DisplayName("N5 空间重算触发 boolean 题：变化原文注入且 false/true 判据自包含")
    void spaceTriggerQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.spaceTriggerQuestion("情绪从平静变为愤怒");
        assertEquals(NanoJevQuestion.TYPE_BOOLEAN, q.type());
        assertEquals(2, q.criteria().size());
        assertTrue(q.criteria().containsKey("false"));
        assertTrue(q.criteria().containsKey("true"));
        assertTrue(q.instructions().contains("情绪从平静变为愤怒"));
        assertTrue(q.criteria().get("true").contains("值得"));
        assertTrue(q.criteria().get("false").contains("不值得"));
    }

    @Test
    @DisplayName("N5 人格偏移证据强度 score 题：4 档自包含（无/弱/中/强）")
    void personaShiftEvidenceQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.personaShiftEvidenceQuestion();
        assertEquals(NanoJevQuestion.TYPE_SCORE, q.type());
        assertEquals(4, q.scoreCriteria().size());
        assertEquals(4, q.pathCount());
        assertTrue(q.scoreCriteria().get(0).contains("没有支持"));
        assertTrue(q.scoreCriteria().get(3).contains("强"));
        assertTrue(q.instructions().contains("人格偏移"));
    }

    @Test
    @DisplayName("N5 人格偏移方向 choice 题：正/负/不明三态自包含")
    void personaShiftDirectionQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.personaShiftDirectionQuestion();
        assertEquals(NanoJevQuestion.TYPE_CHOICE, q.type());
        assertEquals(3, q.criteria().size());
        assertEquals(3, q.pathCount());
        assertTrue(q.criteria().containsKey("positive"));
        assertTrue(q.criteria().containsKey("negative"));
        assertTrue(q.criteria().containsKey("unclear"));
    }

    @Test
    @DisplayName("N5 关系影响幅度 score 题：4 档自包含（无/小/中/大）")
    void relationDeltaMagnitudeQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.relationDeltaMagnitudeQuestion();
        assertEquals(NanoJevQuestion.TYPE_SCORE, q.type());
        assertEquals(4, q.scoreCriteria().size());
        assertTrue(q.scoreCriteria().get(0).contains("无"));
        assertTrue(q.scoreCriteria().get(3).contains("大"));
    }

    @Test
    @DisplayName("N5 关系影响方向 choice 题：增进/恶化/无变化三态自包含")
    void relationDeltaDirectionQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.relationDeltaDirectionQuestion();
        assertEquals(NanoJevQuestion.TYPE_CHOICE, q.type());
        assertEquals(3, q.criteria().size());
        assertTrue(q.criteria().containsKey("improve"));
        assertTrue(q.criteria().containsKey("worsen"));
        assertTrue(q.criteria().containsKey("unchanged"));
    }

    @Test
    @DisplayName("N5 意外事件 boolean 题：false/true 判据自包含（默认关场景）")
    void accidentQuestion() {
        NanoJevQuestion q = NanoJevDecisionTemplates.accidentQuestion();
        assertEquals(NanoJevQuestion.TYPE_BOOLEAN, q.type());
        assertEquals(2, q.criteria().size());
        assertTrue(q.criteria().containsKey("false"));
        assertTrue(q.criteria().containsKey("true"));
        assertTrue(q.criteria().get("false").contains("无事"));
        assertTrue(q.criteria().get("true").contains("意外"));
        assertEquals(1, q.pathCount());
    }
}
