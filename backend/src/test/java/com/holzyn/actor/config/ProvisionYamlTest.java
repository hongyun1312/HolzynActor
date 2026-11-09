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

import com.holzyn.actor.domain.item.provision.ProvisionProperties;
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
 * 开局供给配置校验（对接）。
 *
 * <p>为什么必须有：本仓库没有任何 {@code @SpringBootTest}，yml 键名拼错/占位符写坏不会被
 * 既有套件发现，只会在真实启动时炸（AGENTS §8.4.3）。本测试直载 yml、不启 Spring 上下文。</p>
 *
 * <p>锁定内容：</p>
 * <ol>
 *   <li>{@code provision.enabled} 默认 true——但只在 {@code item.enabled=true} 时生效；</li>
 *   <li>四个分项（npc-kit / location-stock / player-kit / auto-wear）默认全开，可单独关；</li>
 *   <li>{@code max-holders} 默认 500；</li>
 *   <li>关键项可被 {@code HOLOZYN_ACTOR_PROVISION_*} 覆盖（占位符名精确匹配）。</li>
 * </ol>
 */
class ProvisionYamlTest {

    /** 合并加载 application.yml + application-actor.yml 到 StandardEnvironment（占位符可解析）。 */
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

    private static ProvisionProperties bind(StandardEnvironment env) {
        return Binder.get(env)
                .bind("holzyn.actor.provision", Bindable.of(ProvisionProperties.class))
                .orElseThrow(() -> new AssertionError(
                        "ProvisionProperties 绑定失败（provision 段缺失或键名拼错）"));
    }

    @Test
    @DisplayName("供给默认：enabled=true，四个分项全开，max-holders=500")
    void defaultsResolve() throws IOException {
        StandardEnvironment env = env();
        assertEquals("true", strAt(env, "holzyn.actor.provision.enabled"));
        assertEquals("true", strAt(env, "holzyn.actor.provision.npc-kit"));
        assertEquals("true", strAt(env, "holzyn.actor.provision.location-stock"));
        assertEquals("true", strAt(env, "holzyn.actor.provision.player-kit"));
        assertEquals("true", strAt(env, "holzyn.actor.provision.auto-wear"));
        assertEquals("500", strAt(env, "holzyn.actor.provision.max-holders"));

        ProvisionProperties props = bind(env);
        assertTrue(props.isEnabled(), "ProvisionProperties.enabled 默认必须 true");
        assertTrue(props.isNpcKit());
        assertTrue(props.isLocationStock());
        assertTrue(props.isPlayerKit());
        assertTrue(props.isAutoWear());
        assertEquals(500, props.getMaxHolders());
    }

    @Test
    @DisplayName("环境变量覆盖：总开关/分项/上限（占位符名精确匹配）")
    void overridableByEnvironment() throws IOException {
        System.setProperty("HOLOZYN_ACTOR_PROVISION_ENABLED", "false");
        System.setProperty("HOLOZYN_ACTOR_PROVISION_NPC_KIT", "false");
        System.setProperty("HOLOZYN_ACTOR_PROVISION_LOCATION_STOCK", "false");
        System.setProperty("HOLOZYN_ACTOR_PROVISION_PLAYER_KIT", "false");
        System.setProperty("HOLOZYN_ACTOR_PROVISION_AUTO_WEAR", "false");
        System.setProperty("HOLOZYN_ACTOR_PROVISION_MAX_HOLDERS", "20");
        try {
            StandardEnvironment env = env();
            assertEquals("false", strAt(env, "holzyn.actor.provision.enabled"));
            assertEquals("false", strAt(env, "holzyn.actor.provision.npc-kit"));
            assertEquals("false", strAt(env, "holzyn.actor.provision.location-stock"));
            assertEquals("false", strAt(env, "holzyn.actor.provision.player-kit"));
            assertEquals("false", strAt(env, "holzyn.actor.provision.auto-wear"));
            assertEquals("20", strAt(env, "holzyn.actor.provision.max-holders"));

            ProvisionProperties props = bind(env);
            assertFalse(props.isEnabled(), "总开关未被环境变量覆盖");
            assertFalse(props.isNpcKit());
            assertFalse(props.isLocationStock());
            assertFalse(props.isPlayerKit());
            assertFalse(props.isAutoWear());
            assertEquals(20, props.getMaxHolders());
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_PROVISION_ENABLED");
            System.clearProperty("HOLOZYN_ACTOR_PROVISION_NPC_KIT");
            System.clearProperty("HOLOZYN_ACTOR_PROVISION_LOCATION_STOCK");
            System.clearProperty("HOLOZYN_ACTOR_PROVISION_PLAYER_KIT");
            System.clearProperty("HOLOZYN_ACTOR_PROVISION_AUTO_WEAR");
            System.clearProperty("HOLOZYN_ACTOR_PROVISION_MAX_HOLDERS");
        }
        assertTrue(bind(env()).isEnabled(), "清理后应回到默认 true");
    }
}
