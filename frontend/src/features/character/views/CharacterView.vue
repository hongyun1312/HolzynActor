<!--
  Copyright (c) 2026 HONGYUN
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
  SPDX-License-Identifier: Apache-2.0
-->
<template>
  <div class="character-view">
    <!-- 左栏：角色列表 -->
    <div class="char-side">
      <div class="side-header">
        <span>NPC 角色（{{ characters.length }}）</span>
        <el-button size="small" type="primary" text @click="openCharDialog()"><el-icon><HIcon name="Plus" /></el-icon>新增</el-button>
      </div>
      <div class="side-filter">
        <el-select v-model="typeFilter" size="small" clearable placeholder="类型筛选" style="width: 100%">
          <el-option label="特殊型 NPC" value="special" />
          <el-option label="普通型" value="common" />
        </el-select>
      </div>
      <div v-if="filteredChars.length === 0" class="side-empty">暂无角色，点击「新增」添加</div>
      <div v-else class="char-list">
        <div
          v-for="ch in filteredChars"
          :key="ch.id"
          class="char-item"
          :class="{ active: currentChar?.id === ch.id }"
          @click="selectChar(ch)"
        >
          <el-avatar :size="34" class="char-avatar">{{ ch.name?.charAt(0) }}</el-avatar>
          <div class="char-meta">
            <div class="char-name">
              {{ ch.name }}
              <el-tag v-if="ch.isProtagonist === 1" size="small" type="danger">主角</el-tag>
            </div>
            <div class="char-title">
              {{ ch.title || '—' }}
              <el-tag v-if="ch.type === 'common'" size="small" type="info">普通型</el-tag>
            </div>
          </div>
          <div class="char-ops" @click.stop>
            <el-button size="small" text type="primary" @click="openCharDialog(ch)">编辑</el-button>
            <el-button size="small" text type="danger" @click="doRemoveChar(ch)">删除</el-button>
          </div>
        </div>
      </div>
      <div class="side-foot">
        <el-button type="primary" :loading="generatingAll" :disabled="characters.length === 0" size="small" style="width: 100%" @click="generateAll">
          <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;一键生成全部角色档案
        </el-button>
      </div>
    </div>

    <!-- 右栏：角色详情，分 Tab（「关系拓扑」无需选中角色即可查看全角色网络图，其余 Tab 需选中角色） -->
    <div class="char-main">
      <!-- 顶部操作条：仅选中角色时显示 -->
      <div v-if="currentChar" class="detail-header">
          <div class="detail-name">
            {{ currentChar.name }}
            <el-tag v-if="currentChar.isProtagonist === 1" size="small" type="danger">主角</el-tag>
            <el-tag v-if="currentChar.type === 'common'" size="small" type="info">普通型</el-tag>
          </div>
          <div class="detail-ops">
            <el-tag v-if="roleCard" size="small" effect="plain" class="card-version">v{{ roleCard.version }}</el-tag>
            <el-button size="small" :loading="generatingMap[currentChar.id]" @click="generateOne(currentChar)">
              <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;{{ roleCard ? '重新生成' : '生成角色档案' }}
            </el-button>
            <el-button size="small" plain @click="openVersions">
              <el-icon><HIcon name="Clock" /></el-icon>&nbsp;版本历史
            </el-button>
          </div>
        </div>

        <el-tabs v-model="infoTab" class="info-tabs" @tab-change="onInfoTabChange">
          <!-- Tab 1 · 概览：基础档案只读（需选中角色） -->
          <el-tab-pane label="概览" name="overview">
            <div v-if="!currentChar" class="main-empty">
              <el-empty description="从左侧选择一个角色查看详情" />
            </div>
            <template v-else>
            <div class="ov-grid">
              <div class="ov-item"><span class="k">姓名</span><span class="v">{{ currentChar.name }}</span></div>
              <div class="ov-item"><span class="k">头衔</span><span class="v">{{ currentChar.title || '—' }}</span></div>
              <div class="ov-item"><span class="k">类型</span><span class="v">{{ currentChar.type === 'common' ? '普通型' : '特殊型 NPC' }}</span></div>
              <div class="ov-item"><span class="k">是否主角</span><span class="v">{{ currentChar.isProtagonist === 1 ? '是' : '否' }}</span></div>
              <div class="ov-item"><span class="k">位置</span><span class="v">
                <template v-if="currentChar.location">{{ currentChar.location }}<el-tag v-if="currentChar.locationId" size="small" type="success" effect="plain">已归一</el-tag></template>
                <template v-else>—</template>
              </span></div>
              <div class="ov-item"><span class="k">重要度</span><span class="v"><el-rate :model-value="currentChar.importance" disabled size="small" /></span></div>
              <!-- O6 G2 全生命周期阶段机：年龄 / 发育阶段 / 认知 LOD（数据源 /api/debug/growth/overview；旧 jar 无端点显示 —） -->
              <div class="ov-item"><span class="k">年龄</span><span class="v">
                <template v-if="growthOf(currentChar)">{{ growthOf(currentChar).age }} 岁</template>
                <template v-else>—</template>
              </span></div>
              <div class="ov-item"><span class="k">发育阶段</span><span class="v">
                <template v-if="growthOf(currentChar)">
                  {{ growthOf(currentChar).stageLabel || growthOf(currentChar).stage || '—' }}
                  <el-tag v-if="growthOf(currentChar).stage === 'DECEASED'" size="small" type="danger" effect="dark">已故</el-tag>
                  <el-tag v-else-if="growthOf(currentChar).stage === 'INFANT'" size="small" type="warning" effect="plain">婴幼儿期</el-tag>
                </template>
                <template v-else>—</template>
              </span></div>
              <div class="ov-item"><span class="k">认知档位</span><span class="v">
                <template v-if="growthOf(currentChar)">
                  <el-tag size="small" :type="tierTag(growthOf(currentChar).tier)" effect="plain">{{ tierLabel(growthOf(currentChar).tier) }}</el-tag>
                </template>
                <template v-else>—</template>
              </span></div>
              <div class="ov-item"><span class="k">角色档案</span><span class="v">
                <el-tag v-if="currentChar.hasCard" size="small" type="success">已生成</el-tag>
                <el-tag v-else size="small" type="info">未生成</el-tag>
              </span></div>
            </div>
            <div class="ov-detail">
              <div class="ov-detail-label">详细信息</div>
              <div class="ov-detail-text">{{ currentChar.detail || '—' }}</div>
            </div>
            <el-button size="small" text type="primary" @click="openCharDialog(currentChar)">编辑档案</el-button>
            </template>
          </el-tab-pane>

          <!-- Tab 2 · 角色档案（11 维，只读）：服务端 personaRenderText 按维切分渲染；编辑属 G1b -->
          <el-tab-pane label="角色档案（11 维）" name="card">
            <div v-if="!currentChar" class="main-empty">
              <el-empty description="从左侧选择一个角色查看详情" />
            </div>
            <template v-else>
            <div v-if="!roleCard" class="main-empty">
              <el-empty description="该角色尚未生成角色档案（11 维）">
                <el-button type="primary" :loading="generatingMap[currentChar.id]" @click="generateOne(currentChar)">AI 生成角色档案</el-button>
              </el-empty>
            </div>
            <template v-else>
              <!-- 只读 11 维档案（组件：features/character/components/PersonaArchivePanel.vue） -->
              <PersonaArchivePanel :card="roleCard" :character-name="currentChar.name" />
              <div class="persona-ops">
                <!-- G1b 解禁：档案可视化编辑器（表单为主 + JSON 兜底 + AI 补全 + 版本历史/回滚） -->
                <el-button size="small" text type="primary" @click="openPersonaEditor">编辑角色档案（11 维）</el-button>
                <el-button size="small" text @click="openVersions">版本历史</el-button>
              </div>
            </template>
            </template>
          </el-tab-pane>

          <!-- Tab 3 · Prompt：system_prompt 概念随 G1a 废弃（改为运行时按场景渲染分片），此页仅保留说明 -->
          <el-tab-pane label="Prompt" name="prompt">
            <div v-if="!currentChar" class="main-empty">
              <el-empty description="从左侧选择一个角色查看详情" />
            </div>
            <template v-else>
              <el-alert
                type="info"
                :closable="false"
                show-icon
                title="系统 Prompt 已废弃（O6 轨道 G1a）"
                description="角色档案不再在生成时定稿一段 system_prompt，改为运行时按场景渲染分片（决策 S1 S2 S4 S6 S8 ／ 单聊 S1 S2 S3 S7 S8 S10 ／ 群聊 ／ 计划 ／ 人群）。档案本体请见「角色档案（11 维）」Tab；各场景实际注入文本可在调试前端「记忆注入预览」页查看。"
              />
            </template>
          </el-tab-pane>

          <!-- Tab 4 · 记忆：角色长期记忆（需选中角色） -->
          <el-tab-pane label="记忆" name="memory">
            <div v-if="!currentChar" class="main-empty">
              <el-empty description="从左侧选择一个角色查看详情" />
            </div>
            <template v-else>
            <div class="memory-ops">
              <span class="memory-hint">对话后 AI 自动抽取（M1 三重机制：重要度 1 丢 / 2 进工作记忆草稿 / 3+ 直进长期记忆；update/invalidate 版本链演进；target=world 世界信息路由到「项目记忆」页）</span>
              <div class="memory-ops-right">
                <el-button size="small" text type="info" @click="openInjectionPreview" title="分场景注入组合预览（M5：各段文本 + 命中记忆得分 + token 预算）">注入预览</el-button>
                <el-button size="small" text type="warning" @click="doMaintenance" title="统一维护（L1 TTL/巩固扫描 + 归档超龄/保留/二次清理，M5）">统一维护</el-button>
                <el-button size="small" text type="primary" :loading="extracting" @click="doExtractMemories">手动抽取</el-button>
              </div>
            </div>
            <div v-loading="memoryLoading" class="memory-list">
              <div v-if="memories.length === 0" class="side-empty">暂无记忆，对话几轮后自动生成</div>
              <div v-for="m in memories" :key="m.id" class="memory-item" :class="memoryClass(m)">
                <div class="memory-head">
                  <el-tag size="small" :type="typeTag(m)" effect="plain">
                    {{ typeLabel(m) }}
                  </el-tag>
                  <el-tag size="small" :type="impTag(m.importance)" effect="light">重要度 {{ m.importance }}</el-tag>
                  <el-button class="memory-op" size="small" text type="primary" @click="openEditMemoryDialog(m)" title="编辑该记忆（版本+1，旧版自动归档）">
                    <el-icon><HIcon name="Edit" /></el-icon>
                  </el-button>
                  <el-button class="memory-op" size="small" text type="warning" @click="doArchiveMemory(m)" title="归档该记忆（写入归档冷存储，不再参与注入）">
                    <el-icon><HIcon name="Document" /></el-icon>
                  </el-button>
                  <el-button class="memory-del" size="small" text type="danger" @click="doDeleteMemory(m)" title="删除该记忆">
                    <el-icon><HIcon name="Delete" /></el-icon>
                  </el-button>
                </div>
                <div class="memory-content">{{ m.content }}</div>
              </div>
            </div>
            <!-- M3 L3 核心记忆（恒在上下文：静态基线只读 + 动态核心块 CRUD） -->
            <div class="core-memory-sec">
              <div class="core-memory-head">
                <span class="core-memory-title">核心记忆（L3 恒在）</span>
                <el-button size="small" text type="primary" :loading="coreMemoryLoading" @click="loadCoreMemories">刷新</el-button>
                <el-button size="small" type="primary" plain @click="openCoreBlockDialog()">
                  <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新建核心块
                </el-button>
              </div>
              <div v-loading="coreMemoryLoading" class="core-memory-body">
                <div v-if="coreBaselineText" class="core-baseline">
                  <div class="core-baseline-title">静态基线（来自角色档案/脑力/目标/关系，恒在注入）</div>
                  <div class="core-baseline-text">{{ coreBaselineText }}</div>
                </div>
                <div v-if="coreBlocks.length === 0 && !coreBaselineText" class="side-empty">
                  暂无核心记忆。身份信息由静态基线渲染；可由「新建核心块」手动添加，或对话/反思时由 AI 自动演进（core_memory_update）
                </div>
                <div v-for="b in coreBlocks" :key="b.id" class="core-block-item">
                  <div class="core-block-head">
                    <el-tag size="small" :type="coreBlockTag(b)" effect="plain">{{ b.blockKey.startsWith('custom:') ? b.blockName || b.blockKey : (coreBlockKeyLabel(b.blockKey)) }}</el-tag>
                    <el-tag size="small" effect="light">重要度 {{ b.importance }}</el-tag>
                    <el-tag size="small" effect="plain" :type="b.editableByAgent === 0 ? 'info' : 'warning'">
                      {{ b.editableByAgent === 0 ? '仅用户可改' : 'agent 可自编辑' }}
                    </el-tag>
                    <span class="core-block-version">v{{ b.version }}</span>
                    <div class="core-block-ops">
                      <el-button size="small" text type="primary" @click="openCoreBlockDialog(b)">编辑</el-button>
                      <el-button size="small" text type="warning" @click="doArchiveCoreBlock(b)">归档</el-button>
                      <el-button size="small" text type="danger" @click="doDeleteCoreBlock(b)">删除</el-button>
                    </div>
                  </div>
                  <div class="core-block-content">{{ b.content }}</div>
                </div>
              </div>
            </div>
            <!-- M2 L1 工作记忆（热缓冲：草稿/任务/上下文/感知，会话结束后巩固进长期记忆） -->
            <div class="working-memory-sec">
              <div class="working-memory-title">工作记忆（L1 热缓冲）</div>
              <div v-loading="workingMemoryLoading" class="working-memory-list">
                <div v-if="workingMemories.length === 0" class="side-empty">暂无进行中的工作项（对话抽取草稿、行动关注点、感知快照将在会话结束后巩固为长期记忆）</div>
                <div v-for="w in workingMemories" :key="w.id" class="working-memory-item">
                  <div class="working-memory-head">
                    <el-tag size="small" :type="workingTypeTag(w)" effect="plain">{{ workingTypeLabel(w) }}</el-tag>
                    <el-tag v-if="w.importanceHint" size="small" effect="light">建议重要度 {{ w.importanceHint }}</el-tag>
                    <span class="working-memory-time">{{ fmtTime(w.createdAt) }}</span>
                  </div>
                  <div class="working-memory-content">{{ w.content }}</div>
                </div>
              </div>
            </div>
            <!-- M5 记忆治理健康度（§11.3：归档量 / 版本链 / 分层分布——角色维度健康度摘要） -->
            <div class="working-memory-sec">
              <div class="working-memory-title">记忆治理健康度（M5）</div>
              <div v-loading="healthLoading" class="memory-health-grid">
                <div class="health-item">
                  <div class="health-label">归档量</div>
                  <div class="health-value mono">{{ memoryHealth?.archive?.total ?? 0 }}</div>
                  <div class="health-sub">按原因 {{ reasonSummary(memoryHealth?.archive?.byReason) }} · 还原 {{ memoryHealth?.archive?.restored ?? 0 }}</div>
                </div>
                <div class="health-item">
                  <div class="health-label">版本链数</div>
                  <div class="health-value mono">{{ memoryHealth?.versionChain?.totalVersionChainCount ?? 0 }}</div>
                  <div class="health-sub">L2 {{ memoryHealth?.versionChain?.l2VersionChainCount ?? 0 }} · L3 {{ memoryHealth?.versionChain?.l3CoreBlockChainCount ?? 0 }}</div>
                </div>
                <div class="health-item">
                  <div class="health-label">分层 L1/L2/L3</div>
                  <div class="health-value mono">{{ memoryHealth?.tier?.l1?.activeCount ?? 0 }} / {{ memoryHealth?.tier?.l2?.total ?? 0 }} / {{ memoryHealth?.tier?.l3CoreBlocks ?? 0 }}</div>
                  <div class="health-sub">L2：语义 {{ memoryHealth?.tier?.l2?.semantic ?? 0 }} · 经历 {{ memoryHealth?.tier?.l2?.episodic ?? 0 }} · 反思 {{ memoryHealth?.tier?.l2?.reflection ?? 0 }} · 计划 {{ memoryHealth?.tier?.l2?.plan ?? 0 }} · 摘要 {{ memoryHealth?.tier?.l2?.summary ?? 0 }}</div>
                </div>
              </div>
            </div>
            <!-- O1 记忆内核扩展：L1 短期模糊（艾宾浩斯渐进遗忘 + 睡眠清洗状态，只读展示 + 手动触发） -->
            <div class="working-memory-sec">
              <div class="working-memory-title">
                L1 短期模糊（O1：艾宾浩斯遗忘曲线 · 睡眠清洗）
                <el-button size="small" text type="primary" :loading="fuzzLoading" @click="loadFuzzStates">刷新</el-button>
              </div>
              <div v-loading="fuzzLoading" class="working-memory-list">
                <div v-if="fuzzStates.length === 0" class="side-empty">
                  暂无短期模糊记忆。角色经历类记忆写入 L1 short_term 后，将随时间按艾宾浩斯曲线渐进模糊，睡眠时按重要度分档（蒸馏 L2 / 琐事合并 / 模糊跳升）
                </div>
                <div v-for="f in fuzzStates" :key="f.id" class="working-memory-item">
                  <div class="working-memory-head">
                    <el-tag size="small" :type="fuzzLevelTag(f.fuzzLevel)" effect="light">模糊 {{ Math.round(f.fuzzLevel * 100) }}%</el-tag>
                    <el-tag size="small" effect="plain" :type="fuzzStatusTag(f.status)">{{ fuzzStatusLabel(f.status) }}</el-tag>
                    <el-tag v-if="f.retrievalCount" size="small" effect="plain" type="info">检索 {{ f.retrievalCount }} 次</el-tag>
                    <span v-if="f.distilledMemoryId" class="working-memory-time mono">已蒸馏 → L2 #{{ f.distilledMemoryId }}</span>
                    <span class="working-memory-time mono">{{ gameSecondText(f.nextFuzzGameSecond) }} 复查</span>
                    <el-button class="memory-op" size="small" text type="primary"
                               :loading="fuzzTriggerId === f.memoryWorkingId"
                               @click="doTriggerFuzz(f)" title="立即模糊一次（无视艾宾浩斯间隔）">模糊</el-button>
                  </div>
                  <div class="working-memory-content">{{ f.currentContent }}</div>
                  <div v-if="f.originalContent !== f.currentContent" class="fuzz-original">
                    <span class="fuzz-original-label">原版：</span>{{ f.originalContent }}
                  </div>
                </div>
              </div>
            </div>
            <!-- O2 决策与协调：前瞻推演 / 冲突检测协商 / state delta（只读回看，P1-1/P1-2/P1-3） -->
            <div class="working-memory-sec">
              <div class="working-memory-title">
                决策与协调（O2：前瞻推演 · 冲突协商 · 状态增量）
                <el-button size="small" text type="primary" :loading="dcLoading" @click="loadDecisionCoord">刷新</el-button>
              </div>
              <div v-loading="dcLoading" class="dc-wrap">
                <div class="dc-empty" v-if="dcForesight.length === 0 && dcConflicts.length === 0 && dcDeltas.length === 0">
                  暂无 O2 记录。角色遇到资源/关系/位置类刺激或紧急事件时产生前瞻推演；行动目标与他人冲突时产生冲突协商；每次行动执行后产生状态增量
                </div>
                <!-- 前瞻推演 -->
                <div v-if="dcForesight.length" class="dc-sec">
                  <div class="dc-sec-title">前瞻推演（P1-1）</div>
                  <div v-for="f in dcForesight" :key="f.id" class="dc-item">
                    <div class="working-memory-head">
                      <el-tag size="small" :type="f.success ? 'success' : 'warning'" effect="plain">{{ f.success ? '有候选' : '无候选' }}</el-tag>
                      <span class="dc-reason">{{ f.triggerReason }}</span>
                      <span class="working-memory-time mono">{{ gameSecondText(f.gameSecond) }}</span>
                    </div>
                    <div class="working-memory-content">{{ f.finalAction }}</div>
                    <div v-if="f.candidatesJson && f.candidatesJson !== '[]'" class="dc-cands">{{ prettyCandidates(f.candidatesJson) }}</div>
                  </div>
                </div>
                <!-- 冲突协商 -->
                <div v-if="dcConflicts.length" class="dc-sec">
                  <div class="dc-sec-title">冲突协商（P1-3）</div>
                  <div v-for="c in dcConflicts" :key="c.id" class="dc-item">
                    <div class="working-memory-head">
                      <el-tag size="small" :type="dcResolutionTag(c.resolution)" effect="plain">{{ dcResolutionLabel(c.resolution) }}</el-tag>
                      <el-tag size="small" effect="plain" type="info">{{ c.conflictType }}</el-tag>
                      <span class="dc-reason">争「{{ c.resourceName }}」 vs {{ c.opponentName }}</span>
                      <span class="working-memory-time mono">{{ gameSecondText(c.gameSecond) }}</span>
                    </div>
                    <div class="working-memory-content">{{ c.actionAfter }}</div>
                  </div>
                </div>
                <!-- state delta -->
                <div v-if="dcDeltas.length" class="dc-sec">
                  <div class="dc-sec-title">状态增量（P1-2）</div>
                  <div v-for="d in dcDeltas" :key="d.id" class="dc-item">
                    <div class="working-memory-head">
                      <span class="dc-reason">plan #{{ d.decisionId }}</span>
                      <el-tag v-for="f in (d.changedFields || [])" :key="f" size="small" effect="plain" type="info">{{ f }}</el-tag>
                      <span class="working-memory-time mono">{{ gameSecondText(d.gameSecond) }}</span>
                    </div>
                    <div class="working-memory-content">{{ dcActionOf(d.changesJson) }}</div>
                  </div>
                </div>
              </div>
            </div>
            </template>
          </el-tab-pane>

          <!-- Tab 5 · 目标/计划：特殊 NPC 长期目标 + 当日计划（D2 F7，需选中角色） -->
          <el-tab-pane label="目标/计划" name="goalplan">
            <div v-if="!currentChar" class="main-empty">
              <el-empty description="从左侧选择一个角色查看详情" />
            </div>
            <template v-else>
            <div class="gp-ops">
              <span class="gp-hint">目标注入感知【当前目标】（active 前 2 条）；当日计划驱动行动有方向性（世界引擎跨日/决策时自动生成）</span>
              <el-button size="small" text type="primary" :loading="generatingGoals" @click="doGenerateGoals">
                <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;AI 生成目标
              </el-button>
              <el-button size="small" type="primary" plain @click="openGoalDialog()">
                <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增目标
              </el-button>
            </div>
            <!-- 目标列表 -->
            <div class="gp-goals">
              <div v-if="goals.length === 0" class="side-empty">暂无目标，点击「AI 生成目标」或「新增目标」</div>
              <div v-for="g in goals" :key="g.id" class="gp-goal" :class="g.status">
                <div class="gp-goal-head">
                  <el-tag size="small" :type="goalStatusTag(g.status)" effect="plain">{{ goalStatusLabel(g.status) }}</el-tag>
                  <el-rate :model-value="g.priority" disabled size="small" />
                  <span class="gp-goal-title">{{ g.title }}</span>
                  <div class="gp-goal-ops" @click.stop>
                    <el-button size="small" text type="primary" @click="openGoalDialog(g)">编辑</el-button>
                    <el-button v-if="g.status === 'active'" size="small" text type="success" @click="doUpdateGoalStatus(g, 'done')">完成</el-button>
                    <el-button v-if="g.status === 'active'" size="small" text type="warning" @click="doUpdateGoalStatus(g, 'abandoned')">放弃</el-button>
                    <el-button size="small" text type="danger" @click="doDeleteGoal(g)">删除</el-button>
                  </div>
                </div>
                <div v-if="g.description" class="gp-goal-desc">{{ g.description }}</div>
                <div class="gp-goal-meta">
                  优先级 {{ g.priority }} · 创建 {{ fmtTime(g.createdAt) }}
                  <span v-if="g.finishedAt"> · 结束 {{ fmtTime(g.finishedAt) }}</span>
                </div>
              </div>
            </div>
            <!-- 当日计划时间线 -->
            <div class="gp-plan">
              <div class="gp-plan-title">今日计划<span v-if="todayPlanSummary" class="gp-plan-summary">{{ todayPlanSummary }}</span></div>
              <div v-if="todayPlanItems.length === 0" class="side-empty">
                当日尚无计划。世界引擎跨日或角色决策时会自动生成（机制化/AI 生成，存记忆流 kind=plan）。
              </div>
              <div v-else class="gp-plan-timeline">
                <div v-for="(it, i) in todayPlanItems" :key="i" class="gp-plan-item">
                  <div class="gp-plan-hour">{{ String(it.hour).padStart(2, '0') }}:00</div>
                  <div class="gp-plan-dot"></div>
                  <div class="gp-plan-body">
                    <div class="gp-plan-action">{{ it.action || '日常活动' }}</div>
                    <div class="gp-plan-sub">
                      <el-tag v-if="it.state" size="small" effect="plain">{{ planStateLabel(it.state) }}</el-tag>
                      <span v-if="it.target" class="gp-plan-target">→ {{ it.target }}</span>
                      <span v-if="it.location" class="gp-plan-loc">@ {{ it.location }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            </template>
          </el-tab-pane>

          <!-- Tab 6 · 状态：NPC 内部状态展示（M3 状态模块；需求进度条 + 心情 + 主导情绪 + PAD 三维 + urgency 预警；切换 Tab 时自动加载） -->
          <el-tab-pane label="状态" name="state" lazy>
            <div v-if="!currentChar" class="main-empty">
              <el-empty description="从左侧选择一个角色查看详情" />
            </div>
            <template v-else>
              <div class="st-ops">
                <span class="st-hint">内部状态来自 actor_npc_state（世界引擎逐小时驱动 / 决策时写回）；需求越低越急</span>
                <el-button size="small" text type="primary" :loading="stateLoading" @click="loadNpcState">
                  <el-icon><HIcon name="Refresh" /></el-icon>&nbsp;刷新
                </el-button>
              </div>

              <!-- 空态：接口无数据（旧 jar 无端点或该角色从未驱动） -->
              <div v-if="!npcState" class="st-empty">
                <el-empty v-if="!stateLoading" description="暂无内部状态数据（世界引擎驱动后出现）" :image-size="72" />
              </div>

              <template v-else>
                <!-- urgency 紧急预警 -->
                <div v-if="npcState.urgencyLevel > 0" class="st-urgent" :class="'lv' + Math.min(3, npcState.urgencyLevel)">
                  <el-icon><HIcon name="Warning" /></el-icon>
                  <div class="st-urgent-body">
                    <div class="st-urgent-title">紧急状态 · 等级 {{ npcState.urgencyLevel }}{{ npcState.urgencyText ? ' · ' + npcState.urgencyText : '' }}</div>
                    <div class="st-urgent-sub">NPC 将优先处理该需求，可能打断当前行动</div>
                  </div>
                </div>

                <div class="st-grid">
                  <!-- 生理四需 + 健康 + 心情 -->
                  <div class="st-card">
                    <div class="st-card-title">生理需求</div>
                    <div class="st-needs">
                      <div class="st-need">
                        <div class="st-need-head">
                          <span class="st-need-name">饥饿</span>
                          <span class="st-need-val" :class="stNeedTone(npcState.hunger)">{{ npcState.hunger ?? '—' }}<i>{{ (npcState.needLabels && npcState.needLabels['饥饿']) || '' }}</i></span>
                        </div>
                        <el-progress :percentage="npcState.hunger ?? 0" :stroke-width="8" :show-text="false" :color="stNeedColor(npcState.hunger)" />
                      </div>
                      <div class="st-need">
                        <div class="st-need-head">
                          <span class="st-need-name">口渴</span>
                          <span class="st-need-val" :class="stNeedTone(npcState.thirst)">{{ npcState.thirst ?? '—' }}<i>{{ (npcState.needLabels && npcState.needLabels['口渴']) || '' }}</i></span>
                        </div>
                        <el-progress :percentage="npcState.thirst ?? 0" :stroke-width="8" :show-text="false" :color="stNeedColor(npcState.thirst)" />
                      </div>
                      <div class="st-need">
                        <div class="st-need-head">
                          <span class="st-need-name">精力 / 疲劳</span>
                          <span class="st-need-val" :class="stNeedTone(npcState.energy)">{{ npcState.energy ?? '—' }}<i>疲劳 {{ npcState.fatigue ?? '—' }}</i></span>
                        </div>
                        <el-progress :percentage="npcState.energy ?? 0" :stroke-width="8" :show-text="false" :color="stNeedColor(npcState.energy)" />
                      </div>
                      <div class="st-need">
                        <div class="st-need-head">
                          <span class="st-need-name">膀胱</span>
                          <span class="st-need-val" :class="stNeedTone(npcState.bladder)">{{ npcState.bladder ?? '—' }}<i>{{ (npcState.needLabels && npcState.needLabels['膀胱']) || '' }}</i></span>
                        </div>
                        <el-progress :percentage="npcState.bladder ?? 0" :stroke-width="8" :show-text="false" :color="stNeedColor(npcState.bladder)" />
                      </div>
                    </div>
                  </div>

                  <!-- 扩展需求 -->
                  <div class="st-card">
                    <div class="st-card-title">扩展需求</div>
                    <div class="st-needs">
                      <div class="st-need">
                        <div class="st-need-head">
                          <span class="st-need-name">健康</span>
                          <span class="st-need-val" :class="stNeedTone(npcState.health)">{{ npcState.health ?? '—' }}<i>{{ (npcState.needLabels && npcState.needLabels['健康']) || '' }}</i></span>
                        </div>
                        <el-progress :percentage="npcState.health ?? 0" :stroke-width="8" :show-text="false" :color="stNeedColor(npcState.health)" />
                      </div>
                      <div class="st-need">
                        <div class="st-need-head">
                          <span class="st-need-name">清洁</span>
                          <span class="st-need-val" :class="stNeedTone(npcState.hygiene)">{{ npcState.hygiene ?? '—' }}<i>{{ (npcState.needLabels && npcState.needLabels['清洁']) || '' }}</i></span>
                        </div>
                        <el-progress :percentage="npcState.hygiene ?? 0" :stroke-width="8" :show-text="false" :color="stNeedColor(npcState.hygiene)" />
                      </div>
                      <div class="st-need">
                        <div class="st-need-head">
                          <span class="st-need-name">社交</span>
                          <span class="st-need-val" :class="stNeedTone(npcState.social)">{{ npcState.social ?? '—' }}<i>{{ (npcState.needLabels && npcState.needLabels['社交']) || '' }}</i></span>
                        </div>
                        <el-progress :percentage="npcState.social ?? 0" :stroke-width="8" :show-text="false" :color="stNeedColor(npcState.social)" />
                      </div>
                      <div class="st-need">
                        <div class="st-need-head">
                          <span class="st-need-name">趣味</span>
                          <span class="st-need-val" :class="stNeedTone(npcState.fun)">{{ npcState.fun ?? '—' }}<i>{{ (npcState.needLabels && npcState.needLabels['趣味']) || '' }}</i></span>
                        </div>
                        <el-progress :percentage="npcState.fun ?? 0" :stroke-width="8" :show-text="false" :color="stNeedColor(npcState.fun)" />
                      </div>
                      <div class="st-need">
                        <div class="st-need-head">
                          <span class="st-need-name">舒适</span>
                          <span class="st-need-val" :class="stNeedTone(npcState.comfort)">{{ npcState.comfort ?? '—' }}<i>{{ (npcState.needLabels && npcState.needLabels['舒适']) || '' }}</i></span>
                        </div>
                        <el-progress :percentage="npcState.comfort ?? 0" :stroke-width="8" :show-text="false" :color="stNeedColor(npcState.comfort)" />
                      </div>
                      <div v-if="npcState.envModifier != null" class="st-envmod">
                        环境心情修正：<b :class="npcState.envModifier >= 0 ? 'pos' : 'neg'">{{ npcState.envModifier >= 0 ? '+' : '' }}{{ npcState.envModifier }}</b>
                      </div>
                    </div>
                  </div>

                  <!-- 心情 + 主导情绪 -->
                  <div class="st-card">
                    <div class="st-card-title">心情与情绪</div>
                    <div class="st-mood">
                      <div class="st-need-head">
                        <span class="st-need-name">心情</span>
                        <span class="st-need-val">{{ npcState.mood ?? '—' }}<i>{{ stMoodText(npcState.mood) }}</i></span>
                      </div>
                      <el-progress :percentage="npcState.mood ?? 0" :stroke-width="8" :show-text="false" :color="stMoodColor(npcState.mood)" />
                    </div>
                    <div class="st-emotion">
                      <div class="st-emotion-row">
                        <span class="st-emotion-label">主导情绪</span>
                        <el-tag size="small" :type="stEmotionTag(npcState.dominantEmotion)" effect="dark">{{ stEmotionLabel(npcState.dominantEmotion) }}</el-tag>
                        <span v-if="npcState.emotionLabelExplicit === 1" class="st-explicit" title="AI 显式评估标签">AI 评估</span>
                      </div>
                      <div class="st-emotion-row">
                        <span class="st-emotion-label">强度</span>
                        <el-progress :percentage="npcState.emotionIntensity ?? 0" :stroke-width="8" :show-text="true" :color="stNeedColor(npcState.emotionIntensity)" style="flex:1" />
                      </div>
                    </div>
                    <!-- PAD 三维 -->
                    <div class="st-pad">
                      <div class="st-pad-title">PAD 情绪维度</div>
                      <div class="st-pad-row">
                        <span class="st-pad-axis">愉悦 P</span>
                        <el-progress :percentage="stPadPercent(npcState.emotionPleasure)" :stroke-width="8" :show-text="false" :color="stPadColor(npcState.emotionPleasure)" style="flex:1" />
                        <span class="st-pad-val" :class="stPadColorClass(npcState.emotionPleasure)">{{ stFmtPad(npcState.emotionPleasure) }}</span>
                      </div>
                      <div class="st-pad-row">
                        <span class="st-pad-axis">唤醒 A</span>
                        <el-progress :percentage="stPadPercent(npcState.emotionArousal)" :stroke-width="8" :show-text="false" :color="stPadColor(npcState.emotionArousal)" style="flex:1" />
                        <span class="st-pad-val" :class="stPadColorClass(npcState.emotionArousal)">{{ stFmtPad(npcState.emotionArousal) }}</span>
                      </div>
                      <div class="st-pad-row">
                        <span class="st-pad-axis">支配 D</span>
                        <el-progress :percentage="stPadPercent(npcState.emotionDominance)" :stroke-width="8" :show-text="false" :color="stPadColor(npcState.emotionDominance)" style="flex:1" />
                        <span class="st-pad-val" :class="stPadColorClass(npcState.emotionDominance)">{{ stFmtPad(npcState.emotionDominance) }}</span>
                      </div>
                    </div>
                  </div>

                  <!-- 元信息 -->
                  <div class="st-card">
                    <div class="st-card-title">状态元信息</div>
                    <div class="st-meta">
                      <div class="st-meta-row"><span>NPC 类型</span><b>{{ npcState.npcType === 'special' ? '特殊 NPC' : '人群 NPC' }}</b></div>
                      <div class="st-meta-row"><span>引用</span><b class="mono">{{ npcState.npcRef }}</b></div>
                      <div class="st-meta-row"><span>游戏时刻</span><b>{{ fmtGameClock(npcState.gameSecond) }}</b></div>
                      <div class="st-meta-row"><span>最近进食</span><b>{{ fmtGameClock(npcState.lastEatGameSecond) }}</b></div>
                      <div class="st-meta-row"><span>最近睡眠</span><b>{{ fmtGameClock(npcState.lastSleepGameSecond) }}</b></div>
                      <div class="st-meta-row"><span>更新时间</span><b>{{ npcState.updatedAt ? fmtTime(npcState.updatedAt) : '—' }}</b></div>
                    </div>
                  </div>
                </div>

                <!-- M4：状态历史曲线（actor_state_history 快照；引擎逐游戏小时采样） -->
                <div class="st-history-block">
                  <div class="st-history-head">
                    <span class="st-history-title">状态历史曲线（M4）</span>
                    <el-select v-model="stCurveNeed" size="small" style="width: 120px" @change="buildStateCurve">
                      <el-option v-for="n in stCurveNeeds" :key="n.value" :label="n.label" :value="n.value" />
                    </el-select>
                    <span class="st-hint">{{ stHistory.length }} 个采样点 · 逐游戏小时 · 保留 {{ npcStateHistoryMax }}</span>
                  </div>
                  <div v-if="stHistory.length" class="st-curve">
                    <EChart :option="stCurveOption" height="280px" />
                  </div>
                  <div v-else class="st-empty">
                    <el-empty v-if="!stateLoading" description="暂无历史采样（推进世界引擎后生成曲线数据）" :image-size="56" />
                  </div>
                </div>
              </template>
            </template>
          </el-tab-pane>

          <!-- Tab 7 · 关系拓扑：全角色网络图（AntV G6 v5；无需选中角色，选中角色时图内高亮其关联 + 生成该角色关系） -->
          <el-tab-pane label="关系拓扑" name="topology" lazy>
            <RelationTopology
              :project-id="projectId"
              :selected-character-id="currentChar?.id ?? null"
              :active="infoTab === 'topology'"
              :refresh-key="charRefreshKey"
              variant="tab"
              @select-character="selectCharFromTopology"
              @clear-selection="clearSelectionFromTopology"
            />
          </el-tab-pane>

          <!-- Tab 8 · 人格与面具（O6 G3 只读观测：人格偏移日志 + 社会面具真实/表达 PAD 分离） -->
          <el-tab-pane label="人格与面具" name="personamask">
            <div v-if="!currentChar" class="main-empty">
              <el-empty description="从左侧选择一个角色查看详情" />
            </div>
            <template v-else>
              <div class="personamask-ops">
                <span class="personamask-hint">
                  O6 G3：30 游戏日周期/阶段跨越/重大事件触发人格偏移评估（AI 判定 + 程序化截断，含证据链）；
                  面具按情境抑制真实情绪形成表达 PAD（破绽会松动面具、长期无破绽会固化）。数据源 /api/debug/growth/*。
                </span>
                <el-button size="small" text type="primary" :loading="pmLoading" @click="loadPersonaMask">刷新</el-button>
              </div>
              <div class="pm-sec">
                <div class="pm-sec-title">人格偏移日志（最近 20 条）</div>
                <el-table v-loading="pmLoading" :data="shiftLogs.slice(0, 20)" size="small" height="260">
                  <el-table-column prop="triggerLabel" label="触发" width="76" />
                  <el-table-column prop="dimLabel" label="维度" width="76" />
                  <el-table-column prop="field" label="字段" width="100" show-overflow-tooltip />
                  <el-table-column label="Δ" width="84">
                    <template #default="{ row }">
                      <span :class="pmDeltaClass(row.delta)">{{ pmFmtDelta(row.delta) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="变更" width="140">
                    <template #default="{ row }">
                      <span class="pm-sub">{{ row.oldValue }} → {{ row.newValue }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="source" label="来源" width="80">
                    <template #default="{ row }">{{ pmSource(row.source) }}</template>
                  </el-table-column>
                  <el-table-column prop="aiReason" label="AI 理由 / 兜底说明" min-width="180" show-overflow-tooltip />
                </el-table>
                <div v-if="!shiftLogs.length && !pmLoading" class="pm-empty">暂无偏移记录（未达 30 游戏日周期或证据不足时零偏移）</div>
              </div>
              <div class="pm-sec">
                <div class="pm-sec-title">社会面具：真实 vs 表达 PAD</div>
                <div class="pm-pad-grid">
                  <div class="pm-pad-box">
                    <div class="pm-pad-label">内心真实情绪</div>
                    <div class="pm-pad-value">愉悦 {{ pmFmt(maskStatus?.realPad?.pleasure) }} ·
                      唤醒 {{ pmFmt(maskStatus?.realPad?.arousal) }} ·
                      支配 {{ pmFmt(maskStatus?.realPad?.dominance) }}</div>
                  </div>
                  <div class="pm-pad-box">
                    <div class="pm-pad-label">对外表达情绪（面具表演化）</div>
                    <div class="pm-pad-value">愉悦 {{ pmFmt(maskStatus?.expressedPad?.pleasure) }} ·
                      唤醒 {{ pmFmt(maskStatus?.expressedPad?.arousal) }} ·
                      支配 {{ pmFmt(maskStatus?.expressedPad?.dominance) }}</div>
                  </div>
                </div>
                <el-table v-loading="pmLoading" :data="maskStatus?.rows || []" size="small" height="180">
                  <el-table-column prop="contextLabel" label="情境" width="100" />
                  <el-table-column label="抑制（预设→生效）" width="150">
                    <template #default="{ row }">
                      <span class="pm-sub">{{ (row.presetSuppression ?? 0).toFixed(2) }} → {{ (row.effectiveSuppression ?? 0).toFixed(2) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="允许表达" min-width="150" show-overflow-tooltip>
                    <template #default="{ row }">{{ pmMaskList(row.allowed) }}</template>
                  </el-table-column>
                  <el-table-column label="禁止表达" min-width="150" show-overflow-tooltip>
                    <template #default="{ row }">
                      <span class="pm-forbid">{{ pmMaskList(row.forbidden) }}</span>
                    </template>
                  </el-table-column>
                </el-table>
                <div v-if="!(maskStatus?.rows || []).length && !pmLoading" class="pm-empty">
                  （暂无面具运行态行——未经历破绽/固化时抑制与清单直接读档案 ⑪ 维预设）
                </div>
              </div>
            </template>
          </el-tab-pane>
        </el-tabs>
    </div>

    <!-- 角色新增/编辑对话框：只需输入 名字 + 详细信息 + 重要度 + 是否主角，保存后可直接生成角色卡 -->
    <el-dialog v-model="charDialog.visible" :title="charDialog.editId ? '编辑角色' : '新增角色'" width="640px" top="6vh">
      <el-form :model="charDialog.form" label-width="80px">
        <el-form-item label="姓名" required>
          <el-input v-model="charDialog.form.name" maxlength="50" placeholder="角色名字（必填）" />
        </el-form-item>
        <el-form-item label="详细信息" required>
          <el-input v-model="charDialog.form.detail" type="textarea" :rows="12" maxlength="20000" show-word-limit
            placeholder="填写角色的完整详细信息：背景经历、性格特质、能力体系、社会关系、语言风格、语录、目标、心结等。AI 将【严格遵循、只可多不可少】地据此生成 11 维角色档案（杜绝 OOC）。" />
        </el-form-item>
        <el-form-item label="重要度">
          <el-rate v-model="charDialog.form.importance" :max="5" />
        </el-form-item>
        <el-form-item label="主角">
          <el-switch v-model="charDialog.form.isProtagonist" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-collapse class="char-adv-collapse">
          <el-collapse-item title="高级选项（头衔 / 类型 / 位置，可留空）" name="adv">
            <el-form-item label="头衔"><el-input v-model="charDialog.form.title" maxlength="50" placeholder="如：帝国首席法师" /></el-form-item>
            <el-form-item label="类型">
              <el-radio-group v-model="charDialog.form.type">
                <el-radio value="special">特殊型</el-radio>
                <el-radio value="common">普通型</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="位置">
              <el-select v-model="charDialog.form.location" clearable filterable allow-create default-first-option style="width: 100%"
                placeholder="地点表候选或自由输入（保存时自动归一到地点表）">
                <el-option v-for="loc in locationOptions" :key="loc" :label="loc" :value="loc" />
              </el-select>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
        <el-form-item label="生成角色档案">
          <el-switch v-model="charDialog.autoGen" />
          <span class="char-auto-hint">保存后立即按世界观 + 详细信息生成 11 维角色档案</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="charDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="savingChar" @click="saveChar">
          保存{{ charDialog.autoGen ? '并生成角色档案' : '' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 角色档案编辑器（personaJson 手动编辑；入口已在「角色档案（11 维）」Tab 置灰——编辑器属 G1b） -->
    <el-dialog v-model="cardDialog.visible" :title="`编辑角色档案 · ${currentChar?.name}`" width="760px" top="6vh">
      <el-alert
        type="warning"
        :closable="false"
        class="card-edit-hint"
        title="编辑器属 G1b，尚未开放"
        description="本对话框为 G1b 的预留入口（当前入口按钮已置灰）。档案存储为 11 维 v2 契约（schema_version=2 + dims{...}），提交后由后端按分维来源分派落库。"
      />
      <el-input v-model="cardDialog.persona" type="textarea" :rows="18" class="code-input" spellcheck="false" />
      <template #footer>
        <el-button @click="cardDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="cardDialog.saving" @click="saveCard">保存为新版本</el-button>
      </template>
    </el-dialog>

    <!-- 角色档案版本历史对话框（G1b：actor_profile_revision 落地，真历史 + 可回滚） -->
    <el-dialog v-model="versionDialog" :title="`角色档案版本历史 · ${currentChar?.name}`" width="760px">
      <div class="version-hint">
        版本表（actor_profile_revision）已落地（G1b）：每次保存（AI 建档/AI 补全/手动编辑/回滚）都会追加一版快照；回滚 = 以旧版内容保存为新版本（版本号继续自增）。
      </div>
      <div v-loading="versionLoading" class="version-list">
        <div v-if="versions.length === 0" class="side-empty">暂无版本记录</div>
        <div v-for="v in [...versions].reverse()" :key="v.version" class="version-item" :class="{ current: v.version === roleCard?.version }">
          <div class="version-head">
            <el-tag size="small" :type="v.version === roleCard?.version ? 'success' : 'info'" effect="plain">
              v{{ v.version }} {{ v.version === roleCard?.version ? '· 当前' : '' }}
            </el-tag>
            <span class="version-source">{{ sourceLabel(v.source) }}</span>
            <span class="version-time">{{ (v.updatedAt || '').slice(0, 16).replace('T', ' ') }}</span>
            <el-button size="small" text type="primary" @click="viewVersion(v)">查看</el-button>
            <el-button
              v-if="v.version !== roleCard?.version"
              size="small"
              text
              type="warning"
              :loading="restoringVersion === v.version"
              @click="restoreFromVersion(v.version)"
            >回滚到此版</el-button>
          </div>
          <div v-if="versionDetail?.version === v.version" class="version-detail">
            <!-- 历史版本的 11 维只读展示（与主视图同源组件；含该版本的原始 JSON 折叠面板） -->
            <PersonaArchivePanel :key="'vd-' + versionDetail.version" :card="versionDetail" compact :character-name="currentChar?.name" />
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="versionDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 角色档案可视化编辑器（G1b：表单为主 + JSON 兜底 + AI 补全缺失维 + 重新生成 + 版本历史/回滚） -->
    <PersonaEditorDialog
      v-model:visible="editorVisible"
      :card="editorCard"
      :character-name="currentChar?.name"
      @saved="onEditorSaved"
    />
    <!-- 目标新增/编辑对话框（D2 F7：标题/描述/优先级） -->
    <el-dialog v-model="goalDialog.visible" :title="goalDialog.editId ? '编辑目标' : '新增目标'" width="520px" top="8vh">
      <el-form :model="goalDialog.form" label-width="80px">
        <el-form-item label="目标标题" required>
          <el-input v-model="goalDialog.form.title" maxlength="200" show-word-limit placeholder="一句话：如 修复与旧友的关系（必填）" />
        </el-form-item>
        <el-form-item label="目标描述">
          <el-input v-model="goalDialog.form.description" type="textarea" :rows="3" maxlength="500" show-word-limit
            placeholder="背景/动机/成功标准（选填，40-80 字为宜）" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-rate v-model="goalDialog.form.priority" :max="5" />
          <span class="gp-hint">高优先级目标优先注入感知【当前目标】</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="goalDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="savingGoal" @click="saveGoal">保存</el-button>
      </template>
    </el-dialog>
    <!-- 核心块新增/编辑对话框（M3：块键/显示名/内容/重要度/agent 可编辑标识） -->
    <el-dialog v-model="coreBlockDialog.visible" :title="coreBlockDialog.editId ? '编辑核心块' : '新建核心块'" width="560px" top="8vh">
      <el-form :model="coreBlockDialog.form" label-width="110px">
        <el-form-item v-if="!coreBlockDialog.editId" label="块键" required>
          <el-select v-model="coreBlockDialog.form.blockKey" filterable allow-create default-first-option style="width: 100%"
            placeholder="选择标准键或输入 custom: 自定义键">
            <el-option label="identity · 身份" value="identity" />
            <el-option label="personality · 性格" value="personality" />
            <el-option label="beliefs · 核心信念" value="beliefs" />
            <el-option label="values · 价值观" value="values" />
            <el-option label="goals · 长期目标" value="goals" />
            <el-option label="relations · 关键关系" value="relations" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="块键">
          <el-tag effect="plain">{{ coreBlockKeyLabel(coreBlockDialog.form.blockKey) }}</el-tag>
        </el-form-item>
        <el-form-item label="块显示名">
          <el-input v-model="coreBlockDialog.form.blockName" maxlength="100" placeholder="如 核心信念（选填，缺省用块键/标准名）" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="coreBlockDialog.form.content" type="textarea" :rows="5" maxlength="400" show-word-limit
            placeholder="核心块内容（≤400 字，恒在注入上下文；如：守护家园高于一切）" />
        </el-form-item>
        <el-form-item label="重要度">
          <el-rate v-model="coreBlockDialog.form.importance" :max="5" />
          <span class="gp-hint">决定恒在注入顺序（高重要度优先注入）</span>
        </el-form-item>
        <el-form-item label="agent 自编辑">
          <el-switch v-model="coreBlockDialog.form.editableByAgent" :active-value="1" :inactive-value="0"
            active-text="允许 AI 自编辑" inactive-text="仅用户可改（只读块）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="coreBlockDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="savingCoreBlock" @click="saveCoreBlock">保存</el-button>
      </template>
    </el-dialog>

    <!-- M4 L2 记忆编辑对话框（版本链更新：新条 version+1 + 旧条软删 + 旧条自动归档 superseded） -->
    <el-dialog v-model="editMemoryDialog.visible" title="编辑记忆（版本递增）" width="560px">
      <div class="edit-memory-hint">更新后自动生成新版本（version+1），旧版本写入归档（原因=被覆盖），读取以最新版本为准。</div>
      <el-form label-width="70px">
        <el-form-item label="内容" required>
          <el-input v-model="editMemoryDialog.form.content" type="textarea" :rows="6" show-word-limit
            placeholder="记忆内容（更新后旧版自动归档 superseded）" />
        </el-form-item>
        <el-form-item label="重要度">
          <el-rate v-model="editMemoryDialog.form.importance" :max="5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editMemoryDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="editMemoryDialog.saving" @click="saveEditMemory">保存</el-button>
      </template>
    </el-dialog>

    <!-- M5 注入预览对话框（分场景注入组合：各段文本 + 命中记忆得分 + token 预算） -->
    <el-dialog v-model="injectionDialog.visible" title="记忆注入预览（M5 分场景注入）" width="720px">
      <div class="ip-controls">
        <el-select v-model="injectionDialog.scenario" size="small" style="width: 150px" @change="loadInjectionPreview">
          <el-option v-for="s in injectionScenarios" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-input v-model="injectionDialog.query" size="small" placeholder="检索 query（决策/感知情境文本，可空）" style="width: 220px" clearable @keyup.enter="loadInjectionPreview" />
        <el-button size="small" type="primary" :loading="injectionDialog.loading" @click="loadInjectionPreview">预览</el-button>
        <span v-if="injectionDialog.data" class="ip-total dim">合计 {{ injectionDialog.data.totalTokens }} token · 场景 {{ injectionDialog.scenario }}</span>
      </div>
      <div v-if="injectionDialog.data" class="ip-body">
        <div v-if="injectionDialog.data.segments.length === 0" class="side-empty">无内容——该角色当前无可注入记忆（各段均空）</div>
        <div v-for="seg in injectionDialog.data.segments" :key="seg.key" class="ip-seg">
          <div class="ip-seg-head">
            <span class="mono ip-key">{{ seg.key }}</span>
            <span class="ip-title">{{ seg.title }}</span>
            <span class="dim mono">≈ {{ seg.tokens }} token</span>
          </div>
          <pre class="ip-text">{{ seg.text }}</pre>
          <div v-if="seg.hits && seg.hits.length" class="ip-hits">
            <div v-for="h in seg.hits" :key="h.id" class="ip-hit">
              <span class="mono dim">#{{ h.id }}</span>
              <el-tag size="small" effect="plain" :type="hitTypeTag(h.type)">{{ hitTypeLabel(h.type) }}</el-tag>
              <span class="dim">重要度 {{ h.importance }} · 得分 {{ h.score }}</span>
              <el-tag v-if="h.stale" size="small" type="warning">可能过时</el-tag>
              <span class="ip-hit-content">{{ h.content }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-else class="dim">选择场景后点击「预览」，查看该角色实际注入对话/决策/感知/反思的组装组合与预算消耗。</div>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * NPC 角色页（前端布局重构 V1.0，设计文档 §3.5.1；O6 G1a 起角色卡→角色档案（11 维，只读））。
 * <p>职责：特殊型角色（NPC）的管理 + 生成 + 展示全能力——
 * 左栏角色列表（头像/姓名/头衔/重要度/类型/主角标记，类型筛选，新增/编辑/删除）；
 * 右栏角色详情分 Tab：概览（基础档案只读）／角色档案（11 维，只读：服务端 personaRenderText 按维切分，
 * 由 {@code PersonaArchivePanel} 渲染，含"原始 JSON"折叠面板；编辑入口置灰，编辑器属 G1b）／
 * Prompt（system_prompt 已随 G1a 废弃，仅保留说明）／记忆（长期记忆列表+手动抽取）。</p>
 * <p>数据来源：/api/projects/{id}/characters、/api/characters/{id}/card(versions)、memories 系列接口。</p>
 */
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchCharacters, createCharacter, updateCharacter, deleteCharacter,
  generateCharacterCard, fetchCharacterCard, fetchCharacterCardVersions, editCharacterCard,
  restoreCharacterCard,
  fetchMemories, deleteMemory, extractCharacterMemories, fetchWorkingMemories,
  fetchCoreMemories, createCoreBlock, updateCoreBlock, deleteCoreBlock,
  updateMemory, manualArchive,
  fetchInjectionPreview, fetchMemoryHealth, consolidateMemory,
  fetchFuzzStates, fetchFuzzDetail, triggerFuzz, fetchMemoryLineage, fetchInjectRouteTrace,
  fetchGoalPlan, createGoal, updateGoal, deleteGoal, generateGoals,
  fetchWorldLocations,
  fetchNpcState,
  fetchNpcStateHistory,
  fetchForesightRecords, fetchStateDeltas, fetchConflictRecords,
  // O6 G2 全生命周期阶段机：项目全 NPC 发育概览（年龄/阶段/认知 LOD/空间源，只读）
  fetchGrowthOverview,
  // O6 G3 人格演化 + 社会面具：偏移日志（含证据链）+ 面具状态（真实/表达 PAD 分离，只读）
  fetchGrowthShiftLogs, fetchGrowthMaskStatus
} from '@/shared/api'
import RelationTopology from '../components/RelationTopology.vue'
import PersonaArchivePanel from '../components/PersonaArchivePanel.vue'
// G1b：11 维档案可视化编辑器（表单为主 + JSON 兜底 + AI 补全缺失维 + 版本历史/回滚）
import PersonaEditorDialog from '../components/PersonaEditorDialog.vue'
// 档案来源中文名（与 PersonaArchivePanel 同源，避免两处映射漂移）
import { sourceLabel } from '../personaArchive'
// 统一身份引用（G1a：special:/crowd: 已废弃，一律 npc:{id}）
import { npcRef } from '@/shared/npcRef'
import EChart from '@/shared/components/EChart.vue'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

