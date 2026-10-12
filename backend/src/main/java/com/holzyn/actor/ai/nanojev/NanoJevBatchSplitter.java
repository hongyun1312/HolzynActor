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

import java.util.ArrayList;
import java.util.List;

/**
 * NanoJev 批量拆分器（官方契约三轴上限：states ≤ 32 / questions ≤ 96 / 候选路径 ≤ 256 / 请求体 ≤ 2MB）。
 * <p>官方服务超限返回 500「Local demo limit」——调用前必须按四轴自动分批（设计《N 轨道开发路径》§5
 * 风险「契约限制」对策）：青禾村 20 NPC × 5 题 = 100 题 > 96 → 自动分 2 批。</p>
 * <p>拆分策略：贪心累积——逐 state 追加，任一轴越限即开新批（先按 states/questions/paths 三轴，
 * 再加请求体字节轴兜底）。score 档位按 1 路径计（保守口径）。</p>
 */
public final class NanoJevBatchSplitter {

    private NanoJevBatchSplitter() {
    }

    /**
     * 按配置上限把 state 列表拆成合法批次（每批单次请求可安全发送）。
     *
     * @param states 待评估 state 列表（非空）
     *
     * @param properties NanoJev 配置（批上限）
     *
     * @return 批次列表（每批非空；不做跨 state 拆题——state 粒度最小）
     */
    public static List<List<NanoJevState>> split(List<NanoJevState> states, NanoJevProperties properties) {
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        int maxStates = properties == null || properties.getMaxStatesPerRequest() <= 0
                ? 32 : properties.getMaxStatesPerRequest();
        int maxQuestions = properties == null || properties.getMaxQuestionsPerRequest() <= 0
                ? 96 : properties.getMaxQuestionsPerRequest();
        int maxPaths = properties == null || properties.getMaxPathsPerRequest() <= 0
                ? 256 : properties.getMaxPathsPerRequest();
        int maxBody = properties == null || properties.getMaxBodyBytes() <= 0
                ? 2 * 1024 * 1024 : properties.getMaxBodyBytes();

        List<List<NanoJevState>> batches = new ArrayList<>();
        List<NanoJevState> current = new ArrayList<>();
        int curQuestions = 0;
        int curPaths = 0;
        for (NanoJevState s : states) {
            int q = s.questionCount();
            int p = s.pathCount();
            if (q > maxQuestions || p > maxPaths) {
                // 单 state 自身超限（异常输入）：独立成批发送，由服务端 500 兜底（fail-closed 回退）
                if (!current.isEmpty()) {
                    batches.add(current);
                    current = new ArrayList<>();
                    curQuestions = 0;
                    curPaths = 0;
                }
                batches.add(List.of(s));
                continue;
            }
            if (!current.isEmpty()
                    && (current.size() + 1 > maxStates
                    || curQuestions + q > maxQuestions
                    || curPaths + p > maxPaths)) {
                batches.add(current);
                current = new ArrayList<>();
                curQuestions = 0;
                curPaths = 0;
            }
            current.add(s);
            curQuestions += q;
            curPaths += p;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        // 请求体字节轴兜底：某批超 2MB 时按 state 粒度再拆（超长单 state 独立成批）
        List<List<NanoJevState>> out = new ArrayList<>();
        for (List<NanoJevState> b : batches) {
            if (b.size() == 1) {
                out.add(b);
                continue;
            }
            List<NanoJevState> cb = new ArrayList<>();
            int bytes = 0;
            for (NanoJevState s : b) {
                int addBytes = new NanoJevRequest(List.of(s)).bodyBytes();
                if (!cb.isEmpty() && bytes + addBytes > maxBody) {
                    out.add(cb);
                    cb = new ArrayList<>();
                    bytes = 0;
                }
                cb.add(s);
                bytes += addBytes;
            }
            if (!cb.isEmpty()) {
                out.add(cb);
            }
        }
        return out;
    }

    /** 统计总问题数（快照用）。 */
    public static int totalQuestions(List<NanoJevState> states) {
        int n = 0;
        for (NanoJevState s : states) {
            n += s.questionCount();
        }
        return n;
    }

    /** 统计总候选路径数（快照用）。 */
    public static int totalPaths(List<NanoJevState> states) {
        int n = 0;
        for (NanoJevState s : states) {
            n += s.pathCount();
        }
        return n;
    }
}
