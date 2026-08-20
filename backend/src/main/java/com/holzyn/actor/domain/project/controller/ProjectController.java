package com.holzyn.actor.domain.project.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.project.dto.ProjectDTO;
import com.holzyn.actor.domain.world.dto.WorldSettingDTO;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.project.vo.ProjectVO;
import com.holzyn.actor.domain.world.vo.WorldSettingVO;
import com.holzyn.actor.domain.character.service.CharacterCardService;
import com.holzyn.actor.domain.project.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 项目与世界观管理控制器（A-C1）。
 * <p>职责：提供项目（作品）CRUD、世界观设定读取/保存（覆盖更新）、一键生成角色卡等接口。
 * 所有接口统一返回 R&lt;T&gt;，归属以当前登录用户为准（服务层校验）。</p>
 * <p>所属模块：controller/project（项目/世界观子域）</p>
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final CharacterCardService characterCardService;

    /**
     * 项目列表（分页，按更新时间倒序）。
     *
     * @param page 页码（默认 1）
     * @param size 每页条数（默认 12）
     * @return 分页结果（含每个项目角色数）
     */
    @GetMapping
    public R<PageResult<ProjectVO>> list(@RequestParam(name = "page", defaultValue = "1") int page,
                                         @RequestParam(name = "size", defaultValue = "12") int size) {
        return R.ok(projectService.list(page, size));
    }

    /**
     * 创建项目。
     *
     * @param dto 项目入参（name 必填）
     * @return 创建后的项目
     */
    @PostMapping
    public R<ProjectVO> create(@Valid @RequestBody ProjectDTO dto) {
        return R.ok(projectService.create(dto));
    }

    /**
     * 项目详情。
     *
     * @param id 项目主键
     * @return 项目详情
     */
    @GetMapping("/{id}")
    public R<ProjectVO> detail(@PathVariable("id") Long id) {
        return R.ok(projectService.detail(id));
    }

    /**
     * 编辑项目。
     *
     * @param id  项目主键
     * @param dto 项目入参
     * @return 更新后的项目
     */
    @PutMapping("/{id}")
    public R<ProjectVO> update(@PathVariable("id") Long id, @Valid @RequestBody ProjectDTO dto) {
        return R.ok(projectService.update(id, dto));
    }

    /**
     * 删除项目（软删除）。
     *
     * @param id 项目主键
     * @return 删除确认
     */
    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable("id") Long id) {
        projectService.delete(id);
        return R.ok(Map.of("id", id, "deleted", true));
    }

    /**
     * 读取项目最新版本世界观设定。
     *
     * @param id 项目主键
     * @return 世界观（无则 data=null）
     */
    @GetMapping("/{id}/world-setting")
    public R<WorldSettingVO> worldSetting(@PathVariable("id") Long id) {
        return R.ok(projectService.getWorldSetting(id));
    }

    /**
     * 保存世界观设定（覆盖更新保存，直接修改最新记录不产生新版本）。
     *
     * @param id  项目主键
     * @param dto 世界观入参
     * @return 保存后的世界观（当前版本）
     */
    @PostMapping("/{id}/world-setting")
    public R<WorldSettingVO> saveWorldSetting(@PathVariable("id") Long id, @Valid @RequestBody WorldSettingDTO dto) {
        return R.ok(projectService.saveWorldSetting(id, dto));
    }

    /**
     * 一键为项目全部角色生成角色卡（AI，逐角色串行）。
     *
     * @param id 项目主键
     * @return 每个角色的生成结果列表
     */
    @PostMapping("/{id}/generate-cards")
    public R<List<Map<String, Object>>> generateCards(@PathVariable("id") Long id) {
        return R.ok(characterCardService.generateAllCards(id));
    }
}