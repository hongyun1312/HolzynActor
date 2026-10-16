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

package com.holzyn.actor.domain.agent.runtime;

/**
 * 决策上下文（阶段 C AgentRuntime 骨架，domain/agent/runtime）。
 * <p>职责：承载一次决策循环所需的上下文入参——项目/用户/会话定位、游戏时间口径、
 * 检索查询与触发理由等。 为骨架阶段，字段按六组件决策循环的最小输入集定义
 * （感知/记忆/思考/扮演各步使用；后续 / 按需扩展，如目标/计划/剧情锁）。</p>
 * <p>时间口径：决策一律游戏时间——{@link #gameSecond} 显式传入
 * （引擎推进/回放场景）或为 null（=读世界时钟当前值）；禁止真实时间进入决策上下文。</p>
 */
public class DecisionContext {

    /** 项目 ID（感知/记忆/预算按项目定位；必填） */
    private final Long projectId;

    /** 归属用户 ID（委托旧服务 ActionEngine.trigger 的归属校验用；可空=由旧服务回退） */
    private final Long userId;

    /** 触发会话 ID（对话/事件回应类触发携带，扮演桩委托 ChatService 用；可空） */
    private final Long conversationId;

    /** 游戏秒（统一时间口径；null=当前世界时钟） */
    private final Long gameSecond;

    /** 检索/扮演查询文本（记忆检索 query + 扮演桩的发言内容；可空） */
    private final String query;

    /** 触发理由（如「手动触发行动」「世界引擎推进至游戏日边界」；可空） */
    private final String reason;

    /** 情境覆盖文本（思考桩委托 ActionEngine.trigger 的 situation 入参；可空=由旧服务组装） */
    private final String situation;

    /**
     * 实时行为决策系统：刺激类型编码（{@link StimulusType#code()}；STIMULUS 触发源携带，
     * 周期/对话等触发为 null；供感知/思考/记录与 打断恢复优先级判定使用）
     */
    private final String stimulusType;

    /**
     * 实时行为决策系统：刺激文本（刺激的语义描述——事件标题/行动描述/紧急描述等；
     * STIMULUS 触发源携带，同时写入 {@link #situation} 进入思考决策；可空）
     */
    private final String stimulusText;

    /** 实时行为决策系统 预留：被中断计划 ID */
    private final Long interruptedPlanId;

    /**
     * 是否为决策重试补算（P0-02 根治）：true=本决策由失败入队的重试作业重跑（回填到
     * 原定 gameSecond）。决策循环据此把成功决策打标 {@code AI_RETRY}（可追溯「这条 AI
     * 决策经过重试」）；false/缺省=常规决策入口（引擎 tick/手动/刺激等）。
     */
    private final boolean retry;

    private DecisionContext(Builder b) {
        this.projectId = b.projectId;
        this.userId = b.userId;
        this.conversationId = b.conversationId;
        this.gameSecond = b.gameSecond;
        this.query = b.query;
        this.reason = b.reason;
        this.situation = b.situation;
        this.stimulusType = b.stimulusType;
        this.stimulusText = b.stimulusText;
        this.interruptedPlanId = b.interruptedPlanId;
        this.retry = b.retry;
    }

    /**
     * 建造器入口。
     *
     * @return 建造器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 快速构造（测试/壳方法最小入参）。
     *
     * @param projectId 项目 ID
     *
     * @param userId 归属用户 ID（可空）
     *
     * @return 决策上下文
     */
    public static DecisionContext of(Long projectId, Long userId) {
        return DecisionContext.builder().projectId(projectId).userId(userId).build();
    }

    /** 项目 ID */
    public Long projectId() {
        return projectId;
    }

    /** 归属用户 ID */
    public Long userId() {
        return userId;
    }

    /** 触发会话 ID */
    public Long conversationId() {
        return conversationId;
    }

    /** 游戏秒（null=当前世界时钟） */
    public Long gameSecond() {
        return gameSecond;
    }

    /** 检索/扮演查询文本 */
    public String query() {
        return query;
    }

    /** 触发理由 */
    public String reason() {
        return reason;
    }

    /** 情境覆盖文本 */
    public String situation() {
        return situation;
    }

    /** 刺激类型编码（STIMULUS 触发源携带；周期/对话/手动等为 null） */
    public String stimulusType() {
        return stimulusType;
    }

    /** 刺激文本（STIMULUS 触发源携带；可空） */
    public String stimulusText() {
        return stimulusText;
    }

    /** 被中断计划 ID */
    public Long interruptedPlanId() {
        return interruptedPlanId;
    }

    /** 是否为决策重试补算（P0-02 根治；缺省 false） */
    public boolean retry() {
        return retry;
    }

    /**
     * 建造器（决策上下文各字段可空语义见字段注释）。
     */
    public static final class Builder {
        private Long projectId;
        private Long userId;
        private Long conversationId;
        private Long gameSecond;
        private String query;
        private String reason;
        private String situation;
        private String stimulusType;
        private String stimulusText;
        private Long interruptedPlanId;
        private boolean retry;

        public Builder projectId(Long projectId) {
            this.projectId = projectId;
            return this;
        }

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder conversationId(Long conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder gameSecond(Long gameSecond) {
            this.gameSecond = gameSecond;
            return this;
        }

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder situation(String situation) {
            this.situation = situation;
            return this;
        }

        public Builder stimulusType(String stimulusType) {
            this.stimulusType = stimulusType;
            return this;
        }

        public Builder stimulusText(String stimulusText) {
            this.stimulusText = stimulusText;
            return this;
        }

        public Builder interruptedPlanId(Long interruptedPlanId) {
            this.interruptedPlanId = interruptedPlanId;
            return this;
        }

        /** 标记为重试补算（P0-02 根治：回填原定 gameSecond 的重跑决策） */
        public Builder retry(boolean retry) {
            this.retry = retry;
            return this;
        }

        public DecisionContext build() {
            return new DecisionContext(this);
        }
    }
}
