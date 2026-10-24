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

package com.holzyn.actor.domain.mapgen.service;

import com.holzyn.actor.domain.mapgen.algo.MapPlacer;
import com.holzyn.actor.domain.mapgen.entity.ActorNpcRoutine;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.worldmap.entity.ActorHomeMember;
import com.holzyn.actor.domain.worldmap.entity.ActorNpcHome;
import com.holzyn.actor.domain.worldmap.repository.ActorHomeMemberRepository;
import com.holzyn.actor.domain.worldmap.repository.ActorNpcHomeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色日程生成（M7）：把「世界数据里的角色」排进「地图上的落点」，得到一天的行程锦点。
 *
 * <p><b>为什么这是「让角色像真人」的关键一步</b>：地图落位完成时，角色只知道自己家在哪
 * （{@code actor_npc_home.pos}）。但真人不是一直待在家里——他天亮出门、去码头/田里/铺子干活、
 * 中午回家吃饭、傍晚去集市或井台、天黑回家睡觉。没有这张时间表，地图上就只有一个不动的点；
 * 有了它，前端按世界时钟插值，就能看到角色真的在村子里走来走去。</p>
 *
 * <p><b>为什么不用 AI 生成行程</b>：一天的结构（睡眠 8 小时、两餐、上午下午各一段劳作）
 * 是跨文化高度稳定的常量，用程序排既稳定又可复现；AI 的用处是「今天为什么提前收工」
 * 这类运行期事件，那是事件层的事。另外 12 个角色 × 10 段 = 120 段，AI 生成反而容易漏段/撞时间。</p>
 *
 * <p><b>落点从哪来</b>：全部来自 S4 落位结果——{@code homeRef} 找主屋、{@code key=pier/well/shop/…}
 * 找设施、{@code kind=field} 找条田。角色职业来自 {@code actor_profile.occupation}，
 * 家庭角色（户主/配偶/子女/老人）来自 {@code actor_home_member.role}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapRoutineService {

    /** JSON 读语义字段用。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ActorCharacterRepository characterRepository;
    private final ActorNpcHomeRepository homeRepository;
    private final ActorHomeMemberRepository homeMemberRepository;

    /** 一个落点（米）。 */
    private record Spot(String kind, String name, String featureCode, double x, double z) {
    }

    /**
     * 生成某张图上全部角色的日程。
     *
     * @param projectId 项目 ID
     * @param mapId     地图 ID
     * @param features  S4 落位结果（带语义 JSON）
     * @return 日程段（已按角色、时间排序）
     */
    public List<ActorNpcRoutine> build(Long projectId, Long mapId,
                                       List<MapPlacer.PlacedFeature> features) {
        List<ActorNpcRoutine> out = new ArrayList<>();
        if (features == null || features.isEmpty()) {
            return out;
        }
        Map<String, Spot> homes = new LinkedHashMap<>();
        List<Spot> fields = new ArrayList<>();
        Map<String, Spot> facilities = new HashMap<>();
        Spot plaza = null;
        Spot pier = null;
        for (MapPlacer.PlacedFeature f : features) {
            String kind = f.kind();
            if ("pier".equals(kind)) {
                pier = new Spot("shore", f.name(), f.code(), f.x(), f.z());
                continue;
            }
            if ("plaza".equals(kind)) {
                if (plaza == null) {
                    plaza = new Spot("market", f.name(), f.code(), f.x(), f.z());
                }
                continue;
            }
            if ("field".equals(kind)) {
                fields.add(new Spot("field", f.name(), f.code(), f.x(), f.z()));
                continue;
            }
            if (!"building".equals(kind)) {
                continue;
            }
            JsonNode sem = semantic(f.semanticJson());
            if (sem == null) {
                continue;
            }
            String homeRef = sem.path("homeRef").asText("");
            if (!homeRef.isBlank()) {
                homes.putIfAbsent(homeRef, new Spot("home", f.name(), f.code(), f.x(), f.z()));
            }
            String key = sem.path("key").asText("");
            if (!key.isBlank()) {
                facilities.putIfAbsent(key, new Spot("work", f.name(), f.code(), f.x(), f.z()));
            }
        }
        if (plaza == null) {
            plaza = facilities.getOrDefault("well", homes.isEmpty() ? null
                    : homes.values().iterator().next());
        }
        if (pier == null) {
            pier = facilities.get("boathouse");
        }

        List<ActorNpcHome> homeRows;
        List<ActorHomeMember> members;
        Map<Long, ActorCharacter> chars = new HashMap<>();
        try {
            homeRows = homeRepository.findByProjectIdOrderByIdAsc(projectId);
            members = homeMemberRepository.findByProjectIdOrderByHomeIdAscIdAsc(projectId);
            for (ActorCharacter c : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(
                    projectId, 0)) {
                chars.put(c.getId(), c);
            }
        } catch (Exception e) {
            log.warn("[mapgen] 生成日程时读取角色/住所失败：{}", e.getMessage());
            return out;
        }
        Map<Long, List<ActorHomeMember>> byHome = new HashMap<>();
        for (ActorHomeMember m : members) {
            byHome.computeIfAbsent(m.getHomeId(), k -> new ArrayList<>()).add(m);
        }
        int built = 0;
        for (ActorNpcHome home : homeRows) {
            Spot homeSpot = homes.get(String.valueOf(home.getId()));
            if (homeSpot == null) {
                // 这户没有落成主屋（地形放不下/契约缺口）——没法给「家」这个落点，跳过
                continue;
            }
            List<ActorHomeMember> hs = byHome.getOrDefault(home.getId(), List.of());
            for (ActorHomeMember m : hs) {
                ActorCharacter ch = parseRef(m.getCharacterRef(), chars);
                String name = ch != null && ch.getName() != null && !ch.getName().isBlank()
                        ? ch.getName() : home.getName() + "的家人";
                String ref = m.getCharacterRef();
                if (ref == null || ref.isBlank()) {
                    continue;
                }
                Spot work = workplace(ch, m, facilities, fields, pier, plaza, homeSpot);
                out.addAll(dayOf(mapId, projectId, ref, name, homeSpot, work, plaza, pier, m,
                        fields, homeSpot, hashOf(ref)));
                built++;
            }
        }
        log.info("[mapgen] 日程生成：{} 户 / {} 个角色 / {} 段（map={}）",
                homeRows.size(), built, out.size(), mapId);
        return out;
    }

    /**
     * 排一个角色的一天。
     *
     * <p>时刻不是硬编码整齐划一：每个人按 ref 哈希抖动 ±20 分钟，早上「谁先起」、晚上
     * 「谁最后睡」都不一样——真实村落的早晨从来不是全村同时开门的。</p>
     */
    private List<ActorNpcRoutine> dayOf(Long mapId, Long projectId, String ref, String name,
                                        Spot home, Spot work, Spot plaza, Spot pier,
                                        ActorHomeMember member, List<Spot> fields,
                                        Spot homeSpot, int seed) {
        int jitter = (seed % 41) - 20;
        int wake = clampMinute(330 + jitter);
        int workStart = clampMinute(450 + jitter);
        int noon = clampMinute(720 + (seed % 31) - 15);
        int workAgain = clampMinute(800 + (seed % 17) - 8);
        int errand = clampMinute(990 + (seed % 23) - 11);
        int dinner = clampMinute(1110 + (seed % 19) - 9);
        int social = clampMinute(1170 + (seed % 29) - 14);
        int sleep = clampMinute(1290 + (seed % 37) - 18);

        List<ActorNpcRoutine> out = new ArrayList<>();
        add(out, mapId, projectId, ref, name, 0, wake, home, "sleep", "睡觉", true);
        add(out, mapId, projectId, ref, name, wake, workStart, home, "chores", "起床、挑水、喂鸡", false);
        add(out, mapId, projectId, ref, name, workStart, noon, work, activity(work),
                label(work, name), false);
        add(out, mapId, projectId, ref, name, noon, workAgain, home, "meal", "回家吃午饭", true);
        if (sleep - social > 120) {
            add(out, mapId, projectId, ref, name, workAgain, errand, work, activity(work),
                    label(work, name), false);
            Spot market = plaza == null ? home : plaza;
            add(out, mapId, projectId, ref, name, errand, dinner, market, "errand",
                    "去集市/井台办点事", false);
            add(out, mapId, projectId, ref, name, dinner, social, home, "meal", "吃晚饭", true);
            add(out, mapId, projectId, ref, name, social, sleep, socialSpot(plaza, home), "chat",
                    "和邻里说说话", false);
        } else {
            add(out, mapId, projectId, ref, name, workAgain, social, work, activity(work),
                    label(work, name), false);
            add(out, mapId, projectId, ref, name, social, sleep, home, "meal", "吃晚饭", true);
        }
        add(out, mapId, projectId, ref, name, sleep, 1440, home, "sleep", "回家歇下", true);
        // 排序 + 编号（前端按 seq 顺序插值）
        out.sort((a, b) -> Integer.compare(a.getStartMinute(), b.getStartMinute()));
        for (int i = 0; i < out.size(); i++) {
            out.get(i).setSeq(i);
        }
        return out;
    }

    private Spot socialSpot(Spot plaza, Spot home) {
        return plaza == null ? home : plaza;
    }

    private String activity(Spot work) {
        return "work";
    }

    private String label(Spot work, String name) {
        if (work == null) {
            return "在家附近忙活";
        }
        return switch (work.kind()) {
            case "field" -> "下地干活";
            case "shore" -> "去码头/岸边";
            case "market" -> "在集市里张罗";
            default -> "在" + (work.name() == null ? "做工的地方" : work.name()) + "干活";
        };
    }

    /** 推一个日程段（start/end 相同则跳过）。 */
    private void add(List<ActorNpcRoutine> out, Long mapId, Long projectId, String ref, String name,
                     int start, int end, Spot spot, String activity, String label, boolean indoor) {
        if (end <= start) {
            return;
        }
        ActorNpcRoutine r = new ActorNpcRoutine();
        r.setProjectId(projectId);
        r.setMapId(mapId);
        r.setNpcRef(ref);
        r.setNpcName(name);
        r.setStartMinute(start);
        r.setEndMinute(Math.min(1440, end));
        r.setPlaceKind(spot == null ? "home" : spot.kind());
        r.setPlaceRef(spot == null ? "" : spot.name());
        r.setFeatureCode(spot == null ? null : spot.featureCode());
        r.setPosX(spot == null ? null : spot.x());
        r.setPosZ(spot == null ? null : spot.z());
        r.setActivity(activity);
        r.setLabel(label);
        r.setIndoor(indoor ? 1 : 0);
        r.setSeq(out.size());
        out.add(r);
    }

    /**
     * 定这个人的工作地：先看职业关键词，再看家庭角色（子女上学、老人守家）。
     */
    private Spot workplace(ActorCharacter ch, ActorHomeMember member,
                           Map<String, Spot> facilities, List<Spot> fields, Spot pier,
                           Spot plaza, Spot home) {
        String role = member.getRole() == null ? "" : member.getRole();
        if (role.contains("子") || role.contains("女") || role.contains("孩")) {
            Spot school = facilities.get("school");
            return school == null ? home : school;
        }
        if (role.contains("老") || role.contains("祖")) {
            return home;
        }
        String occ = ch == null || ch.getOccupation() == null ? "" : ch.getOccupation();
        // 职业关键词 → 设施语义键（都是通用词，不含任何具体世界观名字）
        if (occ.contains("渔") || occ.contains("船") || occ.contains("网") || occ.contains("海")) {
            return pier != null ? pier : facilities.getOrDefault("boathouse", plaza);
        }
        if (occ.contains("农") || occ.contains("田") || occ.contains("耕") || occ.contains("牧")) {
            return nearest(fields, home);
        }
        if (occ.contains("商") || occ.contains("铺") || occ.contains("卖") || occ.contains("掌柜")) {
            return facilities.getOrDefault("shop", plaza);
        }
        if (occ.contains("医") || occ.contains("药") || occ.contains("草")) {
            return facilities.getOrDefault("shop", facilities.getOrDefault("herb", plaza));
        }
        if (occ.contains("教") || occ.contains("塾") || occ.contains("先生")) {
            return facilities.getOrDefault("school", plaza);
        }
        if (occ.contains("铁") || occ.contains("匠") || occ.contains("织") || occ.contains("染")
                || occ.contains("工")) {
            return facilities.getOrDefault("workshop", plaza);
        }
        if (occ.contains("灯") || occ.contains("守塔")) {
            return facilities.getOrDefault("lighthouse", plaza);
        }
        if (occ.contains("盐")) {
            return facilities.getOrDefault("salt", facilities.getOrDefault("workshop", plaza));
        }
        if (occ.contains("管") || occ.contains("议事") || occ.contains("吏") || occ.contains("仓")) {
            return facilities.getOrDefault("hall", facilities.getOrDefault("granary", plaza));
        }
        if (occ.contains("磨") || occ.contains("面")) {
            return facilities.getOrDefault("mill", plaza);
        }
        // 没有职业信息：去田里/集市，别让他整天杵在家门口
        Spot f = nearest(fields, home);
        return f != null ? f : plaza != null ? plaza : home;
    }

    /** 找离某点最近的落点。 */
    private Spot nearest(List<Spot> spots, Spot from) {
        Spot best = null;
        double bestD = Double.MAX_VALUE;
        for (Spot s : spots) {
            double d = Math.hypot(s.x() - from.x(), s.z() - from.z());
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /** {@code npc:12} → actor_profile id 12 对应的角色。 */
    private ActorCharacter parseRef(String ref, Map<Long, ActorCharacter> chars) {
        if (ref == null) {
            return null;
        }
        int idx = ref.indexOf(':');
        String idPart = idx >= 0 ? ref.substring(idx + 1) : ref;
        try {
            return chars.get(Long.parseLong(idPart.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JsonNode semantic(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private int hashOf(String ref) {
        int h = ref == null ? 0 : ref.hashCode();
        return Math.abs(h == Integer.MIN_VALUE ? 7 : h);
    }

    private int clampMinute(int m) {
        return Math.max(0, Math.min(1439, m));
    }
}
