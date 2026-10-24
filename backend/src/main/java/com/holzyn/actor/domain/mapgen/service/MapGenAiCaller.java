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

package com.holzyn.actor.domain.mapgen.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.mapgen.config.MapGenProperties;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 地图管线的**唯一 AI 出口**：重试 + 记账 + trace 三件事都在这里做一次，三个 Stage 只负责拼提示词。
 *
 * <p><b>为什么必须重试</b>（这一版实测踩到的）：S0/S2/S3 是同一条流水线上背靠背的三次调用，
 * 供应商侧的频率限制很容易在第二次就返回 {@code 429 Requests are too frequent}。
 * 第一次真机跑就撞上了：S0 直接吃 429 回落兜底、S2 又被截断——一次生成里两次 AI 都白花，
 * 最后出来的是「兜底岛 + 兜底资产 + AI 路网」的混合体。重试一次（退避 2s/4s）能把这种
 * 抖动挡掉，成本是失败路径上多等几秒（生成本来就是异步的，用户看不到）。
 *
 * <p><b>为什么统一记账</b>：用量按 scene 分组统计，三个 Stage 各写一遍容易漏字段
 * （尤其是 cache 命中/未命中）——收到这里之后只有一处需要维护。
 *
 * <p><b>事务纪律</b>：本类含 HTTP 等待，<b>不得</b>带 {@code @Transactional}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapGenAiCaller {

    /** 退避基数（毫秒；第 n 次重试等 n × 2 秒） */
    private static final long BACKOFF_MS = 2000L;

    private final AiSpringChatGateway aiChatGateway;
    private final UsageLogService usageLogService;
    private final CurrentUserProvider currentUserProvider;
    private final MapGenProperties props;

    /**
     * 一次调用的入参（把「怎么问」和「问完之后干什么」分开）。
     *
     * @param scene       场景名（用量统计分组；如 {@code world_map_profile}）
     * @param system      系统提示词
     * @param user        用户提示词（模板渲染结果）
     * @param temperature 温度
     * @param maxTokens   输出上限
     */
    public record Prompt(String scene, String system, String user,
                         double temperature, int maxTokens) {
    }

    /**
     * 调用 AI 并返回文本内容（失败重试 {@code mapgen.ai-retries} 次后仍失败则抛 502）。
     *
     * @param projectId 项目 ID（记账与 trace 归属）
     * @param p         调用入参
     * @return 模型输出文本（非空）
     */
    public String content(Long projectId, Prompt p) {
        int attempts = Math.max(1, 1 + props.getAiRetries());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String text = once(projectId, p, attempt);
                if (attempt > 1) {
                    log.info("[mapgen] {} 第 {} 次重试成功", p.scene(), attempt - 1);
                }
                return text;
            } catch (RuntimeException e) {
                last = e;
                log.warn("[mapgen] {} 第 {}/{} 次调用失败：{}", p.scene(), attempt, attempts, e.getMessage());
                if (attempt < attempts) {
                    sleep(BACKOFF_MS * attempt);
                }
            }
        }
        throw new BizException(502, "AI 调用失败（" + p.scene() + "）："
                + (last == null ? "未知错误" : last.getMessage()));
    }

    /**
     * 当前用户 ID（本地单用户恒为 1）。
     * <p>为什么从这里取而不是让各 Stage 自己注入 {@code CurrentUserProvider}：拼提示词时要用它
     * 解析「用户级模板覆盖」，而真正调用时也要用它记账——两处必须是同一个值，
     * 放在一处才不会出现「按 A 用户的模板渲染、记到 B 用户账上」。</p>
     *
     * @return 用户 ID
     */
    public Long currentUserId() {
        return currentUserProvider.currentUserId();
    }

    /** 单次调用（含 trace/记账）。 */
    private String once(Long projectId, Prompt p, int attempt) {
        Long userId = currentUserProvider.currentUserId();
        AiChatRequest req = new AiChatRequest(null, List.of(
                new AiChatRequest.ChatMessage("system", p.system()),
                new AiChatRequest.ChatMessage("user", p.user())),
                p.temperature(), p.maxTokens(), true);
        long start = System.currentTimeMillis();
        AiChatResult result = AiCallContext.withTrace("mapgen-" + p.scene() + "-" + projectId, () ->
                AiCallContext.run(p.scene(), null, attempt, () ->
                        aiChatGateway.chatCompletion(userId, projectId, null, req)));
        usageLogService.record(userId, projectId, null, result.providerId(), result.model(), p.scene(),
                result.promptTokens(), result.completionTokens(),
                result.cacheHitTokens(), result.cacheMissTokens(),
                (int) (System.currentTimeMillis() - start));
        String content = result.content();
        if (content == null || content.isBlank()) {
            throw new BizException(502, "AI 返回空内容");
        }
        return content;
    }

    /** 退避（中断即恢复中断标记，不吞掉）。 */
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
