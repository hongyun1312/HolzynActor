package com.holzyn.actor.domain.project.vo;

/**
 * 世界初始化工作流结果 VO（2026-08-19 世界初始化）。
 * <p>职责：承载初始化 6 步全部执行完成的汇总结果（失败步骤已跳过并记录日志）。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 *
 * @param projectId    项目 ID
 * @param locations    地点数（本次提取并入库）
 * @param cards        角色卡数（本次新生成）
 * @param npcs         普通型 NPC 数（本次生成并入库）
 * @param relations    关系数（本次写入）
 * @param gameTimeText 设置后的世界历时间文本（如「星历 1050 年 3 月 12 日」）
 * @param vectorized   本次完成向量化的知识文档数
 */
public record WorldInitResultVO(
        Long projectId,
        int locations,
        int cards,
        int npcs,
        int relations,
        String gameTimeText,
        int vectorized
) {
}
