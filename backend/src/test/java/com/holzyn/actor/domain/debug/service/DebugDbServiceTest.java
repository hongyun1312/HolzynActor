package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.common.BizException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DebugDbService 白名单/敏感列逻辑单元测试（2026-08-20 调试前端 DB 浏览）。
 * <p>职责：验证——非白名单表被拒（只读安全边界）、敏感列（api_key/embedding 等）被剔除。</p>
 * <p>说明：只测纯静态逻辑（不依赖数据库）；SQL 构造与元数据读取在运行时冒烟验证。</p>
 */
class DebugDbServiceTest {

    /**
     * 非白名单表名：抛 BizException（杜绝任意表名注入）。
     */
    @Test
    void nonWhitelistTableRejected() {
        // 构造 null JdbcTemplate：白名单校验发生在任何 SQL 之前，因此不触碰数据源
        DebugDbService service = new DebugDbService(null);
        assertThrows(BizException.class, () -> service.browse("sys_users", 1, 10, null));
        assertThrows(BizException.class, () -> service.browse("actor_project; DROP TABLE actor_project", 1, 10, null));
        assertThrows(BizException.class, () -> service.browse("", 1, 10, null));
        assertThrows(BizException.class, () -> service.browse(null, 1, 10, null));
    }

    /**
     * 白名单内的表名可通过校验（校验放行后才可能因 jdbcTemplate=null 走到 NPE）。
     */
    @Test
    void whitelistTablePassesValidation() {
        DebugDbService service = new DebugDbService(null);
        try {
            service.browse("actor_profile", 1, 10, null);
        } catch (NullPointerException npe) {
            // 校验放行后才会因 jdbcTemplate==null 走到 NPE —— 证明未被白名单拒绝 ✓
            return;
        } catch (BizException be) {
            // 不应因白名单被拒
            assertTrue(be.getMessage() == null || !be.getMessage().contains("白名单"),
                    "白名单表不应被白名单校验拒绝");
        }
    }

    /**
     * C1（2026-08-21）：新表/补表白名单同步——actor_profile_brain（脑力配置，原 actor_agent_profile，九模块重构 2026-09-19）与
     * actor_world_engine（世界引擎配置，B3 遗留缺表）均可在调试 DB 浏览（校验放行，不因白名单被拒）。
     */
    @Test
    void c1NewTablesInWhitelist() {
        DebugDbService service = new DebugDbService(null);
        for (String table : new String[]{"actor_profile_brain", "actor_world_engine"}) {
            try {
                service.browse(table, 1, 10, null);
            } catch (NullPointerException npe) {
                // 校验放行后才会因 jdbcTemplate==null 走到 NPE —— 证明未被白名单拒绝 ✓
                continue;
            } catch (BizException be) {
                throw new AssertionError("表 " + table + " 应在调试 DB 白名单内（校验被拒）", be);
            }
        }
    }

    /**
     * D2（2026-08-24）：新表白名单同步——actor_goal（F7 目标系统表，原 actor_character_goal，九模块重构 2026-09-19）可在调试 DB 浏览。
     */
    @Test
    void d2NewTablesInWhitelist() {
        DebugDbService service = new DebugDbService(null);
        for (String table : new String[]{"actor_goal"}) {
            try {
                service.browse(table, 1, 10, null);
            } catch (NullPointerException npe) {
                // 校验放行后才会因 jdbcTemplate==null 走到 NPE —— 证明未被白名单拒绝 ✓
                continue;
            } catch (BizException be) {
                throw new AssertionError("表 " + table + " 应在调试 DB 白名单内（校验被拒）", be);
            }
        }
    }

    /**
     * M1（工作流世界观生成）：引擎骨架三表（actor_worldgen_workflow/task/node_attempt）可在调试 DB 浏览。
     */
    @Test
    void m1WorldgenTablesInWhitelist() {
        DebugDbService service = new DebugDbService(null);
        for (String table : new String[]{"actor_worldgen_workflow", "actor_worldgen_task",
                "actor_worldgen_node_attempt"}) {
            try {
                service.browse(table, 1, 10, null);
            } catch (NullPointerException npe) {
                // 校验放行后才会因 jdbcTemplate==null 走到 NPE —— 证明未被白名单拒绝 ✓
                continue;
            } catch (BizException be) {
                throw new AssertionError("表 " + table + " 应在调试 DB 白名单内（校验被拒）", be);
            }
        }
    }

    /**
     * M4（NPC 状态模块）：状态历史快照表（actor_state_history）+ 全局调参覆盖表（actor_setting）
     * 可在调试 DB 浏览（白名单同步确认，曲线数据源/覆盖表调试可见）。
     */
    @Test
    void m4StateHistoryAndSettingInWhitelist() {
        DebugDbService service = new DebugDbService(null);
        for (String table : new String[]{"actor_state_history", "actor_setting"}) {
            try {
                service.browse(table, 1, 10, null);
            } catch (NullPointerException npe) {
                // 校验放行后才会因 jdbcTemplate==null 走到 NPE —— 证明未被白名单拒绝 ✓
                continue;
            } catch (BizException be) {
                throw new AssertionError("表 " + table + " 应在调试 DB 白名单内（校验被拒）", be);
            }
        }
    }

