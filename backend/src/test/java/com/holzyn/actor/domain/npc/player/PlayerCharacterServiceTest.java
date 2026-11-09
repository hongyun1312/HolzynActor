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

package com.holzyn.actor.domain.npc.player;

import com.holzyn.actor.domain.dialogue.entity.ActorPlayerProfile;
import com.holzyn.actor.domain.dialogue.service.PlayerProfileService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 玩家同构服务单元测试（§4.4.3）。
 *
 * <p>锁四条：① 开关关闭零行为；② 幂等（已有行直接返回，不插第二行）；
 * ③ 名字取玩家档案昵称、空则「旅行者」、超长截断 50；④ 并发唯一索引冲突回查。</p>
 */
class PlayerCharacterServiceTest {

    private ActorCharacterRepository characterRepository;
    private PlayerProfileService playerProfileService;
    private WorldLoopProperties properties;
    private PlayerCharacterService service;

    @BeforeEach
    void setUp() {
        characterRepository = mock(ActorCharacterRepository.class);
        playerProfileService = mock(PlayerProfileService.class);
        properties = new WorldLoopProperties();
        properties.setEnabled(true);
        service = new PlayerCharacterService(characterRepository, playerProfileService, properties);
        when(characterRepository.save(any(ActorCharacter.class))).thenAnswer(inv -> {
            ActorCharacter ch = inv.getArgument(0);
            if (ch.getId() == null) {
                ch.setId(100L);
            }
            return ch;
        });
    }

    @Test
    @DisplayName("开关关闭：不查库、不创建（M0 默认行为与基线一致）")
    void disabledIsNoop() {
        properties.setEnabled(false);
        assertNull(service.ensurePlayerCharacter(1L, 9L));
        verify(characterRepository, never()).findFirstByProjectIdAndControllerAndDeletedOrderByIdAsc(
                anyLong(), anyString(), anyInt());
        verify(characterRepository, never()).save(any(ActorCharacter.class));
    }

    @Test
    @DisplayName("幂等：已有 controller=player 行直接返回，不插第二行（A5）")
    void existingPlayerReturned() {
        ActorCharacter existing = new ActorCharacter();
        existing.setId(7L);
        existing.setProjectId(1L);
        existing.setController("player");
        existing.setName("老玩家");
        when(characterRepository.findFirstByProjectIdAndControllerAndDeletedOrderByIdAsc(1L, "player", 0))
                .thenReturn(Optional.of(existing));

        assertTrue(existing == service.ensurePlayerCharacter(1L, 9L));
        verify(characterRepository, never()).save(any(ActorCharacter.class));
    }

    @Test
    @DisplayName("创建：昵称取玩家档案、controller=player、importance=5、不置 is_protagonist")
    void createsPlayerFromProfileNickname() {
        when(characterRepository.findFirstByProjectIdAndControllerAndDeletedOrderByIdAsc(1L, "player", 0))
                .thenReturn(Optional.empty());
        ActorPlayerProfile profile = new ActorPlayerProfile();
        profile.setName("小明");
        when(playerProfileService.profileOf(9L)).thenReturn(profile);

        ActorCharacter player = service.ensurePlayerCharacter(1L, 9L);

        assertEquals(Long.valueOf(100L), player.getId());
        assertEquals("小明", player.getName());
        assertEquals("player", player.getController());
        assertEquals("9", player.getPlayerUserId());
        assertEquals("special", player.getType());
        assertEquals(Integer.valueOf(5), player.getImportance());
        assertEquals(Integer.valueOf(0), player.getIsProtagonist(), "玩家不置 is_protagonist");
        assertEquals(Integer.valueOf(1), player.getStatus());
        assertEquals(Integer.valueOf(0), player.getDeleted());
        assertTrue(player.isPlayer());
        assertEquals("npc:100", player.npcRef(), "引用沿用 npc: 前缀（R5）");
    }

    @Test
    @DisplayName("昵称为空 → 旅行者；档案读取异常也回退旅行者")
    void fallsBackToTravelerName() {
        when(characterRepository.findFirstByProjectIdAndControllerAndDeletedOrderByIdAsc(1L, "player", 0))
                .thenReturn(Optional.empty());
        ActorPlayerProfile blank = new ActorPlayerProfile();
        blank.setName("  ");
        when(playerProfileService.profileOf(9L)).thenReturn(blank);
        ActorCharacter first = service.ensurePlayerCharacter(1L, 9L);
        assertEquals("旅行者", first.getName());

        when(playerProfileService.profileOf(10L)).thenThrow(new IllegalStateException("account db down"));
        ActorCharacter second = service.ensurePlayerCharacter(1L, 10L);
        assertEquals("旅行者", second.getName(), "档案异常不得阻断玩家创建");
    }

    @Test
    @DisplayName("昵称超长截断到 50 字（actor_profile.name varchar(50)）")
    void truncatesLongName() {
        when(characterRepository.findFirstByProjectIdAndControllerAndDeletedOrderByIdAsc(1L, "player", 0))
                .thenReturn(Optional.empty());
        ActorPlayerProfile profile = new ActorPlayerProfile();
        profile.setName("名".repeat(60));
        when(playerProfileService.profileOf(9L)).thenReturn(profile);

        ActorCharacter player = service.ensurePlayerCharacter(1L, 9L);

        assertEquals(50, player.getName().length());
    }

    @Test
    @DisplayName("并发唯一索引冲突：回查返回先创建的行，不抛异常")
    void raceFallsBackToExistingRow() {
        ActorCharacter winner = new ActorCharacter();
        winner.setId(88L);
        winner.setProjectId(1L);
        winner.setController("player");
        when(characterRepository.findFirstByProjectIdAndControllerAndDeletedOrderByIdAsc(1L, "player", 0))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(characterRepository.save(any(ActorCharacter.class)))
                .thenThrow(new DataIntegrityViolationException("ux_profile_player"));

        ActorCharacter player = service.ensurePlayerCharacter(1L, 9L);

        assertTrue(winner == player);
    }

    @Test
    @DisplayName("创建失败（非唯一索引）：返回 null 并允许下次入口重试")
    void creationFailureIsSwallowed() {
        when(characterRepository.findFirstByProjectIdAndControllerAndDeletedOrderByIdAsc(1L, "player", 0))
                .thenReturn(Optional.empty());
        when(playerProfileService.profileOf(9L)).thenReturn(null);
        when(characterRepository.save(any(ActorCharacter.class)))
                .thenThrow(new IllegalStateException("disk full"));

        assertNull(service.ensurePlayerCharacter(1L, 9L));
    }

    @Test
    @DisplayName("playerOf：查不到返回 null；入参 null 不查库")
    void playerOfIsLenient() {
        assertNull(service.playerOf(null));
        when(characterRepository.findFirstByProjectIdAndControllerAndDeletedOrderByIdAsc(1L, "player", 0))
                .thenReturn(Optional.empty());
        assertNull(service.playerOf(1L));
    }

    @Test
    @DisplayName("默认实体：controller=npc、isPlayer=false（旧存档兼容）")
    void defaultControllerIsNpc() {
        ActorCharacter npc = new ActorCharacter();
        assertEquals("npc", npc.getController());
        assertFalse(npc.isPlayer());
    }
}
