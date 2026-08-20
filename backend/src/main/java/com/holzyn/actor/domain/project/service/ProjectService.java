package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.project.dto.ProjectDTO;
import com.holzyn.actor.domain.world.dto.WorldSettingDTO;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.project.vo.ProjectVO;
import com.holzyn.actor.domain.world.vo.WorldSettingVO;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 项目与世界观业务服务（A-C1）。
 * <p>职责：提供项目（作品）的增删改查与世界观设定的覆盖更新保存/读取（不产生新版本）。
 * 所有归属均以当前登录用户为准（CurrentUserProvider），越权访问返回 404。</p>
 * <p>所属模块：service/project（项目/世界观子域）</p>
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ActorProjectRepository projectRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final ActorCharacterRepository characterRepository;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 查询当前用户的项目列表（分页，按更新时间倒序）。
     *
     * @param page 页码（从 1 开始）
     * @param size 每页条数
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
     * @param id  项目主键
     * @param dto 项目入参
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
     * @param dto       世界观入参
     * @return 保存后的世界观 VO（当前版本）
     */
    @Transactional
    public WorldSettingVO saveWorldSetting(Long projectId, WorldSettingDTO dto) {
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
        target.setGenre(dto.genre());
        target.setEra(dto.era());
        target.setGeography(dto.geography());
        target.setFactions(dto.factions());
        target.setMagicSystem(dto.magicSystem());
        target.setCulture(dto.culture());
        target.setHistory(dto.history());
        target.setFreeText(dto.freeText());
        target.setStatus(1);
        return WorldSettingVO.of(worldSettingRepository.save(target));
    }

    /**
     * 统计项目下未删除角色数。
     *
     * @param projectId 项目 ID
     * @return 角色数量
     */
    private long characterCount(Long projectId) {
        return characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0).size();
    }

    /**
     * 按 id + 当前用户归属查询项目（不存在或越权时抛 404）。
     *
     * @param id 项目主键
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
     * @param p   项目实体
     */
    private void apply(ProjectDTO dto, ActorProject p) {
        p.setName(dto.name());
        p.setCode(dto.code());
        p.setSummary(dto.summary());
        p.setCoverUrl(dto.coverUrl());
    }
}
