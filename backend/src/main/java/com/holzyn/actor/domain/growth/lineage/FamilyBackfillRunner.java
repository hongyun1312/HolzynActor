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

import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 存量世界「开局家族与基因建档」补齐启动器（M4）。
 *
 * <p><b>职责</b>：应用启动时（世界已挂载的前提下）对每个有角色的项目跑一次
 * {@link FamilyGenesisService#ensure}，把老存档里"性别年龄全空、谱系表 0 行、基因表 0 行"的世界
 * 补成有家谱的世界。实测样本：world 33 的 12 个角色里 9 个主要角色
 * {@code gender/stage/age/birth_game_second} 全 NULL，{@code actor_growth_lineage} 0 行、
 * {@code actor_growth_genome} 0 行——家族谱系页因此永远只有一个孤零零的节点。</p>
 *
 * <p><b>为什么按项目循环</b>：{@code actor_profile} 住在世界库里，一次只能看到一个挂载世界的行；
 * 启动时通常没有挂载世界（{@code WorldGate} 直接跳过），等用户在 UI 里打开世界后，
 * 手动接口 {@code POST /api/debug/growth/family/backfill} 或下次启动补齐。</p>
 *
 * <p><b>幂等与安全</b>：
 * <ul>
 *   <li>{@code ensure} 内部只填空值、只加缺失的边（{@code marry}/{@code addParentChild} 自带去重），
 *       重复启动零新增；</li>
 *   <li>启动路径<b>不传 userId</b> → 不走 AI 精修（启动时没有登录态，也不该在启动期调模型）；</li>
 *   <li>失败只告警、不阻断启动（与 {@code MarriageWidowBackfillRunner} / {@code LocationBackfillRunner}
 *       同一纪律），下次启动重试。</li>
 * </ul></p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FamilyBackfillRunner implements ApplicationRunner {

    private final FamilyGenesisService familyGenesisService;
    private final ActorCharacterRepository characterRepository;

    /**
     * 世界闸门：启动时通常没有挂载世界，此时 {@code actor_profile} 不在目录库里，
     * 扫描只会报 "Table not found" 白跑一遍。未挂载就跳过（{@code @Autowired(required=false)}
     * 便于纯单测环境直接 new）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.worldstore.WorldGate worldGate;

    /**
     * 启动入口。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        if (worldGate == null) {
            backfillNow();
        } else {
            worldGate.tryMaintenance("FamilyBackfillRunner.run", this::backfillNow);
        }
    }

    /** 扫描全部项目并补齐（世界未挂载时不会进入）。 */
    private void backfillNow() {
        try {
            backfill();
        } catch (Exception e) {
            log.warn("[家族] 启动补齐失败（不影响启动，下次启动重试）: {}", e.getMessage());
        }
    }

    /**
     * 对每个有角色的项目执行一次建档（单个项目失败隔离继续）。
     *
     * @return 处理的项目数
     */
    public int backfill() {
        Set<Long> projectIds = new LinkedHashSet<>();
        try {
            List<ActorCharacter> all = characterRepository.findByDeletedOrderByIdAsc(0);
            for (ActorCharacter c : all) {
                if (c != null && c.getProjectId() != null) {
                    projectIds.add(c.getProjectId());
                }
            }
        } catch (Exception e) {
            log.warn("[家族] 启动补齐-项目枚举失败（跳过）: {}", e.getMessage());
            return 0;
        }
        int done = 0;
        for (Long projectId : projectIds) {
            try {
                FamilyGenesisService.Result r = familyGenesisService.ensure(null, projectId);
                if (r.profilesFixed() > 0 || r.marriages() > 0 || r.parentEdges() > 0 || r.genomes() > 0) {
                    log.info("[家族] 启动补齐：project={} 档案 +{} / 家族 {} 簇 / 婚姻 +{} / 亲子 +{} / 基因 +{}",
                            projectId, r.profilesFixed(), r.families(), r.marriages(), r.parentEdges(), r.genomes());
                }
                done++;
            } catch (Exception e) {
                log.warn("[家族] 启动补齐单个项目失败（隔离继续）: project={} 原因={}", projectId, e.getMessage());
            }
        }
        return done;
    }
}
