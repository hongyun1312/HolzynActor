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

package com.holzyn.actor.domain.evaluation.temporal.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.agent.runtime.entity.ActorDecisionLog;
import com.holzyn.actor.domain.agent.runtime.ActorDecisionLogRepository;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.evaluation.temporal.entity.ActorTemporalProbe;
import com.holzyn.actor.domain.evaluation.temporal.repository.ActorTemporalProbeRepository;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.Assertion;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.BatchResult;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.CharDistribution;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.CausalReport;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.DecayCurve;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.DecayPoint;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.DecayReport;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.DistPair;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.DistributionReport;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.ProbeRequest;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.ProbeView;
import com.holzyn.actor.domain.evaluation.temporal.service.TemporalAuditSupport.TemporalReport;
import com.holzyn.actor.domain.mapnav.entity.ActorNpcPath;
import com.holzyn.actor.domain.mapnav.repository.ActorNpcPathRepository;
import com.holzyn.actor.domain.mapnav.service.NpcSpatialView;
import com.holzyn.actor.domain.memory.common.ActorMemoryTypes;
import com.holzyn.actor.domain.npc.memory.entity.ActorMemory;
import com.holzyn.actor.domain.npc.memory.repository.ActorMemoryRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldEntity;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.ActorWorldEntityRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时间性验证服务：否定状态探针 + 因果断言 + 7 日行为分布 + 记忆时距衰减。
 *
 * <p><b>边界</b>：本服务是审计层，只读既有世界状态表（决策台账/轨迹/位置/实体/记忆），
 * 唯一写入是 {@code actor_temporal_probe}（探针结果）。报告一律现算，不落快照——
 * 避免「报告与世界不同步」的假证据。</p>
 *
 * <p><b>为什么探针要落「问题 + 证据快照」</b>：失败时必须能区分「模型不懂时间」与
 * 「注入层没给时间信息」，否则会把注入缺陷记成模型缺陷。</p>
 *
 * <p><b>架构不变量</b>：含 AI 调用，公开方法一律不带 {@code @Transactional}；
 * 依赖全部字段注入 {@code @Autowired(required=false)}，null=未装配[单测]按能力缺失降级。</p>
 */
@Slf4j
@Service
public class TemporalAuditService {

    /** 探针场景码（PromptTemplateService 内置模板 + PromptSpecRegistry 登记）。 */
    public static final String SCENE_TEMPORAL_PROBE = "temporal_probe";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 动作五型（分布审计口径）。 */
    private static final List<String> ACTION_TYPES = List.of("移动", "交谈", "劳作", "等待", "延续", "其它");

    @Autowired(required = false)
    private ActorTemporalProbeRepository probeRepository;
    @Autowired(required = false)
    private ActorDecisionLogRepository decisionRepository;
    @Autowired(required = false)
    private ActorNpcPathRepository pathRepository;
    @Autowired(required = false)
    private ActorWorldEntityRepository entityRepository;
    @Autowired(required = false)
    private WorldLocationRepository locationRepository;
    @Autowired(required = false)
    private ActorCharacterRepository characterRepository;
    @Autowired(required = false)
    private ActorMemoryRepository memoryRepository;
    @Autowired(required = false)
    private NpcSpatialView npcSpatialView;
    @Autowired(required = false)
    private PromptTemplateService promptTemplateService;
    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;
    @Autowired(required = false)
    private UsageLogService usageLogService;
    @Autowired(required = false)
    private WorldClockService worldClockService;
    @Autowired(required = false)
    private AgentMemoryService agentMemoryService;
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    /** 测试开关（与 test.* 门控同源；关闭时探针/报告接口 403）。 */
    @Value("${holzyn.actor.evaluation.test-enabled:false}")
    private boolean testEnabled;

    // ==================== 探针 ====================

    /**
     * 跑一批探针（每个角色 × 类型 × 变体至多一条）。
     *
     * <p>调用前应暂停世界：探针的真值取自「当前时刻」的台账，世界推进会让真值与注入不一致。
     * 世界未暂停不阻断（记录 gameSecond 供核对），但报告会标注批次时刻。</p>
     *
     * @param projectId 项目 ID
     * @param req 批次请求（角色/类型可空=全量）
     *
     * @return 批次结果（含逐条明细）
     */
    public BatchResult runProbes(Long projectId, ProbeRequest req) {
        requireEnabled();
        long gs = gameSecondOf(projectId);
        String runTag = req != null && req.runTag() != null && !req.runTag().isBlank()
                ? req.runTag() : "probe-" + gs;
        List<String> types = req != null && req.probeTypes() != null && !req.probeTypes().isEmpty()
                ? req.probeTypes() : List.of(ActorTemporalProbe.TYPE_STALE_PLACE,
                ActorTemporalProbe.TYPE_STALE_ENTITY, ActorTemporalProbe.TYPE_TEMPORAL_RECALL,
                ActorTemporalProbe.TYPE_CAUSAL_CHAIN);
        List<String> refs = resolveRefs(projectId, req, gs);
        Map<String, String> names = characterNames(projectId);
        List<ActorTemporalProbe> saved = new ArrayList<>();
        for (String ref : refs) {
            String name = names.getOrDefault(ref, ref);
            for (String type : types) {
                try {
                    saved.addAll(runType(projectId, runTag, gs, ref, name, type));
                } catch (Exception e) {
                    log.warn("[时间性审计] 探针类型执行失败: project={} ref={} type={} : {}",
                            projectId, ref, type, e.getMessage());
                }
            }
        }
        return summarize(runTag, gs, saved);
    }

    private List<ActorTemporalProbe> runType(Long projectId, String runTag, long gs,
                                             String ref, String name, String type) {
        switch (type) {
            case ActorTemporalProbe.TYPE_STALE_PLACE:
                return runStalePlace(projectId, runTag, gs, ref, name);
            case ActorTemporalProbe.TYPE_STALE_ENTITY:
                return runStaleEntity(projectId, runTag, gs, ref, name);
            case ActorTemporalProbe.TYPE_TEMPORAL_RECALL:
                return runTemporalRecall(projectId, runTag, gs, ref, name);
            case ActorTemporalProbe.TYPE_CAUSAL_CHAIN:
                return runCausalChain(projectId, runTag, gs, ref, name);
            default:
                return List.of();
        }
    }

    /** 否定状态探针①：离开地点后仍被问及该地点。 */
    private List<ActorTemporalProbe> runStalePlace(Long projectId, String runTag, long gs,
                                                   String ref, String name) {
        ActorNpcPath path = latestArrivedPath(projectId, ref, gs);
        String from = "";
        String to = "";
        Long depart = null;
        if (path != null && !blank(path.getFromRef()) && !blank(path.getToRef())
                && !path.getFromRef().equals(path.getToRef())) {
            from = placeName(projectId, path.getFromRef());
            to = placeName(projectId, path.getToRef());
            depart = path.getDepartGameSecond();
        }
        if (blank(from) || blank(to) || from.equals(to)) {
            // 兜底（修复轮）：取最近两条「终点不同」的到达轨迹——A=较早终点，B=最近终点。
            // 小世界多数移动是「家→茶馆→家」，latest 的 from 常等于 to 或为空，旧逻辑会整批 SKIP，
            // 否定状态就永远测不到；轨迹表本身已证明「人离开过 A、现在在 B」。
            from = "";
            to = "";
            for (ActorNpcPath p : arrivedPaths(projectId, ref, gs)) {
                String cand = placeName(projectId, p.getToRef());
                if (blank(cand) || cand.equals(to)) {
                    continue;
                }
                if (blank(to)) {
                    to = cand;
                    depart = p.getArriveGameSecond();
                } else {
                    from = cand;
                    break;
                }
            }
        }
        if (blank(from) || blank(to) || from.equals(to)) {
            return List.of(skip(projectId, runTag, gs, ref, name, ActorTemporalProbe.TYPE_STALE_PLACE,
                    "世界内没有可用的「离开 A 前往 B」轨迹真值"));
        }
        String question = "有人问你：「你现在人在哪儿？" + from + "那边还看得到你吗？」请如实回答。";
        String evidenceFull = "你先前在「" + from + "」" + (depart == null ? "" :
                "，于" + TemporalAuditSupport.timeLabel(depart) + "离开")
                + "，后来去了「" + to + "」，此后一直在「" + to + "」。";
        String expected = "{\"current_location\":\"" + to + "\",\"forbidden_present\":[\"" + from + "\",\"你\"]}";
        List<ActorTemporalProbe> out = new ArrayList<>();
        out.add(execute(projectId, runTag, gs, ref, name, ActorTemporalProbe.TYPE_STALE_PLACE,
                ActorTemporalProbe.VARIANT_FULL, question, evidenceFull, expected, to, to));
        out.add(execute(projectId, runTag, gs, ref, name, ActorTemporalProbe.TYPE_STALE_PLACE,
                ActorTemporalProbe.VARIANT_NO_STATE, question,
                "（对照组：关闭了位置与实体状态注入，只能凭问题本身作答）", expected, to, to));
        return out;
    }

