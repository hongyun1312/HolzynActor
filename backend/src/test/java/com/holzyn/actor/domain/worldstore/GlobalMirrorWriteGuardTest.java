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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局镜像写守卫的判定逻辑（V1.1.2-beta.1 M2）。
 *
 * <p>守卫的失效方式有两种，都要测：</p>
 * <ol>
 *   <li><b>该拦没拦</b>（漏判表名/漏剥注释）→ 配置被写进世界库镜像，重开世界时静默丢失；</li>
 *   <li><b>不该拦却拦了</b>（把普通世界表当镜像、或忘了 GLOBAL 豁免）→ 进世界后正常写操作全 500。
 *       第二种故障比第一种更"响"，但同样要避免。</li>
 * </ol>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class GlobalMirrorWriteGuardTest {

    private final MountedWorld mounted = new MountedWorld();

    private final GlobalMirrorWriteGuard guard = new GlobalMirrorWriteGuard(mounted);

    @AfterEach
    void clear() {
        mounted.unmount();
        WorldContext.clear();
    }

    @Test
    @DisplayName("SQL 解析：识别全部写动词与 schema 前缀/引号，SELECT 一律不认")
    void parsesWrittenTable() {
        assertEquals("actor_setting", GlobalMirrorWriteGuard.parseWrittenTable("insert into actor_setting (a) values (1)"));
        assertEquals("actor_setting", GlobalMirrorWriteGuard.parseWrittenTable("UPDATE \"actor_setting\" set a = 1"));
        assertEquals("actor_setting", GlobalMirrorWriteGuard.parseWrittenTable("update public.actor_setting set a = 1"));
        assertEquals("actor_setting", GlobalMirrorWriteGuard.parseWrittenTable("delete from actor_setting where id = 1"));
        assertEquals("actor_project", GlobalMirrorWriteGuard.parseWrittenTable("merge into actor_project key(id) values (1)"));
        assertEquals("sys_user", GlobalMirrorWriteGuard.parseWrittenTable("truncate table sys_user"));
        assertEquals("actor_setting", GlobalMirrorWriteGuard.parseWrittenTable(
                "/* load com.holzyn.actor.X */ update actor_setting set a = 1"));

        assertNull(GlobalMirrorWriteGuard.parseWrittenTable("select * from actor_setting"));
        assertNull(GlobalMirrorWriteGuard.parseWrittenTable("  select 1"));
        assertNull(GlobalMirrorWriteGuard.parseWrittenTable(null));
    }

    @Test
    @DisplayName("未挂载世界：任何写都放行，且不做 SQL 解析（快路径）")
    void passesWhenNoWorldMounted() {
        String sql = "insert into actor_setting (setting_key) values ('x')";
        assertEquals(sql, guard.inspect(sql), "未挂载时必须原样放行");
        assertEquals(0, guard.cachedStatements(), "未挂载时连解析都不该做：这是每一条语句都要过的热路径");
    }

    @Test
    @DisplayName("已挂载 + 世界上下文 + 镜像表 → 409 且消息指明表名与后果")
    void blocksMirrorWriteInWorldContext() {
        mounted.mount("uid-abc");
        BizException e = assertThrows(BizException.class,
                () -> guard.inspect("update actor_setting set setting_value = 'x'"));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("actor_setting"), "错误信息必须点出是哪张表：" + e.getMessage());
        assertTrue(e.getMessage().contains("uid-abc"), "错误信息必须点出是哪个世界：" + e.getMessage());
    }

    @Test
    @DisplayName("已挂载 + 显式 GLOBAL → 放行（全局配置只能在目录库写，但必须能写）")
    void passesGlobalContext() {
        mounted.mount("uid-abc");
        String sql = "update actor_model_provider set name = 'x'";
        try (WorldContext.Scope ignored = WorldContext.global()) {
            assertEquals(sql, guard.inspect(sql), "GLOBAL 上下文必须放行，否则设置页在世界打开后就瘫痪了");
        }
    }

    @Test
    @DisplayName("已挂载 + 世界表 → 放行（守卫只认 12 张镜像，不能误伤 101 张世界表）")
    void passesWorldTables() {
        mounted.mount("uid-abc");
        String sql = "update actor_profile set name = 'x' where project_id = 1";
        assertEquals(sql, guard.inspect(sql));
    }

    @Test
    @DisplayName("缓存：同一条语句只解析一次，且判定结果稳定")
    void cachesVerdict() {
        mounted.mount("uid-abc");
        String sql = "insert into actor_prompt_template (code) values ('x')";
        assertThrows(BizException.class, () -> guard.inspect(sql));
        int afterFirst = guard.cachedStatements();
        assertThrows(BizException.class, () -> guard.inspect(sql));
        assertEquals(afterFirst, guard.cachedStatements(), "第二次不应再新增缓存条目");
        assertTrue(afterFirst > 0, "首次判定必须写入缓存");
    }
}
