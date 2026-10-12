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

package com.holzyn.actor.domain.npc.state.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.npc.state.config.NpcStateProperties;
import com.holzyn.actor.domain.settings.entity.ActorSetting;
import com.holzyn.actor.domain.settings.repository.ActorSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * NPC 状态模块调参服务 ——运行时参数覆盖引擎。
 * <p>职责：调参面板把衰减率/阈值/恢复量等 {@link NpcStateProperties}
 * 全量参数以「配置路径即键名」（如 {@code decay.hunger-per-hour}）写入全局覆盖表
 * {@code actor_setting}，并<b>实时应用到 {@link NpcStateProperties} bean</b>（启动加载 + 运行即时生效）。</p>
 * <p>设计约定：</p>
 * <ul>
 * <li><b>生效值 = 覆盖值 ∪ 默认值</b>（覆盖优先；未覆盖时取 application.yml 绑定默认值）；</li>
 * <li><b>全局级</b>覆盖（不绑 project_id，跨项目生效）；</li>
 * <li><b>启动加载</b>：{@link #init} 快照默认值后加载 DB 覆盖应用到 bean；
 * <b>运行实时</b>：save 写库 + 立即应用（同一 singleton bean 实例，消费方即时读到新值）；</li>
 * <li><b>重置</b>：单键重置（恢复默认 + 删覆盖）/ 一键全部恢复默认；</li>
 * <li><b>键表</b>：全量 NpcStateProperties 参数（衰减/urgency/健康/恢复/情绪/注入/驱动/历史）。</li>
 * </ul>
 * <p>设计依据：《NPC状态模块_主开发方案》§11（配置表）/ §12 （调参）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NpcStateTuningService {

    /** 覆盖表键名前缀（区别于其他设置；实际键 = 配置路径，如 decay.hunger-per-hour） */
    public static final String SETTING_PREFIX = "npc-state.";

    private final NpcStateProperties props;
    private final ActorSettingRepository settingRepository;

    /** 可调参数注册表（key=配置路径 → 元数据 + getter/setter） */
    private final Map<String, TunableKey> keys = new LinkedHashMap<>();

    /** 启动时快照的默认值（key → 字符串；应用覆盖前抓取，供单键重置恢复默认） */
    private final Map<String, String> baseDefaults = new LinkedHashMap<>();

    /** 可调参数元数据 */
    public record TunableKey(String key, String group, String label, String type,
                             Function<NpcStateProperties, Object> getter,
                             BiConsumer<NpcStateProperties, String> setter) {
    }

    /** 调参面板行（含生效值/默认值/是否覆盖） */
    public record TuningRow(String key, String group, String label, String type,
                            String value, String defaultValue, boolean overridden) {
    }

    /**
     * 启动初始化：注册全量可调参数 → 快照默认值 → 加载 DB 覆盖应用。
     * <p>时序：{@code NpcStateProperties} 已由 Spring 绑定 application.yml（默认值就位），
     * 此处先快照再应用覆盖——保证「重置回默认」永远回到 yml 默认而非覆盖值。</p>
     */
    @PostConstruct
    void init() {
        registerAll();
        keys.forEach((k, t) -> baseDefaults.put(k, stringify(t.getter().apply(props))));
        // 启动加载 DB 覆盖（幂等：键未注册/值非法跳过并告警）
        List<ActorSetting> overrides = settingRepository.findAllByOrderByIdAsc();
        for (ActorSetting s : overrides) {
            String key = stripPrefix(s.getSettingKey());
            if (key == null || !keys.containsKey(key)) {
                log.warn("[状态调参] 启动跳过未注册键: {}", s.getSettingKey());
                continue;
            }
            applyValue(key, s.getSettingValue());
        }
        if (!overrides.isEmpty()) {
            log.info("[状态调参] 启动加载 {} 条覆盖生效（npc-state.*）", overrides.size());
        }
    }

    // ==================== 读接口（调参面板数据源） ====================

    /**
     * 全量可调参数当前状态（生效值 + 默认值 + 是否覆盖）。
     *
     * @return 调参面板行列表（按注册顺序分组稳定排列）
     */
    @Transactional(readOnly = true)
    public List<TuningRow> effective() {
        List<TuningRow> rows = new ArrayList<>();
        for (TunableKey t : keys.values()) {
            String value = stringify(t.getter().apply(props));
            String def = baseDefaults.getOrDefault(t.key(), "");
            boolean overridden = settingRepository.existsBySettingKey(SETTING_PREFIX + t.key());
            rows.add(new TuningRow(t.key(), t.group(), t.label(), t.type(), value, def, overridden));
        }
        return rows;
    }

    // ==================== 写接口（保存/重置） ====================

    /**
     * 保存单个参数覆盖（写库 + 实时应用）。
     *
     * @param key 配置路径（如 decay.hunger-per-hour）
     *
     * @param value 新值字符串（按类型解析）
     *
     * @return 应用后的生效值字符串
     */
    @Transactional
    public String save(String key, String value) {
        TunableKey t = requireKey(key);
        if (value == null || value.isBlank()) {
            throw new BizException(400, "参数值不能为空：" + key);
        }
        applyValue(key, value); // 先校验+应用（非法值抛 400）
        ActorSetting setting = settingRepository.findBySettingKey(SETTING_PREFIX + key).orElseGet(ActorSetting::new);
        setting.setSettingKey(SETTING_PREFIX + key);
        setting.setSettingValue(value);
        setting.setDescription(t.group() + " · " + t.label());
        settingRepository.save(setting);
        log.info("[状态调参] 保存覆盖：{} = {}（原默认 {}）", key, value, baseDefaults.getOrDefault(key, ""));
        return stringify(t.getter().apply(props));
    }

    /**
     * 单键重置（恢复默认 + 删除覆盖）。
     *
     * @param key 配置路径
     *
     * @return 恢复后的默认值字符串
     */
    @Transactional
    public String reset(String key) {
        TunableKey t = requireKey(key);
        settingRepository.deleteBySettingKey(SETTING_PREFIX + key);
        String def = baseDefaults.getOrDefault(key, "");
        if (def != null && !def.isEmpty()) {
            t.setter().accept(props, def); // 恢复默认
        }
        log.info("[状态调参] 单键重置：{} 恢复默认 {}", key, def);
        return def;
    }

    /**
     * 一键全部恢复默认（清空全部覆盖 + bean 重置为快照默认值）。
     *
     * @return 重置的覆盖条数
     */
    @Transactional
    public int resetAll() {
        List<ActorSetting> overrides = settingRepository.findAllByOrderByIdAsc();
        settingRepository.deleteAll(overrides);
        for (TunableKey t : keys.values()) {
            String def = baseDefaults.getOrDefault(t.key(), "");
            if (def != null && !def.isEmpty()) {
                t.setter().accept(props, def);
            }
        }
        log.info("[状态调参] 一键全部恢复默认（{} 条覆盖已清除）", overrides.size());
        return overrides.size();
    }

    /** 校验并取可调参数（未注册抛 400） */
    private TunableKey requireKey(String key) {
        if (key == null || !keys.containsKey(key)) {
            throw new BizException(400, "未知可调参数：" + key);
        }
        return keys.get(key);
    }

    /** 应用单个覆盖值到 bean（值非法抛 400；不落库——由 save 负责） */
    private void applyValue(String key, String value) {
        TunableKey t = requireKey(key);
        try {
            t.setter().accept(props, value);
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "参数值格式非法：" + key + " = " + value + "（应为 " + t.type() + "）");
        }
    }

    /** 值 → 字符串（null → 空串） */
    private static String stringify(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 去掉 SETTING_PREFIX 前缀（容错：无前缀原样返回） */
    private static String stripPrefix(String key) {
        if (key == null) {
            return null;
        }
        return key.startsWith(SETTING_PREFIX) ? key.substring(SETTING_PREFIX.length()) : key;
    }

    // ==================== 可调参数注册表（全量 NpcStateProperties） ====================

    /**
     * 注册全部可调参数。
     * <p>分组：decay 衰减 / urgency 紧急 / health 健康 / recovery 恢复 / emotion 情绪 /
     * inject 注入 / drive 驱动 / history 历史 / misc 其他。</p>
     */
    private void registerAll() {
        // ===== 衰减 decay =====
        dec("decay.hunger-per-hour", "decay", "饥饿衰减 /游戏小时", "double",
                p -> p.getDecay().getHungerPerHour(),
                (p, v) -> p.getDecay().setHungerPerHour(Double.parseDouble(v)));
        dec("decay.thirst-per-hour", "decay", "口渴衰减 /游戏小时", "double",
                p -> p.getDecay().getThirstPerHour(),
                (p, v) -> p.getDecay().setThirstPerHour(Double.parseDouble(v)));
        dec("decay.energy-per-hour", "decay", "精力衰减 /游戏小时", "double",
                p -> p.getDecay().getEnergyPerHour(),
                (p, v) -> p.getDecay().setEnergyPerHour(Double.parseDouble(v)));
        dec("decay.bladder-per-hour", "decay", "膀胱衰减 /游戏小时", "double",
                p -> p.getDecay().getBladderPerHour(),
                (p, v) -> p.getDecay().setBladderPerHour(Double.parseDouble(v)));
        dec("decay.hygiene-per-hour", "decay", "清洁衰减 /游戏小时", "double",
                p -> p.getDecay().getHygienePerHour(),
                (p, v) -> p.getDecay().setHygienePerHour(Double.parseDouble(v)));
        dec("decay.social-per-hour", "decay", "社交衰减 /游戏小时", "double",
                p -> p.getDecay().getSocialPerHour(),
                (p, v) -> p.getDecay().setSocialPerHour(Double.parseDouble(v)));
        dec("decay.fun-per-hour", "decay", "趣味衰减 /游戏小时", "double",
                p -> p.getDecay().getFunPerHour(),
                (p, v) -> p.getDecay().setFunPerHour(Double.parseDouble(v)));
        dec("decay.sleep-restore-per-hour", "decay", "睡眠精力恢复 /游戏小时", "double",
                p -> p.getDecay().getSleepRestorePerHour(),
                (p, v) -> p.getDecay().setSleepRestorePerHour(Double.parseDouble(v)));
        dec("decay.taper", "decay", "低值区衰减放缓开关", "boolean",
                p -> p.getDecay().isTaper(),
                (p, v) -> p.getDecay().setTaper(Boolean.parseBoolean(v)));
        dec("decay.taper-threshold", "decay", "低值放缓阈值", "double",
                p -> p.getDecay().getTaperThreshold(),
                (p, v) -> p.getDecay().setTaperThreshold(Double.parseDouble(v)));
        // ===== 其他 =====
        dec("crowd-decay-scale", "misc", "人群衰减整体缩放", "double",
                p -> p.getCrowdDecayScale(),
                (p, v) -> p.setCrowdDecayScale(Double.parseDouble(v)));
        dec("sleep-start-hour", "misc", "睡眠时段开始小时", "int",
                p -> p.getSleepStartHour(),
                (p, v) -> p.setSleepStartHour(Integer.parseInt(v)));
        dec("sleep-end-hour", "misc", "睡眠时段结束小时", "int",
                p -> p.getSleepEndHour(),
                (p, v) -> p.setSleepEndHour(Integer.parseInt(v)));
        dec("ai-crowd-threshold", "misc", "人群 AI 决策阈值（场景人数≤）", "int",
                p -> p.getAiCrowdThreshold(),
                (p, v) -> p.setAiCrowdThreshold(Integer.parseInt(v)));
        //
        // ===== urgency =====
        dec("urgency.threshold-strong", "urgency", "强烈紧急阈值（需求<）", "double",
                p -> p.getUrgency().getThresholdStrong(),
                (p, v) -> p.getUrgency().setThresholdStrong(Double.parseDouble(v)));
        dec("urgency.threshold-mild", "urgency", "轻度紧急阈值（需求<）", "double",
                p -> p.getUrgency().getThresholdMild(),
                (p, v) -> p.getUrgency().setThresholdMild(Double.parseDouble(v)));
        dec("urgency.health-alert", "urgency", "健康告急阈值（health<）", "double",
                p -> p.getUrgency().getHealthAlert(),
                (p, v) -> p.getUrgency().setHealthAlert(Double.parseDouble(v)));
        //
        // ===== health =====
        dec("health.corrosion-rate", "health", "健康侵蚀速率 /游戏小时", "double",
                p -> p.getHealth().getCorrosionRate(),
                (p, v) -> p.getHealth().setCorrosionRate(Double.parseDouble(v)));
        dec("health.recover-rate", "health", "健康回升速率 /游戏小时", "double",
                p -> p.getHealth().getRecoverRate(),
                (p, v) -> p.getHealth().setRecoverRate(Double.parseDouble(v)));
        dec("health.corrosion-threshold", "health", "健康侵蚀判定阈值", "double",
                p -> p.getHealth().getCorrosionThreshold(),
                (p, v) -> p.getHealth().setCorrosionThreshold(Double.parseDouble(v)));
        dec("health.recover-threshold", "health", "健康回升判定阈值", "double",
                p -> p.getHealth().getRecoverThreshold(),
                (p, v) -> p.getHealth().setRecoverThreshold(Double.parseDouble(v)));
        //
        // ===== recovery =====
        dec("recovery.eat-hunger", "recovery", "进食恢复饥饿", "int",
                p -> p.getRecovery().getEatHunger(),
                (p, v) -> p.getRecovery().setEatHunger(Integer.parseInt(v)));
        dec("recovery.drink-thirst", "recovery", "饮水恢复口渴", "int",
                p -> p.getRecovery().getDrinkThirst(),
                (p, v) -> p.getRecovery().setDrinkThirst(Integer.parseInt(v)));
        dec("recovery.sleep-energy", "recovery", "睡眠恢复精力", "int",
                p -> p.getRecovery().getSleepEnergy(),
                (p, v) -> p.getRecovery().setSleepEnergy(Integer.parseInt(v)));
        dec("recovery.toilet-bladder", "recovery", "如厕恢复膀胱", "int",
                p -> p.getRecovery().getToiletBladder(),
                (p, v) -> p.getRecovery().setToiletBladder(Integer.parseInt(v)));
        dec("recovery.wash-hygiene", "recovery", "洗浴恢复清洁", "int",
                p -> p.getRecovery().getWashHygiene(),
                (p, v) -> p.getRecovery().setWashHygiene(Integer.parseInt(v)));
        dec("recovery.chat-social", "recovery", "交谈恢复社交", "int",
                p -> p.getRecovery().getChatSocial(),
                (p, v) -> p.getRecovery().setChatSocial(Integer.parseInt(v)));
        dec("recovery.play-fun", "recovery", "娱乐恢复趣味", "int",
                p -> p.getRecovery().getPlayFun(),
                (p, v) -> p.getRecovery().setPlayFun(Integer.parseInt(v)));
        dec("recovery.treat-health", "recovery", "治疗恢复健康", "int",
                p -> p.getRecovery().getTreatHealth(),
                (p, v) -> p.getRecovery().setTreatHealth(Integer.parseInt(v)));
        dec("recovery.sleep-health", "recovery", "睡眠附带健康恢复", "int",
                p -> p.getRecovery().getSleepHealth(),
                (p, v) -> p.getRecovery().setSleepHealth(Integer.parseInt(v)));
        //
        // ===== emotion =====
        dec("emotion.ai-eval-enabled", "emotion", "AI 情绪评估开关", "boolean",
                p -> p.getEmotion().isAiEvalEnabled(),
                (p, v) -> p.getEmotion().setAiEvalEnabled(Boolean.parseBoolean(v)));
        dec("emotion.ai-eval-cooldown-hours", "emotion", "AI 评估冷却（游戏小时）", "double",
                p -> p.getEmotion().getAiEvalCooldownHours(),
                (p, v) -> p.getEmotion().setAiEvalCooldownHours(Double.parseDouble(v)));
        dec("emotion.eval-event-importance", "emotion", "事件触发评估重要度阈值", "int",
                p -> p.getEmotion().getEvalEventImportance(),
                (p, v) -> p.getEmotion().setEvalEventImportance(Integer.parseInt(v)));
        dec("emotion.decay-half-life-hours", "emotion", "情绪衰减半衰期（游戏小时）", "double",
                p -> p.getEmotion().getDecayHalfLifeHours(),
                (p, v) -> p.getEmotion().setDecayHalfLifeHours(Double.parseDouble(v)));
        dec("emotion.occ-enabled", "emotion", "OCC 三维评价开关", "boolean",
                p -> p.getEmotion().isOccEnabled(),
                (p, v) -> p.getEmotion().setOccEnabled(Boolean.parseBoolean(v)));
        dec("emotion.occ-desirability-weight", "emotion", "OCC 合意性权重", "double",
                p -> p.getEmotion().getOccDesirabilityWeight(),
                (p, v) -> p.getEmotion().setOccDesirabilityWeight(Double.parseDouble(v)));
        dec("emotion.occ-praiseworthiness-weight", "emotion", "OCC 称赞性权重", "double",
                p -> p.getEmotion().getOccPraiseworthinessWeight(),
                (p, v) -> p.getEmotion().setOccPraiseworthinessWeight(Double.parseDouble(v)));
        dec("emotion.occ-liking-weight", "emotion", "OCC 喜好性权重", "double",
                p -> p.getEmotion().getOccLikingWeight(),
                (p, v) -> p.getEmotion().setOccLikingWeight(Double.parseDouble(v)));
        dec("emotion.mood-weight", "emotion", "mood 情绪积分权重", "double",
                p -> p.getEmotion().getMoodWeight(),
                (p, v) -> p.getEmotion().setMoodWeight(Double.parseDouble(v)));
        dec("emotion.mood-synergy-threshold", "emotion", "心情协同阈值", "double",
                p -> p.getEmotion().getMoodSynergyThreshold(),
                (p, v) -> p.getEmotion().setMoodSynergyThreshold(Double.parseDouble(v)));
        dec("emotion.mood-synergy-factor", "emotion", "心情协同衰减系数", "double",
                p -> p.getEmotion().getMoodSynergyFactor(),
                (p, v) -> p.getEmotion().setMoodSynergyFactor(Double.parseDouble(v)));
        //
        // ===== inject =====
        dec("inject.state-budget", "inject", "状态段注入 token 预算", "int",
                p -> p.getInject().getStateBudget(),
                (p, v) -> p.getInject().setStateBudget(Integer.parseInt(v)));
        dec("inject.inject-ai-decision", "inject", "决策注入开关", "boolean",
                p -> p.getInject().isInjectAiDecision(),
                (p, v) -> p.getInject().setInjectAiDecision(Boolean.parseBoolean(v)));
        dec("inject.inject-dialog", "inject", "对话注入开关", "boolean",
                p -> p.getInject().isInjectDialog(),
                (p, v) -> p.getInject().setInjectDialog(Boolean.parseBoolean(v)));
        //
        // ===== drive =====
        dec("drive.crowd-probabilistic-enabled", "drive", "人群概率化驱动开关", "boolean",
                p -> p.getDrive().isCrowdProbabilisticEnabled(),
                (p, v) -> p.getDrive().setCrowdProbabilisticEnabled(Boolean.parseBoolean(v)));
        dec("drive.probabilistic-threshold", "drive", "概率化触发需求阈值", "double",
                p -> p.getDrive().getProbabilisticThreshold(),
                (p, v) -> p.getDrive().setProbabilisticThreshold(Double.parseDouble(v)));
        dec("drive.move-to-target", "drive", "概率化动作移动至目标地点", "boolean",
                p -> p.getDrive().isMoveToTarget(),
                (p, v) -> p.getDrive().setMoveToTarget(Boolean.parseBoolean(v)));
        //
        // ===== history =====
        dec("history.enabled", "history", "历史快照采样开关", "boolean",
                p -> p.getHistory().isEnabled(),
                (p, v) -> p.getHistory().setEnabled(Boolean.parseBoolean(v)));
        // （世界演化与游戏时间系统，）：采样粒度可配（默认 60=每小时，可切 5=细粒度演示；
        // 切 5 时保留上限经 History.effectiveMaxPerNpc 自动放大 168→2016）
        dec("history.sample-minutes", "history", "历史快照采样粒度（游戏分钟，60=每小时/5=细粒度）", "int",
                p -> p.getHistory().getSampleMinutes(),
                (p, v) -> p.getHistory().setSampleMinutes(Integer.parseInt(v)));
        dec("history.max-per-npc", "history", "每 NPC 快照保留条数", "int",
                p -> p.getHistory().getMaxPerNpc(),
                (p, v) -> p.getHistory().setMaxPerNpc(Integer.parseInt(v)));
        // ===== enabled（总开关） =====
        dec("enabled", "misc", "状态模块总开关", "boolean",
                p -> p.isEnabled(),
                (p, v) -> p.setEnabled(Boolean.parseBoolean(v)));
    }

    /** 注册辅助（key → TunableKey 入表） */
    private void dec(String key, String group, String label, String type,
                     Function<NpcStateProperties, Object> getter,
                     BiConsumer<NpcStateProperties, String> setter) {
        keys.put(key, new TunableKey(key, group, label, type, getter, setter));
    }
}
