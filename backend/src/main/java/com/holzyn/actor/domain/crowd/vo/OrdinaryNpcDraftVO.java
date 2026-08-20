package com.holzyn.actor.domain.crowd.vo;

/**
 * 普通型 NPC 生成草稿视图（AI 生成预览用；2026-08-19 分类体系重构）。
 * <p>职责：承载 AI 批量生成的单条草稿（不入库），供 SSE 逐条推送/前端预览勾选/批量入库。
 * 字段：名称/性别/种族/次级种族/年龄/归属/当前所在地/职业/角色详情（不含关系——关系在 NPC
 * 生成后单独生成，复用关系拓扑结构写入 actor_character_relation）。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 *
 * @param name        名称
 * @param gender      性别
 * @param race        种族（一级）
 * @param subRace     次级种族（二级）
 * @param age         年龄
 * @param affiliation 归属
 * @param location    当前所在地
 * @param occupation  职业
 * @param detail      角色详情
 */
public record OrdinaryNpcDraftVO(
        String name,
        String gender,
        String race,
        String subRace,
        Integer age,
        String affiliation,
        String location,
        String occupation,
        String detail
) {
}
