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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求上下文判定（{@link WorldRequestContextFilter#isGlobalPath}）的路径口径测试。
 *
 * <p>为什么值得单独一个测试：这里的判定错了不会编译失败，运行期表现是"某个页面 500：
 * 表不存在"——M5 把世界内接口改名到 {@code /api/worlds/{id}/...} 之后，前缀级判定曾把所有
 * 世界内请求都判成"全局"，`GET /api/worlds/1/world-clock` 直接打在目录库上，500
 * {@code Table "actor_world_clock" not found}。这类错误只有真跑起来才会撞到，必须留网。</p> *
 * <p>规则一句话：<b>目录库自己能回答的（生命周期）算全局，世界库才有的（世界内接口）不算</b>。
 * 判错成世界内 → 路由到世界库（世界库确实有那些表，能跑）；判错成全局 → 必然 500。</p>
 *
 * <p>所属模块：test/domain/worldstore</p>
 */
class WorldRequestContextFilterTest {

    @Test
    @DisplayName("存档生命周期路径 → 全局（只读目录库）")
    void lifecyclePathsAreGlobal() {
        String[] globals = {
                "/api/worlds",              // 列表 / 新建
                "/api/worlds/",             // 带斜杠的列表
                "/api/worlds/current",      // 当前挂载
                "/api/worlds/1",            // 详情 / 改名 / 归档删除
                "/api/worlds/1/open",
                "/api/worlds/1/close",
                "/api/worlds/1/manifest",
                "/api/worlds/1/export",     // M6：导出读磁盘目录 + 目录库索引，不走世界库路由
                "/api/worlds/import"
        };
        for (String uri : globals) {
            assertTrue(WorldRequestContextFilter.isGlobalPath(request(uri)),
                    "应判为全局（目录库）：" + uri);
        }
    }

    @Test
    @DisplayName("世界内接口 → 非全局（必须打到世界库，否则 500 表不存在）")
    void worldScopedPathsAreNotGlobal() {
        String[] worldScoped = {
                "/api/worlds/1/world-clock",                       // M5 实测踩过：曾被判成全局 → 500
                "/api/worlds/1/characters",
                "/api/worlds/1/characters/7/goals",
                "/api/worlds/1/memories",
                "/api/worlds/1/knowledge-docs",
                "/api/worlds/1/scenes",
                "/api/worlds/1/timeline",
                "/api/worlds/1/world-setting",
                "/api/worlds/1/ordinary-npcs",
                "/api/worlds/parse/parse",                         // M6：文件解析建世界（确认创建后要写世界库）
                "/api/worlds/parse/confirm",
                "/api/worlds/1/1/open"                              // 多段数字不是生命周期形态
        };
        for (String uri : worldScoped) {
            assertFalse(WorldRequestContextFilter.isGlobalPath(request(uri)),
                    "不应判为全局（会打到目录库）：" + uri);
        }
    }

    @Test
    @DisplayName("非 /api/worlds 路径一律不受影响（普通世界接口与静态资源）")
    void otherPathsAreUntouched() {
        for (String uri : new String[]{"/api/local-account/me", "/world/1/sandbox", "/", "/api/legacy-prefix"}) {
            assertFalse(WorldRequestContextFilter.isGlobalPath(request(uri)), "不该判为全局：" + uri);
        }
    }

    /** 构造只带 URI 的请求（{@code getRequestURI} 即真实路径，不带 query） */
    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        return req;
    }
}
