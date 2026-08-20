package com.holzyn.actor.domain.crowd.vo;

import java.util.List;
import java.util.Map;

/**
 * 标准字段数据 + 主/次分类字段的 AI 拟定预览（2026-08-19 分类体系重构）。
 * <p>职责：承载「AI 依据世界观一次性拟定全部字段字典 + 选出主/次分类字段」的结果（不落库），
 * 供前端预览确认后整体保存。</p>
 * <p>所属模块：model/vo（视图对象层）</p>
 *
 * @param primaryField   主分类字段（AI 选出：race/affiliation/occupation）
 * @param secondaryField 次分类字段（与主字段不同）
 * @param fields         字段字典：{ race: [...], affiliation: [...], occupation: [...] }
 */
public record FieldDictPreviewVO(
        String primaryField,
        String secondaryField,
        Map<String, List<FieldDictVO>> fields
) {
}
