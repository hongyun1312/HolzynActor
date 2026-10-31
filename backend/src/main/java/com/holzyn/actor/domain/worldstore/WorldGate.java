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

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 后台任务的世界闸门（V1.1.2-beta.1，唯一闸门实现）。
 *
 * <p>职责：让 17 个 {@code @Scheduled} 维护任务在"没有世界挂载"时安全跳过。</p>
 *
 * <p>为什么需要闸门而不是"反正路由会回落到目录库"：这正是危险所在——未挂载时默认路由是
 * 目录库，而维护任务干的是"扫描 findAll() 然后改状态"。任务以为是世界数据，实际扫到的是
 * 目录库里的 16 张索引/配置表；对 {@code actor_ai_call_log} 这类两库同构表，还会把
 * <b>目录库里的全局行</b>当成世界行去删。回退到目录库是"读不到东西"的安全，不是
 * "可以让写任务照跑"的安全。</p>
 *
 * <p>三种用法：</p>
 * <ul>
 *   <li>{@link #tryMaintenance(String, Runnable)}：世界未挂载 → 跳过并返回 false；</li>
 *   <li>{@link #tryMaintenanceWithResult(String, Supplier)}：同上，带返回值；</li>
 *   <li>{@link #tryGlobalMaintenance(String, Runnable)}：无论是否挂载都强制走目录库
 *       （如 AI 日志保留策略要清目录库里的 {@code project_id IS NULL} 行）。</li>
 * </ul>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Slf4j
@Component
public class WorldGate {

    private final WorldSessionService sessions;

    /** 每个任务名被跳过的次数（诊断用：能看出"某个维护任务其实一直没跑过"） */
    private final Map<String, Long> skipCounts = new ConcurrentHashMap<>();

    /** 已打过"首次跳过"INFO 的任务名（避免日志刷屏） */
    private final Map<String, Boolean> announced = new ConcurrentHashMap<>();

    public WorldGate(WorldSessionService sessions) {
        this.sessions = sessions;
    }

    /**
     * 世界维护任务闸门。
     *
     * @param name 任务名（日志用，建议与 {@code @Scheduled} 方法同名）
     * @param body 任务体
     *
     * @return true = 已执行；false = 世界未挂载而跳过
     */
    public boolean tryMaintenance(String name, Runnable body) {
        if (!sessions.isOpen()) {
            skip(name);
            return false;
        }
        body.run();
        return true;
    }

    /**
     * 世界维护任务闸门（带返回值）。
     *
     * <p>刻意不叫 {@code tryMaintenance(String, Supplier)}：那会与 {@code Runnable} 重载形成
     * 静默歧义——{@code () -> list.add(x)} 这种"有返回值但没人要"的表达式 lambda 会被
     * 编译期选成 {@code Supplier<Boolean>}，于是返回类型从 boolean 变成 {@code Optional<Boolean>}，
     * 调用点不报错却行为变了。名字分开就没有这个问题。</p>
     *
     * @param name 任务名
     * @param body 任务体
     * @param <T>  返回值类型
     *
     * @return 任务结果；世界未挂载时 {@link Optional#empty()}
     */
    public <T> Optional<T> tryMaintenanceWithResult(String name, Supplier<T> body) {
        if (!sessions.isOpen()) {
            skip(name);
            return Optional.empty();
        }
        return Optional.ofNullable(body.get());
    }

    /**
     * 全局维护任务闸门（强制目录库上下文）。
     *
     * @param name 任务名
     * @param body 任务体
     *
     * @return true = 已执行
     */
    public boolean tryGlobalMaintenance(String name, Runnable body) {
        WorldContext.runAsGlobal(body);
        return true;
    }

    /** 跳过记录（首次 INFO，之后 debug） */
    private void skip(String name) {
        long n = skipCounts.merge(name, 1L, Long::sum);
        if (announced.putIfAbsent(name, Boolean.TRUE) == null) {
            log.info("[world-store] 维护任务「{}」已挂闸门：当前无挂载世界，已跳过（后续同类跳过不再提示）", name);
        } else {
            log.debug("[world-store] 维护任务「{}」跳过（累计 {} 次）", name, n);
        }
    }

    /** 某任务被跳过的累计次数（测试/诊断用） */
    long skipCount(String name) {
        return skipCounts.getOrDefault(name, 0L);
    }
}
