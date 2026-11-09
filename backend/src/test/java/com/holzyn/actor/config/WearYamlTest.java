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

import com.holzyn.actor.domain.item.wear.WearProperties;
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
 * 损耗配置校验。
 *
 * <p>本仓库没有任何 {@code @SpringBootTest}，yml 键名拼错不会被既有套件发现，
 * 只会在真实启动时炸（AGENTS §8.4.3）。本测试直载 yml、不启 Spring 上下文。</p>
 *
 * <p>锁定内容：</p>
 * <ol>
 *   <li>损耗段挂在 {@code holzyn.actor.wear}（不是 {@code item.wear}）；</li>
 *   <li>默认值与设计一致：<b>默认关</b>、每次挥砍 2 点、卷刃阈值 25、修理 25、基准伤害 8；</li>
 *   <li>关键项可被 {@code HOLOZYN_ACTOR_WEAR_*} 覆盖（占位符名精确匹配）；</li>
 *   <li>装备事实同步默认开、可见性默认 location。</li>
 * </ol>
 */
class WearYamlTest {

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

    private static WearProperties bind(StandardEnvironment env) {
        return Binder.get(env)
                .bind("holzyn.actor.wear", Bindable.of(WearProperties.class))
                .orElseThrow(() -> new AssertionError("WearProperties 绑定失败（wear 段缺失或键名拼错）"));
    }

    @Test
    @DisplayName("I4 默认：wear.enabled=false（行为回 I3），item.enabled 也默认关")
    void disabledByDefault() throws IOException {
        StandardEnvironment env = env();
        assertEquals("false", strAt(env, "holzyn.actor.wear.enabled"));
        assertFalse(bind(env).isEnabled(), "WearProperties.enabled 默认必须 false");
        assertEquals("false", strAt(env, "holzyn.actor.item.enabled"),
                "物品域默认关闭 → 没有可信持有栈 → 损耗域不生效");
    }

    @Test
    @DisplayName("wear 段：2 / 25 / 25 / 8 / true / location")
    void defaultsResolve() throws IOException {
        StandardEnvironment env = env();
        assertEquals("2", strAt(env, "holzyn.actor.wear.attack-wear"));
        assertEquals("25", strAt(env, "holzyn.actor.wear.blunt-threshold"));
        assertEquals("25", strAt(env, "holzyn.actor.wear.repair-points"));
        assertEquals("8", strAt(env, "holzyn.actor.wear.base-damage"));
        assertEquals("true", strAt(env, "holzyn.actor.wear.equip-fact-sync"));
        assertEquals("location", strAt(env, "holzyn.actor.wear.equip-visibility"));

        WearProperties props = bind(env);
        assertEquals(2, props.getAttackWear());
        assertEquals(25, props.getBluntThreshold());
        assertEquals(25, props.getRepairPoints());
        assertEquals(8, props.getBaseDamage());
        assertTrue(props.isEquipFactSync(), "装备事实同步默认开");
        assertEquals("location", props.getEquipVisibility());
    }

    @Test
    @DisplayName("环境变量覆盖：开关/磨损点/卷刃阈值/基准伤害（占位符名精确匹配）")
    void overridableByEnvironment() throws IOException {
        System.setProperty("HOLOZYN_ACTOR_WEAR_ENABLED", "true");
        System.setProperty("HOLOZYN_ACTOR_WEAR_ATTACK_WEAR", "5");
        System.setProperty("HOLOZYN_ACTOR_WEAR_BLUNT_THRESHOLD", "40");
        System.setProperty("HOLOZYN_ACTOR_WEAR_BASE_DAMAGE", "12");
        try {
            StandardEnvironment env = env();
            assertEquals("true", strAt(env, "holzyn.actor.wear.enabled"));
            assertEquals("5", strAt(env, "holzyn.actor.wear.attack-wear"));
            assertEquals("40", strAt(env, "holzyn.actor.wear.blunt-threshold"));
            assertEquals("12", strAt(env, "holzyn.actor.wear.base-damage"));

            WearProperties props = bind(env);
            assertTrue(props.isEnabled());
            assertEquals(5, props.getAttackWear());
            assertEquals(40, props.getBluntThreshold());
            assertEquals(12, props.getBaseDamage());
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_WEAR_ENABLED");
            System.clearProperty("HOLOZYN_ACTOR_WEAR_ATTACK_WEAR");
            System.clearProperty("HOLOZYN_ACTOR_WEAR_BLUNT_THRESHOLD");
            System.clearProperty("HOLOZYN_ACTOR_WEAR_BASE_DAMAGE");
        }
        assertFalse(bind(env()).isEnabled(), "清理后应回到默认 false");
    }
}
