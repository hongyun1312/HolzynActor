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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPA 深链转发白名单测试。
 *
 * <p>职责：锁定 {@link SpaForwardController} 必须登记的顶层前端路由。</p>
 *
 * <p>为什么值得一条测试：开发态由 Vite 的 history 回退兜住，**只有打到 jar 的生产态**
 * 才会暴露「直接访问 /tutorial 返回 404 JSON」这类问题（布局重构批次③ 就真实踩到过一次）。
 * 用反射读注解断言白名单，可以在不启动 Spring 上下文的前提下把这条约定钉住。</p>
 */
class SpaForwardControllerTest {

    /** 取出 forwardPortal 方法上 @GetMapping 的路径列表 */
    private String[] portalPaths() throws Exception {
        Method m = SpaForwardController.class.getMethod("forwardPortal");
        return m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value();
    }

    /** 所有现存顶层前端路由都要在白名单里（漏一个 = 该页面刷新即 404） */
    @Test
    void portalWhitelistCoversTopLevelRoutes() throws Exception {
        String[] paths = portalPaths();
        for (String required : new String[]{
                "/login", "/register", "/projects", "/project", "/project/**", "/admin", "/admin/**",
                "/settings", "/settings/**", "/account", "/onboarding", "/tutorial"}) {
            assertTrue(contains(paths, required), "SPA 白名单缺少顶层路由：" + required);
        }
    }

    /** 返回值必须是 forward:/index.html（写错前缀会变成渲染字面量或 404） */
    @Test
    void portalForwardsToIndex() throws Exception {
        SpaForwardController controller = new SpaForwardController();
        assertEquals("forward:/index.html", controller.forwardPortal());
        assertEquals("forward:/debug/index.html", controller.forwardDebug());
        assertEquals("forward:/index.html", controller.forwardHome());
    }

    /** 调试台入口两个变体都要登记（/debug 与 /debug/） */
    @Test
    void debugEntryHasBothVariants() throws Exception {
        Method m = SpaForwardController.class.getMethod("forwardDebug");
        String[] paths = m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value();
        assertArrayEquals(new String[]{"/debug", "/debug/"}, paths);
    }

    /** 数组包含判定 */
    private boolean contains(String[] arr, String v) {
        for (String s : arr) {
            if (s.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
