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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.model.WorldCategory;
import com.holzyn.actor.domain.world.model.WorldTone;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界分类与程度的统一读取入口。
 *
 * <p>为什么单独一个服务：宏观调度器（每个游戏分钟被 world engine 调一次）、
 * 提示词注入器（每次 AI 调用）、初始化流程（每步执行）三处都要问「这个世界的程度是什么」，
 * 各写一份 {@code worldSettingRepository.findTopBy...} 既重复又容易口径不一。
 * 这里的读取带一层短 TTL 缓存——宏观 tick 是热路径，不能每次都查库；
 * 用户改了档位后最多 2 秒生效，足够。</p>
 *
 * <p>未挂载世界 / 没有设定时一律回落到 {@link WorldTone#STANDARD}（= 改造前的行为），
 * 读库异常也只 warn——判定世界尺度不能因为一次查询失败就把世界变空。</p>
 *
 * <p>所属模块：domain/world（世界设定分类）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldToneService {

    /** 缓存有效期（毫秒）：够挡掉宏观 tick 的高频读，又不会让改档位迟迟不生效 */
    private static final long CACHE_TTL_MS = 2000L;

    /** 世界设定（世界库） */
    private final ActorWorldSettingRepository worldSettingRepository;

    /** 可选：目录库索引（用于「世界库里还没写设定」时回落到目录库快照） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.worldstore.WorldIndexDao worldIndexDao;

    /** projectId → (tone, 过期时间) */
    private final Map<Long, CachedTone> cache = new ConcurrentHashMap<>();

    private record CachedTone(WorldTone tone, String category, long expireAt) {
        boolean fresh() {
            return System.currentTimeMillis() < expireAt;
        }
    }

    /**
     * 世界程度（缺省/非法/读取失败均为 {@link WorldTone#STANDARD}）。
     *
     * @param projectId 项目 ID
     *
     * @return 世界程度（永不为 null）
     */
    public WorldTone tone(Long projectId) {
        return resolve(projectId).tone();
    }

    /**
     * 世界类别（缺省为空串，表示「还没选过」，前端据此提示补选）。
     *
     * @param projectId 项目 ID
     *
     * @return 固定类别标签，或空串
     */
    public String category(Long projectId) {
        return resolve(projectId).category();
    }

    /**
     * 提示词用的「世界程度边界」段。
     *
     * <p><b>标准档返回空串</b>：标准 = 本轮改造前的行为，提示词必须逐字节不变，
     * 否则等于给所有历史世界的判定/叙事偷偷换了输入（零回归的前提）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 中文边界段落（标准档为空串）
     */
    public String boundary(Long projectId) {
        WorldTone t = tone(projectId);
        if (t == WorldTone.STANDARD) {
            return "";
        }
        String cat = category(projectId);
        String catLine = cat == null || cat.isBlank() ? "" : "世界类别：" + cat + "。";
        return catLine + t.boundary();
    }

    /**
     * 宏观层是否允许调度（休闲档为 false：整个宏观引擎不跑）。
     *
     * @param projectId 项目 ID
     *
     * @return true=可以调度
     */
    public boolean allowMacro(Long projectId) {
        return !tone(projectId).macroDisabled();
    }

    /**
     * 势力/战争链是否允许调度（休闲与轻度档为 false）。
     *
     * @param projectId 项目 ID
     *
     * @return true=可以跑 tickFaction
     */
    public boolean allowFactionEngine(Long projectId) {
        return !tone(projectId).factionDisabled();
    }

    /**
     * 档位变化后立刻失效缓存（保存世界观、改档位时调用）。
     *
     * @param projectId 项目 ID
     */
    public void invalidate(Long projectId) {
        if (projectId != null) {
            cache.remove(projectId);
        }
    }

    private CachedTone resolve(Long projectId) {
        if (projectId == null) {
            return new CachedTone(WorldTone.STANDARD, "", 0L);
        }
        CachedTone cached = cache.get(projectId);
        if (cached != null && cached.fresh()) {
            return cached;
        }
        CachedTone loaded = load(projectId);
        cache.put(projectId, loaded);
        return loaded;
    }

    private CachedTone load(Long projectId) {
        long expireAt = System.currentTimeMillis() + CACHE_TTL_MS;
        try {
            ActorWorldSetting ws = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                    .orElse(null);
            if (ws != null) {
                WorldTone tone = WorldTone.ofCode(ws.getTone());
                String category = WorldCategory.normalize(ws.getGenre());
                return new CachedTone(tone, category, expireAt);
            }
        } catch (Exception e) {
            // 世界库未挂载 / 迁移未跑：不能因为读不到设定就把世界判成休闲，
            // 但也**不能就此放弃**——目录库快照里还有一份（下面回落），否则"世界没挂载时
            // 休闲档的宏观步会显示成待执行"，用户打开世界才会变成已跳过。
            log.debug("[世界程度] 读取世界设定失败，回落目录库快照 project={}：{}", projectId, e.toString());
        }
        // 世界库里还没有设定（新建世界的第一步）：回落到目录库快照，仍没有再按标准档
        try {
            if (worldIndexDao != null) {
                java.util.Optional<com.holzyn.actor.domain.worldstore.WorldIndexDao.WorldIndexRow> rowOpt =
                        worldIndexDao.readById(projectId);
                if (rowOpt != null && rowOpt.isPresent()) {
                    com.holzyn.actor.domain.worldstore.WorldIndexDao.WorldIndexRow row = rowOpt.get();
                    return new CachedTone(WorldTone.ofCode(row.tone()),
                            WorldCategory.normalize(row.genre()), expireAt);
                }
            }
        } catch (Exception e) {
            log.debug("[世界程度] 读取目录库分类失败 project={}：{}", projectId, e.toString());
        }
        return new CachedTone(WorldTone.STANDARD, "", expireAt);
    }
}
