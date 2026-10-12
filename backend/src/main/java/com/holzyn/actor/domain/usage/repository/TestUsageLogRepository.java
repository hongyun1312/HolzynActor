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

package com.holzyn.actor.domain.usage.repository;

import com.holzyn.actor.domain.usage.entity.TestUsageLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 长跑测试用量明细仓库（{@code actor_test_usage_log}）。
 * <p>职责：只提供插入能力（{@link JpaRepository} 自带 {@code save}，事务由
 * {@code SimpleJpaRepository} 自行开启）。聚合查询一律走
 * 本仓库<b>不</b>暴露任何业务查询方法——避免测试表被业务代码引用成事实上的领域概念。</p>
 */
public interface TestUsageLogRepository extends JpaRepository<TestUsageLog, Long> {
}
