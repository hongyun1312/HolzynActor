/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.mapgen.model;

import java.util.Locale;

/**
 * 资产/设施的<b>通用语义键</b>——同一个概念在不同环节必须判成同一个键。
 *
 * <p>为什么必须是同一份实现：S2 拿它判「AI 给的资产里有没有这个设施」，
 * S4 拿它判「这栋楼该盖成哪一类」。如果两边各写一套关键词表，一定会出现
 * 「S2 认为灯塔已经有了、S4 却找不到灯塔」这种死结（实测：灯塔、议事堂、水井
 * 在图上全靠随机落位，锚点完全失效）。</p>
 *
 * <p><b>铁律：只允许通用语义词</b>（灯/塔、码头/渡口、集市、堂、学、井、磨、仓、船屋、盐、铺、坊）。
 * 这里出现的任何词都必须是「换任何世界观都成立」的概念，不许写具体地名或角色名。</p>
 */
public final class AssetSemantics {

    private AssetSemantics() {
    }

    /**
     * 由编码 + 名字 + 类型抽通用语义键。
     *
     * @param code 英文编码（可为 null）
     * @param name 世界观里的叫法（可为 null）
     * @param kind 几何/类型提示（可为 null）
     * @return 语义键；识别不出返回空串
     */
    public static String key(String code, String name, String kind) {
        String s = (nvl(code) + " " + nvl(name) + " " + nvl(kind)).toLowerCase(Locale.ROOT);
        if (has(s, "lighthouse", "beacon", "lighttower", "灯塔", "灯楼", "望楼")) {
            return "lighthouse";
        }
        if (has(s, "pier", "dock", "ferry", "quay", "wharf", "码头", "渡口", "埠头")) {
            return "pier";
        }
        if (has(s, "market", "bazaar", "square", "集市", "市集", "广场")) {
            return "market";
        }
        if (has(s, "council", "hall", "议事", "会堂", "公堂")) {
            return "hall";
        }
        if (has(s, "school", "academy", "学堂", "私塾", "书院")) {
            return "school";
        }
        if (has(s, "well", "水井", "井台", "井亭")) {
            return "well";
        }
        if (has(s, "mill", "磨坊", "磨房")) {
            return "mill";
        }
        if (has(s, "granary", "silo", "仓廪", "粮仓", "谷仓")) {
            return "granary";
        }
        if (has(s, "boathouse", "boat_house", "船屋", "船寮")) {
            return "boathouse";
        }
        if (has(s, "salt", "晒盐", "盐田", "盐场")) {
            return "salt";
        }
        if (has(s, "shop", "store", "market_stall", "铺", "小店")) {
            return "shop";
        }
        if (has(s, "smithy", "forge", "workshop", "kiln", "dyery", "工坊", "作坊", "铁匠")) {
            return "workshop";
        }
        return "";
    }

    private static boolean has(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
