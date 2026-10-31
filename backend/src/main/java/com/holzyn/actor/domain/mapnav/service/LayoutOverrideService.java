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
import com.holzyn.actor.domain.mapgen.service.MapPersistService;
import com.holzyn.actor.domain.mapnav.entity.ActorMapFeatureOverride;
import com.holzyn.actor.domain.mapnav.repository.ActorMapFeatureOverrideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 布局覆盖层：手改的写入、重放与清除。
 *
 * <p><b>为什么手改不直接写要素本体</b>：S3/S4 一重跑就把要素整张删了重建。覆盖层让
 * 「用户的手改」与「AI 生成的地图」分开存：重跑后按 {@code feature_code} 把坐标贴回去，
 * 贴不上就保留（等下次重生成再试）并提示——绝不会静默丢掉用户十分钟的拖拽。</p>
 *
 * <p><b>为什么清除是安全的</b>：要素本体的坐标从未被覆盖写死想过「原值」这种修复路径——
 * 覆盖层一删，视图立刻回到 AI 原版。这是「不改本体」换来的最大好处。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LayoutOverrideService {

    private final ActorMapFeatureOverrideRepository overrideRepository;
    private final ActorMapFeatureRepository featureRepository;
    private final ActorWorldMapRepository mapRepository;
    private final MapPersistService persist;

    /**
     * 某项目的全部手改。
     *
     * @param projectId 项目
     * @return 覆盖行
     */
    public List<ActorMapFeatureOverride> list(Long projectId) {
        try {
            return overrideRepository.findByProjectIdOrderByIdAsc(projectId);
        } catch (Exception e) {
            log.debug("[mapnav] 读手改失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 布局修订号（乐观锁用；无手改 = 0）。
     *
     * @param projectId 项目
     * @return 修订号（取所有手改的最大 revision）
     */
    public int revision(Long projectId) {
        int max = 0;
        for (ActorMapFeatureOverride o : list(projectId)) {
            max = Math.max(max, o.getRevision() == null ? 1 : o.getRevision());
        }
        return max;
    }

    /**
     * 写/更新一条手改。
     *
     * @param projectId 项目
     * @param mapId     地图
     * @param code      要素 code
     * @param x         中心 X
     * @param z         中心 Z
     * @param rotation  朝向
     * @param doorX     门 X
     * @param doorZ     门 Z
     * @param doorDir   门朝向
     * @param note      备注
     * @return 写入后的行
     */
    public ActorMapFeatureOverride upsert(Long projectId, Long mapId, String code,
                                          double x, double z, double rotation,
                                          double doorX, double doorZ, double doorDir,
                                          String note) {
        ActorMapFeatureOverride row = overrideRepository
                .findByMapIdAndFeatureCode(mapId, code).orElse(null);
        if (row == null) {
            row = new ActorMapFeatureOverride();
            row.setProjectId(projectId);
            row.setMapId(mapId);
            row.setFeatureCode(code);
            row.setRevision(1);
        } else {
            row.setRevision((row.getRevision() == null ? 1 : row.getRevision()) + 1);
        }
        row.setPosX(x);
        row.setPosZ(z);
        row.setRotation(rotation);
        row.setDoorX(doorX);
        row.setDoorZ(doorZ);
        row.setDoorDir(doorDir);
        row.setNote(note);
        row.setUpdatedAt(LocalDateTime.now());
        return overrideRepository.save(row);
    }

    /**
     * 清除单条手改（立即恢复 AI 原版）。
     *
     * @param projectId 项目
     * @param code      要素 code
     * @return 清掉的条数
     */
    public int clear(Long projectId, String code) {
        ActorWorldMap map = mapRepository.findFirstByProjectIdOrderByIdDesc(projectId).orElse(null);
        if (map == null) {
            return 0;
        }
        List<ActorMapFeatureOverride> rows =
                overrideRepository.findByMapIdOrderByIdAsc(map.getId());
        int n = 0;
        for (ActorMapFeatureOverride o : rows) {
            if (code == null || code.equals(o.getFeatureCode())) {
                overrideRepository.delete(o);
                n++;
            }
        }
        return n;
    }

    /**
     * 清除一个项目下全部手改（跨图）。
     *
     * <p>只在重掷种子时调用：换一张岛之后 {@code feature_code} 不再指向同一栋建筑，
     * 按 code 重放等于把用户的手改搬到邻居家。宁可明确丢弃，也不做这种"静默错位"。</p>
     *
     * @param projectId 项目
     * @return 清掉的条数
     */
    @org.springframework.transaction.annotation.Transactional
    public int clearAll(Long projectId) {
        try {
            long n = overrideRepository.deleteByProjectId(projectId);
            return (int) n;
        } catch (RuntimeException e) {
            log.warn("[mapnav] 清空手改失败（project={}）：{}", projectId, e.getMessage());
            return 0;
        }
    }

    /**
     * 把覆盖层重放到要素本体上（S4 落位之后调用）。
     *
     * <p>逐条：找 {@code feature_code} 同名要素 → 贴坐标/朝向/门 → 不合法就跳过并记 warning。
     * 找不到要素的覆盖<b>保留</b>（配置 {@code keep-orphan-overrides}），等下次重生成再试。</p>
     *
     * @param mapId 地图
     * @return {applied, missed, skipped}
     */
    @org.springframework.transaction.annotation.Transactional
    public ReplayResult replay(Long mapId) {
        List<ActorMapFeatureOverride> rows;
        try {
            // 按项目读，不按 map_id 读：重生成会插一条新 map 行，手改还挂在上一张图的 map_id 上。
            // 早先按 map_id 读，于是"再生成一次手改就没了"——覆盖层明明还在库里，只是查不到。
            Long projectId = mapRepository.findById(mapId)
                    .map(com.holzyn.actor.domain.mapgen.entity.ActorWorldMap::getProjectId)
                    .orElse(null);
            rows = projectId == null
                    ? overrideRepository.findByMapIdOrderByIdAsc(mapId)
                    : overrideRepository.findByProjectIdOrderByIdAsc(projectId);
        } catch (Exception e) {
            log.debug("[mapnav] 读手改失败：{}", e.getMessage());
            return new ReplayResult(0, 0, 0);
        }
        if (rows.isEmpty()) {
            return new ReplayResult(0, 0, 0);
        }
        Map<String, ActorMapFeature> byCode = new HashMap<>();
        for (ActorMapFeature f : featureRepository.findByMapIdOrderBySortOrderAsc(mapId)) {
            byCode.put(f.getCode(), f);
        }
        int applied = 0;
        int missed = 0;
        int skipped = 0;
        for (ActorMapFeatureOverride o : rows) {
            ActorMapFeature f = byCode.get(o.getFeatureCode());
            if (f == null) {
                missed++;
                continue;
            }
            // 把手改迁到新图上：否则下一轮又一次"查不到"，用户看到的永远是原版
            if (!mapId.equals(o.getMapId())) {
                o.setMapId(mapId);
                try {
                    overrideRepository.save(o);
                } catch (RuntimeException e) {
                    log.debug("[mapnav] 迁移手改 map 失败（{}）：{}", o.getFeatureCode(), e.getMessage());
                }
            }
            double doorX = o.getDoorX() == null ? o.getPosX() : o.getDoorX();
            double doorZ = o.getDoorZ() == null ? o.getPosZ() : o.getDoorZ();
            double doorDir = o.getDoorDir() == null ? o.getRotation() : o.getDoorDir();
            boolean ok = persist.applyLayoutGeometry(f.getId(), o.getPosX(), o.getPosZ(),
                    o.getRotation() == null ? 0 : o.getRotation(), doorX, doorZ, doorDir);
            if (ok) {
                applied++;
            } else {
                skipped++;
            }
        }
        log.info("[mapnav] 手改重放：map={} 应用 {} / 失配 {} / 跳过 {}", mapId, applied, missed, skipped);
        return new ReplayResult(applied, missed, skipped);
    }

    /**
     * 重放结果。
     *
     * @param applied 应用条数
     * @param missed  找不到要素的条数（保留待下次）
     * @param skipped 写入失败的条数
     */
    public record ReplayResult(int applied, int missed, int skipped) {
    }

    /**
     * 列出某张图的手改要素（与要素本体拼成一行的视图，前端画角标用）。
     *
     * @param projectId 项目
     * @return 覆盖行
     */
    public List<ActorMapFeatureOverride> listView(Long projectId) {
        List<ActorMapFeatureOverride> rows = new ArrayList<>(list(projectId));
        rows.sort((a, b) -> String.valueOf(a.getFeatureCode()).compareTo(String.valueOf(b.getFeatureCode())));
        return rows;
    }
}
