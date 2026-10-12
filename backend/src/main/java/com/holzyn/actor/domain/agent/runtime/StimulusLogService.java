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

package com.holzyn.actor.domain.agent.runtime;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 刺激日志服务 ——调试前端「刺激日志页」数据源。
 * <p>职责：进程内记录「即时决策通道」的刺激事件与护栏命中（冷却/预算/每日预算/去重/激活闸门/
 * 人群跳过/空闲降频等），供 {@code RealtimeStimulusController} 查询与调试前端刺激时间线 +
 * 护栏统计可视化。</p>
 * <p>实现：内存环形缓冲（上限 {@link #MAX_ENTRIES}，超出丢弃最旧），单实例运行约定重启丢失可接受
 * （刺激日志为调试观测数据，非业务事实源；业务事实仍以 actor_event/actor_ai_call_log/actor_plan 为准）。</p>
 */
@Slf4j
@Service
public class StimulusLogService {

    /** 环形缓冲上限（防长期运行内存膨胀；超出丢弃最旧） */
    static final int MAX_ENTRIES = 500;

    /** 刺激文本在日志中的摘要长度（完整文本不落内存，调试页看摘要） */
    private static final int TEXT_ABBREVIATE = 120;

    /** 自增序号（时间线顺序标识） */
    private final AtomicLong seq = new AtomicLong();

    /** 环形缓冲（同步锁保护——低频写入，性能无虞） */
    private final List<Entry> buffer = new ArrayList<>();

    /** 日志条目（时间线/护栏命中/统计共用）。 */
    @Data
    public static class Entry {
        /** 自增序号 */
        private long id;
        /** 项目 ID */
        private Long projectId;
        /** 游戏时刻（秒，可空） */
        private Long gameSecond;
        /** 真实时刻（记录时间） */
        private LocalDateTime realTime;
        /** 刺激类型编码（stimulus_type / environment / idle_skip 等） */
        private String type;
        /** 角色引用（special:{id} / crowd:{id}；空=项目级） */
        private String npcRef;
        /** 刺激文本摘要 */
        private String stimulusText;
        /** 结果：triggered / cooldown / budget / daily_budget / not_active / disabled / crowd / idle_skip / crowd_ai */
        private String result;
        /** 充说明（中文） */
        private String detail;
    }

    /**
     * 记录一条刺激日志（环形缓冲，超出上限丢弃最旧）。
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 游戏时刻（秒，可空）
     *
     * @param type 刺激类型编码
     *
     * @param npcRef 角色引用（可空=项目级）
     *
     * @param stimulusText 刺激文本（内部摘要存储）
     *
     * @param result 结果编码（triggered/cooldown/budget/daily_budget/not_active/disabled/crowd/idle_skip/crowd_ai）
     *
     * @param detail 补充说明（中文）
     */
    public void log(Long projectId, Long gameSecond, String type, String npcRef, String stimulusText,
                    String result, String detail) {
        try {
            Entry e = new Entry();
            e.setId(seq.incrementAndGet());
            e.setProjectId(projectId);
            e.setGameSecond(gameSecond);
            e.setRealTime(LocalDateTime.now());
            e.setType(type == null ? "?" : type);
            e.setNpcRef(npcRef);
            e.setStimulusText(abbreviate(stimulusText, TEXT_ABBREVIATE));
            e.setResult(result == null ? "?" : result);
            e.setDetail(detail);
            synchronized (buffer) {
                buffer.add(e);
                while (buffer.size() > MAX_ENTRIES) {
                    buffer.remove(0);
                }
            }
        } catch (Exception ex) {
            log.debug("[刺激日志] 记录失败（跳过）: {}", ex.getMessage());
        }
    }

    /**
     * 最近刺激日志（项目过滤 + 倒序，limit 截断）。
     *
     * @param projectId 项目 ID（null=全部项目）
     *
     * @param limit 条数上限（可空=50，上限=环形缓冲容量 500）
     *
     * @return 日志条目（倒序：最新在前）
     */
    public List<Entry> recent(Long projectId, Integer limit) {
        int max = limit == null ? 50 : Math.min(Math.max(1, limit), MAX_ENTRIES);
        synchronized (buffer) {
            List<Entry> filtered = new ArrayList<>();
            for (Entry e : buffer) {
                if (projectId == null || projectId.equals(e.getProjectId())) {
                    filtered.add(e);
                }
            }
            int from = Math.max(0, filtered.size() - max);
            return filtered.stream().skip(from).collect(Collectors.toCollection(ArrayList::new)).reversed();
        }
    }

    /**
     * 护栏命中统计（项目过滤）：按 result 分组计数 + 总条数。
     *
     * @param projectId 项目 ID（null=全部项目）
     *
     * @return 统计 Map（result → 计数 + total）
     */
    public Map<String, Object> stats(Long projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        long total = 0;
        Map<String, Long> byResult = new LinkedHashMap<>();
        synchronized (buffer) {
            for (Entry e : buffer) {
                if (projectId != null && !projectId.equals(e.getProjectId())) {
                    continue;
                }
                total++;
                byResult.merge(e.getResult(), 1L, Long::sum);
            }
        }
        out.put("total", total);
        out.put("byResult", byResult);
        return out;
    }

    /** 文本摘要。 */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 时间格式化（调试页展示用）。 */
    public static String formatRealTime(LocalDateTime t) {
        return t == null ? "" : t.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"));
    }
}
