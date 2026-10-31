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

package com.holzyn.actor.domain.settings.vo;

import lombok.Data;

/**
 * 供应商下的单个模型视图（同时也是 {@code models_json} 的序列化元素）。
 *
 * <p>角色只有三种：{@code text}=文本模型（对话/角色卡/生成）、{@code embedding}=向量模型（RAG/记忆）、
 * {@code decision}=决策模型（实时决策/判定类调用；未配置时回退文本模型）。</p>
 *
 * <p>为什么 id 是字符串：模型是供应商配置里的内嵌条目，没有独立自增主键；用随机 id 让
 * 「编辑 / 删除 / 设为默认」有稳定句柄，避免按数组下标操作在并发编辑时串行。</p>
 */
@Data
public class ProviderModelVO {

    /** 条目标识（随机字符串；旧数据推导出的条目为 legacy-*） */
    private String id;

    /** 模型名（发给 OpenAI 兼容接口的 model 字段） */
    private String name;

    /** 角色：text / embedding / decision */
    private String role;

    /** 是否为该角色下的默认模型：0 否 / 1 是 */
    private Integer isDefault;

    /** 是否启用：0 否 / 1 是 */
    private Integer enabled;

    /** 备注（用户标注用途，可空） */
    private String remark;

    /**
     * 上下文容量（tokens，可空=未知）。
     *
     * <p><b>只是元数据</b>：本字段不参与运行时上下文裁剪——输入预算仍由各场景的
     * {@code inject-token-budget} / {@code budget-*-tokens} 决定（见 application-actor.yml）。
     * 放在模型条目上是为了让用户一眼看出"这个模型能装多少"，避免拿 8K 模型跑大世界。
     * OpenAI 兼容的 {@code GET /models} 不返回该信息（OpenRouter 的 {@code context_length}
     * 属少数），所以只能填，不能可靠地自动获取。</p>
     */
    private Integer contextWindow;
}
