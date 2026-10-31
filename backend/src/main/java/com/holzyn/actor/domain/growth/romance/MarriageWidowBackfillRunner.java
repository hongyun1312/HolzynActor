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

package com.holzyn.actor.domain.growth.romance;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.growth.lineage.LineageService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 存量丧偶婚姻终止启动器（治理批次 0 · 卡5，domain/growth/romance）。
 * <p>职责：应用启动时对存量「配偶已故但婚姻边仍 active=1」的行一次性兜底终止（净修正 5：
 * executeDeath 此前不调 {@code terminateMarriage}（零调用方）→ 存量丧偶者（如 雷大壮→孙二娘）
 * 终身不能再婚）。行内容：① 谱系婚姻边 active=0（历史保留）；② 亡偶 MARRIAGE 关系行终止。</p>
 * <p>判定用 {@link LineageService#hasActiveMarriageEdge}（<b>不过滤配偶在世</b>——
 * spouseOf/isMarried 已带 alive 读侧过滤，亡偶边在它们眼中「不存在」，必须查原始边）。</p>
 * <p>幂等：边已终止/角色在世跳过，重复启动为空操作；只终止不新建，绝不动在世者婚姻。</p>
 * <p>实现对齐 {@code LocationBackfillRunner} 思路：ApplicationRunner + 失败不阻断启动
 * （异常仅告警放行，下次启动重试）。数量级（项目角色总数）小，启动开销可忽略。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarriageWidowBackfillRunner implements ApplicationRunner {

    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final LineageService lineageService;

    /**
     * 世界闸门（V1.1.2-beta.1 M4）：启动时通常没有挂载世界，此时 {@code actor_profile} /
     * {@code actor_character_relation} 都不在目录库里，扫描只会报 "Table not found" 并白跑一遍。
     * 未挂载就跳过（M3 迁移已把存量婚姻边原样搬进世界库，不会漏修）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.worldstore.WorldGate worldGate;

    /**
     * 启动时执行存量丧偶婚姻终止（幂等）。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        if (worldGate == null) {
            backfillNow();
        } else {
            worldGate.tryMaintenance("MarriageWidowBackfillRunner.run", this::backfillNow);
        }
    }

    /** 补行实体（世界未挂载时不会进入） */
    private void backfillNow() {
        try {
            int n = backfill();
            log.info("[谱系] 启动丧偶婚姻终止补行完成：{} 条存量婚姻边已终止", n);
        } catch (Exception e) {
            // 行失败不阻断启动（幂等，下次启动重试；仅记录）
            log.warn("[谱系] 启动丧偶婚姻终止补行失败（不影响启动，下次启动重试）: {}", e.getMessage());
        }
    }

    /**
     * 遍历全部角色：已故且有存续婚姻边 → 终止（幂等；单条失败隔离继续）。
     *
     * @return 本次终止的婚姻数
     */
    @Transactional
    public int backfill() {
        int n = 0;
        for (ActorCharacter c : characterRepository.findByDeletedOrderByIdAsc(0)) {
            if (c == null || c.getProjectId() == null || c.getId() == null
                    || !"DECEASED".equalsIgnoreCase(c.getStage())
                    || !lineageService.hasActiveMarriageEdge(c.getProjectId(), NpcRefs.of(c.getId()))) {
                continue;
            }
            try {
                lineageService.terminateMarriage(c.getProjectId(), NpcRefs.of(c.getId()), "widow");
                List<ActorCharacterRelation> marriageRows = relationRepository.findByProjectId(c.getProjectId()).stream()
                        .filter(r -> r != null && "MARRIAGE".equals(r.getRelationType())
                                && (c.getId().equals(r.getFromCharacterId())
                                || c.getId().equals(r.getToCharacterId())))
                        .toList();
                if (!marriageRows.isEmpty()) {
                    relationRepository.deleteAll(marriageRows);
                }
                n++;
                log.info("[谱系] 存量丧偶婚姻终止：ref={}（{}）", NpcRefs.of(c.getId()), c.getName());
            } catch (Exception e) {
                log.warn("[谱系] 单条补行失败（隔离继续）: ref={} : {}", NpcRefs.of(c.getId()), e.getMessage());
            }
        }
        return n;
    }
}
