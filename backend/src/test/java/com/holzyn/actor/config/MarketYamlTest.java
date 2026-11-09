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

import com.holzyn.actor.domain.market.config.MarketProperties;
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
 * 市场配置校验。
 *
 * <p>本仓库没有任何 {@code @SpringBootTest}，yml 键名拼错不会被既有套件发现，
 * 只会在真实启动时炸（AGENTS §8.4.3）。本测试直载 yml、不启 Spring 上下文。</p>
 *
 * <p>锁定内容：</p>
 * <ol>
 *   <li>市场段挂在 {@code holzyn.actor.market}（不是 {@code item.market}）；</li>
 *   <li>默认值与设计一致：货币 coin、基准价 10、中性库存 10、需求窗口 7 天；</li>
 *   <li>关键项可被 {@code HOLOZYN_ACTOR_MARKET_*} 覆盖；</li>
 *   <li>默认 <b>开启</b>，但真正生效还要求 {@code item.enabled=true}（端口装配）。</li>
 * </ol>
 */
class MarketYamlTest {

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

    private static MarketProperties bind(StandardEnvironment env) {
        return Binder.get(env)
                .bind("holzyn.actor.market", Bindable.of(MarketProperties.class))
                .orElseThrow(() -> new AssertionError("MarketProperties 绑定失败（market 段缺失或键名拼错）"));
    }

    @Test
    @DisplayName("I2 默认：market.enabled=true，但 item.enabled=false 时市场不生效")
    void enabledByDefaultButGatedByItem() throws IOException {
        StandardEnvironment env = env();
        assertEquals("true", strAt(env, "holzyn.actor.market.enabled"));
        assertTrue(bind(env).isEnabled(), "MarketProperties.enabled 默认必须 true");
        assertEquals("false", strAt(env, "holzyn.actor.item.enabled"),
                "物品域默认关闭 → 端口不装配 → MarketService.enabled()=false");
    }

    @Test
    @DisplayName("market 段：coin / 10 / 1 / 100000 / 10 / 7 / 99")
    void defaultsResolve() throws IOException {
        StandardEnvironment env = env();
        assertEquals("coin", strAt(env, "holzyn.actor.market.currency-item-key"));
        assertEquals("10", strAt(env, "holzyn.actor.market.default-base-price"));
        assertEquals("1", strAt(env, "holzyn.actor.market.min-price"));
        assertEquals("100000", strAt(env, "holzyn.actor.market.max-price"));
        assertEquals("10", strAt(env, "holzyn.actor.market.neutral-stock"));
        assertEquals("7", strAt(env, "holzyn.actor.market.demand-window-days"));
        assertEquals("99", strAt(env, "holzyn.actor.market.max-qty-per-trade"));

        MarketProperties props = bind(env);
        assertEquals("coin", props.getCurrencyItemKey());
        assertEquals(10, props.getDefaultBasePrice());
        assertEquals(1, props.getMinPrice());
        assertEquals(100000, props.getMaxPrice());
        assertEquals(10, props.getNeutralStock());
        assertEquals(7, props.getDemandWindowDays());
        assertEquals(99, props.getMaxQtyPerTrade());
        assertTrue(props.isStockFactSync(), "stock 事实同步默认开");
    }

    @Test
    @DisplayName("环境变量覆盖：货币键/基准价/中性库存/成交上限（占位符名精确匹配）")
    void overridableByEnvironment() throws IOException {
        System.setProperty("HOLOZYN_ACTOR_MARKET_CURRENCY_ITEM_KEY", "silver_coin");
        System.setProperty("HOLOZYN_ACTOR_MARKET_DEFAULT_BASE_PRICE", "25");
        System.setProperty("HOLOZYN_ACTOR_MARKET_NEUTRAL_STOCK", "40");
        System.setProperty("HOLOZYN_ACTOR_MARKET_MAX_QTY_PER_TRADE", "5");
        try {
            StandardEnvironment env = env();
            assertEquals("silver_coin", strAt(env, "holzyn.actor.market.currency-item-key"));
            assertEquals("25", strAt(env, "holzyn.actor.market.default-base-price"));
            assertEquals("40", strAt(env, "holzyn.actor.market.neutral-stock"));
            assertEquals("5", strAt(env, "holzyn.actor.market.max-qty-per-trade"));

            MarketProperties props = bind(env);
            assertEquals("silver_coin", props.getCurrencyItemKey());
            assertEquals(25, props.getDefaultBasePrice());
            assertEquals(40, props.getNeutralStock());
            assertEquals(5, props.getMaxQtyPerTrade());
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_MARKET_CURRENCY_ITEM_KEY");
            System.clearProperty("HOLOZYN_ACTOR_MARKET_DEFAULT_BASE_PRICE");
            System.clearProperty("HOLOZYN_ACTOR_MARKET_NEUTRAL_STOCK");
            System.clearProperty("HOLOZYN_ACTOR_MARKET_MAX_QTY_PER_TRADE");
        }
        assertEquals("coin", bind(env()).getCurrencyItemKey(), "清理后应回到默认 coin");
    }

    @Test
    @DisplayName("开关可关：HOLOZYN_ACTOR_MARKET_ENABLED=false 时市场段整体停用")
    void canBeDisabled() throws IOException {
        System.setProperty("HOLOZYN_ACTOR_MARKET_ENABLED", "false");
        try {
            assertFalse(bind(env()).isEnabled());
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_MARKET_ENABLED");
        }
        assertTrue(bind(env()).isEnabled());
    }
}
