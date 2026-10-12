package com.holzyn.actor.domain.unity.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.unity.model.UnityEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unity 对接协议编解码与校验单元测试（U 轨道 U1，domain/unity/service）。
 * <p>职责：验证——envelope JSON 编解码往返（含 payload/gameSecond/traceId）、
 * 非法 JSON/缺 type/空消息拒绝、上行类型白名单、traceId 生成。</p>
 */
class UnityProtocolTest {

    private UnityProtocolService protocol;

    @BeforeEach
    void setUp() {
        protocol = new UnityProtocolService();
    }

    @Test
    void decodeFullEnvelope() {
        String json = "{\"type\":\"clock_report\",\"seq\":7,\"projectId\":37,"
                + "\"gameSecond\":3600,\"payload\":{\"rate\":60},\"traceId\":\"ut-abc\"}";
        UnityEnvelope env = protocol.decode(json);
        assertEquals("clock_report", env.type());
        assertEquals(7L, env.seq());
        assertEquals(37L, env.projectId());
        assertEquals(3600L, env.gameSecond());
        assertEquals(60, ((Number) env.payload().get("rate")).intValue());
        assertEquals("ut-abc", env.traceId());
    }

    @Test
    void decodeMinimalEnvelope() {
        // 仅 type（seq/projectId/gameSecond/traceId 可空；payload 缺省空 Map）
        UnityEnvelope env = protocol.decode("{\"type\":\"heartbeat\"}");
        assertEquals("heartbeat", env.type());
        assertNull(env.seq());
        assertNull(env.projectId());
        assertNull(env.gameSecond());
        assertNotNull(env.payload());
        assertTrue(env.payload().isEmpty());
        assertNull(env.traceId());
    }

    @Test
    void decodeRejectsMissingType() {
        assertThrows(BizException.class, () -> protocol.decode("{\"seq\":1}"));
        assertThrows(BizException.class, () -> protocol.decode("{\"type\":\"\"}"));
    }

    @Test
    void decodeRejectsMalformed() {
        assertThrows(BizException.class, () -> protocol.decode("not-json"));
        assertThrows(BizException.class, () -> protocol.decode(""));
        assertThrows(BizException.class, () -> protocol.decode(null));
    }

    @Test
    void encodeRoundTrip() {
        UnityEnvelope env = new UnityEnvelope("clock", null, 37L, 7200L,
                Map.of("rate", 60), "ut-trace-1");
        String json = protocol.encode(env);
        UnityEnvelope back = protocol.decode(json);
        assertEquals("clock", back.type());
        assertEquals(37L, back.projectId());
        assertEquals(7200L, back.gameSecond());
        assertEquals("ut-trace-1", back.traceId());
    }

    @Test
    void upstreamTypeWhitelist() {
        assertTrue(protocol.isUpstreamType("hello"));
        assertTrue(protocol.isUpstreamType("heartbeat"));
        assertTrue(protocol.isUpstreamType("clock_report"));
        assertTrue(protocol.isUpstreamType("perception"));
        assertTrue(protocol.isUpstreamType("environment"));
        assertTrue(protocol.isUpstreamType("player_action"));
        assertTrue(protocol.isUpstreamType("intervention"));
        // 下行类型/未知类型不是上行白名单
        assertFalse(protocol.isUpstreamType("connected"));
        assertFalse(protocol.isUpstreamType("npc_talk"));
        assertFalse(protocol.isUpstreamType("unknown"));
        assertFalse(protocol.isUpstreamType(null));
    }

    @Test
    void newTraceIdFormat() {
        String id = protocol.newTraceId();
        assertNotNull(id);
        assertTrue(id.startsWith("ut-"));
        assertEquals(35, id.length()); // "ut-" + 32 位
    }

    @Test
    void decodePayloadList() {
        String json = "{\"type\":\"perception\",\"payload\":{\"targetNpcRefs\":[\"special:1\",\"special:2\"],"
                + "\"text\":\"玩家朝你走来\",\"broadcast\":true}}";
        UnityEnvelope env = protocol.decode(json);
        @SuppressWarnings("unchecked")
        List<String> refs = (List<String>) env.payload().get("targetNpcRefs");
        assertEquals(List.of("special:1", "special:2"), refs);
        assertEquals(true, env.payload().get("broadcast"));
    }
}
