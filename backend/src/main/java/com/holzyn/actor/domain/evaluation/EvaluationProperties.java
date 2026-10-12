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

package com.holzyn.actor.domain.evaluation;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 行为质量评估 + 专门测试接口配置。
 * <p>配置段 {@code holzyn.actor.evaluation.*}（application.yml + 环境变量全覆盖）：
 * </p>
 * <ul>
 * <li><b>test-enabled</b>：专门测试接口总开关（默认 <b>false</b>——关闭时测试接口一律
 * 403「测试接口未启用」；长跑/评测时开启，）；</li>
 * <li><b>judge.*</b>：LLM-as-Judge 独立云端模型（baseUrl/apiKey/model/超时/并发/输入截断上限/
 * 温度）——与生成模型不同家族规避 self-preference 偏见；</li>
 * <li><b>sampling.*</b>：评估批跑采样默认参数（批样本数/游戏时间窗/决策类 scene 集合）；</li>
 * <li><b>rubric.*</b>：评分 rubric 版本与五维定义（维度键顺序）。</li>
 * </ul>
 */
@Component
@Getter
public class EvaluationProperties {

    // ==================== 专门测试接口总开关 ====================

    /** 专门测试接口总开关（默认 false；false=测试接口全部 403，正常世界运行零影响） */
    @Value("${holzyn.actor.evaluation.test-enabled:false}")
    private boolean testEnabled = false;

    //

    /** judge 供应商 baseUrl（火山方舟 Ark coding v3，OpenAI 兼容） */
    @Value("${holzyn.actor.evaluation.judge.base-url:https://ark.cn-beijing.volces.com/api/coding/v3}")
    private String judgeBaseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3";

    /** judge API Key（生产经环境变量注入） */
    @Value("${holzyn.actor.evaluation.judge.api-key:}")
    private String judgeApiKey = "";

    /** judge 模型名（与生成模型不同家族：deepseek-v4-flash vs 本地 qwen3.6-35b） */
    @Value("${holzyn.actor.evaluation.judge.model:deepseek-v4-flash}")
    private String judgeModel = "deepseek-v4-flash";

    /** judge 供应商显示名（报告/批次记录用） */
    @Value("${holzyn.actor.evaluation.judge.provider-label:ark-coding-v3}")
    private String judgeProviderLabel = "ark-coding-v3";

    /** judge 读超时（秒，默认 120，与 AI 层同口径） */
    @Value("${holzyn.actor.evaluation.judge.timeout-read-seconds:120}")
    private long judgeTimeoutReadSeconds = 120;

    /** judge 建连超时（秒，默认 30） */
    @Value("${holzyn.actor.evaluation.judge.timeout-connect-seconds:30}")
    private long judgeTimeoutConnectSeconds = 30;

    /** judge 单次请求总时长上限（秒，默认 130 硬性兜底） */
    @Value("${holzyn.actor.evaluation.judge.timeout-request-seconds:130}")
    private long judgeTimeoutRequestSeconds = 130;

    /** judge 批跑并发 */
    @Value("${holzyn.actor.evaluation.judge.concurrency:4}")
    private int judgeConcurrency = 4;

    /** judge 样本输入长度上限（字符，默认 4000：超长截断防 verbosity 偏见 + 防上下文膨胀） */
    @Value("${holzyn.actor.evaluation.judge.max-input-chars:4000}")
    private int judgeMaxInputChars = 4000;

    /** judge 输出上限（token，默认 1024：五维评分+理由足够） */
    @Value("${holzyn.actor.evaluation.judge.max-output-tokens:1024}")
    private int judgeMaxOutputTokens = 1024;

    /** judge 温度（默认 0.0：评分必须稳定可复现，零随机） */
    @Value("${holzyn.actor.evaluation.judge.temperature:0.0}")
    private double judgeTemperature = 0.0;

    // ==================== 评估批跑采样 ====================

    /** 批跑默认样本数（默认 20，设计 §7.3 sample-per-run） */
    @Value("${holzyn.actor.evaluation.sampling.default-sample-count:20}")
    private int defaultSampleCount = 20;

    /** 批跑默认游戏时间窗（游戏分钟，默认 1440=近 1 游戏日；经 rate 折算真实分钟查询 actor_ai_call_log） */
    @Value("${holzyn.actor.evaluation.sampling.default-window-game-minutes:1440}")
    private int defaultWindowGameMinutes = 1440;

    /**
     * 决策类 scene 集合（DB 采样/JSONL 导入的过滤白名单，逗号分隔；实测 actor_ai_call_log：
     * 主决策 AI 调用=action[step_type=ai_call，决策链路级 agent_decision 是标记行须排除]，
     * 对话=dialog，恢复决策=interrupt_resume，冲突协商=negotiate）
     */
    @Value("${holzyn.actor.evaluation.sampling.scenes:action,dialog,interrupt_resume,negotiate}")
    private String scenesCsv = "action,dialog,interrupt_resume,negotiate";

    /** 决策类 scene 集合（解析后）。 */
    public List<String> decisionScenes() {
        return Arrays.stream(scenesCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    //

    /** rubric 版本（升级 rubric 时递增，报告与历史批次可区分） */
    @Value("${holzyn.actor.evaluation.rubric.version:v1}")
    private String rubricVersion = "v1";

    /** 五维键（顺序即模板/rubric 展示顺序） */
    public static final List<String> RUBRIC_DIMENSIONS = List.of(
            "consistency",      // ① 角色一致性（OOC）
            "reasonability",    // ② 决策合理性（动机/行为逻辑）
            "context_usage",    // ③ 记忆与环境利用
            "foresight",        // ④ 前瞻性
            "naturalness");     // ⑤ 语言与行为自然度

    /** 五维中文名（报告/模板展示）。 */
    public static String dimensionLabel(String key) {
        return switch (key == null ? "" : key) {
            case "consistency" -> "角色一致性";
            case "reasonability" -> "决策合理性";
            case "context_usage" -> "记忆与环境利用";
            case "foresight" -> "前瞻性";
            case "naturalness" -> "语言与行为自然度";
            default -> key;
        };
    }

    /** 四维评分（foresight 未注入样本时中性处理：该维不参与总分，见 JudgeScoreParse）。 */
    public static boolean isForesightDimension(String key) {
        return "foresight".equals(key);
    }
}
