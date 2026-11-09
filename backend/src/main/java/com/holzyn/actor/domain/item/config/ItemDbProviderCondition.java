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

package com.holzyn.actor.domain.item.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@code DbInventoryService} 的装配条件：{@code item.enabled=true} 且 {@code item.provider=db}。
 *
 * <p>为什么需要这个条件而不是只判断 enabled：{@code provider=engine} 时物品权威在引擎，
 * 后端若还装一个 DB 写实现，就会出现「两份库存各写一半」的双权威（教训）。
 * 此时端口不装配 → EffectApplier 按降级 {@code SKIPPED_NO_INVENTORY}，
 * 明确「本轮没接引擎实现」，而不是静默写错。</p>
 *
 * <p>不启 Spring 上下文也能单测：直接 new 一个假的 {@link ConditionContext} 即可。</p>
 */
public class ItemDbProviderCondition implements Condition {

    /** 配置前缀 */
    private static final String PREFIX = "holzyn.actor.item.";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        boolean enabled = Boolean.parseBoolean(env.getProperty(PREFIX + "enabled", "false"));
        String provider = env.getProperty(PREFIX + "provider", "db");
        return enabled && "db".equalsIgnoreCase(provider == null ? "db" : provider.trim());
    }
}
