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

package com.holzyn.actor.ai.nanojev;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NanoJev 单个 state（共享上下文 + 一组问题）。
 * <p>官方契约：{@code id} 唯一非空；{@code state}
 * 为非空字符串 / JSON 对象 / 数组；{@code questions} 非空对象、键为 qid 非空字符串。</p>
 * <p>用途：同一角色的多个判定（意愿/情绪/候选选择）合并进一个 state 一次前向批量出结果；
 * 多个角色的 state 可进同一次请求（states ≤ 32）—— 起每 tick 全 NPC 批量判定即此形态。</p>
 *
 * @param id state 唯一 ID（响应中一一对应返回；习惯命名 s1/s2/… 或角色 ref）
 *
 * @param state 共享上下文（字符串 / JSON 对象 / 数组；非空）
 *
 * @param questions 问题表（qid → 问题；保持插入序，响应 answers 键与之一一对应）
 */
public record NanoJevState(String id, Object state, Map<String, NanoJevQuestion> questions) {

    /** 构造（questions 保持插入序；调用方用 LinkedHashMap）。 */
    public NanoJevState {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("NanoJev state.id 必须非空");
        }
        if (state == null) {
            throw new IllegalArgumentException("NanoJev state.state 必须非空");
        }
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("NanoJev state.questions 必须非空");
        }
    }

    /** 便捷构造（状态为纯文本串）。 */
    public static NanoJevState text(String id, String state, Map<String, NanoJevQuestion> questions) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("NanoJev state.state 必须非空");
        }
        return new NanoJevState(id, state, questions);
    }

    /** 便捷构造（状态为 JSON 对象/数组，如感知快照）。 */
    public static NanoJevState json(String id, Object state, Map<String, NanoJevQuestion> questions) {
        return new NanoJevState(id, state, questions);
    }

    /** 该 state 的问题数。 */
    public int questionCount() {
        return questions.size();
    }

    /** 该 state 的候选路径总数（求和，供批量拆分器按 256 上限分轴）。 */
    public int pathCount() {
        int n = 0;
        for (NanoJevQuestion q : questions.values()) {
            n += q.pathCount();
        }
        return n;
    }

    /** 空 questions 表（便捷：调用方逐题 put）。 */
    public static Map<String, NanoJevQuestion> newQuestions() {
        return new LinkedHashMap<>();
    }
}
