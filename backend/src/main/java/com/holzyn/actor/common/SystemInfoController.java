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

package com.holzyn.actor.common;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统信息控制器（只读）。
 * <p>职责：提供 GET /api/system/schema —— 返回数据库迁移状态（Flyway 应用到的最大版本 +
 * schema 元数据表中的 schema_version），供桌面端更新机制/冒烟验证确认「迁移已就绪、
 * 后端 schema 与实体一致」；数据全部只读，不涉及任何业务写操作。</p>
 */
@RestController
@RequestMapping("/api/system")
public class SystemInfoController {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoController.class);

    private final JdbcTemplate jdbcTemplate;
    private final Flyway flyway;

    public SystemInfoController(JdbcTemplate jdbcTemplate, Flyway flyway) {
        this.jdbcTemplate = jdbcTemplate;
        this.flyway = flyway;
    }

    /**
     * 数据库 schema 迁移状态。
     *
     * @return { appliedVersion, success, schemaVersion, meta }
     */
    @GetMapping("/schema")
    public R<Map<String, Object>> schema() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            body.put("appliedVersion", flyway.info().current() != null
                    ? flyway.info().current().getVersion() == null
                        ? null : flyway.info().current().getVersion().getVersion()
                    : null);
            body.put("success", flyway.info().current() == null
                    || flyway.info().current().getState().isApplied());
            body.put("pending", flyway.info().pending().length);
        } catch (Exception e) {
            // 老库从未跑过 Flyway（远程/预置库）时，info() 可能异常；降级为元数据表查询
            log.debug("[system] flyway.info 不可用: {}", e.getMessage());
            body.put("appliedVersion", null);
            body.put("success", false);
            body.put("pending", 0);
        }
        try {
            String v = jdbcTemplate.queryForObject(
                    "select meta_value from actor_schema_meta where meta_key = 'schema_version'",
                    String.class);
            body.put("schemaVersion", v);
        } catch (Exception e) {
            // actor_schema_meta 不存在（V2 前的旧库 / remote MySQL）→ 返回 null
            body.put("schemaVersion", null);
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select meta_key, meta_value, updated_at from actor_schema_meta order by meta_key");
            body.put("meta", rows);
        } catch (Exception e) {
            body.put("meta", List.of());
        }
        return R.ok(body);
    }
}
