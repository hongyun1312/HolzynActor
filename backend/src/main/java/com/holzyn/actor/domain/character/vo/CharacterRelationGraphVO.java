package com.holzyn.actor.domain.character.vo;

import java.util.List;

/**
 * 角色关系拓扑图视图对象（CharacterRelationGraphVO）。
 * <p>职责：向前端返回「全角色网络图」所需数据。节点 = ①项目下全部未删除 NPC 角色 +
 * ②项目下全部普通型 NPC（kind=crowd，每个普通 NPC 以具体人名独立成节点）+ ③关系表引用但
 * 两表都不存在的「幽灵角色」（kind=ghost，仅名称，id 兜底存 0）；关系边 = actor_character_relation
 * （端点按 NPC→普通型 NPC→幽灵 顺序解析为唯一 key，自环过滤）。供 AntV G6 v5 力导向布局渲染；
 * 选中角色/幽灵节点时右上角卡片查询详情。</p>
 * <p>字段命名对齐 G6 GraphData：nodes + relations。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 */
public record CharacterRelationGraphVO(
        /** 全部节点（NPC/普通型 NPC/幽灵三类） */
        List<NodeVO> nodes,
        /** 关系边（from→to，带双方解析后的 key 与名称） */
        List<EdgeVO> relations
) {

    /**
     * 图节点（id 即 G6 节点 id，全局唯一）。
     *
     * @param id            唯一 key：npc-&lt;角色id&gt; / crowd-&lt;普通型NPC id&gt; / ghost-&lt;角色名&gt;
     * @param name          显示名（NPC=角色名；普通型=普通 NPC 姓名；幽灵=关系表引用名）
     * @param kind          节点类型：npc / crowd / ghost（前端区分样式）
     * @param type          NPC 类型（special/common）；普通型/幽灵为 null
     * @param importance    重要度 1~5（NPC 有；其余 null）
     * @param isProtagonist 是否主角（NPC：1=是；其余 null）
     * @param title         NPC 头衔（可空）
     * @param detail        NPC 详细信息 / 普通型 NPC 角色详情（展示用，后端截断防止大 payload）
     * @param crowdName     普通型 NPC 归属（势力/组织/村落，kind=crowd 有）
     * @param occupation    普通型 NPC 职业（可空）
     * @param state         普通型 NPC 当前状态（idle/walk/…；可空）
     * @param lastAction    普通型 NPC 最近行动描述（可空）
     */
    public record NodeVO(
            String id,
            String name,
            String kind,
            String type,
            Integer importance,
            Integer isProtagonist,
            String title,
            String detail,
            String crowdName,
            String occupation,
            String state,
            String lastAction
    ) {
    }

    /**
     * 关系边。
     *
     * @param id           关系主键（G6 边 id）
     * @param fromKey      发起方解析后的节点 key（npc-/crowd-/ghost-）
     * @param toKey        目标方解析后的节点 key
     * @param fromName     发起方角色名
     * @param toName       目标方角色名
     * @param relationType 关系类型（决定边配色）
     * @param description  关系描述（悬浮提示展示）
     */
    public record EdgeVO(
            Long id,
            String fromKey,
            String toKey,
            String fromName,
            String toName,
            String relationType,
            String description
    ) {
    }
}
