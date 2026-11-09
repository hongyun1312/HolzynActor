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

package com.holzyn.actor.domain.world.config;

import com.holzyn.actor.domain.world.authority.model.WorldAuthorityMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 角色↔世界闭环配置（前缀 {@code holzyn.actor.world-loop}，§4.10）。
 *
 * <p><b>默认全关</b>：{@code enabled=false} 时–新链路一律不挂载，行为与基线逐字节一致
 * 。桌面端验证时用环境变量 {@code HOLOZYN_ACTOR_WORLD_LOOP_ENABLED=true} 单独打开。</p>
 *
 * <p>env 覆盖：yml 里已写显式占位符（{@code ${HOLOZYN_ACTOR_WORLD_LOOP_...:默认值}}），
 * 按占位符名精确匹配；未写占位符的路径走 Spring 松弛绑定（点→下划线、连字符删除）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.world-loop")
public class WorldLoopProperties {

    /** 总开关（false=所有新增链路不生效，行为回基线） */
    private boolean enabled = false;

    /** 权威维度（双形态：桌面端 brain / 接引擎 engine） */
    private Authority authority = new Authority();

    /** 对话回写 */
    private Dialog dialog = new Dialog();

    /** 事实层（建能力，接线） */
    private Fact fact = new Fact();

    /** 行为裁决 */
    private Adjudicate adjudicate = new Adjudicate();

    /** 因果链（建列，写入） */
    private Causal causal = new Causal();

    /** NPC 主动性 */
    private Initiative initiative = new Initiative();

    /** 权威配置。 */
    @Data
    public static class Authority {

        /** 模式：auto / brain / engine（auto 以引擎连接态判定） */
        private WorldAuthorityMode mode = WorldAuthorityMode.AUTO;

        /** 是否只允许世界关闭时切换模式（运行中不热切换） */
        private boolean requireClosedForSwitch = true;

        /** hydrate 分页大小（每页事实条数） */
        private int hydratePageSize = 500;

        /** 镜像过期告警阈值（游戏分钟） */
        private int staleWarnGameMinutes = 10;

        /** 允许的 drift 条数（0=任何 drift 都记 WARN） */
        private int driftTolerance = 0;
    }

    /** 对话回写配置。 */
    @Data
    public static class Dialog {

        /** 对话回写总开关 */
        private boolean enabled = true;

        /** 抽取批大小（每 N 轮调一次 AI） */
        private int extractBatchRounds = 6;

        /** 会话闲置多少游戏分钟后抽取 */
        private int extractIdleGameMinutes = 10;

        /** 每轮对话折算的游戏分钟（时间成本） */
        private int timeCostMinutesPerRound = 2;

        /** 是否向同地点广播对话事件 */
        private boolean announceToLocation = true;
    }

    /** 事实层配置。 */
    @Data
    public static class Fact {

        /** 事实层开关 */
        private boolean enabled = true;

        /** 决策提示词注入的「我知道的事实」条数上限 */
        private int promptTopK = 8;

        /** 到期扫描周期（游戏小时） */
        private int sweepCronGameHours = 1;

        /** 秘密类事实是否生成传闻包 */
        private boolean rumorFromSecret = true;
    }

    /** 裁决配置。 */
    @Data
    public static class Adjudicate {

        /** 裁决总开关 */
        private boolean enabled = true;

        /** 触发 AI 裁决的最低重要度 */
        private int aiMinImportance = 4;

        /** AI 裁决最多返回的效果条数 */
        private int aiMaxEffects = 4;

        /** 规则无法判定时是否默认放行（fail-open；关开关时与现状一致） */
        private boolean defaultAllow = true;
    }

    /** 因果配置。 */
    @Data
    public static class Causal {

        /** 因果链开关 */
        private boolean enabled = true;

        /** 最大链深 */
        private int maxDepth = 6;

        /** 单条关联链最大节点数 */
        private int maxNodesPerChain = 32;

        /** 单角色每游戏小时最多被动反应次数 */
        private int maxReactionsPerActorHour = 6;
    }

    /** 主动性配置。 */
    @Data
    public static class Initiative {

        /** 主动性扫描开关（默认关） */
        private boolean enabled = false;

        /** 最低紧迫度 */
        private int minUrgency = 3;

        /** 每游戏小时最多发起次数（项目级） */
        private int maxPerHour = 2;

        /** 久未见面阈值（游戏日） */
        private int absenceDays = 3;

        /** 到达时是否通知玩家 */
        private boolean visitNotify = true;
    }
}
