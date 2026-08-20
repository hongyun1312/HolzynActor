package com.holzyn.actor.domain.character.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.character.dto.CharacterDTO;
import com.holzyn.actor.domain.character.dto.RelationBatchDTO;
import com.holzyn.actor.domain.character.dto.RelationGenerateDTO;
import com.holzyn.actor.domain.character.vo.CharacterCardVO;
import com.holzyn.actor.domain.character.vo.CharacterRelationGraphVO;
import com.holzyn.actor.domain.character.vo.CharacterVO;
import com.holzyn.actor.domain.character.vo.RelationDraftVO;
import com.holzyn.actor.domain.character.service.CharacterCardService;
import com.holzyn.actor.domain.character.service.CharacterRelationService;
import com.holzyn.actor.domain.character.service.CharacterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 角色库与角色卡控制器（A-C2 / A-C3）。
 * <p>职责：提供角色 CRUD、角色卡生成/最新版本/版本历史/手动编辑、角色关系拓扑图/
 * 关系 AI 生成预览/批量入库等接口。统一返回 R&lt;T&gt;，角色归属经 角色→项目→用户 两级校验。</p>
 * <p>所属模块：controller/character（角色子域）</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CharacterController {

    private final CharacterService characterService;
    private final CharacterCardService characterCardService;
    private final CharacterRelationService characterRelationService;

    /**
     * 项目角色列表。
     *
     * @param projectId 项目 ID
     * @return 角色列表（含是否已生成角色卡标记）
     */
    @GetMapping("/projects/{projectId}/characters")
    public R<List<CharacterVO>> list(@PathVariable("projectId") Long projectId) {
        return R.ok(characterService.list(projectId));
    }

    /**
     * 项目「角色关系拓扑图」数据（角色页「关系拓扑」Tab + 全局拓扑页数据源）。
     * <p>返回全角色网络图所需结构：nodes=NPC + 普通人群成员 + 幽灵角色三类节点，
     * relations=项目下全部关系（端点已解析为节点 key，自环过滤）。</p>
     *
     * @param projectId 项目 ID
     * @return 拓扑图 VO（nodes + relations）
     */
    @GetMapping("/projects/{projectId}/character-relations")
    public R<CharacterRelationGraphVO> relationsGraph(@PathVariable("projectId") Long projectId) {
        return R.ok(characterService.relationsGraph(projectId));
    }

    /**
     * AI 生成角色关系预览（不落库；2026-08-19 支持普通型 NPC 范围）。
     * <p>scope=character 生成「当前选中特殊 NPC」的关系；scope=crowd 生成「当前选中普通型 NPC」的关系；
     * scope=project 生成全项目角色关系网络。
     * AI 读取项目世界观 + 现有角色/普通人群名单（+单角色详细信息），返回识别并清洗后的关系草稿，
     * 供前端预览确认后再调用批量入库接口写入。</p>
     *
     * @param projectId 项目 ID
     * @param dto       生成入参：{scope: character|crowd|project, characterId?, crowdId?, mode: rebuild|supplement}
     * @return 关系草稿列表（RelationDraftVO）
     */
    @PostMapping("/projects/{projectId}/character-relations/generate")
    public R<List<RelationDraftVO>> generateRelations(@PathVariable("projectId") Long projectId,
                                                      @RequestBody(required = false) RelationGenerateDTO dto) {
        return R.ok(characterRelationService.generate(projectId, dto == null ? new RelationGenerateDTO("character", null, null, "supplement") : dto));
    }

    /**
     * 关系批量入库（预览确认后写入）。
     * <p>mode=rebuild 先清空相关范围（crowdId 非空=该普通 NPC 相关关系；characterId 非空=该特殊角色相关关系；
     * 否则整个项目关系表）再写入；
     * 写入时端点命中已存在 NPC 存 id+名，否则名称兜底（id=0 + from_name/to_name）。</p>
     *
     * @param projectId 项目 ID
     * @param dto       入库入参：{mode: rebuild|supplement, characterId?, crowdId?, items: [{from,to,relationType,description}]}
     * @return {added 实际写入条数, total 提交条数}
     */
    @PostMapping("/projects/{projectId}/character-relations/batch")
    public R<Map<String, Object>> batchSaveRelations(@PathVariable("projectId") Long projectId,
                                                     @RequestBody(required = false) RelationBatchDTO dto) {
        return R.ok(characterRelationService.batchSave(projectId,
                dto == null ? new RelationBatchDTO("supplement", null, null, List.of()) : dto));
    }

    /**
     * 新增角色。
     *
     * @param projectId 项目 ID
     * @param dto       角色入参
     * @return 创建后的角色
     */
    @PostMapping("/projects/{projectId}/characters")
    public R<CharacterVO> create(@PathVariable("projectId") Long projectId, @Valid @RequestBody CharacterDTO dto) {
        return R.ok(characterService.create(projectId, dto));
    }

    /**
     * 角色详情。
     *
     * @param id 角色主键
     * @return 角色详情
     */
    @GetMapping("/characters/{id}")
    public R<CharacterVO> detail(@PathVariable("id") Long id) {
        return R.ok(characterService.detail(id));
    }

    /**
     * 编辑角色。
     *
     * @param id  角色主键
     * @param dto 角色入参
     * @return 更新后的角色
     */
    @PutMapping("/characters/{id}")
    public R<CharacterVO> update(@PathVariable("id") Long id, @Valid @RequestBody CharacterDTO dto) {
        return R.ok(characterService.update(id, dto));
    }

    /**
     * 删除角色（软删除）。
     *
     * @param id 角色主键
     * @return 删除确认
     */
    @DeleteMapping("/characters/{id}")
    public R<Map<String, Object>> delete(@PathVariable("id") Long id) {
        characterService.delete(id);
        return R.ok(Map.of("id", id, "deleted", true));
    }

    /**
     * 为单个角色生成（或重新生成）角色卡（AI 结构化输出）。
     *
     * @param id 角色主键
     * @return 新版本角色卡
     */
    @PostMapping("/characters/{id}/generate-card")
    public R<CharacterCardVO> generateCard(@PathVariable("id") Long id) {
        return R.ok(characterCardService.generateCard(id));
    }

    /**
     * 角色卡最新版本。
     *
     * @param id 角色主键
     * @return 最新版本角色卡（无则 data=null）
     */
    @GetMapping("/characters/{id}/card")
    public R<CharacterCardVO> card(@PathVariable("id") Long id) {
        // 归属校验在生成/编辑接口完成；此处直接按角色取最新版本
        characterService.detail(id);
        return R.ok(characterCardService.latestCard(id).map(CharacterCardVO::of).orElse(null));
    }

    /**
     * 角色卡版本历史。
     *
     * @param id 角色主键
     * @return 版本列表（升序）
     */
    @GetMapping("/characters/{id}/card/versions")
    public R<List<CharacterCardVO>> cardVersions(@PathVariable("id") Long id) {
        characterService.detail(id);
        return R.ok(characterCardService.versionHistory(id).stream().map(CharacterCardVO::of).toList());
    }

    /**
     * 手动编辑角色卡（保存为新版本，source=edited）。
     *
     * @param id   角色主键
     * @param body 编辑入参：{personaJson, systemPrompt}
     * @return 编辑后的角色卡
     */
    @PutMapping("/characters/{id}/card")
    public R<CharacterCardVO> editCard(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        return R.ok(characterCardService.editCard(id, body));
    }
}