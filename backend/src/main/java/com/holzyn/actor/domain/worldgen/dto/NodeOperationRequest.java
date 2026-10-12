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

package com.holzyn.actor.domain.worldgen.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 节点操作请求（POST .../nodes/{nodeId}/retry|skip，CAS 乐观锁）。
 * <p>expectedAttemptCount = 客户端看到的该节点当前尝试数（retry_count 最大值 + 1）；
 * 服务端比对实际尝试数，不一致返回 409（防并发双击重复执行）。</p>
 *
 * @param expectedAttemptCount 期望的当前尝试数
 */
public record NodeOperationRequest(
        @NotNull(message = "expectedAttemptCount 不能为空") Integer expectedAttemptCount) {
}
