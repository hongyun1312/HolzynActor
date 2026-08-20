package com.holzyn.actor.domain.settings.service;

import com.holzyn.actor.domain.settings.entity.ActorPromptTemplate;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PromptTemplateService 逻辑单元测试（P2 阶段三 + V2.0 项目化）。
 * <p>职责：验证内置模板完整性（13 个模板 + 占位符）与「项目覆盖 > 用户覆盖 > 内置」的合并规则。</p>
 */
class PromptTemplateServiceTest {

    /**
     * 内置模板共 22 个且编码齐全（P4-1 起新增 memory_extract / memory_summarize；V2.1 新增 evolution_orchestrator；
     * vP5-7.6 新增 scene_generate 场景 AI 自动生成；vP5-7.9 新增 evolution_schedule 演化逐拍调度；
     * 角色关系拓扑轮新增 relation_gen 角色关系生成；普通人群重构轮新增 crowd_category_gen 职业分类拟定 /
     * ordinary_npc_gen 居民批量生成 / crowd_schedule_project 项目级调度 / crowd_schedule_affiliation 归属级调度；
     * 2026-08-19 新建项目解析重构轮新增 world_segment 世界观总结分段 / world_segment_characters 角色完整分离 /
     * world_time_infer 世界时间推断）。
     */
    @Test
    void builtinsContainsAllTemplates() {
        var builtins = PromptTemplateService.builtins();
        assertEquals(22, builtins.size());
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CARD_GEN));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_DIALOG));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_GROUP));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLD_EVENT));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_ACTION));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_PROJECT_IMPORT));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_PROJECT_IMPORT_CHARACTERS));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_PROJECT_IMPORT_FIELD));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CROWD));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MEMORY_EXTRACT));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MEMORY_SUMMARIZE));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_EVOLUTION));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_SCENE_GENERATE));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_EVOLUTION_SCHEDULE));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_RELATION_GEN));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CROWD_CATEGORY));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CROWD_NPC_GEN));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CROWD_SCHEDULE_PROJECT));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CROWD_SCHEDULE_AFFILIATION));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLD_SEGMENT));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLD_SEGMENT_CHARACTERS));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLD_TIME_INFER));
    }

    /**
     * 内置模板内容含占位符（可由 render 替换）。
     */
    @Test
    void builtinsContainPlaceholders() {
        var builtins = PromptTemplateService.builtins();
        assertTrue(builtins.get(PromptTemplateService.CODE_CARD_GEN).template().contains("{{world_setting}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_GROUP).template().contains("{{members_summary}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_EVENT).template().contains("{{world_setting}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_ACTION).template().contains("{{situation}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_CROWD).template().contains("{{crowd_config}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_CROWD).template().contains("{{hour}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_RELATION_GEN).template().contains("{{world_setting}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_RELATION_GEN).template().contains("{{roster}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_RELATION_GEN).template().contains("{{task_requirement}}"));
    }

    /**
     * 内置模板含系统提示词（2026-08-19：生成类模板的系统提示词随模板落库，代码不再硬编码）。
     */
    @Test
    void builtinsCarrySystemMessage() {
        var builtins = PromptTemplateService.builtins();
        assertEquals("你只输出严格的 JSON 数组，不输出任何其他文字。",
                builtins.get(PromptTemplateService.CODE_CROWD_NPC_GEN).systemMessage());
        assertEquals("你只输出严格的 JSON，不输出任何其他文字。",
                builtins.get(PromptTemplateService.CODE_CROWD_CATEGORY).systemMessage());
        assertEquals("你只输出严格的 JSON，不输出任何其他文字。",
                builtins.get(PromptTemplateService.CODE_CROWD_SCHEDULE_PROJECT).systemMessage());
        assertEquals("你只输出严格的 JSON 数组，不输出任何其他文字。",
                builtins.get(PromptTemplateService.CODE_CROWD_SCHEDULE_AFFILIATION).systemMessage());
        assertEquals("你只输出严格的 JSON，不输出任何其他文字。",
                builtins.get(PromptTemplateService.CODE_RELATION_GEN).systemMessage());
    }

    /**
     * 居民生成模板含性别铁律（2026-08-19：性别只允许 男/女/无性，禁止雄雌与括号补充说明）。
     */
    @Test
    void ordinaryNpcGenContainsGenderRule() {
        String tpl = PromptTemplateService.builtins().get(PromptTemplateService.CODE_CROWD_NPC_GEN).template();
        assertTrue(tpl.contains("男 / 女 / 无性"), "模板应限定性别取值为 男/女/无性");
        assertTrue(tpl.contains("禁止 雄性/雌性"), "模板应禁止 雄性/雌性 表述");
        assertTrue(tpl.contains("禁止在性别后附加括号"), "模板应禁止性别括号补充");
    }

    /**
     * 合并规则：用户覆盖覆盖同编码内置，其余保留内置。
     */
    @Test
    void mergeEffectiveOverrideWins() {
        ActorPromptTemplate builtin = new ActorPromptTemplate();
        builtin.setUserId(0L); builtin.setCode("dialog_system"); builtin.setName("内置对话"); builtin.setTemplate("内置内容");
        ActorPromptTemplate override = new ActorPromptTemplate();
        override.setUserId(5L); override.setCode("dialog_system"); override.setName("用户对话"); override.setTemplate("用户覆盖内容");
        ActorPromptTemplate other = new ActorPromptTemplate();
        other.setUserId(0L); other.setCode("action_gen"); other.setName("行动"); other.setTemplate("内置行动");

        List<ActorPromptTemplate> merged = PromptTemplateService.mergeEffective(List.of(builtin, other), List.of(override), List.of());
        assertEquals(2, merged.size());
        ActorPromptTemplate dialog = merged.stream().filter(t -> "dialog_system".equals(t.getCode())).findFirst().orElseThrow();
        assertEquals("用户覆盖内容", dialog.getTemplate());
        assertTrue(merged.stream().anyMatch(t -> "action_gen".equals(t.getCode())));
    }

    /**
     * 合并规则（V2.0 项目化）：项目级覆盖 > 用户覆盖 > 内置。
     */
    @Test
    void mergeEffectiveProjectOverrideWins() {
        ActorPromptTemplate builtin = new ActorPromptTemplate();
        builtin.setUserId(0L); builtin.setCode("dialog_system"); builtin.setName("内置对话"); builtin.setTemplate("内置内容");
        ActorPromptTemplate userOverride = new ActorPromptTemplate();
        userOverride.setUserId(5L); userOverride.setCode("dialog_system"); userOverride.setName("用户对话"); userOverride.setTemplate("用户覆盖内容");
        ActorPromptTemplate projectOverride = new ActorPromptTemplate();
        projectOverride.setUserId(5L); projectOverride.setProjectId(10L); projectOverride.setCode("dialog_system"); projectOverride.setName("项目对话"); projectOverride.setTemplate("项目覆盖内容");

        List<ActorPromptTemplate> merged = PromptTemplateService.mergeEffective(
                List.of(builtin), List.of(userOverride), List.of(projectOverride));
        assertEquals(1, merged.size());
        assertEquals("项目覆盖内容", merged.get(0).getTemplate());
    }
}