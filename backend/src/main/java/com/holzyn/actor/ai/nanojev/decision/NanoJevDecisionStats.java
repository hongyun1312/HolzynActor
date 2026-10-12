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

package com.holzyn.actor.ai.nanojev.decision;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * NanoJev 判定层 · 判定统计。
 * <p>纯内存观测设施（不落库）：环形缓冲记录最近判定（含分布/top1Prob/熵/采纳或升级/延迟），
 * 按场景聚合计数与平均延迟——调试页「NanoJev 判定」展示「判定请求数/成功率/平均耗时/分布熵」，
 * 对齐 N 轨道设计 §9 验收「调试页指标」。</p>
 * <p>线程安全：{@link ConcurrentLinkedDeque} 无锁追加 + 快照遍历；统计失败零阻断。</p>
 */
@Component
public class NanoJevDecisionStats {

    /** 环形缓冲上限（防长跑内存膨胀；默认 500 条） */
    public static final int MAX_RECORDS = 500;

    /** 单条判定记录（不可变快照）。 */
    public record Record(
            LocalDateTime at,
            String scene,
            String npcRef,
            String questionType,
            Map<String, Double> probabilities,
            Double top1Prob,
            Double entropy,
            /** true=采纳 NanoJev / false=升级 Qwen（或失败回退） */
            boolean adopted,
            /** 升级原因（top1_prob_low / entropy_high / 未启用 / 失败 / 无） */
            String disposition,
            String chosen,
            long latencyMs) {
    }

    private final ConcurrentLinkedDeque<Record> records = new ConcurrentLinkedDeque<>();

    /** 场景聚合。 */
    private final Map<String, Map<String, Object>> sceneStats = new java.util.concurrent.ConcurrentHashMap<>();

    /** 记录一条判定（无锁追加 + 场景聚合）。 */
    public void record(Record r) {
        if (r == null) {
            return;
        }
        records.addFirst(r);
        while (records.size() > MAX_RECORDS) {
            records.pollLast();
        }
        Map<String, Object> agg = sceneStats.computeIfAbsent(r.scene(), k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scene", k);
            m.put("count", 0L);
            m.put("adopted", 0L);
            m.put("escalated", 0L);
            m.put("failed", 0L);
            m.put("latencySumMs", 0L);
            m.put("latencyAvgMs", 0.0);
            return m;
        });
        synchronized (agg) {
            agg.put("count", (Long) agg.get("count") + 1);
            if (r.adopted()) {
                agg.put("adopted", (Long) agg.get("adopted") + 1);
            } else {
                agg.put("escalated", (Long) agg.get("escalated") + 1);
            }
            if ("failed".equals(r.disposition())) {
                agg.put("failed", (Long) agg.get("failed") + 1);
            }
            long sum = (Long) agg.get("latencySumMs") + r.latencyMs();
            agg.put("latencySumMs", sum);
            agg.put("latencyAvgMs", Math.round(sum * 10.0 / (Long) agg.get("count")) / 10.0);
        }
    }

    /** 最近 N 条判定记录（新→旧）。 */
    public List<Record> recent(int max) {
        List<Record> out = new ArrayList<>();
        for (Record r : records) {
            out.add(r);
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    /** 场景聚合统计（全部已见场景）。 */
    public List<Map<String, Object>> sceneStats() {
        return new ArrayList<>(sceneStats.values());
    }

    /** 清空（调试页手动重置用；不常用）。 */
    public void reset() {
        records.clear();
        sceneStats.clear();
    }
}
