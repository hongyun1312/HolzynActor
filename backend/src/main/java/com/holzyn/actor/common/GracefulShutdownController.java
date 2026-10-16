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
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 优雅关闭控制器。
 * <p>职责：提供 POST /api/system/shutdown —— 桌面端更新/退出前调用，让 Spring 上下文
 * 正常关闭（数据源释放 → H2 干净落盘），随后 JVM 自然退出；Electron 主进程等待其退出，
 * 超时再强杀进程树（见 desktop/main.js stopBackendGracefully）。</p>
 * <p>安全：端点仅做本地关闭语义；桌面端使用随机端口 + 仅监听 127.0.0.1；远程 profile
 * 部署方可通过环境变量 HOLZYN_ACTOR_SYSTEM_SHUTDOWN_ENABLED=false 关闭本端点。</p>
 */
@RestController
@RequestMapping("/api/system")
public class GracefulShutdownController {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownController.class);

    private final ApplicationContext context;
    private final boolean shutdownEnabled;

    public GracefulShutdownController(ApplicationContext context,
                                      @Value("${holzyn.actor.system.shutdown-enabled:true}") boolean shutdownEnabled) {
        this.context = context;
        this.shutdownEnabled = shutdownEnabled;
    }

    /**
     * 优雅关闭端点：先返回响应，随后延迟 300ms 关闭 Spring 上下文（保证响应先落盘/刷出）。
     *
     * @return 关闭受理确认
     */
    @PostMapping("/shutdown")
    public R<Map<String, Object>> shutdown() {
        if (!shutdownEnabled) {
            log.warn("[system] 优雅关闭端点已被禁用（shutdown-enabled=false）");
            return R.fail(403, "shutdown disabled");
        }
        log.info("[system] 收到优雅关闭请求，300ms 后关闭 Spring 上下文（H2 干净落盘）");
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "holzyn-graceful-shutdown");
            t.setDaemon(false);
            return t;
        }).submit(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int code = SpringApplication.exit(context, () -> 0);
            log.info("[system] Spring 上下文已关闭，exitCode={}", code);
        });
        return R.ok(Map.of("status", "shutting-down"));
    }
}
