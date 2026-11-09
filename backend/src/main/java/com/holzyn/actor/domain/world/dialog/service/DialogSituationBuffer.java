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

package com.holzyn.actor.domain.world.dialog.service;

import com.holzyn.actor.domain.world.fact.service.WorldFactQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轮末情境缓冲。
 * <p>对谈结束后，{@code AgentRuntime.evaluateMembers(after_dialog)} 会为每个 NPC 组装决策情境；
 * 本服务把「本轮对话摘要 + 已知事实 Top-8 + 在场者」暂存到会话级缓冲，供该路径读取。
 * 只保存在内存：情境是「刚刚这一轮」的短时上下文，重启丢失不影响世界状态。</p>
 * <p><b>不落库</b>：摘要本身已随 {@code kind=dialog} 事件进事件总线；事实来自事实层。</p>
 */
@Slf4j
@Service
public class DialogSituationBuffer {

    /** 摘要最大字符数（防情境段挤掉决策关键信息） */
    static final int SUMMARY_MAX = 400;

    /** 会话键 → 最近一轮情境 */
    private final Map<String, RoundSituation> buffer = new ConcurrentHashMap<>();

    /** 事实只读查询（可空=只注入摘要与在场者） */
    @Autowired(required = false)
    private WorldFactQuery worldFactQuery;

    /**
     * 记住最近一轮（覆盖式；同一会话只保留最新）。
     *
     * @param conversationKey 会话键（调用方用 {@code conversation:{id}}）
     * @param summary         本轮摘要
     * @param presentNames    在场者名字
     */
    public void remember(String conversationKey, String summary, List<String> presentNames) {
        if (conversationKey == null || conversationKey.isBlank()) {
            return;
        }
        buffer.put(conversationKey, new RoundSituation(
                summary == null ? "" : truncate(summary.trim(), SUMMARY_MAX),
                presentNames == null ? List.of() : List.copyOf(presentNames)));
    }

    /**
     * 组装某 NPC 的轮末情境。
     *
     * @param projectId      项目 ID
     * @param characterRef   角色引用
     * @param conversationId 旧对话会话 ID
     * @param gameSecond     当前游戏秒（可空）
     *
     * @return 情境文本；无缓冲返回 null（调用方回退既有情境组装）
     */
    public String situationFor(Long projectId, String characterRef, Long conversationId, Long gameSecond) {
        if (conversationId == null) {
            return null;
        }
        RoundSituation rs = buffer.get("conversation:" + conversationId);
        if (rs == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (!rs.summary().isBlank()) {
            sb.append("【本轮对话】").append(rs.summary());
        }
        String facts = factsSegment(projectId, characterRef, gameSecond);
        if (!facts.isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(facts);
        }
        if (!rs.presentNames().isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("【在场】").append(String.join("、", rs.presentNames()));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 已知事实 Top-8（复用digest；失败零阻断）。 */
    private String factsSegment(Long projectId, String characterRef, Long gameSecond) {
        if (worldFactQuery == null || projectId == null || characterRef == null || characterRef.isBlank()) {
            return "";
        }
        try {
            long gs = gameSecond == null ? 0L : gameSecond;
            String digest = worldFactQuery.digestForPrompt(projectId, characterRef, gs, 8);
            // digestForPrompt 无事实时返回空串/仅版本行；只保留有事实条目的段
            return digest == null || digest.isBlank() || !digest.contains("- ") ? "" : digest;
        } catch (Exception e) {
            log.debug("[对话情境] 事实摘要读取失败（跳过）: {}", e.getMessage());
            return "";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 会话级最近一轮情境。 */
    private record RoundSituation(String summary, List<String> presentNames) {
    }
}
