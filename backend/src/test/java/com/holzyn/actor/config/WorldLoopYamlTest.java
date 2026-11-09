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

import com.holzyn.actor.domain.world.authority.model.WorldAuthorityMode;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
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
 * 角色↔世界闭环配置校验。
 *
 * <p>为什么必须有这个测试：本仓库没有任何 {@code @SpringBootTest}，yml 键名拼错/占位符写坏
 * 不会被既有套件发现，只会在真实启动时炸（AGENTS §8.4.3）。本测试直载 yml、不启 Spring 上下文，
 * 同时校验「键可解析」与「生产实际读取方式（{@link WorldLoopProperties} 绑定）」。</p>
 *
 * <p>锁定内容：</p>
 * <ol>
 *   <li>总开关默认 <b>true</b>（翻转：各阶段全部交付后，关着就等于把
 *       「对话回写 / 事实感知 / 行动裁决」整条闭环摘掉，I 阶段的吃买做装备全部不执行）；</li>
 *   <li>authority/dialog/fact/adjudicate/causal/initiative 六段默认值与§4.10 一致；</li>
 *   <li>关键项可被 {@code HOLOZYN_ACTOR_WORLD_LOOP_*} 环境变量覆盖（占位符名精确匹配 yml）；</li>
 *   <li>{@link WorldAuthorityMode} 枚举可从 yml 文本（auto/brain/engine）绑定。</li>
 * </ol>
 */
class WorldLoopYamlTest {

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

    private static WorldLoopProperties bind(StandardEnvironment env) {
        return Binder.get(env)
                .bind("holzyn.actor.world-loop", Bindable.of(WorldLoopProperties.class))
                .orElseThrow(() -> new AssertionError("WorldLoopProperties 绑定失败（world-loop 段缺失或键名拼错）"));
    }

    @Test
    @DisplayName("默认：world-loop.enabled=true（M×I 闭环默认挂载，可用 env=false 退回）")
    void enabledByDefault() throws IOException {
        StandardEnvironment env = env();
        assertEquals("true", strAt(env, "holzyn.actor.world-loop.enabled"),
                "总开关必须默认 true：关着时 I 阶段挂在裁决器上的闭环全部不执行");
        assertTrue(bind(env).isEnabled(), "WorldLoopProperties 绑定后 enabled 必须为 true");
    }

    @Test
    @DisplayName("authority 段：默认 auto / 关世界才切 / 分页 500 / 过期 10 分钟 / drift 0")
    void authorityDefaultsResolve() throws IOException {
        StandardEnvironment env = env();
        assertEquals("auto", strAt(env, "holzyn.actor.world-loop.authority.mode"));
        assertEquals("true", strAt(env, "holzyn.actor.world-loop.authority.require-closed-for-switch"));
        assertEquals("500", strAt(env, "holzyn.actor.world-loop.authority.hydrate-page-size"));
        assertEquals("10", strAt(env, "holzyn.actor.world-loop.authority.stale-warn-game-minutes"));
        assertEquals("0", strAt(env, "holzyn.actor.world-loop.authority.drift-tolerance"));

        WorldLoopProperties props = bind(env);
        assertEquals(WorldAuthorityMode.AUTO, props.getAuthority().getMode());
        assertTrue(props.getAuthority().isRequireClosedForSwitch());
        assertEquals(500, props.getAuthority().getHydratePageSize());
        assertEquals(10, props.getAuthority().getStaleWarnGameMinutes());
        assertEquals(0, props.getAuthority().getDriftTolerance());
    }

