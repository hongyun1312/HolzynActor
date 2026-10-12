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

package com.holzyn.actor.ai.nanojev.ab.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * NanoJev A/B 质量评估 · 批跑主表。
 * <p>职责：承载一次「NanoJev vs Qwen 同题型重问」对照批跑的元信息与聚合报告——
 * 样本来自青禾村真实运行记录（actor_ai_call_log 抽取真实情境 → 构造三题型判定题），
 * 逐样本跑 NanoJev（evaluate 分布）+ Qwen（同题型重问选中项）+ Judge（四维合理性评分），
 * 输出一致率 / 分布合理性 / 延迟表 / 按场景分桶的置信度升级阈值标定。</p>
 * <p>设计依据：《HolzynActor_NanoJev并行决策模型接入_开发路径 .md》§6 A/B 评估
 * 。 （开工逐题确认）：① 样本=真实情境构造判定题（社交意愿
 * 无历史调用点→从对话历史截取发言前情境构造）；② Qwen 对照=同题型重问（同任务同格式，
 * top-1 一致率定义干净）；③ 人工标注=机器对照先行，报告后按需补标；④ 置信度阈值=用
 * Qwen 一致率做代理标定（NanoJev top-1 == Qwen top-1 为近似真值）；⑤ 交付=纯后端+报告
 * 落库（不动前端）；⑥ 存储=新建专用表 + 复用 JudgeChatClient；⑦ 样本来源=短跑
 * 青禾村世界攒真实情境；⑧ 配额=情绪 ~75 / 候选选择 ~75 / 社交意愿 ~50；⑨ 出口条件=
 * 先看结果再调门槛；⑩ 阈值粒度=按场景分桶标定；⑪ judge=全量四维评分；⑫ 模板=三类
 * 场景实际编写并升版登记（nanojev_* version 1→2）；⑬ 批跑=异步 + 轮询；⑭ 升级机制=
 * 只标定 + 参数化（holzyn.actor.ai.nanojev.escalation.*），不接线任何业务插拔点；⑮ 候选集=
 * 按样本动态提取（历史 action 输出提炼动作摘要 + 通用项）。</p>
 * <p>状态机：pending（已创建未启动）→ running（异步批跑中）→ done（完成）/ failed（失败）；
 * 批跑为异步虚拟线程并发（默认 4 路），不阻塞测试接口。</p>
 * <p><b>架构不变量</b>：批跑核心（含 NanoJev/Qwen/judge 调用的方法）一律<b>不带
 * {@code @Transactional}</b>——NanoJev 调用同样是 AI 调用（HTTP 网络等待），事务内持锁
 * 违反 固化架构不变量（AiCallOutsideTransactionTest 扩展守护）。</p>
 */
@Data
@Entity
@Table(name = "actor_nanojev_ab_run")
public class ActorNanoJevAbRun {

    /** 主键（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属项目 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 批跑场景：all（三类全跑，默认）/ emotion_eval / crowd_decision / social_willingness */
    @Column(name = "scene_scope", length = 30)
    private String sceneScope = "all";

    /** 状态：pending / running / done / failed */
    @Column(name = "status", length = 20)
    private String status = "pending";

    /** 本批次样本数（落库后统计） */
    @Column(name = "sample_count")
    private Integer sampleCount = 0;

    /** 已成功完成 NanoJev+Qwen 对照的样本数 */
    @Column(name = "compared_count")
    private Integer comparedCount = 0;

    /** NanoJev top-1 == Qwen top-1 一致的样本数（一致率分子） */
    @Column(name = "matched_count")
    private Integer matchedCount = 0;

    /** Qwen 对照模型名（同题型重问的基线模型） */
    @Column(name = "qwen_model", length = 100)
    private String qwenModel;

    /** judge 模型名（四维合理性评分） */
    @Column(name = "judge_model", length = 100)
    private String judgeModel;

    /** 模板版本 */
    @Column(name = "template_version", length = 20)
    private String templateVersion = "2";

    /** 批次汇总 JSON（一致率/分布合理性/延迟表/按场景分桶阈值标定） */
    @Column(name = "summary_json", columnDefinition = "LONGTEXT")
    private String summaryJson;

    /** 完整报告 JSON（汇总 + 逐样本明细引用） */
    @Column(name = "report_json", columnDefinition = "LONGTEXT")
    private String reportJson;

    /** 失败原因（批次级失败时填充） */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 完成时间（done/failed 时填充） */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 插入前回调：填充时间与默认值。 */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (status == null || status.isBlank()) status = "pending";
        if (sampleCount == null) sampleCount = 0;
        if (comparedCount == null) comparedCount = 0;
        if (matchedCount == null) matchedCount = 0;
        if (sceneScope == null || sceneScope.isBlank()) sceneScope = "all";
        if (templateVersion == null || templateVersion.isBlank()) templateVersion = "2";
    }
}
