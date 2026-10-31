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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改名守卫（V1.1.2-beta.1 M5）：后端不得再出现旧前缀 {@code /api/projects} 与旧前端路由 {@code /project/}。
 *
 * <p>为什么需要源码扫描而不是"改完看一眼"：本轮是 300+ 处机械替换，漏一处不会编译失败，
 * 只会在真实请求时 404（{@code /api/projects/...} 已无映射）或把用户带进空页面（旧路由）。</p>
 *
 * <p>白名单（必须显式列出，宁可写死也不要"模糊放过"）：</p>
 * <ul>
 *   <li>{@code scene/project/}、{@code char/project/}、{@code working/project/}：AI 日志与记忆层
 *       的斜杠分隔词组（"场景/项目/模型"），不是路由；</li>
 *   <li>{@code db/legacy/**}、{@code docs/**}：历史 SQL 与文档（设计文档要引用旧路径讲清"改了什么"）。</li>
 * </ul>
 *
 * <p>前端 {@code frontend/src} 的同类扫描已并入本类（M5 前端批次）：{@code /api/projects} 与 {@code /project/}
 * 同样是零命中，只有 {@code features/project/} 这个<b>模块目录名</b>放行（Java 包名与前端目录名按设计 §3.13 保留旧名）。</p>
 *
 * <p>所属模块：test/config（跨模块治理守卫）</p>
 */
class ProjectRenameSweepTest {

    /** 旧 API 前缀（必须为零） */
    private static final String LEGACY_API_PREFIX = "/api/projects";

    /** 旧前端路由前缀（必须为零，除斜杠词组） */
    private static final String LEGACY_ROUTE_PREFIX = "/project/";

    /** 斜杠词组白名单：这些 `xxx/project/` 是日志口径，不是路由 */
    private static final Pattern WORD_SLASH_WHITELIST =
            Pattern.compile("(scene|char|working|core|model)/project/");

    /**
     * 前端模块路径白名单：{@code @/features/project/} 是<b>目录名</b>，不是路由。
     *
     * <p>M5 只改"对外词汇"（HTTP 路径与前端路由），Java 包名与前端目录名一律保留旧名（设计 §3.13）。
     * 所以 `@/features/project/ProjectLayout.vue` 这种 import 是正确的，不能要求它改成 world。</p>
     */
    private static final Pattern MODULE_PATH_WHITELIST =
            Pattern.compile("features/project/");

    /** 前端路由扫描的白名单：日志词组 + 模块目录名（两者都不是路由） */
    private static final Pattern FRONTEND_ROUTE_WHITELIST =
            Pattern.compile("(scene|char|working|core|model)/project/|features/project/");

    /** 前端源码根目录（surefire 的工作目录是 backend 模块，故用相对路径） */
    private static final Path FRONTEND_SRC = Path.of("../frontend/src");

    /**
     * 文件白名单：两个"故意写出旧前缀"的测试。
     *
     * <ul>
     *   <li>{@code ProjectRenameSweepTest.java}：本文件要定义"旧前缀是什么"才能扫描它；</li>
     *   <li>{@code SpaForwardControllerTest.java}：断言"旧路由必须消失"，不写出旧路由就没法断言。</li>
     *   <li>{@code SpaRouteWhitelistTest.java}：同上，它要断言旧 {@code /project(s)} 不在转发清单里。</li>
     * </ul>
     *
     * <p>除这两个之外不给任何豁免——豁免一多，扫描就退化成"什么都查不出来"。</p>
     */
    private static final java.util.Set<String> SELF_WHITELIST =
            java.util.Set.of("ProjectRenameSweepTest.java", "SpaForwardControllerTest.java",
                    "SpaRouteWhitelistTest.java");

    @Test
    @DisplayName("后端源码里不得再出现 /api/projects（旧前缀已无映射，漏一处就是 404）")
    void backendHasNoLegacyApiPrefix() throws IOException {
        Map<String, List<String>> hits = scan(new Path[]{Path.of("src/main"), Path.of("src/test")},
                LEGACY_API_PREFIX, null);
        assertTrue(hits.isEmpty(), "以下文件仍在用旧 API 前缀 " + LEGACY_API_PREFIX + "：\n" + render(hits));
    }

    @Test
    @DisplayName("后端不得再出现旧前端路由 /project/（用户可见链接改指 /world/）")
    void backendHasNoLegacyFrontendRoute() throws IOException {
        Map<String, List<String>> hits = scan(new Path[]{Path.of("src/main"), Path.of("src/test")},
                LEGACY_ROUTE_PREFIX, WORD_SLASH_WHITELIST);
        assertTrue(hits.isEmpty(), "以下文件仍在指向旧前端路由 " + LEGACY_ROUTE_PREFIX + "：\n" + render(hits));
    }

    @Test
    @DisplayName("前端源码里不得再出现 /api/projects（旧前缀已无映射，漏一处就是 404）")
    void frontendHasNoLegacyApiPrefix() throws IOException {
        assertTrue(Files.isDirectory(FRONTEND_SRC), "前端源码目录不存在（工作目录应为 backend）：" + FRONTEND_SRC.toAbsolutePath());
        Map<String, List<String>> hits = scan(new Path[]{FRONTEND_SRC}, LEGACY_API_PREFIX, null);
        assertTrue(hits.isEmpty(), "以下前端文件仍在用旧 API 前缀 " + LEGACY_API_PREFIX + "：\n" + render(hits));
    }

    @Test
    @DisplayName("前端不得再出现旧路由 /project/（features/project/ 是模块目录名，不是路由）")
    void frontendHasNoLegacyFrontendRoute() throws IOException {
        assertTrue(Files.isDirectory(FRONTEND_SRC), "前端源码目录不存在（工作目录应为 backend）：" + FRONTEND_SRC.toAbsolutePath());
        Map<String, List<String>> hits = scan(new Path[]{FRONTEND_SRC}, LEGACY_ROUTE_PREFIX, FRONTEND_ROUTE_WHITELIST);
        assertTrue(hits.isEmpty(), "以下前端文件仍在指向旧路由 " + LEGACY_ROUTE_PREFIX + "：\n" + render(hits));
    }

    @Test
    @DisplayName("模块目录名白名单有效：features/project/ 放行，存盘页路由仍被抓")
    void modulePathWhitelistIsTight() {
        assertTrue(MODULE_PATH_WHITELIST.matcher("import('@/features/project/ProjectLayout.vue')").find(),
                "模块目录名必须被放行（Java 包名/前端目录名保留旧名，见设计 §3.13）");
        assertTrue(!MODULE_PATH_WHITELIST.matcher("router.push('/project/5/sandbox')").find(),
                "模块目录名白名单不许放过真正的旧路由");
    }

    @Test
    @DisplayName("扫描器自身有效：白名单词组不会被误报，真路由会被抓到")
    void scannerSanity() {        Matcher whitelisted = WORD_SLASH_WHITELIST.matcher("scene/project/model 与 working/project/summary");
        assertTrue(whitelisted.find(), "白名单正则必须能匹配日志词组");
        assertTrue(!WORD_SLASH_WHITELIST.matcher("/project/5/characters").find(),
                "白名单不许放过真正的旧路由 /project/5/characters");
    }

    /**
     * 扫描若干目录下的文本文件。
     *
     * @param roots     目录（不存在则跳过）
     * @param needle    待查字面量
     * @param whitelist 命中行里出现即放过的词组正则（可空）
     *
     * @return 文件 → 命中行列表
     */
    private static Map<String, List<String>> scan(Path[] roots, String needle, Pattern whitelist) throws IOException {
        Map<String, List<String>> hits = new LinkedHashMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : files.filter(ProjectRenameSweepTest::isTextSource).toList()) {
                    if (SELF_WHITELIST.contains(p.getFileName().toString())) {
                        continue;
                    }
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    if (!text.contains(needle)) {
                        continue;
                    }
                    List<String> lines = new ArrayList<>();
                    String[] all = text.split("\n", -1);
                    for (int i = 0; i < all.length; i++) {
                        String line = all[i];
                        if (!line.contains(needle)) {
                            continue;
                        }
                        if (whitelist != null && whitelist.matcher(line).find()) {
                            continue;
                        }
                        lines.add((i + 1) + ": " + line.trim());
                    }
                    if (!lines.isEmpty()) {
                        hits.put(p.toString(), lines);
                    }
                }
            }
        }
        return hits;
    }

    /** 只扫源码与配置，跳过二进制与构建产物 */
    private static boolean isTextSource(Path p) {
        String s = p.toString().replace('\\', '/');
        if (s.contains("/target/") || s.contains("/node_modules/") || s.contains("/dist/") || s.contains("/static/")) {
            return false;
        }
        return s.endsWith(".java") || s.endsWith(".yml") || s.endsWith(".yaml") || s.endsWith(".js")
                || s.endsWith(".vue") || s.endsWith(".ts") || s.endsWith(".json");
    }

    /** 命中渲染（文件: 行号: 内容），失败信息要能直接定位 */
    private static String render(Map<String, List<String>> hits) {
        StringBuilder sb = new StringBuilder();
        hits.forEach((file, lines) -> {
            sb.append("  ").append(file).append('\n');
            for (String line : lines) {
                sb.append("    ").append(line).append('\n');
            }
        });
        return sb.toString();
    }
}
