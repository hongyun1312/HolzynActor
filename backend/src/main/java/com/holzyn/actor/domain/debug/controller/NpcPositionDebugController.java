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

package com.holzyn.actor.domain.debug.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.evaluation.TestRunService;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition;
import com.holzyn.actor.domain.mapnav.service.NpcPositionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色坐标的测试操作接口（反事实环境实验专用）。
 *
 * <p><b>为什么需要它</b>：要验证「同一角色、同一目标，只换环境时行为分布是否改变」，
 * 必须能把同一个角色依次摆到三个真实地点上再触发决策；而坐标的唯一写入口
 * {@link NpcPositionService} 只有世界引擎内部调用，没有对外接口。这里只做一件事：
 * 在测试门控下把一次坐标写入转交给那个唯一写入口，绝不另开第二个写库路径。</p>
 *
 * <p><b>安全约定</b>：写操作走 {@link TestRunService#requireTestEnabled()} 门控
 * （{@code holzyn.actor.evaluation.test-enabled=true} 才可用，默认 403）；
 * 读操作保持只读。实验结束必须用同一接口把坐标还原——报告里要留下还原前后的两条记录。</p>
 *
 * <p>所属模块：domain/debug（调试与实验工具）</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/worlds/{projectId}/debug/npc-position")
@RequiredArgsConstructor
public class NpcPositionDebugController {

    /** 坐标唯一写入口（只调它，不直接碰表） */
    private final NpcPositionService positionService;

    /** 测试门控 */
    private final TestRunService testRunService;

    /**
     * 移动状态机（可选依赖）：摆位时取消在途移动，避免旧路径行继续被感知念成「正走在去某地」。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.worldmap.service.MovementService movementService;

    /**
     * 目标解析器（可选依赖）：验证「AI 说去哪 → 服务端换坐标」这一跳是否真的成立。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.mapnav.service.TargetResolver targetResolver;

    /**
     * 只读：把一句口语目标解析成落点（测试门控）。
     *
     * <p><b>为什么需要这个只读接口</b>：位置可信的最后一跳是「模型只说去哪，服务端换坐标」。
     * 这一跳此前是静默的——{@code target=""} 或名字对不上就原地不动，而日志只到 debug。
     * 有了它就能用一句真实动作文本（如「顺路去米行讨碗水喝」）直接验证整条链：
     * 剥目的地 → 模糊匹配要素 → 可站格纠正，并把 {@code resolvedBy} 暴露出来。</p>
     *
     * @param projectId 项目
     *
     * @param q         目标文本（口语即可）
     *
     * @return 解析结果；解析不到返回 resolved=false（不抛错，便于批量探针）
     */
    @GetMapping("/resolve-target")
    public R<Map<String, Object>> resolveTarget(@PathVariable("projectId") Long projectId,
                                                @org.springframework.web.bind.annotation.RequestParam("q") String q) {
        testRunService.requireTestEnabled();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("q", q);
        m.put("destination", com.holzyn.actor.domain.mapnav.service.TargetResolver.destinationText(q));
        if (targetResolver == null) {
            m.put("resolved", false);
            m.put("reason", "TargetResolver 未装配");
            return R.ok(m);
        }
        var r = targetResolver.resolve(projectId, q).orElse(null);
        m.put("resolved", r != null);
        if (r != null) {
            m.put("targetRef", r.getTargetRef());
            m.put("featureCode", r.getFeatureCode());
            m.put("x", r.getX());
            m.put("z", r.getZ());
            m.put("resolvedBy", r.getResolvedBy());
            m.put("snapped", r.isSnapped());
        }
        return R.ok(m);
    }

    /**
     * 只读：列出本项目全部角色坐标（实验前备份、实验后核对还原）。
     *
     * @param projectId 项目
     *
     * @return 坐标行
     */
    @GetMapping
    public R<List<Map<String, Object>>> list(@PathVariable("projectId") Long projectId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ActorNpcPosition p : positionService.list(projectId)) {
            rows.add(view(p));
        }
        return R.ok(rows);
    }

    /**
     * 测试操作：把某个角色写到指定坐标（经唯一写入口）。
     *
     * <p>body：{@code npcRef, x, z, heading?, indoor?, activity?, featureCode?, status?}；
     * 地图 ID 与角色名默认沿用该角色已有行，避免实验过程把归属改掉。</p>
     *
     * @param projectId 项目
     *
     * @param body      请求体
     *
     * @return 写入后的坐标行
     */
    @PostMapping
    public R<Map<String, Object>> upsert(@PathVariable("projectId") Long projectId,
                                         @RequestBody Map<String, Object> body) {
        testRunService.requireTestEnabled();
        String npcRef = body == null ? null : str(body.get("npcRef"));
        if (npcRef == null || npcRef.isBlank()) {
            throw new BizException(400, "npcRef 必填");
        }
        Double x = dbl(body.get("x"));
        Double z = dbl(body.get("z"));
        if (x == null || z == null) {
            throw new BizException(400, "x/z 必填（米制地图坐标）");
        }
        ActorNpcPosition old = positionService.find(projectId, npcRef).orElse(null);
        Long mapId = body.get("mapId") == null
                ? (old == null ? null : old.getMapId()) : ((Number) body.get("mapId")).longValue();
        if (mapId == null) {
            throw new BizException(400, "该角色尚无坐标行，必须显式给 mapId");
        }
        Double heading = dbl(body.get("heading"));
        String activity = body.get("activity") == null ? null : str(body.get("activity"));
        String featureCode = body.get("featureCode") == null ? null : str(body.get("featureCode"));
        String status = body.get("status") == null ? "static" : str(body.get("status"));
        boolean indoor = body.get("indoor") != null && Boolean.parseBoolean(String.valueOf(body.get("indoor")));
        long gameSecond = body.get("gameSecond") == null ? 0L : ((Number) body.get("gameSecond")).longValue();
        // 摆位前先取消该角色的在途移动：留着旧路径行，感知会继续念「正走在去X的路上」，
        // 而人已经被摆到别处——实验数据立刻被这句假叙述污染（实测踩过一次）。
        if (movementService != null) {
            try {
                movementService.cancelMove(projectId, npcRef, gameSecond);
            } catch (Exception e) {
                log.warn("[反事实实验] 取消失败（继续摆位）: ref={} : {}", npcRef, e.getMessage());
            }
        }
        ActorNpcPosition row = positionService.upsert(projectId, mapId, npcRef,
                body.get("npcName") == null ? null : str(body.get("npcName")),
                x, z, heading == null ? 0d : heading, indoor, activity, featureCode, status, gameSecond);
        log.info("[反事实实验] 坐标写入：project={} ref={} → ({}, {}) feature={} indoor={} status={}（原位置 {}）",
                projectId, npcRef, x, z, featureCode, indoor, status,
                old == null ? "无" : "(" + old.getX() + ", " + old.getZ() + ")");
        return R.ok(view(row));
    }

    /** 坐标行视图（只暴露实验需要的字段）。 */
    private Map<String, Object> view(ActorNpcPosition p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("npcRef", p.getNpcRef());
        m.put("npcName", p.getNpcName());
        m.put("mapId", p.getMapId());
        m.put("x", p.getX());
        m.put("z", p.getZ());
        m.put("heading", p.getHeading());
        m.put("indoor", p.getIndoor());
        m.put("activity", p.getActivity());
        m.put("featureCode", p.getFeatureCode());
        m.put("status", p.getStatus());
        m.put("gameSecond", p.getGameSecond());
        return m;
    }

    /** 字符串取值。 */
    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** 数值取值（兼容 Integer/Double/String）。 */
    private static Double dbl(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
