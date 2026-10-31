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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式声明"这段代码要在目录库上下文里跑"（V1.1.2-beta.1）。
 *
 * <p>分档后默认路由是<b>当前挂载世界</b>（见 {@link WorldRoutingDataSource}）。所有"属于本机、
 * 不属于某个世界"的操作——存档列表、全局设置、模型供应商、提示词模板、账户档案——
 * 都必须显式声明为全局，否则会被路由到世界库的只读镜像上，写入丢失（由
 * {@link GlobalMirrorWriteGuard} 兜底报错）。</p>
 *
 * <p>用于类（整个控制器都是全局语义）或方法（单个接口是全局语义）。由
 * {@link GlobalContextInterceptor} 在请求进入时置位、在 {@code afterCompletion} 还原，
 * 异步派发时由 {@link WorldRequestContextFilter} 的 {@code finally} 兜底清理，防止线程池
 * 复用把标记带给下一个请求。</p>
 *
 * <p><b>不要用在会调用 AI 的长任务上</b>：全局上下文只是路由标记，不会限制时长；
 * 但一个长达数分钟的请求一直持有"全局"标记，会让它派生出的异步写全部落到目录库，
 * 反而偏离预期。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface GlobalContext {
}