// 全局拓扑页「补充」跳转参数：?from=topology&name=角色名 → 自动打开新增弹窗并预填角色名，保存后回跳拓扑页
const fromTopology = route.query.from === 'topology'
const supplementName = route.query.name ? String(route.query.name) : ''

// ===== 左栏：角色列表 =====
const characters = ref([])
const currentChar = ref(null)
const typeFilter = ref(null)
const generatingMap = reactive({})
const generatingAll = ref(false)

// 类型筛选后的角色列表
const filteredChars = computed(() => {
  if (!typeFilter.value) return characters.value
  return characters.value.filter(c => c.type === typeFilter.value)
})

// 「关系拓扑」Tab 刷新键：角色集合变化（新增/删除）时改变该值，触发拓扑图重新拉取
const charRefreshKey = computed(() => characters.value.map(c => c.id).join(','))

// ===== 右栏：详情 Tab =====
const infoTab = ref('overview')
const roleCard = ref(null)
const memories = ref([])
const memoryLoading = ref(false)
const extracting = ref(false)
// O6 G2 全生命周期阶段机：项目全 NPC 发育概览（id → {age, stage, stageLabel, tier, ...}，概览 Tab 展示）
const growthMap = ref({})
// O6 G3 人格演化 + 社会面具（「人格与面具」Tab 只读观测：偏移日志 + 面具真实/表达 PAD）
const shiftLogs = ref([])
const maskStatus = ref(null)
const pmLoading = ref(false)
// M2 L1 工作记忆（active 工作项：草稿/任务/上下文/感知）
const workingMemories = ref([])
const workingMemoryLoading = ref(false)
// M3 L3 核心记忆（静态基线只读 + 动态核心块 CRUD）
const coreBlocks = ref([])
const coreBaselineText = ref('')
const coreMemoryLoading = ref(false)
const savingCoreBlock = ref(false)
const coreBlockDialog = reactive({
  visible: false, editId: null,
  form: { blockKey: 'beliefs', blockName: '', content: '', importance: 3, editableByAgent: 1 }
})
// M4 L2 记忆编辑对话框（版本链更新：新条 version+1 + 旧条软删 + 旧条自动归档 superseded）
const editMemoryDialog = reactive({ visible: false, editId: null, saving: false, form: { content: '', importance: 3 } })
// M5 记忆治理健康度（归档量/版本链/分层分布——角色维度） + 注入预览对话框 + 巩固编排
const memoryHealth = ref(null)
const healthLoading = ref(false)
const injectionDialog = reactive({ visible: false, scenario: 'dialog', query: '', loading: false, data: null })
// O1 记忆内核扩展：L1 短期模糊状态（艾宾浩斯渐进遗忘 + 睡眠清洗；只读展示 + 手动触发）
const fuzzStates = ref([])
const fuzzLoading = ref(false)
const fuzzTriggerId = ref(null)
// O2 决策与协调（P1-1 前瞻推演 / P1-2 state delta / P1-3 冲突协商；只读回看）
const dcForesight = ref([])
const dcConflicts = ref([])
const dcDeltas = ref([])
const dcLoading = ref(false)
const injectionScenarios = [
  { value: 'dialog', label: '对话 dialog' },
  { value: 'decision', label: '决策 decision' },
  { value: 'perception', label: '感知 perception' },
  { value: 'reflection', label: '反思 reflection' }
]

