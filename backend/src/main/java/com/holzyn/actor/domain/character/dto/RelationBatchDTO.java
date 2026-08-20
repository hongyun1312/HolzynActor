package com.holzyn.actor.domain.character.dto;

import com.holzyn.actor.domain.character.vo.RelationDraftVO;

import java.util.List;

/**
 * 角色关系批量入库请求（预览确认后的写入；2026-08-19 支持普通型 NPC 范围）。
 *
 * @param mode        重建/补充：rebuild=先清空相关范围再写入；supplement=仅追加
 * @param characterId 单角色范围时的特殊 NPC ID（scope=character 时用于定位清空范围；可空）
 * @param crowdId     单普通型 NPC 范围时的普通 NPC ID（scope=crowd 时用于定位清空范围；可空）
 * @param items       确认后的关系草稿列表
 */
public record RelationBatchDTO(String mode, Long characterId, Long crowdId, List<RelationDraftVO> items) {
}
