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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 回归测试：<b>优雅关闭必须真正结束 JVM</b>（2026-10-23 桌面端「退出要等 8 秒」事故固化）。
 *
 * <p>事故现场（桌面端应用自写日志）：</p>
 * <pre>
 * 19:20:04.126  收到优雅关闭请求
 * 19:20:04.459  Spring 上下文已关闭，exitCode=0      ← 落盘其实只用了 0.33s
 * 19:20:12.132  优雅退出超时，强杀进程树            ← JVM 又空转 8s，被 taskkill
 * </pre>
 *
 * <p>根因有两个，缺一不可：</p>
 * <ol>
 *   <li>{@code SpringApplication.exit(...)} 只关闭上下文、<b>不会结束 JVM</b>；</li>
 *   <li>原实现用 {@code Executors.newSingleThreadExecutor} 且线程 {@code setDaemon(false)}、
 *       线程池从不 shutdown —— 任务跑完后池线程仍是非守护线程，JVM 永远退不出去。</li>
 * </ol>
 *
 * <p>本测试同时守住这两点：<b>上下文关闭后必须调用退出</b>、<b>执行线程必须是守护线程</b>。
 * 生产代码里的 {@link GracefulShutdownController#exitJvm(int)} 就是为这里留的替换点，
 * 否则测试里直接 {@code System.exit} 会把 Surefire 进程一起带走。</p>
 *
 * <p>所属模块：common（系统级关闭端点）</p>
 */
class GracefulShutdownControllerTest {

    /** 可测控制器：拦截 exitJvm，记录退出码与执行线程，避免真的结束测试 JVM */
    private static final class TestableController extends GracefulShutdownController {

        private final AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        private final AtomicReference<Thread> exitThread = new AtomicReference<>();

        private TestableController(ApplicationContext context) {
            super(context, true);
        }

        @Override
        void exitJvm(int code) {
            exitCode.set(code);
            exitThread.set(Thread.currentThread());
        }
    }

    @Test
    @DisplayName("优雅关闭：关闭上下文后必须结束 JVM，且执行线程是守护线程")
    void shutdown_endsJvmOnDaemonThreadAfterClosingContext() throws Exception {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(context.getBeansOfType(ExitCodeGenerator.class)).thenReturn(Collections.emptyMap());

        TestableController controller = new TestableController(context);
        R<Map<String, Object>> result = controller.shutdown();

        assertEquals(200, result.getCode(), "关闭请求应立即受理（先返回响应，再由后台线程收尾）");
        assertEquals("shutting-down", result.getData().get("status"));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (controller.exitCode.get() == Integer.MIN_VALUE && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertNotEquals(Integer.MIN_VALUE, controller.exitCode.get(),
                "上下文关闭后必须结束 JVM：漏掉这一步，JVM 会因残留非守护线程不退出，桌面端只能等 8s 强杀");
        assertEquals(0, controller.exitCode.get(), "无损关闭应以 0 退出");
        verify(context, timeout(3000)).close();

        Thread shutdownThread = controller.exitThread.get();
        assertNotNull(shutdownThread, "必须记录到执行退出的线程");
        assertTrue(shutdownThread.isDaemon(),
                "关闭线程必须是守护线程：非守护线程残留本身就是 JVM 无法退出的根因");
        assertEquals("holzyn-graceful-shutdown", shutdownThread.getName());
    }

    @Test
    @DisplayName("端点被禁用时不关闭上下文、不结束 JVM")
    void shutdownDisabled_doesNothing() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        GracefulShutdownController controller = new GracefulShutdownController(context, false);

        R<Map<String, Object>> result = controller.shutdown();

        assertEquals(403, result.getCode());
        verifyNoInteractions(context);
    }
}
