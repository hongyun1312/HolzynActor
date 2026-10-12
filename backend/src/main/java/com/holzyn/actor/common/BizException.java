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

package com.holzyn.actor.common;

/**
 * 业务异常。
 * <p>职责：表达可预期的业务错误（如资源越权、状态不允许、AI 调用失败），
 * 携带业务状态码（code）与面向用户的中文提示，由全局异常处理器统一转换为 R 响应。</p>
 * <p>与 AiCallException 的关系：AiCallException 偏向 AI 调用错误；本异常用于通用业务规则。</p>
 */
public class BizException extends RuntimeException {

    /** 业务状态码（默认 400 参数/业务错误） */
    private final int code;

    /**
     * 构造业务异常（默认 400）。
     *
     * @param message 中文错误提示
     */
    public BizException(String message) {
        this(400, message);
    }

    /**
     * 构造业务异常。
     *
     * @param code 业务状态码（如 400/404/409）
     *
     * @param message 中文错误提示
     */
    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 获取业务状态码。
     *
     * @return 业务状态码
     */
    public int getCode() {
        return code;
    }
}