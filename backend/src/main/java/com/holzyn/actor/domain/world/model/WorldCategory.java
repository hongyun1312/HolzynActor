/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.world.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 世界类别（固定八类，只能从中选）。
 *
 * <p>为什么要把 {@code genre} 从自由文本改成固定枚举：题材会作为 {@code {{genre}}} 注入到
 * 世界观生成、角色卡、故事规划、知识库同步等十余处提示词里，此前是用户自由填写，
 * 同一个「东方修仙」能被写成「仙侠 / 修仙 / 东方玄幻 / 练气大陆」，AI 每次拿到的题材语义都不稳定；
 * 也导致画廊无法按题材归类、世界之间没法比较。</p>
 *
 * <p>存储口径：<b>库里存中文标签</b>（{@link #label()}），因为提示词里 {@code {{genre}}}
 * 直接拼中文读起来最通顺，历史数据也本来就是中文；{@link #code()} 只用于前端取值与接口契约。</p>
 *
 * <p>所属模块：domain/world（世界设定分类）</p>
 */
public enum WorldCategory {

    /** 奇幻（西幻 / 魔法 / 架空大陆） */
    FANTASY("qihuan", "奇幻"),

    /** 科幻（太空 / 赛博 / 近未来） */
    SCI_FI("kehuan", "科幻"),

    /** 都市（现代 / 校园 / 职场） */
    URBAN("dushi", "都市"),

    /** 历史（古代 / 王朝 / 民国） */
    HISTORY("lishi", "历史"),

    /** 末世（废土 / 灾变 / 生存） */
    POST_APOCALYPTIC("moshi", "末世"),

    /** 仙侠（修仙 / 武侠 / 江湖） */
    XIANXIA("xianxia", "仙侠"),

    /** 日常（治愈 / 田园 / 生活模拟） */
    SLICE_OF_LIFE("richang", "日常"),

    /** 悬疑（推理 / 侦探 / 惊悚） */
    MYSTERY("xuanyi", "悬疑");

    private final String code;

    private final String label;

    WorldCategory(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 稳定编码（前端取值 / 接口契约，不随文案变化） */
    public String code() {
        return code;
    }

    /** 中文标签（落库与提示词使用） */
    public String label() {
        return label;
    }

    /** 全部中文标签（前端下拉与提示词选项清单复用） */
    public static List<String> labels() {
        List<String> out = new ArrayList<>(values().length);
        for (WorldCategory c : values()) {
            out.add(c.label);
        }
        return List.copyOf(out);
    }

    /** 按中文标签查找（严格相等） */
    public static Optional<WorldCategory> ofLabel(String label) {
        if (label == null || label.isBlank()) {
            return Optional.empty();
        }
        String t = label.trim();
        for (WorldCategory c : values()) {
            if (c.label.equals(t)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** 按编码查找 */
    public static Optional<WorldCategory> ofCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String t = code.trim();
        for (WorldCategory c : values()) {
            if (c.code.equalsIgnoreCase(t)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /**
     * 把「任意历史写法」归一成固定标签。
     *
     * <p>历史数据是自由文本（如「仙侠」「治愈系小岛生活模拟 Demo」「东方修仙」），
     * 迁移与文件解析都用同一套关键字映射；映射不到返回空串，由前端提示用户重新选，
     * <b>不要</b>硬塞一个默认分类——猜错比空着更糟（会把仙侠世界当成奇幻去生成）。</p>
     *
     * @param raw 原始题材文本
     *
     * @return 固定标签；无法识别时返回空串
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String t = raw.trim();
        // 已经完全等于某个标签
        for (WorldCategory c : values()) {
            if (c.label.equals(t)) {
                return c.label;
            }
        }
        // 关键字命中顺序：先判特征最强的两类（仙侠 / 末世），再判宽泛的几类
        String[][] rules = {
                {"仙侠", "修仙", "修真", "武侠", "江湖", "宗门", "剑气", "内丹", "灵根"},
                {"末世", "废土", "丧尸", "灾变", "末日", "辐射", "幸存者"},
                {"奇幻", "魔法", "魔幻", "西幻", "幻想", "精灵", "龙与", "深渊", "异世界", "架空大陆"},
                {"科幻", "赛博", "太空", "星际", "机甲", "未来", "人工智能", "飞船", "克隆"},
                {"悬疑", "推理", "侦探", "恐怖", "惊悚", "诡秘", "密室"},
                {"历史", "古代", "王朝", "中世纪", "民国", "战国", "宫廷", "朝堂"},
                {"日常", "治愈", "休闲", "田园", "生活模拟", "慢生活", "小镇", "海岛", "岛屿", "海滨", "咖啡"},
                {"都市", "现代", "校园", "职场", "言情", "青春", "城市", "公寓"},
        };
        WorldCategory[] order = {XIANXIA, POST_APOCALYPTIC, FANTASY, SCI_FI, MYSTERY, HISTORY, SLICE_OF_LIFE, URBAN};
        for (int i = 0; i < rules.length; i++) {
            for (String key : rules[i]) {
                if (t.contains(key)) {
                    return order[i].label;
                }
            }
        }
        return "";
    }

    /**
     * 校验并归一（保存链路用）。
     *
     * @param raw 提交的题材
     *
     * @return 固定标签
     *
     * @throws IllegalArgumentException 不在固定八类内（调用方转 400）
     */
    public static String require(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("世界类别只能是以下八类之一：" + String.join(" / ", labels()));
        }
        return normalized;
    }
}
