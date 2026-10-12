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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.awt.Desktop.Action;
import java.net.URI;

/**
 * 打包桌面模式：启动完成后在默认浏览器自动打开本地 Web 界面（2026-10-10 桌面端封装）。
 * <p>背景：桌面封装（jpackage）把应用打成无控制台/可随装随跑的 exe，启动后是一个
 * {@code http://127.0.0.1:8080} 本地服务进程——没有「启动成功」的可见反馈，用户会不知所措。
 * 本组件在上下文就绪后自动用默认浏览器打开该地址，作为桌面应用的「启动可见反馈」。</p>
 * <p>装配条件（三选一即可关闭）：</p>
 * <ul>
 *   <li>仅打包桌面模式装配：{@code -Dholzyn.actor.packaged=true}（jpackage --java-options 注入，
 *       与 DataDirResolver.isPackaged 同源）；开发/服务器模式不装配、零影响；</li>
 *   <li>{@code holzyn.actor.packaged.auto-open}（默认 true）设为 false 关闭；</li>
 *   <li>启动参数带 {@code --no-open} 关闭（排障/无人值守场景）。</li>
 * </ul>
 * <p>失败静默降级（仅 WARN）不阻断启动；浏览器未装/headless 环境给出可手动访问的地址提示。</p>
 * <p>实现对齐 {@code DemoWorldSeeder} 思路：ApplicationRunner + 失败不阻断启动。</p>
 */
@Component
@ConditionalOnProperty(name = "holzyn.actor.packaged", havingValue = "true")
public class DesktopAutoOpen implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DesktopAutoOpen.class);

    /** 关闭参数（启动参数 {@code --no-open} 或 {@code no-open} 任一命中即跳过打开） */
    static final String DISABLE_ARG = "no-open";

    @Value("${server.port:8080}")
    private int port;

    @Value("${holzyn.actor.packaged.auto-open:true}")
    private boolean autoOpen;

    @Override
    public void run(ApplicationArguments args) {
        if (!autoOpen || containsArg(args, DISABLE_ARG)) {
            return;
        }
        String url = "http://127.0.0.1:" + port;
        try {
            openBrowser(url);
            log.info("[桌面端] 已在默认浏览器打开 {}（关闭控制台窗口即退出应用）", url);
        } catch (Exception e) {
            log.warn("[桌面端] 自动打开浏览器失败（可手动访问 {}）：{}", url, e.getMessage());
        }
    }

    /**
     * 启动参数是否命中（同时兼容 {@code --name} 选项与裸 {@code name} 非选项参数）。
     *
     * @param args Spring 启动参数
     * @param name 参数名（不含 {@code --} 前缀）
     *
     * @return 命中返回 true
     */
    static boolean containsArg(ApplicationArguments args, String name) {
        if (args == null) {
            return false;
        }
        for (String nonOption : args.getNonOptionArgs()) {
            if (("--" + name).equals(nonOption) || name.equals(nonOption)) {
                return true;
            }
        }
        for (String option : args.getOptionNames()) {
            if (name.equals(option) || ("--" + name).equals(option)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用默认浏览器打开地址（跨平台）：java.awt.Desktop 优先，Windows 兜底 rundll32、
     * macOS 兜底 open、Linux 兜底 xdg-open。
     *
     * @param url 要打开的地址
     *
     * @throws Exception 打开失败（调用方已降级为 WARN）
     */
    static void openBrowser(String url) throws Exception {
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Action.BROWSE)) {
                desktop.browse(URI.create(url));
                return;
            }
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            return;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            new ProcessBuilder("open", url).start();
            return;
        }
        new ProcessBuilder("xdg-open", url).start();
    }
}
