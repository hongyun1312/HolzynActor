package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroTrace;
import com.holzyn.actor.domain.world.repository.ActorInfoPacketRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldChronicleRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroEventRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroTraceRepository;
import com.holzyn.actor.domain.world.vo.MacroTraceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 宏观全链路 trace 服务单元测试（M5.5，domain/world/service，同包）。
 * <p>职责：验证——① 记录四跳字段落库（input_json/params_json/event_ids_json/feedback_json
 * [帧板块·编年史「宏观」标签·宏观信息包]）；② uk 幂等覆盖写（同项目同日同类型不重复行）；
 * ③ 滚动裁剪（超上限删最旧）；④ 读取 VO（JSON 解析 + limit 钳制）；⑤ 失败零阻断
 * （事件/编年史/信息包任一查询异常不阻断 trace 落库）。</p>
 */
class MacroTraceServiceTest {

    private ActorWorldMacroTraceRepository traceRepository;
    private ActorWorldMacroEventRepository eventRepository;
    private ActorWorldChronicleRepository chronicleRepository;
    private ActorInfoPacketRepository packetRepository;
    private MacroTraceService service;

    @BeforeEach
    void setUp() {
        traceRepository = Mockito.mock(ActorWorldMacroTraceRepository.class);
        eventRepository = Mockito.mock(ActorWorldMacroEventRepository.class);
        chronicleRepository = Mockito.mock(ActorWorldChronicleRepository.class);
        packetRepository = Mockito.mock(ActorInfoPacketRepository.class);
        service = new MacroTraceService(traceRepository, eventRepository, chronicleRepository,
                packetRepository, new ObjectMapper());
        when(traceRepository.save(any(ActorWorldMacroTrace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(traceRepository.countByProjectId(anyLong())).thenReturn(0L);
        when(traceRepository.findByProjectIdAndGameDayAndTickType(anyLong(), anyLong(), any()))
                .thenReturn(Optional.empty());
        when(chronicleRepository.countByProjectIdAndGameDayAndTagsContaining(anyLong(), anyLong(), any()))
                .thenReturn(2L);
        when(packetRepository.countByProjectIdAndInfoIdStartingWith(anyLong(), any()))
                .thenReturn(3L);
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    void recordPersistsFourJumps() {
        // 当日宏观事件 2 条（事件跳）
        ActorWorldMacroEvent e1 = new ActorWorldMacroEvent();
        e1.setId(101L);
        ActorWorldMacroEvent e2 = new ActorWorldMacroEvent();
        e2.setId(102L);
        when(eventRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(1L, 5L, 5L))
                .thenReturn(List.of(e1, e2));

        service.record(1L, 5L, 432000L, "daily",
                map("season", "spring", "weather", "晴", "deltas", map("market_activity", 2.0)),
                map("harvestFactor", 0.9, "grainPrice", 61.64, "grievance", 0), true);

        ArgumentCaptor<ActorWorldMacroTrace> captor = ArgumentCaptor.forClass(ActorWorldMacroTrace.class);
        verify(traceRepository).save(captor.capture());
        ActorWorldMacroTrace t = captor.getValue();
        assertEquals(1L, t.getProjectId());
        assertEquals(5L, t.getGameDay());
        assertEquals(432000L, t.getGameSecond());
        assertEquals("daily", t.getTickType());
        assertTrue(t.getInputJson().contains("\"season\":\"spring\""), t.getInputJson());
        assertTrue(t.getInputJson().contains("\"market_activity\":2.0"), t.getInputJson());
        assertTrue(t.getParamsJson().contains("\"grainPrice\":61.64"), t.getParamsJson());
        // 事件跳：当日 2 条宏观事件主键
        assertTrue(t.getEventIdsJson().contains("101") && t.getEventIdsJson().contains("102"),
                t.getEventIdsJson());
        // 回灌跳：帧板块写入 + 编年史「宏观」标签 2 + 宏观信息包 3
        assertTrue(t.getFeedbackJson().contains("\"frameBoard\":true"), t.getFeedbackJson());
        assertTrue(t.getFeedbackJson().contains("\"chronicleMacro\":2"), t.getFeedbackJson());
        assertTrue(t.getFeedbackJson().contains("\"macroPackets\":3"), t.getFeedbackJson());
    }

    @Test
    void recordUpsertOverwritesByUk() {
        ActorWorldMacroTrace existing = new ActorWorldMacroTrace();
        existing.setId(7L);
        when(traceRepository.findByProjectIdAndGameDayAndTickType(1L, 5L, "faction"))
                .thenReturn(Optional.of(existing));

        service.record(1L, 5L, 100L, "faction", map("grievance", 10.0), map("factions", map("官府", 60.0)), true);
        service.record(1L, 5L, 200L, "faction", map("grievance", 12.0), map("factions", map("官府", 61.0)), true);

        ArgumentCaptor<ActorWorldMacroTrace> captor = ArgumentCaptor.forClass(ActorWorldMacroTrace.class);
        verify(traceRepository, Mockito.times(2)).save(captor.capture());
        assertEquals(7L, captor.getAllValues().get(0).getId(), "uk 命中复用既有行（覆盖写不重复建行）");
        assertEquals(7L, captor.getAllValues().get(1).getId());
        assertTrue(captor.getAllValues().get(1).getInputJson().contains("12.0"));
    }

    @Test
    void pruneRemovesOldestBeyondCap() {
        when(traceRepository.countByProjectId(1L)).thenReturn((long) (MacroTraceService.KEEP_PER_PROJECT + 5));
        ActorWorldMacroTrace t1 = new ActorWorldMacroTrace();
        t1.setId(1L);
        ActorWorldMacroTrace t2 = new ActorWorldMacroTrace();
        t2.setId(2L);
        when(traceRepository.findByProjectIdOrderByIdAsc(1L, org.springframework.data.domain.PageRequest.of(0, 5)))
                .thenReturn(List.of(t1, t2));

        service.record(1L, 6L, 100L, "daily", map("a", 1), map("b", 2), true);

        verify(traceRepository).deleteAllById(List.of(1L, 2L));
    }

    @Test
    void recordSurvivesFeedbackQueryFailures() {
        // 事件查询抛异常 → 事件跳为空但 trace 仍落库（零阻断）
        when(eventRepository.findByProjectIdAndGameDayBetweenOrderByGameDayAsc(anyLong(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("db down"));
        when(chronicleRepository.countByProjectIdAndGameDayAndTagsContaining(anyLong(), anyLong(), any()))
                .thenThrow(new RuntimeException("db down"));
        when(packetRepository.countByProjectIdAndInfoIdStartingWith(anyLong(), any()))
                .thenThrow(new RuntimeException("db down"));

        service.record(1L, 5L, 100L, "daily", map("season", "summer"), map("harvestFactor", 1.05), true);

        ArgumentCaptor<ActorWorldMacroTrace> captor = ArgumentCaptor.forClass(ActorWorldMacroTrace.class);
        verify(traceRepository).save(captor.capture());
        assertEquals("daily", captor.getValue().getTickType());
        assertTrue(captor.getValue().getInputJson().contains("summer"));
        assertTrue(captor.getValue().getFeedbackJson().contains("\"chronicleMacro\":0"));
    }

    @Test
    void listVOsParsesAllJumps() {
        ActorWorldMacroTrace t = new ActorWorldMacroTrace();
        t.setId(9L);
        t.setProjectId(1L);
        t.setGameDay(5L);
        t.setTickType("daily");
        t.setInputJson("{\"season\":\"winter\",\"weather\":\"雪\"}");
        t.setParamsJson("{\"grainPrice\":80.5}");
        t.setEventIdsJson("[201,202]");
        t.setFeedbackJson("{\"frameBoard\":true,\"macroPackets\":3}");
        when(traceRepository.findRecent(eq(1L), eq(null), any()))
                .thenReturn(List.of(t));

        List<MacroTraceVO> vos = service.listVOs(1L, null, 999);

        assertEquals(1, vos.size());
        MacroTraceVO vo = vos.get(0);
        assertEquals("winter", vo.getInput().get("season"));
        assertEquals(80.5, vo.getParams().get("grainPrice"));
        assertEquals(List.of(201L, 202L), vo.getEventIds());
        assertEquals(Boolean.TRUE, vo.getFeedback().get("frameBoard"));
        assertEquals(3L, vo.getFeedback().get("macroPackets"));
    }

    @Test
    void listVOsLimitClampedAndFailureEmpty() {
        // limit 钳制（>200 → 200 上限由 PageRequest 承载，不抛错即可）
        when(traceRepository.findRecent(anyLong(), any(), any())).thenReturn(List.of());
        assertEquals(0, service.listVOs(1L, "daily", 999).size());
        // 读取异常 → 空列表
        when(traceRepository.findRecent(anyLong(), any(), any()))
                .thenThrow(new RuntimeException("db down"));
        assertEquals(0, service.listVOs(1L, null, 50).size());
        verify(traceRepository, never()).deleteAllById(any());
    }
}
