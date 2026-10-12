package com.holzyn.actor.domain.evaluation;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.service.WorldClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DecisionSampleSampler 单元测试（O2.5 P2-1 双通道采样：DB 采样 + JSONL 导入）。
 */
class DecisionSampleSamplerTest {

    private ActorAiCallLogRepository aiCallLogRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository ordinaryNpcRepository;
    private WorldClockService worldClockService;
    private EvaluationProperties properties;
    private DecisionSampleSampler sampler;

    @BeforeEach
    void setUp() {
        aiCallLogRepository = Mockito.mock(ActorAiCallLogRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        ordinaryNpcRepository = Mockito.mock(ActorCharacterRepository.class);
        worldClockService = Mockito.mock(WorldClockService.class);
        properties = new EvaluationProperties();
        ReflectionTestUtils.setField(properties, "scenesCsv", "action_gen,interrupt_resume,dialog,autonomous_chat_beat,negotiate");
        ReflectionTestUtils.setField(properties, "defaultSampleCount", 20);
        ReflectionTestUtils.setField(properties, "defaultWindowGameMinutes", 1440);
        sampler = new DecisionSampleSampler(aiCallLogRepository, characterRepository, ordinaryNpcRepository,
                worldClockService, properties);
    }

    /** DB 采样：Specification 查询 + 角色名/ref 解析 + 样本字段映射。 */
    @Test
    void sampleFromDbMapsRowsAndResolvesNames() {
        ActorWorldClock clock = new ActorWorldClock();
        clock.setRate(24);
        when(worldClockService.requireClock(1L)).thenReturn(clock);
        ActorCharacter ch = new ActorCharacter();
        ch.setId(5L);
        ch.setProjectId(1L);
        ch.setName("沈砚秋");
        when(characterRepository.findById(5L)).thenReturn(Optional.of(ch));
        ActorAiCallLog call = new ActorAiCallLog();
        call.setId(10L);
        call.setProjectId(1L);
        call.setCharacterId(5L);
        call.setScene("action_gen");
        call.setStatus("success");
        call.setUserPrompt("有人争摊位");
        call.setSystemPrompt("你是沈砚秋…");
        call.setResponse("决定去集市");
        call.setTraceId("t-1");
        call.setCreatedAt(LocalDateTime.now());
        when(aiCallLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(call)));

        List<DecisionSample> out = sampler.sampleFromDb(1L, 10, 1440, null);
        assertEquals(1, out.size());
        DecisionSample s = out.get(0);
        assertEquals("db", s.source());
        assertEquals("npc:5", s.npcRef());
        assertEquals("沈砚秋", s.npcName());
        assertEquals("action_gen", s.scene());
        assertEquals("有人争摊位", s.stimulusText());
        assertEquals("决定去集市", s.responseText());
        assertEquals("t-1", s.traceId());
        verify(aiCallLogRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    /** DB 采样：普通 NPC 解析为 crowd ref；查询走 Specification 过滤（时间窗折算真实分钟）。 */
    @Test
    void sampleFromDbResolvesCrowdRef() {
        ActorWorldClock clock = new ActorWorldClock();
        clock.setRate(24);
        when(worldClockService.requireClock(1L)).thenReturn(clock);
        ActorCharacter npc = new ActorCharacter();
        npc.setId(9L);
        npc.setProjectId(1L);
        npc.setName("王二");
        when(ordinaryNpcRepository.findById(9L)).thenReturn(Optional.of(npc));
        ActorAiCallLog call = new ActorAiCallLog();
        call.setId(11L);
        call.setProjectId(1L);
        call.setCharacterId(9L);
        call.setScene("dialog");
        call.setStatus("success");
        call.setUserPrompt("问好");
        call.setResponse("回礼");
        call.setCreatedAt(LocalDateTime.now());
        when(aiCallLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(call)));
        List<DecisionSample> out = sampler.sampleFromDb(1L, 5, null, null);
        assertEquals("npc:9", out.get(0).npcRef());
        assertEquals("王二", out.get(0).npcName());
    }

    /** JSONL 导入：项目过滤 + scene/status 过滤 + 行序倒序取 N 条。 */
    @Test
    void sampleFromJsonlFiltersAndTakesNewestFirst(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ai-calls.jsonl");
        Files.writeString(file, String.join("\n",
                jsonl(1, 37L, "action_gen", "success", "旧样本1", "r1", "t1"),
                jsonl(2, 37L, "action_gen", "success", "新样本2", "r2", "t2"),
                jsonl(3, 37L, "crowd", "success", "非决策场景", "r3", "t3"),
                jsonl(4, 37L, "dialog", "failed", "失败样本", "r4", "t4"),
                jsonl(5, 99L, "action_gen", "success", "其他项目", "r5", "t5")));

        List<DecisionSample> out = sampler.sampleFromJsonl(37L, file.toString(), 10);
        assertEquals(2, out.size(), "仅 2 条命中（场景+状态+项目过滤）");
        assertEquals("新样本2", out.get(0).stimulusText(), "文件内最新行优先");
        assertEquals("旧样本1", out.get(1).stimulusText());
        assertEquals("jsonl", out.get(0).source());
    }

    /** JSONL 导入：文件不存在 → BizException 400（中文提示）。 */
    @Test
    void sampleFromJsonlMissingFileThrows(@TempDir Path dir) {
        Path missing = dir.resolve("not-exist.jsonl");
        assertThrows(BizException.class, () -> sampler.sampleFromJsonl(37L, missing.toString(), 10));
    }

    /** 文本截断工具：超长截断 + 省略号标记。 */
    @Test
    void shortenTruncates() {
        assertEquals("abc…", DecisionSampleSampler.shorten("abcdefg", 3));
        assertEquals("abc", DecisionSampleSampler.shorten("abc", 3));
        assertEquals("", DecisionSampleSampler.shorten(null, 10));
    }

    private static String jsonl(long id, Long projectId, String scene, String status, String prompt,
                                String response, String traceId) {
        return "{\"id\":" + id + ",\"project_id\":" + projectId + ",\"character_id\":5,\"scene\":\""
                + scene + "\",\"status\":\"" + status + "\",\"user_prompt\":\"" + prompt
                + "\",\"system_prompt\":\"sys\",\"response\":\"" + response + "\",\"trace_id\":\"" + traceId + "\"}";
    }
}
