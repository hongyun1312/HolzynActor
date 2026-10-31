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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@link GlobalContext} 的请求级实现（V1.1.2-beta.1）。
 *
 * <p>用 MVC 拦截器而不是过滤器：注解挂在方法/类上，只有拿到了 {@link HandlerMethod} 才能判定；
 * 过滤器阶段还没有 Handler 映射结果。路径级默认（{@code /api/worlds/**}）由
 * {@link WorldRequestContextFilter} 负责。</p>
 *
 * <p>三个还原点，缺一不可：</p>
 * <ol>
 *   <li>{@code afterCompletion}：普通请求正常/异常结束；</li>
 *   <li>{@code afterConcurrentHandlingStarted}：SSE/异步请求在第一段结束时交出线程，
 *       此时若不清，标记会留在<b>被归还给 Tomcat 池的线程</b>上——下一个请求哪怕只读也会
 *       打到目录库，症状是"进世界后随机读不到数据"，极难定位；</li>
 *   <li>{@link WorldRequestContextFilter} 的 {@code finally}：前两者都被跳过时的兜底。</li>
 * </ol>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
public class GlobalContextInterceptor implements AsyncHandlerInterceptor {

    /** 请求属性名（同一请求内 preHandle 与 afterCompletion 之间传递作用域） */
    static final String SCOPE_ATTRIBUTE = GlobalContextInterceptor.class.getName() + ".scope";

    /**
     * 进入全局上下文（带注解的处理器）。
     *
     * @param request  请求
     * @param response 响应
     * @param handler  处理器
     *
     * @return 恒为 true（本拦截器只置位，不拦截请求）
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod hm && requiresGlobal(hm)) {
            request.setAttribute(SCOPE_ATTRIBUTE, WorldContext.global());
        }
        return true;
    }

    /**
     * 请求结束（同步）时还原上下文。
     *
     * @param request  请求
     * @param response 响应
     * @param handler  处理器
     * @param ex       请求处理异常（可空）
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        closeScope(request);
    }

    /**
     * 异步请求交出线程时还原上下文（SSE）。
     *
     * <p>Spring 7 起该回调多了 {@code handler} 参数（与 {@code afterCompletion} 对齐），
     * 这里用不到，仅保持签名一致。</p>
     *
     * @param request  请求
     * @param response 响应
     * @param handler  处理器
     */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        closeScope(request);
        // 异步线程池里的后续处理不应继承"全局"：线程可能被复用给别的世界请求
        WorldContext.clear();
    }

    /**
     * 处理器是否要求全局上下文（方法优先，其次声明类）。
     *
     * @param hm 处理器方法
     *
     * @return true = 需要显式全局
     */
    static boolean requiresGlobal(HandlerMethod hm) {
        return hm.getMethodAnnotation(GlobalContext.class) != null
                || hm.getBeanType().isAnnotationPresent(GlobalContext.class);
    }

    /** 关闭并移除本次请求的作用域（幂等） */
    private static void closeScope(HttpServletRequest request) {
        Object scope = request.getAttribute(SCOPE_ATTRIBUTE);
        if (scope instanceof WorldContext.Scope s) {
            s.close();
            request.removeAttribute(SCOPE_ATTRIBUTE);
        }
    }
}
