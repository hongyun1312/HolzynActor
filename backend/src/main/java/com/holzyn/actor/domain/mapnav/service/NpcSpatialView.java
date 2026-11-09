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

import com.holzyn.actor.domain.mapgen.entity.ActorMapFeature;
import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPosition;
import com.holzyn.actor.domain.mapnav.vo.SpatialFix;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 空间读取的统一口径（<b>只读</b>）——「三方对账」的第一腿与第三腿都由它供数。
 *
 * <p><b>它解决的是什么</b>：位置在库里有两份（{@code actor_npc_position} 米制权威、
 * {@code actor_sandbox_state} 旧沙盘场景格）。以前 AI 侧读沙盘、地图页读权威、决策又读沙盘，
 * 于是「AI 以为他在哪」和「图上他在哪」天然两套。本类把「读哪一份」收成一句话：
 * <b>权威优先，为空回退沙盘，回退必须留 {@code source}</b>。</p>
 *
 * <p><b>为什么不是第二个门面</b>：写入口仍然只有 {@link NpcPositionService}（`AGENTS.md` §8.1 第 4 条）。
 * 本类不写任何表、不做缓存、不做换算，只做「同一份字段集的统一投影」，因此不会与写入口形成第二真相。</p>
 *
 * <p><b>为什么可达候选用直线距离</b>：候选清单每次 AI 调用都要算，逐点 A* 会是 5 次寻路/角色/次。
 * 这里用「直线距离 ≤ 400 米」作为可达上界，并在文本与返回里标注 {@code straightLine=true}——
 * 它是<b>确定性</b>的（同一位置必然同一清单），而重访判据要的正是确定性。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NpcSpatialView {

    /** 候选点半径（米）：超过它不算「附近」 */
    public static final double CANDIDATE_RADIUS_M = 400.0;

    /** 注入/对账默认候选条数 */
    public static final int DEFAULT_CANDIDATE_LIMIT = 5;

    /**
     * 「旁边有人」的默认半径（米）。
     *
     * <p>取 60 米：对齐旧沙盘口径（500 场景域里 30 格 ≈ 全图 6%），换到 1024 米制域约 60 米；
     * 再大就会把「隔了两条街的人」写成「正从身旁经过」。</p>
     */
    public static final double DEFAULT_NEARBY_RADIUS_M = 60.0;

    /** 游戏刻长（秒）：滞后上限的基准（感知周期为 5 游戏分钟） */
    public static final long TICK_SECONDS = 300L;

    private final NpcPositionService positionService;

    private final ActorSandboxStateRepository sandboxStateRepository;

    private final ActorMapFeatureRepository featureRepository;

    private final ActorWorldMapRepository mapRepository;

    private final ActorWorldClockRepository clockRepository;

    /**
     * 取某角色此刻的空间定值（权威优先，沙盘回退）。
     *
     * @param projectId 项目
     *
     * @param npcRef    角色引用
     *
     * @return 定值；两处都没有该角色返回 empty
     */
    public Optional<SpatialFix> resolve(Long projectId, String npcRef) {
        return resolve(projectId, npcRef, null);
    }

    /**
     * 取某角色在指定游戏秒的空间定值（对账探针用同一个入口，避免「探针算一套、线上算另一套」）。
     *
     * @param projectId  项目
     *
     * @param npcRef     角色引用
     *
     * @param gameSecond 目标游戏秒（null=用权威行自带秒 / 世界时钟秒）
     *
     * @return 定值
     */
    public Optional<SpatialFix> resolve(Long projectId, String npcRef, Long gameSecond) {
        if (projectId == null || npcRef == null || npcRef.isBlank()) {
            return Optional.empty();
        }
        SpatialFix fix = authorityFix(projectId, npcRef);
        if (fix == null) {
            fix = sandboxFix(projectId, npcRef);
        }
        if (fix == null) {
            return Optional.empty();
        }
        if (gameSecond != null) {
            fix.setGameSecond(gameSecond);
        } else if (fix.getGameSecond() <= 0 || !isWalking(fix.getStatus())) {
            // 静止行：位置自最后一次变更起一直有效，对外统一取引擎推进秒。
            // 在途行：坐标是瞬时值，只认行内秒（它的秒每 tick 都会被刷新）。
            fix.setGameSecond(engineGameSecond(projectId));
        }
        return Optional.of(fix);
    }

    /** 是否在途（{@code actor_npc_position.status = walking}）。 */
    private static boolean isWalking(String status) {
        return status != null && "walking".equalsIgnoreCase(status.trim());
    }

    /**
     * 权威行列表（对账探针用：先按权威表枚举角色，缺失的再用沙盘补齐）。
     *
     * @param projectId 项目
     *
     * @return 权威行（读失败返回空表）
     */
    public List<ActorNpcPosition> listAuthority(Long projectId) {
        try {
            return positionService.list(projectId);
        } catch (Exception e) {
            log.debug("[mapnav] 读权威坐标列表失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 世界推进秒（唯一时间口径）。
     *
     * @param projectId 项目
     *
     * @return 游戏秒；读不到返回 0
     */
    public long engineGameSecond(Long projectId) {
        try {
            ActorWorldClock clock = clockRepository.findByProjectId(projectId).orElse(null);
            if (clock != null && clock.getLastGameSecond() != null) {
                return clock.getLastGameSecond();
            }
        } catch (Exception e) {
            log.debug("[mapnav] 读世界时钟失败：{}", e.getMessage());
        }
        return 0L;
    }

    /**
     * 附近候选点清单（确定性：同一位置 → 同一清单；排序 = 距离升序、距离相同按 code 升序）。
     *
     * @param projectId 项目
     *
     * @param x         参考点 X（米）
     *
     * @param z         参考点 Z（米）
     *
     * @param limit     最多几条（≤0 用默认 5）
     *
     * @return 候选（无地图返回空表）
     */
    public List<Candidate> candidates(Long projectId, double x, double z, int limit) {
        int cap = limit <= 0 ? DEFAULT_CANDIDATE_LIMIT : limit;
        List<Candidate> out = new ArrayList<>();
        ActorWorldMap map = latestMap(projectId);
        if (map == null) {
            return out;
        }
        try {
            for (ActorMapFeature f : featureRepository.findByMapIdOrderBySortOrderAsc(map.getId())) {
                double[] p = spotOf(f);
                if (p == null) {
                    continue;
                }
                double dist = Math.hypot(p[0] - x, p[1] - z);
                if (dist > CANDIDATE_RADIUS_M) {
                    continue;
                }
                Candidate c = new Candidate();
                c.setRef(f.getCode());
                c.setName(f.getName());
                c.setKind(f.getKind());
                c.setX(round2(p[0]));
                c.setZ(round2(p[1]));
                c.setDistanceM(round2(dist));
                c.setBearing(bearingText(x, z, p[0], p[1]));
                c.setStraightLine(true);
                out.add(c);
            }
        } catch (Exception e) {
            log.debug("[mapnav] 读候选要素失败：{}", e.getMessage());
            return out;
        }
        out.sort(Comparator
                .comparingDouble(Candidate::getDistanceM)
                .thenComparing(c -> String.valueOf(c.getRef())));
        return out.size() <= cap ? out : new ArrayList<>(out.subList(0, cap));
    }

    /**
     * 要素名（注入腿「我在哪」的文本源）。
     *
     * <p><b>为什么不用 {@code actor_world_location} 反查</b>：真实世界里该表的
     * {@code center_x/center_z} 是空的（世界 33 实测 9 行全空），于是
     * {@code WorldMapQueryService.regionAt} 永远返回 null——「当前位置与方向」段以前依赖它，
     * 命中不了就整段不注入（实测 47 条决策 prompt 无一含该段）。权威行自带
     * {@code feature_code}，直接换名是最可靠的「我在哪」。</p>
     *
     * @param projectId   项目
     *
     * @param featureCode 要素 code（权威行原值）
     *
     * @return 要素名；查不到返回 null
     */
    public String featureName(Long projectId, String featureCode) {
        if (featureCode == null || featureCode.isBlank()) {
            return null;
        }
        ActorWorldMap map = latestMap(projectId);
        if (map == null) {
            return null;
        }
        String want = featureCode.trim();
        try {
            for (ActorMapFeature f : featureRepository.findByMapIdOrderBySortOrderAsc(map.getId())) {
                if (f.getCode() != null && want.equalsIgnoreCase(f.getCode().trim())) {
                    return f.getName();
                }
            }
        } catch (Exception e) {
            log.debug("[mapnav] 读要素名失败：{}", e.getMessage());
        }
        return null;
    }

    /**
     * 附近角色（<b>权威口径</b>，米制）：按权威行直线距离取最近的若干名。
     *
     * <p><b>为什么不复用 {@code WorldMapQueryService.nearbyRefs}</b>：它读沙盘行、按沙盘格距
     * 比较，而沙盘是 0..500 场景域、权威是 0..1024 米制域——两套坐标混用，算出来的
     * 「谁在我旁边」是错的。位置只认权威，距离也只在权威域里算。</p>
     *
     * @param projectId  项目
     *
     * @param x          参考点 X（米）
     *
     * @param z          参考点 Z（米）
     *
     * @param excludeRef 排除的角色（自己）
     *
     * @param radiusM    半径（米；≤0 用默认 60 米）
     *
     * @param limit      最多几名（≤0 用 2）
     *
     * @return 附近角色（按距离升序）
     */
    public List<SpatialFix> nearbyAuthority(Long projectId, double x, double z,
                                            String excludeRef, double radiusM, int limit) {
        List<SpatialFix> out = new ArrayList<>();
        int cap = limit <= 0 ? 2 : limit;
        double r = radiusM <= 0 ? DEFAULT_NEARBY_RADIUS_M : radiusM;
        try {
            for (ActorNpcPosition row : listAuthority(projectId)) {
                if (row == null || row.getNpcRef() == null
                        || row.getX() == null || row.getZ() == null) {
                    continue;
                }
                if (excludeRef != null && excludeRef.equalsIgnoreCase(row.getNpcRef())) {
                    continue;
                }
                if (Math.hypot(row.getX() - x, row.getZ() - z) > r) {
                    continue;
                }
                SpatialFix f = new SpatialFix();
                f.setNpcRef(row.getNpcRef());
                f.setNpcName(row.getNpcName());
                f.setX(row.getX());
                f.setZ(row.getZ());
                f.setStatus(row.getStatus());
                f.setActivity(row.getActivity());
                f.setFeatureCode(row.getFeatureCode());
                f.setSource(SpatialFix.SOURCE_AUTHORITY);
                out.add(f);
            }
        } catch (Exception e) {
            log.debug("[mapnav] 附近角色（权威）查询失败：{}", e.getMessage());
            return out;
        }
        out.sort(Comparator.comparingDouble(f -> Math.hypot(f.getX() - x, f.getZ() - z)));
        return out.size() <= cap ? out : new ArrayList<>(out.subList(0, cap));
    }

    /**
     * 候选清单的稳定指纹（对账/重访比对用：位置 + 距离 + 方位全进指纹）。
     *
     * @param candidates 候选
     *
     * @return 指纹（无候选返回 {@code -}）
     */
    public static String candidateFingerprint(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (Candidate c : candidates) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(c.getRef()).append('@')
                    .append(String.format(Locale.ROOT, "%.2f", c.getDistanceM())).append(',')
                    .append(c.getBearing());
        }
        return sb.toString();
    }

    /**
     * 规范化坐标 token（<b>三方对账的唯一依据</b>）。
     *
     * <p>格式固定为 {@code (x.xx,z.zz)@gs=NNN}：两位小数、逗号分隔、无空格。
     * 之所以要「无空格 + 固定小数位」，是因为对账脚本要能从真实 prompt 文本里正则抽出来，
     * 而 {@code 123.4} 与 {@code 123.40} 会被当成两个不同的值。</p>
     *
     * @param x          X
     *
     * @param z          Z
     *
     * @param gameSecond 游戏秒
     *
     * @return token
     */
    public static String token(double x, double z, long gameSecond) {
        return String.format(Locale.ROOT, "(%.2f,%.2f)@gs=%d", x, z, gameSecond);
    }

    /**
     * 从任意文本里抽第一个坐标 token（对账脚本与单测共用同一份正则口径）。
     *
     * @param text 文本（可空）
     *
     * @return {@code [x, z, gs]}；没抽到返回 null
     */
    public static double[] parseToken(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = TOKEN_PATTERN.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            return new double[]{
                    Double.parseDouble(m.group(1)),
                    Double.parseDouble(m.group(2)),
                    Double.parseDouble(m.group(3))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** token 正则（唯一口径） */
    private static final java.util.regex.Pattern TOKEN_PATTERN =
            java.util.regex.Pattern.compile("\\((-?\\d+\\.\\d{2}),(-?\\d+\\.\\d{2})\\)@gs=(\\d+)");

    // ==================== 内部 ====================

    /** 权威行 → 定值（没有返回 null）。 */
    private SpatialFix authorityFix(Long projectId, String npcRef) {
        try {
            ActorNpcPosition row = positionService.find(projectId, npcRef).orElse(null);
            if (row == null) {
                return null;
            }
            SpatialFix fix = new SpatialFix();
            fix.setNpcRef(row.getNpcRef());
            fix.setNpcName(row.getNpcName());
            fix.setX(row.getX() == null ? 0 : row.getX());
            fix.setZ(row.getZ() == null ? 0 : row.getZ());
            fix.setHeading(row.getHeading() == null ? 0 : row.getHeading());
            fix.setFeatureCode(row.getFeatureCode());
            fix.setGameSecond(row.getGameSecond() == null ? 0 : row.getGameSecond());
            fix.setLastChangedGameSecond(fix.getGameSecond());
            fix.setStatus(row.getStatus());
            fix.setActivity(row.getActivity());
            fix.setIndoor(row.getIndoor() != null && row.getIndoor() == 1);
            fix.setSource(SpatialFix.SOURCE_AUTHORITY);
            return fix;
        } catch (Exception e) {
            log.debug("[mapnav] 读权威坐标失败：{}", e.getMessage());
            return null;
        }
    }

    /** 沙盘行 → 定值（兼容回退；没有返回 null）。 */
    private SpatialFix sandboxFix(Long projectId, String npcRef) {
        if (sandboxStateRepository == null) {
            return null;
        }
        try {
            ActorSandboxState st = sandboxStateRepository
                    .findByProjectIdAndNpcRef(projectId, npcRef).orElse(null);
            if (st == null || st.getPosX() == null || st.getPosZ() == null) {
                return null;
            }
            SpatialFix fix = new SpatialFix();
            fix.setNpcRef(npcRef);
            fix.setX(st.getPosX());
            fix.setZ(st.getPosZ());
            fix.setHeading(st.getHeading() == null ? 0 : Math.toDegrees(st.getHeading()));
            fix.setGameSecond(engineGameSecond(projectId));
            fix.setStatus(st.getMoveState());
            fix.setActivity(st.getActionText());
            fix.setSource(SpatialFix.SOURCE_SANDBOX);
            return fix;
        } catch (Exception e) {
            log.debug("[mapnav] 读沙盘坐标失败：{}", e.getMessage());
            return null;
        }
    }

    /** 最新一张图。 */
    private ActorWorldMap latestMap(Long projectId) {
        try {
            return mapRepository.findFirstByProjectIdOrderByIdDesc(projectId).orElse(null);
        } catch (Exception e) {
            log.debug("[mapnav] 读地图失败：{}", e.getMessage());
            return null;
        }
    }

    /** 要素落点：门优先，其次几何中心。 */
    private static double[] spotOf(ActorMapFeature f) {
        if (f == null) {
            return null;
        }
        if (f.getDoorX() != null && f.getDoorZ() != null) {
            return new double[]{f.getDoorX(), f.getDoorZ()};
        }
        if (f.getPosX() != null && f.getPosZ() != null) {
            return new double[]{f.getPosX(), f.getPosZ()};
        }
        return null;
    }

    /** 八方位文本（0=北，顺时针）。 */
    static String bearingText(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9) {
            return "此处";
        }
        double deg = (Math.toDegrees(Math.atan2(dx, -dz)) + 360) % 360;
        String[] names = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        return names[(int) Math.round(deg / 45.0) % 8];
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 附近候选点（确定性清单的一项）。 */
    @Data
    public static class Candidate {

        /** 要素 code（也是 AI 可用的 targetRef） */
        private String ref;

        /** 名称 */
        private String name;

        /** 类型（building / road / …） */
        private String kind;

        /** 落点 X（米） */
        private double x;

        /** 落点 Z（米） */
        private double z;

        /** 直线距离（米，两位小数） */
        private double distanceM;

        /** 八方位 */
        private String bearing;

        /** 是否直线距离（true=未做逐点寻路） */
        private boolean straightLine = true;
    }

    /**
     * 供对账探针使用：把候选清单按 code 归并成 map（顺序无关的比较口径）。
     *
     * @param candidates 候选
     *
     * @return code → 距离/方位
     */
    public static Map<String, String> candidateIndex(List<Candidate> candidates) {
        Map<String, String> m = new LinkedHashMap<>();
        if (candidates == null) {
            return m;
        }
        for (Candidate c : candidates) {
            m.put(String.valueOf(c.getRef()),
                    String.format(Locale.ROOT, "%.2f/%s", c.getDistanceM(), c.getBearing()));
        }
        return m;
    }
}
