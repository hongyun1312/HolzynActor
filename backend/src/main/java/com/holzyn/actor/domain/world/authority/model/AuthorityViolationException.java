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

package com.holzyn.actor.domain.world.authority.model;

/**
 * 权威写越权异常（fail-closed）。
 *
 * <p>触发场景：ENGINE 模式且 {@code authority=engine} 的事实在非镜像路径被写；
 * 或 BRAIN 事实被 engine 写。对应设计文档/。</p>
 */
public class AuthorityViolationException extends RuntimeException {

    /** 错误码（与引擎协议 error.code 对齐） */
    public static final String CODE = "AUTHORITY_DENIED";

    /**
     * 构造。
     *
     * @param message 说明
     */
    public AuthorityViolationException(String message) {
        super(message);
    }
}
