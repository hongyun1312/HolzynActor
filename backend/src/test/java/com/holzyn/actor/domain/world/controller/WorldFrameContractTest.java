package com.holzyn.actor.domain.world.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holzyn.actor.domain.world.service.WorldFrameService;
import com.holzyn.actor.domain.world.vo.WorldFrameVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/projects/{id}/world-frame} 契约测试（UI V2.0 §8.4，2026-10-15）。
 *
 * <p><b>为什么必须有这个测试</b>：该接口原设计「无 active 帧 → 404」，是采集轮里唯一的 4xx
 * 来源（每轮 6 条 DevTools 红字）。契约改判为「<b>恒 200</b> + {@code generated} 显式字段」后，
 * 前端不再靠 catch 404 判断"世界还没生成世界帧"，而是读字段——一旦有人把这段改回抛异常，
 * 或把 Lombok 字段改名（wire 名变了前端就读不到），本测试必须失败。</p>
 *
 * <p><b>为什么不启 Spring</b>：本仓库 253 个测试类约定不加载 Spring 上下文（沿
 * {@code WorldConfigYamlTest} 先例），故直接构造 Controller 并 pin Jackson 的 wire 字段名。</p>
 *
 * <p>所属模块：test/domain/world（接口契约）。</p>
 */
class WorldFrameContractTest {

    private final WorldFrameService service = mock(WorldFrameService.class);
    private final WorldFrameController controller = new WorldFrameController(service);
    /** 与生产同源：application.yml 的 spring.jackson.default-property-inclusion=non_null */
    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** 未生成：控制器必须正常返回（不抛异常 = Spring 映射为 200），而不是 404。 */
    @Test
    @DisplayName("无 active 帧：GET /world-frame 返回 200 载荷（generated=false + hint），不再抛 404")
    void current_noFrame_returnsPendingNotThrows() {
        WorldFrameVO pending = new WorldFrameVO();
        pending.setGenerated(false);
        pending.setProjectId(1L);
        pending.setHint(WorldFrameService.HINT_NO_FRAME);
        when(service.getActiveFrame(1L)).thenReturn(pending);

        WorldFrameVO vo = assertDoesNotThrow(() -> controller.current(1L),
                "未生成世界帧是正常状态，控制器不得抛异常（抛异常会被映射为 4xx）");
        assertEquals(Boolean.FALSE, vo.getGenerated());
        assertEquals(1L, vo.getProjectId(), "未生成时也要能归位到项目（前端单页多项目复用）");
        assertEquals(WorldFrameService.HINT_NO_FRAME, vo.getHint(), "必须给出人类可读原因，前端直接展示");
        assertNull(vo.getFrameId(), "未生成时不得伪造帧字段");
    }

    /** 线格式（wire）字段名是前后端唯一契约：前端读 res.generated / res.hint。 */
    @Test
    @DisplayName("JSON 线格式：generated / hint 字段名固定（前端据 generated 走未生成分支）")
    void wireFormat_pinsGeneratedAndHint() throws Exception {
        WorldFrameVO pending = new WorldFrameVO();
        pending.setGenerated(false);
        pending.setProjectId(7L);
        pending.setHint(WorldFrameService.HINT_NO_FRAME);
        String pendingJson = mapper.writeValueAsString(pending);
        assertTrue(pendingJson.contains("\"generated\":false"),
                "未生成必须序列化为 generated:false（实际：" + pendingJson + "）");
        assertTrue(pendingJson.contains("\"hint\":"), "hint 必须在线上可达（实际：" + pendingJson + "）");
        assertFalse(pendingJson.contains("frameId"), "未生成不得带出帧数据（null 字段按 non_null 整体省略）");

        WorldFrameVO frame = new WorldFrameVO();
        frame.setFrameId(9L);
        String frameJson = mapper.writeValueAsString(frame);
        assertTrue(frameJson.contains("\"generated\":true"),
                "VO 默认即已生成（既有 toVO 路径不显式设也应为 true，实际：" + frameJson + "）");
        assertFalse(frameJson.contains("\"hint\""), "已生成时 hint 不应出现在线格式里（实际：" + frameJson + "）");
    }
}