    /** 否定状态探针②：已被判定失效的实体仍被当作在场/可用。 */
    private List<ActorTemporalProbe> runStaleEntity(Long projectId, String runTag, long gs,
                                                    String ref, String name) {
        long day = Math.floorDiv(gs, 86400L);
        ActorWorldEntity invalid = invalidEntity(projectId, day);
        if (invalid == null) {
            return List.of(skip(projectId, runTag, gs, ref, name, ActorTemporalProbe.TYPE_STALE_ENTITY,
                    "世界内没有 valid_to_game_day 已过的实体，无法构造否定状态"));
        }
        String ename = blank(invalid.getName()) ? invalid.getEntityKey() : invalid.getName();
        String question = "你要去「" + ename + "」办点事。它现在还在吗？还能用/见得到吗？";
        String when = invalid.getValidToGameDay() == null ? "其状态已标记为关闭/不再有效"
                : "已于第" + (invalid.getValidToGameDay() + 1) + "日失效/关闭";
        String evidenceFull = "「" + ename + "」" + when
                + "（失效前状态：" + abbreviate(invalid.getCurrentState(), 80) + "）。";
        String expected = "{\"subject\":\"" + ename + "\",\"state_in\":[\"absent\",\"closed\"]}";
        List<ActorTemporalProbe> out = new ArrayList<>();
        out.add(execute(projectId, runTag, gs, ref, name, ActorTemporalProbe.TYPE_STALE_ENTITY,
                ActorTemporalProbe.VARIANT_FULL, question, evidenceFull, expected, ename, null));
        out.add(execute(projectId, runTag, gs, ref, name, ActorTemporalProbe.TYPE_STALE_ENTITY,
                ActorTemporalProbe.VARIANT_NO_STATE, question,
                "（对照组：关闭了实体状态注入，只能凭问题本身作答）", expected, ename, null));
        return out;
    }

    /**
     * 时间回溯探针：近事（6–72h）与远事（≥72h）各一条。
     *
     * <p>真值 = 真实记忆内容，问题时刻对齐到记忆的游戏时刻（不是决策时刻）——
     * 否则注入的是 ±25h 的其他动作，模型按记忆回答反而被判错，测到的是审计自己的对齐全错。</p>
     */
    private List<ActorTemporalProbe> runTemporalRecall(Long projectId, String runTag, long gs,
                                                       String ref, String name) {
        List<MemoryPoint> mems = recentMemories(projectId, ref, gs, 8).stream()
                .filter(m -> !blank(placeInText(projectId, m.content())))
                .toList();
        if (mems.isEmpty()) {
            return List.of(skip(projectId, runTag, gs, ref, name, ActorTemporalProbe.TYPE_TEMPORAL_RECALL,
                    "近 7 日没有带地点线索的情节记忆，无法核对真实回忆"));
        }
        MemoryPoint near = null;
        MemoryPoint far = null;
        for (MemoryPoint m : mems) {
            if (m.ageHours() >= 6 && m.ageHours() < 72
                    && (near == null || Math.abs(m.ageHours() - 24) < Math.abs(near.ageHours() - 24))) {
                near = m;
            }
            if (m.ageHours() >= 72 && (far == null || m.ageHours() > far.ageHours())) {
                far = m;
            }
        }
        List<ActorTemporalProbe> out = new ArrayList<>();
        if (near != null) {
            out.add(recallProbe(projectId, runTag, gs, ref, name, mems, near, "near"));
        }
        if (far != null) {
            out.add(recallProbe(projectId, runTag, gs, ref, name, mems, far, "far"));
        }
        if (out.isEmpty()) {
            out.add(skip(projectId, runTag, gs, ref, name, ActorTemporalProbe.TYPE_TEMPORAL_RECALL,
                    "记忆年龄分布不足（无 6h 以上的可回溯记忆）"));
        }
        return out;
    }

    private ActorTemporalProbe recallProbe(Long projectId, String runTag, long gs, String ref, String name,
                                           List<MemoryPoint> mems, MemoryPoint target, String band) {
        // 证据里必须包含「被问到的那条记忆」，否则模型无从答起；其余取年龄最接近的 3 条，
        // 让它必须在相近年龄的几条里按「约 N 小时」选，而不是靠唯一性蒙对。
        List<MemoryPoint> others = new ArrayList<>(mems);
        others.remove(target);
        others.sort(Comparator.comparingLong(m -> Math.abs(m.ageHours() - target.ageHours())));
        List<MemoryPoint> evi = new ArrayList<>();
        evi.add(target);
        for (MemoryPoint m : others) {
            if (evi.size() >= 4) {
                break;
            }
            evi.add(m);
        }
        evi.sort(Comparator.comparingLong(MemoryPoint::ageHours));
        StringBuilder ev = new StringBuilder("检索到的记忆：\n");
        for (MemoryPoint m : evi) {
            ev.append("- （距今约").append(m.ageHours()).append("小时）")
                    .append(abbreviate(m.content(), 90)).append('\n');
        }
        String question = "现在是" + TemporalAuditSupport.timeLabel(gs) + "。回想一下："
                + TemporalAuditSupport.timeLabel(target.gameSecond()) + "（约" + target.ageHours() + "小时前）"
                + "你在做什么？当时在哪里？";
        String place = placeInText(projectId, target.content());
        // 同一小时可能有多条记忆（计划/反思/事件），只认单条会把「答到另一条同刻记忆」判成没回忆起来
        StringBuilder truth = new StringBuilder();
        for (MemoryPoint m : mems) {
            if (Math.abs(m.ageHours() - target.ageHours()) <= 1) {
                truth.append(m.content()).append("；");
            }
        }
        if (truth.isEmpty()) {
            truth.append(target.content());
        }
        String expected = "{\"age_hours\":" + target.ageHours() + ",\"band\":\"" + band + "\",\"place\":\""
                + place + "\"}";
        return execute(projectId, runTag, gs, ref, name,
                ActorTemporalProbe.TYPE_TEMPORAL_RECALL, band, question, ev.toString().trim(),
                expected, abbreviate(truth.toString(), 400), blank(place) ? null : place);
    }

    /** 记忆点：内容 + 游戏时刻 + 距今小时数。 */
    private record MemoryPoint(String content, long gameSecond, long ageHours) {
    }

    /**
     * 取最近若干条**情节记忆**，按 createdAt 映射回游戏时刻。
     * 只用真实发生的经历：反思/计划/语义摘要没有唯一「当时在做什么」的真值，
     * 拿它们当回忆真值会把「模型按摘要答对大意」判成失败（修复轮 day2 的 2 条 far FAIL 即此）。
     */
    private List<MemoryPoint> recentMemories(Long projectId, String ref, long gs, int limit) {
        List<MemoryPoint> out = new ArrayList<>();
        if (memoryRepository == null || worldClockService == null) {
            return out;
        }
        for (ActorMemory m : memoryRepository.findByProjectIdAndCharacterRefAndDeletedOrderByCreatedAtDesc(
                projectId, ref, 0)) {
            if (out.size() >= limit) {
                break;
            }
            if (m.getCreatedAt() == null || blank(m.getContent())) {
                continue;
            }
            if (!ActorMemoryTypes.TYPE_EPISODIC.equals(m.getType())) {
                continue;
            }
            // 计划 JSON 不是「经历」，地点多且没有唯一真值，不能拿来判回忆命中
            String content = m.getContent().trim();
            if (content.startsWith("{") || content.startsWith("[")) {
                continue;
            }
            Long gh = worldClockService.gameHourAt(projectId, m.getCreatedAt());
            if (gh == null) {
                continue;
            }
            long memGs = gh * 3600L;
            if (memGs > gs) {
                continue;
            }
            out.add(new MemoryPoint(m.getContent(), memGs, (gs - memGs) / 3600));
        }
        out.sort(Comparator.comparingLong(MemoryPoint::gameSecond).reversed());
        return out;
    }

