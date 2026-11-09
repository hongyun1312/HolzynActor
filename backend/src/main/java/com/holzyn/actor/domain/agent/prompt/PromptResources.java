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

package com.holzyn.actor.domain.agent.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置 Prompt 的 classpath 资源加载器：一个 Prompt 一个目录，正文与系统提示词分文件。
 * <p><b>为什么把 Prompt 搬出 Java</b>：模板正文动辄数万字符，写成 Java 文本块会把
 * {@code PromptTemplateService} 撑到 4500 行以上，改一行模板要在 98 个模板里翻滚，
 * 也无法用普通编辑器/差异工具审阅。落成资源文件后，一个 Prompt 一个文件，
 * 改模板就是改文件，代码只保留"模板编码 → 资源路径"的映射。</p>
 * <p><b>目录约定</b>（classpath 根）：</p>
 * <pre>
 * prompts/
 *   &lt;code&gt;/user.md      Prompt 正文（支持 {{占位符}}）
 *   &lt;code&gt;/system.md    系统提示词（可缺省）
 * </pre>
 * <p><b>失败口径</b>：资源缺失直接抛异常（内置模板缺失属于部署错误，必须在启动自检或首次渲染时炸出来，
 * 不允许静默降级成空模板——空模板会让模型自由发挥，比报错更难查）。</p>
 * <p><b>行终止符</b>：统一归一为 LF（杜绝 Windows 检出时 CRLF 混入提示词，改变 token 序列与前缀缓存命中）。</p>
 */
public final class PromptResources {

    /** 资源根目录（classpath） */
    private static final String ROOT = "prompts/";

    /** 已加载内容缓存（进程内不可变；模板是只读资源，不做热更新，改模板需重启） */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptResources() {
    }

    /**
     * 读 Prompt 正文。
     *
     * @param code 模板编码（= 目录名）
     *
     * @return 正文文本（行终止符已归一为 LF）
     */
    public static String user(String code) {
        return read(code, "user");
    }

    /**
     * 读系统提示词（缺省返回 {@code null}，由调用方决定兜底）。
     *
     * @param code 模板编码
     *
     * @return 系统提示词；文件不存在返回 {@code null}
     */
    public static String system(String code) {
        return read(code, "system");
    }

    /**
     * 读共享段落（{@code prompts/_shared/<file>.md}）。
     *
     * <p>共享段落是"所有场景逐字一致"的大前缀：统一契约、输出要求等。它们出现在每一次调用的
     * 最前面（或最后面），是前缀缓存能跨场景复用的唯一来源，因此必须与任何具体任务无关。</p>
     *
     * @param fileName 文件名（不含 {@code .md}），如 {@code contract} / {@code requirements}
     *
     * @return 文本（行终止符已归一为 LF）
     */
    public static String shared(String fileName) {
        return loadPath(ROOT + "_shared/" + fileName + ".md", false);
    }

    /**
     * 按段落读取（内部入口，供正文/系统提示词共用缓存逻辑）。
     *
     * @param code 模板编码
     *
     * @param part 段落名（user / system）
     *
     * @return 文本；system 段落缺失返回 {@code null}
     */
    private static String read(String code, String part) {
        // 注意：这里只能走 loadPath 的一次 computeIfAbsent。若先在本层 computeIfAbsent、再在
        // loadPath 里对同一个 Map 再 compute，JDK 会抛 IllegalStateException: Recursive update
        // （ConcurrentHashMap 禁止在映射函数里改动自身，哪怕换一个 key）。
        return loadPath(ROOT + code + '/' + part + ".md", "system".equals(part));
    }

    /**
     * 从 classpath 读取单个文件。
     *
     * @param code 模板编码
     *
     * @param part 段落名
     *
     * @return 归一化后的文本；system 缺失返回 {@code null}
     */
    /**
     * 按完整 classpath 路径读取并归一化（共享段落与内置模板共用同一条读盘/归一逻辑）。
     *
     * @param path classpath 路径
     *
     * @param systemPart 是否为系统提示词段（缺失时返回 null 而非抛错）
     *
     * @return 归一化文本
     */
    private static String loadPath(String path, boolean systemPart) {
        return CACHE.computeIfAbsent(path, key -> {
            try (InputStream in = PromptResources.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    if (systemPart) {
                        return null;
                    }
                    throw new IllegalStateException("内置 Prompt 资源缺失：" + path
                            + "（内置模板必须随 jar 打包进 classpath；缺失即视为部署错误，禁止静默降级为空模板）");
                }
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                // 文件末尾的一个换行只服务于版本控制与编辑器，不属于模板正文（正文自带的结尾换行
                // 因此表现为文件末尾的空行）——避免"编辑器自动补 EOL"悄悄改变提示词字节。
                return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
            } catch (IOException e) {
                throw new IllegalStateException("内置 Prompt 资源读取失败：" + path, e);
            }
        });
    }
}
