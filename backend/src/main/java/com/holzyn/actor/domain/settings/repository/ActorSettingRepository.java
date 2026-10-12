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

package com.holzyn.actor.domain.settings.repository;

import com.holzyn.actor.domain.settings.entity.ActorSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 全局设置覆盖仓库 ——调参覆盖持久层。
 * <p>职责：按键查询/删除覆盖（setting_key 唯一）；查询全部覆盖（启动加载用）。</p>
 */
public interface ActorSettingRepository extends JpaRepository<ActorSetting, Long> {

    /** 按设置键查询（唯一） */
    Optional<ActorSetting> findBySettingKey(String settingKey);

    /** 查询全部覆盖（启动加载用；按 id 升序稳定排列） */
    List<ActorSetting> findAllByOrderByIdAsc();

    /** 按设置键删除（单键重置用） */
    void deleteBySettingKey(String settingKey);

    /** 是否存在某键（幂等判断用） */
    boolean existsBySettingKey(String settingKey);
}
