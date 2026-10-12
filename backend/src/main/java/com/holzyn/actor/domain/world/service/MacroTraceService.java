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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.entity.ActorWorldMacroEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldMacroTrace;
import com.holzyn.actor.domain.world.repository.ActorInfoPacketRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldChronicleRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroEventRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldMacroTraceRepository;
import com.holzyn.actor.domain.world.vo.MacroTraceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宏观社会演化 · 全链路 trace 服务。
 * <p>职责：
 * 每次宏观 tick 收尾落一条 {@link ActorWorldMacroTrace}（表 actor_world_macro_trace），
 * 四跳字段：</p>
 * <ul>
 * <li><b>input</b>：tick 输入摘要（入账聚合/季节天气/连续累积/话题——「入账→引擎」跳）；</li>
 * <li><b>params</b>：引擎推进后关键参数（收成/粮价/民怨/人口/势力/议题/科技——「引擎」跳）；</li>
 * <li><b>eventIds</b>：本 tick 产出宏观事件主键（事件表回看——「事件」跳）；</li>
 * <li><b>feedback</b>：回灌落点审计——帧「社会宏观」板块写入、编年史「宏观」标签条数、
 * 宏观信息包条数（「回灌」跳）。</li>
 * </ul>
 * <p>幂等：uk(project_id+game_day+tick_type) 覆盖写（同 tick 重触发不产生重复行）；
 * 滚动上限每项目 {@link #KEEP_PER_PROJECT} 行（默认 500，超出删最旧）；记录失败零阻断
 * （MacroScheduler 调用方 catch 隔离）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroTraceService {

    /** 每项目 trace 保留上限（超出删最旧——防长跑膨胀；对齐快照滚动语义） */
    public static final int KEEP_PER_PROJECT = 500;

    /** 宏观信息包 info_id 前缀（与 MacroFeedbackService.onMacroEvent 同源） */
    private static final String MACRO_PACKET_PREFIX = "macro:";

    /** 编年史「宏观」徽标 tags JSON 片段（与 WorldMemoryService kind=macro 打标同源） */
    private static final String TAG_MACRO_FRAGMENT = "\"macro\"";

    private final ActorWorldMacroTraceRepository traceRepository;
    private final ActorWorldMacroEventRepository eventRepository;
    private final ActorWorldChronicleRepository chronicleRepository;
    private final ActorInfoPacketRepository packetRepository;
    private final ObjectMapper objectMapper;

    // ==================== 写入 ====================

    /**
     * 记录一次宏观 tick 的全链路 trace（幂等覆盖写 + 滚动裁剪；失败零阻断）。
     *
     * @param projectId 项目 ID
     *
     * @param day 游戏日
     *
     * @param gameSecond 游戏秒
     *
     * @param input tick 输入摘要（Map → input_json）
     *
     * @param params 引擎推进后关键参数（Map → params_json）
     *
     * @param frameBoard 帧「社会宏观」板块是否写入（回灌路①）
     */
    @Transactional
    public void record(Long projectId, Long day, Long gameSecond, String tickType,
                       Map<String, Object> input, Map<String, Object> params, boolean frameBoard) {
        if (projectId == null || day == null) {
            return;
        }
        try {
            ActorWorldMacroTrace t = traceRepository
                    .findByProjectIdAndGameDayAndTickType(projectId, day, tickType)
                    .orElseGet(ActorWorldMacroTrace::new);
            t.setProjectId(projectId);
            t.setGameDay(day);
            t.setGameSecond(gameSecond);
            t.setTickType(tickType);
            t.setInputJson(toJson(input));
            t.setParamsJson(toJson(params));
            // 事件跳：本游戏日产出的宏观事件主键（tick 收尾时该日事件即本 tick 产出——幂等口径）
            t.setEventIdsJson(toJson(eventIds(projectId, day)));
            // 回灌跳：帧板块 / 编年史「宏观」标签 / 宏观信息包 三落点审计
            Map<String, Object> feedback = new LinkedHashMap<>();
            feedback.put("frameBoard", frameBoard);
            feedback.put("chronicleMacro", chronicleMacroCount(projectId, day));
            feedback.put("macroPackets", macroPacketCount(projectId));
            t.setFeedbackJson(toJson(feedback));
            traceRepository.save(t);
            prune(projectId);
        } catch (Exception e) {
            log.warn("[宏观 trace] 记录失败（零阻断）: project={} day={} type={} : {}",
                    projectId, day, tickType, e.getMessage());
        }
    }

    /** 滚动裁剪：超出上限删最旧（幂等；每次写入后调用，量小可接受）。 */
    private void prune(Long projectId) {
        try {
            long total = traceRepository.countByProjectId(projectId);
            if (total <= KEEP_PER_PROJECT) {
                return;
            }
            int excess = (int) (total - KEEP_PER_PROJECT);
            List<Long> ids = traceRepository
                    .findByProjectIdOrderByIdAsc(projectId, PageRequest.of(0, excess))
                    .stream().map(ActorWorldMacroTrace::getId).toList();
            if (!ids.isEmpty()) {
                traceRepository.deleteAllById(ids);
            }
        } catch (Exception e) {
            log.warn("[宏观 trace] 滚动裁剪失败（隔离继续）: project={} : {}", projectId, e.getMessage());
        }
    }

    /** 本游戏日宏观事件主键列表（幂等：按项目+游戏日区间查询）。 */
    private List<Long> eventIds(Long projectId, Long day) {
        List<Long> ids = new ArrayList<>();
        try {
            List<ActorWorldMacroEvent> evs = eventRepository
                    .findByProjectIdAndGameDayBetweenOrderByGameDayAsc(projectId, day, day);
            for (ActorWorldMacroEvent ev : evs) {
                if (ev.getId() != null) {
                    ids.add(ev.getId());
                }
            }
        } catch (Exception ignored) {
            // 事件查询失败不阻断 trace 落库
        }
        return ids;
    }

    /** 编年史「宏观」徽标条数（当日；tags JSON 保守 LIKE 匹配）。 */
    private long chronicleMacroCount(Long projectId, Long day) {
        try {
            return chronicleRepository.countByProjectIdAndGameDayAndTagsContaining(
                    projectId, day, TAG_MACRO_FRAGMENT);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 宏观来源信息包条数（info_id macro: 前缀；失败回退 0 不阻断 trace 落库）。 */
    private long macroPacketCount(Long projectId) {
        try {
            return packetRepository.countByProjectIdAndInfoIdStartingWith(projectId, MACRO_PACKET_PREFIX);
        } catch (Exception e) {
            return 0L;
        }
    }

    // ==================== 读取 ====================

    /**
     * 读取最近 N 条 trace（tickType 可空=全部；limit 缺省 50，上限 200）。
     */
    public List<MacroTraceVO> listVOs(Long projectId, String tickType, Integer limit) {
        int lim = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        List<MacroTraceVO> out = new ArrayList<>();
        List<ActorWorldMacroTrace> rows;
        try {
            rows = traceRepository.findRecent(projectId, tickType, PageRequest.of(0, lim));
        } catch (Exception e) {
            log.warn("[宏观 trace] 读取失败（返回空）: project={} : {}", projectId, e.getMessage());
            return out;
        }
        for (ActorWorldMacroTrace t : rows) {
            out.add(MacroTraceVO.from(t,
                    parseJson(t.getInputJson()), parseJson(t.getParamsJson()),
                    parseIds(t.getEventIdsJson()), parseJson(t.getFeedbackJson())));
        }
        return out;
    }

    // ==================== JSON 工具 ====================

    private String toJson(Map<String, Object> map) {
        try {
            return map == null || map.isEmpty() ? null : objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(List<Long> ids) {
        try {
            return ids == null || ids.isEmpty() ? null : objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            var node = objectMapper.readTree(json);
            Map<String, Object> out = new LinkedHashMap<>();
            if (node.isObject()) {
                node.properties().forEach(e -> out.put(e.getKey(), valueOf(e.getValue())));
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Long> ids = new ArrayList<>();
            var node = objectMapper.readTree(json);
            if (node.isArray()) {
                for (var n : node) {
                    ids.add(n.asLong());
                }
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Jackson 3 节点 → 普通值（Map/List/标量，供 VO 序列化）。 */
    private static Object valueOf(tools.jackson.databind.JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            n.properties().forEach(e -> m.put(e.getKey(), valueOf(e.getValue())));
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            n.forEach(x -> l.add(valueOf(x)));
            return l;
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isFloatingPointNumber()) {
            return n.asDouble();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        return n.asText();
    }
}
