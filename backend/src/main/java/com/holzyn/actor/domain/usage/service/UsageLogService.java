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

package com.holzyn.actor.domain.usage.service;

import com.holzyn.actor.domain.usage.entity.ActorUsageLog;
import com.holzyn.actor.domain.usage.repository.ActorUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 用量日志服务。
 * <p>职责：统一封装 actor_usage_log 写入（用户/项目/角色/供应商/模型/场景/token/耗时），
 * 供角色卡生成、对话、行动等 AI 调用场景记录用量。</p>
 * <p><b>阶段④ 健壮性加固：观测数据不得反噬业务。</b>
 * 本条是「用量落账绝不影响 AI 调用成败」的硬保证，两层保护：</p>
 * <ol>
 * <li><b>独立事务</b>（{@code REQUIRES_NEW}）：落账在自己的事务里提交/回滚，
 * 不会把调用方的事务标记为 rollback-only。否则一旦 INSERT 失败（例如
 * {@code actor_usage_log.scene} 列宽不足触发 H2 22001），异常会在<b>调用方事务提交时</b>
 * 才以 {@code UnexpectedRollbackException} 形式炸出来——那时已经离开本方法的 try/catch，
 * 于是"记不上账"被误判成"AI 调用失败"，把一次<b>已经成功、token 已经花掉</b>的 AI 产出整段作废。
 * 这正是 两段式建档在真库上 100% 失败的实际病根形态。</li>
 * <li><b>内部 try/catch 只告警</b>：任何落账异常都不得向外抛出。</li>
 * </ol>
 * <p>代价：即使调用方事务最终回滚，用量行仍会保留——这是刻意的（token 确实已被消耗，
 * 用量统计应当如实反映成本）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageLogService {

    /** 用量日志仓库 */
    private final ActorUsageLogRepository repository;

    /**
     * 记录一次 AI 调用用量（各字段可空，内部归一化为非空默认值；旧签名重载，缓存统计按 0 处理）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（项目维度统计）
     *
     * @param characterId 角色 ID（可空）
     *
     * @param providerId API 配置主键（可空）
     *
     * @param model 实际模型名（可空）
     *
     * @param scene 场景：card_gen/dialog/action/crowd
     *
     * @param tokenIn 输入 token（可空）
     *
     * @param tokenOut 输出 token（可空）
     *
     * @param durationMs 调用耗时毫秒（可空）
     */
    public void record(Long userId, Long projectId, Long characterId, Long providerId, String model,
                       String scene, Integer tokenIn, Integer tokenOut, Integer durationMs) {
        // 兼容旧签名：未提供缓存命中统计时按 0 处理（不影响聚合）
        record(userId, projectId, characterId, providerId, model, scene, tokenIn, tokenOut,
                null, null, durationMs);
    }

    /**
     * 记录一次 AI 调用用量（完整签名，含上下文缓存命中统计，各字段可空，内部归一化为非空默认值）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（项目维度统计）
     *
     * @param characterId 角色 ID（可空）
     *
     * @param providerId API 配置主键（可空）
     *
     * @param model 实际模型名（可空）
     *
     * @param scene 场景：card_gen/dialog/action/crowd
     *
     * @param tokenIn 输入 token（可空）
     *
     * @param tokenOut 输出 token（可空）
     *
     * @param cacheHitTokens 输入中命中上下文缓存的 token（可空，DeepSeek prompt_cache_hit_tokens）
     *
     * @param cacheMissTokens 输入中未命中缓存的 token（可空）
     *
     * @param durationMs 调用耗时毫秒（可空）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, Long projectId, Long characterId, Long providerId, String model,
                       String scene, Integer tokenIn, Integer tokenOut, Integer cacheHitTokens,
                       Integer cacheMissTokens, Integer durationMs) {
        try {
            ActorUsageLog log = new ActorUsageLog();
            log.setUserId(userId == null ? 1L : userId);
            log.setProjectId(projectId);
            log.setCharacterId(characterId);
            log.setProviderId(providerId);
            log.setModel(model);
            log.setScene(scene);
            log.setTokenIn(tokenIn == null ? 0 : tokenIn);
            log.setTokenOut(tokenOut == null ? 0 : tokenOut);
            log.setCacheHitTokens(cacheHitTokens == null ? 0 : cacheHitTokens);
            log.setCacheMissTokens(cacheMissTokens == null ? 0 : cacheMissTokens);
            log.setDurationMs(durationMs == null ? 0 : durationMs);
            repository.save(log);
        } catch (Exception e) {
            // 用量记录失败不应影响主业务流程，仅告警（scene 一并打出，便于定位列宽/非法值类问题）
            log.warn("用量日志写入失败（已忽略，不影响业务）：scene={} projectId={} 原因={}",
                    scene, projectId, e.getMessage());
        }
    }
}
