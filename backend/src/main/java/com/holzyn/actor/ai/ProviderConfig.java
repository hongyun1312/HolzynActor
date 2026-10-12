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

package com.holzyn.actor.ai;

/**
 * AI 供应商运行时配置（内存中的调用凭据）。
 * <p>用途：将数据库中的加密配置解密后，封装为供 Provider 直接使用的调用参数。
 * 该对象仅存在于服务端内存，序列化后绝不外泄。</p>
 *
 * @param id 配置主键（用于用量日志关联 provider_id）
 *
 * @param baseUrl API Base URL（已去尾部斜杠）
 *
 * @param apiKey 已解密的明文 API Key
 *
 * @param model 默认模型名（可为空）
 *
 * @param streamable 是否支持流式
 */
public record ProviderConfig(Long id, String baseUrl, String apiKey, String model, boolean streamable) {
}
