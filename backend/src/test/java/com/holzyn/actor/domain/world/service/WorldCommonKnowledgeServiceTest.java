package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 世界常识摘要服务单测（domain/world/service，V2.0 段 1A）。
 * <p>职责：① AI 生成 300-500 字精炼常识并写库（含 commonKnowledgeAt 时间戳）；
 * ② 幂等（世界观未变跳过）；③ AI 输出过短/空 → 保留旧值 fail-open；④ 输出清洗
 * （markdown 围栏剥除/超长截断 500/过短 null）；⑤ 定期扫描按最新版本分组补生成。</p>
 * <p>背景：2026-10-08 外网冒烟发现 AiContextInjector.worldBrief 硬截断世界观原文
 * （停在「农业工具改」句中）→ 新增本服务：依最新世界观 AI 生成 300-500 字精炼
 * 「该世界角色都知道的常识」，注入器优先使用（完整返回不截断）。</p>
 */
class WorldCommonKnowledgeServiceTest {

    private ActorWorldSettingRepository worldSettingRepository;
    private PromptTemplateService templateService;
    private AiSpringChatGateway aiChatGateway;
    private WorldCommonKnowledgeService service;

    /** 合规常识（300-500 字）。 */
    private static final String GOOD =
            "青禾村位于青岳山东麓的灵水走廊上，是大雍朝治下的一座灵秀村落。村中灵脉由青岳主峰"
                    + "发源，沿山势向村脚递减，灵气滋养着梯田与河谷；守脉堂的守脉人世代以自身精血供养灵脉，"
                    + "维持全村风调雨顺。临溪镇是山脚平原与外界交通的枢纽，商队与散修在此往来。凡人与"
                    + "灵植亲和的灵修者共处，低阶散修与宗门隐修并存：竹林隐修会守着幽竹秘境的古法聚灵阵，"
                    + "临溪镇商会垄断着灵石与凡俗物资的交换。村中按灵农历行事，月相影响灵力潮汐，逢节气"
                    + "祭谷神、朔望日铁匠不开炉。青禾村史由长老议事与茶摊闲谈口耳相传，巫祝神契世代守护着"
                    + "这份与灵脉共生的秩序。山外的人若要进村，须经灵溪渡口的船夫引路；村中每逢集日，"
                    + "梯田上的农人、铁匠铺的学徒与茶摊的常客都会聚到村口，交换收成与消息，日子就这样"
                    + "顺着灵脉的呼吸一天天过去。";

    @BeforeEach
    void setUp() {
        worldSettingRepository = mock(ActorWorldSettingRepository.class);
        templateService = mock(PromptTemplateService.class);
        aiChatGateway = mock(AiSpringChatGateway.class);
        service = new WorldCommonKnowledgeService(worldSettingRepository, templateService, aiChatGateway);
        when(templateService.render(anyLong(), anyLong(), eq(PromptTemplateService.CODE_WORLD_COMMON_KNOWLEDGE), any()))
                .thenReturn("渲染后的常识提炼提示词");
        when(templateService.systemMessage(anyLong(), anyLong(), eq(PromptTemplateService.CODE_WORLD_COMMON_KNOWLEDGE), any()))
                .thenReturn("你是世界设定提炼师…");
    }

    private ActorWorldSetting world() {
        ActorWorldSetting w = new ActorWorldSetting();
        w.setProjectId(1L);
        w.setVersion(3);
        w.setName("青禾村志");
        w.setGenre("乡土奇幻");
        w.setEra("架空古代");
        w.setGeography("青禾村四面环山，村西有溪，村东为梯田。");
        w.setFactions("沈氏宗族掌村；临溪镇商会互通往来。");
        w.setMagicSystem("灵脉沉寂，神契尚存微弱感应。");
        w.setCulture("逢节气祭谷神；村老议事。");
        w.setHistory("三百年前避兵灾建村。");
        w.setFreeText("村口老槐树下埋着百年前的契约碑。");
        w.setUpdatedAt(LocalDateTime.now());
        return w;
    }

