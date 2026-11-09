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

package com.holzyn.actor.domain.item.provision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 开局供给规则（纯函数，零 Spring / 零随机）。
 *
 * <p><b>为什么要有这一层</b>：物品域把「东西会旧、会烂、会被拿走」都做完了，但真实世界
 * 开局时每个人的背包都是空的——物品域等于没通电。这一层回答的是「一个铁匠、一个农户、
 * 一间杂货铺，开世界时身上/架上应该有什么」。</p>
 *
 * <p><b>为什么按职业而不是随机</b>：随机会让「铁匠家里没有锤子」成为常态，而这种不一致
 * 恰恰是玩家最先察觉的假。职业是角色卡里已有的字段，用它派生最省事也最自洽。</p>
 *
 * <p><b>为什么口粮是固定的</b>：不管什么职业，人都得吃饭。面包与水按人头给，跟职业无关——
 * 否则「铁匠饿死在家里」会变成系统性的荒诞。</p>
 */
public final class ProvisionKit {

    /** 一件要发的东西。 */
    public record Entry(String itemKey, int qty, boolean equip) {

        /** 不装备的条目。 */
        public static Entry of(String itemKey, int qty) {
            return new Entry(itemKey, qty, false);
        }

        /** 要穿在身上的条目（目前只有 body 槽的衣物）。 */
        public static Entry worn(String itemKey, int qty) {
            return new Entry(itemKey, qty, true);
        }
    }

    /** 人人都有的口粮（面包）。 */
    public static final String DAILY_BREAD = "面包";

    /** 人人都有的饮水。 */
    public static final String DAILY_WATER = "水";

    /** 人人都穿的粗布衣（body 槽）。 */
    public static final String DAILY_CLOTHES = "粗布衣";

    /** 货币物品键（与 {@code market.currency-item-key} 默认值一致）。 */
    public static final String COIN = "coin";

    /** 普通村民的随身零钱（够两三天的口粮，不够挥霍）。 */
    public static final int NPC_PURSE = 20;

    private ProvisionKit() {
    }

    /**
     * 某职业的「吃饭家伙」。
     *
     * <p>匹配是<b>包含</b>而不是相等：角色卡里的职业是自由文本（「老铁匠」「村东农户」
     * 「走街货郎」），相等匹配会大面积落空，最后所有人都只剩默认那把柴刀。</p>
     *
     * @param occupation 职业文本（可空）
     *
     * @return 工具/材料条目（不含口粮与衣物）
     */
    public static List<Entry> forOccupation(String occupation) {
        String o = occupation == null ? "" : occupation.trim();
        if (o.isEmpty()) {
            return List.of(Entry.of("柴刀", 1));
        }
        if (containsAny(o, "铁匠", "匠人", "锻造", "锻", "铁工", "打铁")) {
            // 石头/木头：铁匠家里常备的砌炉料。没有它们，「建铁匠炉」（石头×5+铁锭×2）
            // 这条唯一不需要现成设施的配方永远差料——铁匠有炉子之前先得把炉子砌起来。
            return List.of(Entry.of("铁锤", 1), Entry.of("铁锭", 3),
                    Entry.of("石头", 5), Entry.of("木头", 2));
        }
        if (containsAny(o, "农", "耕", "田", "佃", "种地", "庄稼")) {
            return List.of(Entry.of("锄头", 1), Entry.of("麦子", 5), Entry.of("柴刀", 1));
        }
        if (containsAny(o, "猎", "猎户", "捕", "渔")) {
            return List.of(Entry.of("猎弓", 1), Entry.of("箭", 10), Entry.of("肉", 2));
        }
        if (containsAny(o, "厨", "伙", "灶", "庖", "炊")) {
            return List.of(Entry.of("菜刀", 1), Entry.of("面粉", 3));
        }
        if (containsAny(o, "织", "布", "蚕", "绣")) {
            // 织户的料：编草绳（稻草×2）与织布都从这一堆里出
            return List.of(Entry.of("布匹", 2), Entry.of("稻草", 3));
        }
        if (containsAny(o, "商", "掌柜", "贩", "货郎", "牙人", "经纪")) {
            return List.of(Entry.of("布匹", 2), Entry.of(COIN, 50));
        }
        if (containsAny(o, "医", "郎中", "药", "大夫", "巫")) {
            return List.of(Entry.of("药草", 3));
        }
        if (containsAny(o, "书", "先生", "塾", "吏", "账", "文书")) {
            return List.of(Entry.of("纸", 2));
        }
        if (containsAny(o, "兵", "卒", "护院", "捕快", "武", "镖")) {
            return List.of(Entry.of("短刀", 1));
        }
        return List.of(Entry.of("柴刀", 1));
    }

