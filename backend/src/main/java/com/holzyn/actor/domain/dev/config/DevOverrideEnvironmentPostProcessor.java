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

package com.holzyn.actor.domain.dev.config;

import com.holzyn.actor.domain.dev.service.DevOverrideFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 开发者模式参数覆盖的早期注入器（EnvironmentPostProcessor）。
 *
 * <p><b>职责</b>：在 Spring 容器创建 bean 之前，把 {@code <data-dir>/dev-param-overrides.properties}
 * 里的覆盖值以**最高优先级**（{@code addFirst}）加入 Environment。这样：</p>
 * <ul>
 *   <li>{@code @ConfigurationProperties} 绑定拿到的是覆盖值（不需要重启后再写一遍）；</li>
 *   <li>{@code @Value("${holzyn.actor.xxx}")} 这类启动期常量也拿到覆盖值，实现「改完重启即生效」；</li>
 *   <li>覆盖只影响本机运行期，不修改 application.yml（仓库里永远看不到开发者的临时调参）。</li>
 * </ul>
 *
 * <p><b>为什么 order = HIGHEST_PRECEDENCE + 20</b>：必须晚于 Spring Boot 自己的
 * {@code ConfigDataEnvironmentPostProcessor}（它负责读 application.yml，order = HIGHEST_PRECEDENCE + 10），
 * 早于一切业务 bean 的创建；addFirst 保证覆盖层压过 yml、系统属性之外的所有层。</p>
 *
 * <p><b>安全边界</b>：本注入器不校验键的合法性——合法性在写入侧（{@code DevParamService}）
 * 就已经按参数目录校验过；文件被手工改坏时，绑定失败会在启动日志里报出具体键，
 * 删除该行或整个文件即可恢复（面板也提供「清空全部覆盖」）。</p>
 *
 * <p>所属模块：domain/dev（开发者模式控制台）</p>
 */
@Slf4j
public class DevOverrideEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * 读取覆盖文件并置顶注入 Environment（幂等：同名 PropertySource 覆盖旧值）。
     *
     * @param environment 可配置环境
     *
     * @param application 启动应用（本注入器不使用）
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, String> overrides;
        try {
            overrides = DevOverrideFile.readAll();
        } catch (Exception e) {
            log.warn("[开发者模式] 覆盖注入跳过（读取异常）：{}", e.getMessage());
            return;
        }
        if (overrides.isEmpty()) {
            return;
        }
        // MapPropertySource 只认 Object 值；用原始字符串即可，@ConfigurationProperties 会按目标类型转换
        environment.getPropertySources().addFirst(new MapPropertySource(DevOverrideFile.SOURCE_NAME, Map.copyOf(overrides)));
        log.info("[开发者模式] 已注入 {} 条参数覆盖（{}），生效范围：@ConfigurationProperties + @Value",
                overrides.size(), DevOverrideFile.path());
    }

    /**
     * 执行顺序：晚于 ConfigData（HIGHEST_PRECEDENCE + 10），早于一切业务装配。
     *
     * @return 顺序值
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
