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

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.growth.config.GrowthProperties;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.stage.GrowthStage;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import tools.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成长档案补齐（开局家族建档第一步 · M1）。
 *
 * <p><b>解决什么问题</b>：世界里大量角色 `gender / age / birth_game_second / stage` 四列全空
 * （实测 world 33：9 个主要角色全 NULL，只有 3 个普通 NPC 有性别年龄）。后果是连锁的——
 * {@code MarriageService.romanceEligible()} 里 {@code age == null ? 0 : age} 恒小于法定婚龄 18，
 * 于是**永远不会结婚**，进而永远没有婚姻边、没有子嗣、没有亲子边，家族谱系永远是空树。</p>
 *
 * <p><b>补齐口径</b>（只填空值，已有值一律不覆盖）：
 * <ol>
 *   <li>{@code gender}：先看称谓/身份词（老板娘、老汉、婶子…），再看名字用字倾向，最后按
 *       {@code seed} 奇偶确定性兜底——**同一角色任何一次补齐结果都一样**（幂等可复现）；</li>
 *   <li>{@code age}：先看名字前缀（老/翁 → 六七十；小/娃 → 十来岁；阿X → 二十上下；伯/叔 → 五十上下），
 *       再按重要度（主角/重要/次要/路人）分档，最后加 ±3 的确定性抖动；</li>
 *   <li>{@code birth_game_second} = 当前世界秒 − 年龄 × {@code growth.aging.gameSecondsPerYear}；
 *       允许为负（世界 0 时刻之前出生，与 {@code DevelopmentalStageService.fastForwardAge} 同口径）；</li>
 *   <li>{@code stage} = {@link GrowthStage#ofAge(int)}。</li>
 * </ol></p>
 *
 * <p><b>AI 精修（可选，失败零阻断）</b>：装配了 {@link AiSpringChatGateway} 且传入了 userId 时，
 * 一次性把这批「待补角色」的姓名/身份/档案摘要发给模型，要求回
 * {@code {"people":[{"name","gender","age"}]}}；解析成功则以 AI 结果为准（比字符表准得多），
 * 失败/超时/返回残缺都退回确定性推断。<b>AI 调用不在任何事务里</b>（AGENTS 8.1.1）。</p>
 *
 * <p><b>为什么不写在 {@code WorldGenCharacterService} 里</b>：那条链路只管"从 roster 落库"，
 * 而这次要补的是**所有世界的存量角色**（含手工建的、导入的、世界初始化中断的）。
 * 补齐必须是独立、可重入、可对任意项目执行的一步，所以单独成服务，由
 * {@code FamilyGenesisService} 编排、{@code FamilyBackfillRunner} 兜底调用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileCompletionService {

    /** 人设推断场景码（PromptSpecRegistry 登记 + PromptTemplateService 内置模板）。 */
    public static final String SCENE_PROFILE_INFER = "growth_family_profile_infer";

    /** 女性倾向字（姓名用字，出现即加分；只用于"没有任何其它线索"时的兜底）。 */
    private static final String FEMALE_CHARS = "婷娟芳丽燕婉晴芷萤苔娥娘婶婆姑嫂妹姐兰菊梅莲荷珍珠玉秀云霜雪月桃杏香春秋霞玲珊琦璐瑶萱颖慧淑静雅曼妮娃嫣妍姬媛妤娇黛钗环佩绣绮绫罗裙裳妆脂粉";
    /** 男性倾向字。 */
    private static final String MALE_CHARS = "强伟军明铁柱远守拙伯叔公翁爷父兄弟刚勇峰磊涛辉建国志华山河海龙虎彪雄杰豪鹏飞兵平安福贵财宝根生德仁义礼智信忠孝文武政堂石岩松柏栋梁柱墩锁栓柱铁钢铜";

    /** 女性称谓词（身份/头衔里出现 → 直接判女）。 */
    private static final String[] FEMALE_WORDS = {"老板娘", "掌柜娘", "母亲", "妈妈", "姑娘", "姐姐", "妹妹", "奶奶",
            "婆婆", "婶", "姨", "姑", "嫂", "媳妇", "女儿", "姐妹", "少女", "妇人", "寡妇", "婆姨"};
    /** 男性称谓词。 */
    private static final String[] MALE_WORDS = {"父亲", "爸爸", "爷爷", "老汉", "老头", "掌柜", "大叔", "先生",
            "兄弟", "哥哥", "弟弟", "儿子", "少年", "汉子", "猎户", "渔夫", "船夫", "铁匠", "木匠", "族长", "里正"};

    /** 年龄合法区间（与 {@code GrowthStage} 上限对齐，避免出现 200 岁怪物）。 */
    private static final int AGE_MIN = 6;
    private static final int AGE_MAX = 88;

    private final ActorCharacterRepository characterRepository;
    private final GrowthProperties growthProperties;

    /** AI 网关（未装配 → 全走确定性推断；单测直接构造时不需要）。 */
    @Autowired(required = false)
    private AiSpringChatGateway aiChatGateway;
    /** 提示词模板（AI 精修用）。 */
    @Autowired(required = false)
    private PromptTemplateService promptTemplateService;
    /** 用量入账（AI 精修用）。 */
    @Autowired(required = false)
    private UsageLogService usageLogService;

    /**
     * 补齐一批角色的成长档案（只填空值）。
     *
     * @param userId 归属用户（null → 不做 AI 精修，例如应用启动补齐时没有登录态）
     *
     * @param projectId 项目 ID
     *
     * @param characters 待补角色（调用方已按 deleted=0 过滤）
     *
     * @param gameSecond 当前世界秒（用于推算出生时刻）
     *
     * @return 本次实际补齐的角色数（一个角色只要有任一字段被补就计 1）
     */
    public int complete(Long userId, Long projectId, List<ActorCharacter> characters, Long gameSecond) {
        if (projectId == null || characters == null || characters.isEmpty()) {
            return 0;
        }
        List<ActorCharacter> need = new ArrayList<>();
        List<ActorCharacter> needAi = new ArrayList<>();
        for (ActorCharacter ch : characters) {
            if (ch == null || ch.getId() == null) {
                continue;
            }
            // 四件套任一缺失都算不完整：改前只认 gender/age，导致「已有性别和年龄、但阶段/出生时刻为空」的
            // 角色被整条跳过（实测阿苔 女28 岁却永远没有阶段，家族树显示「女 — 28 岁」）。
            boolean blankProfile = isBlank(ch.getGender()) || ch.getAge() == null;
            if (blankProfile) {
                need.add(ch);
                needAi.add(ch);
            } else if (isBlank(ch.getStage()) || ch.getBirthGameSecond() == null) {
                need.add(ch);
            }
        }
        if (need.isEmpty()) {
            return 0;
        }
        // ① AI 精修（事务外，失败零阻断）：只送「真的缺性别/年龄」的人，缺阶段的不必花 token
        Map<String, InferResult> ai = needAi.isEmpty()
                ? java.util.Collections.emptyMap()
                : refineByAi(userId, projectId, needAi, gameSecond);
        // ② 落库：AI 结果优先，缺的用确定性推断
        int fixed = 0;
        long yearSeconds = growthProperties.getAging().getGameSecondsPerYear();
        for (ActorCharacter ch : need) {
            boolean touched = false;
            InferResult hint = ai.get(ch.getName());
            if (isBlank(ch.getGender())) {
                String gender = hint != null && !isBlank(hint.gender()) ? hint.gender() : inferGender(ch, projectId);
                ch.setGender(gender);
                touched = true;
            }
            if (ch.getAge() == null) {
                Integer age = hint != null && hint.age() != null ? hint.age() : inferAge(ch, projectId);
                ch.setAge(age);
                touched = true;
            }
            Integer age = ch.getAge() == null ? AGE_MIN : ch.getAge();
            if (ch.getBirthGameSecond() == null) {
                ch.setBirthGameSecond((gameSecond == null ? 0L : gameSecond) - age * yearSeconds);
                touched = true;
            }
            if (isBlank(ch.getStage())) {
                ch.setStage(GrowthStage.ofAge(age).name());
                touched = true;
            }
            if (!touched) {
                continue;
            }
            try {
                characterRepository.save(ch);
                fixed++;
            } catch (Exception e) {
                log.warn("[家族] 成长档案落库失败（跳过）: {}({}) 原因={}", ch.getName(), ch.npcRef(), e.getMessage());
            }
        }
        if (fixed > 0) {
            log.info("[家族] 成长档案补齐完成：project={}, 角色 {} 个（AI 精修 {} 个）",
                    projectId, fixed, ai.size());
        }
        return fixed;
    }

    /**
     * AI 精修：一次性把这批角色交给模型判定性别与年龄。
     *
     * @param userId 归属用户（null 直接跳过）
     *
     * @param projectId 项目 ID
     *
     * @param need 待补角色
     *
     * @param gameSecond 世界秒（暂无用途，保留给后续"按时代推断年龄"）
     *
     * @return 姓名 → 推断结果（失败返回空 Map）
     */
    private Map<String, InferResult> refineByAi(Long userId, Long projectId, List<ActorCharacter> need, Long gameSecond) {
        Map<String, InferResult> out = new LinkedHashMap<>();
        if (userId == null || aiChatGateway == null || promptTemplateService == null) {
            return out;
        }
        try {
            StringBuilder list = new StringBuilder();
            for (ActorCharacter ch : need) {
                list.append("- ").append(nz(ch.getName()))
                        .append("｜身份：").append(nz(ch.getTitle()))
                        .append("｜职业：").append(nz(ch.getOccupation()))
                        .append("｜所属：").append(nz(ch.getAffiliation()))
                        .append("｜档案摘要：").append(excerpt(ch.getDetail(), 160))
                        .append('\n');
            }
            Map<String, Object> ph = new LinkedHashMap<>();
            ph.put("people", list.toString());
            String prompt = promptTemplateService.render(userId, projectId, SCENE_PROFILE_INFER, ph);
            String system = promptTemplateService.systemMessage(userId, projectId, SCENE_PROFILE_INFER, null);
            if (system == null || system.isBlank()) {
                system = "你是角色档案整理员，只输出 JSON，不要任何解释。";
            }
            AiChatRequest req = new AiChatRequest(null, List.of(
                    new AiChatRequest.ChatMessage("system", system),
                    new AiChatRequest.ChatMessage("user", prompt)),
                    com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry.temperatureOf(SCENE_PROFILE_INFER),
                    com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry.maxOutputTokensOf(SCENE_PROFILE_INFER),
                    true);
            AiChatResult result = AiCallContext.run(SCENE_PROFILE_INFER, null, 1,
                    () -> aiChatGateway.chatCompletion(userId, projectId, null, req));
            if (result == null || result.content() == null || result.content().isBlank()) {
                return out;
            }
            recordUsage(userId, projectId, result);
            JsonNode root = PersonaJson.mapper().readTree(stripFence(result.content()));
            JsonNode people = root.path("people");
            if (!people.isArray()) {
                return out;
            }
            for (JsonNode p : people) {
                String name = p.path("name").asText("").trim();
                if (name.isBlank()) {
                    continue;
                }
                String gender = p.path("gender").asText("").trim();
                if (gender.startsWith("m") || gender.startsWith("M") || gender.contains("男")) {
                    gender = "男";
                } else if (gender.startsWith("f") || gender.startsWith("F") || gender.contains("女")) {
                    gender = "女";
                } else {
                    gender = "";
                }
                Integer age = p.path("age").isNumber() ? clampAge(p.path("age").asInt()) : null;
                out.put(name, new InferResult(gender, age));
            }
            log.info("[家族] AI 精修档案：project={}, 命中 {} 个角色", projectId, out.size());
        } catch (Exception e) {
            log.warn("[家族] AI 精修档案失败（退回确定性推断）: project={} 原因={}", projectId, e.getMessage());
            out.clear();
        }
        return out;
    }

    /** 用量入账（失败只告警）。 */
    private void recordUsage(Long userId, Long projectId, AiChatResult result) {
        if (usageLogService == null) {
            return;
        }
        try {
            usageLogService.record(userId, projectId, null, result.providerId(), result.model(),
                    SCENE_PROFILE_INFER, result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
        } catch (Exception e) {
            log.warn("[家族] 档案精修用量入账失败（忽略）: {}", e.getMessage());
        }
    }

    //
    // 确定性推断（纯函数，包内可见供单测直接断言）
    //

    /**
     * 确定性性别推断：称谓词 → 姓名用字 → seed 奇偶。
     *
     * @param ch 角色
     *
     * @param seed 种子（通常 projectId），保证同一角色结果稳定
     *
     * @return {@code 男} / {@code 女}
     */
    static String inferGender(ActorCharacter ch, Long seed) {
        String text = nz(ch.getTitle()) + "｜" + nz(ch.getOccupation()) + "｜" + nz(ch.getAffiliation())
                + "｜" + excerpt(ch.getDetail(), 400);
        for (String w : FEMALE_WORDS) {
            if (text.contains(w)) {
                return "女";
            }
        }
        for (String w : MALE_WORDS) {
            if (text.contains(w)) {
                return "男";
            }
        }
        String name = nz(ch.getName());
        int female = 0;
        int male = 0;
        for (int i = 0; i < name.length(); i++) {
            String c = String.valueOf(name.charAt(i));
            if (FEMALE_CHARS.contains(c)) {
                female++;
            }
            if (MALE_CHARS.contains(c)) {
                male++;
            }
        }
        if (female > male) {
            return "女";
        }
        if (male > female) {
            return "男";
        }
        long h = (seed == null ? 0L : seed) * 31L + name.hashCode();
        return (h & 1L) == 0L ? "男" : "女";
    }

    /**
     * 确定性年龄推断：明确先验（称谓/姓名前缀）→ 村庄年龄分布 → 确定性取模。
     *
     * <p><b>为什么不用"按重要度给固定年龄"</b>：那样全世界的主要角色都落在 34~54 岁这一段，
     * 代际分层里全挤进同一带，结果是既没有老人也没有孩子、谁都不是谁的父母
     * （world 33 首次补齐实测：12 个角色只配出 2 对夫妻、1 条亲子边）。
     * 改成按村庄人口结构铺开（儿童/青年/壮年/中年/老年都有），家族才立得起来。</p>
     *
     * @param ch 角色
     *
     * @param seed 种子（通常 projectId）
     *
     * @return 年龄（{@value #AGE_MIN}~{@value #AGE_MAX}）
     */
    static int inferAge(ActorCharacter ch, Long seed) {
        String name = nz(ch.getName());
        String text = nz(ch.getTitle()) + nz(ch.getOccupation());
        long h = (seed == null ? 0L : seed) * 131L + name.hashCode();
        int r = (int) Math.floorMod(h, 100L);
        // ① 明确先验：称谓/名号已经把年龄段说死了
        if (name.startsWith("老") || name.contains("翁") || name.contains("婆") || name.contains("公")
                || text.contains("老汉") || text.contains("老太太") || text.contains("族长") || text.contains("里正")) {
            return clampAge(58 + r % 17);
        }
        if (name.startsWith("小") || name.contains("娃") || name.contains("童") || name.contains("儿")
                || text.contains("少年") || text.contains("少女") || text.contains("学徒")) {
            return clampAge(7 + r % 10);
        }
        if (isNickPrefixName(name)) {
            // 阿澈 / 阿苔 这类小名：多为青壮年
            return clampAge(18 + r % 12);
        }
        if (name.contains("伯") || name.contains("叔") || name.contains("婶") || name.contains("姨")
                || name.contains("姑")) {
            return clampAge(44 + r % 14);
        }
        if (text.contains("掌柜") || text.contains("先生") || text.contains("大夫") || text.contains("教书")) {
            return clampAge(38 + r % 16);
        }
        // ② 一般人群：按"一个村子该有的人口结构"铺开
        if (r < 10) {
            return clampAge(8 + r);
        }
        if (r < 32) {
            return clampAge(18 + r % 8);
        }
        if (r < 68) {
            return clampAge(26 + r % 15);
        }
        if (r < 90) {
            return clampAge(41 + r % 15);
        }
        return clampAge(56 + r % 15);
    }

    /** 是否"阿X"式小名（两个字且以「阿」开头）。 */
    static boolean isNickPrefixName(String name) {
        return name != null && name.length() == 2 && name.charAt(0) == '阿';
    }

    /** 年龄夹取到合法区间。 */
    static int clampAge(int age) {
        return Math.max(AGE_MIN, Math.min(AGE_MAX, age));
    }

    /** 去掉 AI 可能包上的 ```json 围栏。 */
    private static String stripFence(String text) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl > 0 ? t.substring(nl + 1) : t;
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
        }
        return t.trim();
    }

    /** 取文本前 n 个字符（null 安全）。 */
    private static String excerpt(String text, int n) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= n ? t : t.substring(0, n);
    }

    /** null 安全字符串。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 空白判定。 */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * AI 精修结果。
     *
     * @param gender {@code 男/女}（空串表示 AI 未给出）
     *
     * @param age 年龄（null 表示 AI 未给出）
     */
    record InferResult(String gender, Integer age) {
    }
}
