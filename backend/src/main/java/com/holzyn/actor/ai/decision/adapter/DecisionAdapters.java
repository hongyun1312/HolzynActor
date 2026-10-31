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

package com.holzyn.actor.ai.decision.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 适配器注册表（Spring 收集全部 {@link DecisionAdapter} 实现，按 ID 解析）。
 *
 * <p><b>单一解析入口</b>：配置 {@code holzyn.actor.ai.decision.adapter} 是唯一选择依据；
 * 未配置/配置了未知 ID 时回退到 {@link NanojevAdapter#ID}（保持既有部署的行为不变），
 * 并对未知 ID 打一条 WARN —— 静默回退到错误模型是比「报错」更坏的故障形态。</p>
 */
@Slf4j
@Component
public class DecisionAdapters {

    /** 兼容旧部署的缺省适配器 ID（未配置 adapter 时使用） */
    public static final String DEFAULT_ID = NanojevAdapter.ID;

    private final Map<String, DecisionAdapter> byId = new LinkedHashMap<>();

    private final DecisionAdapter fallback;

    /**
     * 构造（Spring 注入全部实现；顺序=Bean 定义序）。
     *
     * @param adapters 全部适配器（可空）
     */
    @Autowired
    public DecisionAdapters(List<DecisionAdapter> adapters) {
        DecisionAdapter def = null;
        if (adapters != null) {
            for (DecisionAdapter a : adapters) {
                byId.put(a.id(), a);
                if (DEFAULT_ID.equals(a.id())) {
                    def = a;
                }
            }
            if (def == null && !adapters.isEmpty()) {
                def = adapters.get(0);
            }
        }
        this.fallback = def;
    }

    /**
     * 按 ID 解析适配器（大小写/空白容忍；未知 ID 回退缺省并告警）。
     *
     * @param id 适配器 ID（可空=缺省）
     *
     * @return 适配器；仓库无任何实现时返回 {@code null}（调用方按未启用处置）
     */
    public DecisionAdapter resolve(String id) {
        if (id != null && !id.isBlank()) {
            DecisionAdapter found = byId.get(id.trim());
            if (found != null) {
                return found;
            }
            log.warn("[Decision] 未知决策模型适配器 id={}（已注册={}），回退缺省 {}",
                    id, byId.keySet(), DEFAULT_ID);
        }
        return fallback;
    }

    /** 缺省适配器（未配置 adapter 时）。 */
    public DecisionAdapter defaultAdapter() {
        return fallback;
    }

    /** 全部适配器（展示/目录用；不可变视图）。 */
    public List<DecisionAdapter> all() {
        return List.copyOf(byId.values());
    }

    /** 全部适配器 ID（校验/日志用）。 */
    public List<String> ids() {
        return List.copyOf(byId.keySet());
    }

    /**
     * 是否已注册指定 ID（配置校验用；不做回退）。
     *
     * @param id 适配器 ID
     *
     * @return true=已注册
     */
    public boolean contains(String id) {
        return id != null && byId.containsKey(id.trim());
    }
}