    /**
     * M2（实时行为决策系统 打断与恢复）：被打断任务记录表（actor_interrupt_record）
     * 可在调试 DB 浏览（白名单同步确认，打断恢复链路调试可见）。
     */
    @Test
    void m2InterruptRecordInWhitelist() {
        DebugDbService service = new DebugDbService(null);
        try {
            service.browse("actor_interrupt_record", 1, 10, null);
        } catch (NullPointerException npe) {
            // 校验放行后才会因 jdbcTemplate==null 走到 NPE —— 证明未被白名单拒绝 ✓
            return;
        } catch (BizException be) {
            throw new AssertionError("表 actor_interrupt_record 应在调试 DB 白名单内（校验被拒）", be);
        }
        throw new AssertionError("应因 jdbcTemplate==null 抛 NPE（白名单已放行）");
    }

    /**
     * M4（世界演化与游戏时间系统 每日世界帧体系）：三张新表（actor_world_frame / actor_info_packet /
     * actor_info_diffusion）可在调试 DB 浏览（白名单同步确认，帧/信息包/扩散状态调试可见）。
     */
    @Test
    void m4WorldFrameTablesInWhitelist() {
        DebugDbService service = new DebugDbService(null);
        for (String table : new String[]{"actor_world_frame", "actor_info_packet", "actor_info_diffusion"}) {
            try {
                service.browse(table, 1, 10, null);
            } catch (NullPointerException npe) {
                // 校验放行后才会因 jdbcTemplate==null 走到 NPE —— 证明未被白名单拒绝 ✓
                continue;
            } catch (BizException be) {
                throw new AssertionError("表 " + table + " 应在调试 DB 白名单内（校验被拒）", be);
            }
        }
    }

    /**
     * 敏感列过滤：api_key/api_key_cipher/embedding 等永不展示，普通列保留。
     */
    @Test
    void sensitiveColumnsFiltered() {
        Set<String> cols = new LinkedHashSet<>();
        cols.add("id");
        cols.add("name");
        cols.add("api_key");
        cols.add("api_key_cipher");
        cols.add("base_url");
        cols.add("embedding");
        cols.add("persona_json");

        Set<String> safe = DebugDbService.filterSensitiveColumns(cols);
        assertEquals(4, safe.size());
        assertTrue(safe.contains("id"));
        assertTrue(safe.contains("name"));
        assertTrue(safe.contains("base_url"));
        assertTrue(safe.contains("persona_json"));
        // 大小写不敏感（H2 DATABASE_TO_LOWER / MySQL 混合大小写均覆盖）
        Set<String> upper = Set.of("ID", "API_KEY_CIPHER", "BASE_URL");
        assertEquals(Set.of("ID", "BASE_URL"), DebugDbService.filterSensitiveColumns(upper));
    }

    /**
     * 宏观社会演化系统 M1/M2（2026-09-15）：宏观三表（actor_world_macro_snapshot /
     * actor_world_macro_event / actor_world_macro_delta）可在调试 DB 浏览（白名单同步确认，
     * 宏观快照/事件/入账流水调试可见）。
     */
    @Test
    void macroTablesInWhitelist() {
        DebugDbService service = new DebugDbService(null);
        for (String table : new String[]{"actor_world_macro_snapshot", "actor_world_macro_event",
                "actor_world_macro_delta"}) {
            try {
                service.browse(table, 1, 10, null);
            } catch (NullPointerException npe) {
                // 校验放行后才会因 jdbcTemplate==null 走到 NPE —— 证明未被白名单拒绝 ✓
                continue;
            } catch (BizException be) {
                throw new AssertionError("表 " + table + " 应在调试 DB 白名单内（校验被拒）", be);
            }
        }
    }

    /**
     * 项目级 AI 助手（2026-10-12 M5 收尾）：助手三表（actor_assistant_session / actor_assistant_message /
     * actor_assistant_intervention）可在调试 DB 浏览（白名单同步确认，会话/消息/干预留痕调试可见）。
     */
    @Test
    void assistantTablesInWhitelist() {
        DebugDbService service = new DebugDbService(null);
        for (String table : new String[]{"actor_assistant_session", "actor_assistant_message",
                "actor_assistant_intervention"}) {
            try {
                service.browse(table, 1, 10, null);
            } catch (NullPointerException npe) {
                // 校验放行后才会因 jdbcTemplate==null 走到 NPE —— 证明未被白名单拒绝 ✓
                continue;
            } catch (BizException be) {
                throw new AssertionError("表 " + table + " 应在调试 DB 白名单内（校验被拒）", be);
            }
        }
    }

    /**
     * 对谈模式（2026-10-12 M1 对谈地基 + 2026-10-13 M4/M5）：对谈六表（actor_talk_session /
     * actor_talk_message / actor_talk_player_profile / actor_talk_player_relation /
     * actor_talk_worldbook[M4] / actor_talk_group_member[M5]）可在调试 DB 浏览
     * （白名单同步确认，会话/消息/玩家档案/玩家-角色关系/世界书/群聊成员调试可见）。
     */
    @Test
    void dialogueTablesInWhitelist() {
        DebugDbService service = new DebugDbService(null);
        for (String table : new String[]{"actor_talk_session", "actor_talk_message",
                "actor_talk_player_profile", "actor_talk_player_relation", "actor_talk_worldbook",
                "actor_talk_group_member"}) {
            try {
                service.browse(table, 1, 10, null);
            } catch (NullPointerException npe) {
                // 校验放行后才会因 jdbcTemplate==null 走到 NPE —— 证明未被白名单拒绝 ✓
                continue;
            } catch (BizException be) {
                throw new AssertionError("表 " + table + " 应在调试 DB 白名单内（校验被拒）", be);
            }
        }
    }
}
