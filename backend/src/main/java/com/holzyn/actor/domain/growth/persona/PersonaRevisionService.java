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

package com.holzyn.actor.domain.growth.persona;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色档案版本历史服务。
 * <p><b>职责</b>：</p>
 * <ul>
 * <li>{@link #snapshot}——每次档案落库成功后追加一行版本快照（唯一写入口，
 * 由 {@code PersonaService.savePersona} 内部调用，全链路自动生效：AI 建档 / AI 补全 /
 * 手动编辑 / 回滚都留痕）；</li>
 * <li>{@link #listVersions}——按角色取全部历史版本（前端「版本历史」）；</li>
 * <li>{@link #findVersion}——取单版内容（前端查看某版 / 回滚数据源）。</li>
 * </ul>
 * <p><b>事务边界</b>：snapshot 在 {@code savePersona} 的短事务内执行（与档案写入同事务提交，
 * 版本历史与主表永不分裂——不会出现"主表已覆盖、历史没记上"的中间态）。本服务自身不含任何 AI 调用。</p>
 * <p><b>回滚语义</b>：回滚 = 把某版 {@code persona_json} 作为新版本重新保存（版本号继续自增，
 * 旧版仍在历史里）——只增不改原则不破坏，且"回滚到哪版"本身也留一条历史，审计链完整。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaRevisionService {

    /** 版本历史仓库 */
    private final ActorProfileRevisionRepository revisionRepository;

    /**
     * 一条版本快照（{@code savePersona} 落库成功后调用；同事务提交）。
     *
     * @param projectId 项目 ID
     *
     * @param version 落库后的档案版本号
     *
     * @param personaJson 落库后的档案 JSON（存储态）
     *
     * @param source 写入来源（generated / manual / edited / restored / complete_dims）
     *
     * @param generatedBy 档案内 generated_by（可空）
     *
     * @param generatedScene 档案内 generated_scene（可空）
     *
     * @param remark 备注（可空）
     */
    public void snapshot(Long projectId, Long profileId, int version, String personaJson,
                         String source, String generatedBy, String generatedScene, String remark) {
        if (profileId == null || version <= 0) {
            return;
        }
        ActorProfileRevision rev = new ActorProfileRevision();
        rev.setProjectId(projectId);
        rev.setProfileId(profileId);
        rev.setPersonaVersion(version);
        rev.setPersonaJson(personaJson);
        rev.setSource(source);
        rev.setGeneratedBy(generatedBy);
        rev.setGeneratedScene(generatedScene);
        rev.setRemark(remark);
        try {
            revisionRepository.save(rev);
            log.debug("[档案版本] 已追加快照：profileId={} version={} source={}", profileId, version, source);
        } catch (Exception e) {
            // 观测/审计数据不得反噬业务：
            // 版本快照失败只告警，绝不阻断档案保存本身
            log.warn("[档案版本] 快照追加失败（已忽略，不影响档案保存）：profileId={} version={} 原因={}",
                    profileId, version, e.getMessage());
        }
    }

    /**
     * 按角色取全部历史版本（升序）。
     *
     * @return 版本列表（升序）
     */
    @Transactional(readOnly = true)
    public List<ActorProfileRevision> listVersions(Long profileId) {
        if (profileId == null) {
            return List.of();
        }
        return revisionRepository.findByProfileIdOrderByPersonaVersionAsc(profileId);
    }

    /**
     * 取某角色的指定版本快照（回滚数据源）。
     *
     * @param personaVersion 版本号
     *
     * @return 快照；不存在返回 null
     */
    @Transactional(readOnly = true)
    public ActorProfileRevision findVersion(Long profileId, Integer personaVersion) {
        if (profileId == null || personaVersion == null) {
            return null;
        }
        return revisionRepository.findByProfileIdAndPersonaVersion(profileId, personaVersion).orElse(null);
    }
}