// ===== 目标/计划（D2 F7） =====
const goals = ref([])
const todayPlan = ref(null)
const todayPlanSummary = ref('')
const generatingGoals = ref(false)
const savingGoal = ref(false)
const goalDialog = reactive({ visible: false, editId: null, form: { title: '', description: '', priority: 3 } })

// ===== 状态（M3：NPC 内部状态——需求/心情/情绪/urgency；M4：历史曲线） =====
const npcState = ref(null)
const stateLoading = ref(false)

// M4：状态历史曲线（actor_state_history 快照；引擎逐游戏小时采样，保留上限 168）
const stHistory = ref([])
const stCurveNeed = ref('hunger')
const npcStateHistoryMax = 168
const stCurveNeeds = [
  { value: 'hunger', label: '饥饿' },
  { value: 'thirst', label: '口渴' },
  { value: 'energy', label: '精力' },
  { value: 'bladder', label: '膀胱' },
  { value: 'health', label: '健康' },
  { value: 'mood', label: '心情' },
  { value: 'social', label: '社交' },
  { value: 'fun', label: '趣味' },
  { value: 'hygiene', label: '清洁' }
]
const stCurveOption = computed(() => {
  const pts = stHistory.value
  const key = stCurveNeed.value
  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const p = params[0]
        if (!p) return ''
        const raw = pts[p.dataIndex]
        const lbl = stCurveNeeds.find(n => n.value === key)?.label || key
        return `${fmtGameClock(raw?.gameSecond)}<br/>${lbl}：<b>${raw?.[key] ?? '—'}</b>`
          + (raw?.urgencyLevel ? `<br/><span style="color:#ff6b6b">紧急 Lv${raw.urgencyLevel}</span>` : '')
      }
    },
    grid: { left: 44, right: 20, top: 30, bottom: 32 },
    xAxis: { type: 'category', data: pts.map(p => fmtGameHour(p.gameSecond)), axisLabel: { fontSize: 10 } },
    yAxis: { type: 'value', min: 0, max: 100 },
    series: [{
      type: 'line', data: pts.map(p => p[key] ?? null), smooth: true, symbol: 'circle', symbolSize: 5,
      lineStyle: { width: 2 },
      areaStyle: { opacity: 0.12 },
      markLine: {
        silent: true,
        data: [
          { yAxis: 30, lineStyle: { color: '#ffb84d', type: 'dashed' }, label: { formatter: '轻度 30' } },
          { yAxis: 15, lineStyle: { color: '#ff6b6b', type: 'dashed' }, label: { formatter: '强烈 15' } }
        ]
      }
    }]
  }
})

