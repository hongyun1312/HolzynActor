package com.holzyn.actor.ai.nanojev.ab;

import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * N2 A/B 评估单测：NanoJevAbSampleFactory 真实情境抽取与三类场景样本构造。
 *
 * <p>测试纪律（V1.3 第六层）：仓库全 mock（Specification 查询返回脚本化行），
 * 零网络零真实 AI；构造样本断言题面/题型/候选集结构。</p>
 *
 * <p>所属模块：ai/nanojev/ab（N 轨道 N2）</p>
 */
class NanoJevAbSampleFactoryTest {

    private ActorAiCallLogRepository aiCallLogRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository ordinaryNpcRepository;
    private NanoJevAbProperties properties;
    private NanoJevAbSampleFactory factory;

    @BeforeEach
    void setUp() {
        aiCallLogRepository = mock(ActorAiCallLogRepository.class);
        characterRepository = mock(ActorCharacterRepository.class);
        ordinaryNpcRepository = mock(ActorCharacterRepository.class);
        properties = new NanoJevAbProperties();
        properties.setEnabled(true);
        properties.setDefaultEmotionQuota(75);
        properties.setDefaultCrowdQuota(75);
        properties.setDefaultSocialQuota(50);
        factory = new NanoJevAbSampleFactory(aiCallLogRepository, characterRepository,
                ordinaryNpcRepository, properties);
    }

    @Test
    @DisplayName("样本构造：三类场景 × 三题型全部产出（all 范围）")
    void buildSamples_allScopes() {
        // 脚本化：按调用顺序返回各场景行（emotion → action → dialog 各 2 条）
        when(aiCallLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(toPage(rowsFor("npc_emotion_eval")))
                .thenReturn(toPage(rowsFor("action")))
                .thenReturn(toPage(rowsFor("dialog")));
        List<AbSample> samples = factory.buildSamples(1L, "all");
        assertNotNull(samples);
        assertTrue(samples.size() >= 6, "三类场景各至少 2 条");
        long emotion = samples.stream().filter(s -> s.scene().equals(AbSample.SCENE_EMOTION_EVAL)).count();
        long crowd = samples.stream().filter(s -> s.scene().equals(AbSample.SCENE_CROWD_DECISION)).count();
        long social = samples.stream().filter(s -> s.scene().equals(AbSample.SCENE_SOCIAL_WILLINGNESS)).count();
        assertEquals(2, emotion);
        assertEquals(2, crowd);
        assertEquals(2, social);
        // 题型断言
        assertTrue(samples.stream().filter(s -> s.scene().equals(AbSample.SCENE_EMOTION_EVAL))
                .allMatch(s -> s.questionType().equals("score")));
        assertTrue(samples.stream().filter(s -> s.scene().equals(AbSample.SCENE_CROWD_DECISION))
                .allMatch(s -> s.questionType().equals("choice")));
        assertTrue(samples.stream().filter(s -> s.scene().equals(AbSample.SCENE_SOCIAL_WILLINGNESS))
                .allMatch(s -> s.questionType().equals("boolean")));
        // 候选选择样本带动态候选集（≥5 项）
        AbSample crowdSample = samples.stream()
                .filter(s -> s.scene().equals(AbSample.SCENE_CROWD_DECISION)).findFirst().orElseThrow();
        assertTrue(crowdSample.question().criteria().size() >= 5);
    }

    @Test
    @DisplayName("样本构造：单场景范围只产出该场景")
    void buildSamples_singleScope() {
        when(aiCallLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(toPage(rowsFor("npc_emotion_eval")));
        List<AbSample> samples = factory.buildSamples(1L, AbSample.SCENE_EMOTION_EVAL);
        assertEquals(2, samples.size());
        assertTrue(samples.stream().allMatch(s -> s.scene().equals(AbSample.SCENE_EMOTION_EVAL)));
    }

    @Test
    @DisplayName("样本构造：无真实情境时返回空（不伪造样本）")
    void buildSamples_emptyReturnsEmpty() {
        when(aiCallLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());
        List<AbSample> samples = factory.buildSamples(1L, "all");
        assertNotNull(samples);
        assertTrue(samples.isEmpty());
    }

    /** 按场景返回行（每种 scene 2 条；含真实 user_prompt/response）。 */
    private static List<ActorAiCallLog> rowsFor(String scene) {
        List<ActorAiCallLog> out = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            ActorAiCallLog row = switch (scene) {
                case "npc_emotion_eval" -> log(1L, "npc_emotion_eval",
                        "青禾村·午后。孙二娘在灵溪河畔洗衣，阿贵路过打招呼。",
                        "{\"emotion\":\"happy\",\"intensity\":62}");
                case "action" -> log(2L, "action",
                        "青禾村·上午。雷铁根在集市摆摊卖菜，天气晴好。",
                        "<decision>{\"action_type\":\"start_action\",\"action_detail\":\"继续招呼客人卖菜\","
                                + "\"candidates\":[{\"action\":\"去灵溪河挑水\"},{\"action\":\"收摊回家做饭\"}]}</decision>");
                default -> log(3L, "dialog",
                        "青禾村·傍晚。赵四娘正在厨房做饭，林秀在门外喊她。",
                        "{\"dialogue\":\"哎，来了来了！\"}");
            };
            out.add(row);
        }
        return out;
    }

    private static ActorAiCallLog log(Long id, String scene, String prompt, String response) {
        ActorAiCallLog l = new ActorAiCallLog();
        l.setId(id);
        l.setProjectId(1L);
        l.setScene(scene);
        l.setStatus("success");
        l.setStepType("ai_call");
        l.setUserPrompt(prompt);
        l.setResponse(response);
        l.setCharacterId(1L);
        return l;
    }

    /** List → Page（Specification 查询返回类型）。 */
    private static org.springframework.data.domain.Page<ActorAiCallLog> toPage(List<ActorAiCallLog> rows) {
        return new org.springframework.data.domain.PageImpl<>(rows);
    }
}
