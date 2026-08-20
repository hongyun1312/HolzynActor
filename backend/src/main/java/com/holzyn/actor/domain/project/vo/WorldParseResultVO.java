package com.holzyn.actor.domain.project.vo;

import java.util.Map;

/**
 * 新建项目「文件解析工作流」结果 VO（2026-08-19 新建项目解析重构）。
 * <p>职责：承载解析工作流全部落库完成后的汇总结果（项目已创建，可直接跳转项目空间/初始化页）。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 *
 * @param projectId        新建项目 ID
 * @param projectName      项目名称
 * @param worldName        世界观名称
 * @param segmentChars     各分段字数（地理/势力/规则/文化/历史/补充）
 * @param characterCount   已创建角色数
 * @param knowledgeDocCount 知识库文档数（原始文件全文，暂未向量化）
 */
public record WorldParseResultVO(
        Long projectId,
        String projectName,
        String worldName,
        Map<String, Integer> segmentChars,
        int characterCount,
        int knowledgeDocCount
) {
}
