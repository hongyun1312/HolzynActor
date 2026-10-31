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

package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.project.model.WorldInitStep;
import com.holzyn.actor.domain.project.vo.WorldInitStatusVO;
import com.holzyn.actor.domain.project.vo.WorldInitStepVO;
import com.holzyn.actor.domain.world.entity.ActorWorldClock;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.worldstore.MountedWorld;
import com.holzyn.actor.domain.worldstore.WorldIndexDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界初始化状态服务（"这个世界是否完全初始化完成"的唯一判定与唯一写入口）。
 *
 * <p><b>职责三件</b>：</p>
 * <ol>
 *   <li><b>判状态</b>：把「步骤定义（{@link WorldInitStep}）」＋「已记录的步骤状态」
 *       ＋「实时数据校验」三者合成一份 {@link WorldInitStatusVO}——画廊角标、世界详情页横幅、
 *       仪表盘提示、初始化向导都只认这一份结论。</li>
 *   <li><b>写状态</b>：{@code markRunning / markDone / markFailed / markSkipped} 是步骤状态的唯一写入路径。</li>
 *   <li><b>刷快照</b>：把完成度回写目录库 {@code actor_project.init_status/init_done/init_total}，
 *       让世界画廊在"不打开任何世界库"时也能显示"未初始化"角标。</li>
 * </ol>
 *
 * <p><b>状态存哪里、为什么不是一张表</b>：存目录库 {@code actor_project.init_steps}（步骤明细 JSON）。
 * 世界库基线被 {@code WorldSchemaParityTest} / {@code WorldTableClassificationTest} 冻结为
 * legacy 的 113 张表，新增表会同时打破「两库表集合等式」与「ORM 映射归类」；而步骤状态本就是
 * 项目行上的从属摘要，挂在 actor_project 上既不用动 schema 治理基线，也保持单一写入口
 * （{@link WorldIndexDao} 独占该列）。</p>
 *
 * <p><b>为什么要存明细而不是只看实时数据</b>：只信明细，历史世界（本功能之前建的）会全部显示
 * "未初始化"，用户被逼着重跑一遍 AI；只信实时数据，就分不清"从没跑过"和"跑过但 AI 失败"，
 * 也给不出失败原因。两者取或：任一为真即视为已满足。</p>
 *
 * <p>所属模块：domain/project（项目与世界初始化）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldInitStatusService {

    /** 步骤状态 JSON 解析器（与 WorldMetaSuggestService 同一套 Jackson 3 用法） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一个游戏日的秒数（世界时间"已成立"的门槛，见 {@link #clockReady(Long)}） */
    private static final long ONE_GAME_DAY = 86400L;

    /** 步骤明细在 actor_project.init_steps 里的字段名 */
    private static final String K_STATUS = "status";

    private static final String K_COUNT = "count";
    private static final String K_MESSAGE = "message";
    private static final String K_ATTEMPTS = "attempts";
    private static final String K_FINISHED = "finishedAt";

    /** 世界观设定（世界库） */
    private final ActorWorldSettingRepository worldSettingRepository;

    /** 世界观地点（世界库） */
    private final WorldLocationRepository worldLocationRepository;

    /** 角色卡与普通 NPC 共用角色表（世界库；普通 NPC 的 type=common） */
    private final ActorCharacterRepository characterRepository;

    /** 角色关系（世界库） */
    private final ActorCharacterRelationRepository relationRepository;

    /** 世界时钟（世界库；只读，不触发懒创建） */
    private final ActorWorldClockRepository clockRepository;

    /**
     * 目录库索引 DAO（步骤明细 + 完成度快照的唯一读写入口）。
     *
     * <p>字段注入 {@code required=false}：单元测试与"目录库未迁移"的场景下装配不到它，
     * 此时状态判定退化为"只看实时数据"，不抛异常也不 500。</p>
     */
    @Autowired(required = false)
    private WorldIndexDao worldIndexDao;

    /**
     * 当前挂载的世界（判断"实时校验能不能读"）。
     *
     * <p>世界没挂载时，世界库连接池已关闭，实时校验必然读空——此时写快照会把状态误置为
     * NOT_STARTED，把用户跑过的世界说成"没初始化"。所以快照只在挂载状态下才写。</p>
     */
    @Autowired(required = false)
    private MountedWorld mountedWorld;

    /**
     * 世界程度（决定「宏观社会模板」这一步是否属于本世界所需）。
     *
     * <p>为什么要在这里也要看档位：休闲/轻度档的世界本来就不该有宏观模板，向导里那一步
     * 应当显示「已跳过——该档位不生成宏观模板」，既不算失败也不算未完成。若只靠执行时
     * markSkipped 而不在判定侧兜底，用户中途改档（或换机器打开旧世界）就会看到一个永远
     * 待办的可选步。</p>
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldToneService worldToneService;

    // ==================================================================
    // 读：状态判定
    // ==================================================================

    /**
     * 计算世界初始化状态（画廊/详情页/仪表盘/向导共用的唯一判定）。
     *
     * @param projectId 项目 ID
     *
     * @return 初始化状态 VO（步骤列表按执行顺序）
     */
    public WorldInitStatusVO status(Long projectId) {
        return buildStatus(projectId);
    }

    /**
     * 计算并回写目录库快照（画廊角标）。
     *
     * <p><b>只能在"目标世界已挂载"的时机调用</b>（创建/打开/关闭/单步执行完成）：实时校验依赖世界库，
     * 世界没挂载时所有实时项都会读空，回写会把快照误置为 NOT_STARTED。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 最新状态
     */
    public WorldInitStatusVO refreshStatus(Long projectId) {
        WorldInitStatusVO vo = buildStatus(projectId);
        writeRegistry(projectId, vo);
        return vo;
    }

    /**
     * 组装初始化状态（纯读，不写库）。
     *
     * @param projectId 项目 ID
     *
     * @return 初始化状态 VO（步骤列表按执行顺序）
     */
    private WorldInitStatusVO buildStatus(Long projectId) {
        Map<String, StepState> states = loadStates(projectId);
        List<WorldInitStepVO> steps = new ArrayList<>();
        int doneCount = 0;
        int requiredDone = 0;
        int requiredTotal = 0;
        String currentStep = null;
        boolean started = false;
        boolean degraded = false;
        for (WorldInitStep step : WorldInitStep.values()) {
            boolean satisfied = satisfied(projectId, step);
            StepState state = states.get(step.code());
            String status = state == null || state.status == null
                    ? WorldInitStepVO.STATUS_PENDING : state.status;
            String toneSkip = toneSkipMessage(projectId, step);
            if (toneSkip != null && WorldInitStepVO.STATUS_PENDING.equals(status)) {
                // 档位决定这一步"本世界不需要"：直接呈现为已跳过（不是失败、也不拉低完成度）
                status = WorldInitStepVO.STATUS_SKIPPED;
                satisfied = true;
                state = new StepState();
                state.status = WorldInitStepVO.STATUS_SKIPPED;
                state.message = toneSkip;
            }
            if (!WorldInitStepVO.STATUS_PENDING.equals(status)) {
                started = true;
            }
            WorldInitStepVO vo = WorldInitStepVO.of(step, status, satisfied,
                    state == null || state.count == null ? 0 : state.count,
                    state == null ? null : state.message,
                    state == null || state.attempts == null ? 0 : state.attempts,
                    state == null ? null : state.finishedAt);
            steps.add(vo);
            if (isSatisfied(vo)) {
                doneCount++;
                if (step.required()) {
                    requiredDone++;
                }
            } else if (step.required()) {
                if (currentStep == null) {
                    currentStep = step.code();
                }
            } else {
                degraded = true;
            }
            if (step.required()) {
                requiredTotal++;
            }
        }
        boolean complete = currentStep == null;
        String message;
        if (complete) {
            message = degraded
                    ? "初始化已完成（部分可选步骤未完成，世界仍可正常运行）"
                    : "初始化已完成，世界可以正常开始";
        } else {
            WorldInitStep pending = WorldInitStep.of(currentStep).orElse(null);
            message = "初始化未完成：还差 " + (requiredTotal - requiredDone) + " 个必需步骤，"
                    + "下一步是「" + (pending == null ? currentStep : pending.label()) + "」";
        }
        return new WorldInitStatusVO(projectId, worldNameOf(projectId), started, complete, complete && degraded,
                doneCount, steps.size(), requiredDone, requiredTotal, currentStep, message, steps);
    }

    /**
     * 某一步骤是否已满足（明细里 DONE/SKIPPED，或实时数据已存在）。
     *
     * @param projectId 项目 ID
     *
     * @param step      步骤
     *
     * @return true=已满足
     */
    public boolean satisfied(Long projectId, WorldInitStep step) {
        StepState state = loadStates(projectId).get(step.code());
        String status = state == null ? null : state.status;
        // 用户显式跳过（仅可选步骤）视为已满足：跳过是用户的决定，不该反复提示"未完成"
        if (WorldInitStepVO.STATUS_SKIPPED.equals(status)) {
            return true;
        }
        if (WorldInitStepVO.STATUS_DONE.equals(status)) {
            return true;
        }
        return liveSatisfied(projectId, step);
    }

    /**
     * 某步骤的实时数据是否已存在（不看明细；供"跳过已生成"和门禁判断复用）。
     *
     * @param projectId 项目 ID
     *
     * @param step      步骤
     *
     * @return true=数据已存在
     */
    public boolean liveSatisfied(Long projectId, WorldInitStep step) {
        try {
            switch (step) {
                case WORLDVIEW:
                    return settingReady(projectId);
                case LOCATIONS:
                    return worldLocationRepository.countByProjectId(projectId) > 0;
                case CHARACTERS:
                    return characterRepository.countByProjectIdAndDeleted(projectId, 0) > 0;
                case NPCS:
                    return commonNpcCount(projectId) > 0;
                case RELATIONS:
                    return !relationRepository.findByProjectId(projectId).isEmpty();
                case WORLD_TIME:
                    return clockReady(projectId);
                default:
                    // 家族与基因 / 世界地图与住所 / 宏观模板 / 知识向量化：没有可靠的"数据已存在"
                    // 判据（可能只影响部分角色或只写快照），一律只认明细状态，避免误判为已完成。
                    return false;
            }
        } catch (Exception e) {
            // 世界库未挂载/表未迁移时会抛异常——判状态不能因此 500，按"未满足"处理
            log.debug("[世界初始化状态] 实时校验失败 step={}：{}", step.code(), e.toString());
            return false;
        }
    }

    /**
     * 世界观设定是否"够用"（核心字段：名称 + 至少三个分节有内容）。
     *
     * <p>为什么不是"有行就算"：只有名称、五个分节全空的世界，后续地点/角色/关系全都没输入，
     * 跑起来只会得到空壳——那正是用户报障"初始化完成但世界是空的"的场景。</p>
     */
    private boolean settingReady(Long projectId) {
        ActorWorldSetting s = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
        if (s == null) {
            return false;
        }
        boolean named = s.getName() != null && !s.getName().isBlank();
        int filled = 0;
        for (String f : new String[]{s.getGeography(), s.getFactions(), s.getMagicSystem(),
                s.getCulture(), s.getHistory(), s.getFreeText()}) {
            if (f != null && !f.isBlank()) {
                filled++;
            }
        }
        return named && filled >= 3;
    }

    /**
     * 该步骤是否因「世界程度」而整体不适用（目前只有宏观社会模板一步）。
     *
     * @param projectId 项目 ID
     *
     * @param step      步骤
     *
     * @return 需要注意的跳过说明；不适用时返回 {@code null}
     */
    private String toneSkipMessage(Long projectId, WorldInitStep step) {
        if (step != com.holzyn.actor.domain.project.model.WorldInitStep.MACRO || worldToneService == null) {
            return null;
        }
        try {
            com.holzyn.actor.domain.world.model.WorldTone tone = worldToneService.tone(projectId);
            if (!tone.skipsMacroTemplate()) {
                return null;
            }
            return "该档位不生成宏观模板（世界程度：" + tone.label() + "），已按设定跳过";
        } catch (RuntimeException e) {
            log.debug("[世界初始化] 世界程度读取失败，按标准档判定 project={}：{}", projectId, e.toString());
            return null;
        }
    }

    /** 普通型 NPC 数量（含历史 crowd 类型） */    private long commonNpcCount(Long projectId) {
        return characterRepository.countByProjectIdAndTypeAndDeleted(projectId, NpcRefs.TYPE_COMMON, 0)
                + characterRepository.countByProjectIdAndTypeAndDeleted(projectId, NpcRefs.TYPE_COMMON_LEGACY, 0);
    }

    /**
     * 世界时间是否已设置（时钟行存在，且已经过一个"可辨认的起点"）。
     *
     * <p>只读仓储、不调 {@code WorldClockService.requireClock}——后者会懒创建时钟行，
     * 把"没初始化过"的世界当场变成"有 0 点时钟"的假象。</p>
     *
     * <p><b>为什么门槛是"跑过一天"而不是"&gt;0"</b>：时钟会随世界运行自动推进，刚打开的新世界
     * 几分钟就有 last_game_second&gt;0（实测 2 分钟就满足），于是"世界时间"这一步会在用户
     * 从没执行过的情况下自己变成已完成，严格顺序就形同虚设。跑满一个游戏日才认为
     * "时间已经是这个世界的一部分"（初始化推出的起点通常远大于此）。</p>
     */
    private boolean clockReady(Long projectId) {
        ActorWorldClock clock = clockRepository.findByProjectId(projectId).orElse(null);
        return clock != null && clock.getLastGameSecond() != null && clock.getLastGameSecond() >= ONE_GAME_DAY;
    }

    /** 世界观名称（无设定时返回 null，前端回退项目名） */
    private String worldNameOf(Long projectId) {
        try {
            return worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                    .map(ActorWorldSetting::getName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 步骤的最终"已完成"判定（含实时满足被抬成 DONE 的情况） */
    private static boolean isSatisfied(WorldInitStepVO vo) {
        return vo.satisfied() || WorldInitStepVO.STATUS_DONE.equals(vo.status())
                || WorldInitStepVO.STATUS_SKIPPED.equals(vo.status());
    }

    // ==================================================================
    // 读：步骤明细（actor_project.init_steps JSON）
    // ==================================================================

    /**
     * 读项目全部步骤状态（按步骤编码索引；读不到/解析失败返回空 Map）。
     *
     * @param projectId 项目 ID
     *
     * @return 步骤编码 → 状态
     */
    private Map<String, StepState> loadStates(Long projectId) {
        Map<String, StepState> out = new LinkedHashMap<>();
        if (worldIndexDao == null || projectId == null) {
            return out;
        }
        String json;
        try {
            json = worldIndexDao.readInitSteps(projectId);
        } catch (Exception e) {
            log.debug("[世界初始化状态] 读取步骤明细失败 project={}：{}", projectId, e.toString());
            return out;
        }
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            for (WorldInitStep step : WorldInitStep.values()) {
                JsonNode node = root.path(step.code());
                if (node == null || node.isMissingNode() || node.isNull()) {
                    continue;
                }
                StepState st = new StepState();
                st.status = textOrNull(node, K_STATUS);
                st.count = intOrNull(node, K_COUNT);
                st.message = textOrNull(node, K_MESSAGE);
                st.attempts = intOrNull(node, K_ATTEMPTS);
                st.finishedAt = dateOrNull(node, K_FINISHED);
                out.put(step.code(), st);
            }
        } catch (Exception e) {
            log.warn("[世界初始化状态] 步骤明细 JSON 解析失败（按无状态处理）project={}：{}",
                    projectId, e.getMessage());
        }
        return out;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v == null || v.isMissingNode() || v.isNull() ? null : v.asString("");
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v == null || v.isMissingNode() || v.isNull() || !v.isNumber() ? null : v.asInt(0);
    }

    private static LocalDateTime dateOrNull(JsonNode node, String field) {
        String text = textOrNull(node, field);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================================================================
    // 写：步骤明细（唯一写入口）
    // ==================================================================

    /**
     * 标记步骤开始执行。
     *
     * @param projectId 项目 ID
     *
     * @param step      步骤
     */
    public void markRunning(Long projectId, WorldInitStep step) {
        StepState st = stateForWrite(projectId, step);
        st.status = WorldInitStepVO.STATUS_RUNNING;
        st.message = "正在执行…";
        st.attempts = (st.attempts == null ? 0 : st.attempts) + 1;
        saveState(projectId, step, st);
    }

    /**
     * 标记步骤完成。
     *
     * @param projectId 项目 ID
     *
     * @param step      步骤
     *
     * @param itemCount 本次产出条数
     *
     * @param message   成功摘要（可空）
     */
    public void markDone(Long projectId, WorldInitStep step, int itemCount, String message) {
        StepState st = stateForWrite(projectId, step);
        st.status = WorldInitStepVO.STATUS_DONE;
        st.count = itemCount;
        st.message = truncate(message, 480);
        st.finishedAt = LocalDateTime.now();
        saveState(projectId, step, st);
        refreshRegistry(projectId);
    }

    /**
     * 标记步骤失败。
     *
     * @param projectId 项目 ID
     *
     * @param step      步骤
     *
     * @param message   失败原因（面向用户，截断到 480 字）
     */
    public void markFailed(Long projectId, WorldInitStep step, String message) {
        StepState st = stateForWrite(projectId, step);
        st.status = WorldInitStepVO.STATUS_FAILED;
        st.message = truncate(message, 480);
        st.finishedAt = LocalDateTime.now();
        saveState(projectId, step, st);
        refreshRegistry(projectId);
    }

    /**
     * 标记步骤被用户跳过（仅可选步骤）。
     *
     * @param projectId 项目 ID
     *
     * @param step      步骤
     *
     * @param message   跳过说明（可空）
     */
    public void markSkipped(Long projectId, WorldInitStep step, String message) {
        StepState st = stateForWrite(projectId, step);
        st.status = WorldInitStepVO.STATUS_SKIPPED;
        st.message = truncate(message == null || message.isBlank() ? "用户跳过" : message, 480);
        st.finishedAt = LocalDateTime.now();
        saveState(projectId, step, st);
        refreshRegistry(projectId);
    }

    /**
     * 把当前完成度回写目录库快照（画廊卡片角标）。
     *
     * <p>失败只告警：目录库快照是"展示缓存"，写不进去不能影响初始化本身。</p>
     *
     * @param projectId 项目 ID
     */
    public void refreshRegistry(Long projectId) {
        if (worldIndexDao == null) {
            return;
        }
        try {
            writeRegistry(projectId, buildStatus(projectId));
        } catch (Exception e) {
            log.debug("[世界初始化状态] 目录库快照回写失败 project={}：{}", projectId, e.toString());
        }
    }

    /**
     * 把完成度写进目录库快照（画廊卡片角标读的就是它）。
     *
     * <p>失败只告警：目录库快照是"展示缓存"，写不进去不能影响初始化本身。</p>
     *
     * @param projectId 项目 ID
     *
     * @param status    已组装好的状态
     */
    private void writeRegistry(Long projectId, WorldInitStatusVO status) {
        if (worldIndexDao == null) {
            return;
        }
        if (mountedWorld != null && !isThisWorldMounted(projectId)) {
            // 不是"这个世界"挂着：实时项读的是别的世界（或空），写下去会把快照误置为 NOT_STARTED
            return;
        }
        try {
            String statusCode = status.complete() ? (status.degraded() ? "DEGRADED" : "DONE")
                    : (status.initialized() ? "PARTIAL" : "NOT_STARTED");
            worldIndexDao.updateInitSummary(projectId, statusCode, status.doneCount(), status.totalCount());
            // 顺手同步分类快照：老世界（本次升级前保存的）在目录库里 genre/tone 还是空的，
            // 画廊卡片就显示不出「仙侠 · 休闲」。这里读的是世界库真值，比让用户重新保存一遍稳。
            syncClassification(projectId);
        } catch (Exception e) {
            log.debug("[世界初始化状态] 目录库快照回写失败 project={}：{}", projectId, e.toString());
        }
    }

    /**
     * 把世界库里的类别/程度同步到目录库快照（画廊卡片标签的数据源）。
     *
     * @param projectId 项目 ID
     */
    private void syncClassification(Long projectId) {
        ActorWorldSetting s = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
        if (s == null) {
            return;
        }
        try {
            worldIndexDao.updateClassification(projectId, s.getGenre(), s.getTone());
        } catch (Exception e) {
            log.debug("[世界初始化状态] 分类快照同步失败 project={}：{}", projectId, e.toString());
        }
    }

    /**
     * 判断"这个世界"是否正处于挂载状态。
     *
     * <p>只判断 {@code mountedWorld.isMounted()} 不够：同一时刻只挂一个世界，别的世界在读状态时
     * 实时校验读到的是当前挂载那个的库，写快照就会把别人的完成度写到自己头上（实测把跑满的世界写成 NOT_STARTED）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return true = 该项目对应世界就是当前挂载世界
     */
    private boolean isThisWorldMounted(Long projectId) {
        if (mountedWorld == null) {
            return true;
        }
        String uid = mountedWorld.uid();
        if (uid == null) {
            return false;
        }
        try {
            return worldIndexDao.readById(projectId)
                    .map(row -> uid.equals(row.worldUid()))
                    .orElse(false);
        } catch (Exception e) {
            log.debug("[世界初始化状态] 读取世界 uid 失败 project={}：{}", projectId, e.toString());
            return false;
        }
    }

    /** 取（或新建）可写状态 */
    private StepState stateForWrite(Long projectId, WorldInitStep step) {
        StepState st = loadStates(projectId).get(step.code());
        return st == null ? new StepState() : st;
    }

    /** 写单步状态（失败只告警：不能因为"记不下状态"把用户的初始化结果丢掉） */
    private void saveState(Long projectId, WorldInitStep step, StepState st) {
        if (worldIndexDao == null) {
            return;
        }
        Map<String, StepState> states = loadStates(projectId);
        states.put(step.code(), st);
        persist(projectId, states);
    }

    /** 序列化并落库（异常只告警） */
    private void persist(Long projectId, Map<String, StepState> states) {
        if (worldIndexDao == null) {
            return;
        }
        try {
            ObjectNode root = MAPPER.createObjectNode();
            states.forEach((code, st) -> {
                ObjectNode node = root.putObject(code);
                if (st.status != null) {
                    node.put(K_STATUS, st.status);
                }
                if (st.count != null) {
                    node.put(K_COUNT, st.count);
                }
                if (st.message != null) {
                    node.put(K_MESSAGE, st.message);
                }
                if (st.attempts != null) {
                    node.put(K_ATTEMPTS, st.attempts);
                }
                if (st.finishedAt != null) {
                    node.put(K_FINISHED, st.finishedAt.toString());
                }
            });
            worldIndexDao.updateInitSteps(projectId, root.toString());
        } catch (Exception e) {
            log.warn("[世界初始化状态] 步骤明细保存失败 project={}：{}", projectId, e.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 单步状态（对应 init_steps JSON 里的一个键） */
    private static final class StepState {
        /** PENDING/RUNNING/DONE/FAILED/SKIPPED */
        String status;
        /** 本次产出条数 */
        Integer count;
        /** 面向用户的一句话（失败原因 / 成功摘要） */
        String message;
        /** 尝试次数（重跑递增） */
        Integer attempts;
        /** 完成时间 */
        LocalDateTime finishedAt;
    }
}
