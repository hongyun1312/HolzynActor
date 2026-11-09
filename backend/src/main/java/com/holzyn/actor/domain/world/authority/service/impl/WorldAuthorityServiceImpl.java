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

package com.holzyn.actor.domain.world.authority.service.impl;

import com.holzyn.actor.domain.world.authority.entity.ActorWorldAuthorityState;
import com.holzyn.actor.domain.world.authority.model.WorldAuthorityMode;
import com.holzyn.actor.domain.world.authority.repository.ActorWorldAuthorityStateRepository;
import com.holzyn.actor.domain.world.authority.service.WorldAuthorityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 权威状态写入口实现（{@code actor_world_authority_state} 的唯一写者）。
 *
 * <p>本类零 AI、纯短事务；调用时机是「打开/关闭世界」这类低频生命周期节点，
 * 不在世界 tick 热路径上。</p>
 */
@Slf4j
@Service
public class WorldAuthorityServiceImpl implements WorldAuthorityService {

    private final ActorWorldAuthorityStateRepository repository;

    public WorldAuthorityServiceImpl(ActorWorldAuthorityStateRepository repository) {
        this.repository = repository;
    }

    @Override
    public ActorWorldAuthorityState stateOf(Long projectId) {
        return projectId == null ? null : repository.findById(projectId).orElse(null);
    }

    @Override
    @Transactional
    public ActorWorldAuthorityState settle(Long projectId, WorldAuthorityMode mode, Long gameSecond) {
        if (projectId == null) {
            throw new IllegalArgumentException("权威状态落定缺少 projectId");
        }
        WorldAuthorityMode effective = mode == null ? WorldAuthorityMode.BRAIN : mode;
        ActorWorldAuthorityState state = repository.findById(projectId).orElseGet(() -> {
            ActorWorldAuthorityState fresh = new ActorWorldAuthorityState();
            fresh.setProjectId(projectId);
            return fresh;
        });
        state.setMode(effective.name().toLowerCase());
        state.setLastGameSecond(gameSecond);
        state.setUpdatedAt(LocalDateTime.now());
        state = repository.save(state);
        log.info("[权威] 模式落定：project={} mode={} gs={}", projectId, effective, gameSecond);
        return state;
    }

    @Override
    @Transactional
    public void markEngineConnected(Long projectId, LocalDateTime at) {
        if (projectId == null) {
            return;
        }
        ActorWorldAuthorityState state = repository.findById(projectId).orElseGet(() -> {
            ActorWorldAuthorityState fresh = new ActorWorldAuthorityState();
            fresh.setProjectId(projectId);
            fresh.setMode("brain");
            return fresh;
        });
        state.setLastEngineConnectedAt(at == null ? LocalDateTime.now() : at);
        state.setUpdatedAt(LocalDateTime.now());
        repository.save(state);
    }

    @Override
    @Transactional
    public void recordHandover(Long projectId, Long lastEngineSeq, String driftJson) {
        if (projectId == null) {
            return;
        }
        ActorWorldAuthorityState state = repository.findById(projectId).orElseGet(() -> {
            ActorWorldAuthorityState fresh = new ActorWorldAuthorityState();
            fresh.setProjectId(projectId);
            fresh.setMode("brain");
            return fresh;
        });
        state.setLastHandoverAt(LocalDateTime.now());
        if (lastEngineSeq != null) {
            state.setLastEngineSeq(lastEngineSeq);
        }
        state.setDriftJson(driftJson);
        state.setUpdatedAt(LocalDateTime.now());
        repository.save(state);
        log.info("[权威] 交接记录：project={} lastSeq={} drift={}", projectId, lastEngineSeq,
                driftJson == null ? "无" : "有");
    }
}
