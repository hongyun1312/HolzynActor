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

import java.util.function.Supplier;

/**
 * 世界上下文：线程级的「显式走全局库」标记（V1.1.2-beta.1）。
 *
 * <p><b>为什么只有 GLOBAL 一个标记，没有 WORLD 标记</b>：单活动世界模型下"当前挂载的世界"
 * 是一个全局单例状态（{@link WorldSessionService} 持有），所有线程天然看到同一个值。
 * 所以路由只需要回答一个问题——"这次访问是不是要**绕过**当前世界去打目录库"。
 * 反过来，若把"当前世界"也塞进 ThreadLocal，就得给仓库里 63 处自建线程池逐个挂
 * TaskDecorator 才不会丢上下文（AGENTS 里那类漏挂必出事故的改造），而丢上下文本身在
 * 单活动世界下是安全的：会回落到"当前挂载世界"，不会写到另一个世界去。</p>
 *
 * <p><b>为什么是可嵌套的计数而不是布尔</b>：{@code @GlobalContext} 标注的服务可能在另一个
 * 全局上下文内被调用（如迁移过程中调配置读取），布尔标记会被内层的退出提前清掉，
 * 导致外层后续访问悄悄走回世界库。计数只在归零时才真正退出。</p>
 *
 * <p>用法（务必用 try-with-resources 或 {@code runAsGlobal}，手写 enter/exit 遇异常会漏还原）：</p>
 * <pre>{@code
 * try (var scope = WorldContext.global()) {
 *     repository.findAll();          // 打到目录库
 * }
 * String name = WorldContext.callAsGlobal(() -> project.getName());
 * }</pre>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
public final class WorldContext {

    /** 当前线程的全局上下文嵌套深度（0 = 未进入） */
    private static final ThreadLocal<Integer> GLOBAL_DEPTH = ThreadLocal.withInitial(() -> 0);

    private WorldContext() {
    }

    /**
     * 当前线程是否处于"显式全局"上下文。
     *
     * @return true = 本次访问应打到目录库
     */
    public static boolean isGlobal() {
        return GLOBAL_DEPTH.get() > 0;
    }

    /** 当前线程的嵌套深度（仅诊断/测试用） */
    public static int depth() {
        return GLOBAL_DEPTH.get();
    }

    /**
     * 进入全局上下文（可嵌套）。
     *
     * @return 作用域，关闭时退出一层；建议 try-with-resources
     */
    public static Scope global() {
        GLOBAL_DEPTH.set(GLOBAL_DEPTH.get() + 1);
        return new Scope();
    }

    /**
     * 在全局上下文里执行。
     *
     * @param action 待执行动作
     */
    public static void runAsGlobal(Runnable action) {
        try (Scope ignored = global()) {
            action.run();
        }
    }

    /**
     * 在全局上下文里求值。
     *
     * @param supplier 求值动作
     * @param <T>      返回类型
     *
     * @return 求值结果
     */
    public static <T> T callAsGlobal(Supplier<T> supplier) {
        try (Scope ignored = global()) {
            return supplier.get();
        }
    }

    /**
     * 清空当前线程的上下文。
     *
     * <p>只给测试与线程池提交前的防御性清理用：正常路径必须靠 {@link Scope} 自动退出，
     * 手动 clear 会掩盖"忘记退出"的缺陷。</p>
     */
    public static void clear() {
        GLOBAL_DEPTH.remove();
    }

    /** 全局上下文作用域（幂等关闭，防重复 close 把深度减成负数） */
    public static final class Scope implements AutoCloseable {

        private boolean closed;

        private Scope() {
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            int next = GLOBAL_DEPTH.get() - 1;
            if (next <= 0) {
                GLOBAL_DEPTH.remove();
            } else {
                GLOBAL_DEPTH.set(next);
            }
        }
    }
}
