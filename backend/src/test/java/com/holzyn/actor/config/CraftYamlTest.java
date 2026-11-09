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

package com.holzyn.actor.config;

import com.holzyn.actor.domain.craft.config.CraftProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 制作配置校验。
 *
 * <p>本仓库没有任何 {@code @SpringBootTest}，yml 键名拼错不会被既有套件发现，
 * 只会在真实启动时炸（AGENTS §8.4.3）。本测试直载 yml、不启 Spring 上下文。</p>
 *
 * <p>锁定内容：</p>
 * <ol>
 *   <li>制作段挂在 {@code holzyn.actor.craft}（不是 {@code item.craft}）；</li>
 *   <li>默认值与设计一致：<b>默认关</b>、单次 5 份、扫描 50 件、默认 30 分钟、上限 1440 分钟；</li>
 *   <li>关键项可被 {@code HOLOZYN_ACTOR_CRAFT_*} 覆盖（占位符名精确匹配）；</li>
 *   <li>建造事实同步默认开、可见性默认 location。</li>
 * </ol>
 */
class CraftYamlTest {

    private static StandardEnvironment env() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertFalse(sources.isEmpty(), "application.yml 加载结果为空");
        List<PropertySource<?>> actorSources = new YamlPropertySourceLoader()
                .load("application-actor", new ClassPathResource("application-actor.yml"));
        assertFalse(actorSources.isEmpty(), "application-actor.yml 加载结果为空");
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);
        actorSources.forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    private static String strAt(StandardEnvironment env, String key) {
        String raw = env.getProperty(key);
        assertNotNull(raw, "缺失或键名拼错：" + key);
        return raw.trim();
    }

    private static CraftProperties bind(StandardEnvironment env) {
        return Binder.get(env)
                .bind("holzyn.actor.craft", Bindable.of(CraftProperties.class))
                .orElseThrow(() -> new AssertionError("CraftProperties 绑定失败（craft 段缺失或键名拼错）"));
    }

    @Test
    @DisplayName("I3 默认：craft.enabled=false（行为回 I2 之前），item.enabled 也默认关")
    void disabledByDefault() throws IOException {
        StandardEnvironment env = env();
        assertEquals("false", strAt(env, "holzyn.actor.craft.enabled"));
        assertFalse(bind(env).isEnabled(), "CraftProperties.enabled 默认必须 false");
        assertEquals("false", strAt(env, "holzyn.actor.item.enabled"),
                "物品域默认关闭 → 端口不装配 → CraftService.enabled()=false");
    }

    @Test
    @DisplayName("craft 段：5 / 50 / 30 / 1440 / true / location")
    void defaultsResolve() throws IOException {
        StandardEnvironment env = env();
        assertEquals("5", strAt(env, "holzyn.actor.craft.max-batches-per-job"));
        assertEquals("50", strAt(env, "holzyn.actor.craft.max-jobs-per-sweep"));
        assertEquals("30", strAt(env, "holzyn.actor.craft.default-duration-minutes"));
        assertEquals("1440", strAt(env, "holzyn.actor.craft.max-duration-minutes"));
        assertEquals("true", strAt(env, "holzyn.actor.craft.build-fact-sync"));
        assertEquals("location", strAt(env, "holzyn.actor.craft.build-visibility"));

        CraftProperties props = bind(env);
        assertEquals(5, props.getMaxBatchesPerJob());
        assertEquals(50, props.getMaxJobsPerSweep());
        assertEquals(30, props.getDefaultDurationMinutes());
        assertEquals(1440, props.getMaxDurationMinutes());
        assertTrue(props.isBuildFactSync(), "建造事实同步默认开");
        assertEquals("location", props.getBuildVisibility());
    }

    @Test
    @DisplayName("环境变量覆盖：开关/份数上限/扫描上限/耗时上限（占位符名精确匹配）")
    void overridableByEnvironment() throws IOException {
        System.setProperty("HOLOZYN_ACTOR_CRAFT_ENABLED", "true");
        System.setProperty("HOLOZYN_ACTOR_CRAFT_MAX_BATCHES_PER_JOB", "2");
        System.setProperty("HOLOZYN_ACTOR_CRAFT_MAX_JOBS_PER_SWEEP", "7");
        System.setProperty("HOLOZYN_ACTOR_CRAFT_MAX_DURATION_MINUTES", "90");
        try {
            StandardEnvironment env = env();
            assertEquals("true", strAt(env, "holzyn.actor.craft.enabled"));
            assertEquals("2", strAt(env, "holzyn.actor.craft.max-batches-per-job"));
            assertEquals("7", strAt(env, "holzyn.actor.craft.max-jobs-per-sweep"));
            assertEquals("90", strAt(env, "holzyn.actor.craft.max-duration-minutes"));

            CraftProperties props = bind(env);
            assertTrue(props.isEnabled());
            assertEquals(2, props.getMaxBatchesPerJob());
            assertEquals(7, props.getMaxJobsPerSweep());
            assertEquals(90, props.getMaxDurationMinutes());
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_CRAFT_ENABLED");
            System.clearProperty("HOLOZYN_ACTOR_CRAFT_MAX_BATCHES_PER_JOB");
            System.clearProperty("HOLOZYN_ACTOR_CRAFT_MAX_JOBS_PER_SWEEP");
            System.clearProperty("HOLOZYN_ACTOR_CRAFT_MAX_DURATION_MINUTES");
        }
        assertFalse(bind(env()).isEnabled(), "清理后应回到默认 false");
    }
}
