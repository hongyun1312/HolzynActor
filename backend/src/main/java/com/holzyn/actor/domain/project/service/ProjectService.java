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

package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.project.dto.ProjectDTO;
import com.holzyn.actor.domain.world.dto.WorldSettingDTO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.knowledge.service.WorldKnowledgeSyncService;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.model.WorldCategory;
import com.holzyn.actor.domain.world.model.WorldTone;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.project.vo.ProjectVO;
import com.holzyn.actor.domain.world.vo.WorldSettingVO;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.worldstore.WorldIndexDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目与世界观业务服务（A-）。
 * <p>职责：提供项目（作品）的增删改查与世界观设定的覆盖更新保存/读取（不产生新版本）。
 * 所有归属均以当前登录用户为准（CurrentUserProvider），越权访问返回 404。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ActorProjectRepository projectRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final ActorCharacterRepository characterRepository;
    private final CurrentUserProvider currentUserProvider;
    /** 世界设定知识同步器（RAG 适配：世界设定保存提交后 → 项目级知识文档幂等同步 + 向量化） */
    private final WorldKnowledgeSyncService worldKnowledgeSyncService;
    /** 世界常识摘要服务（V2.0 段 1A 稳定世界观：世界观保存提交后 → 依最新设定 AI 生成/更新 300-500 字精炼常识） */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldCommonKnowledgeService worldCommonKnowledgeService;

    /**
     * 目录库索引 DAO（{@code actor_project} 展示列的唯一写入口）。
     *
     * <p>为什么字段注入 {@code required=false}：单元测试与"目录库未迁移"场景装配不到它，
     * 此时世界名只落到世界观表，不抛异常。</p>
     *
     * <p>为什么必须 {@code @Lazy}：ProjectService 在会话/引擎依赖链上（世界打开时会回写项目），
     * 而 WorldIndexDao 又挂在目录库数据源上——直接注入会形成 Bean 环，实测启动即报
     * "WorldGate 需要 WorldSessionService，找不到 Bean"（环被 Spring 断在了别处）。</p>
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private WorldIndexDao worldIndexDao;

    /**
     * 世界程度读取入口（{@code @Lazy}：它本身依赖 WorldIndexDao，直接注入会与上面的环叠加）。
     *
     * <p>保存世界观后必须让它的短 TTL 缓存失效，否则宏观调度器最多 2 秒内还按旧档位跑。</p>
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldToneService worldToneService;

    /**
     * 查询当前用户的项目列表（分页，按更新时间倒序）。
     *
     * @param page 页码（从 1 开始）
     *
     * @param size 每页条数
     *
     * @return 分页结果（含每个项目的角色数）
     */
    @Transactional(readOnly = true)
    public PageResult<ProjectVO> list(int page, int size) {
        Long userId = currentUserProvider.currentUserId();
        List<ActorProject> all = projectRepository.findByUserIdAndDeletedOrderByUpdatedAtDesc(userId, 0);
        List<ProjectVO> vos = all.stream().map(p -> ProjectVO.of(p, characterCount(p.getId()))).toList();
        // 内存分页（本地项目数量有限，后续数据量大可改 SQL 分页）
        int total = vos.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        List<ProjectVO> pageList = from >= total ? List.of() : vos.subList(from, to);
        return PageResult.of(pageList, total, page, size);
    }

    /**
     * 创建项目。
     *
     * @param dto 项目入参
     *
     * @return 创建后的项目 VO
     */
    @Transactional
    public ProjectVO create(ProjectDTO dto) {
        ActorProject p = new ActorProject();
        p.setUserId(currentUserProvider.currentUserId());
        apply(dto, p);
        p.setStatus(0);
        return ProjectVO.of(projectRepository.save(p), 0L);
    }

    /**
     * 查询项目详情（归属校验）。
     *
     * @param id 项目主键
     *
     * @return 项目 VO
     */
    @Transactional(readOnly = true)
    public ProjectVO detail(Long id) {
        ActorProject p = requireOwned(id);
        return ProjectVO.of(p, characterCount(p.getId()));
    }

    /**
     * 编辑项目（归属校验）。
     *
     * @param id 项目主键
     *
     * @param dto 项目入参
     *
     * @return 更新后的项目 VO
     */
    @Transactional
    public ProjectVO update(Long id, ProjectDTO dto) {
        ActorProject p = requireOwned(id);
        apply(dto, p);
        return ProjectVO.of(projectRepository.save(p), characterCount(p.getId()));
    }

    /**
     * 删除项目（软删除，归属校验）。
     *
     * @param id 项目主键
     */
    @Transactional
    public void delete(Long id) {
        ActorProject p = requireOwned(id);
        p.setDeleted(1);
        projectRepository.save(p);
    }

    /**
     * 读取项目最新版本世界观设定（归属校验）。
     *
     * @param projectId 项目 ID
     *
     * @return 世界观 VO（无则返回 null）
     */
    @Transactional(readOnly = true)
    public WorldSettingVO getWorldSetting(Long projectId) {
        requireOwned(projectId);
        return worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(WorldSettingVO::of).orElse(null);
    }

    /**
     * 保存世界观设定（版本化：内容有变化才新增版本，保留历史）。
     *
     * @param projectId 项目 ID
     *
     * @param dto 世界观入参
     *
     * @return 保存后的世界观 VO（当前版本）
     */
    @Transactional
    public WorldSettingVO saveWorldSetting(Long projectId, WorldSettingDTO dto) {
        // ：手动/解析/导入保存不携带 worldgen 来源 → 清空来源标记（当前版本不再由 worldgen 生成）
        return saveWorldSetting(projectId, dto, null);
    }

    /**
     * 保存世界观设定。
     *
     * @param projectId 项目 ID
     *
     * @param dto 世界观入参
     *
     * @param worldgenTaskId 来源 worldgen 任务 ID（非 worldgen 保存传 null，会清空标记）
     *
     * @return 保存后的世界观 VO（当前版本）
     */
    @Transactional
    public WorldSettingVO saveWorldSetting(Long projectId, WorldSettingDTO dto, Long worldgenTaskId) {
        requireOwned(projectId);
        // 覆盖更新：直接取最新一条记录更新，不新增版本（用户每次保存看到同一条数据）
        ActorWorldSetting target = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
        if (target == null) {
            // 首次保存：新建版本 1
            target = new ActorWorldSetting();
            target.setProjectId(projectId);
            target.setVersion(1);
        }
        target.setName(dto.name());
        // 世界类别固定八类：非空就必须能归一成那八个标签之一，归不了一律 400。
        // 为什么这里用 require 而不是 normalize：normalize 会把不认识的题材静默丢成 null，
        // 用户以为选上了、实际没落库；require 直接回「只能从这八类里选」，前端就能提示补选。
        // 空值仍然允许（新建向导第一次保存可能还没选类别）。
        String genre = dto.genre() == null ? "" : dto.genre().trim();
        target.setGenre(genre.isEmpty() ? null : WorldCategory.require(genre));
        // 世界程度：空值按 standard 落库（显式写下来，避免"空=未设置"与"空=标准"两种语义混用）
        target.setTone(WorldTone.ofCode(dto.tone()).code());
        target.setEra(dto.era());
        target.setGeography(dto.geography());
        target.setFactions(dto.factions());
        target.setMagicSystem(dto.magicSystem());
        target.setCulture(dto.culture());
        target.setHistory(dto.history());
        target.setFreeText(dto.freeText());
        target.setWorldgenTaskId(worldgenTaskId);
        target.setStatus(1);
        WorldSettingVO saved = WorldSettingVO.of(worldSettingRepository.save(target));
        // 分类/程度的展示快照写目录库（画廊卡片上的「仙侠 · 休闲」）；档位还影响宏观调度与提示词，
        // 缓存必须立刻失效，否则改档后最多 2 秒内仍然按旧档位跑
        try {
            if (worldIndexDao != null) {
                worldIndexDao.updateClassification(projectId, saved.genre(), saved.tone());
            }
            if (worldToneService != null) {
                worldToneService.invalidate(projectId);
            }
        } catch (Exception e) {
            log.warn("[世界观] 同步分类快照失败（不影响保存本身）project={}：{}", projectId, e.toString());
        }
        // RAG 适配：世界设定已变 → 事务提交后把最新设定同步为项目级知识文档（AI 调用在事务外，不持 H2 锁）
        // userId 在事务内取好传入（afterCommit 回调执行时请求上下文可能已清理）
        scheduleWorldKnowledgeSync(currentUserProvider.currentUserId(), projectId);
        return saved;
    }

    /**
     * 保存世界名与世界简介（新建世界向导「世界名与简介」步的落库入口）。
     *
     * <p><b>为什么不能直接 save 项目行</b>：进入某个世界后当前数据源就是世界库，而
     * {@code actor_project} 在世界库里只是**只读镜像**（重开世界时会被目录库覆盖），
     * 直接 {@code projectRepository.save} 会被全局配置写保护拦下并 400。所以这里：
     * 目录库{@code actor_project}（画廊真正读的那份）走 {@link WorldIndexDao#updateMeta}
     * 这个 index 列唯一写入口；世界库只更新 {@code actor_world_setting.name}（详情页标题）。
     * 世界库镜像由关世界时的 mirror sync 自然对齐，不需要在这里写。</p>
     *
     * <p>不含 AI 调用，可以 {@code @Transactional}。</p>
     *
     * @param projectId 项目 ID
     *
     * @param name      世界名（非空）
     *
     * @param summary   世界简介（可空）
     *
     * @return 更新后的项目 VO（name/summary 为本次写入值）
     */
    @Transactional
    public ProjectVO applyWorldMeta(Long projectId, String name, String summary) {
        ActorProject p = requireOwned(projectId);
        if (worldIndexDao != null) {
            worldIndexDao.updateMeta(projectId, name, summary, null, LocalDateTime.now());
        }
        worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId).ifPresent(s -> {
            s.setName(name);
            worldSettingRepository.save(s);
        });
        // 返回新值的 VO 但不改托管实体：改托管实体会在提交时把它 flush 回镜像表
        return new ProjectVO(p.getId(), p.getProjectUid(), p.getUserId(), name, p.getCode(), p.getCoverUrl(),
                summary, p.getStatus(), characterCount(projectId), p.getCreatedAt(), p.getUpdatedAt());
    }

    /**
     * 事务提交后触发世界设定 → 知识文档同步（RAG 适配）+ 世界常识摘要生成（V2.0 段 1A）。
     * <p>saveWorldSetting 为 @Transactional，而向量化/常识生成含 embedding/AI 调用——若在事务内直接调用，
     * H2 行锁持有时间 = 调用耗时，违背「AI 在事务外、写阶段短事务」架构约束；故经
     * {@link TransactionSynchronization#afterCommit} 注册，提交后再执行；无事务上下文（防御）直接执行。</p>
     *
     * @param userId 归属用户 ID（事务内取出，afterCommit 时不再依赖请求上下文）
     *
     * @param projectId 项目 ID
     */
    private void scheduleWorldKnowledgeSync(Long userId, Long projectId) {
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            worldKnowledgeSyncService.syncWorldSetting(userId, projectId);
                        } catch (Exception e) {
                            log.warn("[世界设定同步] 提交后同步失败（可手动补建）: 项目={} : {}", projectId, e.getMessage());
                        }
                        // 世界常识摘要（V2.0 段 1A）：依最新世界观生成/更新 300-500 字精炼常识
                        if (worldCommonKnowledgeService != null) {
                            try {
                                worldCommonKnowledgeService.generate(userId, projectId);
                            } catch (Exception e) {
                                log.warn("[世界常识] 提交后生成失败（注入回退截断拼装）: 项目={} : {}", projectId, e.getMessage());
                            }
                        }
                    }
                });
            } else {
                // 无事务上下文（防御）：直接同步
                worldKnowledgeSyncService.syncWorldSetting(userId, projectId);
                if (worldCommonKnowledgeService != null) {
                    try {
                        worldCommonKnowledgeService.generate(userId, projectId);
                    } catch (Exception e) {
                        log.warn("[世界常识] 无事务直接生成失败（注入回退截断拼装）: 项目={} : {}", projectId, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[世界设定同步] 注册提交后同步失败（跳过，可手动补建）: 项目={} : {}", projectId, e.getMessage());
        }
    }

    /**
     * 统计项目下未删除角色数。
     *
     * @param projectId 项目 ID
     *
     * @return 角色数量
     */
    private long characterCount(Long projectId) {
        return characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0).size();
    }

    /**
     * 按 id + 当前用户归属查询项目（不存在或越权时抛 404）。
     *
     * @param id 项目主键
     *
     * @return 项目实体
     */
    private ActorProject requireOwned(Long id) {
        Long userId = currentUserProvider.currentUserId();
        return projectRepository.findByIdAndUserIdAndDeleted(id, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 将 DTO 字段应用到项目实体（创建/更新共用）。
     *
     * @param dto 项目入参
     *
     * @param p 项目实体
     */
    private void apply(ProjectDTO dto, ActorProject p) {
        p.setName(dto.name());
        p.setCode(dto.code());
        p.setSummary(dto.summary());
        p.setCoverUrl(dto.coverUrl());
    }
}