/** 状态 Tab 需求清单（9 项：生理四需 + 健康 + 清洁/社交/趣味 + 舒适） */
const NEEDS = [
  { key: 'hunger', label: '饥饿' },
  { key: 'thirst', label: '口渴' },
  { key: 'energy', label: '精力' },
  { key: 'bladder', label: '膀胱' },
  { key: 'health', label: '健康' },
  { key: 'hygiene', label: '清洁' },
  { key: 'social', label: '社交' },
  { key: 'fun', label: '趣味' },
  { key: 'comfort', label: '舒适' }
]

// ===== 对话框 =====
const charDialog = reactive({ visible: false, editId: null, saving: false, autoGen: true, form: { name: '', title: '', detail: '', type: 'special', importance: 3, isProtagonist: 0, location: '' } })
// 档案编辑器（G1b 预留）：只保留 personaJson——systemPrompt 字段随 G1a 废弃（已从入参/表单移除）
const cardDialog = reactive({ visible: false, persona: '', saving: false })
const versionDialog = ref(false)
const versionLoading = ref(false)
const versions = ref([])
const versionDetail = ref(null)

/** 加载角色列表 */
async function loadChars() {
  try {
    characters.value = await fetchCharacters(projectId)
    if (currentChar.value) {
      currentChar.value = characters.value.find(c => c.id === currentChar.value.id) || null
      if (currentChar.value) await loadRoleCard()
    } else if (characters.value.length > 0) {
      await selectChar(characters.value[0])
    }
  } catch (e) { ElMessage.error(e.message || '加载角色失败') }
}

