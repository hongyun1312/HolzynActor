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

package com.holzyn.actor.domain.npc.state.config;

import com.holzyn.actor.domain.npc.state.entity.ActorEmotionAnchor;
import com.holzyn.actor.domain.npc.state.repository.ActorEmotionAnchorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PAD 基础情绪锚点启动种子。
 * <p>职责：应用启动时把 7 个基础情绪锚点（平静/高兴/愤怒/悲伤/恐惧/惊讶/厌恶，PAD 三维取自
 * 主开发方案 §7.1 经典 PAD 常模）写入 actor_emotion_anchor：缺失 → 插入；已存在但数值不一致
 * （代码随版本演进）→ 同步更新（全局参考表以代码为准；用户可在此调参，但下一次启动若代码值
 * 变化会覆盖——与内置 Prompt 模板同步语义一致）。</p>
 * <p>设计依据：《NPC状态模块_主开发方案》§7.1（PAD 空间与基础情绪锚点表）。
 * </p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EmotionAnchorSeeder implements ApplicationRunner {

    /** 锚点仓库 */
    private final ActorEmotionAnchorRepository anchorRepository;

    /**
     * 启动时幂等初始化 7 个基础情绪锚点。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        anchors().forEach((code, def) -> {
            ActorEmotionAnchor existing = anchorRepository.findByCode(code).orElse(null);
            if (existing == null) {
                ActorEmotionAnchor a = new ActorEmotionAnchor();
                apply(a, code, def);
                anchorRepository.save(a);
                log.info("初始化 PAD 情绪锚点: {} - {}", code, def.name());
            } else if (!def.name().equals(existing.getName())
                    || !def.pleasure().equals(existing.getPleasure())
                    || !def.arousal().equals(existing.getArousal())
                    || !def.dominance().equals(existing.getDominance())
                    || !def.description().equals(existing.getDescription())) {
                apply(existing, code, def);
                anchorRepository.save(existing);
                log.info("同步 PAD 情绪锚点数值: {} - {}", code, def.name());
            }
        });
    }

    /**
     * 把内置定义应用到锚点实体（新建/同步共用）。
     */
    private void apply(ActorEmotionAnchor a, String code, AnchorDef def) {
        a.setCode(code);
        a.setName(def.name());
        a.setPleasure(def.pleasure());
        a.setArousal(def.arousal());
        a.setDominance(def.dominance());
        a.setDescription(def.description());
        a.setSortOrder(def.sortOrder());
    }

    /**
     * 内置 7 基础情绪锚点定义（主开发方案 §7.1，经典 PAD 常模）。
     *
     * @return code → 锚点定义
     */
    static Map<String, AnchorDef> anchors() {
        Map<String, AnchorDef> map = new LinkedHashMap<>();
        map.put("calm", new AnchorDef("平静", 0.10d, -0.10d, 0.00d, "情绪基准/默认态：平和、不激动", 1));
        map.put("happy", new AnchorDef("高兴", 0.80d, 0.50d, 0.45d, "愉悦唤醒且主动：满足/被认可/好消息", 2));
        map.put("angry", new AnchorDef("愤怒", -0.50d, 0.60d, 0.25d, "不悦高唤醒且偏支配：受挫/被冒犯", 3));
        map.put("sad", new AnchorDef("悲伤", -0.40d, -0.20d, -0.50d, "不悦低唤醒低支配：失落/失去", 4));
        map.put("fearful", new AnchorDef("恐惧", -0.64d, 0.60d, -0.43d, "不悦高唤醒低支配：威胁/危险", 5));
        map.put("surprised", new AnchorDef("惊讶", 0.30d, 0.70d, -0.20d, "高唤醒中性偏悦：意外/出乎意料", 6));
        map.put("disgusted", new AnchorDef("厌恶", -0.60d, 0.35d, 0.10d, "不悦中唤醒：反感/排斥", 7));
        return map;
    }

    /**
     * 内置锚点定义记录（code 即 Map 键，定义内含名称/三维/说明/排序）。
     *
     * @param name 情绪名称（中文）
     *
     * @param pleasure PAD-愉悦
     *
     * @param arousal PAD-唤醒
     *
     * @param dominance PAD-支配
     *
     * @param description 情绪说明
     *
     * @param sortOrder 排序
     */
    record AnchorDef(String name, Double pleasure, Double arousal, Double dominance,
                     String description, Integer sortOrder) {
    }
}
