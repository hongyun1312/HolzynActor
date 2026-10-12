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

import com.holzyn.actor.domain.world.entity.ActorMacroDeltaRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 可扩展入账规则仓储。
 * <p>查询口径：按项目（0=全局默认 + 项目覆盖，加载/热更新重建内存缓存）；
 * 幂等 upsert 由 uk(project_id+action_type+keywords) 保证（服务层先查后更）。</p>
 */
public interface ActorMacroDeltaRuleRepository extends JpaRepository<ActorMacroDeltaRule, Long> {

    /** 某项目全部规则（含全局默认行按需筛选；enabled 过滤在服务层） */
    List<ActorMacroDeltaRule> findByProjectIdOrderByIdAsc(Long projectId);

    /** 全部规则（全局默认 + 各项目覆盖；启动种子判定用） */
    List<ActorMacroDeltaRule> findAllByOrderByIdAsc();
}