/** 选中角色：加载角色卡 + 记忆 + （目标/计划，若当前在目标 Tab）（keepTab=true 时保持当前 Tab，用于关系拓扑图内点选角色） */
async function selectChar(ch, keepTab = false) {
  currentChar.value = ch
  if (!keepTab) infoTab.value = 'overview'
  await loadRoleCard()
  await loadMemories()
  await loadGrowthOverview()
  if (infoTab.value === 'goalplan') await loadGoalPlan()
}

/** 关系拓扑图内点击节点 → 在左侧选中该角色（保持当前「关系拓扑」Tab，图内立即高亮其关联） */
async function selectCharFromTopology(id) {
  const ch = characters.value.find(c => c.id === id)
  if (!ch) return
  // 图内点选也同步左侧列表选中态；keepTab 保持停留在「关系拓扑」Tab
  await selectChar(ch, true)
}

/** 关系拓扑图内点击空白画布 → 取消左侧选中角色（selectedCharacterId→null，拓扑图全部节点恢复清晰显示；保持停留在「关系拓扑」Tab） */
function clearSelectionFromTopology() {
  currentChar.value = null
  roleCard.value = null
  memories.value = []
  goals.value = []
  todayPlan.value = null
  todayPlanSummary.value = ''
}

/** 当日计划解析后的条目（planToday JSON → items[]，非法/空返回 []） */
const todayPlanItems = computed(() => {
  if (!todayPlan.value) return []
  try {
    const p = typeof todayPlan.value === 'string' ? JSON.parse(todayPlan.value) : todayPlan.value
    return Array.isArray(p?.items) ? p.items.slice().sort((a, b) => (a.hour || 0) - (b.hour || 0)) : []
  } catch (_) { return [] }
})

/** 加载当前角色最新角色卡 */
async function loadRoleCard() {
  roleCard.value = null
  if (!currentChar.value) return
  try { roleCard.value = await fetchCharacterCard(currentChar.value.id) } catch (_) { roleCard.value = null }
}

/** 加载项目全 NPC 发育概览（O6 G2：年龄/阶段/认知 LOD/空间源；旧 jar 无端点静默 → 展示 —） */
async function loadGrowthOverview() {
  try {
    const r = await fetchGrowthOverview(projectId)
    const npcs = Array.isArray(r?.npcs) ? r.npcs : []
    const m = {}
    for (const n of npcs) {
      if (n && n.id != null) m[n.id] = n
    }
    growthMap.value = m
  } catch (_) { growthMap.value = {} }
}

/** 当前角色发育条目（概览 Tab 用；无数据返回 null） */
function growthOf(ch) {
  return (ch && growthMap.value[ch.id]) || null
}

/** 加载「人格与面具」Tab 数据（O6 G3：偏移日志 + 面具状态；旧 jar 无端点静默 → 空态） */
async function loadPersonaMask() {
  if (!currentChar.value) { shiftLogs.value = []; maskStatus.value = null; return }
  pmLoading.value = true
  try {
    const ref = npcRef(currentChar.value.id)
    const [logs, mask] = await Promise.all([
      fetchGrowthShiftLogs(projectId, ref).catch(() => ({ list: [] })),
      fetchGrowthMaskStatus(projectId, ref).catch(() => null)
    ])
    shiftLogs.value = Array.isArray(logs?.list) ? logs.list : []
    maskStatus.value = mask || null
  } catch (_) { shiftLogs.value = []; maskStatus.value = null }
  finally { pmLoading.value = false }
}

/** 偏移 Δ 显示（+/- 三位小数） */
function pmFmtDelta(v) {
  if (v == null) return '—'
  return (v > 0 ? '+' : '') + Number(v).toFixed(3)
}

function pmDeltaClass(v) {
  if (v == null) return ''
  if (v > 0) return 'pm-delta-up'
  if (v < 0) return 'pm-delta-down'
  return ''
}

function pmSource(s) {
  return { ai: 'AI 判定', programmatic: '程序化', breach: '破绽' }[s] || s || '—'
}

function pmMaskList(json) {
  if (!json || json === '[]') return '—'
  try { return JSON.parse(json).join('、') } catch { return json }
}

function pmFmt(v) {
  if (v == null) return '—'
  return (v > 0 ? '+' : '') + Number(v).toFixed(2)
}

/** 认知 LOD 档位中文名 */
function tierLabel(t) {
  return ({ dormant: '休眠（零 AI）', ambient: '周遭（AI 轻频）', focus: '焦点（AI 实时）' })[t] || t || '—'
}

/** 认知 LOD 档位标签色 */
function tierTag(t) {
  return ({ dormant: 'info', ambient: 'warning', focus: 'success' })[t] || 'info'
}

/** 加载当前角色记忆列表 */
async function loadMemories() {
  if (!currentChar.value) { memories.value = []; return }
  memoryLoading.value = true
  try {
    const page = await fetchMemories(projectId, { characterId: currentChar.value.id, page: 1, size: 50 })
    memories.value = page.list || []
  } catch (_) { memories.value = [] }
  finally { memoryLoading.value = false }
  await loadWorkingMemories()
  await loadCoreMemories()
  await loadMemoryHealth()
  await loadFuzzStates()
  await loadDecisionCoord()
}

// ===== M5 记忆治理健康度 + 注入预览 + 巩固编排 =====

/** 加载当前角色记忆治理健康度（归档量/版本链/分层分布，角色维度 npc:{id}） */
async function loadMemoryHealth() {
  if (!currentChar.value) { memoryHealth.value = null; return }
  healthLoading.value = true
  try {
    const r = await fetchMemoryHealth(projectId, { characterRef: npcRef(currentChar.value.id) })
    memoryHealth.value = r?.data || null
  } catch (_) { memoryHealth.value = null }
  finally { healthLoading.value = false }
}

/** 打开注入预览对话框并立即预览（默认 dialog 场景） */
function openInjectionPreview() {
  injectionDialog.visible = true
  injectionDialog.data = null
  injectionDialog.query = ''
  loadInjectionPreview()
}

/** 按当前场景组装注入预览（分场景：dialog/decision/perception/reflection） */
async function loadInjectionPreview() {
  if (!currentChar.value) return
  injectionDialog.loading = true
  try {
    const r = await fetchInjectionPreview(projectId, {
      characterRef: npcRef(currentChar.value.id),
      scenario: injectionDialog.scenario,
      query: injectionDialog.query || undefined
    })
    injectionDialog.data = r?.data || null
  } catch (e) { ElMessage.error(e.message || '注入预览失败') }
  finally { injectionDialog.loading = false }
}

/** 手动触发统一巩固编排（M5：L1 TTL/巩固扫描 + 归档超龄/保留/二次清理，scene=memory_manage trace） */
async function doMaintenance() {
  try {
    await ElMessageBox.confirm('触发统一维护（L1 工作记忆 TTL 清理/巩固扫描 + 归档超龄扫描/保留滚动/二次清理）？', '统一维护', { type: 'warning' })
  } catch (_) { return }
  try {
    const r = await consolidateMemory({ maintenance: true })
    ElMessage.success(`统一维护完成：${r.data?.maintenanceSteps ?? 0} 步`)
    await loadMemories()
  } catch (e) { ElMessage.error(e.message || '统一维护失败') }
}

/** 归档原因分组摘要（age/superseded/downgraded/evicted/manual） */
function reasonSummary(byReason) {
  if (!byReason || Object.keys(byReason).length === 0) return '-'
  return Object.entries(byReason).map(([k, v]) => `${k}:${v}`).join(' · ')
}

/** 命中记忆类型显示名（预览命中 HitVO：仅 type，无 subtype） */
function hitTypeLabel(t) {
  return ({ semantic: '语义', episodic: '情节', reflection: '反思', plan: '计划' })[t] || t || '记忆'
}

/** 命中记忆类型标签色（预览命中） */
function hitTypeTag(t) {
  return ({ semantic: 'info', episodic: 'warning', reflection: 'danger', plan: 'success' })[t] || 'info'
}

/** 加载当前角色 L3 核心记忆（M3：静态基线只读 + 动态核心块列表） */
async function loadCoreMemories() {
  if (!currentChar.value) { coreBlocks.value = []; coreBaselineText.value = ''; return }
  coreMemoryLoading.value = true
  try {
    const vo = await fetchCoreMemories(projectId, { characterId: currentChar.value.id })
    coreBaselineText.value = vo?.baselineText || ''
    coreBlocks.value = vo?.blocks || []
  } catch (_) { coreBlocks.value = []; coreBaselineText.value = '' }
  finally { coreMemoryLoading.value = false }
}

/** 打开核心块新增/编辑对话框（M3；编辑时预填，块键编辑态只读） */
function openCoreBlockDialog(b = null) {
  if (b) {
    coreBlockDialog.editId = b.id
    coreBlockDialog.form = {
      blockKey: b.blockKey, blockName: b.blockName || '', content: b.content || '',
      importance: b.importance || 3, editableByAgent: b.editableByAgent === 0 ? 0 : 1
    }
  } else {
    coreBlockDialog.editId = null
    coreBlockDialog.form = { blockKey: 'beliefs', blockName: '', content: '', importance: 3, editableByAgent: 1 }
  }
  coreBlockDialog.visible = true
}

/** 保存核心块（新建/编辑，M3；编辑走版本链 version+1） */
async function saveCoreBlock() {
  const f = coreBlockDialog.form
  if (!f.blockKey || !f.blockKey.trim()) return ElMessage.warning('请选择/输入块键')
  if (!f.content || !f.content.trim()) return ElMessage.warning('核心块内容不能为空')
  savingCoreBlock.value = true
  try {
    if (coreBlockDialog.editId) {
      await updateCoreBlock(projectId, coreBlockDialog.editId, {
        blockName: f.blockName, content: f.content, importance: f.importance, editableByAgent: f.editableByAgent
      })
      ElMessage.success('核心块已更新（版本 +1）')
    } else {
      await createCoreBlock(projectId, {
        characterRef: npcRef(currentChar.value.id), blockKey: f.blockKey.trim(),
        blockName: f.blockName, content: f.content, importance: f.importance, editableByAgent: f.editableByAgent
      })
      ElMessage.success('核心块已创建（恒在注入上下文）')
    }
    coreBlockDialog.visible = false
    await loadCoreMemories()
  } catch (e) {
    ElMessage.error(e.message || '保存核心块失败')
  } finally { savingCoreBlock.value = false }
}

/** 删除核心块（M3，软删确认） */
async function doDeleteCoreBlock(b) {
  try {
    await ElMessageBox.confirm(`确认删除核心块「${b.blockName || b.blockKey}」？删除后不再恒在注入。`, '删除确认', { type: 'warning' })
  } catch (_) { return }
  try {
    await deleteCoreBlock(projectId, b.id)
    ElMessage.success('核心块已删除')
    await loadCoreMemories()
  } catch (e) {
    ElMessage.error(e.message || '删除核心块失败')
  }
}

/** 手动归档核心块（M4：写归档原文冷存 + 软删，不再恒在注入但仍可检索/还原） */
async function doArchiveCoreBlock(b) {
  try {
    await ElMessageBox.confirm(`确认归档核心块「${b.blockName || b.blockKey}」？将写入记忆归档（冷存储），不再恒在注入，但仍可检索/还原。`, '归档确认', { type: 'warning' })
  } catch (_) { return }
  try {
    const r = await manualArchive(projectId, { sourceTable: 'actor_core_memory', ids: [b.id] })
    ElMessage.success(`核心块已归档（${r.archived} 条）`)
    await loadCoreMemories()
  } catch (e) {
    ElMessage.error(e.message || '归档核心块失败')
  }
}

