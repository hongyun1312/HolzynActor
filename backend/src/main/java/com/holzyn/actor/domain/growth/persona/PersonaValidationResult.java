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

package com.holzyn.actor.domain.growth.persona;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 档案校验结果。
 * <p>承载两件事：①<b>契约校验</b>（错误/告警，错误不阻断世界运行——AI 输出不合法时降级为部分建档，
 * 对齐「零阻断」原则）；②<b>覆盖率报告</b>——11 维在"档案存储 + 模块投影"两个来源上的覆盖情况，
 * 这是「11 维无空维」与「档案消费既有模块数据」的可观测口径。</p>
 */
public class PersonaValidationResult {

    /** 维覆盖状态：已存档案 */
    public static final String STORED = "stored";
    /** 维覆盖状态：档案缺省但可由模块投影补齐（合法，非空维） */
    public static final String PROJECTED = "projected";
    /** 维覆盖状态：档案与投影都没有 */
    public static final String MISSING = "missing";

    /** 是否通过契约校验（有 error 即 false） */
    private final boolean valid;
    /** 错误（契约违规；影响可用性） */
    private final List<String> errors;
    /** 告警（可疑但不阻断；如未知情境码） */
    private final List<String> warnings;
    /** 维覆盖：维键 → {@link #STORED}/{@link #PROJECTED}/{@link #MISSING} */
    private final Map<String, String> coverage;

    /**
     * 构造校验结果。
     *
     * @param errors 错误列表
     *
     * @param warnings 告警列表
     *
     * @param coverage 维覆盖映射
     */
    public PersonaValidationResult(List<String> errors, List<String> warnings, Map<String, String> coverage) {
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
        this.coverage = coverage == null ? Map.of() : new LinkedHashMap<>(coverage);
        this.valid = this.errors.isEmpty();
    }

    /** @return 是否通过契约校验 */
    public boolean isValid() {
        return valid;
    }

    /** @return 错误列表 */
    public List<String> getErrors() {
        return errors;
    }

    /** @return 告警列表 */
    public List<String> getWarnings() {
        return warnings;
    }

    /** @return 维覆盖映射（维键 → stored/projected/missing） */
    public Map<String, String> getCoverage() {
        return coverage;
    }

    /** @return 真正空维的维键列表 */
    public List<String> getMissingDims() {
        return coverage.entrySet().stream()
                .filter(e -> MISSING.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** @return 已存档案的维键列表 */
    public List<String> getStoredDims() {
        return coverage.entrySet().stream()
                .filter(e -> STORED.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** @return 由模块投影补齐的维键列表 */
    public List<String> getProjectedDims() {
        return coverage.entrySet().stream()
                .filter(e -> PROJECTED.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** @return 覆盖维数（stored + projected；满分 11） */
    public int getCoveredDimCount() {
        return (int) coverage.values().stream().filter(v -> !MISSING.equals(v)).count();
    }

    /** @return 覆盖率 0-1（用于调试页指标与验收断言） */
    public double getCoverageRatio() {
        return coverage.isEmpty() ? 0d : (double) getCoveredDimCount() / coverage.size();
    }

    /** @return 一行摘要（日志/调试页用） */
    public String summary() {
        return "valid=" + valid + " covered=" + getCoveredDimCount() + "/" + coverage.size()
                + " missing=" + getMissingDims() + " errors=" + errors.size() + " warnings=" + warnings.size();
    }
}