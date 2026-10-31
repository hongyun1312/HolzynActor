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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP 请求的上下文判定（V1.1.2-beta.1，唯一实现）。
 *
 * <p>两件事：</p>
 * <ol>
 *   <li><b>路径级默认</b>：只有"存档生命周期"那几个路径语义上永远是对着目录库的
 *       （列表 / 新建 / 详情 / 改名删除 / 当前挂载 / 打开关闭 / 清单 / 包导入），
 *       其余 {@code /api/worlds/{id}/...} 都是<b>世界内接口</b>（时钟、角色、记忆、知识…），
 *       必须打到世界库；</li>
 *   <li><b>兜底清理</b>：无论如何都在 {@code finally} 里清空线程上下文。这一步不是可选项——
 *       本项目 350 个测试类没有 {@code @SpringBootTest}，任何"忘记还原"的标记都只会在真实
 *       运行里表现为"进过某个页面之后，别的页面开始读错库"，而且重启即消失，几乎不可能复现。</li>
 * </ol>
 *
 * <p><b>为什么不是"整个 /api/worlds 前缀都算全局"</b>（M5 实测教训）：M5 把世界内接口
 * 从旧的"项目"前缀改名成 {@code /api/worlds/{id}/...} 之后，前缀级判定
 * 会把 {@code GET /api/worlds/1/world-clock} 也判成全局请求 → 路由到目录库 → 目录库没有
 * {@code actor_world_clock}，直接 500 {@code Table "actor_world_clock" not found}。
 * 生命周期控制器本身有类级 {@code @GlobalContext}，这里的路径规则只是"提前判定 + 兜底清理"，
 * 因此<b>宁可窄</b>：判错成世界内会被路由到世界库（世界库确实有那些表），判错成全局则必然 500。</p>
 *
 * <p>为什么用过滤器而不是只在拦截器里做：拦截器只在 DispatcherServlet 命中处理器之后才执行，
 * 404、静态资源、异常提前返回等路径不会经过拦截器；而 {@code finally} 必须覆盖所有路径。</p>
 *
 * <p>顺序刻意靠前（{@code HIGHEST_PRECEDENCE + 100}）：必须早于任何会碰数据库的过滤器/拦截器，
 * 否则那一段的数据库访问仍看到上一次请求残留的上下文。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class WorldRequestContextFilter extends OncePerRequestFilter {

    /** 语义上永远读目录库的路径前缀（存档生命周期） */
    static final String WORLDS_API_PREFIX = "/api/worlds";

    /**
     * 生命周期路径（{@code /api/worlds} 之后的形态）。
     *
     * <p>命中的都是"目录库自己就能回答"的请求：列表、新建、当前挂载、单档详情、改名删除、
     * 打开/关闭、清单、导入、导出。导出（M6）也在这里：它读的是<b>磁盘上的世界目录</b>与目录库索引，
     * 不经过路由数据源，所以判成全局是安全的，也免掉"必须挂载着才能导出"的伪约束。</p>
     *
     * <p>注意 {@code /api/worlds/import} 只认<b>精确</b>那一条：文件解析建世界的接口在
     * {@code /api/worlds/parse/**}（M6 从 {@code /import/**} 迁出），它必须按"世界内"路由，
     * 因为确认创建后要立刻往新挂载的世界库里写角色/地点。</p>
     */
    private static final java.util.regex.Pattern LIFECYCLE_PATH =
            java.util.regex.Pattern.compile(
                    "^/api/worlds(?:/current|/import|/\\d+(?:/(?:open|close|manifest|export))?)?/?$");

    /**
     * 判定本次请求的上下文并保证还原。
     *
     * @param request  请求
     * @param response 响应
     * @param chain    过滤器链
     *
     * @throws ServletException 下游过滤器异常
     * @throws IOException      下游 IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 防御：上一个用到本线程的请求可能因异步/异常没还原
        WorldContext.clear();
        WorldContext.Scope scope = isGlobalPath(request) ? WorldContext.global() : null;
        try {
            chain.doFilter(request, response);
        } finally {
            if (scope != null) {
                scope.close();
            }
            WorldContext.clear();
        }
    }

    /**
     * 是否路径级全局请求。
     *
     * @param request 请求
     *
     * @return true = 本次请求必须读目录库
     */    static boolean isGlobalPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        return LIFECYCLE_PATH.matcher(uri).matches();
    }
}