/** 打开 L2 记忆编辑对话框（M4 版本链：更新后新条 version+1，旧条软删+自动归档 superseded） */
function openEditMemoryDialog(m) {
  editMemoryDialog.editId = m.id
  editMemoryDialog.form.content = m.content || ''
  editMemoryDialog.form.importance = m.importance || 3
  editMemoryDialog.visible = true
}

/** 保存 L2 记忆更新（M4 版本链） */
async function saveEditMemory() {
  const f = editMemoryDialog.form
  if (!f.content || !f.content.trim()) return ElMessage.warning('记忆内容不能为空')
  editMemoryDialog.saving = true
  try {
    await updateMemory(editMemoryDialog.editId, { projectId, content: f.content, importance: f.importance })
    ElMessage.success('记忆已更新（版本 +1，旧版已归档）')
    editMemoryDialog.visible = false
    await loadMemories()
  } catch (e) {
    ElMessage.error(e.message || '更新记忆失败')
  } finally { editMemoryDialog.saving = false }
}

/** 手动归档 L2 长期记忆（M4：写归档原文冷存 + 软删） */
async function doArchiveMemory(m) {
  try {
    await ElMessageBox.confirm('确认归档该记忆？将写入记忆归档（冷存储），不再参与该角色记忆注入，但仍可检索/还原。', '归档确认', { type: 'warning' })
  } catch (_) { return }
  try {
    const r = await manualArchive(projectId, { sourceTable: 'actor_memory', ids: [m.id] })
    ElMessage.success(`记忆已归档（${r.archived} 条）`)
    await loadMemories()
  } catch (e) {
    ElMessage.error(e.message || '归档记忆失败')
  }
}

/** 核心块标准键显示名（M3） */
function coreBlockKeyLabel(key) {
  return { identity: '身份', personality: '性格', beliefs: '核心信念', values: '价值观', goals: '长期目标', relations: '关键关系' }[key] || key
}

/** 核心块标签色（M3：自定义=success / 标准=primary） */
function coreBlockTag(b) {
  return (b.blockKey || '').startsWith('custom:') ? 'success' : 'primary'
}

/** 加载当前角色 L1 工作记忆（M2：active 草稿/任务/上下文/感知，只读展示） */
async function loadWorkingMemories() {
  if (!currentChar.value) { workingMemories.value = []; return }
  workingMemoryLoading.value = true
  try {
    const page = await fetchWorkingMemories(projectId, { characterId: currentChar.value.id, status: 'active', page: 1, size: 50 })
    workingMemories.value = page.list || []
  } catch (_) { workingMemories.value = [] }
  finally { workingMemoryLoading.value = false }
}

/** L1 工作项类型显示名（M2） */
function workingTypeLabel(w) {
  const t = w.itemType || 'draft'
  return { draft: '草稿', task: '任务', context: '上下文', perception: '感知' }[t] || t
}

/** L1 工作项类型标签色（M2：草稿=info / 任务=primary / 上下文=success / 感知=warning） */
function workingTypeTag(w) {
  const t = w.itemType || 'draft'
  return { draft: 'info', task: 'primary', context: 'success', perception: 'warning' }[t] || 'info'
}

// ===== O1 记忆内核扩展：L1 短期模糊 =====

/** 加载当前角色 L1 模糊状态（active 优先；含蒸馏/归档历史，上限 100） */
async function loadFuzzStates() {
  if (!currentChar.value) { fuzzStates.value = []; return }
  fuzzLoading.value = true
  try {
    const list = await fetchFuzzStates(projectId, { characterRef: npcRef(currentChar.value.id) })
    fuzzStates.value = Array.isArray(list) ? list : (list?.data || [])
  } catch (_) { fuzzStates.value = [] }
  finally { fuzzLoading.value = false }
}

/** 手动触发单条模糊（无视艾宾浩斯间隔立即执行一次，O1a 调试/冒烟）。 */
async function doTriggerFuzz(f) {
  fuzzTriggerId.value = f.memoryWorkingId
  try {
    const r = await triggerFuzz(projectId, { memoryWorkingId: f.memoryWorkingId })
    ElMessage.success(r?.triggered ? `已模糊（fuzz_level 上升）` : '未触发（状态非 active）')
    await loadFuzzStates()
  } catch (e) {
    ElMessage.error(e.message || '触发失败')
  } finally { fuzzTriggerId.value = null }
}

/** 模糊度标签色（0.7+ 深 / 0.3+ 中 / 低清晰） */
function fuzzLevelTag(f) { return f >= 0.7 ? 'danger' : f >= 0.3 ? 'warning' : 'success' }

/** 模糊状态标签色（active=primary / distilled=success / archived=info） */
function fuzzStatusTag(s) { return s === 'distilled' ? 'success' : s === 'archived' ? 'info' : 'primary' }

/** 模糊状态显示名 */
function fuzzStatusLabel(s) {
  return { active: '模糊中', distilled: '已蒸馏 L2', archived: '已归档' }[s] || s || 'active'
}

// ===== O2 决策与协调（P1-1 前瞻推演 / P1-2 state delta / P1-3 冲突协商） =====

/** 加载当前角色 O2 记录：前瞻推演 + 冲突协商 + 状态增量（npcRef 过滤；R 包裹解包） */
async function loadDecisionCoord() {
  if (!currentChar.value) { dcForesight.value = []; dcConflicts.value = []; dcDeltas.value = []; return }
  dcLoading.value = true
  const ref = npcRef(currentChar.value.id)
  try {
    const [f, c, d] = await Promise.all([
      fetchForesightRecords(projectId, { npcRef: ref, limit: 20 }),
      fetchConflictRecords(projectId, { npcRef: ref, limit: 20 }),
      fetchStateDeltas(projectId, { npcRef: ref, limit: 20 })
    ])
    dcForesight.value = Array.isArray(f) ? f : (f?.data || [])
    dcConflicts.value = Array.isArray(c) ? c : (c?.data || [])
    dcDeltas.value = Array.isArray(d) ? d : (d?.data || [])
  } catch (_) { dcForesight.value = []; dcConflicts.value = []; dcDeltas.value = [] }
  finally { dcLoading.value = false }
}

/** 冲突处置显示名（P1-3 resolution code → 中文） */
function dcResolutionLabel(r) {
  return { takeover: '强占', negotiated: '协商让步', fallback_wait: '等待兜底' }[r] || r || '—'
}

/** 冲突处置标签色（强占=danger / 协商=warning / 等待兜底=info） */
function dcResolutionTag(r) {
  return { takeover: 'danger', negotiated: 'warning', fallback_wait: 'info' }[r] || 'info'
}

/** 前瞻候选 JSON → 多行「① action → consequence」文本（P1-1 展示） */
function prettyCandidates(json) {
  try {
    const arr = JSON.parse(json || '[]')
    if (!Array.isArray(arr)) return ''
    return arr.map((c, i) => `${i + 1}. ${c.action || ''}${c.consequence ? ' → ' + c.consequence : ''}`).join('\n')
  } catch (_) { return '' }
}

/** state delta changes_json → 可读摘要（P1-2：优先取 action/location 变化，其余字段逐一列出） */
function dcActionOf(changesJson) {
  try {
    const obj = JSON.parse(changesJson || '{}')
    const parts = []
    for (const [k, v] of Object.entries(obj)) {
      if (v && typeof v === 'object') {
        const from = v.from, to = v.to
        parts.push(`${k}: ${from === undefined ? '∅' : JSON.stringify(from)} → ${to === undefined ? '∅' : JSON.stringify(to)}`)
      } else {
        parts.push(`${k}: ${JSON.stringify(v)}`)
      }
    }
    return parts.join('；') || '无变化'
  } catch (_) { return changesJson || '无变化' }
}

/** 游戏秒 → D天 HH:00 文本（L1 复查时刻展示） */
function gameSecondText(gs) {
  if (gs == null || gs === '') return '—'
  const day = Math.floor(gs / 86400)
  const hour = Math.floor((gs % 86400) / 3600)
  return `D${day} ${String(hour).padStart(2, '0')}:00`
}

/**
 * 右栏 Tab 切换回调：切到「记忆」Tab 时重新拉取记忆列表；切到「目标/计划」Tab 时拉取目标+当日计划。
 * <p>背景：记忆为「对话回复完成后异步抽取」（后端约数秒落库），而记忆列表仅在
 * 选中角色时加载一次——停留页面期间对话产生的记忆不会自动出现。切 Tab 时重新拉取保证所见即最新；
 * 目标/计划由世界引擎跨日/决策异步生成，同理切 Tab 时刷新。</p>
 *
 * @param name 切换到的 Tab 名称（overview/prompt/memory/goalplan）
 */
function onInfoTabChange(name) {
  if (name === 'memory') loadMemories()
  if (name === 'goalplan') loadGoalPlan()
  if (name === 'state') loadNpcState()
  if (name === 'personamask') loadPersonaMask()
}

/** M3+M4：加载当前角色内部状态 + 历史曲线（actor_npc_state 读接口 + actor_state_history 快照；失败静默保持空态） */
async function loadNpcState() {
  if (!currentChar.value) { npcState.value = null; stHistory.value = []; return }
  stateLoading.value = true
  try {
    const ref = npcRef(currentChar.value.id)
    npcState.value = await fetchNpcState(projectId, ref)
    // M4：历史曲线快照（失败不影响当前值展示——旧 jar 无历史端点时静默）
    try {
      stHistory.value = await fetchNpcStateHistory(projectId, ref, npcStateHistoryMax)
    } catch (_) { stHistory.value = [] }
  } catch (_) { /* 端点失败静默（旧 jar 无此端点时保持空态） */ }
  finally { stateLoading.value = false }
}

/** M3：状态展示工具——需求颜色/数值色调/心情/主导情绪/PAD */
function stNeedColor(v) {
  if (v == null) return '#9db8e8'
  if (v < 30) return '#ff6b6b'
  if (v < 60) return '#ffb84d'
  return '#52d68a'
}
function stNeedTone(v) {
  if (v == null) return ''
  if (v < 30) return 'low'
  if (v < 60) return 'mid'
  return ''
}
function stMoodColor(v) {
  if (v == null) return '#9db8e8'
  if (v < 40) return '#ff6b6b'
  if (v < 65) return '#ffb84d'
  return '#52d68a'
}
function stEmotionLabel(e) {
  return ({
    calm: '平静', happy: '高兴', angry: '愤怒', sad: '悲伤',
    fearful: '恐惧', surprised: '惊讶', disgusted: '厌恶'
  })[e] || e || '—'
}
function stPadPercent(v) {
  if (v == null) return 50
  return Math.round(((Math.max(-1, Math.min(1, v)) + 1) / 2) * 100)
}
function stPadColor(v) {
  if (v == null) return '#9db8e8'
  return v >= 0 ? '#52d68a' : '#ff6b6b'
}
function stFmtPad(v) {
  if (v == null) return '—'
  return (v >= 0 ? '+' : '') + v.toFixed(2)
}

/** M3：心情数值文字档位（心情越低越差） */
function stMoodText(v) {
  if (v == null) return ''
  if (v < 35) return '低落'
  if (v < 60) return '一般'
  if (v < 80) return '不错'
  return '极佳'
}

/** M3：主导情绪标签色（默认情绪 → Element 色；自定义情绪走 success） */
function stEmotionTag(e) {
  return ({
    calm: 'info', happy: 'success', angry: 'danger', sad: 'warning',
    fearful: 'warning', surprised: 'primary', disgusted: 'danger'
  })[e] || 'success'
}

/** M3：PAD 数值色类（正绿负红，用于右侧数值文本） */
function stPadColorClass(v) {
  if (v == null) return ''
  return v >= 0 ? 'pos' : 'neg'
}

/** 游戏时刻秒 → HH:MM 时钟（空返回 '—'） */
function fmtGameClock(sec) {
  if (sec == null || sec < 0) return '—'
  const h = Math.floor(sec / 3600) % 24
  const m = Math.floor((sec % 3600) / 60)
  return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0')
}

/** M4：游戏时刻秒 → 「第N日 HH时」（曲线 x 轴标签） */
function fmtGameHour(sec) {
  if (sec == null || sec < 0) return '—'
  const h = Math.floor(sec / 3600)
  return `第${Math.floor(h / 24) + 1}日 ${String(h % 24).padStart(2, '0')}时`
}

/** 加载当前角色目标列表 + 当日计划（goal-plan 聚合端点） */
async function loadGoalPlan() {
  if (!currentChar.value) { goals.value = []; todayPlan.value = null; todayPlanSummary.value = ''; return }
  try {
    const data = await fetchGoalPlan(projectId, currentChar.value.id)
    goals.value = data.goals || []
    todayPlan.value = data.planToday || null
    todayPlanSummary.value = data.planSummary || ''
  } catch (_) { /* 端点失败静默（旧 jar 无此端点时保持空态） */ }
}

/** AI 生成目标（goal_gen：无 active 目标时生成 1~3 个，失败静默） */
async function doGenerateGoals() {
  if (!currentChar.value) return
  generatingGoals.value = true
  try {
    const created = await generateGoals(projectId, currentChar.value.id)
    if (created > 0) {
      ElMessage.success(`已生成 ${created} 个目标`)
      await loadGoalPlan()
    } else {
      ElMessage.info('未生成新目标（可能已有进行中目标，或 AI 预算不可用）')
      await loadGoalPlan()
    }
  } catch (e) { ElMessage.error(e.message || '生成目标失败') }
  finally { generatingGoals.value = false }
}

/** 打开目标新增/编辑对话框 */
function openGoalDialog(row) {
  if (row) {
    goalDialog.editId = row.id
    Object.assign(goalDialog.form, { title: row.title, description: row.description || '', priority: row.priority || 3 })
  } else {
    goalDialog.editId = null
    Object.assign(goalDialog.form, { title: '', description: '', priority: 3 })
  }
  goalDialog.visible = true
}

/** 保存目标（新增/编辑） */
async function saveGoal() {
  const f = goalDialog.form
  if (!f.title || !f.title.trim()) return ElMessage.warning('请填写目标标题')
  savingGoal.value = true
  try {
    if (goalDialog.editId) {
      await updateGoal(goalDialog.editId, { title: f.title.trim(), description: f.description || null, priority: f.priority })
    } else {
      await createGoal(projectId, currentChar.value.id, { title: f.title.trim(), description: f.description || null, priority: f.priority })
    }
    ElMessage.success('目标已保存')
    goalDialog.visible = false
    await loadGoalPlan()
  } catch (e) { ElMessage.error(e.message || '保存失败') }
  finally { savingGoal.value = false }
}

/** 更新目标状态（done/abandoned，手动通道；反思 AI 判断为自动通道） */
async function doUpdateGoalStatus(g, status) {
  try {
    await updateGoal(g.id, { status })
    ElMessage.success(`目标已标记为「${goalStatusLabel(status)}」`)
    await loadGoalPlan()
  } catch (e) { ElMessage.error(e.message || '更新失败') }
}

/** 删除目标（软删） */
async function doDeleteGoal(g) {
  try {
    await ElMessageBox.confirm(`确认删除目标「${g.title}」？`, '删除确认', { type: 'warning' })
    await deleteGoal(g.id)
    ElMessage.success('目标已删除')
    await loadGoalPlan()
  } catch (_) { /* 用户取消 */ }
}

