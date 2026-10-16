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

package com.holzyn.actor.domain.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 项目级 AI 助手配置（前缀 {@code holzyn.actor.assistant}）。
 * <p>职责：承载助手对话引擎的全部可调参数——总开关、工具循环上限、历史窗口、docs 文档索引路径
 * 与切片参数、以及世界状态注入预算（轻量世界上下文段）。全部有安全默认值，缺省配置即开箱可用。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.assistant")
public class AssistantProperties {

    /** 助手总开关（false=对话接口直接返回 403 提示，索引/设置页照常只读展示） */
    private boolean enabled = true;

    /** 工具循环最大轮数（模型声明工具调用 → 执行 → 回喂 → 再调；防止死循环） */
    private int maxToolRounds = 3;

    /** 历史窗口（最近 N 条用户/助手消息参与上下文；0=不注入历史） */
    private int historyWindow = 12;

    /** 工具决策调用的最大输出 token（jsonMode，工具调用 JSON 很短） */
    private int toolMaxTokens = 1024;

    /** 最终回答流式调用的最大输出 token */
    private int answerMaxTokens = 1400;

    /** 工具决策调用温度（判定类低温抑制发散） */
    private double toolTemperature = 0.3;

    /** 最终回答调用温度（叙事需要一定发散，事实模式经提示词约束） */
    private double answerTemperature = 0.6;

    /** docs 文档索引根目录（相对当前工作目录或绝对路径；dev 从 backend/ 启动默认=仓库根 docs/；可 env 覆盖） */
    private String docsIndexDir = "../docs";

    /** docs 索引是否启用（false=search_project_docs 工具直接返回「未启用」，不扫描不 embedding） */
    private boolean docsIndexEnabled = true;

    /** 单文档切片字符上限（超限整篇跳过，防超大备份文档拖慢索引） */
    private int docsMaxFileChars = 200_000;

    /** 切片目标长度（字符，按章节边界切） */
    private int docsChunkSize = 500;

    /** 切片重叠（字符） */
    private int docsChunkOverlap = 60;

    /** 文档检索返回条数上限 */
    private int docsSearchTopK = 4;

    /** 世界知识检索返回条数上限（search_world_knowledge 工具） */
    private int worldKnowledgeTopK = 5;

    /** 世界概览注入预算（字符；对话开始时注入的世界状态摘要长度上限，防上下文过长） */
    private int worldContextChars = 600;
}
