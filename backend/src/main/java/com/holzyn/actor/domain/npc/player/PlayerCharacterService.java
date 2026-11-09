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

import com.holzyn.actor.domain.dialogue.service.PlayerProfileService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.world.config.WorldLoopProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 玩家角色同构服务（§4.4.3）。
 *
 * <p><b>为什么玩家必须是 {@code actor_profile} 的一行</b>：NPC 的关系、记忆、事件与在场判定
 * 全部以 {@code npc:{id}} 为主体；玩家不在表里就永远收不到「有人靠近你」，所有「他信你/他恨你」
 * 只能走对谈侧链（§6.3）。本服务只负责<b>幂等插入</b>，不复制任何玩家专用读写路径。</p>
 *
 * <p><b>引用前缀</b>：沿用 {@code npc:{id}}，不新增 {@code player:}——通过
 * {@code controller='player'} 区分。</p>
 *
 * <p><b>开关</b>：{@code holzyn.actor.world-loop.enabled=false} 时直接返回 null，
 * 保证关闭态与基线逐字节一致。</p>
 */
@Slf4j
@Service
public class PlayerCharacterService {

    /** 控制方：玩家 */
    public static final String CONTROLLER_PLAYER = "player";

    /** 玩家默认名（档案昵称为空时兜底；设计文档 Q1 决议） */
    public static final String DEFAULT_PLAYER_NAME = "旅行者";

    /** 姓名列宽（actor_profile.name varchar(50)） */
    private static final int NAME_MAX = 50;

    private final ActorCharacterRepository characterRepository;

    private final PlayerProfileService playerProfileService;

    private final WorldLoopProperties properties;

    public PlayerCharacterService(ActorCharacterRepository characterRepository,
                                  PlayerProfileService playerProfileService,
                                  WorldLoopProperties properties) {
        this.characterRepository = characterRepository;
        this.playerProfileService = playerProfileService;
        this.properties = properties;
    }

    /**
     * 幂等确保当前项目的玩家角色存在（不存在则创建）。
     *
     * <p>调用点：世界初始化末尾（{@code WorldInitService.runInit}）与单聊入口兜底
     * （{@code RoleplayService.generateSingleReply}）。重复调用不会产生第二行；
     * 并发插入由 {@code ux_profile_player(project_id, player_user_id)} 唯一索引兜底。</p>
     *
     * @param projectId 项目/世界 ID
     *
     * @param userId    玩家账号 ID（写入 {@code player_user_id}，对齐 actor_project.user_id）
     *
     * @return 玩家角色行；开关关闭/入参缺失/创建失败时 null
     */
    public ActorCharacter ensurePlayerCharacter(Long projectId, Long userId) {
        if (properties == null || !properties.isEnabled() || projectId == null || userId == null) {
            return null;
        }
        Optional<ActorCharacter> existing = characterRepository
                .findFirstByProjectIdAndControllerAndDeletedOrderByIdAsc(projectId, CONTROLLER_PLAYER, 0);
        if (existing.isPresent()) {
            return existing.get();
        }
        ActorCharacter player = new ActorCharacter();
        player.setProjectId(projectId);
        player.setType("special");
        player.setController(CONTROLLER_PLAYER);
        player.setPlayerUserId(String.valueOf(userId));
        player.setName(playerName(userId));
        // 玩家固定重要度 5（设计文档 Q1）；不置 is_protagonist（保持「世界内主角 NPC」原语义）
        player.setImportance(5);
        player.setStatus(1);
        player.setDeleted(0);
        player.setIsProtagonist(0);
        try {
            ActorCharacter saved = characterRepository.save(player);
            log.info("[玩家同构] 已创建玩家角色：project={} userId={} ref={} name={}",
                    projectId, userId, saved.npcRef(), saved.getName());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 并发创建：唯一索引已挡住第二次插入，回查返回先创建的那一行
            Optional<ActorCharacter> raced = characterRepository
                    .findFirstByProjectIdAndControllerAndDeletedOrderByIdAsc(projectId, CONTROLLER_PLAYER, 0);
            if (raced.isPresent()) {
                return raced.get();
            }
            log.warn("[玩家同构] 玩家角色创建失败（唯一索引冲突且回查为空）：project={} userId={} : {}",
                    projectId, userId, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            // 初始化/对话不能因为玩家行创建失败而停摆（玩家可在下次入口重试）
            log.warn("[玩家同构] 玩家角色创建失败（跳过，下次入口重试）：project={} userId={} : {}",
                    projectId, userId, e.getMessage());
            return null;
        }
    }

    /**
     * 查询项目玩家角色（不存在返回 null；不触发创建）。
     *
     * @param projectId 项目/世界 ID
     *
     * @return 玩家角色行或 null
     */
    public ActorCharacter playerOf(Long projectId) {
        if (projectId == null) {
            return null;
        }
        return characterRepository
                .findFirstByProjectIdAndControllerAndDeletedOrderByIdAsc(projectId, CONTROLLER_PLAYER, 0)
                .orElse(null);
    }

    /**
     * 玩家名：档案昵称优先，空则「旅行者」（设计文档 Q1）。
     *
     * @param userId 玩家账号 ID
     *
     * @return 1~50 字玩家名（恒非空）
     */
    private String playerName(Long userId) {
        String name = null;
        try {
            if (playerProfileService != null) {
                var profile = playerProfileService.profileOf(userId);
                if (profile != null && profile.getName() != null && !profile.getName().isBlank()) {
                    name = profile.getName().trim();
                }
            }
        } catch (Exception e) {
            log.debug("[玩家同构] 玩家档案昵称读取失败，回退默认名：userId={} : {}", userId, e.getMessage());
        }
        if (name == null || name.isBlank()) {
            name = DEFAULT_PLAYER_NAME;
        }
        return name.length() > NAME_MAX ? name.substring(0, NAME_MAX) : name;
    }
}
