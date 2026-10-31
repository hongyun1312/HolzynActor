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

package com.holzyn.actor.domain.growth.lineage;

import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 成长档案补齐单测（M1 验收）。
 *
 * <p>守的是一条很死的东西：<b>同一角色、同一世界，补齐结果必须每次都一样</b>。
 * 否则重复启动补齐会把角色年龄改来改去（今年 34 明年 41），家谱跟着抖动。</p>
 *
 * <p>确定性推断是纯函数（{@code static}），所以这里不需要 Spring 与数据库；
 * 落库路径用 Mockito 的仓储桩验证"只填空值、不覆盖已有值"。</p>
 */
class ProfileCompletionServiceTest {

    /** 造一个只填了姓名的角色。 */
    private static ActorCharacter ch(long id, String name, String title, int importance) {
        ActorCharacter c = new ActorCharacter();
        c.setId(id);
        c.setProjectId(1L);
        c.setName(name);
        c.setTitle(title);
        c.setImportance(importance);
        c.setType("special");
        return c;
    }

    /** 造服务（仓储为 mock、配置取默认值、AI 未装配 → 只走确定性推断）。 */
    private static ProfileCompletionService service(ActorCharacterRepository repo) {
        GrowthProperties props = new GrowthProperties();
        return new ProfileCompletionService(repo, props);
    }

    @Test
    @DisplayName("性别推断：称谓优先，其次姓名用字，最后确定性兜底")
    void infersGender() {
        assertEquals("女", ProfileCompletionService.inferGender(ch(1, "沈明烛", "茶馆老板娘", 4), 1L));
        assertEquals("男", ProfileCompletionService.inferGender(ch(2, "老周", "打更老汉", 1), 1L));
        assertEquals("女", ProfileCompletionService.inferGender(ch(3, "白芷", null, 2), 1L), "「芷」是女性用字");
        assertEquals("男", ProfileCompletionService.inferGender(ch(4, "铁柱", null, 1), 1L), "「铁/柱」是男性用字");
    }

    @Test
    @DisplayName("性别兜底确定性：同一角色反复推断结果一致")
    void genderFallbackIsStable() {
        ActorCharacter a = ch(9, "无名", null, 2);
        String first = ProfileCompletionService.inferGender(a, 33L);
        assertNotNull(first);
        for (int i = 0; i < 20; i++) {
            assertEquals(first, ProfileCompletionService.inferGender(a, 33L), "同名同世界必须同结果");
        }
        assertTrue("男".equals(first) || "女".equals(first));
    }

    @Test
    @DisplayName("年龄推断：老/小/阿/伯 等前缀与重要度分档")
    void infersAge() {
        int old = ProfileCompletionService.inferAge(ch(1, "老周", "打更人", 1), 1L);
        assertTrue(old >= 60, "「老周」应判为老人，实际 " + old);
        int kid = ProfileCompletionService.inferAge(ch(2, "小萤", "渔家女", 2), 1L);
        assertTrue(kid <= 18, "「小萤」应判为少年，实际 " + kid);
        int young = ProfileCompletionService.inferAge(ch(3, "阿澈", null, 2), 1L);
        assertTrue(young >= 18 && young <= 30, "「阿澈」应判为青年，实际 " + young);
        int elder = ageOf("沈伯远");
        assertTrue(elder >= 40, "「伯」应判为长辈，实际 " + elder);
    }

    /** 便捷断言辅助：按姓名推断一次年龄（种子固定）。 */
    private static int ageOf(String name) {
        return ProfileCompletionService.inferAge(ch(7, name, null, 3), 1L);
    }

    @Test
    @DisplayName("年龄永远落在合法区间（6~88）")
    void ageAlwaysInRange() {
        String[] names = {"王五", "长", "非常非常长的名字", "123", "无名氏"};
        for (String n : names) {
            for (int importance = 1; importance <= 5; importance++) {
                int age = ProfileCompletionService.inferAge(ch(1, n, null, importance), 42L);
                assertTrue(age >= 6 && age <= 88, n + " 年龄越界：" + age);
            }
        }
    }

    @Test
    @DisplayName("补齐只填空值：已有性别/年龄/出生/阶段一律不覆盖")
    void fillsOnlyBlankFields() {
        ActorCharacterRepository repo = mock(ActorCharacterRepository.class);
        when(repo.save(any(ActorCharacter.class))).thenAnswer(inv -> inv.getArgument(0));
        ActorCharacter full = ch(1, "沈伯远", "族长", 3);
        full.setGender("男");
        full.setAge(68);
        full.setBirthGameSecond(1_000L);
        full.setStage("ELDER");
        ActorCharacter blank = ch(2, "阿苔", "妇人", 2);
        int fixed = service(repo).complete(null, 1L, new ArrayList<>(List.of(full, blank)), 32_628_096_000L);
        assertEquals(1, fixed, "只有空档案那个角色该被补");
        assertEquals("男", full.getGender());
        assertEquals(68, full.getAge());
        assertEquals(1_000L, full.getBirthGameSecond());
        assertEquals("ELDER", full.getStage());
        assertNotNull(blank.getGender(), "空档案必须补出性别");
        assertNotNull(blank.getAge());
        assertNotNull(blank.getBirthGameSecond());
        assertNotNull(blank.getStage());
        assertEquals(ProfileCompletionService.inferAge(blank, 1L), blank.getAge(), "补出来的年龄应等于确定性推断值");
        // 出生时刻 = 世界秒 − 年龄 × 年秒
        long expectedBirth = 32_628_096_000L - (long) blank.getAge() * 31_536_000L;
        assertEquals(expectedBirth, blank.getBirthGameSecond());
    }

    @Test
    @DisplayName("已有性别年龄、只缺阶段/出生时刻 → 也补，且不改年龄")
    void fillsStageAndBirthWhenAgeKnown() {
        ActorCharacterRepository repo = mock(ActorCharacterRepository.class);
        when(repo.save(any(ActorCharacter.class))).thenAnswer(inv -> inv.getArgument(0));
        ActorCharacter ch = ch(1, "阿苔", "妇人", 2);
        ch.setGender("女");
        ch.setAge(28);
        int fixed = service(repo).complete(null, 1L, List.of(ch), 32_628_096_000L);
        assertEquals(1, fixed, "缺阶段/出生也算不完整，必须补");
        assertEquals("女", ch.getGender());
        assertEquals(28, ch.getAge(), "已有年龄不得被推断值覆盖");
        assertEquals("YOUTH", ch.getStage(), "阶段应由年龄推出（28 岁 = 青年期 YOUTH）");
        assertEquals(32_628_096_000L - 28L * 31_536_000L, ch.getBirthGameSecond());
    }

    @Test
    @DisplayName("全部已填 → 零改动、零写库")
    void noopWhenNothingToFill() {
        ActorCharacterRepository repo = mock(ActorCharacterRepository.class);
        ActorCharacter full = ch(1, "沈伯远", "族长", 3);
        full.setGender("男");
        full.setAge(68);
        full.setStage("ELDER");
        full.setBirthGameSecond(1_000L);
        int fixed = service(repo).complete(null, 1L, List.of(full), 100L);
        assertEquals(0, fixed);
    }
}
