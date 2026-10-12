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

import lombok.Data;

/**
 * 统一错误响应详情。
 * <p>职责：封装 API 异常返回时的错误详情，嵌套在 R<T>的 error 字段中返回给前端。</p>
 */
@Data
public class ErrorResponse {

    /** 错误关联的字段名（如参数校验失败时的字段名，可为空） */
    private String field;

    /** 错误详细描述（人类可读的错误说明） */
    private String detail;

    /**
     * 构造错误详情。
     *
     * @param field 错误关联的字段名，无具体字段时传空字符串
     *
     * @param detail 错误详细描述
     */
    public ErrorResponse(String field, String detail) {
        this.field = field;
        this.detail = detail;
    }

    /**
     * 快速构造仅含详情的错误响应（field 为空）。
     *
     * @param detail 错误详细描述
     *
     * @return ErrorResponse 实例
     */
    public static ErrorResponse of(String detail) {
        return new ErrorResponse("", detail);
    }

    /**
     * 快速构造含字段名和详情的错误响应。
     *
     * @param field 错误字段名
     *
     * @param detail 错误详细描述
     *
     * @return ErrorResponse 实例
     */
    public static ErrorResponse of(String field, String detail) {
        return new ErrorResponse(field, detail);
    }
}