/** 目标状态中文标签 */
function goalStatusLabel(s) { return ({ active: '进行中', done: '已完成', abandoned: '已放弃' })[s] || s || '—' }
/** 目标状态标签色 */
function goalStatusTag(s) { return ({ active: 'primary', done: 'success', abandoned: 'info' })[s] || 'info' }
/** 计划条目状态中文标签 */
function planStateLabel(s) { return ({ walk: '赶路', stop: '驻足/劳作', talk: '社交', rest: '休息', idle: '空闲' })[s] || s || '' }
/** 时间格式化（YYYY-MM-DD HH:mm） */
function fmtTime(t) { return t ? String(t).slice(0, 16).replace('T', ' ') : '' }

/** 删除单条记忆（软删） */
async function doDeleteMemory(m) {
  try {
    await ElMessageBox.confirm(`确认删除该条${m.subtype === 'summary' ? '摘要' : '记忆'}？删除后不再注入后续对话。`, '删除确认', { type: 'warning' })
    await deleteMemory(m.id)
    ElMessage.success('已删除')
    await loadMemories()
  } catch (_) { /* 用户取消 */ }
}

/** 手动触发当前角色记忆抽取 */
async function doExtractMemories() {
  if (!currentChar.value) return
  extracting.value = true
  try {
    const res = await extractCharacterMemories(currentChar.value.id)
    ElMessage.success(`抽取完成，新增 ${res.added || 0} 条事实`)
    await loadMemories()
  } catch (e) { ElMessage.error(e.message || '抽取失败') }
  finally { extracting.value = false }
}

/** 打开角色新增/编辑对话框（新增默认开启「保存后自动生成角色卡」） */
function openCharDialog(row) {
  if (row) {
    charDialog.editId = row.id
    Object.assign(charDialog.form, { name: row.name, title: row.title, detail: row.detail, type: row.type, importance: row.importance, isProtagonist: row.isProtagonist, location: row.location || '' })
    charDialog.autoGen = true
  } else {
    charDialog.editId = null
    Object.assign(charDialog.form, { name: '', title: '', detail: '', type: 'special', importance: 3, isProtagonist: 0, location: '' })
    charDialog.autoGen = true
  }
  charDialog.visible = true
}

/** 全局拓扑页「补充」入口：自动打开新增弹窗并预填角色名（保存后回跳拓扑页） */
function openSupplement(name) {
  charDialog.editId = null
  Object.assign(charDialog.form, { name: name || '', title: '', detail: '', type: 'special', importance: 3, isProtagonist: 0, location: '' })
  charDialog.autoGen = true
  charDialog.visible = true
}

/**
 * 保存角色（重构后的新增逻辑）：只需输入 姓名 + 详细信息 + 重要度 + 是否主角。
 * 保存后若勾选「自动生成角色档案」，立即严格按 世界观 + 详细信息 生成 11 维角色档案，
 * 并自动选中该角色展示到「角色档案（11 维）」Tab。
 */
async function saveChar() {
  const f = charDialog.form
  if (!f.name.trim()) return ElMessage.warning('请填写角色姓名')
  if (!f.detail.trim()) return ElMessage.warning('请填写角色详细信息（AI 将严格依据它生成 11 维角色档案，只可多不可少）')
  charDialog.saving = true
  try {
    const payload = { name: f.name.trim(), title: f.title || null, detail: f.detail || null, type: f.type, importance: f.importance, isProtagonist: f.isProtagonist, location: (f.location || '').trim() || null }
    let id = charDialog.editId
    if (id) {
      await updateCharacter(id, payload)
    } else {
      const created = await createCharacter(projectId, payload)
      id = created.id
    }
    charDialog.visible = false
    await loadChars()
    // 自动生成角色档案：严格按世界观 + 详细信息生成 11 维档案（只可多不可少）
    if (charDialog.autoGen && f.detail.trim()) {
      await generateOneById(id, `「${f.name.trim()}」`)
      if (currentChar.value?.id === id) {
        await loadRoleCard()
        infoTab.value = 'card'
      }
    }
    ElMessage.success('角色已保存')
    // 全局拓扑页「补充」流程：保存成功后回到拓扑页（自动按名称全表扫描关联由后端完成）
    if (fromTopology) {
      router.replace(`/project/${projectId}/topology`)
      return
    }
  } catch (e) { ElMessage.error(e.message || '保存失败') } finally { charDialog.saving = false }
}

/** 删除角色 */
async function doRemoveChar(row) {
  try {
    await ElMessageBox.confirm(`确认删除角色「${row.name}」？`, '删除确认', { type: 'warning' })
    await deleteCharacter(row.id)
    ElMessage.success('角色已删除')
    if (currentChar.value?.id === row.id) { currentChar.value = null; roleCard.value = null }
    await loadChars()
  } catch (_) { /* 取消或失败 */ }
}

/** 按角色 ID 生成角色档案（生成后刷新列表与当前选中档案） */
async function generateOneById(id, label) {
  generatingMap[id] = true
  try {
    await generateCharacterCard(id)
    if (label) ElMessage.success(`${label}角色档案已生成`)
    await loadChars()
    if (currentChar.value?.id === id) await loadRoleCard()
  } catch (e) { ElMessage.error(e.message || '生成失败') } finally { generatingMap[id] = false }
}

/** 单角色生成角色档案 */
async function generateOne(row) {
  await generateOneById(row.id, `「${row.name}」`)
}

/**
 * 一键生成全部角色档案（串行）。
 * <p>行为：只生成「尚未生成档案」的 NPC 角色（hasCard=false），
 * 已生成档案的角色自动跳过（不重复调用 AI，避免浪费 token）；
 * 若所有角色均已生成档案，则给出友善提醒并直接返回。</p>
 */
async function generateAll() {
  // 只挑选尚未生成档案的角色；hasCard 由后端 list 接口批量返回
  const pending = characters.value.filter(c => !c.hasCard)
  const skipped = characters.value.length - pending.length
  // 全部都已生成档案：友善提醒，不做任何 AI 调用
  if (pending.length === 0) {
    ElMessage.info('所有角色均已生成角色档案，无需重复生成 🎉')
    return
  }
  generatingAll.value = true
  let ok = 0
  for (const c of pending) {
    generatingMap[c.id] = true
    try { await generateCharacterCard(c.id); ok++ } catch (e) { ElMessage.warning(`「${c.name}」生成失败：${e.message}`) }
    finally { generatingMap[c.id] = false }
  }
  generatingAll.value = false
  // 汇总：成功数 / 本次实际生成数；有跳过时额外说明已生成数量
  ElMessage.success(
    skipped > 0
      ? `一键生成完成：成功 ${ok}/${pending.length}，已跳过 ${skipped} 个已生成角色档案的角色`
      : `一键生成完成：成功 ${ok}/${pending.length}`
  )
  await loadChars()
}

/**
 * 打开角色档案 JSON 编辑对话框。
 *
 * <p><b>G1b 预留（当前不可达）</b>：「角色档案（11 维）」Tab 的编辑入口按钮已置灰，
 * 故本函数与上方 {@code cardDialog} 对话框现阶段不会被触发——保留原样是为了让 G1b 的档案编辑器
 * 只做"解禁入口 + 补校验"，不必重建 UI 与保存链路。</p>
 */
function openCardEdit() {
  if (!roleCard.value) return
  cardDialog.persona = prettyJson(roleCard.value.personaJson)
  cardDialog.visible = true
}

/** 保存角色档案（新版本，source=manual；只提交 personaJson——systemPrompt 入参已废弃） */
async function saveCard() {
  cardDialog.saving = true
  try {
    const card = await editCharacterCard(currentChar.value.id, {
      personaJson: cardDialog.persona
    })
    roleCard.value = card
    cardDialog.visible = false
    ElMessage.success(`角色档案已保存为新版本 v${card.version}`)
    await loadChars()
  } catch (e) { ElMessage.error(e.message || '保存失败') } finally { cardDialog.saving = false }
}

/** 打开版本历史对话框 */
async function openVersions() {
  versionDialog.value = true
  versionLoading.value = true
  versionDetail.value = null
  try {
    versions.value = await fetchCharacterCardVersions(currentChar.value.id)
  } catch (e) { ElMessage.error(e.message || '加载版本失败'); versions.value = [] }
  finally { versionLoading.value = false }
}

// ===== G1b：档案可视化编辑器（PersonaEditorDialog） =====
const editorVisible = ref(false)
const editorCard = ref(null)

/** 打开 11 维档案编辑器（G1b 解禁入口；角色切换/重新加载后 editorCard 跟随 roleCard） */
function openPersonaEditor() {
  if (!currentChar.value) return
  editorCard.value = roleCard.value
  editorVisible.value = true
}

/** 编辑器保存/补全/重新生成/回滚成功后：刷新主视图档案与列表标记 */
async function onEditorSaved(card) {
  roleCard.value = card || null
  if (card) {
    editorCard.value = card
    ElMessage.success(`角色档案已更新：v${card.version}`)
  }
  await loadChars()
}

/** 版本历史对话框内回滚到指定版本（保存为新版本 source=restored） */
async function restoreFromVersion(version) {
  restoringVersion.value = version
  try {
    const card = await restoreCharacterCard(currentChar.value.id, version)
    roleCard.value = card
    ElMessage.success(`已回滚到 v${version}（保存为新版本 v${card.version}）`)
    await openVersions()
    await loadChars()
  } catch (e) { ElMessage.error(e.response?.data?.message || e.message || '回滚失败') }
  finally { restoringVersion.value = null }
}
const restoringVersion = ref(null)

/** 查看某个历史版本详情 */
function viewVersion(v) {
  versionDetail.value = versionDetail.value?.version === v.version ? null : v
}

/** 重要度颜色映射 */
function impTag(i) { return i >= 4 ? 'danger' : (i >= 3 ? 'warning' : 'info') }

/** 记忆类型显示名（M1 起 type+subtype 取代 kind：semantic 摘要/事实、episodic 经历、reflection 反思、plan 计划） */
function typeLabel(m) {
  if (m.type === 'semantic') return m.subtype === 'summary' ? '摘要' : '事实'
  if (m.type === 'episodic') return '经历'
  if (m.type === 'reflection') return '反思'
  if (m.type === 'plan') return '计划'
  return '记忆'
}

/** 记忆类型标签色（摘要=primary / 事实=info / 经历=warning / 反思=danger / 计划=success） */
function typeTag(m) {
  if (m.type === 'semantic') return m.subtype === 'summary' ? 'primary' : 'info'
  if (m.type === 'episodic') return 'warning'
  if (m.type === 'reflection') return 'danger'
  if (m.type === 'plan') return 'success'
  return 'info'
}

/** 记忆条目样式类（M1 起按 type+subtype 映射到 CSS：summary/fact/observation 沿用 + reflection/plan 新增） */
function memoryClass(m) {
  if (m.type === 'semantic') return m.subtype === 'summary' ? 'summary' : 'fact'
  if (m.type === 'episodic') return 'observation'
  return m.type || 'fact'
}

/** 格式化 JSON 文本（G1b 预留的档案编辑器预填用） */
function prettyJson(text) {
  if (!text) return ''
  try { return JSON.stringify(JSON.parse(text), null, 2) } catch (_) { return text || '' }
}

// D3 F11：角色「位置」候选来源 = 世界观地点表（地点表优先，可自由输入兜底；空表回退启发式）
const locationOptions = ref([])
async function loadLocationOptions() {
  try {
    const locs = await fetchWorldLocations(projectId)
    locationOptions.value = (locs || []).map(l => l.name).filter(Boolean)
  } catch (_) { locationOptions.value = [] }
}

onMounted(async () => {
  await loadChars()
  await loadLocationOptions()
  // 全局拓扑页「补充」跳转（?from=topology&name=角色名）：加载完列表后自动打开新增弹窗并预填角色名
  if (fromTopology && supplementName) {
    await nextTick()
    openSupplement(supplementName)
  }
})
</script>

<style scoped>
/* 高度取外壳内容区 100%（替代 calc(100vh - 140px) 魔法值：顶栏/内边距/密度变化自动适应） */
.character-view { display: flex; gap: 16px; height: 100%; min-height: 0; }

