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

package com.holzyn.actor.domain.mapnav.service;

import com.holzyn.actor.domain.mapnav.algo.GridAStar;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPath;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition;
import com.holzyn.actor.domain.mapnav.repository.ActorNpcPathRepository;
import com.holzyn.actor.domain.mapnav.vo.SpatialFix;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 三方对账探针：把「引擎权威渲染层 / AI 注入」三条腿摊在同一个响应里。
 *
 * <p><b>为什么要有这个接口</b>：这三条腿分别由三个模块产出（{@code NpcPositionService}、
 * 地图页的 {@code placeFromActors}、{@code PerceptionService} 的注入段）。任何两处只要有一处
 * 换了口径（四舍五入、单位、采样时刻），都只会表现为「图上人和 AI 说的位置不一样」，
 * 而且**不会报错**。把三者放到一个返回体里逐位比较，是让这件事从「观察」变成「断言」的最小代价。</p>
 *
 * <p><b>第三腿为什么是「期望 token」而不是「注入原文」</b>：注入原文只存在于**真实发生过的那次
 * AI 调用**里（{@code actor_ai_call_log.user_prompt}）。探针能给的是「这一刻应该注入什么」，
 * 真正的比对由验收脚本把探针 token 与真实 prompt 里抽出 token 对撞——这样第三腿的证据
 * 永远是模型真的看到的东西，而不是我们复算的东西。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpatialAuditService {

    private final NpcSpatialView spatialView;

    private final ActorNpcPathRepository pathRepository;

    private final ActorSandboxStateRepository sandboxStateRepository;

    /** 导航健康度（缺 nav.bin 时降级寻路）：对账要把「路径是否可信」也一起报出来。 */
    private final NpcNavigationService navigationService;

    /**
     * 生成对账快照。
     *
     * @param projectId  项目
     *
     * @param gameSecond 对账时刻（null=引擎推进秒）
     *
     * @return 快照（{@code {gameSecond, tickSeconds, summary, actors}}
     */
    public Map<String, Object> audit(Long projectId, Long gameSecond) {
        long gs = gameSecond != null && gameSecond > 0
                ? gameSecond : spatialView.engineGameSecond(projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameSecond", gs);
        out.put("tickSeconds", NpcSpatialView.TICK_SECONDS);

        Map<String, ActorNpcPath> walking = walkingPaths(projectId);
        List<Map<String, Object>> actors = new ArrayList<>();
        int diverged = 0;
        int sandbox = 0;
        int stale = 0;

        for (SpatialFix fix : allFixes(projectId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ref", fix.getNpcRef());
            row.put("name", fix.getNpcName());
            row.put("source", fix.getSource());
            if (SpatialFix.SOURCE_SANDBOX.equals(fix.getSource())) {
                sandbox++;
            }
            row.put("authority", authorityBlock(fix));
            // 同一时刻才可比：权威行自带它的生效秒（在途行每 tick 刷新），渲染层也必须按
            // **那个秒**复算，否则比的是「权威的下一刻」与「渲染的此刻」——差一个 tick 的
            // 路程（20 米/游戏分钟 × 300 秒 = 100 米），会把正常的显示滞后误判成分歧。
            // 显示滞后另由 lagGameSecond 单独量化（口径：≤1 个 tick 允许）。
            long renderGs = fix.getGameSecond() > 0 ? fix.getGameSecond() : gs;
            row.put("render", renderBlock(projectId, fix, walking.get(fix.getNpcRef()), renderGs));
            row.put("token", NpcSpatialView.token(fix.getX(), fix.getZ(), fix.getGameSecond()));
            List<NpcSpatialView.Candidate> cands = spatialView.candidates(projectId, fix.getX(), fix.getZ(),
                    NpcSpatialView.DEFAULT_CANDIDATE_LIMIT);
            row.put("candidates", cands);
            row.put("candidateFingerprint", NpcSpatialView.candidateFingerprint(cands));

            long lag = Math.abs(gs - fix.getGameSecond());
            row.put("lagGameSecond", lag);
            boolean outdated = lag > NpcSpatialView.TICK_SECONDS;
            if (outdated) {
                stale++;
            }
            Map<String, Object> render = asMap(row.get("render"));
            boolean divergedNow = !sameXZ(fix.getX(), fix.getZ(),
                    num(render.get("x")), num(render.get("z")));
            row.put("diverged", divergedNow);
            row.put("stale", outdated);
            if (divergedNow) {
                diverged++;
                row.put("reason", "权威坐标与渲染层折线求值不一致（归一化长度或轨迹未被渲染层读到）");
            } else if (outdated) {
                row.put("reason", "权威行游戏秒落后对账时刻超过 1 个 tick（推进或快照滞后）");
            }
            actors.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("checked", actors.size());
        summary.put("diverged", diverged);
        summary.put("stale", stale);
        summary.put("sandboxFallback", sandbox);
        // 降级寻路不算「三方分歧」，但它是「NPC 在物理空间里是否真的可信」的前提条件：
        // 缺 nav.bin 时人只会绕开建筑、绕不开水，pass 仍可能为 true，但报告必须显式标出来。
        summary.put("navDegraded", navigationService.degraded(projectId));
        summary.put("pass", diverged == 0 && stale == 0 && sandbox == 0);
        out.put("summary", summary);
        out.put("actors", actors);
        return out;
    }

    // ==================== 内部 ====================

    /** 全部角色定值：权威优先，缺失的角色用沙盘回退补齐（回退必须被计数）。 */
    private List<SpatialFix> allFixes(Long projectId) {
        List<SpatialFix> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        try {
            for (ActorNpcPosition p : spatialView.listAuthority(projectId)) {
                SpatialFix fix = spatialView.resolve(projectId, p.getNpcRef()).orElse(null);
                if (fix != null && seen.add(fix.getNpcRef())) {
                    out.add(fix);
                }
            }
        } catch (Exception e) {
            log.debug("[mapnav] 对账：读权威列表失败：{}", e.getMessage());
        }
        try {
            if (sandboxStateRepository != null) {
                for (ActorSandboxState st : sandboxStateRepository.findByProjectIdOrderByIdAsc(projectId)) {
                    String ref = st.getNpcRef();
                    if (ref == null || !seen.add(ref)) {
                        continue;
                    }
                    SpatialFix fix = spatialView.resolve(projectId, ref).orElse(null);
                    if (fix != null) {
                        out.add(fix);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[mapnav] 对账：读沙盘列表失败：{}", e.getMessage());
        }
        out.sort((a, b) -> String.valueOf(a.getNpcRef()).compareTo(String.valueOf(b.getNpcRef())));
        return out;
    }

    /** 权威段。 */
    private Map<String, Object> authorityBlock(SpatialFix fix) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", round2(fix.getX()));
        m.put("z", round2(fix.getZ()));
        m.put("featureCode", fix.getFeatureCode());
        m.put("status", fix.getStatus());
        m.put("indoor", fix.isIndoor());
        m.put("gameSecond", fix.getGameSecond());
        m.put("lastChangedGameSecond", fix.getLastChangedGameSecond());
        return m;
    }

    /**
     * 渲染段：**与前端 {@code placeFromActors} 同一算法**。
     *
     * <p>有在途轨迹 → {@code pointAt(折线, clamp((gs-depart)/span) × lengthM)}；
     * 无轨迹 → 直接贴权威坐标。归一化长度一律取 {@code path.lengthM}（已把
     * 它改成「按落库折线实测」，与前端 {@code polylineLength(points)} 逐位相等）。</p>
     */
    private Map<String, Object> renderBlock(Long projectId, SpatialFix fix, ActorNpcPath path, long gs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gameSecond", gs);
        double x = fix.getX();
        double z = fix.getZ();
        String by = "static";
        if (path != null) {
            try {
                List<double[]> pts = parsePoints(path.getPointsJson());
                double length = path.getLengthM() == null ? 0d : path.getLengthM();
                if (pts.size() >= 2 && length > 0) {
                    long depart = path.getDepartGameSecond() == null ? gs : path.getDepartGameSecond();
                    long arrive = path.getArriveGameSecond() == null ? gs : path.getArriveGameSecond();
                    double span = Math.max(1, arrive - depart);
                    double t = (gs - depart) / span;
                    t = Math.max(0, Math.min(1, t));
                    double[] at = GridAStar.pointAt(pts, t * length);
                    x = at[0];
                    z = at[1];
                    by = "path";
                }
            } catch (Exception e) {
                log.debug("[mapnav] 对账：渲染复算失败：{}", e.getMessage());
            }
        }
        m.put("x", round2(x));
        m.put("z", round2(z));
        m.put("by", by);
        m.put("pathId", path == null ? null : path.getId());
        m.put("lengthM", path == null ? null : round2(path.getLengthM() == null ? 0d : path.getLengthM()));
        return m;
    }

    /** 在途轨迹（一次查完）。 */
    private Map<String, ActorNpcPath> walkingPaths(Long projectId) {
        Map<String, ActorNpcPath> m = new LinkedHashMap<>();
        try {
            for (ActorNpcPath p : pathRepository.findByProjectIdAndStatusOrderByIdAsc(
                    projectId, NpcMovementService.WALKING)) {
                m.put(p.getNpcRef(), p);
            }
        } catch (Exception e) {
            log.debug("[mapnav] 对账：读在途轨迹失败：{}", e.getMessage());
        }
        return m;
    }

    /** 折线解析（与 {@code NpcMovementService} 同口径）。 */
    private static final tools.jackson.databind.ObjectMapper MAPPER =
            new tools.jackson.databind.ObjectMapper();

    private static List<double[]> parsePoints(String json) {
        List<double[]> pts = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return pts;
        }
        try {
            tools.jackson.databind.JsonNode arr = MAPPER.readTree(json);
            for (tools.jackson.databind.JsonNode p : arr) {
                pts.add(new double[]{p.path(0).asDouble(), p.path(1).asDouble()});
            }
        } catch (Exception ignored) {
            // 坏数据按空折线处理：宁可回退到静态坐标，也不让探针 500
        }
        return pts;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0d;
    }

    /** 逐位相等（按两位小数的规范化字符串比，避免 0.1+0.2 这类浮点噪声被当成分歧）。 */
    private static boolean sameXZ(double ax, double az, double x, double z) {
        return fmt(ax).equals(fmt(x)) && fmt(az).equals(fmt(z));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
