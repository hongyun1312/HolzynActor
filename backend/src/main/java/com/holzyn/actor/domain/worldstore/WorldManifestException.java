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

package com.holzyn.actor.domain.worldstore;

/**
 * 世界清单（{@code manifest.json}）读写或兼容性校验失败。
 *
 * <p>刻意不用 {@code BizException}：本异常会在"启动扫描收编"这种**无 HTTP 请求上下文**的
 * 场景抛出，包成 400 业务异常会让人误以为"是某个接口返回错"，而真实原因是磁盘上的存档
 * 与当前应用不兼容。异常消息一律带上具体文件路径与修法，避免"打开失败"这种无信息提示。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
public class WorldManifestException extends RuntimeException {

    public WorldManifestException(String message) {
        super(message);
    }

    public WorldManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