/* 左栏 */
/* 左栏宽度统一取页内双栏令牌（原写死 300px；需特例时在页面内覆盖并注明原因） */
.char-side { width: var(--page-aside-w, 260px); background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 12px; display: flex; flex-direction: column; overflow-y: auto; flex-shrink: 0; }
.side-header { display: flex; align-items: center; justify-content: space-between; font-weight: 600; color: var(--text-primary); padding: 4px 2px 8px; }
.side-filter { margin-bottom: 8px; }
.side-empty { color: var(--text-secondary); font-size: 0.82rem; padding: 12px 4px; }
.char-list { flex: 1; display: flex; flex-direction: column; gap: 4px; overflow-y: auto; }
.char-item { padding: 8px 10px; border-radius: var(--radius-sm); cursor: pointer; display: flex; align-items: center; gap: 10px; transition: background 0.15s; }
.char-item:hover { background: var(--bg-hover, var(--bg-layer-2)); }
.char-item.active { background: var(--primary-bg, #ecf5ff); }
.char-avatar { flex-shrink: 0; background: var(--brand-gradient); color: #fff; }
.char-meta { flex: 1; min-width: 0; }
.char-name { font-size: 0.88rem; font-weight: 600; display: flex; align-items: center; gap: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.char-title { font-size: 0.75rem; color: var(--text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.char-ops { display: flex; gap: 2px; opacity: 0; transition: opacity .15s; flex-shrink: 0; }
.char-item:hover .char-ops { opacity: 1; }
.side-foot { border-top: 1px solid var(--border-light); padding-top: 10px; }

/* 右栏 */
.char-main { flex: 1; background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 14px 18px; display: flex; flex-direction: column; overflow: hidden; }
.main-empty { border: none; box-shadow: none; display: flex; align-items: center; justify-content: center; height: 100%; }
.detail-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.detail-name { font-size: 1.1rem; font-weight: 700; color: var(--text-primary); display: flex; align-items: center; gap: 8px; }
.detail-ops { display: flex; align-items: center; gap: 8px; }
.card-version { margin-right: 4px; }
.info-tabs { flex: 1; min-height: 0; overflow-y: auto; }

/* 概览 Tab */
.ov-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px 20px; margin-bottom: 16px; }
.ov-item { display: flex; gap: 8px; align-items: center; }
.ov-item .k { color: var(--text-secondary); font-size: 0.8rem; width: 64px; flex-shrink: 0; }
.ov-item .v { color: var(--text-primary); font-size: 0.85rem; }
.ov-detail { margin-bottom: 8px; }
.ov-detail-label { font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 6px; }
.ov-detail-text { font-size: 0.88rem; color: var(--text-regular); line-height: 1.8; white-space: pre-wrap; word-break: break-word; background: var(--bg-layer-2); border-radius: 8px; padding: 12px; }

/* 结构化角色卡 */
.persona-sections { display: flex; flex-direction: column; gap: 18px; }
.p-section { }
.p-title { font-weight: 700; color: var(--text-primary); font-size: 0.95rem; margin-bottom: 8px; padding-bottom: 6px; border-bottom: 1px solid var(--border-light); }
.p-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 8px 16px; }
.p-cell { display: flex; gap: 8px; }
.p-cell .k { color: var(--text-secondary); font-size: 0.78rem; flex-shrink: 0; }
.p-cell .v { color: var(--text-primary); font-size: 0.85rem; }
.p-list { }
.p-label { font-size: 0.75rem; color: var(--text-secondary); margin: 8px 0 4px; }
.p-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.p-text { font-size: 0.88rem; color: var(--text-regular); line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.p-none { color: var(--text-placeholder); font-size: 0.82rem; }
.p-bitem { margin-bottom: 2px; }
.persona-ops { margin-top: 16px; display: flex; gap: 8px; }

/* Prompt Tab */
.prompt-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.prompt-hint { font-size: 0.78rem; color: var(--text-secondary); }
.prompt-pre { margin: 0; padding: 14px; background: var(--bg-layer-2); border: 1px solid var(--border-light); border-radius: 8px; font-family: var(--font-mono, monospace); font-size: 0.78rem; line-height: 1.7; white-space: pre-wrap; word-break: break-word; max-height: calc(100vh - 320px); overflow-y: auto; }

/* 人格与面具 Tab（O6 G3） */
.personamask-ops { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.personamask-hint { font-size: 0.72rem; color: var(--text-secondary); }
.pm-sec { margin-top: 12px; }
.pm-sec-title { font-size: 0.78rem; font-weight: 600; color: var(--text-secondary); margin-bottom: 8px; }
.pm-pad-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 8px; }
.pm-pad-box { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 10px; }
.pm-pad-label { font-size: 0.72rem; color: var(--text-secondary); }
.pm-pad-value { font-size: 0.82rem; margin-top: 4px; }
.pm-sub { font-size: 0.74rem; color: var(--text-secondary); }
.pm-empty { font-size: 0.74rem; color: var(--text-secondary); padding: 8px 2px; }
.pm-delta-up { color: var(--state-success); }
.pm-delta-down { color: var(--state-danger); }
.pm-forbid { color: var(--state-danger); }

/* 记忆 Tab */
.memory-ops { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.memory-hint { font-size: 0.72rem; color: var(--text-secondary); }
.memory-list { display: flex; flex-direction: column; gap: 8px; max-height: calc(100vh - 340px); overflow-y: auto; }
.memory-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 10px; }
.memory-item.summary { background: var(--state-success-bg); }
.memory-item.fact { background: var(--bg-layer-2); }
.memory-item.observation { background: color-mix(in srgb, var(--state-warning) 8%, transparent); }
.memory-item.reflection { background: color-mix(in srgb, var(--state-danger) 8%, transparent); }
.memory-item.plan { background: color-mix(in srgb, var(--state-primary) 8%, transparent); }
.memory-head { display: flex; align-items: center; gap: 6px; }
.memory-op { opacity: 0; transition: opacity .15s; }
.memory-item:hover .memory-op { opacity: 1; }
.memory-del { margin-left: auto; opacity: 0; transition: opacity .15s; }
.memory-item:hover .memory-del { opacity: 1; }
.edit-memory-hint { font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 12px; }
.memory-content { font-size: 0.8rem; color: var(--text-primary); margin-top: 6px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }

/* M2 L1 工作记忆（热缓冲） */
.working-memory-sec { margin-top: 14px; border-top: 1px dashed var(--border-light); padding-top: 10px; }
.working-memory-title { font-size: 0.78rem; font-weight: 600; color: var(--text-secondary); margin-bottom: 8px; }
.working-memory-list { display: flex; flex-direction: column; gap: 8px; max-height: 30vh; overflow-y: auto; }
.working-memory-item { border: 1px dashed var(--border-light); border-radius: 8px; padding: 8px 10px; background: color-mix(in srgb, var(--state-primary) 4%, transparent); }
.working-memory-head { display: flex; align-items: center; gap: 6px; }
.working-memory-time { font-size: 0.7rem; color: var(--text-placeholder); margin-left: auto; }
.working-memory-content { font-size: 0.78rem; color: var(--text-primary); margin-top: 5px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }

/* O1 记忆内核扩展：L1 短期模糊（原版 vs 当前模糊版本对照） */
.fuzz-original { font-size: 0.72rem; color: var(--text-secondary); margin-top: 4px; line-height: 1.5; word-break: break-word; }
.fuzz-original-label { color: var(--text-placeholder); }

/* M5 记忆治理健康度（角色维度摘要卡片） */
.memory-ops-right { display: flex; align-items: center; gap: 2px; }
.memory-health-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 10px; }
.health-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 10px; background: var(--bg-layer-2); }
.health-label { font-size: 0.72rem; color: var(--text-secondary); margin-bottom: 4px; }
.health-value { font-size: 1.1rem; font-weight: 700; color: var(--text-primary); margin-bottom: 4px; }
.health-sub { font-size: 0.68rem; color: var(--text-secondary); line-height: 1.6; }

/* M5 注入预览对话框 */
.ip-controls { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.ip-total { margin-left: auto; }
.ip-body { max-height: 55vh; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; }
.ip-seg { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 10px; }
.ip-seg-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.ip-key { font-size: 0.7rem; color: var(--state-primary); background: color-mix(in srgb, var(--state-primary) 8%, transparent); padding: 1px 6px; border-radius: 4px; }
.ip-title { font-weight: 600; font-size: 0.78rem; color: var(--text-primary); }
.ip-text { margin: 0; padding: 6px 8px; background: var(--bg-layer-2); border: 1px dashed var(--border-light); border-radius: 6px; font-size: 0.72rem; line-height: 1.6; color: var(--text-primary); white-space: pre-wrap; word-break: break-word; font-family: var(--font-mono, Consolas, 'Courier New', monospace); }
.ip-hits { display: flex; flex-direction: column; gap: 4px; margin-top: 6px; }
.ip-hit { display: flex; align-items: center; gap: 6px; font-size: 0.72rem; color: var(--text-secondary); flex-wrap: wrap; }
.ip-hit-content { color: var(--text-primary); word-break: break-all; }
.mono { font-family: var(--font-mono, Consolas, 'Courier New', monospace); }

/* M3 L3 核心记忆（恒在：静态基线只读 + 动态核心块 CRUD） */
.core-memory-sec { margin-top: 14px; border-top: 1px dashed var(--border-light); padding-top: 10px; }
.core-memory-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.core-memory-title { font-size: 0.78rem; font-weight: 600; color: var(--text-secondary); }
.core-memory-body { display: flex; flex-direction: column; gap: 8px; max-height: 34vh; overflow-y: auto; }
.core-baseline { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 10px; background: color-mix(in srgb, var(--state-success) 6%, transparent); }
.core-baseline-title { font-size: 0.72rem; font-weight: 600; color: var(--state-success); margin-bottom: 5px; }
.core-baseline-text { font-size: 0.76rem; color: var(--text-primary); line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.core-block-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 10px; background: color-mix(in srgb, var(--state-warning) 5%, transparent); }
.core-block-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.core-block-version { font-size: 0.7rem; color: var(--text-placeholder); }
.core-block-ops { margin-left: auto; display: flex; align-items: center; }
.core-block-content { font-size: 0.78rem; color: var(--text-primary); margin-top: 6px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }

/* 版本历史 */
.version-hint { font-size: 0.75rem; color: var(--text-secondary); background: var(--bg-layer-2); border: 1px dashed var(--border-light); border-radius: 6px; padding: 6px 10px; margin-bottom: 10px; line-height: 1.6; }
.version-list { max-height: 55vh; overflow-y: auto; display: flex; flex-direction: column; gap: 8px; }
.version-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 10px 12px; }
.version-item.current { border-color: #67c23a; }
.version-head { display: flex; align-items: center; gap: 10px; }
.version-source { font-size: 0.78rem; color: var(--text-secondary); }
.version-time { font-size: 0.75rem; color: var(--text-placeholder); margin-left: auto; }
.version-detail { margin-top: 10px; }
.card-edit-hint { margin-bottom: 10px; }
.vd-label { font-size: 0.75rem; color: var(--text-secondary); margin-bottom: 4px; }
.vd-pre { margin: 0 0 10px; padding: 10px; background: var(--bg-layer-2); border-radius: 6px; font-size: 0.75rem; white-space: pre-wrap; word-break: break-word; max-height: 200px; overflow-y: auto; }

/* 目标/计划 Tab（D2 F7） */
.gp-ops { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.gp-hint { font-size: 0.72rem; color: var(--text-secondary); margin-right: auto; }
.gp-goals { display: flex; flex-direction: column; gap: 8px; margin-bottom: 18px; max-height: 40vh; overflow-y: auto; }
.gp-goal { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 12px; }
.gp-goal.done { opacity: 0.75; }
.gp-goal.done .gp-goal-title { text-decoration: line-through; color: var(--text-secondary); }
.gp-goal.abandoned { opacity: 0.6; }
.gp-goal.abandoned .gp-goal-title { color: var(--text-secondary); }
.gp-goal-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.gp-goal-title { font-size: 0.9rem; font-weight: 600; color: var(--text-primary); min-width: 0; }
.gp-goal-ops { margin-left: auto; display: flex; gap: 2px; opacity: 0; transition: opacity .15s; }
.gp-goal:hover .gp-goal-ops { opacity: 1; }
.gp-goal-desc { font-size: 0.8rem; color: var(--text-regular); margin-top: 6px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }
.gp-goal-meta { font-size: 0.72rem; color: var(--text-placeholder); margin-top: 6px; }
.gp-plan-title { font-weight: 700; color: var(--text-primary); font-size: 0.95rem; margin-bottom: 8px; padding-bottom: 6px; border-bottom: 1px solid var(--border-light); display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.gp-plan-summary { font-weight: 400; font-size: 0.78rem; color: var(--text-secondary); }
.gp-plan-timeline { display: flex; flex-direction: column; gap: 4px; max-height: 40vh; overflow-y: auto; }
.gp-plan-item { display: flex; gap: 10px; align-items: flex-start; padding: 4px 0; }
.gp-plan-hour { width: 44px; flex-shrink: 0; font-size: 0.8rem; font-weight: 600; color: var(--text-primary); text-align: right; padding-top: 2px; }
.gp-plan-dot { width: 10px; height: 10px; border-radius: 999px; margin-top: 6px; flex-shrink: 0; background: var(--brand-gradient, var(--accent, #409eff)); }
.gp-plan-body { flex: 1; min-width: 0; }
.gp-plan-action { font-size: 0.85rem; color: var(--text-primary); line-height: 1.5; }
.gp-plan-sub { display: flex; align-items: center; gap: 8px; margin-top: 2px; flex-wrap: wrap; }
.gp-plan-target { font-size: 0.75rem; color: var(--text-secondary); }
.gp-plan-loc { font-size: 0.75rem; color: var(--text-secondary); }

/* ==================== M3 · 状态 Tab ==================== */
.st-ops { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.st-hint { font-size: 0.72rem; color: var(--text-secondary); margin-right: auto; }
.st-empty { padding: 12px 0; }
.st-urgent { display: flex; align-items: flex-start; gap: 10px; padding: 10px 12px; border-radius: 8px; margin-bottom: 12px; border: 1px solid; }
.st-urgent.lv1 { background: rgba(255, 184, 77, 0.10); border-color: #ffb84d; color: #ffb84d; }
.st-urgent.lv2 { background: rgba(255, 107, 107, 0.10); border-color: #ff6b6b; color: #ff6b6b; }
.st-urgent.lv3 { background: rgba(214, 48, 48, 0.14); border-color: #d63030; color: #d63030; }
.st-urgent-body { flex: 1; min-width: 0; }
.st-urgent-title { font-weight: 700; font-size: 0.85rem; }
.st-urgent-sub { font-size: 0.72rem; opacity: 0.85; margin-top: 2px; }
.st-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.st-card { background: var(--bg-card, rgba(255,255,255,0.04)); border: 1px solid var(--border-light); border-radius: 10px; padding: 12px 14px; }
.st-card-title { font-weight: 700; font-size: 0.85rem; color: var(--text-primary); margin-bottom: 10px; padding-bottom: 6px; border-bottom: 1px solid var(--border-light); }
.st-needs { display: flex; flex-direction: column; gap: 10px; }
.st-need { display: flex; flex-direction: column; gap: 3px; }
.st-need-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.st-need-name { font-size: 0.8rem; font-weight: 600; color: var(--text-regular); }
.st-need-val { font-size: 0.8rem; font-weight: 700; color: var(--text-primary); display: flex; align-items: baseline; gap: 6px; }
.st-need-val i { font-style: normal; font-size: 0.7rem; font-weight: 400; color: var(--text-placeholder); }
.st-need-val.low { color: #ff6b6b; }
.st-need-val.mid { color: #ffb84d; }
.st-envmod { font-size: 0.75rem; color: var(--text-secondary); margin-top: 4px; }
.st-envmod b.pos { color: #52d68a; }
.st-envmod b.neg { color: #ff6b6b; }
.st-mood { display: flex; flex-direction: column; gap: 4px; margin-bottom: 12px; }
.st-emotion { display: flex; flex-direction: column; gap: 8px; }
.st-emotion-row { display: flex; align-items: center; gap: 10px; }
.st-emotion-label { font-size: 0.8rem; color: var(--text-regular); width: 64px; flex-shrink: 0; }
.st-explicit { font-size: 0.68rem; color: #409eff; background: rgba(64, 158, 255, 0.12); padding: 1px 6px; border-radius: 4px; }
.st-pad { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--border-light); display: flex; flex-direction: column; gap: 6px; }
.st-pad-title { font-size: 0.75rem; font-weight: 600; color: var(--text-secondary); }
.st-pad-row { display: flex; align-items: center; gap: 10px; }
.st-pad-axis { font-size: 0.75rem; color: var(--text-regular); width: 64px; flex-shrink: 0; }
.st-pad-val { font-size: 0.75rem; font-weight: 700; width: 46px; text-align: right; font-family: var(--font-mono, monospace); }
.st-pad-val.pos { color: #52d68a; }
.st-pad-val.neg { color: #ff6b6b; }
.st-meta { display: flex; flex-direction: column; gap: 8px; }
.st-meta-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; font-size: 0.78rem; }
.st-meta-row span { color: var(--text-secondary); }
.st-meta-row b { color: var(--text-primary); font-weight: 600; text-align: right; word-break: break-all; }
/* ==================== M4 · 状态历史曲线 ==================== */
.st-history-block { margin-top: 14px; border: 1px solid var(--border-light); border-radius: 10px; padding: 12px 14px; }
.st-history-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
.st-history-title { font-weight: 700; font-size: 0.85rem; color: var(--text-primary); }
.st-curve { margin-top: 4px; }

.code-input { font-family: var(--font-mono, monospace); font-size: 0.82rem; }

/* 角色新增/编辑对话框 */
.char-adv-collapse { margin-bottom: 8px; border: none; }
.char-auto-hint { font-size: 0.78rem; color: var(--text-secondary); margin-left: 8px; }
</style>
