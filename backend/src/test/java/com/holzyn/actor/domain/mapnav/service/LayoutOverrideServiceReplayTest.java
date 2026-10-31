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

import com.holzyn.actor.domain.mapgen.entity.ActorWorldMap;
import com.holzyn.actor.domain.mapgen.repository.ActorWorldMapRepository;
import com.holzyn.actor.domain.mapgen.repository.ActorMapFeatureRepository;
import com.holzyn.actor.domain.mapgen.entity.ActorMapFeature;
import com.holzyn.actor.domain.mapgen.service.MapPersistService;
import com.holzyn.actor.domain.mapnav.entity.ActorMapFeatureOverride;
import com.holzyn.actor.domain.mapnav.repository.ActorMapFeatureOverrideRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖层重放的"两条命门"回归。
 *
 * <p>这两个 bug 都是在真实冒烟里才暴露的，单测里不钉住，下次重构就会复发：</p>
 * <ol>
 *   <li><b>按项目读，不按 map_id 读</b>——重生成会插一条新 map 行，手改还挂在上一张图的
 *       map_id 上。按 map_id 读的结果就是"手改还在库里，但重放永远查不到"，
 *       用户看到的是重生成一次编辑就没了。</li>
 *   <li><b>清空要走事务</b>——{@code deleteByProjectId} 这种派生删除在无事务线程里会抛
 *       {@code No EntityManager with actual transaction available}，被 catch 之后
 *       表现为"点了重掷、手改没清"，静默失败。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class LayoutOverrideServiceReplayTest {

    @Mock
    private ActorMapFeatureOverrideRepository overrideRepository;

    @Mock
    private ActorMapFeatureRepository featureRepository;

    @Mock
    private ActorWorldMapRepository mapRepository;

    @Mock
    private MapPersistService persist;

    @InjectMocks
    private LayoutOverrideService service;

    private static ActorWorldMap map(long id, long projectId) {
        ActorWorldMap m = new ActorWorldMap();
        m.setId(id);
        m.setProjectId(projectId);
        return m;
    }

    private static ActorMapFeature feature(long id, String code) {
        ActorMapFeature f = new ActorMapFeature();
        f.setId(id);
        f.setCode(code);
        return f;
    }

    private static ActorMapFeatureOverride override(long mapId, String code, double x, double z, double rot) {
        ActorMapFeatureOverride o = new ActorMapFeatureOverride();
        o.setMapId(mapId);
        o.setFeatureCode(code);
        o.setPosX(x);
        o.setPosZ(z);
        o.setRotation(rot);
        o.setRevision(1);
        return o;
    }

    @Test
    @DisplayName("重放按项目读手改，并把旧 map_id 迁到新图")
    void replayReadsOverridesByProjectAndMigratesMapId() {
        when(mapRepository.findById(101L)).thenReturn(Optional.of(map(101L, 9L)));
        when(overrideRepository.findByProjectIdOrderByIdAsc(9L))
                .thenReturn(List.of(override(100L, "f_a", 12.5, 33.5, 90)));
        when(featureRepository.findByMapIdOrderBySortOrderAsc(101L))
                .thenReturn(List.of(feature(7L, "f_a")));
        when(persist.applyLayoutGeometry(eq(7L), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble())).thenReturn(true);

        LayoutOverrideService.ReplayResult r = service.replay(101L);

        assertEquals(1, r.applied());
        assertEquals(0, r.missed());
        // 关键：走的是项目维度的查询；按 map_id 查只会在"同一张图重跑"时命中
        verify(overrideRepository).findByProjectIdOrderByIdAsc(9L);
        verify(overrideRepository, never()).findByMapIdOrderByIdAsc(anyLong());
        // 迁移：不迁的话下一轮重放又查不到，用户看到"又变回原版"
        ArgumentCaptor<ActorMapFeatureOverride> saved = ArgumentCaptor.forClass(ActorMapFeatureOverride.class);
        verify(overrideRepository).save(saved.capture());
        assertEquals(101L, saved.getValue().getMapId(), "手改必须迁到新图的 map_id");
    }

    @Test
    @DisplayName("清空手改按项目范围删除（重掷地图时用）")
    void clearAllDeletesByProject() {
        when(overrideRepository.deleteByProjectId(9L)).thenReturn(3L);

        assertEquals(3, service.clearAll(9L));
        verify(overrideRepository).deleteByProjectId(9L);
    }
}
