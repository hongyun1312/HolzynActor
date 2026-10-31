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

package com.holzyn.actor.domain.growth.mask;

import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 面具情境程序化解析。
 * <p><b>判定规则</b>（全部可单测；失败零阻断返回空列表=无面具）：</p>
 * <ul>
 * <li><b>场合码</b>：本人 {@code location} 命中私人场所词（家/宅/房/屋/居/卧/闺/厢/书斋…）
 * → {@code private_place}；否则（含未知）→ {@code public_place}；</li>
 * <li><b>对象码</b>（有在场者时逐人判定，一人在多情境叠加）：
 * <ol>
 * <li>对方身份含权威词（村长/族长/里正/保长/官/衙/上官/头领/庄主/门主/长老/师父…）→ 对权威；</li>
 * <li>本人↔对方关系：好感 &lt; −30 或关系类型/描述含敌/仇/怨 → 对仇怨对象；</li>
 * <li>关系类型含配偶/夫妻/恋人/情侣/伴侣 → 对伴侣；</li>
 * <li>关系类型含子女/儿/女/孙/侄/甥，或对方明显年幼（小 ≥15 岁）→ 对子女；</li>
 * <li>无关系行或好感 &lt; 20 → 对陌生人；</li>
 * <li>其余 → 对同伴（同村同辈默认）。</li>
 * </ol></li>
 * <li><b>空对象列表（=非角色对象在场，如玩家会话）</b>：追加 {@code before_stranger}（外来者）；</li>
 * <li><b>null 对象（=独处/无社交对象，如决策场景）</b>：只给场合码。</li>
 * </ul>
 * <p>判定点设计（N 轨道预留）：本类被 {@link MaskService} 经 {@link MaskContextSource} 接口调用；
 * 之后 决策模型 接入只替换实现（choice 题型），不重改面具主流程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProgrammaticMaskContextResolver implements MaskContextSource {

    /** 私人场所关键词（地点名命中任一 → private_place） */
    private static final String[] PRIVATE_PLACE_WORDS = {"家", "宅", "房", "屋", "居", "卧", "闺", "厢", "书斋", "内室", "寝"};
    /** 权威身份关键词（对方 occupation/title 命中任一 → before_authority） */
    private static final String[] AUTHORITY_WORDS = {"村长", "族长", "里正", "保长", "上官", "头领", "庄主", "门主", "长老",
            "师父", "县令", "县丞", "典史", "官", "衙", "保甲"};
    /** 仇怨关系关键词（关系类型/描述命中任一 → before_rival） */
    private static final String[] RIVAL_WORDS = {"敌", "仇", "怨", "对头"};
    /** 伴侣关系关键词（关系类型命中任一 → before_partner） */
    private static final String[] PARTNER_WORDS = {"配偶", "夫妻", "恋人", "情侣", "伴侣", "情人", "爱侣"};
    /** 晚辈关系关键词（关系类型命中任一 → before_child） */
    private static final String[] CHILD_WORDS = {"子女", "儿子", "女儿", "孙", "侄", "甥"};
    /** 仇怨好感阈值（affinity < 此值 → before_rival） */
    private static final int RIVAL_AFFINITY = -30;
    /** 陌生人好感阈值（affinity < 此值且无更亲密关系 → before_stranger） */
    private static final int STRANGER_AFFINITY = 20;
    /** 晚辈年龄差阈值（对方比本人小 ≥ 此岁数 → before_child 候选） */
    private static final int CHILD_AGE_GAP = 15;

    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRelationRepository relationRepository;

    /**
     * 决策模型 面具情境判定（默认关场景；开启且采纳时优先，失败/升级回退本程序化实现；
     * 字段注入保持构造器不变，null=未装配[单测]直接走程序化）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.ai.decision.gate.MaskContextJudge decisionJudge;

    @Override
    public List<String> resolve(Long projectId, String selfRef, List<String> partnerRefs, Long gameSecond) {
        try {
            Set<String> codes = new LinkedHashSet<>();
            // ① 场合码（恒在）
            codes.add(isPrivatePlace(placeOf(projectId, selfRef)) ? MaskContextCode.PRIVATE_PLACE
                    : MaskContextCode.PUBLIC_PLACE);
            if (partnerRefs == null || partnerRefs.isEmpty()) {
                if (partnerRefs != null) {
                    // 空列表 = 有非角色对象在场（如玩家会话）→ 外来者
                    codes.add(MaskContextCode.BEFORE_STRANGER);
                }
                return new ArrayList<>(codes);
            }
            // ② 对象码（逐人在场者判定；一人在多情境时全部加入，消费方叠加取最大抑制）
            ActorCharacter self = characterRepository.findById(NpcRefs.idOf(selfRef)).orElse(null);
            for (String ref : partnerRefs) {
                if (ref == null || ref.isBlank() || !ref.startsWith("npc:")) {
                    continue;
                }
                codes.add(contextOfPartner(projectId, self, ref));
            }
            return new ArrayList<>(codes);
        } catch (Exception e) {
            log.warn("[面具] 情境解析失败（返回空=无面具，零阻断）: ref={} 原因={}", selfRef, e.getMessage());
            return List.of();
        }
    }

    /**
     * 单人判定（优先级：权威 > 仇怨 > 伴侣 > 子女 > 陌生人 > 同伴；
     * 决策模型 优先——开启且采纳时用 决策模型 情境码，否则程序化）。
     */
    private String contextOfPartner(Long projectId, ActorCharacter self, String partnerRef) {
        // ：决策模型 面具情境判定（对象码；默认关场景）
        if (decisionJudge != null) {
            try {
                ActorCharacter partner = characterRepository.findById(NpcRefs.idOf(partnerRef)).orElse(null);
                String code = decisionJudge.decide(projectId, NpcRefs.of(self.getId()),
                        self == null ? null : self.getName(), partnerDescription(projectId, self, partner, partnerRef));
                if (code != null && MaskContextCode.isValid(code)) {
                    return code;
                }
            } catch (Exception e) {
                log.warn("[面具] 决策模型 情境判定异常（回退程序化）: ref={} : {}", selfRefOf(self), e.getMessage());
            }
        }
        ActorCharacter partner = characterRepository.findById(NpcRefs.idOf(partnerRef)).orElse(null);
        if (isAuthority(partner)) {
            return MaskContextCode.BEFORE_AUTHORITY;
        }
        ActorCharacterRelation rel = findRelation(projectId, self, partnerRef);
        if (rel != null) {
            if (rel.getAffinity() != null && rel.getAffinity() < RIVAL_AFFINITY || hitsAny(rel, RIVAL_WORDS)) {
                return MaskContextCode.BEFORE_RIVAL;
            }
            if (hitsType(rel, PARTNER_WORDS)) {
                return MaskContextCode.BEFORE_PARTNER;
            }
            if (hitsType(rel, CHILD_WORDS)) {
                return MaskContextCode.BEFORE_CHILD;
            }
            if (rel.getAffinity() == null || rel.getAffinity() < STRANGER_AFFINITY) {
                return MaskContextCode.BEFORE_STRANGER;
            }
        }
        // 无关系行：明显年幼（同村晚辈脸熟）→ 对子女；否则陌生人
        if (isMuchYounger(self, partner)) {
            return MaskContextCode.BEFORE_CHILD;
        }
        return MaskContextCode.BEFORE_STRANGER;
    }

    /** 对方描述（决策模型 情境判定输入：姓名/身份/年龄/关系好感；程序化解析辅助）。 */
    private String partnerDescription(Long projectId, ActorCharacter self, ActorCharacter partner,
                                      String partnerRef) {
        StringBuilder sb = new StringBuilder();
        if (partner == null) {
            sb.append("（身份未知的人）");
        } else {
            sb.append(partner.getName() == null || partner.getName().isBlank() ? "（无名者）" : partner.getName());
            if (partner.getOccupation() != null && !partner.getOccupation().isBlank()) {
                sb.append("，身份").append(partner.getOccupation().trim());
            }
            if (partner.getTitle() != null && !partner.getTitle().isBlank()) {
                sb.append("，").append(partner.getTitle().trim());
            }
            if (partner.getAge() != null) {
                sb.append("，年约").append(partner.getAge()).append("岁");
            }
        }
        ActorCharacterRelation rel = self == null ? null : findRelation(projectId, self, partnerRef);
        if (rel != null) {
            if (rel.getRelationType() != null && !rel.getRelationType().isBlank()) {
                sb.append("，与己关系：").append(rel.getRelationType().trim());
            }
            if (rel.getAffinity() != null) {
                sb.append("，好感").append(rel.getAffinity());
            }
        }
        return sb.toString();
    }

    /** 本人引用（self 为 null 时占位）。 */
    private String selfRefOf(ActorCharacter self) {
        return self == null ? "npc:?" : NpcRefs.of(self.getId());
    }

    /** 本人当前地点（location 列；解析失败返回 null → 按公共场合）。 */
    private String placeOf(Long projectId, String selfRef) {
        return characterRepository.findById(NpcRefs.idOf(selfRef))
                .map(ActorCharacter::getLocation)
                .orElse(null);
    }

    /** 地点是否私人场所（家宅类词命中）。 */
    private static boolean isPrivatePlace(String location) {
        if (location == null || location.isBlank()) {
            return false;
        }
        for (String w : PRIVATE_PLACE_WORDS) {
            if (location.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 对方是否权威身份（occupation/title 词命中）。 */
    private static boolean isAuthority(ActorCharacter partner) {
        if (partner == null) {
            return false;
        }
        String occ = partner.getOccupation();
        String title = partner.getTitle();
        for (String w : AUTHORITY_WORDS) {
            if ((occ != null && occ.contains(w)) || (title != null && title.contains(w))) {
                return true;
            }
        }
        return false;
    }

    /** 本人↔对方关系行（双向任一方向）。 */
    private ActorCharacterRelation findRelation(Long projectId, ActorCharacter self, String partnerRef) {
        if (projectId == null || self == null || partnerRef == null) {
            return null;
        }
        String selfRef = NpcRefs.of(self.getId());
        for (ActorCharacterRelation r : relationRepository.findByProjectId(projectId)) {
            if (r == null) {
                continue;
            }
            boolean from = selfRef.equals(r.getFromRef()) && partnerRef.equals(r.getToRef());
            boolean to = partnerRef.equals(r.getFromRef()) && selfRef.equals(r.getToRef());
            if (from || to) {
                return r;
            }
        }
        return null;
    }

    /** 关系行任意文本字段命中词表（关系类型/描述；仇恨词判定用）。 */
    private static boolean hitsAny(ActorCharacterRelation rel, String[] words) {
        return hitsType(rel, words) || hitsDesc(rel, words);
    }

    private static boolean hitsType(ActorCharacterRelation rel, String[] words) {
        if (rel == null || rel.getRelationType() == null) {
            return false;
        }
        for (String w : words) {
            if (rel.getRelationType().contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hitsDesc(ActorCharacterRelation rel, String[] words) {
        if (rel == null || rel.getDescription() == null) {
            return false;
        }
        for (String w : words) {
            if (rel.getDescription().contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 对方是否明显年幼（年龄差 ≥15 岁；任一方年龄未知返回 false）。 */
    private static boolean isMuchYounger(ActorCharacter self, ActorCharacter partner) {
        if (self == null || partner == null || self.getAge() == null || partner.getAge() == null) {
            return false;
        }
        return partner.getAge() - self.getAge() <= -CHILD_AGE_GAP;
    }
}
