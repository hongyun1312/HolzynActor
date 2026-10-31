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

package com.holzyn.actor.domain.settings.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 供应商下新增/编辑模型的请求体。
 *
 * <p>role 取值 {@code text} / {@code embedding} / {@code decision}；为空时按 text 处理。
 * isDefault=1 表示"该角色下的默认模型"（同一供应商同一角色至多一个默认，由服务层维护互斥）。</p>
 */
@Data
public class ProviderModelDTO {

    /** 模型名（如 deepseek-chat / bge-m3） */
    @NotBlank(message = "模型名不能为空")
    @Size(max = 100, message = "模型名长度不能超过 100")
    private String name;

    /** 角色：text / embedding / decision（可空=text） */
    @Size(max = 20, message = "角色长度不能超过 20")
    private String role;

    /** 是否设为该角色的默认模型（缺省 false） */
    private Boolean isDefault;

    /** 是否启用（缺省 true） */
    private Boolean enabled;

    /** 备注 */
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;

    /**
     * 上下文容量（tokens，元数据；0 / 负数 / 空 = 未知或清空）。
     *
     * <p>仅记录与展示，<b>不参与运行时上下文裁剪</b>。编辑时传 0 表示清空既有值，
     * 不传（null）表示保持原值不变——与 {@code remark}/{@code enabled} 的"null=不改"语义一致。</p>
     */
    @Min(value = 0, message = "上下文容量不能为负数")
    @Max(value = 10000000, message = "上下文容量不能超过 10000000（tokens）")
    private Integer contextWindow;
}
