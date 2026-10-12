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

package com.holzyn.actor.domain.world.repository;

import com.holzyn.actor.domain.world.entity.ActorWorldMacroSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 宏观指标快照仓储。
 * <p>查询口径：按项目 + 游戏日 + tick 类型幂等查（uk）；历史按游戏日降序分页取；
 * 滚动上限清理用「按游戏日升序取最旧行」（每项目上限超出时删除）。</p>
 */
public interface ActorWorldMacroSnapshotRepository extends JpaRepository<ActorWorldMacroSnapshot, Long> {

    /** 幂等查：某项目某游戏日某 tick 类型的快照行（uk 约束） */
    Optional<ActorWorldMacroSnapshot> findByProjectIdAndGameDayAndTickType(Long projectId, Long gameDay, String tickType);

    /** 历史快照（游戏日降序，供曲线回看/读接口） */
    List<ActorWorldMacroSnapshot> findByProjectIdOrderByGameDayDescTickTypeAsc(Long projectId);

    /** 区间 + tick 类型过滤（读接口） */
    List<ActorWorldMacroSnapshot> findByProjectIdAndGameDayBetweenAndTickTypeOrderByGameDayAsc(
            Long projectId, Long fromDay, Long toDay, String tickType);

    /** 区间查询（读接口，不限 tick 类型） */
    List<ActorWorldMacroSnapshot> findByProjectIdAndGameDayBetweenOrderByGameDayAscTickTypeAsc(
            Long projectId, Long fromDay, Long toDay);

    /** 某项目最新一条（任意 tick 类型；状态恢复/惰性加载用） */
    Optional<ActorWorldMacroSnapshot> findTopByProjectIdOrderByGameDayDesc(Long projectId);

    /** 某项目最新一条 init 行（初始化判定用） */
    Optional<ActorWorldMacroSnapshot> findTopByProjectIdAndTickTypeOrderByGameDayDesc(Long projectId, String tickType);

    /** 某项目快照行数（滚动上限判定） */
    long countByProjectId(Long projectId);

    /** 最旧一行（滚动清理用） */
    Optional<ActorWorldMacroSnapshot> findTopByProjectIdOrderByGameDayAsc(Long projectId);
}
