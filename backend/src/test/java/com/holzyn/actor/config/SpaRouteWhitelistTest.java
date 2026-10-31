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

package com.holzyn.actor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由白名单守卫（V1.1.2-beta.1 M5）：前端「有页面的顶层路由」必须被 {@code SpaForwardController} 转发。
 *
 * <p>为什么需要它：Vue Router 的 history 模式在开发态由 Vite 的 SPA 回退兜住，路由随便加都能打开；
 * 只有打成 jar 之后，直接访问/刷新一个**没有登记**的 URL 才会得到 404 JSON。这类缺陷在开发态
 * 完全不可见，而且"加一条路由"看起来与"改后端白名单"毫无关系——正是最容易被忘掉的那类耦合。</p>
 *
 * <p>覆盖判定：{@code SpaForwardController#forwardPortal} 的 {@code @GetMapping} 字面量集合里
 * 存在同路径，或存在 {@code <前缀>/**} 且路径以 {@code <前缀>/} 开头。</p>
 *
 * <p>只校验"有 component 的顶层路由"：纯 {@code redirect} 路由（如 {@code /chat → /}）不登记也只在
 * 直连旧地址时 404，且它们本就是历史收敛项；把它们也纳入会让白名单被一批"立刻跳走"的路径撑大。</p>
 *
 * <p>所属模块：test/config（跨模块治理守卫）</p>
 */
class SpaRouteWhitelistTest {

    /** 前端路由定义文件（surefire 工作目录是 backend 模块） */
    private static final Path ROUTER_FILE = Path.of("../frontend/src/shared/router/index.js");

    /** SPA 回退控制器源码 */
    private static final Path SPA_CONTROLLER =
            Path.of("src/main/java/com/holzyn/actor/common/SpaForwardController.java");

    /** 顶层路由条目起始行：`  {` 或 `  { path: '...'` */
    private static final Pattern PATH_LITERAL = Pattern.compile("^ {2,4}(?:\\{\\s*)?path: '([^']+)'");

    /** 控制器里的 GetMapping 路径字面量（`"forward:/index.html"` 不以 / 开头，天然被排除） */
    private static final Pattern MAPPING_LITERAL = Pattern.compile("\"(/[^\"]*)\"");

    /**
     * 白名单里允许存在、但前端并没有"页面路由"的条目（显式列出，便于审阅为什么不删）。
     *
     * <ul>
     *   <li>{@code /login}、{@code /register}：登录注册已随无登录化移除，保留转发兼容历史直达 URL；</li>
     *   <li>{@code /admin}、{@code /admin/**}：管理后台（未在本仓库前端实现）；</li>
     *   <li>{@code /worlds}、{@code /worlds/**}：多存档列表的前身路径（现由 {@code /} 存档选单承担，
     *       保留是为了旧书签不 404）；</li>
     *   <li>{@code /settings/**}：{@code /settings} 的子页由前端设置页内部导航承担，后端只需一个前缀。</li>
     * </ul>
     */
    private static final Set<String> ALLOWED_EXTRA_MAPPINGS = Set.of(
            "/login", "/register", "/admin", "/admin/**",
            "/worlds", "/worlds/**", "/settings/**",
            // 调试前端（debug-frontend 独立应用，hash 路由）：两个入口路径都由 SpaForwardController 转发
            "/debug", "/debug/");

    @Test
    @DisplayName("每个有页面的顶层前端路由都被 SPA 回退覆盖（否则刷新/收藏夹直达 404）")
    void everyTopLevelPageRouteIsForwarded() throws IOException {
        Set<String> pages = topLevelPageRoutes();
        assertTrue(pages.size() >= 6, "解析到的顶层页面路由过少（解析器可能失效）：" + pages);
        assertTrue(pages.contains("/world/:id") && pages.contains("/world/new"),
                "解析器必须能读到 /world/:id 与 /world/new：实际=" + pages);

        Set<String> mappings = forwardedMappings();
        List<String> missing = new ArrayList<>();
        for (String page : pages) {
            if (!covered(page, mappings)) {
                missing.add(page);
            }
        }
        assertTrue(missing.isEmpty(),
                "以下前端路由未被 SpaForwardController 转发（直连/刷新会 404）：" + missing + "\n现有转发清单：" + mappings);
    }

    @Test
    @DisplayName("旧 /project(s) 不再出现在转发清单里（设计 §3.13：只改对外词汇，旧路径不保留）")
    void legacyProjectMappingsAreGone() throws IOException {
        Set<String> mappings = forwardedMappings();
        assertFalse(mappings.contains("/project") || mappings.contains("/projects")
                        || mappings.stream().anyMatch(m -> m.startsWith("/project/")),
                "SpaForwardController 仍在转发旧路由：" + mappings);
    }

    @Test
    @DisplayName("转发清单里没有来历不明的条目（新增项必须同步登记到白名单说明）")
    void noUnknownExtraMappings() throws IOException {
        Set<String> pages = topLevelPageRoutes();
        List<String> unknown = new ArrayList<>();
        for (String m : forwardedMappings()) {
            if (coveredByPage(m, pages) || ALLOWED_EXTRA_MAPPINGS.contains(m)) {
                continue;
            }
            unknown.add(m);
        }
        assertTrue(unknown.isEmpty(),
                "以下转发条目既不对应前端页面，也不在 ALLOWED_EXTRA_MAPPINGS 里（清理或登记说明）：" + unknown);
    }

    /**
     * 解析前端路由文件里"带 component 的顶层路由"。
     *
     * @return 绝对路径字面量集合（保持定义顺序）
     */
    private static Set<String> topLevelPageRoutes() throws IOException {
        assertTrue(Files.isRegularFile(ROUTER_FILE), "找不到前端路由文件：" + ROUTER_FILE.toAbsolutePath());
        String[] lines = Files.readString(ROUTER_FILE, StandardCharsets.UTF_8).split("\n", -1);
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = PATH_LITERAL.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            String path = m.group(1);
            if (!path.startsWith("/")) {
                continue; // 子路由用相对路径，不需要后端转发
            }
            if (blockHasComponent(lines, i)) {
                out.add(path);
            }
        }
        return out;
    }

    /** 当前行往后 5 行内出现 component: 视为"有页面"（路由对象都很短，条目之间不会互相干扰） */
    private static boolean blockHasComponent(String[] lines, int from) {
        for (int i = from; i < Math.min(lines.length, from + 6); i++) {
            if (lines[i].contains("component:")) {
                return true;
            }
            // 遇到下一个顶层路由条目就停（说明当前对象已结束）
            if (i > from && PATH_LITERAL.matcher(lines[i]).find()) {
                return false;
            }
        }
        return false;
    }

    /** 解析 SpaForwardController 里 @GetMapping 的路径字面量 */
    private static Set<String> forwardedMappings() throws IOException {
        assertTrue(Files.isRegularFile(SPA_CONTROLLER), "找不到 SPA 控制器：" + SPA_CONTROLLER.toAbsolutePath());
        String src = Files.readString(SPA_CONTROLLER, StandardCharsets.UTF_8);
        Set<String> out = new LinkedHashSet<>();
        Matcher m = MAPPING_LITERAL.matcher(src);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 页面路由是否被转发清单覆盖 */
    private static boolean covered(String page, Set<String> mappings) {
        if (mappings.contains(page)) {
            return true;
        }
        for (String m : mappings) {
            if (!m.endsWith("/**")) {
                continue;
            }
            String prefix = m.substring(0, m.length() - 3);
            if (page.equals(prefix) || page.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 转发条目是否"由某个页面路由派生"（精确同路径、前缀通配，或它本身就是某页面的父段） */
    private static boolean coveredByPage(String mapping, Set<String> pages) {
        if (pages.contains(mapping)) {
            return true;
        }
        if (mapping.endsWith("/**")) {
            String prefix = mapping.substring(0, mapping.length() - 3);
            return pages.stream().anyMatch(p -> p.equals(prefix) || p.startsWith(prefix + "/"));
        }
        // 父段转发：/world 之于 /world/:id（Spring 里两条都要登记，"/world" 不接受子路径）
        String base = mapping.endsWith("/") ? mapping : mapping + "/";
        return pages.stream().anyMatch(p -> p.startsWith(base));
    }
}
