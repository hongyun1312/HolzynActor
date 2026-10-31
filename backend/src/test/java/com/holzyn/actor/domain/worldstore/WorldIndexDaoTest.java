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

package com.holzyn.actor.domain.worldstore;

import com.holzyn.actor.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存档索引 DAO（V1.1.2-beta.1 M2）——真 H2 库。
 *
 * <p>这个 DAO 承担"存档列表的全部数据来源"，它的失败方式都是用户可见的：
 * 少读一列 → 卡片显示 0；写错状态 → 点不开；对过渡态不兼容 → 整个列表 500。</p>
 *
 * <p>测三组：</p>
 * <ol>
 *   <li>CRUD 与状态迁移（收编插入 → 挂载 → 关闭回写 → 启动归零）；</li>
 *   <li>{@code coalesce} 语义：本次没读到摘要时<b>不能</b>把上次的好值擦成 NULL；</li>
 *   <li>过渡态（目录库还是旧单体库、没有 V101 的列）：读要能用，写要 503。</li>
 * </ol>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class WorldIndexDaoTest {

    private static WorldIndexDao newDao(String name) {
        DataSource ds = WorldStoreTestSupport.mem(name, WorldStoreTestSupport.REGISTRY);
        return new WorldIndexDao(ds);
    }

    @Test
    @DisplayName("收编插入 → 读回 → 字段与默认状态正确")
    void insertAndRead() {
        WorldIndexDao dao = newDao("index_insert");
        long id = dao.insertIndexRow("uid-1", "青禾村", 1L, WorldStatus.CLOSED, "1.1.2-beta.1", "{}", LocalDateTime.now());
        assertTrue(id > 0, "必须返回自增主键");

        List<WorldIndexDao.WorldIndexRow> rows = dao.readAll();
        assertEquals(1, rows.size());
        WorldIndexDao.WorldIndexRow row = rows.get(0);
        assertEquals("uid-1", row.worldUid());
        assertEquals("青禾村", row.name());
        assertEquals(WorldStatus.CLOSED, row.status());
        assertEquals("1.1.2-beta.1", row.schemaVersion());
        assertNotNull(row.createdAt());
    }

    @Test
    @DisplayName("状态迁移：closed → mounted（记最近打开）→ closed（回写摘要）")
    void statusTransitions() {
        WorldIndexDao dao = newDao("index_status");
        long id = dao.insertIndexRow("uid-2", "浮生小镇", 1L, WorldStatus.CLOSED, null, null, LocalDateTime.now());

        LocalDateTime opened = LocalDateTime.now();
        dao.markMounted(id, "uid-2", opened);
        WorldIndexDao.WorldIndexRow mounted = dao.readById(id).orElseThrow();
        assertEquals(WorldStatus.MOUNTED, mounted.status());
        assertNotNull(mounted.lastOpenedAt(), "挂载必须记下最近打开时间（列表页「最后游玩」）");

        dao.markClosed(id, new WorldIndexDao.ClosedSummary(LocalDateTime.now(), 32_628_096_000L, 24L, 4096L, "{}", "1.1.2-beta.1"));
        WorldIndexDao.WorldIndexRow closed = dao.readById(id).orElseThrow();
        assertEquals(WorldStatus.CLOSED, closed.status());
        assertNotNull(closed.lastClosedAt());
        assertEquals(32_628_096_000L, closed.gameSecond());
        assertEquals(24L, closed.characterCount());
        assertEquals(4096L, closed.sizeBytes());
    }

    @Test
    @DisplayName("摘要回写用 coalesce：本次读不到（null）不得擦掉上次的值")
    void closedSummaryKeepsPreviousValues() {
        WorldIndexDao dao = newDao("index_coalesce");
        long id = dao.insertIndexRow("uid-3", "保护世界", 1L, WorldStatus.CLOSED, null, null, LocalDateTime.now());
        dao.markClosed(id, new WorldIndexDao.ClosedSummary(LocalDateTime.now(), 777L, 9L, 100L, "{}", "v1"));

        // 第二次关闭时摘要全部读失败（世界库损坏/被外部改过）
        dao.markClosed(id, new WorldIndexDao.ClosedSummary(LocalDateTime.now(), null, null, null, null, null));
        WorldIndexDao.WorldIndexRow row = dao.readById(id).orElseThrow();
        assertEquals(777L, row.gameSecond(), "世界时间被擦成 NULL，用户会以为世界回到开局");
        assertEquals(9L, row.characterCount());
        assertEquals(100L, row.sizeBytes());
    }

    @Test
    @DisplayName("启动归零：残留的 mounted 全部改回 closed")
    void resetMountedOnStartup() {
        WorldIndexDao dao = newDao("index_reset");
        long id = dao.insertIndexRow("uid-4", "崩溃残留", 1L, WorldStatus.CLOSED, null, null, LocalDateTime.now());
        dao.markMounted(id, "uid-4", LocalDateTime.now());
        assertEquals(1, dao.resetMountedOnStartup(LocalDateTime.now()));
        assertEquals(WorldStatus.CLOSED, dao.readById(id).orElseThrow().status());
    }

    @Test
    @DisplayName("状态标记：missing / incompatible 可写入并读回（收编用）")
    void markStatus() {
        WorldIndexDao dao = newDao("index_mark");
        long id = dao.insertIndexRow("uid-5", "目录被挪走", 1L, WorldStatus.CLOSED, null, null, LocalDateTime.now());
        dao.markStatus(id, WorldStatus.MISSING, LocalDateTime.now());
        assertEquals(WorldStatus.MISSING, dao.readById(id).orElseThrow().status());
        dao.markStatus(id, WorldStatus.INCOMPATIBLE, LocalDateTime.now());
        assertEquals(WorldStatus.INCOMPATIBLE, dao.readById(id).orElseThrow().status());
    }

    @Test
    @DisplayName("schema 齐全：missingColumns 为空，写路径不报 503")
    void registryHasAllIndexColumns() {
        WorldIndexDao dao = newDao("index_columns");
        assertTrue(dao.missingColumns().isEmpty(), "V101 之后目录库不应缺列，缺=" + dao.missingColumns());
    }

    @Test
    @DisplayName("过渡态（旧单体库，无 V101 列）：读能工作、写抛 503")
    void transitionStateToleratesMissingColumns() {
        DataSource legacy = WorldStoreTestSupport.mem("index_legacy", "classpath:db/legacy");
        WorldStoreTestSupport.exec(legacy,
                "insert into actor_project (user_id, name, project_uid) values (1, '旧世界', 'uid-legacy')");
        WorldIndexDao dao = new WorldIndexDao(legacy);

        assertFalse(dao.missingColumns().isEmpty(), "旧库本就没有索引列");
        List<WorldIndexDao.WorldIndexRow> rows = dao.readAll();
        assertEquals(1, rows.size(), "旧库上的存档列表必须还能读出来（否则 M2 接线后列表直接 500）");
        assertEquals("uid-legacy", rows.get(0).worldUid());
        assertEquals(WorldStatus.CLOSED, rows.get(0).status(), "缺列时状态应回退 closed");
        assertNull(rows.get(0).gameSecond());

        BizException e = assertThrows(BizException.class,
                () -> dao.insertIndexRow("uid-new", "新世界", 1L, WorldStatus.CLOSED, null, null, LocalDateTime.now()));
        assertEquals(503, e.getCode(), "写不了就必须明确说「去跑迁移」，而不是抛一个 Column not found");
        assertTrue(e.getMessage().contains("V101"), e.getMessage());
    }
}