    /** 因果账本探针：给前序动作，问当前状态；配对反事实检查敏感性。 */
    private List<ActorTemporalProbe> runCausalChain(Long projectId, String runTag, long gs,
                                                    String ref, String name) {
        List<ActorDecisionLog> decisions = decisionsOf(projectId, ref, gs, 5);
        List<ActorDecisionLog> ordered = decisions.stream()
                .filter(d -> !blank(d.getActionText()) && d.getGameSecond() != null)
                .sorted(Comparator.comparing(ActorDecisionLog::getGameSecond))
                .toList();
        // 优先选「动作文本里确实写了地名」的步子：否则问句里没有任何可推理的移动信息，
        // 模型答「不确定」是合理的，测到的是审计自己选样失败（修复轮）。
        List<ActorDecisionLog> withPlace = new ArrayList<>();
        for (ActorDecisionLog d : ordered) {
            if (!blank(placeInText(projectId, normalizeAction(d.getActionText())))) {
                withPlace.add(d);
            }
        }
        List<ActorDecisionLog> chain = withPlace.size() >= 2 ? withPlace : ordered;
        if (chain.size() > 3) {
            chain = chain.subList(chain.size() - 3, chain.size());
        }
        if (chain.size() < 2) {
            return List.of(skip(projectId, runTag, gs, ref, name, ActorTemporalProbe.TYPE_CAUSAL_CHAIN,
                    "近 5 日有效动作不足 2 条，无法构造因果链"));
        }
        List<String> places = new ArrayList<>();
        String lastKnown = "";
        for (ActorDecisionLog d : chain) {
            String action = normalizeAction(d.getActionText());
            // 真值以「账本里写的动作文本」为准：resolved_json 的 targetRef 可能过期
            // （实测账本写「前往南溪老街洗漱」而 resolved 仍指「南溪茶馆」，按 resolved 判会把答对判成答错）。
            String p = placeInText(projectId, action);
            if (blank(p)) {
                // 动作文本没写地名（如「招呼伙计端茶」）说明人没挪窝，沿用上一处
                p = lastKnown;
            }
            if (blank(p)) {
                p = resolvedTargetName(projectId, d);
            }
            if (!blank(p)) {
                places.add(p);
                lastKnown = p;
            }
        }
        String lastPlace = places.isEmpty() ? "" : places.get(places.size() - 1);
        // 账本变体的问句是「按最后一步的目的地或所在场所回答」，真值必须取**账本末步文本**里的地名；
        // 不能用权威轨迹覆盖：轨迹是「人实际在哪」，与账本写的「要去哪」可以不同（day1 实测
        // 末步写「前往南溪茶馆用餐」而轨迹在「菜畦」，按轨迹判会把答对的 4/4 全判成答错）。
        // 仅在文本完全没有地名时，才回落到末步时刻的已到达轨迹。
        if (blank(lastPlace)) {
            lastPlace = placeAt(projectId, ref, chain.get(chain.size() - 1).getGameSecond());
        }
        // 反事实必须改「最后一步」的目的地：只改第 1 步时，末态不变，模型没有理由改变答案，
        // 旧写法把「答案稳定」误判成「反事实不敏感」（修复轮）。
        final String lp = lastPlace;
        String mutated = places.stream().filter(p -> !p.equals(lp)).findFirst().orElse("");
        if (blank(mutated)) {
            mutated = locationNames(projectId).stream().filter(n -> !n.equals(lp)).findFirst().orElse("");
        }
        if (blank(mutated)) {
            mutated = "另一个地方";
        }
        String ledger = ledgerText(chain);
        String question = "根据你记得的行动：\n" + ledger
                + "\n\n这几步走完之后，你最可能停在哪里？（按最后一步的目的地或所在场所回答）";
        String expected = "{\"last_place\":\"" + lastPlace + "\",\"last_action\":\""
                + lastAction(chain).replace("\"", "'") + "\"}";
        ActorTemporalProbe full = execute(projectId, runTag, gs, ref, name,
                ActorTemporalProbe.TYPE_CAUSAL_CHAIN, ActorTemporalProbe.VARIANT_LEDGER,
                question, "（只给动作台账，不给结果）", expected, lastAction(chain),
                blank(lastPlace) ? null : lastPlace);
        String mutatedQuestion = question + "\n\n更正：最后一步的目的地其实是「" + mutated + "」，不是「"
                + lastPlace + "」；前面几步不变。";
        String mutatedExpected = "{\"last_place\":\"" + mutated + "\",\"last_action\":\""
                + lastAction(chain).replace("\"", "'") + "\"}";
        ActorTemporalProbe counterfactual = execute(projectId, runTag, gs, ref, name,
                ActorTemporalProbe.TYPE_CAUSAL_CHAIN, ActorTemporalProbe.VARIANT_LEDGER_MUTATED,
                mutatedQuestion, ledger, mutatedExpected, lastAction(chain), mutated);
        List<ActorTemporalProbe> out = new ArrayList<>();
        out.add(full);
        out.add(counterfactual);
        return out;
    }

    // ==================== 探针执行 ====================