    /**
     * 一个 NPC 的整套随身物品：职业家伙 + 口粮 + 衣物。
     *
     * @param occupation 职业文本（可空）
     *
     * @param breadQty   面包数量（按重要度给，1–3）
     *
     * @param waterQty   水数量（1–3）
     *
     * @return 条目表（稳定顺序：家伙在前、口粮居中、衣物最后）
     */
    public static List<Entry> forNpc(String occupation, int breadQty, int waterQty) {
        List<Entry> out = new ArrayList<>(forOccupation(occupation));
        out.add(Entry.of(DAILY_BREAD, clampQty(breadQty)));
        out.add(Entry.of(DAILY_WATER, clampQty(waterQty)));
        // 随身零钱：没有钱的村民永远买不了东西，市场对他们就是橱窗——整世界长跑实测：
        // 饥饿值掉到底的角色身上一件吃食也没有、口袋一文不名，只能反复「觅食充饥」空转。
        // 20 文约等于两三天的口粮钱：够应急，不够挥霍。
        out.add(Entry.of(COIN, NPC_PURSE));
        out.add(Entry.worn(DAILY_CLOTHES, 1));
        return out;
    }

    /**
     * 玩家开局的随身物品。
     *
     * <p>给一把短刀而不是柴刀：玩家是外来者，身上带件像样的防身家伙才说得通；
     * 给 100 文是为了第一天能真的买东西（否则市场对玩家永远是橱窗）。</p>
     *
     * @return 条目表
     */
    public static List<Entry> forPlayer() {
        return List.of(
                Entry.of("短刀", 1),
                Entry.of(DAILY_BREAD, 3),
                Entry.of(DAILY_WATER, 3),
                Entry.of(COIN, 100),
                Entry.worn(DAILY_CLOTHES, 1));
    }

    /**
     * 某个地点该有的存货（只有「像店铺的地方」才铺货）。
     *
     * @param locationName 地点名（可空）
     *
     * @return 条目表；不像店铺返回空表
     */
    public static List<Entry> forLocation(String locationName) {
        if (!isShopLike(locationName)) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        out.add(Entry.of(DAILY_BREAD, 10));
        out.add(Entry.of("面粉", 5));
        out.add(Entry.of(DAILY_WATER, 10));
        out.add(Entry.of("肉", 3));
        out.add(Entry.of("麦子", 10));
        out.add(Entry.of(DAILY_CLOTHES, 2));
        out.add(Entry.of(COIN, 200));
        // 建材：砌炉（石头×5）、编草绳（稻草×2）、木工活（木头）都要现成的料。
        // 柜台不卖建材时，世界上所有「不需要现成设施」的配方都永远差料，制作域还是 0 笔。
        out.add(Entry.of("石头", 6));
        out.add(Entry.of("木头", 6));
        out.add(Entry.of("稻草", 6));
        String name = locationName == null ? "" : locationName;
        if (containsAny(name, "铁", "匠", "锻")) {
            out.add(Entry.of("柴刀", 3));
            out.add(Entry.of("短刀", 2));
            out.add(Entry.of("铁锭", 5));
            // 整世界长跑实测：铁匠铺有炉子、铁匠身上有铁锭，但**没有任何木料与炭**，
            // 于是「打柴刀」（铁锭×1+木头×1）和「炼铁锭」（铁矿×3+炭×1）这两条配方永远缺料——
            // 制作域在真实世界里等于不存在（5 天 20 小时 0 笔开工）。铺子里该有的料得铺上。
            out.add(Entry.of("木头", 6));
            out.add(Entry.of("炭", 4));
            out.add(Entry.of("铁矿", 4));
        }
        if (containsAny(name, "药", "医")) {
            out.add(Entry.of("药草", 5));
        }
        if (containsAny(name, "酒", "茶", "水")) {
            out.add(Entry.of(DAILY_WATER, 20));
        }
        return out;
    }

    /**
     * 是否「有买卖的地方」：像店铺，或者本身就是聚落（镇/城/村/街）。
     *
     * <p><b>为什么聚落也算</b>：真跑一测就发现，地图生成常常只给「青溪村 / 临溪镇」两个地点，
     * 一个「铺/集市」都没有——按 {@link #isShopLike} 判定的话，全镇唯一有柜台的地方
     * （聚落本身，{@link #forLocation} 同口径铺货）在感知里完全不存在：没有【此处的货】、
     * 没有「买卖」可做，模型永远不会去买东西。聚落是交易发生的地方，这一点必须与铺货口径一致。</p>
     *
     * @param locationName 地点名
     *
     * @return true=可能是买卖发生的地方
     */
    public static boolean isMarketLike(String locationName) {
        return isShopLike(locationName) || isSettlementCore(locationName);
    }