    @Test
    @DisplayName("dialog/fact/adjudicate/causal/initiative 段：默认值与设计文档一致")
    void subsystemDefaultsResolve() throws IOException {
        StandardEnvironment env = env();
        assertEquals("true", strAt(env, "holzyn.actor.world-loop.dialog.enabled"));
        assertEquals("6", strAt(env, "holzyn.actor.world-loop.dialog.extract-batch-rounds"));
        assertEquals("10", strAt(env, "holzyn.actor.world-loop.dialog.extract-idle-game-minutes"));
        assertEquals("2", strAt(env, "holzyn.actor.world-loop.dialog.time-cost-minutes-per-round"));
        assertEquals("true", strAt(env, "holzyn.actor.world-loop.dialog.announce-to-location"));

        assertEquals("true", strAt(env, "holzyn.actor.world-loop.fact.enabled"));
        assertEquals("8", strAt(env, "holzyn.actor.world-loop.fact.prompt-top-k"));
        assertEquals("1", strAt(env, "holzyn.actor.world-loop.fact.sweep-cron-game-hours"));
        assertEquals("true", strAt(env, "holzyn.actor.world-loop.fact.rumor-from-secret"));

        assertEquals("true", strAt(env, "holzyn.actor.world-loop.adjudicate.enabled"));
        assertEquals("4", strAt(env, "holzyn.actor.world-loop.adjudicate.ai-min-importance"));
        assertEquals("4", strAt(env, "holzyn.actor.world-loop.adjudicate.ai-max-effects"));
        assertEquals("true", strAt(env, "holzyn.actor.world-loop.adjudicate.default-allow"));

        assertEquals("true", strAt(env, "holzyn.actor.world-loop.causal.enabled"));
        assertEquals("6", strAt(env, "holzyn.actor.world-loop.causal.max-depth"));
        assertEquals("32", strAt(env, "holzyn.actor.world-loop.causal.max-nodes-per-chain"));
        assertEquals("6", strAt(env, "holzyn.actor.world-loop.causal.max-reactions-per-actor-hour"));

        assertEquals("false", strAt(env, "holzyn.actor.world-loop.initiative.enabled"));
        assertEquals("3", strAt(env, "holzyn.actor.world-loop.initiative.min-urgency"));
        assertEquals("2", strAt(env, "holzyn.actor.world-loop.initiative.max-per-hour"));
        assertEquals("3", strAt(env, "holzyn.actor.world-loop.initiative.absence-days"));
        assertEquals("true", strAt(env, "holzyn.actor.world-loop.initiative.visit-notify"));

        WorldLoopProperties props = bind(env);
        assertEquals(6, props.getDialog().getExtractBatchRounds());
        assertEquals(2, props.getDialog().getTimeCostMinutesPerRound());
        assertEquals(8, props.getFact().getPromptTopK());
        assertEquals(4, props.getAdjudicate().getAiMinImportance());
        assertEquals(6, props.getCausal().getMaxDepth());
        assertEquals(32, props.getCausal().getMaxNodesPerChain());
        assertFalse(props.getInitiative().isEnabled());
    }

    @Test
    @DisplayName("环境变量覆盖：总开关/权威模式/因果深度/主动性（占位符名精确匹配）")
    void overridableByEnvironment() throws IOException {
        System.setProperty("HOLOZYN_ACTOR_WORLD_LOOP_ENABLED", "true");
        System.setProperty("HOLOZYN_ACTOR_WORLD_LOOP_AUTHORITY_MODE", "brain");
        System.setProperty("HOLOZYN_ACTOR_WORLD_LOOP_CAUSAL_MAX_DEPTH", "3");
        System.setProperty("HOLOZYN_ACTOR_WORLD_LOOP_INITIATIVE_ENABLED", "true");
        System.setProperty("HOLOZYN_ACTOR_WORLD_LOOP_INITIATIVE_ABSENCE_DAYS", "5");
        try {
            StandardEnvironment env = env();
            assertEquals("true", strAt(env, "holzyn.actor.world-loop.enabled"));
            assertEquals("brain", strAt(env, "holzyn.actor.world-loop.authority.mode"));
            assertEquals("3", strAt(env, "holzyn.actor.world-loop.causal.max-depth"));
            assertEquals("true", strAt(env, "holzyn.actor.world-loop.initiative.enabled"));
            assertEquals("5", strAt(env, "holzyn.actor.world-loop.initiative.absence-days"));

            WorldLoopProperties props = bind(env);
            assertTrue(props.isEnabled(), "总开关未被环境变量覆盖");
            assertEquals(WorldAuthorityMode.BRAIN, props.getAuthority().getMode(), "权威模式未绑定为 brain");
            assertEquals(3, props.getCausal().getMaxDepth(), "因果深度未被环境变量覆盖");
            assertTrue(props.getInitiative().isEnabled(), "主动性开关未被环境变量覆盖");
            assertEquals(5, props.getInitiative().getAbsenceDays(), "久未见面阈值未被环境变量覆盖");
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_WORLD_LOOP_ENABLED");
            System.clearProperty("HOLOZYN_ACTOR_WORLD_LOOP_AUTHORITY_MODE");
            System.clearProperty("HOLOZYN_ACTOR_WORLD_LOOP_CAUSAL_MAX_DEPTH");
            System.clearProperty("HOLOZYN_ACTOR_WORLD_LOOP_INITIATIVE_ENABLED");
            System.clearProperty("HOLOZYN_ACTOR_WORLD_LOOP_INITIATIVE_ABSENCE_DAYS");
        }
        assertTrue(bind(env()).isEnabled(), "清理后应回到默认 true");
    }

    @Test
    @DisplayName("authority.mode 枚举：auto/brain/engine 三种文本均可绑定")
    void authorityModeBindsAllEnumValues() throws IOException {
        assertEquals(WorldAuthorityMode.AUTO, bind(env()).getAuthority().getMode());
        assertEquals(WorldAuthorityMode.BRAIN, WorldAuthorityMode.parse("brain"));
        assertEquals(WorldAuthorityMode.ENGINE, WorldAuthorityMode.parse(" ENGINE "));
        assertEquals(WorldAuthorityMode.AUTO, WorldAuthorityMode.parse("unknown"));
        assertEquals(WorldAuthorityMode.AUTO, WorldAuthorityMode.parse(null));
    }
}
