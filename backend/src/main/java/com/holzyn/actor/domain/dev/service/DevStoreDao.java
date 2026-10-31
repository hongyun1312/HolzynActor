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

package com.holzyn.actor.domain.dev.service;

import com.holzyn.actor.common.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开发者模式的持久化入口（参数覆盖 + 操作审计），落在<b>目录库</b>。
 *
 * <p><b>为什么不用 JPA 实体/仓库</b>（重要，踩过）：本应用是「单活动世界 + 路由数据源」架构——
 * 默认 {@code DataSource} 会随当前挂载世界切换到该世界的库，只有 {@code registryDataSource}
 * 永远指向目录库。开发者模式的参数是**进程级**的（改了影响整个 JVM 的行为，且覆盖文件也是全局一份），
 * 若挂在路由数据源上就会出现「挂了世界 1 时表不存在 / 换个世界参数又不见了」的漂移。
 * 因此这里显式 {@code @Qualifier("registryDataSource")} 直连目录库，表在
 * {@code db/migration/global/V102__dev_console.sql}。</p>
 *
 * <p><b>为什么用 JdbcTemplate 而不是再开一个 EntityManagerFactory</b>：为两张独立小表
 * 引一套 JPA 持久化单元，代价（启动时间、连接池、实体扫描风险）远大于收益；
 * 这里只有 8 条固定 SQL，用原生 SQL 反而让「写的是哪张表、哪一列」一目了然。</p>
 *
 * <p><b>写入纪律</b>：本类是 {@code actor_dev_param} / {@code actor_dev_audit} 的唯一写入者，
 * 其他模块只读（AGENTS §8.1.4）。</p>
 *
 * <p>所属模块：domain/dev（开发者模式控制台）</p>
 */
@Slf4j
@Component
public class DevStoreDao {

    private final JdbcTemplate jdbc;

    /**
     * 构造：绑定目录库数据源。
     *
     * @param registryDataSource 目录库（holzyn-registry）数据源
     */
    public DevStoreDao(@Qualifier("registryDataSource") DataSource registryDataSource) {
        this.jdbc = new JdbcTemplate(registryDataSource);
    }

    // ==================== 参数覆盖 ====================

