package com.holzyn.actor.domain.project.dto;

import java.util.List;

/**
 * 文件导入建项目相关数据结构（P2 后续新功能）。
 * <p>职责：承载「上传文件 → AI 解析 → 预览确认 → 创建」链路中的项目/世界观/角色三部分数据，
 * 供确认创建 DTO 与预览 VO 复用（解析结果与确认入参同构）。</p>
 * <p>所属模块：model/dto（数据传输层-导入子域）</p>
 */
public final class ProjectImportDTO {

    /** 工具类禁止实例化 */
    private ProjectImportDTO() {
    }

    /**
     * 项目部分：名称与概要。
     *
     * @param name    项目名称（AI 从文件内容或文件名推断）
     * @param summary 项目概要
     */
    public record ProjectPart(String name, String summary) {
    }

    /**
     * 世界观部分：结构化字段 + 完整自由文本。
     *
     * @param name        世界观名称（缺省时服务层回退项目名）
     * @param genre       题材
     * @param era         时代背景
     * @param geography   地理/地图设定
     * @param factions    势力/阵营
     * @param magicSystem 规则体系（魔法/科技/规则）
     * @param culture     文化/风俗
     * @param history     历史背景
     * @param freeText    完整世界观自由文本
     */
    public record WorldPart(String name, String genre, String era, String geography,
                            String factions, String magicSystem, String culture,
                            String history, String freeText) {
    }

    /**
     * 角色档案部分（AI 解析/生成的角色）。
     *
     * @param type          类型：special 特殊型 / common 普通型
     * @param name          角色姓名（必填）
     * @param title         头衔
     * @param detail        角色详细信息（角色卡生成的核心输入源）
     * @param isProtagonist 是否主角：0/1
     * @param importance    重要度（1-5）
     */
    public record CharacterPart(String type, String name, String title, String detail,
                                Integer isProtagonist, Integer importance) {
    }

    /**
     * 地点档案部分（AI 从地理设定提取 / 手动维护的地点）。
     *
     * @param name       地点名称（必填）
     * @param type       地点类型（城市/城镇/酒馆/森林…）
     * @param intro      详细简介
     * @param importance 重要度（1-5）
     */
    public record LocationPart(String name, String type, String intro, Integer importance) {
    }

    /**
     * 确认创建请求：项目 + 世界观 + 角色列表 + 地点列表。
     *
     * @param project      项目部分
     * @param worldSetting 世界观部分
     * @param characters   角色档案列表（可为空）
     * @param locations    地点列表（可为空，随项目一起入库）
     */
    public record Confirm(ProjectPart project, WorldPart worldSetting,
                          List<CharacterPart> characters, List<LocationPart> locations) {
    }

    /**
     * AI 自动生成角色请求。
     *
     * @param projectName        项目名称（可空，用于情境）
     * @param worldSettingFreeText 世界观自由文本（可空，用于情境）
     * @param count              生成数量（默认 5，1-10）
     */
    public record GenerateCharactersRequest(String projectName, String worldSettingFreeText, Integer count) {
    }
}