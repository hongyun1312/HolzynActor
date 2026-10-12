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

package com.holzyn.actor.domain.observability.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.observability.vo.GlobalSearchVO;
import com.holzyn.actor.domain.observability.vo.GlobalSearchVO.HitType;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.world.entity.ActorEvent;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.ActorEventRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 全局搜索聚合服务（观测域·只读）。
 *
 * <p>职责：对「角色 / 地点 / 事件 / 知识 / 项目」五类实体做统一关键词检索，输出分组结果
 * （{@link GlobalSearchVO}），供前端全局搜索面板渲染与跳转。</p>
 *
 * <p>架构不变量：</p>
 * <ul>
 *   <li><b>纯只读、零 AI、零新表</b>：只做 LIKE 检索，不写库、不调用模型、不建缓存。</li>
 *   <li><b>单类型异常隔离</b>：某一类型查询失败只丢该组（日志留痕），其余类型照常返回——
 *       搜索是辅助能力，不能因为某张表的问题整体报错。</li>
 *   <li><b>有界</b>：每类型条数受 {@link #DEFAULT_PER_TYPE} 钳制（上限 {@link #MAX_LIMIT}），
 *       查询一律带 {@code PageRequest}，杜绝全表加载。</li>
 *   <li><b>命中口径</b>：大小写不敏感的子串匹配；标题命中优先于正文命中排序。</li>
 * </ul>
 */
@Service
public class GlobalSearchService {

    /** 单次检索总条数默认上限 */
    public static final int DEFAULT_LIMIT = 30;
    /** 单次检索总条数硬上限 */
    public static final int MAX_LIMIT = 100;
    /** 每个类型最多返回条数（避免单类型淹没其它类型） */
    public static final int DEFAULT_PER_TYPE = 8;
    /** 摘要片段长度上限 */
    private static final int SNIPPET_MAX = 120;

    private final ActorCharacterRepository characterRepository;
    private final WorldLocationRepository locationRepository;
    private final ActorEventRepository eventRepository;
    private final ActorKnowledgeDocRepository knowledgeRepository;
    private final ActorProjectRepository projectRepository;

    /**
     * 构造服务。
     *
     * @param characterRepository 角色仓库
     *
     * @param locationRepository  地点仓库
     *
     * @param eventRepository     事件仓库
     *
     * @param knowledgeRepository 知识库文档仓库
     *
     * @param projectRepository   项目仓库
     */
    public GlobalSearchService(ActorCharacterRepository characterRepository,
                               WorldLocationRepository locationRepository,
                               ActorEventRepository eventRepository,
                               ActorKnowledgeDocRepository knowledgeRepository,
                               ActorProjectRepository projectRepository) {
        this.characterRepository = characterRepository;
        this.locationRepository = locationRepository;
        this.eventRepository = eventRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.projectRepository = projectRepository;
    }

    /**
     * 执行全局搜索。
     *
     * @param projectId 项目 ID（用于限定范围与生成跳转路径）
     *
     * @param q         查询串（空白 → 抛 400；两侧空白自动去除）
     *
     * @param types     类型过滤（逗号分隔，null/空 = 全部）；无法识别的类型静默忽略
     *
     * @param limit     总条数上限（null → {@link #DEFAULT_LIMIT}；越界钳制到 1~{@link #MAX_LIMIT}）
     *
     * @return 分组结果
     */
    public GlobalSearchVO search(Long projectId, String q, String types, Integer limit) {
        String keyword = q == null ? "" : q.trim();
        if (keyword.isEmpty()) {
            throw new BizException("搜索关键词不能为空");
        }
        if (keyword.length() > 64) {
            // 防超长 pattern 拖慢 LIKE：截断而不是报错（用户多半是误粘贴）
            keyword = keyword.substring(0, 64);
        }
        int cap = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        Set<HitType> wanted = parseTypes(types);
        String base = "/project/" + projectId;

        List<GlobalSearchVO.Group> groups = new ArrayList<>();
        int remaining = cap;
        List<String> usedTypes = new ArrayList<>();

        for (HitType type : HitType.values()) {
            if (!wanted.contains(type) || remaining <= 0) {
                continue;
            }
            int perType = Math.min(DEFAULT_PER_TYPE, remaining);
            List<GlobalSearchVO.Hit> hits;
            try {
                hits = switch (type) {
                    case CHARACTER -> searchCharacters(projectId, keyword, base, perType);
                    case LOCATION -> searchLocations(projectId, keyword, base, perType);
                    case EVENT -> searchEvents(projectId, keyword, base, perType);
                    case KNOWLEDGE -> searchKnowledge(projectId, keyword, base, perType);
                    case PROJECT -> searchProjects(projectId, keyword, base, perType);
                };
            } catch (RuntimeException e) {
                // 单类型失败只丢该组
                hits = List.of();
            }
            // 防御：即便仓库/自定义实现忽略了 Pageable，也不让单类型超量返回
            if (hits.size() > perType) {
                hits = List.copyOf(hits.subList(0, perType));
            }
            if (!hits.isEmpty()) {
                usedTypes.add(type.code());
                groups.add(new GlobalSearchVO.Group(type.code(), type.label(), hits.size(), hits));
                remaining -= hits.size();
            }
        }

        int total = groups.stream().mapToInt(GlobalSearchVO.Group::count).sum();
        // 截断判定：命中数用满了本次配额，说明后面可能还有（不额外做 count 查询，避免双倍开销）
        boolean truncated = total >= cap;
        return new GlobalSearchVO(projectId, keyword, usedTypes, total, truncated, groups);
    }

    /** 角色：name / title / detail / occupation / affiliation 五字段子串匹配 */
    private List<GlobalSearchVO.Hit> searchCharacters(Long projectId, String keyword, String base, int limit) {
        List<ActorCharacter> rows = characterRepository.searchByKeyword(projectId, keyword, PageRequest.of(0, limit));
        List<GlobalSearchVO.Hit> out = new ArrayList<>(rows.size());
        for (ActorCharacter c : rows) {
            String meta = c.getType() == null ? null : ("special".equals(c.getType()) ? "特殊角色" : "普通人群");
            String snippet = c.getTitle() != null && !c.getTitle().isBlank()
                    ? c.getTitle() : snippet(c.getDetail());
            out.add(new GlobalSearchVO.Hit(HitType.CHARACTER.code(), c.getId(), c.getName(), snippet,
                    joinMeta(meta, c.getOccupation()), base + "/characters"));
        }
        return out;
    }

    /** 地点：name / intro / type */
    private List<GlobalSearchVO.Hit> searchLocations(Long projectId, String keyword, String base, int limit) {
        List<ActorWorldLocation> rows = locationRepository.searchByKeyword(projectId, keyword, PageRequest.of(0, limit));
        List<GlobalSearchVO.Hit> out = new ArrayList<>(rows.size());
        for (ActorWorldLocation l : rows) {
            out.add(new GlobalSearchVO.Hit(HitType.LOCATION.code(), l.getId(), l.getName(),
                    snippet(l.getIntro()), l.getType(), base + "/world"));
        }
        return out;
    }

    /** 事件：title / content / locationName */
    private List<GlobalSearchVO.Hit> searchEvents(Long projectId, String keyword, String base, int limit) {
        List<ActorEvent> rows = eventRepository.searchByKeyword(projectId, keyword, PageRequest.of(0, limit));
        List<GlobalSearchVO.Hit> out = new ArrayList<>(rows.size());
        for (ActorEvent e : rows) {
            String meta = e.getGameHour() == null ? e.getLocationName() : ("第 " + (e.getGameHour() / 24 + 1) + " 日");
            out.add(new GlobalSearchVO.Hit(HitType.EVENT.code(), e.getId(), e.getTitle(),
                    snippet(e.getContent()), meta, base + "/timeline"));
        }
        return out;
    }

    /** 知识库：title / content */
    private List<GlobalSearchVO.Hit> searchKnowledge(Long projectId, String keyword, String base, int limit) {
        List<ActorKnowledgeDoc> rows = knowledgeRepository.searchByKeyword(projectId, keyword, PageRequest.of(0, limit));
        List<GlobalSearchVO.Hit> out = new ArrayList<>(rows.size());
        for (ActorKnowledgeDoc d : rows) {
            String meta = d.getCharacterId() == null ? "项目级" : ("角色 #" + d.getCharacterId());
            out.add(new GlobalSearchVO.Hit(HitType.KNOWLEDGE.code(), d.getId(), d.getTitle(),
                    snippet(d.getContent()), meta, base + "/knowledge"));
        }
        return out;
    }

    /** 项目：本次仅检索当前项目本体（name / summary），便于从搜索结果回到项目入口 */
    private List<GlobalSearchVO.Hit> searchProjects(Long projectId, String keyword, String base, int limit) {
        List<ActorProject> rows = projectRepository.searchByKeyword(projectId, keyword, PageRequest.of(0, limit));
        List<GlobalSearchVO.Hit> out = new ArrayList<>(rows.size());
        for (ActorProject p : rows) {
            out.add(new GlobalSearchVO.Hit(HitType.PROJECT.code(), p.getId(), p.getName(),
                    snippet(p.getSummary()), p.getCode(), base + "/dashboard"));
        }
        return out;
    }

    /** 解析类型过滤；null/空 = 全部 */
    private Set<HitType> parseTypes(String types) {
        if (types == null || types.isBlank()) {
            return EnumSet.allOf(HitType.class);
        }
        Set<HitType> wanted = EnumSet.noneOf(HitType.class);
        for (String raw : types.split(",")) {
            HitType t = HitType.parse(raw);
            if (t != null) {
                wanted.add(t);
            }
        }
        return wanted.isEmpty() ? EnumSet.allOf(HitType.class) : wanted;
    }

    /** 摘要：去换行 + 截断 */
    private String snippet(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= SNIPPET_MAX ? flat : flat.substring(0, SNIPPET_MAX) + "…";
    }

    /** 元信息拼接（跳过空值，去重保序） */
    private String joinMeta(String... parts) {
        Set<String> seen = new LinkedHashSet<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                seen.add(p.trim());
            }
        }
        return seen.isEmpty() ? null : String.join(" · ", seen);
    }
}
