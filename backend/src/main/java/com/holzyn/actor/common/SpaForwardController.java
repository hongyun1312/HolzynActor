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

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 路由回退控制器。
 * <p>职责：将所有前端 Vue Router 路由（非 API 请求）forward 到 index.html，
 * 使 history 模式在刷新页面或直接访问 URL 时不会返回 404。</p>
 * <p>原理：Spring Boot 默认仅对 static 目录下的静态文件做映射，
 * 不会将未知路径回退到 index.html。此控制器显式拦截前端路由并转发。</p>
 * <p>不影响：/api/** 由各自 Controller 处理；静态资源（JS/CSS/图片）由 Spring Boot 默认 ResourceHandler 优先匹配。</p>
 * <p>路由范围（actor）：/login（OIDC 登录引导）、/register（注册页）、/project/**（项目/对话/行动/人群）、/admin/**（管理后台）。</p>
 */
@Controller
public class SpaForwardController {

    /**
     * 前端 SPA 路由回退：/login、/register、/projects、/project、/project/**、/admin、/admin/**、
     * /settings、/settings/**、/account、/onboarding、/tutorial -> index.html。
     * <p>说明：登录/注册已随无登录化移除，但保留转发以兼容历史直达 URL；/settings 为首页全局设置四子页；
     * /tutorial 为布局重构批次③ 的新手上手教程（全局页）。</p>
     *
     * <p><b>维护提醒</b>：新增顶层前端路由（如 /tutorial）必须同步登记在此，否则直接访问该 URL
     * （刷新页面、收藏夹直达、外部链接）会返回 404 JSON —— 开发态由 Vite 的 history 回退兜住，
     * 只有打到 jar 的生产态才会暴露该问题。</p>
     *
     * @return Spring 视图名称 "forward:/index.html"
     */
    @GetMapping({"/login", "/register", "/projects", "/projects/**", "/project", "/project/**", "/admin", "/admin/**",
            "/settings", "/settings/**", "/account", "/onboarding", "/tutorial"})
    public String forwardPortal() {
        return "forward:/index.html";
    }

    /**
     * 调试前端（debug-frontend 独立应用，）：/debug、/debug/ 转发到 static/debug/index.html。
     * <p>说明：调试前端用 hash 路由（#/overview 等），页面内跳转不经过后端；
     * 仅需转发两个「无斜杠/带斜杠」入口路径；/debug/assets/... 等静态资源由
     * Spring Boot 默认 ResourceHandler 直接命中（不进本控制器）。</p>
     *
     * @return Spring 视图名称 "forward:/debug/index.html"
     */
    @GetMapping({"/debug", "/debug/"})
    public String forwardDebug() {
        return "forward:/debug/index.html";
    }

    /**
     * 主页与其余未知前端路由回退：/ -> index.html。
     *
     * @return Spring 视图名称 "forward:/index.html"
     */
    @GetMapping({"/"})
    public String forwardHome() {
        return "forward:/index.html";
    }
}