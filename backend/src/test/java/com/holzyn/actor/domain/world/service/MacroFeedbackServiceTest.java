package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import com.holzyn.actor.domain.world.repository.ActorInfoPacketRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 宏观回灌服务单元测试（宏观社会演化系统 M2，domain/world/service，同包）。
 * <p>职责：验证回灌路①（帧「社会宏观」板块——简报写入 active 帧 + 重大事件即时追加）
 * 与回灌路③（宏观事件 → 信息包：macro: 前缀/3 日有效期/全村皆知/扩散渠道）。</p>
 */
class MacroFeedbackServiceTest {

    private ActorWorldFrameRepository frameRepository;
    private ActorInfoPacketRepository packetRepository;
    private MacroStateService macroStateService;
    private MacroFeedbackService service;

    @BeforeEach
    void setUp() {
        frameRepository = Mockito.mock(ActorWorldFrameRepository.class);
        packetRepository = Mockito.mock(ActorInfoPacketRepository.class);
        macroStateService = Mockito.mock(MacroStateService.class);
        service = new MacroFeedbackService(frameRepository, packetRepository, macroStateService);
        when(packetRepository.save(any(ActorInfoPacket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(frameRepository.save(any(ActorWorldFrame.class))).thenAnswer(inv -> inv.getArgument(0));
        when(macroStateService.buildSummary(any(MacroState.class), anyString()))
                .thenReturn("第 6 游戏日宏观简报：人口 300 口；粮价 100 文/石。");
    }

    private ActorWorldMacroEvent event() {
        ActorWorldMacroEvent ev = new ActorWorldMacroEvent();
        ev.setId(42L);
        ev.setProjectId(100L);
        ev.setGameDay(5L);
        ev.setMacroType("economy");
        ev.setEventType("price_surge");
        ev.setSeverity(4);
        ev.setNarrative("本村集市粮行门前排起长队。");
        ev.setSource("ai");
        return ev;
    }

    /**
     * 回灌路③：宏观事件 → 信息包（frame_id=null 独立于帧包、info_id 带 macro: 前缀、
     * valid_until=事件日+3、渠道=集市传闻/茶馆闲谈、无可见性条件=全村皆知）。
     */
    @Test
    void onMacroEventCreatesInfoPacket() {
        service.onMacroEvent(100L, 5L, event(), "铁匠");
        ArgumentCaptor<ActorInfoPacket> captor = ArgumentCaptor.forClass(ActorInfoPacket.class);
        verify(packetRepository).save(captor.capture());
        ActorInfoPacket p = captor.getValue();
        assertEquals(100L, p.getProjectId());
        assertTrue(p.getInfoId().startsWith("macro:"));
        assertEquals("铁匠", p.getOrigin());
        assertEquals("本村集市粮行门前排起长队。", p.getContent());
        assertEquals(8L, p.getValidUntilDay());
        assertEquals("active", p.getStatus());
    }

    /**
     * 回灌路①：重大事件即时追加到 active 帧板块（保留既有简报）。
     */
    @Test
    void onMacroEventAppendsFrameSummary() {
        ActorWorldFrame frame = new ActorWorldFrame();
        frame.setId(1L);
        frame.setMacroSummaryJson("既有简报");
        when(frameRepository.findByProjectIdAndStatus(100L, "active")).thenReturn(List.of(frame));
        service.onMacroEvent(100L, 5L, event(), null);
        assertTrue(frame.getMacroSummaryJson().contains("既有简报"));
        assertTrue(frame.getMacroSummaryJson().contains("【宏观事件·第6日】"));
    }

    /**
     * 回灌路①：每旬/月简报写入 active 帧（无 active 帧跳过不报错）。
     */
    @Test
    void writeFrameSummaryWritesActiveFrame() {
        ActorWorldFrame frame = new ActorWorldFrame();
        frame.setId(2L);
        when(frameRepository.findByProjectIdAndStatus(100L, "active")).thenReturn(List.of(frame));
        MacroState st = new MacroState();
        st.setGameDay(9L);
        service.writeFrameSummary(100L, st, "faction");
        assertNotNull(frame.getMacroSummaryJson());
        assertTrue(frame.getMacroSummaryJson().contains("人口 300"));
        // 无 active 帧 → 跳过
        when(frameRepository.findByProjectIdAndStatus(100L, "active")).thenReturn(List.of());
        service.writeFrameSummary(100L, st, "daily");
        verify(frameRepository, Mockito.times(1)).save(any(ActorWorldFrame.class));
    }
}
