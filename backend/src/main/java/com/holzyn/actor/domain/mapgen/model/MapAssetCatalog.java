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

package com.holzyn.actor.domain.mapgen.model;

import java.util.ArrayList;
import java.util.List;

/**
 * S2 资产目录——建筑类型库 + 街区性格，<b>完全没有坐标</b>（用户决策 #19/#20/#27）。
 *
 * <p>「街区性格」（{@link District}）不参与落位计算，但它是 S4/前端理解这张图的关键：
 * 同样是「东巷」的一排房子，{@code character} 写着「盐仓与渔具，白天有鱼腥味」和写着
 * 「蒙学所在的安静巷子」，S4 会挑不同类型的房子填进去，玩家看一眼就知道自己在镇子的哪一头。</p>
 *
 * @param types      建筑类型库（按 sortOrder 顺序）
 * @param districts  街区性格（可为空）
 * @param rawJson    AI 原文（排障/回放用；兜底目录为 {@code null}）
 * @param aiGenerated 是否 AI 产出
 */
public record MapAssetCatalog(List<MapAssetType> types, List<District> districts,
                              String rawJson, boolean aiGenerated) {

    /**
     * 街区性格。
     *
     * @param code      编码
     * @param name      名字（「老街」）
     * @param character 一句话性格（「石板路两侧是铺面，早市最热闹」）
     */
    public record District(String code, String name, String character) {
    }

    /**
     * 确定性兜底目录（AI 关闭 / AI 失败时使用）。
     *
     * @return 海岛小镇资产目录
     */
    public static MapAssetCatalog fallback() {
        return new MapAssetCatalog(MapAssetType.fallbackIsland(), List.of(
                new District("old_street", "老街", "石板路两侧是铺面，早市最热闹"),
                new District("harbor_row", "码头排屋", "鱼腥味与晒网架，清早最忙"),
                new District("temple_lane", "井亭巷", "安静，只有打水的声音")), null, false);
    }

    /**
     * 按大类取类型（S4 填街用）。
     *
     * @param category 大类（民居/商铺/工坊/公共/生产/水工/自然）
     * @return 该类下的类型（可能为空）
     */
    public List<MapAssetType> byCategory(String category) {
        List<MapAssetType> out = new ArrayList<>();
        for (MapAssetType t : types) {
            if (t.category().equals(category)) {
                out.add(t);
            }
        }
        return out;
    }
}
