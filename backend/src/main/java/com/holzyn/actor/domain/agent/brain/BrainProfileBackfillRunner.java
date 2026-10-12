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

package com.holzyn.actor.domain.agent.brain;

import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 脑力配置存量补行启动器（阶段 C BrainProfile，domain/agent/brain）。
 * <p>职责：应用启动时（Hibernate ddl-auto=update 已建 actor_agent_profile 表之后）为存量
 * 智能体幂等补行——遍历未删除特殊角色 → ensureProfile("special", id)；遍历全部普通 NPC →
 * ensureProfile("ordinary", id)。只增不删，重复启动为空操作。</p>
 * <p>实现对齐 {@code OldCrowdTableCleaner} 思路：ApplicationRunner + 失败不阻断启动
 * （补行异常仅告警放行，下次启动重试）；行数量级（项目角色 + 普通 NPC 总数）小，启动开销可忽略。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrainProfileBackfillRunner implements ApplicationRunner {

    private final BrainProfileService brainProfileService;

    /**
     * 启动时执行存量补行（幂等）。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            int ensured = brainProfileService.backfillExisting();
            log.info("[脑力配置] 启动补行完成：{} 个智能体已确保脑力配置行（存量数据默认脑力生效）", ensured);
        } catch (Exception e) {
            // 行失败不阻断启动（幂等，下次启动重试；仅记录）
            log.warn("[脑力配置] 启动补行失败（不影响启动，下次启动重试）: {}", e.getMessage());
        }
    }
}
