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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 废弃旧表/旧列清理（普通型人群重构的启动迁移）。
 * <p>职责：应用启动时（Hibernate ddl-auto=update 已建好新表之后）幂等清理已废弃的
 * 旧人群结构与旧分类字段——</p>
 * <ul>
 * <li>重构一：废弃 <code>actor_crowd</code>/ <code>actor_crowd_member</code>（人群组+程序化池子）；
 * 新结构为单表 <code>actor_ordinary_npc</code>。</li>
 * <li>重构二（分类体系重构）：废弃「AI 职业分类」——删表 <code>actor_crowd_category</code>、
 * 删除 <code>actor_ordinary_npc</code>的 category_l1/category_l2 旧列；
 * 分类改为由 AI 从 归属/职业/种族 中选 2 个字段聚合，字段标准数据存
 * <code>actor_npc_field_dict</code>，主/次分类字段存 <code>actor_crowd_runtime</code>。</li>
 * <li>（角色档案统一重构）：<code>actor_profile_ordinary</code>与
 * <code>actor_profile_card</code>物理废弃——普通型由 <code>actor_profile.type='common'</code>表达，
 * 档案并入 <code>actor_profile.persona_json</code>。这两张旧表不再清理/改写，仅在残留时告警一次。</li>
 * </ul>
 * <p>幂等：<code>DROP ... IF EXISTS</code>，首次执行清理，后续为空操作；失败不阻断启动（告警放行）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OldCrowdTableCleaner implements ApplicationRunner {

    private final DataSource dataSource;

    /**
     * 启动时清理废弃旧表与旧列。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            // 重构一：废弃人群组 + 程序化池子旧表
            st.execute("DROP TABLE IF EXISTS actor_crowd_member");
            st.execute("DROP TABLE IF EXISTS actor_crowd");
            // 重构二：废弃「AI 职业分类字典」表
            st.execute("DROP TABLE IF EXISTS actor_crowd_category");
            // ：actor_profile_ordinary（普通型 NPC 表）与 actor_profile_card（角色卡表）
            // 已随「角色档案统一重构」物理废弃——普通型改由 actor_profile.type='common' 表达，档案并入
            // actor_profile.persona_json。新基线库里这两张表不存在，故此处不再对它们执行 ALTER
            // （旧代码在此 ALTER 一张不存在的表，导致每次启动输出一条误导性 WARN）。
            // 仅做存在性探测：老库 仍带着这两张表残留，提示一次即可，不自动删除
            // （旧数据是"另一套 id 口径"的历史快照，静默 DROP 会连带丢掉唯一的旧人群副本）。
            warnIfLegacyTablePresent(conn, "actor_profile_ordinary", "普通型 NPC（G1a 前）");
            warnIfLegacyTablePresent(conn, "actor_profile_card", "角色卡（G1a 前）");
            // 不再输出清理成功 INFO（数据库结构统一由 DatabaseStructureValidator 校验并输出日志）
        } catch (Exception e) {
            // 清理失败不阻断启动（幂等，下次启动重试；仅记录）
            log.warn("[迁移] 清理废弃旧表失败（不影响启动，下次启动重试）: {}", e.getMessage());
        }
    }

    /**
     * 探测 之前的旧表是否残留（仅告警，不删除）。
     *
     * @param conn 数据库连接
     *
     * @param table 旧表名
     *
     * @param label 中文说明（日志可读性）
     */
    private void warnIfLegacyTablePresent(Connection conn, String table, String label) {
        try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, table, new String[]{"TABLE"})) {
            if (rs.next()) {
                log.warn("[迁移] 检测到 G1a 之前的旧表 {}（{}）——本版本已不再读写该表；"
                        + "如确认无需保留历史数据，可手工 DROP TABLE {}", table, label, table);
            }
        } catch (Exception e) {
            // 探测失败不影响启动
            log.debug("[迁移] 旧表探测失败（忽略）: {}", e.getMessage());
        }
    }
}
