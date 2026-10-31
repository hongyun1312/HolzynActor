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

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.genetic.GenomeService;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * 开局家族与基因建档编排（M3–M4）。
 *
 * <p><b>一句话</b>：让每个世界开局就有一张真实的家谱——每个角色有性别年龄与出生时刻，
 * 有父母、可能有配偶与子女，并且都有基因档案（有父母的按孟德尔重组，没父母的按人格反推）。</p>
 *
 * <p><b>四步（顺序不可换）</b>：
 * <ol>
 *   <li>{@link ProfileCompletionService} 补齐 {@code gender/age/birth_game_second/stage}；
 *       不补这一步，婚配判定永远不过（婚龄判定读 age，空值按 0 处理）；</li>
 *   <li>{@link FamilyGraphPlanner} 纯函数算一份边计划（同簇聚类 → 代际分层 → 婚配 → 亲子）；</li>
 *   <li>落库：婚姻边走 {@link LineageService#marry}，亲子边走 {@link LineageService#addParentChild}
 *       （两者都自带"已存在则跳过"，所以本方法天然幂等）；</li>
 *   <li>基因建档：先落边、后建基因——这样"有父母的按父母重组"才拿得到父母。</li>
 * </ol></p>
 *
 * <p><b>为什么不加 {@code @Transactional}</b>：第 1 步里可能调 AI 判性别年龄（{@code ProfileCompletionService}），
 * 而 AI 调用绝不能包在事务里（AGENTS 8.1.1，H2 行锁跨 AI 时长会锁超时并丢掉已付费的结果）。
 * 本方法只做编排，每一步自己走短事务；中途失败只记录、不抛出（世界初始化不允许被单个角色炸掉）。</p>
 *
 * <p><b>刻意不做的事</b>：不写 {@code actor_relation} 的 MARRIAGE 行、不广播婚礼事件、
 * 不推进世界时钟。理由：{@code actor_relation} 的写入口属于关系/婚礼链路
 * （{@code MarriageService.performWedding} 会带 AI 叙事与事件广播，开局跑它会制造大量"AI 编的婚礼"），
 * 而谱系页/生育链路只读 {@code actor_growth_lineage}，所以本批只落谱系边。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FamilyGenesisService {

    /** 每簇每代最大婚配对数的默认口径（与规划器一致，便于日志解释）。 */
    private static final int MAX_PAIRS = FamilyGraphPlanner.MAX_PAIRS_PER_BAND;

    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRelationRepository relationRepository;
    private final LineageService lineageService;
    private final ProfileCompletionService profileCompletionService;
    private final GenomeService genomeService;
    private final GrowthProperties growthProperties;
    private final WorldClockService worldClockService;

    /**
     * 建档结果（同时用于调试接口返回与日志）。
     *
     * @param profilesFixed 补齐成长档案的角色数
     *
     * @param families 识别出的家族簇数
     *
     * @param marriages 本次新写婚姻边数
     *
     * @param parentEdges 本次新写亲子边数
     *
     * @param genomes 本次新写基因档案数
     */
    public record Result(int profilesFixed, int families, int marriages, int parentEdges, int genomes) {

        /** 空结果。 */
        public static final Result EMPTY = new Result(0, 0, 0, 0, 0);
    }

    /**
     * 用世界时钟当前秒数执行一次建档（幂等）。
     *
     * @param userId 归属用户（null → 不做 AI 精修）
     *
     * @param projectId 项目 ID
     *
     * @return 结果（永不 null）
     */
    public Result ensure(Long userId, Long projectId) {
        return ensure(userId, projectId, worldClockService.currentEngineGameSecond(projectId));
    }

    /**
     * 执行一次建档（幂等）。
     *
     * @param userId 归属用户（null → 不做 AI 精修）
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 世界当前秒数（null → 按 0 处理，出生时刻可能为负）
     *
     * @return 结果（永不 null）
     */
    public Result ensure(Long userId, Long projectId, Long gameSecond) {
        if (projectId == null) {
            return Result.EMPTY;
        }
        long gs = gameSecond == null ? 0L : gameSecond;
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        if (chars == null || chars.isEmpty()) {
            return Result.EMPTY;
        }
        // ① 成长档案补齐（可能触发一次 AI，事务外）
        int profilesFixed = 0;
        try {
            profilesFixed = profileCompletionService.complete(userId, projectId, chars, gs);
        } catch (Exception e) {
            log.warn("[家族] 成长档案补齐失败（继续用现有字段）: project={} 原因={}", projectId, e.getMessage());
        }
        if (profilesFixed > 0) {
            chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        }
        // ② 规划家族图
        List<FamilyGraphPlanner.CharSnap> snaps = new ArrayList<>();
        for (ActorCharacter c : chars) {
            if (c == null || c.getId() == null) {
                continue;
            }
            snaps.add(new FamilyGraphPlanner.CharSnap(c.getId(), c.npcRef(), c.getName(), c.getGender(),
                    c.getAge() == null ? 0 : c.getAge(),
                    "DECEASED".equalsIgnoreCase(c.getStage()),
                    c.getImportance() == null ? 1 : c.getImportance()));
        }
        List<FamilyGraphPlanner.RelSnap> rels = new ArrayList<>();
        try {
            for (ActorCharacterRelation r : relationRepository.findByProjectId(projectId)) {
                if (r == null || r.getFromRef() == null || r.getToRef() == null) {
                    continue;
                }
                rels.add(new FamilyGraphPlanner.RelSnap(r.getRelationType(), r.getFromRef(), r.getToRef()));
            }
        } catch (Exception e) {
            log.warn("[家族] 关系快照读取失败（按无关系规划）: project={} 原因={}", projectId, e.getMessage());
        }
        FamilyGraphPlanner.Plan plan;
        try {
            plan = FamilyGraphPlanner.plan(snaps, rels, gs,
                    growthProperties.getAging().getGameSecondsPerYear(),
                    growthProperties.getReproduction().getMarriageMinAge(),
                    projectId);
        } catch (Exception e) {
            log.warn("[家族] 家族图规划失败: project={} 原因={}", projectId, e.getMessage());
            return new Result(profilesFixed, 0, 0, 0, 0);
        }
        // ③ 落边（marry / addParentChild 自带幂等；已婚后不再重复配）
        int marriages = 0;
        for (FamilyGraphPlanner.MarriagePlan m : plan.marriages()) {
            try {
                if (lineageService.isMarried(projectId, m.husbandRef())
                        || lineageService.isMarried(projectId, m.wifeRef())) {
                    continue;
                }
                // 三代内血亲禁婚：规划器已按簇/代隔离，这里再按权威判定复检一次
                if (lineageService.isBloodRelated(projectId, m.husbandRef(), m.wifeRef())) {
                    log.warn("[家族] 跳过血亲婚配：{} × {}（家族 {}）", m.husbandRef(), m.wifeRef(), m.familyKey());
                    continue;
                }
                lineageService.marry(projectId, m.husbandRef(), m.wifeRef(), m.gameSecond());
                marriages++;
            } catch (Exception e) {
                log.warn("[家族] 婚姻边落库失败（跳过）: {} × {} 原因={}", m.husbandRef(), m.wifeRef(), e.getMessage());
            }
        }
        int parentEdges = 0;
        for (FamilyGraphPlanner.ParentPlan p : plan.parentEdges()) {
            try {
                // 只有真的新增了边才计数：改前把「已存在被跳过」也算成新增，
                // 幂等复跑会报「亲子 +3」而库里一行没变，日志骗人
                if (lineageService.addParentChild(projectId, p.parentRef(), p.childRef(), p.relation(), p.gameSecond())) {
                    parentEdges++;
                }
            } catch (Exception e) {
                log.warn("[家族] 亲子边落库失败（跳过）: {} → {} 原因={}", p.parentRef(), p.childRef(), e.getMessage());
            }
        }
        // ④ 基因建档（先有边，后有基因：有父母才能按父母重组）
        int genomes = 0;
        for (ActorCharacter c : chars) {
            try {
                // ensureXxx 对「已有行」会直接回读并返回数据（不是 null），所以计数必须先探一次存在性，
                // 否则幂等复跑永远报「基因 +2」，与库里 0 新增不符
                boolean existed = genomeService.find(projectId, c.npcRef()).isPresent();
                Optional<String> fatherRef = lineageService.fatherOf(projectId, c.npcRef());
                Optional<String> motherRef = lineageService.motherOf(projectId, c.npcRef());
                ActorCharacter father = characterOf(projectId, fatherRef.orElse(null));
                ActorCharacter mother = characterOf(projectId, motherRef.orElse(null));
                Object built = father != null || mother != null
                        ? genomeService.ensureInherited(projectId, c, father, mother, new Random(projectId * 1000L + c.getId()))
                        : genomeService.ensureInferred(projectId, c);
                if (!existed && built != null) {
                    genomes++;
                }
            } catch (Exception e) {
                log.warn("[家族] 基因建档失败（跳过）: {} 原因={}", c == null ? null : c.npcRef(), e.getMessage());
            }
        }
        Result result = new Result(profilesFixed, plan.families().size(), marriages, parentEdges, genomes);
        log.info("[家族] 建档完成：project={}, 档案 +{}, 家族 {} 簇（每簇每代 ≤{} 对）, 婚姻 +{}, 亲子 +{}, 基因 +{}",
                projectId, profilesFixed, plan.families().size(), MAX_PAIRS, marriages, parentEdges, genomes);
        return result;
    }

    /**
     * 按引用取角色（引用非法/不存在返回 null）。
     *
     * @param projectId 项目 ID
     *
     * @param ref 统一身份引用
     *
     * @return 角色或 null
     */
    private ActorCharacter characterOf(Long projectId, String ref) {
        Long id = NpcRefs.idOf(ref);
        if (id == null) {
            return null;
        }
        ActorCharacter c = characterRepository.findById(id).orElse(null);
        if (c == null || c.getProjectId() == null || !c.getProjectId().equals(projectId)) {
            return null;
        }
        return c;
    }
}
