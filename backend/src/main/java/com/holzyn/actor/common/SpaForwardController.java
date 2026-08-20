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
 * <p>所属模块：controller/common（通用控制器）</p>
 */
@Controller
public class SpaForwardController {

    /**
     * 前端 SPA 路由回退：/login、/register、/projects、/project、/project/**、/admin、/admin/**、
     * /settings、/settings/**、/account、/onboarding -> index.html。
     * <p>说明：登录/注册已随无登录化移除，但保留转发以兼容历史直达 URL；/settings 为首页全局设置四子页。</p>
     *
     * @return Spring 视图名称 "forward:/index.html"
     */
    @GetMapping({"/login", "/register", "/projects", "/projects/**", "/project", "/project/**", "/admin", "/admin/**",
            "/settings", "/settings/**", "/account", "/onboarding"})
    public String forwardPortal() {
        return "forward:/index.html";
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