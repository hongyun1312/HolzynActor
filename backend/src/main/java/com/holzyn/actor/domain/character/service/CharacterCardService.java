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

package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.character.vo.CharacterCardVO;
import com.holzyn.actor.domain.growth.persona.ActorProfileRevision;
import com.holzyn.actor.domain.growth.persona.PersonaBootstrapService;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.persona.PersonaRevisionService;
import com.holzyn.actor.domain.growth.persona.PersonaSchemaValidator;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.growth.persona.PersonaValidationResult;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 角色档案服务（A- 核心产物； 轨道 起统一到「档案服务」， 收口后生成路径
 * delegate 到两段式建档）。
 * <p><b>职责</b>：角色档案的<b>对外门面</b>——生成（{@link #generateCard}）、查询（{@link #latestCard} /
 * {@link #versionHistory}）、人工编辑（{@link #editCard}）。档案本身统一落在角色主表
 * {@code actor_profile} 的 {@code persona_json}/{@code persona_version} 列上，读写一律经
 * <p><b>改造要点（类名保留，语义已从"角色卡"转为"角色档案"）</b>：</p>
 * <ol>
 * <li><b>存储位置</b>：不再有 {@code actor_profile_card} 表与 {@code ActorCharacterCard} 实体——
 * 档案落在角色统一主表 {@code actor_profile} 的 {@code persona_json}/{@code persona_version} 列上，
 * 且由 {@link PersonaService} <b>按分维来源声明分派</b>：③ 生理基线 → {@code actor_state}、
 * ⑤ 目标 → {@code actor_goal}、⑥ 知识领域/盲区 → {@code actor_knowledge_doc}，其余留在档案列
 * （这就是"档案不独立于模块"：权威数据只有一份）。</li>
 * <li><b>system_prompt 取消</b>：档案不再在生成时定稿一段系统提示词，改为<b>运行时按场景渲染分片</b>
 * （{@code renderShardsForScene}：决策/单聊/群聊/计划/人群各取所需）。好处是档案一改，
 * 各场景 Prompt 自动跟着变，不会出现"档案更新了而缓存 prompt 还是旧的"的失配。</li>
 * <li><b>生成路径唯一化（收口）</b>：本类曾自己拼 Prompt + 调 AI + 剥 Markdown + 解析 JSON，
 * 与 {@link PersonaBootstrapService} 的"模板/解析/落库"三份实现重复。现在 {@link #generateCard}
 * <b>只做归属校验 + delegate</b>（两段式，2 次 AI 调用/人）；本类不再持有
 * Prompt/AI 网关/用量日志/当前用户等依赖——那些职责归建档服务。</li>
 * <li><b>零阻断</b>：档案无法有效产出时一律返回 {@code null}、<b>不抛异常</b>——世界初始化第 2 步是
 * 逐角色串行生成，任何一个角色的模型抖动都不允许把整批/整个世界初始化打断
 * （建档服务内部已按"失败 → 单段重试 → 放弃但保留已得维度"实现）。</li>
 * </ol>
 * <p><b>归属与凭据</b>：角色归属经 {@link CharacterService#requireOwned} 校验（本类唯一会抛出的异常）；
 * AI 调用的模型/凭据与用量入账由 {@code PersonaBootstrapService} 内部承担
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterCardService {

    /** 落库来源标记：用户在档案编辑器手动保存 */
    private static final String SOURCE_MANUAL = "manual";

    private final ActorCharacterRepository characterRepository;
    /** 角色档案服务 */
    private final PersonaService personaService;
    /** 档案契约校验器 */
    private final PersonaSchemaValidator personaSchemaValidator;
    /** 档案版本历史服务 */
    private final PersonaRevisionService personaRevisionService;
    private final CharacterService characterService;

    /**
     * 角色档案 AI 两段式建档服务（{@code PersonaBootstrapService}）：全系统唯一的 AI 建档链路。
     * <p>收口：{@link #generateCard} 不再自己调 AI + 解析（与建档服务的模板/解析/落库三份实现重复），
     * 改为 delegate 到本服务；成本口径 = <b>2 次 AI 调用/人</b>（段 A + 段 B）。</p>
     */
    private final PersonaBootstrapService personaBootstrapService;

    /**
     * 为单个角色生成（或重新生成）角色档案（归属校验）。
     * <p><b>收口：delegate 到两段式建档</b>——本方法不再自己拼 Prompt、调 AI、剥 Markdown、
     * 解析 JSON（{@code character_card_gen} 模板虽已迁 11 维 v2，但与建档服务重复），
     * 改为直接调用 {@link PersonaBootstrapService#bootstrap(Long, Long, String)}：
     * 段 A（①身份与社会 ②外貌与体质 ④气质与人格 ⑤价值观与动机 ⑨经历与创伤）→
     * 段 B（③生理基线 ⑥能力与知识 ⑦语言与表达 ⑧习惯与日常 ⑩发育·谱系 ⑪社会面具），
     * 每段落库都走 {@link PersonaService#savePersona}（③⑤⑥ 由它分派到 actor_state/actor_goal/
     * actor_knowledge_doc，其余留在 persona_json，落库前剥离 ③ 维与 ⑩.lineage）。</p>
     * <p><b>锚定文本</b>：用角色主表的 {@code detail}（开发者已写的角色详细信息）；
     * 为空时由建档服务自行回退骨架与世界观的锚定材料。</p>
     * <p><b>对外行为（保持不变）</b>：返回 {@link CharacterCardVO}（无有效档案时返回 null，
     * 控制器按 {@code R.ok(null)} 返回）；<b>不抛异常</b>——{@code PersonaBootstrapService} 自身零阻断
     * （AI 超时 / 非法 JSON / 维度全空 / 角色行不存在一律返回 null 并记 warn），
     * 因为世界初始化第 2 步是逐角色串行生成，任何一次模型抖动都不允许打断整批/整个世界初始化。
     * 唯一仍可能抛出的是 {@link CharacterService#requireOwned} 的归属校验失败（400/404，属调用方错误）。</p>
     * <p><b>成本口径</b>：两段式 = <b>2 次 AI 调用/人</b>（段 A + 段 B），
     * 场景码 {@code persona_bootstrap_fill_a / persona_bootstrap_fill_b}（用量与调试 trace 记这两个场景）。</p>
     *
     * @param characterId 角色主键
     *
     * @return 生成后的档案 VO（新版本）；两段式建档未产出有效档案时返回 null（调用方按"本次未生成"处理）
     */
    /**
     * 为单个角色生成（或重新生成）角色档案（归属校验）——<b>显式重新生成（force=true）</b>。
     * <p>与 {@link #generateCard(Long, boolean)} 的语义：本方法面向编辑器「重新生成」按钮等
     * <b>用户显式触发</b>的场景，走 force=true——无视 建档幂等开关，两段强制重跑。</p>
     * <p><b>事务说明（架构不变量， 固化进测试）</b>：本方法含 AI 调用（两段式建档），
     * <b>不带 {@code @Transactional}</b>——AI 期间不能持有 H2 行锁；落库由
     * {@code PersonaService.savePersona} 自身的短事务承担（含版本历史快照）。</p>
     *
     * @param characterId 角色主键
     *
     * @return 生成后的档案 VO（新版本）；两段式建档未产出有效档案时返回 null
     */
    public CharacterCardVO generateCard(Long characterId) {
        return generateCard(characterId, true);
    }

    /**
     * 为单个角色生成（或重新生成）角色档案（归属校验）——force 变体。
     * <p><b>force 语义</b>：</p>
     * <ul>
     * <li>{@code force=true}：显式重新生成（编辑器按钮），无视幂等开关，两段强制重跑；</li>
     * <li>{@code force=false}：批处理/启动补档路径（世界初始化全量/缺失补档、普通居民兜底），
     * 受 {@code holzyn.actor.growth.persona.bootstrap-idempotent}（默认 true）约束——
     * 该段维度已全覆盖则<b>复用既有档案、零 AI 调用</b>（恢复基线启动不再重跑 43 次人格引导）。</li>
     * </ul>
     * <p><b>事务说明</b>：含 AI 调用，本方法<b>不带 {@code @Transactional}</b>（架构不变量）。</p>
     *
     * @param characterId 角色主键
     *
     * @param force 是否强制重跑（true=重新生成；false=已覆盖则复用）
     *
     * @return 生成后的档案 VO（新版本）；未产出有效档案时返回 null
     */
    public CharacterCardVO generateCard(Long characterId, boolean force) {
        ActorCharacter character = characterService.requireOwned(characterId);
        long taskStart = System.currentTimeMillis();
        log.info("[档案生成] 任务开始（两段式建档{}）：角色ID={} 名称={}", force ? "·强制" : "·幂等", characterId, character.getName());
        // 唯一 AI 建档链路：段 A → 段 B（零阻断；本类不再持有 Prompt/AI/用量依赖）
        PersonaProfile profile = personaBootstrapService.bootstrap(
                character.getProjectId(), characterId, character.getDetail(), force);
        if (profile == null) {
            log.warn("[档案生成] 两段式建档未产出有效档案（已跳过、不阻断）：角色ID={}", characterId);
            return null;
        }
        // 建档成功：标记角色已建档（对外状态语义不变：档案列有内容即为已建档）
        // 修复（长跑建档缺陷）：bootstrap 内部已通过 savePersona 落库 persona_json / persona_version，
        // 而此处 character 是建档前加载的陈旧实例（personaJson=null、personaVersion=初值 1），
        // 直接 save 会触发 merge 全列覆盖，把这两列打回初值（persona 被清空，仅 revision 快照留存）。
        // 必须先重新读取最新行再落 status，避免陈旧实体覆盖已保存的档案。
        ActorCharacter fresh = characterService.requireOwned(characterId);
        fresh.setStatus(1);
        characterRepository.save(fresh);
        CharacterCardVO card = toVO(fresh, personaService.personaJsonOf(characterId));
        log.info("[档案生成] 任务结束：角色ID={} 名称={} 版本={} 耗时={}ms 成功",
                characterId, fresh.getName(), profile.getPersonaVersion(), System.currentTimeMillis() - taskStart);
        return card;
    }

    /**
     * 一键为项目全部角色生成档案（逐角色串行，<b>force=false 幂等</b>）。
     * <p>语义变更：本方法由世界初始化
     * {@code rebuild=true} 路径调用，原先会全量重新生成（恢复基线启动重跑 43 次 AI 引导）；现改为
     * 非 force——已覆盖角色复用既有档案，只有空档案/缺维角色才补建。单角色失败/跳过不影响其余。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 每个角色的生成结果列表（成功含 card，失败/跳过含 message）
     */
    public List<Map<String, Object>> generateAllCards(Long projectId) {
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        if (chars.isEmpty()) {
            throw new BizException(400, "该项目还没有角色，请先添加角色再生成角色档案");
        }
        long batchStart = System.currentTimeMillis();
        log.info("[档案-批量生成] 任务开始（幂等：已覆盖复用既有档案）：项目={} 角色数={}", projectId, chars.size());
        List<Map<String, Object>> results = new ArrayList<>();
        for (ActorCharacter c : chars) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("characterId", c.getId());
            item.put("characterName", c.getName());
            try {
                CharacterCardVO card = generateCard(c.getId(), false);
                if (card == null) {
                    item.put("success", false);
                    item.put("message", "AI 输出无法成为有效 11 维档案，本次跳过（详见服务端日志）");
                } else {
                    item.put("success", true);
                    item.put("card", card);
                }
            } catch (Exception e) {
                item.put("success", false);
                item.put("message", e.getMessage());
            }
            results.add(item);
        }
        log.info("[档案-批量生成] 任务结束：项目={} 角色数={} 耗时={}ms", projectId, chars.size(), System.currentTimeMillis() - batchStart);
        return results;
    }

    /**
     * 为项目「尚未生成档案」的角色批量生成（世界初始化第 2 步；跳过已生成，幂等）。
     * <p>与 generateAllCards 的区别：只处理尚无档案的角色（幂等），已有档案的角色跳过；
     * 单个角色失败不影响其余；返回每个角色的生成结果列表。</p>
     * <p>「是否已有档案」的判断用 {@code personaService.latestPersonaJson(id).isPresent()}（有内容即已建档）。
     * 本方法含 AI 调用（缺档补建），<b>不带 {@code @Transactional}</b>（架构不变量）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 处理结果列表（含 characterId/characterName/success/message；已生成跳过的行 success=false 且 skipped=true）
     */
    public List<Map<String, Object>> generateMissingCards(Long projectId) {
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        if (chars.isEmpty()) {
            log.info("[档案-缺失补生成] 项目={} 暂无角色，跳过", projectId);
            return List.of();
        }
        long batchStart = System.currentTimeMillis();
        log.info("[档案-缺失补生成] 任务开始：项目={} 角色数={}", projectId, chars.size());
        List<Map<String, Object>> results = new ArrayList<>();
        int generated = 0;
        for (ActorCharacter c : chars) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("characterId", c.getId());
            item.put("characterName", c.getName());
            if (personaService.latestPersonaJson(c.getId()).isPresent()) {
                item.put("success", false);
                item.put("skipped", true);
                item.put("message", "已生成，跳过");
                results.add(item);
                continue;
            }
            try {
                CharacterCardVO card = generateCard(c.getId(), false);
                if (card == null) {
                    item.put("success", false);
                    item.put("skipped", false);
                    item.put("message", "AI 输出无法成为有效 11 维档案，本次跳过（详见服务端日志）");
                    log.warn("[档案-缺失补生成] 角色 {} 档案解析为空，已跳过", c.getName());
                } else {
                    item.put("success", true);
                    item.put("card", card);
                    generated++;
                    log.info("[档案-缺失补生成] 角色 {} 档案生成成功", c.getName());
                }
            } catch (Exception e) {
                item.put("success", false);
                item.put("skipped", false);
                item.put("message", e.getMessage());
                log.warn("[档案-缺失补生成] 角色 {} 生成失败：{}", c.getName(), e.getMessage());
            }
            results.add(item);
        }
        log.info("[档案-缺失补生成] 任务结束：项目={} 角色数={} 新生成={} 耗时={}ms",
                projectId, chars.size(), generated, System.currentTimeMillis() - batchStart);
        return results;
    }

    /**
     * 查询角色最新档案（归属校验）。
     * <p><b>签名变更</b>：返回类型由历史 {@code Optional<ActorCharacterCard>} 改为
     * {@code Optional<CharacterCardVO>}——档案卡实体已删除，对外只剩"视图"这一种形态；
     * 调用方（控制器）因此可以直接返回，不再需要 {@code .map(CharacterCardVO::of)}。</p>
     *
     * @param characterId 角色主键
     *
     * @return 最新档案 VO；角色无档案（persona_json 为空）时 {@code Optional.empty()}
     */
    @Transactional(readOnly = true)
    public Optional<CharacterCardVO> latestCard(Long characterId) {
        characterService.requireOwned(characterId);
        return loadLatest(characterId);
    }

    /**
     * 查询角色档案版本历史（升序）。
     * <p><b>落地（版本历史表 {@code actor_profile_revision} 已建）</b>：每次档案落库
     * （AI 建档 / AI 补全 / 手动编辑 / 回滚）都会由 {@code PersonaService.savePersona} 追加一条快照，
     * 本方法按 profile_id 查全部修订行 → 逐行构造 VO 升序返回——<b>现在是真历史</b>（此前只有"最新一版"）。
     * 存量角色在版本表落地前已建档（版本号 ≥1 但无快照行）→ 快照为空时回退返回最新一版，前端不空窗。</p>
     *
     * @param characterId 角色主键
     *
     * @return 版本列表（升序；无档案时为空列表）
     */
    @Transactional(readOnly = true)
    public List<CharacterCardVO> versionHistory(Long characterId) {
        characterService.requireOwned(characterId);
        ActorCharacter ch = characterRepository.findById(characterId).orElse(null);
        if (ch == null) {
            return List.of();
        }
        List<ActorProfileRevision> revisions = personaRevisionService.listVersions(characterId);
        if (revisions.isEmpty()) {
            // 版本表落地前已建档的存量角色：无快照行，回退"最新一版"
            Optional<CharacterCardVO> latest = loadLatest(characterId);
            return latest.isPresent() ? List.of(latest.get()) : List.of();
        }
        List<CharacterCardVO> out = new ArrayList<>(revisions.size());
        for (ActorProfileRevision rev : revisions) {
            if (rev == null || rev.getPersonaJson() == null || rev.getPersonaJson().isBlank()) {
                continue;
            }
            // 旧版渲染文本按"旧版内容 + 当前模块投影"渲染（渲染规则只有后端一份实现；
            // ③ 生理基线等模块权威维取当前态——回看旧版档案时运行态本来就是"现在"）
            String renderText = personaService.renderFullOf(rev.getPersonaJson(), characterId);
            String source = rev.getGeneratedBy() != null ? rev.getGeneratedBy() : rev.getSource();
            out.add(new CharacterCardVO(ch.getId(), ch.getId(), ch.getName(), rev.getPersonaVersion(),
                    rev.getPersonaJson(), renderText, source, rev.getCreatedAt(), rev.getCreatedAt()));
        }
        return out;
    }

    /**
     * 手动编辑角色档案（保存为新版本，source=edited）—— 编辑器保存入口。
     * <p>入参只认 {@code personaJson}（11 维档案 JSON）。<b>校验口径（用户主动提交）</b>：
     * JSON 不可解析 → 400 提示重填；解析成功但 11 维结构化校验不过（schema_version 非法 / 数值越界 /
     * OCEAN 缺失等）→ 400 并逐条返回错误信息——编辑器是开发者改预设的入口，错误必须当场说清。
     * 校验通过后走 {@code PersonaService.savePersona}：③⑤⑥ 维按分维来源分派到各权威模块 +
     * 版本自增 + 版本历史快照。</p>
     * <p><b>事务说明</b>：本方法不含 AI 调用（纯解析/校验/落库），保留短事务语义。</p>
     *
     * @param characterId 角色主键
     *
     * @param body 编辑入参：{@code {personaJson}}（{@code systemPrompt} 已忽略）
     *
     * @return 编辑后的档案 VO；落库未生效返回 null
     */
    @Transactional
    public CharacterCardVO editCard(Long characterId, Map<String, String> body) {
        ActorCharacter character = characterService.requireOwned(characterId);
        String persona = body == null ? null : body.get("personaJson");
        if (persona == null || persona.isBlank()) {
            throw new BizException(400, "personaJson 不能为空");
        }
        PersonaProfile profile = PersonaJson.readQuietly(persona);
        if (profile == null) {
            throw new BizException(400, "角色档案 JSON 格式不正确，请检查后重试");
        }
        // ：11 维结构化校验（编辑是用户主动提交，错误当场告知；不校验会放行越界/缺 OCEAN 的脏档案）
        PersonaValidationResult validation = personaSchemaValidator.validate(profile, null);
        if (!validation.isValid()) {
            throw new BizException(400, "角色档案校验未通过：" + String.join("；", validation.getErrors()));
        }
        // 来源与场景码：手动编辑（generated_by=manual，便于渲染权限/审计区分 AI 与人工写入）
        profile.setGeneratedBy(SOURCE_MANUAL);
        profile.setGeneratedScene(SOURCE_MANUAL);
        // 落库仍走档案服务：③⑤⑥ 维同样按分维来源分派到各自权威模块，不在档案里留第二份
        int version = personaService.savePersona(characterId, profile, "edited");
        if (version <= 0) {
            log.warn("[档案编辑] 档案未落库（savePersona 返回 {}，已跳过、不阻断）：角色ID={}", version, characterId);
            return null;
        }
        log.info("[档案编辑] 已保存：角色ID={} 名称={} 版本={}", characterId, character.getName(), version);
        return toVO(character, personaService.personaJsonOf(characterId));
    }

    /**
     * AI 补全缺失维。
     * <p>delegate 到 {@code PersonaBootstrapService.completeMissingDims}——全系统唯一的 AI 建档链路；
     * 无缺失维 → 零 AI 调用直接返回当前档案；全失败返回 null（保留已得维度，可重试）。</p>
     * <p><b>事务说明</b>：含 AI 调用，本方法<b>不带 {@code @Transactional}</b>（架构不变量）。</p>
     *
     * @param characterId 角色主键
     *
     * @return 补全后的档案 VO；全失败返回 null
     */
    public CharacterCardVO completeMissingDims(Long characterId) {
        ActorCharacter character = characterService.requireOwned(characterId);
        PersonaProfile profile = personaBootstrapService.completeMissingDims(character.getProjectId(), characterId);
        if (profile == null) {
            log.warn("[档案补全] AI 补全缺失维未产出有效档案（保留已得维度，可重试）：角色ID={}", characterId);
            return null;
        }
        return toVO(character, personaService.personaJsonOf(characterId));
    }

    /**
     * 回滚到指定版本（保存为新版本，source=restored）。
     * <p>语义：取 {@code actor_profile_revision} 里目标版本快照的 {@code persona_json}，作为<b>新版本</b>
     * 重新保存（版本号继续自增）——只增不改原则不破坏，回滚动作本身也留一条历史，审计链完整。
     * 目标版本不存在 → 400（BizException）。</p>
     * <p><b>事务说明</b>：本方法不含 AI 调用（取快照 + 保存），保留短事务语义。</p>
     *
     * @param characterId 角色主键
     *
     * @param personaVersion 目标版本号
     *
     * @return 回滚后的档案 VO；落库未生效返回 null
     */
    @Transactional
    public CharacterCardVO restoreVersion(Long characterId, Integer personaVersion) {
        ActorCharacter character = characterService.requireOwned(characterId);
        if (personaVersion == null) {
            throw new BizException(400, "version 不能为空");
        }
        ActorProfileRevision rev = personaRevisionService.findVersion(characterId, personaVersion);
        if (rev == null || rev.getPersonaJson() == null || rev.getPersonaJson().isBlank()) {
            throw new BizException(404, "目标版本不存在或内容为空：version=" + personaVersion);
        }
        PersonaProfile profile = PersonaJson.readQuietly(rev.getPersonaJson());
        if (profile == null) {
            throw new BizException(400, "目标版本快照 JSON 无法解析，无法回滚：version=" + personaVersion);
        }
        profile.setGeneratedBy("restored");
        profile.setGeneratedScene("manual");
        int version = personaService.savePersona(characterId, profile, "restored");
        if (version <= 0) {
            log.warn("[档案回滚] 档案未落库（savePersona 返回 {}，已跳过、不阻断）：角色ID={}", version, characterId);
            return null;
        }
        log.info("[档案回滚] 已回滚到版本 {}（新版本 {}）：角色ID={} 名称={}",
                personaVersion, version, characterId, character.getName());
        return toVO(character, personaService.personaJsonOf(characterId));
    }

    /**
     * 加载角色最新档案 VO（不做归属校验，供本类内部复用）。
     *
     * @param characterId 角色主键
     *
     * @return 档案 VO；无档案返回 {@code Optional.empty()}（不构造"空 VO"，避免前端把空档案当成已建档）
     */
    private Optional<CharacterCardVO> loadLatest(Long characterId) {
        String personaJson = personaService.personaJsonOf(characterId);
        if (personaJson == null || personaJson.isBlank()) {
            return Optional.empty();
        }
        return characterRepository.findById(characterId).map(ch -> toVO(ch, personaJson));
    }

    /**
     * 构造只读档案 VO（11 维渲染文本由档案服务统一渲染，前端不重复实现渲染规则）。
     *
     * @param ch 角色主表实体
     *
     * @param personaJson 档案 JSON 原文（调用方已读出，避免重复查询）
     *
     * @return VO（ch 为空返回 null）
     */
    private CharacterCardVO toVO(ActorCharacter ch, String personaJson) {
        if (ch == null) {
            return null;
        }
        return CharacterCardVO.of(ch, personaJson, personaService.renderFull(ch.getId()));
    }

}
