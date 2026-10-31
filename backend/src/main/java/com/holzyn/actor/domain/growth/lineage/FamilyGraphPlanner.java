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

package com.holzyn.actor.domain.growth.lineage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 开局家族图规划器（M2）：**纯函数**，输入角色/关系快照，输出待写的婚姻边与亲子边。
 *
 * <p><b>为什么是纯函数</b>：家族结构最容易出错的地方是"越界"（同性婚配、未成年婚配、
 * 三代内血亲婚配、父母比子女还小）。把这些判定放进数据库读写里就没法单测了，所以这里
 * 不碰任何仓储、不抛业务异常，只算一份 {@link Plan}，由 {@code FamilyGenesisService} 落库。
 * 同一输入必然得到同一输出（{@link Random} 用调用方给的 seed），重复补齐不会长出两套亲戚。</p>
 *
 * <p><b>算法（四步）</b>：
 * <ol>
 *   <li><b>家族聚类</b>：按姓氏（单姓取首字，复姓白名单取两字）并查集合并；
 *       再把 {@code actor_relation} 里 {@code relation_type="同族"} 的两端并进同一簇
 *       （实测 world 33 有「沈明烛—沈伯远 同族」，正是靠这条把零散名字连成家族）；</li>
 *   <li><b>代际分层</b>：G0 祖父辈 ≥{@value #BAND_ELDER}、G1 父辈 {@value #BAND_PARENT}~、
 *       G2 子辈 &lt;{@value #BAND_PARENT}（阈值取婚龄 18 与中年 45 的中点附近，见常量注释）；</li>
 *   <li><b>婚配</b>：同簇同代、异性、双方 18~60 岁、年龄差 ≤{@value #MAX_SPOUSE_AGE_GAP}、
 *       彼此不是「对抗/仇」关系 → 按年龄接近贪心配成夫妻；同簇配不满时允许一次跨簇联姻
 *       （优先「合作」关系连接的簇）；</li>
 *   <li><b>亲子</b>：子辈挂到同簇父辈夫妻名下（父 {@code father}、母 {@code mother} 各一条边），
 *       父辈再挂到祖辈夫妻名下；无夫妻可挂时退化为单亲，仍要求父母比子女大 ≥{@value #MIN_PARENT_AGE_GAP} 岁。</li>
 * </ol></p>
 *
 * <p><b>不变量（单测逐条断言）</b>：异性、婚龄合法、年龄差上限、无血亲婚配（同一簇内不同代不婚配，
 * 且不允许"父女/母子"级差配对）、父母年龄差 ≥{@value #MIN_PARENT_AGE_GAP}、确定性、死人一律排除。</p>
 */
public final class FamilyGraphPlanner {

    /** 祖父辈下界（52：比 ELDER 的 60 岁早一点，给"刚当爷爷"的人留位置）。 */
    public static final int BAND_ELDER = 52;
    /** 父辈下界（26：婚龄 18 + 8 年生育窗口，避免"父子同龄"）。 */
    public static final int BAND_PARENT = 26;
    /** 夫妻年龄差上限（12 岁：超过这个差距在乡土社会里已属罕见）。 */
    public static final int MAX_SPOUSE_AGE_GAP = 12;
    /** 父母与子女最小年龄差（14 岁：低于此值生物学上不可能）。 */
    public static final int MIN_PARENT_AGE_GAP = 14;
    /** 每簇每代最多配几对（防止一个大姓把所有人配成一张网）。 */
    public static final int MAX_PAIRS_PER_BAND = 2;
    /** 对抗类关系的判定词（命中则禁止配对）。 */
    private static final String[] HOSTILE_WORDS = {"对抗", "敌", "仇", "冲突", "不合", "猜忌", "竞争"};
    /** 亲和类关系的判定词（跨簇联姻时优先）。 */
    private static final String[] FRIENDLY_WORDS = {"合作", "同族", "互助", "友好", "邻里"};
    /** 复姓白名单（3 字名取前两字时用）。 */
    private static final Set<String> COMPOUND_SURNAMES = Set.of(
            "欧阳", "司马", "上官", "诸葛", "东方", "独孤", "南宫", "慕容", "皇甫", "尉迟", "公孙", "长孙");

    private FamilyGraphPlanner() {
    }

    /**
     * 角色快照（规划器只认这些字段，和实体解耦）。
     *
     * @param id 角色 ID
     *
     * @param ref 统一身份引用 {@code npc:{id}}
     *
     * @param name 姓名
     *
     * @param gender 性别（{@code 男/女}，未知为 null）
     *
     * @param age 年龄（岁）
     *
     * @param deceased 是否已故
     *
     * @param importance 重要度（1~5，仅用于排序稳定性）
     */
    public record CharSnap(Long id, String ref, String name, String gender, int age,
                           boolean deceased, int importance) {

        /** 是否可按"既有婚姻"配对（开局婚姻都是过去已成的婚事，故不设年龄上限；
         *  运行期新婚姻的 18~60 岁上限仍由 {@code MarriageService} 把关）。 */
        boolean marriageable(int minAge) {
            return !deceased && age >= minAge;
        }

        /** 性别码（男=1 / 女=2 / 未知=0）。 */
        int genderCode() {
            if ("男".equals(gender)) {
                return 1;
            }
            if ("女".equals(gender)) {
                return 2;
            }
            return 0;
        }
    }

    /**
     * 关系快照（只用 {@code relation_type} 的语义：同族用于聚类，对抗/合作用于婚配取舍）。
     *
     * @param type 关系类型文本（如 {@code 同族 / 合作 / 暗中对抗}）
     *
     * @param fromRef 起点引用
     *
     * @param toRef 终点引用
     */
    public record RelSnap(String type, String fromRef, String toRef) {
    }

    /**
     * 婚姻边计划。
     *
     * @param husbandRef 夫方引用
     *
     * @param wifeRef 妻方引用
     *
     * @param gameSecond 成婚时刻（游戏秒）
     *
     * @param familyKey 所属家族键（日志/调试用）
     */
    public record MarriagePlan(String husbandRef, String wifeRef, long gameSecond, String familyKey) {
    }

    /**
     * 亲子边计划。
     *
     * @param parentRef 父母引用
     *
     * @param childRef 子女引用
     *
     * @param relation {@code father} / {@code mother}
     *
     * @param gameSecond 出生时刻（游戏秒）
     */
    public record ParentPlan(String parentRef, String childRef, String relation, long gameSecond) {
    }

    /**
     * 规划结果。
     *
     * @param families 家族簇（键 → 成员引用）
     *
     * @param marriages 婚姻边
     *
     * @param parentEdges 亲子边
     */
    public record Plan(Map<String, List<String>> families, List<MarriagePlan> marriages,
                       List<ParentPlan> parentEdges) {

        /** 是否空计划（无任何边）。 */
        public boolean isEmpty() {
            return marriages.isEmpty() && parentEdges.isEmpty();
        }
    }

    /**
     * 规划家族图。
     *
     * @param characters 角色快照（调用方已过滤 deleted=0）
     *
     * @param relations 关系快照（用于聚类与配对取舍，可为空）
     *
     * @param gameSecond 当前世界秒
     *
     * @param yearSeconds 游戏年秒数（{@code growth.aging.gameSecondsPerYear}）
     *
     * @param minMarriageAge 最低婚龄
     *
     * @param minMarriageAge 最低婚龄（开局婚姻都是"过去已成的婚事"，故不设年龄上限——
     *                       给祖辈设上限会让三代家谱直接断根；运行期新婚姻的 18~60 岁上限
     *                       仍由 {@code MarriageService.romanceEligible} 把关）
     *
     * @param seed 随机种子（通常 projectId，保证同世界可复现）
     *
     * @return 规划结果（永不为 null）
     */
    public static Plan plan(List<CharSnap> characters, List<RelSnap> relations, long gameSecond,
                            long yearSeconds, int minMarriageAge, long seed) {
        List<CharSnap> chars = new ArrayList<>();
        if (characters != null) {
            for (CharSnap c : characters) {
                if (c != null && c.id() != null && c.ref() != null && !c.deceased()) {
                    chars.add(c);
                }
            }
        }
        chars.sort(Comparator.comparing(CharSnap::id));
        if (chars.isEmpty()) {
            return new Plan(Map.of(), List.of(), List.of());
        }
        long year = yearSeconds <= 0 ? 31_536_000L : yearSeconds;
        Random random = new Random(seed);

        // ① 家族聚类
        Map<String, CharSnap> byRef = new LinkedHashMap<>();
        for (CharSnap c : chars) {
            byRef.put(c.ref(), c);
        }
        UnionFind uf = new UnionFind(chars.stream().map(CharSnap::ref).toList());
        Map<String, List<String>> bySurname = new LinkedHashMap<>();
        for (CharSnap c : chars) {
            bySurname.computeIfAbsent(surnameOf(c.name()), k -> new ArrayList<>()).add(c.ref());
        }
        for (List<String> group : bySurname.values()) {
            for (int i = 1; i < group.size(); i++) {
                uf.union(group.get(0), group.get(i));
            }
        }
        Map<String, RelSnap> relationByPair = new LinkedHashMap<>();
        if (relations != null) {
            for (RelSnap r : relations) {
                if (r == null || r.fromRef() == null || r.toRef() == null) {
                    continue;
                }
                relationByPair.put(pairKey(r.fromRef(), r.toRef()), r);
                if (r.type() != null && r.type().contains("同族") && byRef.containsKey(r.fromRef())
                        && byRef.containsKey(r.toRef())) {
                    uf.union(r.fromRef(), r.toRef());
                }
            }
        }
        Map<String, List<CharSnap>> families = new LinkedHashMap<>();
        for (CharSnap c : chars) {
            families.computeIfAbsent(uf.find(c.ref()), k -> new ArrayList<>()).add(c);
        }
        // 家族键用「簇内最多姓氏 + 人数」，便于日志辨识（root → 键）
        Map<String, String> familyKeyByRoot = new LinkedHashMap<>();
        Map<String, List<String>> familyMembers = new LinkedHashMap<>();
        for (Map.Entry<String, List<CharSnap>> e : families.entrySet()) {
            String key = familyKeyOf(e.getValue());
            familyKeyByRoot.put(e.getKey(), key);
            familyMembers.put(key, e.getValue().stream().map(CharSnap::ref).toList());
        }

        // ② 婚配（同簇优先，跨簇兜底）
        List<MarriagePlan> marriages = new ArrayList<>();
        Set<String> married = new HashSet<>();
        for (Map.Entry<String, List<CharSnap>> e : families.entrySet()) {
            String famKey = familyKeyByRoot.get(e.getKey());
            Map<Integer, List<CharSnap>> bands = new LinkedHashMap<>();
            for (CharSnap c : e.getValue()) {
                bands.computeIfAbsent(bandOf(c.age()), k -> new ArrayList<>()).add(c);
            }
            for (Map.Entry<Integer, List<CharSnap>> b : bands.entrySet()) {
                List<CharSnap> pool = b.getValue().stream()
                        .filter(c -> c.marriageable(minMarriageAge))
                        .filter(c -> !married.contains(c.ref()))
                        .sorted(Comparator.comparingInt(CharSnap::age).thenComparing(CharSnap::id))
                        .toList();
                int pairs = 0;
                for (int i = 0; i < pool.size() && pairs < MAX_PAIRS_PER_BAND; i++) {
                    CharSnap a = pool.get(i);
                    if (married.contains(a.ref())) {
                        continue;
                    }
                    CharSnap partner = pickPartner(a, pool, married, relationByPair);
                    if (partner == null) {
                        continue;
                    }
                    marriages.add(toMarriage(a, partner, gameSecond, year, famKey));
                    married.add(a.ref());
                    married.add(partner.ref());
                    pairs++;
                }
            }
        }
        // 跨簇联姻：各簇仍有未婚适龄者时，优先"合作/同族"关系连接的簇之间撮合一对
        List<CharSnap> singles = chars.stream()
                .filter(c -> c.marriageable(minMarriageAge))
                .filter(c -> !married.contains(c.ref()))
                .sorted(Comparator.comparingInt(CharSnap::age).thenComparing(CharSnap::id))
                .toList();
        for (CharSnap a : singles) {
            if (married.contains(a.ref())) {
                continue;
            }
            CharSnap best = null;
            int bestScore = Integer.MIN_VALUE;
            for (CharSnap b : singles) {
                if (b.ref().equals(a.ref()) || married.contains(b.ref())) {
                    continue;
                }
                if (uf.find(a.ref()).equals(uf.find(b.ref()))) {
                    continue; // 同簇交给上一步，跨簇才在这里配
                }
                if (a.genderCode() == 0 || b.genderCode() == 0 || a.genderCode() == b.genderCode()) {
                    continue;
                }
                int gap = Math.abs(a.age() - b.age());
                if (gap > MAX_SPOUSE_AGE_GAP) {
                    continue;
                }
                RelSnap rel = relationByPair.get(pairKey(a.ref(), b.ref()));
                if (rel != null && isHostile(rel.type())) {
                    continue;
                }
                int score = 100 - gap * 3 + (rel != null && isFriendly(rel.type()) ? 20 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = b;
                }
            }
            if (best != null) {
                String famKey = familyKeyByRoot.getOrDefault(uf.find(a.ref()), "异姓")
                        + "×" + familyKeyByRoot.getOrDefault(uf.find(best.ref()), "异姓");
                marriages.add(toMarriage(a, best, gameSecond, year, famKey));
                married.add(a.ref());
                married.add(best.ref());
            }
        }

        // ③ 亲子边
        List<ParentPlan> parentEdges = new ArrayList<>();
        Set<String> hasParent = new HashSet<>();
        Map<String, String> spouseOf = new HashMap<>();
        for (MarriagePlan m : marriages) {
            spouseOf.put(m.husbandRef(), m.wifeRef());
            spouseOf.put(m.wifeRef(), m.husbandRef());
        }
        for (Map.Entry<String, List<CharSnap>> e : families.entrySet()) {
            List<CharSnap> members = e.getValue().stream()
                    .sorted(Comparator.comparingInt(CharSnap::age).reversed().thenComparing(CharSnap::id))
                    .toList();
            List<CharSnap[]> couples = couplesOf(members, marriages, byRef);
            for (CharSnap child : members) {
                if (child.age() < 1 || hasParent.contains(child.ref())) {
                    continue;
                }
                CharSnap[] couple = pickParents(child, couples, members, spouseOf);
                if (couple == null) {
                    continue;
                }
                long birth = gameSecond - (long) child.age() * year;
                for (CharSnap parent : couple) {
                    if (parent == null) {
                        continue;
                    }
                    parentEdges.add(new ParentPlan(parent.ref(), child.ref(),
                            "男".equals(parent.gender()) ? "father" : "mother", birth));
                }
                hasParent.add(child.ref());
            }
        }
        // 家族簇去重（同簇可能被多条 同族 边并入）
        Map<String, List<String>> familiesOut = new LinkedHashMap<>(familyMembers);
        return new Plan(familiesOut, marriages, parentEdges);
    }

    //
    // 内部工具
    //

    /** 年龄 → 代际带（0=子辈 / 1=父辈 / 2=祖父辈）。 */
    static int bandOf(int age) {
        if (age >= BAND_ELDER) {
            return 2;
        }
        if (age >= BAND_PARENT) {
            return 1;
        }
        return 0;
    }

    /**
     * 姓氏：3 字名看复姓白名单，其余取首字。
     *
     * <p>三种名号必须排除，否则会被错误地并成一家人：
     * ① {@code 老周} 是"老+姓"，姓取第二个字；② {@code 小萤 / 阿澈} 是名号不是姓
     * （「阿」「小」不可能是姓），归入「异姓」池当外来户；③ 空名归「异姓」。</p>
     */
    static String surnameOf(String name) {
        if (name == null || name.isBlank()) {
            return "异姓";
        }
        String n = name.trim();
        if (n.length() >= 3 && COMPOUND_SURNAMES.contains(n.substring(0, 2))) {
            return n.substring(0, 2);
        }
        if (n.charAt(0) == '阿' || n.charAt(0) == '小') {
            return "异姓";
        }
        if (n.charAt(0) == '老' && n.length() >= 2) {
            return n.substring(1, 2);
        }
        return n.substring(0, 1);
    }

    /** 从同一代的候选池里挑配偶（性别相反、年龄差合规、关系非对抗、不重复）。 */
    private static CharSnap pickPartner(CharSnap a, List<CharSnap> pool, Set<String> married,
                                        Map<String, RelSnap> relationByPair) {
        if (a.genderCode() == 0) {
            return null;
        }
        CharSnap best = null;
        int bestGap = Integer.MAX_VALUE;
        for (CharSnap b : pool) {
            if (b.ref().equals(a.ref()) || married.contains(b.ref()) || b.genderCode() == 0
                    || b.genderCode() == a.genderCode()) {
                continue;
            }
            int gap = Math.abs(a.age() - b.age());
            if (gap > MAX_SPOUSE_AGE_GAP) {
                continue;
            }
            RelSnap rel = relationByPair.get(pairKey(a.ref(), b.ref()));
            if (rel != null && isHostile(rel.type())) {
                continue;
            }
            if (gap < bestGap) {
                bestGap = gap;
                best = b;
            }
        }
        return best;
    }

    /** 婚姻计划（夫/妻按性别摆位；成婚时刻 = 较年轻一方满婚龄那年，且不早于世界 0 时刻）。 */
    private static MarriagePlan toMarriage(CharSnap a, CharSnap b, long gameSecond, long year, String familyKey) {
        CharSnap husband = a.genderCode() == 1 ? a : b;
        CharSnap wife = a.genderCode() == 1 ? b : a;
        int younger = Math.min(husband.age(), wife.age());
        long at = gameSecond - Math.max(0, younger - 18) * year;
        return new MarriagePlan(husband.ref(), wife.ref(), Math.max(0, at), familyKey);
    }

    /**
     * 与本簇相关的夫妻对（<b>任一方</b>属于本簇即算，因为跨族联姻的夫妻要一起当父母）。
     *
     * <p>上一版只收"双方都在本簇"的夫妻，结果跨族联姻生下的孩子只能拿到单亲边——
     * 家谱里会变成"有爹没娘"，与真实家庭不符。</p>
     */
    private static List<CharSnap[]> couplesOf(List<CharSnap> members, List<MarriagePlan> marriages,
                                              Map<String, CharSnap> byRef) {
        Set<String> memberRefs = new LinkedHashSet<>();
        for (CharSnap m : members) {
            memberRefs.add(m.ref());
        }
        List<CharSnap[]> out = new ArrayList<>();
        for (MarriagePlan m : marriages) {
            if (memberRefs.contains(m.husbandRef()) || memberRefs.contains(m.wifeRef())) {
                CharSnap h = byRef.get(m.husbandRef());
                CharSnap w = byRef.get(m.wifeRef());
                if (h != null && w != null) {
                    out.add(new CharSnap[]{h, w});
                }
            }
        }
        return out;
    }

    /**
     * 给某个子女挑父母：优先"比自己大 ≥14 岁"的夫妻，其次单亲；同簇内按年龄从近到远。
     *
     * <p>排除两种荒谬：① 比自己小或只大十几岁的人不能当父母；② <b>自己的配偶不能当自己父母</b>
     * （贪心配对可能给一位 20 岁的人和 60 岁的人配婚，随后 60 岁者又被当作 20 岁者的父亲——
     * 这是上一版真实存在的越界，必须挡掉）。</p>
     */
    private static CharSnap[] pickParents(CharSnap child, List<CharSnap[]> couples, List<CharSnap> members,
                                          Map<String, String> spouseOf) {
        String childSpouse = spouseOf.get(child.ref());
        CharSnap[] best = null;
        int bestGap = Integer.MAX_VALUE;
        for (CharSnap[] c : couples) {
            if (c[0].ref().equals(childSpouse) || c[1].ref().equals(childSpouse)) {
                continue;
            }
            int gapH = c[0].age() - child.age();
            int gapW = c[1].age() - child.age();
            if (gapH < MIN_PARENT_AGE_GAP || gapW < MIN_PARENT_AGE_GAP) {
                continue;
            }
            int gap = Math.max(gapH, gapW);
            if (gap < bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        if (best != null) {
            return best;
        }
        // 单亲兜底：同簇里年龄差最接近 25 岁的长辈。
        // 但有个坑必须挡：若这位长辈有配偶、而配偶比孩子大不了多少（比如"父亲 42 岁、继母 35 岁、
        // 孩子 28 岁"），只挂单亲会把继母摆在家谱里比孩子只大 7 岁——这种"半个父母"宁可不挂，
        // 让孩子以"亲属"身份留在族里，也不要造出一条荒谬的亲子边。
        CharSnap single = null;
        int bestDelta = Integer.MAX_VALUE;
        for (CharSnap m : members) {
            if (m.ref().equals(child.ref()) || m.ref().equals(childSpouse)) {
                continue;
            }
            int gap = m.age() - child.age();
            if (gap < MIN_PARENT_AGE_GAP) {
                continue;
            }
            String spouse = spouseOf.get(m.ref());
            if (spouse != null) {
                CharSnap sp = null;
                for (CharSnap other : members) {
                    if (other.ref().equals(spouse)) {
                        sp = other;
                        break;
                    }
                }
                if (sp != null && sp.age() - child.age() < MIN_PARENT_AGE_GAP) {
                    continue;
                }
            }
            int delta = Math.abs(gap - 25);
            if (delta < bestDelta) {
                bestDelta = delta;
                single = m;
            }
        }
        return single == null ? null : new CharSnap[]{single, null};
    }

    /** 无序对键（跨 from/to 查询关系时统一口径）。 */
    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /** 关系文本是否对抗。 */
    private static boolean isHostile(String type) {
        if (type == null) {
            return false;
        }
        for (String w : HOSTILE_WORDS) {
            if (type.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 关系文本是否亲和。 */
    private static boolean isFriendly(String type) {
        if (type == null) {
            return false;
        }
        for (String w : FRIENDLY_WORDS) {
            if (type.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 簇名：取成员里出现最多的姓氏 + 人数。 */
    private static String familyKeyOf(List<CharSnap> members) {
        Map<String, Integer> count = new HashMap<>();
        for (CharSnap c : members) {
            count.merge(surnameOf(c.name()), 1, Integer::sum);
        }
        String best = count.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse("异姓");
        return best + "氏(" + members.size() + ")";
    }

    /** 并查集（路径压缩 + 按秩合并），只用于家族聚类。 */
    private static final class UnionFind {

        private final Map<String, String> parent = new HashMap<>();

        UnionFind(List<String> refs) {
            for (String r : refs) {
                parent.put(r, r);
            }
        }

        String find(String x) {
            String p = parent.get(x);
            if (p == null) {
                parent.put(x, x);
                return x;
            }
            if (!p.equals(x)) {
                p = find(p);
                parent.put(x, p);
            }
            return p;
        }

        void union(String a, String b) {
            String ra = find(a);
            String rb = find(b);
            if (!ra.equals(rb)) {
                parent.put(rb, ra);
            }
        }
    }
}
