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

package com.holzyn.actor.domain.evaluation.temporal.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditService;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.BatchResult;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.CausalReport;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.DecayReport;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.DistributionReport;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.ProbeRequest;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.ProbeView;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.TemporalReport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 时间性验证接口。
 * <p>前缀 {@code /api/worlds/{projectId}/temporal-audit}，与测试门面 {@code /test} 并列而非合并：
 * 测试门面管「跑阶段/干预/评估批跑」，本组只管「时间性取证」，共享
 * {@code holzyn.actor.evaluation.test-enabled} 开关（关闭时一律 403）。</p>
 * <p><b>使用前提</b>：世界应处于暂停态，否则探针真值（位置/实体/台账）会随世界推进漂移；
 * 接口不强制暂停，但批次会记录 gameSecond 供事后核对。</p>
 */
@RestController
@RequestMapping("/api/worlds/{projectId}/temporal-audit")
@RequiredArgsConstructor
public class TemporalAuditController {

    private final TemporalAuditService auditService;

    /**
     * 跑一批探针。
     *
     * @param projectId 项目 ID
     * @param req 可选 {characterRefs?, probeTypes?, runTag?}；空=全部角色 × 全部类型
     *
     * @return 批次结果（逐条 PASS/FAIL/INCONCLUSIVE/SKIP/ERROR）
     */
    @PostMapping("/probes")
    public R<BatchResult> runProbes(@PathVariable Long projectId,
                                    @RequestBody(required = false) ProbeRequest req) {
        return R.ok(auditService.runProbes(projectId, req));
    }

    /**
     * 探针明细。
     *
     * @param projectId 项目 ID
     * @param type 可选类型过滤（stale_place/stale_entity/temporal_recall/causal_chain）
     * @param runTag 可选批次过滤；同一批次的结果才可横向比较
     *
     * @return 探针列表（倒序）
     */
    @GetMapping("/probes")
    public R<List<ProbeView>> probes(@PathVariable Long projectId,
                                     @RequestParam(required = false) String type,
                                     @RequestParam(required = false) String runTag) {
        return R.ok(auditService.probes(projectId, type, runTag));
    }

    /**
     * 时间性总报告（数据页一次取全：分布 + 衰减 + 因果 + 探针计数）。
     *
     * @param projectId 项目 ID
     *
     * @return 总报告
     */
    @GetMapping("/report")
    public R<TemporalReport> report(@PathVariable Long projectId) {
        return R.ok(auditService.report(projectId));
    }

    /**
     * 行为分布审计（工作日/周末、昼夜、逐日一致性）。
     *
     * @param projectId 项目 ID
     * @param days 回看天数（默认 7）
     *
     * @return 分布报告
     */
    @GetMapping("/distribution")
    public R<DistributionReport> distribution(@PathVariable Long projectId,
                                              @RequestParam(defaultValue = "7") int days) {
        return R.ok(auditService.distribution(projectId, days));
    }

    /**
     * 记忆时距衰减（打分层双曲线 + 真实记忆刷新证据）。
     *
     * @param projectId 项目 ID
     *
     * @return 衰减报告
     */
    @GetMapping("/decay")
    public R<DecayReport> decay(@PathVariable Long projectId) {
        return R.ok(auditService.decay(projectId));
    }

    /**
     * 因果蕴含断言（零 AI）。
     *
     * @param projectId 项目 ID
     * @param days 回看天数（默认 7）
     *
     * @return 因果断言报告
     */
    @GetMapping("/causal")
    public R<CausalReport> causal(@PathVariable Long projectId,
                                  @RequestParam(defaultValue = "7") int days) {
        return R.ok(auditService.causal(projectId, days));
    }
}
