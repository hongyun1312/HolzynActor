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

import com.holzyn.actor.domain.item.config.ItemProperties;
import com.holzyn.actor.domain.item.model.ItemProvider;
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
 * 物品域配置校验。
 *
 * <p>为什么必须有：本仓库没有任何 {@code @SpringBootTest}，yml 键名拼错/占位符写坏
 * 不会被既有套件发现，只会在真实启动时炸（AGENTS §8.4.3）。本测试直载 yml、不启
 * Spring 上下文，同时校验「键可解析」与「生产实际读取方式（{@link ItemProperties} 绑定）」。</p>
 *
 * <p>锁定内容：</p>
 * <ol>
 *   <li>总开关默认 <b>false</b>（未装配端口时行为与基线一致）；</li>
 *   <li>{@code provider/lazy-def/default-stack-max/max-qty-per-transfer} 默认值与设计文档一致；</li>
 *   <li>关键项可被 {@code HOLOZYN_ACTOR_ITEM_*} 环境变量覆盖（占位符名精确匹配 yml）；</li>
 *   <li>{@link ItemProvider} 可从 yml 文本（db/engine）绑定，未知值回退 DB。</li>
 * </ol>
 */
class ItemYamlTest {

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

    private static ItemProperties bind(StandardEnvironment env) {
        return Binder.get(env)
                .bind("holzyn.actor.item", Bindable.of(ItemProperties.class))
                .orElseThrow(() -> new AssertionError("ItemProperties 绑定失败（item 段缺失或键名拼错）"));
    }

    @Test
    @DisplayName("I1 默认：item.enabled=false，关闭时 InventoryPort 不装配")
    void disabledByDefault() throws IOException {
        StandardEnvironment env = env();
        assertEquals("false", strAt(env, "holzyn.actor.item.enabled"),
                "I1 总开关必须默认 false（R24/I8：行为与基线一致）");
        assertFalse(bind(env).isEnabled(), "ItemProperties.enabled 默认必须 false");
    }

    @Test
    @DisplayName("item 段：provider=db / lazy-def=true / 99 / 999")
    void defaultsResolve() throws IOException {
        StandardEnvironment env = env();
        assertEquals("db", strAt(env, "holzyn.actor.item.provider"));
        assertEquals("true", strAt(env, "holzyn.actor.item.lazy-def"));
        assertEquals("99", strAt(env, "holzyn.actor.item.default-stack-max"));
        assertEquals("999", strAt(env, "holzyn.actor.item.max-qty-per-transfer"));

        ItemProperties props = bind(env);
        assertEquals(ItemProvider.DB, props.getProvider());
        assertTrue(props.isLazyDef());
        assertEquals(99, props.getDefaultStackMax());
        assertEquals(999, props.getMaxQtyPerTransfer());
    }

    @Test
    @DisplayName("环境变量覆盖：enabled/provider/lazy-def/上限（占位符名精确匹配）")
    void overridableByEnvironment() throws IOException {
        System.setProperty("HOLOZYN_ACTOR_ITEM_ENABLED", "true");
        System.setProperty("HOLOZYN_ACTOR_ITEM_PROVIDER", "engine");
        System.setProperty("HOLOZYN_ACTOR_ITEM_LAZY_DEF", "false");
        System.setProperty("HOLOZYN_ACTOR_ITEM_MAX_QTY_PER_TRANSFER", "50");
        try {
            StandardEnvironment env = env();
            assertEquals("true", strAt(env, "holzyn.actor.item.enabled"));
            assertEquals("engine", strAt(env, "holzyn.actor.item.provider"));
            assertEquals("false", strAt(env, "holzyn.actor.item.lazy-def"));
            assertEquals("50", strAt(env, "holzyn.actor.item.max-qty-per-transfer"));

            ItemProperties props = bind(env);
            assertTrue(props.isEnabled(), "总开关未被环境变量覆盖");
            assertEquals(ItemProvider.ENGINE, props.getProvider(), "provider 未绑定为 engine");
            assertFalse(props.isLazyDef());
            assertEquals(50, props.getMaxQtyPerTransfer());
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_ITEM_ENABLED");
            System.clearProperty("HOLOZYN_ACTOR_ITEM_PROVIDER");
            System.clearProperty("HOLOZYN_ACTOR_ITEM_LAZY_DEF");
            System.clearProperty("HOLOZYN_ACTOR_ITEM_MAX_QTY_PER_TRANSFER");
        }
        assertFalse(bind(env()).isEnabled(), "清理后应回到默认 false");
    }

    @Test
    @DisplayName("provider 枚举：db/engine 可绑定，未知/空回退 DB")
    void providerParsesAllValues() {
        assertEquals(ItemProvider.DB, ItemProvider.parse("db"));
        assertEquals(ItemProvider.ENGINE, ItemProvider.parse(" ENGINE "));
        assertEquals(ItemProvider.DB, ItemProvider.parse("unknown"));
        assertEquals(ItemProvider.DB, ItemProvider.parse(null));
    }
}
