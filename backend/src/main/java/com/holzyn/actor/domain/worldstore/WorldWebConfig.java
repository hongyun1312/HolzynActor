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

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册 {@link GlobalContextInterceptor}（V1.1.2-beta.1）。
 *
 * <p>为什么单独开一个 {@code WebMvcConfigurer} 而不改既有 {@code WebMvcConfig}：后者构造器
 * 带 {@code @Value} 参数，被既有测试直接 {@code new} 构造；往里加依赖会打断那些构造点
 * （AGENTS §8.1.2 禁止改既有构造器签名）。Spring 允许多个 {@code WebMvcConfigurer} 并存，
 * 各管一段，互不影响。</p>
 *
 * <p>只挂 {@code /api/**}：静态资源与 SSE 心跳不产生全局写入，挂上去只是每请求多一次判定。</p>
 *
 * <p>所属模块：domain/worldstore（世界存档存储）</p>
 */
@Configuration
public class WorldWebConfig implements WebMvcConfigurer {

    /**
     * 注册全局上下文拦截器。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new GlobalContextInterceptor()).addPathPatterns("/api/**");
    }
}