    /**
     * 执行一条探针：模板渲染 → AI 调用（scene=temporal_probe，留原始日志）→ 断言 → 落库。
     */
    private ActorTemporalProbe execute(Long projectId, String runTag, long gs, String ref, String name,
                                       String type, String variant, String question, String evidence,
                                       String expectedJson, String truthText, String truthPlace) {
        ActorTemporalProbe probe = new ActorTemporalProbe();
        probe.setProjectId(projectId);
        probe.setRunTag(runTag);
        probe.setProbeType(type);
        probe.setVariant(variant);
        probe.setCharacterRef(ref);
        probe.setCharacterId(parseRef(ref));
        probe.setGameSecond(gs);
        probe.setGameDay(Math.floorDiv(gs, 86400L));
        probe.setQuestion(question);
        probe.setEvidenceSnapshot(evidence);
        probe.setExpectedJson(expectedJson);
        probe.setCreatedAt(LocalDateTime.now());
        try {
            if (promptTemplateService == null || aiChatGateway == null) {
                probe.setVerdict(ActorTemporalProbe.VERDICT_INCONCLUSIVE);
                probe.setAssertionDetail("AI/模板未装配（单测环境）");
                return save(probe);
            }
            Map<String, Object> ph = new LinkedHashMap<>();
            long uid = userId();
            ph.put("character_name", name);
            ph.put("persona", persona(ref, name, projectId));
            ph.put("situation", situation(projectId, gs));
            ph.put("evidence", evidence == null ? "（无）" : evidence);
            ph.put("question", question);
            String user = promptTemplateService.render(uid, projectId, SCENE_TEMPORAL_PROBE, ph);
            String system = promptTemplateService.systemMessage(uid, projectId, SCENE_TEMPORAL_PROBE, null);
            if (blank(system)) {
                system = "你是世界内的角色，只输出严格 JSON，不输出任何解释。";
            }
            AiChatRequest request = new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system", system),
                    new AiChatRequest.ChatMessage("user", user)),
                    Math.min(0.4, PromptSpecRegistry.temperatureOf(SCENE_TEMPORAL_PROBE)),
                    PromptSpecRegistry.maxOutputTokensOf(SCENE_TEMPORAL_PROBE), true);
            long t0 = System.currentTimeMillis();
            AiChatResult result = AiCallContext.run(SCENE_TEMPORAL_PROBE, probe.getCharacterId(), () ->
                    aiChatGateway.chatCompletion(uid, projectId, null, request));
            probe.setLatencyMs((int) (System.currentTimeMillis() - t0));
            probe.setPromptLen(user.length());
            probe.setTraceId(AiCallContext.currentTraceId());
            String raw = result == null ? null : result.content();
            probe.setAnswerRaw(raw);
            if (result != null && usageLogService != null) {
                try {
                    usageLogService.record(uid, projectId, probe.getCharacterId(), result.providerId(),
                            result.model(), SCENE_TEMPORAL_PROBE, result.promptTokens(),
                            result.completionTokens(), result.cacheHitTokens(), result.cacheMissTokens(), 0);
                } catch (Exception e) {
                    log.debug("[时间性审计] 用量入账失败（忽略）: {}", e.getMessage());
                }
            }
            String json = JsonUtil.extractJson(raw);
            probe.setAnswerJson(json);
            if (blank(json)) {
                probe.setVerdict(ActorTemporalProbe.VERDICT_INCONCLUSIVE);
                probe.setAssertionDetail("回答不含可解析 JSON");
                return save(probe);
            }
            judge(probe, json, type, variant, truthText, truthPlace);
            return save(probe);
        } catch (Exception e) {
            probe.setVerdict(ActorTemporalProbe.VERDICT_ERROR);
            probe.setAssertionDetail("执行异常：" + e.getMessage());
            log.warn("[时间性审计] 探针执行异常: project={} type={} ref={} : {}",
                    projectId, type, ref, e.getMessage());
            return save(probe);
        }
    }

    /**
     * 程序化断言（不使用 LLM 判分）。真值来自台账/实体表，答案按 JSON 字段比对。
     */
    private void judge(ActorTemporalProbe probe, String json, String type, String variant,
                       String truthText, String truthPlace) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            probe.setVerdict(ActorTemporalProbe.VERDICT_INCONCLUSIVE);
            probe.setAssertionDetail("JSON 解析失败：" + e.getMessage());
            return;
        }
        String current = node.path("current_location").asText("");
        List<String[]> assumptions = new ArrayList<>();
        for (JsonNode a : node.path("assumptions")) {
            assumptions.add(new String[]{a.path("subject").asText(""), a.path("state").asText("")});
        }
        String answer = node.path("answer").asText("");

        switch (type) {
            case ActorTemporalProbe.TYPE_STALE_PLACE -> {
                boolean atDest = !blank(truthPlace) && placeMatch(current, truthPlace);
                List<String> contradictions = new ArrayList<>();
                for (String[] a : assumptions) {
                    if (mentions(a[0], "你", "自己") && isPresent(a[1])) {
                        contradictions.add(a[0] + "=" + a[1]);
                    }
                }
                boolean pass = atDest && contradictions.isEmpty();
                probe.setVerdict(pass ? ActorTemporalProbe.VERDICT_PASS : ActorTemporalProbe.VERDICT_FAIL);
                probe.setAssertionDetail("current_location=" + current + "（期望 " + truthPlace + "）"
                        + "；矛盾声明=" + contradictions);
            }
            case ActorTemporalProbe.TYPE_STALE_ENTITY -> {
                String target = truthText == null ? "" : truthText;
                boolean found = false;
                boolean ok = false;
                for (String[] a : assumptions) {
                    if (mentions(a[0], target) || mentions(target, a[0])) {
                        found = true;
                        ok = isAbsent(a[1]);
                        break;
                    }
                }
                boolean pass = found && ok;
                probe.setVerdict(pass ? ActorTemporalProbe.VERDICT_PASS : ActorTemporalProbe.VERDICT_FAIL);
                probe.setAssertionDetail("失效实体=" + target + "；回答声明="
                        + (found ? "命中" : "未提及") + "；判定=" + (pass ? "absent/closed" : "present/open/unknown"));
            }
            case ActorTemporalProbe.TYPE_TEMPORAL_RECALL -> {
                double sim = TemporalAuditSupport.bigramJaccard(answer + current, truthText);
                double cov = coverage(answer + current, truthText);
                boolean byPlace = !blank(truthPlace) && placeMatch(answer + current, truthPlace);
                boolean pass = byPlace || sim >= 0.2 || cov >= 0.35;
                probe.setVerdict(pass ? ActorTemporalProbe.VERDICT_PASS : ActorTemporalProbe.VERDICT_FAIL);
                probe.setAssertionDetail("回忆相似度 Jaccard=" + String.format("%.2f", sim)
                        + "；覆盖率=" + String.format("%.2f", cov) + "（阈值 0.35）；地点命中=" + byPlace
                        + "（期望 " + truthPlace + "）；回答=" + abbreviate(answer, 80));
            }
            case ActorTemporalProbe.TYPE_CAUSAL_CHAIN -> {
                double sim = TemporalAuditSupport.bigramJaccard(answer + current, truthText);
                boolean byPlace = !blank(truthPlace) && placeMatch(answer + current, truthPlace);
                boolean pass = byPlace || sim >= 0.4;
                probe.setVerdict(pass ? ActorTemporalProbe.VERDICT_PASS : ActorTemporalProbe.VERDICT_FAIL);
                probe.setAssertionDetail("末步地点命中=" + byPlace + "（期望 " + truthPlace + "）"
                        + "；与末动作相似度 Jaccard=" + String.format("%.2f", sim)
                        + "（阈值 0.40）；回答=" + abbreviate(answer + current, 80));
            }
            default -> {
                probe.setVerdict(ActorTemporalProbe.VERDICT_INCONCLUSIVE);
                probe.setAssertionDetail("未知探针类型");
            }
        }
    }

    // ==================== 因果断言 ====================

    /**
     * 因果蕴含断言：在真实台账上做可程序化违规检查（零 AI）。
     * <ul>
     * <li>UNRESOLVED_TARGET：移动类动作但 resolved_json 没有 targetRef；</li>
     * <li>ENTITY_INVALID：动作引用已在当日之前失效的实体；</li>
     * <li>PATH_NOT_MATERIALIZED：声称有目标与路径长度，但轨迹表没有对应到达记录。</li>
     * </ul>
     *
     * @param projectId 项目 ID
     * @param days 回看天数（含当日）
     *
     * @return 断言报告
     */
    public CausalReport causal(Long projectId, int days) {
        requireEnabled();
        long gs = gameSecondOf(projectId);
        long today = Math.floorDiv(gs, 86400L);
        int window = Math.max(1, Math.min(30, days));
        List<ActorDecisionLog> decisions = new ArrayList<>();
        for (long d = today - window + 1; d <= today; d++) {
            decisions.addAll(decisionRepository.findByProjectIdAndGameDayOrderByIdAsc(projectId, d));
        }
        // 已删除角色的历史决策不属于本次审计对象（实测 npc:6-12 已删，但台账仍被扫出 35 条）
        Set<String> active = new LinkedHashSet<>(characterNames(projectId).keySet());
        if (!active.isEmpty()) {
            decisions.removeIf(d -> blank(d.getCharacterRef()) || !active.contains(d.getCharacterRef()));
        }
        long now = gameSecondOf(projectId);
        // 同名的「旧版本实体已失效」不等于「这个名字现在不存在」：只要还有一条 validTo 未过
        // 的同名实体（或该名字就是角色名），就不能判成引用失效实体（否则会把「陈伯」「南溪茶馆」
        // 的历史版本行当成缺陷，实测4 条全是这类误报）。
        Map<String, ActorWorldEntity> invalidByName = new LinkedHashMap<>();
        Set<String> stillValidNames = new LinkedHashSet<>();
        for (ActorWorldEntity e : entityRepository.findByProjectIdOrderByIdDesc(projectId)) {
            if (blank(e.getName())) {
                continue;
            }
            if (e.getValidToGameDay() == null || e.getValidToGameDay() >= today) {
                stillValidNames.add(e.getName());
            } else {
                invalidByName.putIfAbsent(e.getName(), e);
            }
        }
        invalidByName.keySet().removeAll(stillValidNames);
        invalidByName.keySet().removeAll(characterNames(projectId).values());
        List<Assertion> violations = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ActorDecisionLog d : decisions) {
            String action = normalizeAction(d.getActionText());
            JsonNode resolved = parseResolved(d.getResolvedJson());
            String targetRef = resolved == null ? "" : resolved.path("targetRef").asText("");
            if ("移动".equals(TemporalAuditSupport.classifyAction(action)) && blank(targetRef)) {
                violations.add(new Assertion("R1_UNRESOLVED_TARGET", d.getCharacterRef(), d.getId(),
                        d.getGameSecond(), abbreviate(action, 60)));
                counts.merge("R1_UNRESOLVED_TARGET", 1, Integer::sum);
            }
            for (Map.Entry<String, ActorWorldEntity> e : invalidByName.entrySet()) {
                if (action.contains(e.getKey()) && d.getGameDay() != null
                        && d.getGameDay() > e.getValue().getValidToGameDay()) {
                    violations.add(new Assertion("R2_ENTITY_INVALID", d.getCharacterRef(), d.getId(),
                            d.getGameSecond(), "引用了第" + (e.getValue().getValidToGameDay() + 1)
                            + "日已失效的「" + e.getKey() + "」：" + abbreviate(action, 50)));
                    counts.merge("R2_ENTITY_INVALID", 1, Integer::sum);
                    break;
                }
            }
            String featureCode = resolved == null ? "" : resolved.path("featureCode").asText("");
            if (!blank(targetRef) && resolved != null && resolved.path("pathLengthM").asDouble(0) > 0
                    && d.getGameSecond() != null && !pathMaterialized(projectId, d.getCharacterRef(),
                    targetRef, featureCode, d.getGameSecond())) {
                // 口径细分（遗留修复）：轨迹行存在但没走到 arrived ≠ 根本没有轨迹，
                // 也 ≠ 已经取消/仍在途中。按 status + arrive 时刻分类，避免把在途算成违规。
                ActorNpcPath match = matchingPath(projectId, d.getCharacterRef(), targetRef, featureCode,
                        d.getGameSecond());
                if (match != null && "walking".equals(match.getStatus())
                        && match.getArriveGameSecond() != null && match.getArriveGameSecond() > now) {
                    continue;
                }
                String rule;
                String detail;
                if (match == null) {
                    rule = "R3_PATH_NOT_MATERIALIZED";
                    detail = "目标=" + targetRef + "，轨迹表无对应记录";
                } else if ("aborted".equals(match.getStatus())) {
                    // 轨迹被后续决策打断属正常调度（NPC 改主意），不是「未落地」缺陷：
                    // 只计数供观察，不进违规列表。
                    counts.merge("R3_ABORTED", 1, Integer::sum);
                    continue;
                } else {
                    rule = "R3_NOT_ARRIVED";
                    detail = "目标=" + targetRef + "，轨迹已建但未到达（status=" + match.getStatus()
                            + "，arrive=" + match.getArriveGameSecond() + "）";
                }
                violations.add(new Assertion(rule, d.getCharacterRef(), d.getId(),
                        d.getGameSecond(), detail));
                counts.merge(rule, 1, Integer::sum);
            }
        }
        return new CausalReport(decisions.size(), counts, violations);
    }

    // ==================== 行为分布 ====================

    /**
     * 7 日行为分布审计：动作类型 × 时段 × 目的地，输出理性/一致性/多样性可量化信号。
     *
     * @param projectId 项目 ID
     * @param days 回看天数
     *
     * @return 分布报告（每角色 JSD + 置换检验 p 值）
     */
    public DistributionReport distribution(Long projectId, int days) {
        requireEnabled();
        long gs = gameSecondOf(projectId);
        // 只统计「已完整结束」的游戏日：当前日无论走到 00:39 还是 23:55 都不算，
        // 否则同一天不同时刻导出的分布不可比（2 日跑曾把周日 0:00-0:39 的 39 分钟算进周末桶）。
        long day = Math.floorDiv(gs, 86400L);
        long lastComplete = day - 1;
        int window = Math.max(1, Math.min(30, days));
        long from = (lastComplete - window + 1) * 86400L;
        long toExclusive = (lastComplete + 1) * 86400L;
        List<ActorDecisionLog> all = new ArrayList<>();
        for (long d = lastComplete - window + 1; d <= lastComplete; d++) {
            all.addAll(decisionRepository.findByProjectIdAndGameDayOrderByIdAsc(projectId, d));
        }
        Map<String, String> names = characterNames(projectId);
        Set<String> active = new LinkedHashSet<>(names.keySet());
        Map<String, List<ActorDecisionLog>> byRef = new LinkedHashMap<>();
        for (ActorDecisionLog d : all) {
            if (d.getGameSecond() == null || d.getGameSecond() < from || blank(d.getCharacterRef())) {
                continue;
            }
            // 只统计当前在场角色：已删除角色的历史决策不该进入 7 日行为画像（实测踩到）
            if (!active.isEmpty() && !active.contains(d.getCharacterRef())) {
                continue;
            }
            byRef.computeIfAbsent(d.getCharacterRef(), k -> new ArrayList<>()).add(d);
        }
        List<CharDistribution> chars = new ArrayList<>();
        Map<String, Integer> overall = new LinkedHashMap<>();
        for (Map.Entry<String, List<ActorDecisionLog>> e : byRef.entrySet()) {
            List<ActorDecisionLog> ds = e.getValue();
            List<String> actions = new ArrayList<>();
            Map<String, Integer> buckets = new LinkedHashMap<>();
            for (ActorDecisionLog d : ds) {
                actions.add(TemporalAuditSupport.classifyAction(normalizeAction(d.getActionText())));
                buckets.merge(TemporalAuditSupport.bucketOf(d.getGameSecond()), 1, Integer::sum);
            }
            Map<String, Integer> actionCounts = TemporalAuditSupport.counts(actions);
            for (String a : actions) {
                overall.merge(a, 1, Integer::sum);
            }
            List<DistPair> pairs = new ArrayList<>();
            pairs.add(pair("day_night", ds, 0));
            pairs.add(pair("weekday_weekend", ds, 1));
            pairs.addAll(dayToDayPairs(ds));
            chars.add(new CharDistribution(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()),
                    ds.size(), actionCounts, buckets,
                    TemporalAuditSupport.entropy(actionCounts),
                    TemporalAuditSupport.collapseRatio(actionCounts),
                    TemporalAuditSupport.maxShare(actionCounts), pairs));
        }
        chars.sort(Comparator.comparing(CharDistribution::total).reversed());
        return new DistributionReport(from, toExclusive, all.size(), chars, overall);
    }

    /** 昼夜与工作日/周末的 JSD + 置换检验。dimension=0 昼夜，1 工作日/周末。 */
    private DistPair pair(String dimension, List<ActorDecisionLog> ds, int kind) {
        List<String> labels = new ArrayList<>();
        List<Boolean> flags = new ArrayList<>();
        List<String> ga = new ArrayList<>();
        List<String> gb = new ArrayList<>();
        for (ActorDecisionLog d : ds) {
            if (d.getGameSecond() == null) {
                continue;
            }
            String a = TemporalAuditSupport.classifyAction(normalizeAction(d.getActionText()));
            String group = kind == 0 ? TemporalAuditSupport.dayNight(d.getGameSecond())
                    : TemporalAuditSupport.weekdayKind(d.getGameSecond());
            boolean second = "night".equals(group) || "weekend".equals(group);
            labels.add(a);
            flags.add(second);
            (second ? gb : ga).add(a);
        }
        double jsd = TemporalAuditSupport.jsd(TemporalAuditSupport.counts(ga), TemporalAuditSupport.counts(gb));
        double p = TemporalAuditSupport.permutationP(labels, flags, 10000, 20261105L);
        String a = kind == 0 ? "day(06-18)" : "weekday";
        String b = kind == 0 ? "night(18-06)" : "weekend";
        return new DistPair(dimension, a, b, jsd, p, p < 0.05, ga.size(), gb.size());
    }

    /** 同一 NPC 相邻日的分布差异（一致性信号；不做置换检验）。 */
    private List<DistPair> dayToDayPairs(List<ActorDecisionLog> ds) {
        Map<Long, List<String>> byDay = new LinkedHashMap<>();
        for (ActorDecisionLog d : ds) {
            if (d.getGameDay() == null) {
                continue;
            }
            byDay.computeIfAbsent(d.getGameDay(), k -> new ArrayList<>())
                    .add(TemporalAuditSupport.classifyAction(normalizeAction(d.getActionText())));
        }
        List<Long> days = new ArrayList<>(byDay.keySet());
        days.sort(Long::compareTo);
        List<DistPair> out = new ArrayList<>();
        for (int i = 0; i + 1 < days.size(); i++) {
            Map<String, Integer> a = TemporalAuditSupport.counts(byDay.get(days.get(i)));
            Map<String, Integer> b = TemporalAuditSupport.counts(byDay.get(days.get(i + 1)));
            out.add(new DistPair("day_to_day", "第" + (days.get(i) + 1) + "日",
                    "第" + (days.get(i + 1) + 1) + "日",
                    TemporalAuditSupport.jsd(a, b), 1.0, false,
                    a.values().stream().mapToInt(Integer::intValue).sum(),
                    b.values().stream().mapToInt(Integer::intValue).sum()));
        }
        return out;
    }

    // ==================== 记忆时距衰减 ====================

    /**
     * 记忆时距衰减双证：
     * <ol>
     * <li><b>打分层</b>：构造 1h–30d 的合成记忆，比较「事件时间基准」与「last_access_at 基准」
     * 两条曲线；后者就是生产 {@code AgentMemoryService.retrieve} 的实际口径。</li>
     * <li><b>真实数据层</b>：项目记忆的「真实年龄」与「有效年龄（到上次访问）」差值，
     * 统计旧事被刷新（年轻化）的比例。</li>
     * </ol>
     *
     * @param projectId 项目 ID
     *
     * @return 衰减报告
     */
    public DecayReport decay(Long projectId) {
        requireEnabled();
        LocalDateTime now = LocalDateTime.now();
        int halfLife = 24;
        double[] ages = {1, 3, 6, 12, 24, 48, 72, 96, 168, 336, 720};
        List<DecayPoint> eventPoints = new ArrayList<>();
        List<DecayPoint> accessPoints = new ArrayList<>();
        for (double age : ages) {
            double recency = AgentMemoryService.recencyScore(now.minusHours((long) age), now, halfLife);
            double score = AgentMemoryService.retrievalScore(5, recency, 0.5);
            eventPoints.add(new DecayPoint(age, recency, score));
            // 生产口径：一次命中就把 last_access_at 刷到「刚刚」，无论事件多旧
            double touchedRecency = AgentMemoryService.recencyScore(now.minusHours(1), now, halfLife);
            double touchedScore = AgentMemoryService.retrievalScore(5, touchedRecency, 0.5);
            accessPoints.add(new DecayPoint(age, touchedRecency, touchedScore));
        }
        double eventRho = TemporalAuditSupport.spearman(ages, eventPoints.stream()
                .mapToDouble(DecayPoint::score).toArray());
        double accessRho = TemporalAuditSupport.spearman(ages, accessPoints.stream()
                .mapToDouble(DecayPoint::score).toArray());
        boolean monotonic = eventRho < -0.9;

        Map<String, Integer> buckets = new LinkedHashMap<>();
        buckets.put("0-6h", 0);
        buckets.put("6-24h", 0);
        buckets.put("1-3d", 0);
        buckets.put("3-7d", 0);
        buckets.put(">7d", 0);
        int memories = 0;
        int refreshed = 0;
        List<ActorMemory> all = memoryRepository == null ? List.of()
                : memoryRepository.findByProjectIdAndDeletedOrderByIdDesc(projectId, 0);
        double rate = 24;
        try {
            Integer r = worldClockService.rateOf(projectId);
            if (r != null && r > 0) {
                rate = r;
            }
        } catch (Exception ignored) {
            // 时钟不可用按默认速率换算（仅影响证据标注，不影响结论）
        }
        for (ActorMemory m : all) {
            if (m.getCreatedAt() == null) {
                continue;
            }
            memories++;
            double trueAge = Duration.between(m.getCreatedAt(), now).toMinutes() / 60.0 * rate;
            String key = trueAge < 6 ? "0-6h" : trueAge < 24 ? "6-24h" : trueAge < 72 ? "1-3d"
                    : trueAge < 168 ? "3-7d" : ">7d";
            buckets.merge(key, 1, Integer::sum);
            if (m.getLastAccessAt() != null && trueAge > 24) {
                double accessAge = Duration.between(m.getLastAccessAt(), now).toMinutes() / 60.0 * rate;
                if (trueAge - accessAge > 24) {
                    refreshed++;
                }
            }
        }
        double refreshRatio = memories == 0 ? 0 : (double) refreshed / memories;
        String verdict;
        if (!monotonic) {
            verdict = "打分层不单调：检索权重没有随事件年龄下降，时距未被真正建模";
        } else if (accessRho > -0.5) {
            verdict = "打分层单调，但 last_access_at 基准在命中后把旧事刷新为「刚刚」——"
                    + "生产检索对旧事的权重被抹平（真实数据中 " + String.format("%.1f%%", refreshRatio * 100)
                    + " 的 >24h 记忆被刷新过）";
        } else {
            verdict = "打分层与访问基准均随时距下降，时距衰减成立";
        }
        List<DecayCurve> curves = List.of(
                new DecayCurve("event_time", eventPoints, eventRho),
                new DecayCurve("last_access_time", accessPoints, accessRho));
        return new DecayReport(monotonic, eventRho, accessRho, curves, buckets, memories, refreshed,
                refreshRatio, verdict);
    }

    // ==================== 总报告 ====================

    /** 数据页一次取全：星期语境 + 分布 + 衰减 + 因果 + 探针判定计数。 */
    public TemporalReport report(Long projectId) {
        requireEnabled();
        long gs = gameSecondOf(projectId);
        int weekday = WorldClockService.weekdayIndex(gs);
        Map<String, Integer> verdicts = new LinkedHashMap<>();
        for (ActorTemporalProbe p : probeRepository.findByProjectIdOrderByIdDesc(projectId)) {
            verdicts.merge(p.getProbeType() + ":" + p.getVerdict(), 1, Integer::sum);
        }
        return new TemporalReport(gs, weekday, WorldClockService.weekdayLabel(weekday),
                WorldClockService.isWeekend(weekday), LocalDateTime.now().toString(),
                distribution(projectId, 7), decay(projectId), causal(projectId, 7), verdicts);
    }

    /**
     * 探针明细（调试页表格）。
     *
     * @param projectId 项目 ID
     * @param type 可选探针类型过滤
     * @param runTag 可选批次过滤（同一批次才可横向比较；不传则返回全部历史）
     *
     * @return 探针列表（倒序）
     */
    public List<ProbeView> probes(Long projectId, String type, String runTag) {
        requireEnabled();
        List<ActorTemporalProbe> list;
        if (!blank(runTag)) {
            list = probeRepository.findByProjectIdAndRunTagOrderByIdAsc(projectId, runTag);
        } else if (blank(type)) {
            list = probeRepository.findByProjectIdOrderByIdDesc(projectId);
        } else {
            list = probeRepository.findByProjectIdAndProbeTypeOrderByIdAsc(projectId, type);
        }
        if (!blank(runTag) && !blank(type)) {
            list = list.stream().filter(p -> type.equals(p.getProbeType())).toList();
        }
        List<ProbeView> out = new ArrayList<>();
        for (ActorTemporalProbe p : list) {
            out.add(new ProbeView(p.getId(), p.getRunTag(), p.getProbeType(), p.getVariant(),
                    p.getCharacterRef(), p.getGameSecond(), p.getGameDay(), p.getVerdict(),
                    p.getQuestion(), p.getEvidenceSnapshot(), p.getExpectedJson(), p.getAnswerRaw(),
                    p.getAssertionDetail(), p.getLatencyMs(), p.getTraceId()));
        }
        return out;
    }

    // ==================== 内部工具 ====================

    private BatchResult summarize(String runTag, long gs, List<ActorTemporalProbe> saved) {
        int pass = 0;
        int fail = 0;
        int inconclusive = 0;
        int skip = 0;
        int error = 0;
        List<ProbeView> views = new ArrayList<>();
        for (ActorTemporalProbe p : saved) {
            switch (p.getVerdict() == null ? "" : p.getVerdict()) {
                case ActorTemporalProbe.VERDICT_PASS -> pass++;
                case ActorTemporalProbe.VERDICT_FAIL -> fail++;
                case ActorTemporalProbe.VERDICT_SKIP -> skip++;
                case ActorTemporalProbe.VERDICT_ERROR -> error++;
                default -> inconclusive++;
            }
            views.add(new ProbeView(p.getId(), p.getRunTag(), p.getProbeType(), p.getVariant(),
                    p.getCharacterRef(), p.getGameSecond(), p.getGameDay(), p.getVerdict(),
                    p.getQuestion(), p.getEvidenceSnapshot(), p.getExpectedJson(), p.getAnswerRaw(),
                    p.getAssertionDetail(), p.getLatencyMs(), p.getTraceId()));
        }
        return new BatchResult(runTag, gs, saved.size(), pass, fail, inconclusive, skip, error, views);
    }

    /**
     * 落库带轻量重试：世界推进与审计共用 H2 连接，偶发
     * {@code Could not open JPA EntityManager for transaction}（实测 day1 批 1 例），
     * 单次失败不该把已付费的 AI 结果记成 ERROR。
     */
    private ActorTemporalProbe save(ActorTemporalProbe probe) {
        if (probeRepository == null) {
            return probe;
        }
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return probeRepository.save(probe);
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(150L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    private ActorTemporalProbe skip(Long projectId, String runTag, long gs, String ref, String name,
                                    String type, String reason) {
        ActorTemporalProbe p = new ActorTemporalProbe();
        p.setProjectId(projectId);
        p.setRunTag(runTag);
        p.setProbeType(type);
        p.setVariant(ActorTemporalProbe.VARIANT_FULL);
        p.setCharacterRef(ref);
        p.setCharacterId(parseRef(ref));
        p.setGameSecond(gs);
        p.setGameDay(Math.floorDiv(gs, 86400L));
        p.setQuestion("（无候选）");
        p.setAssertionDetail(reason);
        p.setVerdict(ActorTemporalProbe.VERDICT_SKIP);
        p.setCreatedAt(LocalDateTime.now());
        return save(p);
    }

    private List<String> resolveRefs(Long projectId, ProbeRequest req, long gs) {
        if (req != null && req.characterRefs() != null && !req.characterRefs().isEmpty()) {
            return req.characterRefs();
        }
        Set<String> refs = new LinkedHashSet<>();
        Set<String> activeRefs = characterNames(projectId).keySet();
        long today = Math.floorDiv(gs, 86400L);
        for (long d = today - 6; d <= today; d++) {
            for (ActorDecisionLog log : decisionRepository.findByProjectIdAndGameDayOrderByIdAsc(projectId, d)) {
                if (!blank(log.getCharacterRef())
                        && (activeRefs.isEmpty() || activeRefs.contains(log.getCharacterRef()))) {
                    refs.add(log.getCharacterRef());
                }
            }
        }
        if (refs.isEmpty() && characterRepository != null) {
            for (ActorCharacter c : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
                if (c.npcRef() != null) {
                    refs.add(c.npcRef());
                }
            }
        }
        return new ArrayList<>(refs);
    }

    private Map<String, String> characterNames(Long projectId) {
        Map<String, String> names = new LinkedHashMap<>();
        if (characterRepository == null) {
            return names;
        }
        for (ActorCharacter c : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            if (c.npcRef() != null) {
                names.put(c.npcRef(), c.getName());
            }
        }
        return names;
    }

    private List<ActorDecisionLog> decisionsOf(Long projectId, String ref, long gs, int days) {
        long today = Math.floorDiv(gs, 86400L);
        List<ActorDecisionLog> out = new ArrayList<>();
        for (long d = today - days + 1; d <= today; d++) {
            for (ActorDecisionLog log : decisionRepository.findByProjectIdAndGameDayOrderByIdAsc(projectId, d)) {
                if (ref.equals(log.getCharacterRef()) && log.getGameSecond() != null
                        && log.getGameSecond() <= gs) {
                    out.add(log);
                }
            }
        }
        return out;
    }

    private ActorNpcPath latestArrivedPath(Long projectId, String ref, long gs) {
        List<ActorNpcPath> paths = pathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(
                projectId, ref, "arrived");
        for (ActorNpcPath p : paths) {
            if (p.getArriveGameSecond() != null && p.getArriveGameSecond() <= gs
                    && !blank(p.getFromRef()) && !blank(p.getToRef())) {
                return p;
            }
        }
        return null;
    }

    /** 截至 gs 的全部已到达轨迹（按 id 倒序，即最近的在前），供否定地点探针做多跳真值。 */
    private List<ActorNpcPath> arrivedPaths(Long projectId, String ref, long gs) {
        List<ActorNpcPath> out = new ArrayList<>();
        for (ActorNpcPath p : pathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(
                projectId, ref, "arrived")) {
            if (p.getArriveGameSecond() != null && p.getArriveGameSecond() <= gs && !blank(p.getToRef())) {
                out.add(p);
            }
        }
        return out;
    }

    private ActorWorldEntity invalidEntity(Long projectId, long day) {
        ActorWorldEntity closed = null;
        for (ActorWorldEntity e : entityRepository.findByProjectIdOrderByIdDesc(projectId)) {
            if (blank(e.getName()) || e.getEntityKey() == null
                    || !(e.getEntityKey().startsWith("location:") || e.getEntityKey().startsWith("person:"))) {
                continue;
            }
            if (e.getValidToGameDay() != null && e.getValidToGameDay() < day) {
                return e;
            }
            // 兜底：状态文本已明确关闭/废弃（valid_to 未写但语义等价）——否则小世界常常 0 候选
            String st = e.getCurrentState() == null ? "" : e.getCurrentState();
            if (closed == null && (st.contains("关闭") || st.contains("停业") || st.contains("废弃")
                    || st.contains("空置") || st.contains("拆除") || st.contains("不再"))) {
                closed = e;
            }
        }
        return closed;
    }

    private String resolvedTargetName(Long projectId, ActorDecisionLog d) {
        JsonNode n = parseResolved(d.getResolvedJson());
        if (n == null) {
            return "";
        }
        String ref = n.path("targetRef").asText("");
        return blank(ref) ? "" : placeName(projectId, ref);
    }

    private String placeName(Long projectId, String ref) {
        if (blank(ref)) {
            return "";
        }
        // 权威要素码（f_home_78 之类）→ 人类可读地名，否则问题里出现裸码，测的就不是时间性
        if (npcSpatialView != null) {
            try {
                String n = npcSpatialView.featureName(projectId, ref);
                if (!blank(n)) {
                    return n;
                }
            } catch (Exception ignored) {
                // 要素表缺失时回退
            }
        }
        if (entityRepository != null) {
            for (ActorWorldEntity e : entityRepository.findByProjectIdOrderByIdDesc(projectId)) {
                if (ref.equals(e.getEntityKey()) || ("location:" + e.getName()).equals(ref)) {
                    return blank(e.getName()) ? ref : e.getName();
                }
            }
        }
        if (locationRepository != null) {
            Long id = refId(ref);
            for (ActorWorldLocation l : locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId)) {
                if (!blank(l.getName()) && (ref.equals(l.getName()) || (id != null && id.equals(l.getId())))) {
                    return l.getName();
                }
            }
        }
        int i = ref.indexOf(':');
        String tail = i >= 0 && i + 1 < ref.length() ? ref.substring(i + 1) : ref;
        if (!tail.equals(ref) && npcSpatialView != null) {
            try {
                String n = npcSpatialView.featureName(projectId, tail);
                if (!blank(n)) {
                    return n;
                }
            } catch (Exception ignored) {
                // 同上
            }
        }
        return tail;
    }

    /**
     * 从自由文本里找最可能的地点（以 world_location 名单为准）：出现次数最多者优先，
     * 同次数取更长的名字（「南溪茶馆」优先于「南溪镇」），再同则取最后一次出现的。
     */
    private String placeInText(Long projectId, String text) {
        if (blank(text) || locationRepository == null) {
            return "";
        }
        String best = "";
        int bestCount = 0;
        int bestIdx = -1;
        for (String n : locationNames(projectId)) {
            int count = 0;
            int idx = -1;
            int from = 0;
            while (true) {
                int at = text.indexOf(n, from);
                if (at < 0) {
                    break;
                }
                count++;
                idx = at;
                from = at + n.length();
            }
            if (count == 0) {
                continue;
            }
            if (count > bestCount || (count == bestCount && n.length() > best.length())
                    || (count == bestCount && n.length() == best.length() && idx > bestIdx)) {
                bestCount = count;
                bestIdx = idx;
                best = n;
            }
        }
        return best;
    }

    private List<String> locationNames(Long projectId) {
        List<String> out = new ArrayList<>();
        if (locationRepository == null) {
            return out;
        }
        for (ActorWorldLocation l : locationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId)) {
            if (!blank(l.getName())) {
                out.add(l.getName());
            }
        }
        return out;
    }

    /** 只解析纯数字 ref（loc:5 / location:5 / 5），非数字返回 null。 */
    private static Long refId(String ref) {
        if (blank(ref)) {
            return null;
        }
        String t = ref;
        int i = t.indexOf(':');
        if (i >= 0) {
            t = t.substring(i + 1);
        }
        try {
            return Long.parseLong(t.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 决策时刻附近是否存在「同目标」的任意状态轨迹行（created/planned/arrived 都算）。
     * 用来区分「轨迹从未建立」与「建立了但没走完」。
     */
    /**
     * 决策时刻附近「同目标」的轨迹行（任意状态）；用于归因。
     * 同一决策可能留下多行（打断重算），返回出发时刻最近的一行。
     */
    private ActorNpcPath matchingPath(Long projectId, String ref, String targetRef, String featureCode,
                                      long gameSecond) {
        ActorNpcPath best = null;
        long bestDelta = Long.MAX_VALUE;
        for (ActorNpcPath p : pathRepository.findByProjectIdAndNpcRefOrderByIdDesc(projectId, ref)) {
            boolean same = samePlace(projectId, targetRef, p.getToRef())
                    || (!blank(featureCode) && samePlace(projectId, featureCode, p.getToRef()));
            if (!same) {
                continue;
            }
            long at = p.getDepartGameSecond() != null ? p.getDepartGameSecond()
                    : (p.getArriveGameSecond() == null ? Long.MIN_VALUE : p.getArriveGameSecond());
            if (at == Long.MIN_VALUE) {
                continue;
            }
            long delta = Math.abs(at - gameSecond);
            if (delta <= 2 * 3600L && delta < bestDelta) {
                best = p;
                bestDelta = delta;
            }
        }
        return best;
    }

    private boolean pathRowExists(Long projectId, String ref, String targetRef, String featureCode,
                                  long gameSecond) {
        return matchingPath(projectId, ref, targetRef, featureCode, gameSecond) != null;
    }

    private boolean pathMaterialized(Long projectId, String ref, String targetRef, String featureCode,
                                     long gameSecond) {
        // 轨迹行的 toRef 记的是「解析后的要素 code」（如 f_teahouse_12），
        // 而决策 resolved_json 同时有 targetRef（loc:南溪茶馆）和 featureCode，二者都要比；
        // 只比 targetRef 会把所有走 element code 的移动误判成「轨迹未落地」（实测占 53%）。
        List<ActorNpcPath> paths = pathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(
                projectId, ref, "arrived");
        for (ActorNpcPath p : paths) {
            boolean same = samePlace(projectId, targetRef, p.getToRef())
                    || (!blank(featureCode) && samePlace(projectId, featureCode, p.getToRef()));
            if (!same) {
                continue;
            }
            long at = p.getDepartGameSecond() != null ? p.getDepartGameSecond()
                    : (p.getArriveGameSecond() == null ? Long.MIN_VALUE : p.getArriveGameSecond());
            if (at != Long.MIN_VALUE && Math.abs(at - gameSecond) <= 2 * 3600L) {
                return true;
            }
        }
        return false;
    }

    /**
     * 某角色在给定游戏秒「最可能所在」的权威地名：取 arrive ≤ t 的最近一条 arrived 轨迹终点。
     * 没有轨迹时返回空串（调用方回退到文本推断）。
     */
    private String placeAt(Long projectId, String ref, Long gameSecond) {
        if (gameSecond == null || blank(ref)) {
            return "";
        }
        List<ActorNpcPath> paths = pathRepository.findByProjectIdAndNpcRefAndStatusOrderByIdDesc(
                projectId, ref, "arrived");
        ActorNpcPath best = null;
        for (ActorNpcPath p : paths) {
            Long at = p.getArriveGameSecond();
            if (at == null || at > gameSecond || blank(p.getToRef())) {
                continue;
            }
            if (best == null || at > best.getArriveGameSecond()) {
                best = p;
            }
        }
        return best == null ? "" : placeName(projectId, best.getToRef());
    }

    /**
     * 两套 ref 是否指同一地点：先比原串，再比规范化地名（含长短名包含，如「茶馆」⊂「南溪茶馆」）。
     */
    private boolean samePlace(Long projectId, String a, String b) {
        if (blank(a) || blank(b)) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        String na = placeName(projectId, a);
        String nb = placeName(projectId, b);
        if (blank(na) || blank(nb)) {
            return false;
        }
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }

    /**
     * 决策日志的 action_text 有两种历史形态：纯文本，或 {@code {"action":"..."}} 的 JSON 串。
     * 后者直接参与地名匹配/Jaccard 会把真值判空、把答案判错（day5 实测 3 条假 FAIL），故统一取 action 字段。
     */
    private static String normalizeAction(String actionText) {
        if (blank(actionText)) {
            return "";
        }
        String s = actionText.trim();
        if (!s.startsWith("{")) {
            return s;
        }
        try {
            JsonNode n = MAPPER.readTree(JsonUtil.extractJson(s));
            String a = n.path("action").asText("");
            if (!blank(a)) {
                return a.trim();
            }
        } catch (Exception ignored) {
            // 非 JSON 或字段缺失：按原文处理
        }
        return s;
    }

    private String ledgerText(List<ActorDecisionLog> chain) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            ActorDecisionLog d = chain.get(i);
            sb.append("第").append(i + 1).append("步（")
                    .append(TemporalAuditSupport.timeLabel(d.getGameSecond())).append("）：")
                    .append(normalizeAction(d.getActionText())).append('\n');
        }
        return sb.toString().trim();
    }

    private String lastAction(List<ActorDecisionLog> chain) {
        return chain.isEmpty() ? "" : normalizeAction(chain.get(chain.size() - 1).getActionText());
    }

    private String persona(String ref, String name, Long projectId) {
        StringBuilder sb = new StringBuilder("你是「").append(name).append("」。");
        if (characterRepository != null) {
            Long id = parseRef(ref);
            if (id != null) {
                ActorCharacter c = characterRepository.findById(id).orElse(null);
                if (c != null) {
                    if (!blank(c.getOccupation())) {
                        sb.append("你的营生是").append(c.getOccupation()).append("。");
                    }
                    if (!blank(c.getTitle())) {
                        sb.append("身份：").append(c.getTitle()).append("。");
                    }
                }
            }
        }
        return sb.toString();
    }

    private String situation(Long projectId, long gs) {
        int w = WorldClockService.weekdayIndex(gs);
        return "当前世界时刻：" + TemporalAuditSupport.timeLabel(gs) + "（"
                + WorldClockService.weekdayLabel(w) + "，" + (WorldClockService.isWeekend(w) ? "周末" : "工作日")
                + "）。";
    }

    private static JsonNode parseResolved(String json) {
        if (blank(json)) {
            return null;
        }
        try {
            return MAPPER.readTree(JsonUtil.extractJson(json));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 回答对真值的覆盖率：回答里的字符二元组有多少出现在真值里。
     * 短回答复述记忆时 Jaccard 天然偏低（分母含整段记忆），覆盖率更贴近「是否答到了」。
     */
    private static double coverage(String answer, String truth) {
        Set<String> a = bigrams(answer);
        Set<String> t = bigrams(truth);
        if (a.isEmpty()) {
            return 0;
        }
        int hit = 0;
        for (String s : a) {
            if (t.contains(s)) {
                hit++;
            }
        }
        return (double) hit / a.size();
    }

    private static Set<String> bigrams(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (blank(text)) {
            return out;
        }
        String t = text.replaceAll("[\\s\\p{Punct}，。！？、：；「」『』（）…—]", "");
        for (int i = 0; i + 2 <= t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    private static boolean placeMatch(String answer, String truth) {        if (blank(answer)) {
            return false;
        }
        return answer.contains(truth) || truth.contains(answer)
                || (truth.length() >= 2 && answer.contains(truth.substring(truth.length() - 2)))
                || TemporalAuditSupport.bigramJaccard(answer, truth) >= 0.3;
    }

    private static boolean isPresent(String state) {
        return "present".equalsIgnoreCase(state) || "open".equalsIgnoreCase(state);
    }

    private static boolean isAbsent(String state) {
        return "absent".equalsIgnoreCase(state) || "closed".equalsIgnoreCase(state);
    }

    private static boolean mentions(String text, String... keys) {
        if (text == null) {
            return false;
        }
        for (String k : keys) {
            if (k != null && !k.isBlank() && text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static String answerText(String raw) {
        String json = JsonUtil.extractJson(raw);
        if (blank(json)) {
            return raw;
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            return n.path("answer").asText("") + " " + n.path("current_location").asText("");
        } catch (Exception e) {
            return raw;
        }
    }

    private static Long parseRef(String ref) {
        if (ref != null && ref.startsWith("npc:")) {
            try {
                return Long.parseLong(ref.substring(4));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private long gameSecondOf(Long projectId) {
        Long gs = worldClockService == null ? null : worldClockService.currentEngineGameSecond(projectId);
        if (gs == null) {
            throw new BizException(409, "世界时钟不可用，无法定位探针时刻");
        }
        return gs;
    }

    private void requireEnabled() {
        if (!testEnabled) {
            throw new BizException(403,
                    "时间性审计未启用（holzyn.actor.evaluation.test-enabled=false）——开启后重试");
        }
    }

    private long userId() {
        try {
            return currentUserProvider == null ? 0L : currentUserProvider.currentUserId();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
