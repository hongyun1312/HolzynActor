/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DB 只读浏览器服务（调试前端 DB 浏览页）。
 * <p>职责：对<b>白名单表</b>提供只读分页查询 + 关键字过滤 + 行数统计。安全策略：<ul>
 * <li>表名必须命中白名单（{@link #WHITELIST_TABLES}），杜绝任意表名注入；</li>
 * <li>列名经 JDBC {@code DatabaseMetaData} 实际取列，并与「敏感列黑名单」
 * （api_key/api_key_cipher/cipher/secret/password/embedding 等）求差集——敏感列永不展示；</li>
 * <li>关键字过滤对可展示文本列做 {@code LOWER(CAST(col AS VARCHAR)) LIKE ?}（仅本表安全列）。</li>
 * </ul></p>
 * <p>实现不依赖具体表结构（列名动态获取），表结构变更后仍可用。</p>
 */
@Service
public class DebugDbService {

    /** 允许浏览的表（白名单） */
    private static final Set<String> WHITELIST_TABLES = Set.of(
            "actor_project",
            // 九模块规范化重构 表名按九模块后缀规范化（actor_character→actor_profile 等）
            "actor_profile",
            "actor_profile_card",
            "actor_relation",
            "actor_memory",
            "actor_event",
            "actor_dialogue_message",
            "actor_dialogue",
            "actor_dialogue_member",
            "actor_dialogue_config",
            "actor_profile_ordinary",
            "actor_field_dict",
            "actor_crowd_runtime",
            "actor_plan",
            "actor_action_log",
            "actor_knowledge_doc",
            "actor_usage_log",
            "actor_world_clock",
            "actor_world_location",
            "actor_world_setting",
            "actor_scene",
            "actor_prompt_template",
            "actor_model_provider",
            "actor_ai_call_log",
            "actor_evolution",
            "actor_evolution_turn",
            "actor_evolution_participant",
            // ：补 B3 遗留缺表 actor_world_engine + 档案模块脑力配置表（原 actor_agent_profile）
            "actor_world_engine",
            "actor_profile_brain",
            // ： 目标系统表（原 actor_character_goal，调试浏览器可见）
            "actor_goal",
            // （工作流世界观生成）：引擎骨架三表（工作流定义/任务/节点尝试，调试浏览器可见）
            "actor_worldgen_workflow",
            "actor_worldgen_task",
            "actor_worldgen_node_attempt",
            // （记忆系统分层重构，）：L1 工作记忆/L3 核心记忆/归档 三表（调试浏览器可见）
            "actor_memory_working",
            "actor_memory_core",
            "actor_memory_archive",
            // S1（世界沙盘，）：沙盘场景/资产/状态 三表 + S3 摆放表（调试浏览器可见）
            "actor_sandbox_scene",
            "actor_sandbox_asset",
            "actor_sandbox_state",
            "actor_sandbox_placement",
            // 状态
            "actor_state",
            // 状态
            "actor_emotion_anchor",
            // 状态 + 全局调参覆盖表（调试浏览器可见）
            "actor_state_history",
            "actor_setting",
            // 实时行为决策系统：被打断任务记录表（打断与恢复载体，调试浏览器可见）
            "actor_interrupt_record",
            // 世界演化与游戏时间系统：每日世界帧体系三表（帧/信息包/扩散状态，调试浏览器可见）
            "actor_world_frame",
            "actor_info_packet",
            "actor_info_diffusion",
            // 记忆系统重构 /：项目记忆三表（编年史/实体状态/编年史归档，调试浏览器可见）
            "actor_world_chronicle",
            "actor_world_entity",
            "actor_world_chronicle_archive",
            // 宏观社会演化系统 /：宏观三表（快照/事件/入账流水，调试浏览器可见）
            "actor_world_macro_snapshot",
            "actor_world_macro_event",
            "actor_world_macro_delta",
            // 项目级 AI 助手（M1~M5）：会话/消息/干预留痕三表（调试浏览器可见）
            "actor_assistant_session",
            "actor_assistant_message",
            "actor_assistant_intervention",
            // 对谈模式（M1 对谈地基）：会话/消息/玩家档案/玩家-角色关系四表（调试浏览器可见）
            // 表名 actor_talk_*（设计偏差 2026-10-12：设计稿拟 actor_dialogue_*，但既有
            // 「对话」模块已占用 actor_dialogue / actor_dialogue_message 等表，故改用 talk 前缀区分）
            "actor_talk_session",
            "actor_talk_message",
            "actor_talk_player_profile",
            "actor_talk_player_relation",
            // 对谈模式（M4 记忆/世界书等价物，2026-10-13）：会话级世界书条目表（调试浏览器可见）
            "actor_talk_worldbook",
            // 对谈模式（M5 群聊，2026-10-13）：群聊成员表（场景化成员：在场 present + 拉人 manual，调试浏览器可见）
            "actor_talk_group_member"
    );

    /** 敏感列黑名单（永不展示；名称不区分大小写） */
    private static final Set<String> SENSITIVE_COLUMNS = Set.of(
            "api_key", "api_key_cipher", "cipher", "secret", "password",
            "api_key_secret", "embedding"
    );

    /** 默认每页条数 */
    private static final int DEFAULT_SIZE = 50;

    /** 最大每页条数（防拉爆） */
    private static final int MAX_SIZE = 200;

    private final JdbcTemplate jdbcTemplate;

    public DebugDbService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 白名单表（只读快照）。
     *
     * <p>开发者数据页要按「系统库 / 存档库」分别列自己的表，但浏览权限仍必须走同一份
     * 白名单——否则等于给调试面开了第二个入口，两处规则迟早漂移。</p>
     *
     * @return 白名单表名集合
     */
    public static Set<String> whitelistTables() {
        return WHITELIST_TABLES;
    }

    /**
     * 白名单表清单（含行数与列数，供左侧表清单展示）。
     *
     * @return 表信息列表 [{table,rows,columns}]
     */
    public List<Map<String, Object>> tables() {
        return tables(jdbcTemplate, WHITELIST_TABLES);
    }

    /**
     * 用指定连接源 + 指定白名单列表（开发者数据页按范围浏览时使用）。
     *
     * @param jdbc 连接源（目录库 / 某存档的世界库）
     *
     * @param whitelist 该范围允许浏览的表
     *
     * @return 表信息列表 [{table,rows,columns}]；表不存在时 rows/columns = -1
     */
    public List<Map<String, Object>> tables(JdbcTemplate jdbc, Set<String> whitelist) {
        List<Map<String, Object>> list = new ArrayList<>();
        List<String> sorted = new ArrayList<>(whitelist);
        sorted.sort(String::compareTo);
        for (String table : sorted) {
            try {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table", table);
                m.put("rows", count(jdbc, table));
                m.put("columns", safeColumns(jdbc, table).size());
                list.add(m);
            } catch (Exception e) {
                // 表不存在（旧库缺表）时跳过
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table", table);
                m.put("rows", -1);
                m.put("columns", -1);
                m.put("error", e.getMessage());
                list.add(m);
            }
        }
        return list;
    }

    /**
     * 只读分页浏览一张白名单表。
     *
     * @param table 表名（白名单）
     *
     * @param page 页码（从 1 起）
     *
     * @param size 每页条数（默认 50，上限 200）
     *
     * @param keyword 关键字（可空；对安全文本列做不区分大小写包含匹配）
     */
    public Map<String, Object> browse(String table, int page, int size, String keyword) {
        return browse(jdbcTemplate, WHITELIST_TABLES, table, page, size, keyword);
    }

    /**
     * 用指定连接源 + 白名单只读分页浏览一张表。
     *
     * @param jdbc 连接源（目录库 / 某存档的世界库）
     *
     * @param whitelist 该范围允许浏览的表
     *
     * @param table 表名（白名单）
     *
     * @param page 页码（从 1 起）
     *
     * @param size 每页条数（默认 50，上限 200）
     *
     * @param keyword 关键字（可空；对安全文本列做不区分大小写包含匹配）
     *
     * @return 分页结果 {table,page,size,total,columns,items}
     */
    public Map<String, Object> browse(JdbcTemplate jdbc, Set<String> whitelist, String table,
                                      int page, int size, String keyword) {
        String safeTable = requireWhitelist(whitelist, table);
        List<String> cols = safeColumns(jdbc, safeTable);
        int pageNo = Math.max(1, page);
        int pageSize = Math.min(Math.max(1, size), MAX_SIZE);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();

        long total = count(jdbc, safeTable);
        int from = (pageNo - 1) * pageSize;

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", cols))
                .append(" FROM ").append(safeTable);
        List<Object> params = new ArrayList<>();
        if (kw != null && !kw.isEmpty() && !cols.isEmpty()) {
            List<String> conds = new ArrayList<>();
            for (String c : cols) {
                conds.add("LOWER(CAST(" + c + " AS VARCHAR(1000))) LIKE ?");
                params.add("%" + kw.toLowerCase(Locale.ROOT) + "%");
            }
            sql.append(" WHERE ").append(String.join(" OR ", conds));
        }
        // 有主键列则按 id 倒序（最新在前），否则按全列排序兜底
        if (cols.contains("id")) {
            sql.append(" ORDER BY id DESC");
        }
        sql.append(" LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add(from);

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", safeTable);
        out.put("page", pageNo);
        out.put("size", pageSize);
        out.put("total", total);
        out.put("columns", cols);
        out.put("items", rows);
        return out;
    }

    /**
     * 表行数。
     *
     * @param jdbc 连接源
     *
     * @param table 表名（已白名单校验）
     *
     * @return 行数
     */
    private long count(JdbcTemplate jdbc, String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    /**
     * 表名白名单校验（未命中抛 400）。
     *
     * @param whitelist 白名单
     *
     * @param table 表名
     *
     * @return 规范化后的表名（小写）
     */
    private String requireWhitelist(Set<String> whitelist, String table) {
        if (table == null) {
            throw new BizException(400, "表名不能为空");
        }
        String lower = table.trim().toLowerCase(Locale.ROOT);
        if (!whitelist.contains(lower)) {
            throw new BizException(400, "表不在白名单：仅允许调试只读浏览指定表");
        }
        return lower;
    }

    /**
     * 取表的可展示安全列（JDBC 元数据取全列 − 敏感列黑名单）。
     *
     * @param jdbc 连接源
     *
     * @param table 表名（已白名单校验）
     *
     * @return 安全列名列表（升序，与库一致）
     */
    private List<String> safeColumns(JdbcTemplate jdbc, String table) {
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            Set<String> cols = new LinkedHashSet<>();
            try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, null)) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if (col != null) {
                        cols.add(col);
                    }
                }
            }
            return new ArrayList<>(filterSensitiveColumns(cols));
        } catch (SQLException e) {
            throw new BizException(500, "读取表列信息失败：" + e.getMessage());
        }
    }

    /**
     * 敏感列过滤（静态可测）：剔除 api_key/api_key_cipher/embedding 等敏感或超大连字段。
     *
     * @param columns 原始列名集合
     *
     * @return 过滤后的安全列集合（保持原顺序）
     */
    static Set<String> filterSensitiveColumns(Set<String> columns) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : columns) {
            if (c == null) {
                continue;
            }
            String lower = c.toLowerCase(Locale.ROOT);
            if (SENSITIVE_COLUMNS.contains(lower)) {
                continue;
            }
            out.add(c);
        }
        return out;
    }
}
