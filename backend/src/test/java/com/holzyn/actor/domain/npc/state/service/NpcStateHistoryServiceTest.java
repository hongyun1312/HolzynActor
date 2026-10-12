package com.holzyn.actor.domain.npc.state.service;

import com.holzyn.actor.domain.npc.state.config.NpcStateProperties;
import com.holzyn.actor.domain.npc.state.entity.ActorNpcState;
import com.holzyn.actor.domain.npc.state.entity.ActorStateHistory;
import com.holzyn.actor.domain.npc.state.repository.ActorStateHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * NPC 状态历史快照服务单元测试（状态模块 M4，domain/npc/state/service，同包）。
 * <p>职责：验证——采样落库（全字段拷贝/幂等同秒跳过/开关关闭跳过）、保留上限滚动清理
 * （超 maxPerNpc 删最旧）、读接口（升序/限量取最近 N 条/参数非法空列表）。</p>
 * <p>设计依据：《NPC状态模块_主开发方案_V1.0》§12 M4（调试前端曲线数据源）。</p>
 */
class NpcStateHistoryServiceTest {

    private ActorStateHistoryRepository historyRepository;
    private NpcStateProperties props;
    private NpcStateHistoryService service;

    /** 内存快照仓库模拟：exists/save/find 可读，模拟真实 DB 语义 */
    private final List<ActorStateHistory> historyStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        historyRepository = Mockito.mock(ActorStateHistoryRepository.class);
        props = new NpcStateProperties();
        when(historyRepository.existsByProjectIdAndNpcRefAndGameSecond(any(), anyString(), any()))
                .thenAnswer(inv -> historyStore.stream().anyMatch(h ->
                        inv.getArgument(0).equals(h.getProjectId())
                                && inv.getArgument(1).equals(h.getNpcRef())
                                && inv.getArgument(2).equals(h.getGameSecond())));
        when(historyRepository.save(any(ActorStateHistory.class))).thenAnswer(inv -> {
            ActorStateHistory h = inv.getArgument(0);
            if (h.getId() == null) {
                h.setId((long) (historyStore.size() + 1));
                historyStore.add(h);
            }
            return h;
        });
        when(historyRepository.findByProjectIdAndNpcRefOrderByGameSecondAsc(any(), anyString()))
                .thenAnswer(inv -> historyStore.stream()
                        .filter(h -> inv.getArgument(0).equals(h.getProjectId())
                                && inv.getArgument(1).equals(h.getNpcRef()))
                        .sorted(java.util.Comparator.comparing(h -> h.getGameSecond() == null ? 0L : h.getGameSecond()))
                        .toList());
        Mockito.doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<ActorStateHistory> toDel = inv.getArgument(0);
            historyStore.removeAll(toDel);
            return null;
        }).when(historyRepository).deleteAll(Mockito.any());
        service = new NpcStateHistoryService(historyRepository, props);
    }

    private ActorNpcState newState(long gameSecond) {
        ActorNpcState s = new ActorNpcState();
        s.setProjectId(10L);
        s.setNpcType("special");
        s.setNpcRef("special:5");
        s.setGameSecond(gameSecond);
        s.setHunger(68);
        s.setThirst(66);
        s.setEnergy(78);
        s.setBladder(47);
        s.setHealth(98);
        s.setMood(72);
        s.setEmotionPleasure(0.3d);
        s.setDominantEmotion("happy");
        s.setEmotionIntensity(35);
        s.setUrgencyLevel(1);
        s.setUrgencyText("有些饿");
        return s;
    }

    // ==================== 采样 ====================

    @Test
    void recordSnapshot_savesFullFieldCopy() {
        ActorNpcState st = newState(3600L);
        service.recordSnapshot(10L, st);
        assertEquals(1, historyStore.size());
        ActorStateHistory h = historyStore.get(0);
        assertEquals(10L, h.getProjectId());
        assertEquals("special:5", h.getNpcRef());
        assertEquals(3600L, h.getGameSecond());
        assertEquals(68, h.getHunger());
        assertEquals(66, h.getThirst());
        assertEquals(78, h.getEnergy());
        assertEquals(47, h.getBladder());
        assertEquals(98, h.getHealth());
        assertEquals(72, h.getMood());
        assertEquals(0.3d, h.getEmotionPleasure());
        assertEquals("happy", h.getDominantEmotion());
        assertEquals(35, h.getEmotionIntensity());
        assertEquals(1, h.getUrgencyLevel());
        assertEquals("有些饿", h.getUrgencyText());
    }

    @Test
    void recordSnapshot_sameSecondIsIdempotent() {
        service.recordSnapshot(10L, newState(3600L));
        service.recordSnapshot(10L, newState(3600L));
        assertEquals(1, historyStore.size(), "同一 (项目, NPC, 游戏时刻) 重复采样应跳过");
    }

    @Test
    void recordSnapshot_disabledSkips() {
        props.getHistory().setEnabled(false);
        service.recordSnapshot(10L, newState(3600L));
        assertTrue(historyStore.isEmpty(), "采样开关关闭应不落库");
    }

    @Test
    void recordSnapshot_nullOrNoGameSecondSkips() {
        service.recordSnapshot(null, newState(3600L));
        service.recordSnapshot(10L, null);
        ActorNpcState noSecond = newState(3600L);
        noSecond.setGameSecond(null);
        service.recordSnapshot(10L, noSecond);
        assertTrue(historyStore.isEmpty());
    }

    // ==================== 保留上限滚动清理 ====================

    @Test
    void recordSnapshot_trimsOldestBeyondMax() {
        props.getHistory().setMaxPerNpc(3);
        service.recordSnapshot(10L, newState(1000L));
        service.recordSnapshot(10L, newState(2000L));
        service.recordSnapshot(10L, newState(3000L));
        service.recordSnapshot(10L, newState(4000L));
        // 保留最近 3 条（4000/3000/2000），最旧 1000 被删
        assertEquals(3, historyStore.size());
        assertTrue(historyStore.stream().noneMatch(h -> h.getGameSecond() == 1000L), "最旧快照应被滚动删除");
        assertTrue(historyStore.stream().anyMatch(h -> h.getGameSecond() == 4000L));
    }

    @Test
    void recordSnapshot_underMaxKeepsAll() {
        props.getHistory().setMaxPerNpc(5);
        service.recordSnapshot(10L, newState(1000L));
        service.recordSnapshot(10L, newState(2000L));
        assertEquals(2, historyStore.size());
    }

    // ==================== 读接口 ====================

    @Test
    void listHistory_returnsAscending() {
        service.recordSnapshot(10L, newState(3000L));
        service.recordSnapshot(10L, newState(1000L));
        service.recordSnapshot(10L, newState(2000L));
        List<ActorStateHistory> list = service.listHistory(10L, "special:5", null);
        assertEquals(3, list.size());
        assertEquals(1000L, list.get(0).getGameSecond());
        assertEquals(2000L, list.get(1).getGameSecond());
        assertEquals(3000L, list.get(2).getGameSecond());
    }

    @Test
    void listHistory_limitTakesRecentNAscending() {
        service.recordSnapshot(10L, newState(1000L));
        service.recordSnapshot(10L, newState(2000L));
        service.recordSnapshot(10L, newState(3000L));
        service.recordSnapshot(10L, newState(4000L));
        List<ActorStateHistory> list = service.listHistory(10L, "special:5", 2);
        assertEquals(2, list.size());
        assertEquals(3000L, list.get(0).getGameSecond());
        assertEquals(4000L, list.get(1).getGameSecond());
    }

    @Test
    void listHistory_invalidParamsEmpty() {
        assertTrue(service.listHistory(null, "special:5", null).isEmpty());
        assertTrue(service.listHistory(10L, "  ", null).isEmpty());
    }

    @Test
    void listHistory_onlyCurrentNpc() {
        service.recordSnapshot(10L, newState(1000L));
        ActorNpcState other = newState(1000L);
        other.setNpcRef("crowd:9");
        service.recordSnapshot(10L, other);
        List<ActorStateHistory> list = service.listHistory(10L, "crowd:9", null);
        assertEquals(1, list.size());
        assertEquals("crowd:9", list.get(0).getNpcRef());
    }
}