    /**
     * 是否「本身就是聚落」（名字<b>以</b>聚落后缀结尾）。
     *
     * <p><b>为什么用 endsWith 而不是 contains</b>：{@code settlementRank} 用包含匹配是为了识别
     * 「临溪镇街口」这类派生地名；但判断「这里是不是一个能买卖的地方」时，包含匹配会把
     * 「镇口水井」「村口茅房」也算成聚落——一口井不是集市。只有以镇/城/村/街/巷/关/寨
     * <b>结尾</b>的名字，才是「这个地方本身」。</p>
     *
     * @param locationName 地点名
     *
     * @return true=名字以聚落后缀结尾
     */
    public static boolean isSettlementCore(String locationName) {
        if (locationName == null) {
            return false;
        }
        String n = locationName.trim();
        if (n.isEmpty()) {
            return false;
        }
        for (String suffix : new String[]{"镇", "城", "村", "街", "巷", "关", "寨"}) {
            if (n.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否「像店铺的地方」：集市/铺/店/摊/仓/栈/行/坊。
     *
     * <p>刻意不把「广场」「祠堂」算进来——那里没有柜台，凭空铺货会让「货架」这个
     * 概念失去意义。</p>
     *
     * @param locationName 地点名
     *
     * @return true=要铺货
     */
    public static boolean isShopLike(String locationName) {
        if (locationName == null || locationName.isBlank()) {
            return false;
        }
        // 单字只用「铺/店/摊/仓/栈」这类专指店铺的字；「市/行/作」会把「城市广场」
        // 「人行道」「工作间」也判成店铺，凭空给广场铺一货架面包。
        // 「庄」单独收会误伤「山庄/村庄」，所以只收「布庄/钱庄/茶庄/饭庄」这四个真·店铺名。
        return containsAny(locationName, "集市", "市集", "铺", "店", "摊", "仓", "栈",
                "商行", "柜", "作坊", "工坊", "布庄", "钱庄", "茶庄", "饭庄");
    }

    /**
     * 是否「聚落」（镇/城/村/街/巷/渡口/驿站/关/寨）。
     *
     * <p><b>为什么需要这一层</b>：真跑一测就发现，很多世界的地点全是
     * 「青岳主峰 / 青禾村古井 / 灵溪渡口 / 临溪镇」——按「铺/店/集市」一个都命中不了，
     * 于是货架永远空、市场永远没货、玩家永远买不到东西。一个镇子没有柜台不等于没有集市：
     * 聚落本身就是交易发生的地方。</p>
     *
     * <p>刻意不含「朝/系/山/峰/溪/秘境」：那是国、山脉、自然景观，不是集市。</p>
     *
     * @param locationName 地点名
     *
     * @return true=可以作为聚落铺货
     */
    public static boolean isSettlement(String locationName) {
        return settlementRank(locationName) > 0;
    }

    /**
     * 聚落等级（越高越像「有集市的地方」）。
     *
     * <p>镇 4 &gt; 城/关/寨 3 &gt; 村 2 &gt; 街/巷/渡口/驿站 1 &gt; 其它 0。
     * 一个世界只挑<b>等级最高的那一个</b>聚落铺货——每个村都铺一份会让同一个世界出现
     * 三四个一模一样的货架，物价与库存都会失真。</p>
     *
     * @param locationName 地点名
     *
     * @return 0–4
     */
    public static int settlementRank(String locationName) {
        if (locationName == null || locationName.isBlank()) {
            return 0;
        }
        if (locationName.contains("镇")) {
            return 4;
        }
        if (containsAny(locationName, "城", "关", "寨")) {
            return 3;
        }
        if (locationName.contains("村")) {
            return 2;
        }
        if (containsAny(locationName, "街", "巷", "渡口", "驿站")) {
            return 1;
        }
        return 0;
    }

    /**
     * 按角色重要度给口粮（重要角色略多，避免「主角一天就断粮」）。
     *
     * @param importance 重要度（1–10，可空）
     *
     * @return 面包/水的数量（1–3）
     */
    public static int rationFor(Integer importance) {
        int imp = importance == null ? 1 : importance;
        if (imp >= 8) {
            return 3;
        }
        if (imp >= 4) {
            return 2;
        }
        return 1;
    }

    private static int clampQty(int qty) {
        return Math.max(1, Math.min(9, qty));
    }

    private static boolean containsAny(String text, String... needles) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty() && lower.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