    private void wireAi(String content) {
        when(aiChatGateway.chatCompletion(anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiChatResult(content, "qwen3.6-35b-q4-un", 120, 180, 300, 1L));
    }

    // ==================== 生成 ====================

    @Test
    void generate_writesCommonKnowledgeAndTimestamp() {
        ActorWorldSetting w = world();
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L)).thenReturn(Optional.of(w));
        wireAi(GOOD);
        assertTrue(service.generate(1L, 1L));
        assertEquals(GOOD, w.getCommonKnowledge());
        assertTrue(w.getCommonKnowledgeAt() != null);
        verify(worldSettingRepository).saveAndFlush(w);
    }

    @Test
    void generate_skipsWhenWorldUnchanged() {
        ActorWorldSetting w = world();
        w.setCommonKnowledge(GOOD);
        w.setCommonKnowledgeAt(LocalDateTime.now());
        w.setUpdatedAt(LocalDateTime.now().minusMinutes(5)); // 世界观未更新
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L)).thenReturn(Optional.of(w));
        assertFalse(service.generate(1L, 1L));
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    @Test
    void generate_regeneratesWhenWorldUpdatedAfterCommonKnowledge() {
        ActorWorldSetting w = world();
        w.setCommonKnowledge("旧常识");
        w.setCommonKnowledgeAt(LocalDateTime.now().minusMinutes(30));
        w.setUpdatedAt(LocalDateTime.now()); // 世界观比常识新 → 重新生成
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L)).thenReturn(Optional.of(w));
        wireAi(GOOD);
        assertTrue(service.generate(1L, 1L));
        assertEquals(GOOD, w.getCommonKnowledge());
    }

    @Test
    void generate_keepsOldValueWhenAiReturnsBlank() {
        ActorWorldSetting w = world();
        w.setCommonKnowledge("旧常识");
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L)).thenReturn(Optional.of(w));
        wireAi("  "); // 空输出
        assertFalse(service.generate(1L, 1L));
        assertEquals("旧常识", w.getCommonKnowledge());
    }

    @Test
    void generate_skipsWhenNoWorldSetting() {
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        assertFalse(service.generate(1L, 1L));
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }

    // ==================== 输出清洗 ====================

    @Test
    void sanitize_stripsMarkdownFence() {
        String s = WorldCommonKnowledgeService.sanitize("```\n" + GOOD + "\n```");
        assertEquals(GOOD, s);
    }

    @Test
    void sanitize_truncatesTo500() {
        String raw = "字".repeat(600);
        String s = WorldCommonKnowledgeService.sanitize(raw);
        assertTrue(s.length() <= WorldCommonKnowledgeService.MAX_CHARS + 1);
        assertTrue(s.endsWith("…"));
    }

    @Test
    void sanitize_returnsNullWhenTooShort() {
        assertNull(WorldCommonKnowledgeService.sanitize("太短"));
        assertNull(WorldCommonKnowledgeService.sanitize("  "));
        assertNull(WorldCommonKnowledgeService.sanitize(null));
    }

    @Test
    void sanitize_passesThroughGoodText() {
        assertEquals(GOOD, WorldCommonKnowledgeService.sanitize(GOOD));
    }

    // ==================== 定期扫描 ====================

    @Test
    void scanAndBackfill_generatesForLatestVersionOnly() {
        ActorWorldSetting v1 = new ActorWorldSetting();
        v1.setProjectId(1L);
        v1.setVersion(1);
        v1.setName("旧版");
        v1.setUpdatedAt(LocalDateTime.now().minusDays(2));
        ActorWorldSetting v3 = world(); // 最新版
        when(worldSettingRepository.findAll()).thenReturn(java.util.List.of(v1, v3));
        // generate 内部会重查最新版（findTopByProjectIdOrderByVersionDesc）
        when(worldSettingRepository.findTopByProjectIdOrderByVersionDesc(1L)).thenReturn(Optional.of(v3));
        wireAi(GOOD);
        service.scanAndBackfill();
        // 只对最新版（v3）生成，旧版不处理
        assertEquals(GOOD, v3.getCommonKnowledge());
        assertNull(v1.getCommonKnowledge());
    }

    @Test
    void scanAndBackfill_skipsUpToDate() {
        ActorWorldSetting w = world();
        w.setCommonKnowledge(GOOD);
        w.setCommonKnowledgeAt(LocalDateTime.now());
        w.setUpdatedAt(LocalDateTime.now().minusMinutes(1));
        when(worldSettingRepository.findAll()).thenReturn(java.util.List.of(w));
        service.scanAndBackfill();
        verify(aiChatGateway, never()).chatCompletion(anyLong(), anyLong(), any(), any());
    }
}
