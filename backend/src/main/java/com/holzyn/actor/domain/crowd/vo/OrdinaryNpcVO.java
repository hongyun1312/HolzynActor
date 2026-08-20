package com.holzyn.actor.domain.crowd.vo;

import com.holzyn.actor.domain.crowd.entity.ActorOrdinaryNpc;

import java.time.LocalDateTime;

/**
 * 普通型 NPC 视图对象（OrdinaryNpcVO；2026-08-19 分类体系重构）。
 * <p>职责：向前端返回单条普通型 NPC 的完整档案（含状态机字段），供表格渲染/编辑回显。
 * 分类字段（categoryL1/L2）已废弃，改为 种族/次级种族/归属/职业/所在地 等字段。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 *
 * @param id          主键
 * @param projectId   项目 ID
 * @param name        名称
 * @param gender      性别
 * @param race        种族（一级）
 * @param subRace     次级种族（二级）
 * @param age         年龄
 * @param affiliation 归属
 * @param location    当前所在地
 * @param occupation  职业（可空）
 * @param detail      角色详情
 * @param state       当前状态（idle/walk/stop/talk/rest）
 * @param lastAction  最近行动描述
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record OrdinaryNpcVO(
        Long id,
        Long projectId,
        String name,
        String gender,
        String race,
        String subRace,
        Integer age,
        String affiliation,
        String location,
        String occupation,
        String detail,
        String state,
        String lastAction,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * 从实体构造 VO。
     *
     * @param e 普通型 NPC 实体
     * @return VO 对象
     */
    public static OrdinaryNpcVO of(ActorOrdinaryNpc e) {
        return new OrdinaryNpcVO(
                e.getId(), e.getProjectId(), e.getName(), e.getGender(), e.getRace(), e.getSubRace(), e.getAge(),
                e.getAffiliation(), e.getLocation(), e.getOccupation(), e.getDetail(),
                e.getState(), e.getLastAction(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
