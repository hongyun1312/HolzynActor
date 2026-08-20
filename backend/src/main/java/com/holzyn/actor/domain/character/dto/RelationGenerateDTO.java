package com.holzyn.actor.domain.character.dto;

/**
 * 角色关系 AI 生成请求（生成预览用，不落库；2026-08-19 支持普通型 NPC 范围）。
 *
 * @param scope       生成范围：character=单特殊 NPC（角色页拓扑 Tab）；crowd=单普通型 NPC（普通人群页）；
 *                    project=全项目（全局拓扑页）
 * @param characterId 单角色范围时的角色 ID（scope=character 必填）
 * @param crowdId     单普通型 NPC 范围时的普通 NPC ID（scope=crowd 必填）
 * @param mode        已有数据时的处理方式：rebuild=重建（清空相关范围后重新生成）；supplement=补充（追加）
 */
public record RelationGenerateDTO(String scope, Long characterId, Long crowdId, String mode) {
}