    /**
     * 全部覆盖（键 → 值，键升序）。
     *
     * @return 覆盖映射（无覆盖返回空 Map）
     */
    public Map<String, String> overrides() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "select param_key, param_value from actor_dev_param order by param_key")) {
            out.put(String.valueOf(row.get("param_key")), row.get("param_value") == null ? "" : String.valueOf(row.get("param_value")));
        }
        return out;
    }

    /**
     * 覆盖行（含生效方式；「已改参数」页与待重启统计用）。
     *
     * @return 行列表 [{key, value, effect}]
     */
    public List<Map<String, Object>> overrideRows() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "select param_key, param_value, effect from actor_dev_param order by param_key")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", String.valueOf(row.get("param_key")));
            m.put("value", row.get("param_value") == null ? "" : String.valueOf(row.get("param_value")));
            m.put("effect", row.get("effect") == null ? "" : String.valueOf(row.get("effect")));
            out.add(m);
        }
        return out;
    }

    /**
     * 是否存在某个键的覆盖。
     *
     * @param key 参数键
     *
     * @return true=已覆盖
     */
    public boolean exists(String key) {
        Integer n = jdbc.queryForObject("select count(*) from actor_dev_param where param_key = ?", Integer.class, key);
        return n != null && n > 0;
    }

    /**
     * 覆盖总数。
     *
     * @return 条数
     */
    public int count() {
        Integer n = jdbc.queryForObject("select count(*) from actor_dev_param", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * 按生效方式统计条数（待重启 = restart）。
     *
     * @param effect 生效方式
     *
     * @return 条数
     */
    public int countByEffect(String effect) {
        Integer n = jdbc.queryForObject("select count(*) from actor_dev_param where effect = ?", Integer.class, effect);
        return n == null ? 0 : n;
    }

    /**
     * 写入（或更新）一条覆盖。
     *
     * @param key 参数键
     *
     * @param value 参数值
     *
     * @param effect 生效方式（immediate / restart）
     *
     * @param updatedBy 操作人
     */
    public void upsert(String key, String value, String effect, String updatedBy) {
        jdbc.update("merge into actor_dev_param (param_key, param_value, effect, updated_by, updated_at) "
                        + "key (param_key) values (?, ?, ?, ?, ?)",
                key, value, effect, updatedBy, Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * 删除一条覆盖。
     *
     * @param key 参数键
     *
     * @return 影响行数
     */
    public int delete(String key) {
        return jdbc.update("delete from actor_dev_param where param_key = ?", key);
    }

    /**
     * 清空全部覆盖。
     *
     * @return 影响行数
     */
    public int deleteAll() {
        return jdbc.update("delete from actor_dev_param");
    }

    // ==================== 审计 ====================

    /**
     * 追加一条审计（只增不改）。
     *
     * @param action 动作
     *
     * @param target 目标
     *
     * @param oldValue 旧值（超长会被截断，避免 varchar 溢出）
     *
     * @param newValue 新值
     *
     * @param effect 生效方式
     *
     * @param operator 操作人
     *
     * @param page 来源页面
     *
     * @param note 备注
     */
    public void insertAudit(String action, String target, String oldValue, String newValue, String effect,
                            String operator, String page, String note) {
        jdbc.update("insert into actor_dev_audit "
                        + "(action, target, old_value, new_value, effect, operator_name, page, note, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                truncate(action, 32), truncate(target, 200), truncate(oldValue, 4000), truncate(newValue, 4000),
                truncate(effect, 16), truncate(operator, 120), truncate(page, 120), truncate(note, 1000),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * 审计分页（id 倒序，即最新在前）。
     *
     * @param page 页码（从 1 起）
     *
     * @param size 每页条数
     *
     * @param action 动作过滤（可空）
     *
     * @param target 目标片段过滤（可空）
     *
     * @return 分页结果（每行含 10 个展示字段）
     */
    public PageResult<Map<String, Object>> auditPage(int page, int size, String action, String target) {
        int pageNo = Math.max(1, page);
        int pageSize = Math.min(Math.max(1, size), 200);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            where.append(" and action = ?");
            args.add(action);
        } else if (target != null && !target.isBlank()) {
            where.append(" and target like ?");
            args.add("%" + target + "%");
        }
        Integer totalObj = jdbc.queryForObject("select count(*) from actor_dev_audit" + where, Integer.class, args.toArray());
        long total = totalObj == null ? 0L : totalObj;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNo - 1) * pageSize);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "select id, action, target, old_value, new_value, effect, operator_name, page, note, created_at "
                        + "from actor_dev_audit" + where + " order by id desc limit ? offset ?",
                pageArgs.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("action", str(r.get("action")));
            m.put("target", str(r.get("target")));
            m.put("oldValue", str(r.get("old_value")));
            m.put("newValue", str(r.get("new_value")));
            m.put("effect", str(r.get("effect")));
            m.put("operator", str(r.get("operator_name")));
            m.put("page", str(r.get("page")));
            m.put("note", str(r.get("note")));
            Object created = r.get("created_at");
            m.put("createdAt", created == null ? "" : String.valueOf(created));
            rows.add(m);
        }
        return PageResult.of(rows, total, pageNo, pageSize);
    }

    /**
     * 空值安全转字符串。
     *
     * @param v 原始值
     *
     * @return 字符串（null → ""）
     */
    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * 按列宽截断（防止 H2 varchar 溢出把整个写入变成异常）。
     *
     * @param v 原始值
     *
     * @param max 最大长度
     *
     * @return 截断后的字符串（null → null）
     */
    private static String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }
}
