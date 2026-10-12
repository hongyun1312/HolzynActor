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

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 「事务提交后执行」工具。
 * <p><b>要解决的问题</b>：记忆写入路径把 embedding HTTP 调用放在了事务里——
 * {@code actor_memory_working} + {@code actor_memory_fuzz_state} 之后直接调
 * {@code MemoryVectorService.addVector}（一次 embedding 往返，且 {@code applyNoiseAndStore} 同样），
 * 而该方法又常被 {@code AgentActionService.execute} 这类更大的事务嵌套调用（那个事务前面已经改写了
 * {@code actor_state}/{@code actor_character}）。结果是：<b>一个 HTTP 往返期间数据库行锁一直被按住</b>。
 * 世界运行实测（rate=48、20 角色并行决策）在两个游戏小时边界集中出现
 * {@code Timeout trying to lock table "actor_state"} 31 次，其中 4 次情绪 AI 写回重试 3 次后彻底丢失
 * （AI 已付费、结果没落库）。</p>
 * <p><b>约定</b>：事务性流程里的「派生数据计算」——向量化、噪声向量、文件落盘、外部 HTTP——
 * 一律用本工具挪到提交之后；<b>无事务时立即执行</b>（单测/非事务调用语义不变）。异常只记日志不外抛，
 * 保证"观测/增强环节绝不反噬业务"（对齐仓库既有约定：同步 AI 不进事务，S4 教训）。</p>
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * 事务提交后执行（无事务同步时立即执行）；异常只记日志，不外抛（绝不影响提交结果）。
     *
     * @param what 人类可读的动作名（日志定位用，如「L1 写入向量化」）
     *
     * @param action 待执行动作（null=空操作）
     */
    public static void runAfterCommit(String what, Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeRun(what, action);
                }
            });
            return;
        }
        // 无事务（单测/非事务调用）：立即执行，语义与改造前一致
        safeRun(what, action);
    }

    /** 执行并吞掉异常（只告警）。 */
    private static void safeRun(String what, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[提交后执行] {} 失败（已隔离，不影响主流程）: {}", what, e.getMessage());
        }
    }
}