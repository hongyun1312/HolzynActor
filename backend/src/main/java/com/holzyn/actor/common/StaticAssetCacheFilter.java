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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

/**
 * 前端静态产物的缓存与壳页直出策略：**壳页永不缓存（并且绝不给 304），带 hash 的资源永久缓存**。
 *
 * <p><b>为什么必须显式处理</b>：Spring Boot 的默认静态资源映射（classpath:/static/）对
 * {@code index.html} 与 {@code /assets/*.js} 都<b>不写任何 Cache-Control</b>，浏览器于是按
 * 「启发式缓存」自行决定复用（通常是 Last-Modified 距今时长的 10%，重启十几分钟内都算新鲜），
 * 且会用 {@code If-Modified-Since} 换 304 继续沿用本地旧壳页。结果就是：用户升级安装包后，
 * Electron / 浏览器仍拿磁盘缓存里的**旧 index.html + 旧分片**跑整个界面——服务端已是新代码，
 * 用户看到的还是旧界面（实测：装了含「AI 读设定起名」的 1.1.3 之后，客户端缓存里仍是上一版的
 * {@code index-B3Q7AWoa.js} / {@code NewProjectView-D8gl_L1A.js}，于是创意发散生成的仍是拼接名，
 * 前端从不发 {@code /api/worlds/meta-suggest} 请求）。
 *
 * <p>两条规则：
 * <ul>
 *   <li>{@code /assets/**}、{@code /debug/assets/**} 下的文件：文件名带构建 hash，内容变了 URL 就变，
 *       给 {@code max-age=31536000, immutable}；</li>
 *   <li>壳页（{@code /}、{@code /index.html}、{@code /debug}、{@code /debug/} 以及所有无扩展名的
 *       SPA 深链，如 {@code /world/1/sandbox}）：<b>由本过滤器直接写出 static/index.html</b>，
 *       带 {@code no-store}，不再交给 ResourceHttpRequestHandler——后者会回 304，而 304 不换响应体，
 *       正是「装了新版还是旧界面」的成因。</li>
 * </ul>
 *
 * <p>不碰的路径：{@code /api/**}（含 SSE 流，自己带 Cache-Control）、{@code /uploads/**}（用户可替换
 * 同名模型/贴图，不能 immutable）、{@code /ws} 等长连接、以及带扩展名的静态文件（缺文件就该 404，
 * 不能被壳页吞掉）。
 *
 * <p><b>注意</b>：本过滤器只保证「以后不再存旧壳页」；已经存在于用户磁盘缓存里的旧条目，
 * 由 {@code desktop/main.js} 的版本变更清缓存逻辑（{@code purgeStaleHttpCache}）清掉。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class StaticAssetCacheFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(StaticAssetCacheFilter.class);

    /** 带构建 hash 的资源：一年 immutable */
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    /** 壳页：完全不落盘，升级后第一次加载就拿新壳页 */
    private static final String NO_STORE = "no-store, no-cache, must-revalidate, max-age=0";

    /** 带 hash 的静态资源（主前端 /assets、调试前端 /debug/assets） */
    private static final Pattern HASHED_ASSET = Pattern.compile(
            "^/(?:debug/)?assets/[^/]+\\.(?:js|mjs|css|woff2?|ttf|eot|otf|png|jpe?g|gif|svg|webp|avif|ico|map|json|txt)$",
            Pattern.CASE_INSENSITIVE);

    /** 不参与本策略的路径前缀（API、上传资产、WebSocket、监控） */
    private static final String[] SKIP_PREFIXES = {"/api/", "/uploads/", "/ws", "/actuator"};

    /** 主壳页与调试壳页（两者都随 jar 发布，内容每次构建都会变） */
    private static final Resource MAIN_SHELL = new ClassPathResource("static/index.html");
    private static final Resource DEBUG_SHELL = new ClassPathResource("static/debug/index.html");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        boolean readable = "GET".equals(method) || "HEAD".equals(method);
        if (!readable) {
            chain.doFilter(request, response);
            return;
        }
        String path = pathOf(request);
        if (path == null || isSkipped(path)) {
            chain.doFilter(request, response);
            return;
        }
        if (HASHED_ASSET.matcher(path).matches()) {
            response.setHeader("Cache-Control", IMMUTABLE);
            chain.doFilter(request, response);
            return;
        }
        // 带扩展名的普通静态文件（favicon.png 等）：交给默认处理器，缺文件就该 404，不能被壳页吞掉
        if (!isShellPath(path)) {
            chain.doFilter(request, response);
            return;
        }
        if (writeShell(path, "HEAD".equalsIgnoreCase(method), response)) {
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 直接写出壳页并下 no-store 头。
     *
     * @param path     请求路径（已去 contextPath）
     * @param headOnly true = HEAD 请求（只回头，不写体）
     * @param response 响应
     * @return true = 已写出（调用方不要再进过滤器链）；false = 壳页资源不存在，退回默认处理
     */
    private boolean writeShell(String path, boolean headOnly, HttpServletResponse response) throws IOException {
        Resource shell = path.startsWith("/debug") ? DEBUG_SHELL : MAIN_SHELL;
        if (!shell.exists()) {
            log.warn("[静态缓存] 壳页资源缺失，退回默认处理器：{}", shell);
            return false;
        }
        byte[] body;
        try (InputStream in = shell.getInputStream()) {
            body = in.readAllBytes();
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", NO_STORE);
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setContentLength(body.length);
        if (!headOnly) {
            response.getOutputStream().write(body);
        }
        response.flushBuffer();
        if (log.isDebugEnabled()) {
            log.debug("[静态缓存] 壳页直出 no-store: {} ({} 字节)", path, body.length);
        }
        return true;
    }

    /** 取不含 contextPath 的请求路径 */
    private String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return null;
        }
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }

    /** /api/**、/uploads/** 等由各自处理器决定缓存语义，本过滤器不插手 */
    private boolean isSkipped(String path) {
        for (String prefix : SKIP_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否壳页路径：{@code /}、{@code /index.html}、{@code /debug}、{@code /debug/}、{@code /debug/index.html}，
     * 以及所有<b>无扩展名</b>的路径（SPA history 深链，如 {@code /world/1/sandbox}、{@code /new-project}）。
     */
    private boolean isShellPath(String path) {
        if ("/".equals(path) || "/index.html".equals(path)
                || "/debug".equals(path) || "/debug/".equals(path) || "/debug/index.html".equals(path)) {
            return true;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        return !last.contains(".");
    }
}
