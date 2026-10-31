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
  <div class="world-graph-view project-col">
    <!-- 统一页头：标题 + 说明 + 页面级操作（刷新） -->
    <PageHeader
      title="世界图谱"
      desc="把「世界」当成一张可查的图：时间、地点、角色、事件、关系、势力、宏观物资七个切面，全部只读查询。"
    >
      <template #actions>
        <el-button size="small" :loading="loading.overview" @click="refresh">
          <el-icon><HIcon name="Refresh" /></el-icon>&nbsp;刷新
        </el-button>
      </template>
    </PageHeader>

    <!-- 加载失败提示：单域失败只提示不阻断（其余切面仍可查） -->
    <el-alert
      v-if="error"
      class="wg-alert"
      type="warning"
      :closable="false"
      show-icon
      :title="error"
    >
      <template #default>
        <span>该切面暂时取不到数据，其余切面不受影响；点击右上「刷新」重试。</span>
      </template>
    </el-alert>

    <!-- 页内主导航（第二层）：七个切面 -->
    <PageTabs v-model="tab" position="top">
      <!-- ==================== ① 总览 ==================== -->
      <el-tab-pane label="总览" name="overview" lazy>
        <div v-loading="loading.overview" class="wg-pane">
          <!-- 时间条：权威引擎位置 -->
          <div class="wg-clock tech-card">
            <div class="wg-clock-main">
              <span class="wg-clock-label">引擎位置</span>
              <span class="wg-clock-value">{{ overview?.time?.gameTimeText || timeText(overview?.time) }}</span>
            </div>
            <div class="wg-clock-tags">
              <el-tag size="small" effect="plain" type="info">
                第 {{ overview?.time?.gameDay ?? '—' }} 日 · 第 {{ overview?.time?.gameHour ?? '—' }} 小时
              </el-tag>
              <el-tag size="small" effect="light" :type="overview?.time?.paused ? 'warning' : 'success'">
                {{ overview?.time?.paused ? '时钟暂停' : '时钟推进' }}
              </el-tag>
              <el-tag size="small" effect="plain" :type="overview?.time?.worldRunning ? 'success' : 'info'">
                {{ overview?.time?.worldRunning ? '世界运行中' : '世界静止' }}
              </el-tag>
              <el-tag v-if="overview?.time?.calendarName" size="small" effect="plain">
                {{ overview.time.calendarName }}
              </el-tag>
            </div>
          </div>

          <!-- 六维存量 -->
          <div class="wg-counts">
            <div v-for="c in countCells" :key="c.key" class="wg-count tech-card">
              <div class="wg-count-num">{{ c.value ?? '—' }}</div>
              <div class="wg-count-label">{{ c.label }}</div>
            </div>
          </div>

          <!-- 宏观物资摘要 -->
          <div class="wg-card tech-card">
            <div class="wg-card-title">宏观物资</div>
            <div class="wg-macro">
              <div v-for="m in macroCells" :key="m.label" class="wg-macro-cell">
                <div class="wg-macro-label">{{ m.label }}</div>
                <div class="wg-macro-value">{{ m.value }}</div>
              </div>
            </div>
            <div v-if="overview?.macro?.summary" class="wg-summary">{{ overview.macro.summary }}</div>
            <div class="wg-macro-top">
              <span v-if="overview?.macro?.topFaction">最强势力：<b>{{ overview.macro.topFaction }}</b></span>
              <span v-if="overview?.macro?.topIssue">当前议题：<b>{{ overview.macro.topIssue }}</b></span>
              <span v-if="overview?.macro?.season">季节：<b>{{ overview.macro.season }}</b></span>
            </div>
          </div>

          <!-- 最近事件 + 最近编年史 -->
          <div class="wg-two-col">
            <div class="wg-card tech-card">
              <div class="wg-card-title">最近事件 <span class="wg-dim">（{{ overview?.recentEvents?.length || 0 }}）</span></div>
              <div v-if="!overview?.recentEvents?.length" class="wg-empty">暂无事件记录</div>
              <div
                v-for="e in overview?.recentEvents || []"
                :key="e.id"
                class="wg-row"
              >
                <el-tag size="small" effect="plain" class="wg-kind">{{ kindLabel(e.kind) }}</el-tag>
                <span class="wg-row-main">{{ e.title || '（无标题）' }}</span>
                <span class="wg-row-sub">{{ fmtHour(e.gameHour) }}<template v-if="e.locationName"> · {{ e.locationName }}</template></span>
              </div>
            </div>
            <div class="wg-card tech-card">
              <div class="wg-card-title">最近编年史 <span class="wg-dim">（{{ overview?.recentChronicles?.length || 0 }}）</span></div>
              <div v-if="!overview?.recentChronicles?.length" class="wg-empty">暂无编年史条目</div>
              <div
                v-for="c in overview?.recentChronicles || []"
                :key="c.id"
                class="wg-row is-chronicle"
              >
                <span class="wg-day">第 {{ c.gameDay }} 日</span>
                <span class="wg-row-main">{{ c.title }}</span>
                <span class="wg-row-sub">{{ c.season || '' }}{{ c.source ? ' · ' + c.source : '' }}</span>
              </div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ==================== ② 地点 ==================== -->
      <el-tab-pane label="地点" name="locations" lazy>
        <WorkspaceSplit
          storage-key="wg.locations"
          :panes="locationPanes"
          @close-pane="closeChildPane"
        >
          <template #pane-list>
            <div
              v-for="l in locations"
              :key="l.id"
              class="wg-list-item"
              :class="{ 'is-active': activeLocationId === l.id }"
              @click="openLocation(l.id)"
              @contextmenu.prevent="openNodeMenu($event, '地点', l, () => openLocation(l.id))"
            >
              <span class="wg-list-name">{{ l.name }}</span>
              <span class="wg-list-tag">{{ l.type || '—' }}</span>
            </div>
            <div v-if="!locations.length && !loading.locations" class="wg-empty">暂无地点</div>
          </template>

          <template #pane-detail>
            <div v-loading="loading.locationView" class="wg-main">
              <div v-if="!locationView" class="wg-empty">左侧选一个地点，查看「在场角色 / 近期事件 / 关联实体」。</div>
              <template v-else>
                <div class="wg-detail-head">
                  <div class="wg-detail-title">{{ locationView.location?.name }}</div>
                  <div class="wg-detail-tags">
                    <el-tag size="small" effect="plain">{{ locationView.location?.type || '—' }}</el-tag>
                    <el-tag size="small" effect="plain" type="info">重要度 {{ locationView.location?.importance ?? '—' }}</el-tag>
                  </div>
                </div>
                <div v-if="locationView.location?.intro" class="wg-detail-desc">{{ locationView.location.intro }}</div>

                <div class="wg-section-title">在场角色（{{ locationView.characters?.length || 0 }}）</div>
                <div class="wg-chips">
                  <el-tag
                    v-for="c in locationView.characters || []"
                    :key="c.id"
                    size="small"
                    effect="light"
                    class="wg-chip"
                    title="点开：在右侧并置查看该角色（不影响当前地点）"
                    @click="openCharacterFrom(c)"
                  >{{ c.name }}</el-tag>
                  <span v-if="!locationView.characters?.length" class="wg-dim">无</span>
                </div>

                <div class="wg-section-title">近期事件（{{ locationView.events?.length || 0 }}）</div>
                <div v-if="!locationView.events?.length" class="wg-dim">无</div>
                <div v-for="e in locationView.events || []" :key="e.id" class="wg-row">
                  <el-tag size="small" effect="plain" class="wg-kind">{{ kindLabel(e.kind) }}</el-tag>
                  <span class="wg-row-main">{{ e.title }}</span>
                  <span class="wg-row-sub">{{ fmtHour(e.gameHour) }}</span>
                </div>

                <div class="wg-section-title">关联实体状态（{{ locationView.entities?.length || 0 }}）</div>
                <el-table v-if="locationView.entities?.length" :data="locationView.entities" size="small" class="wg-table">
                  <el-table-column prop="name" label="实体" min-width="120" />
                  <el-table-column prop="entityType" label="类型" width="110" />
                  <el-table-column prop="currentState" label="当前状态" min-width="160" show-overflow-tooltip />
                  <el-table-column prop="recentChange" label="近期变化" min-width="160" show-overflow-tooltip />
                </el-table>
                <div v-else class="wg-dim">无</div>
              </template>
            </div>
          </template>

          <template #pane-child>
            <GraphChildCard
              :target="childTarget"
              :character-view="childCharView"
              :faction-view="childFactionView"
              :loading="childLoading"
              @open-tab="openFromChild"
            />
          </template>
        </WorkspaceSplit>
      </el-tab-pane>

      <!-- ==================== ③ 角色 ==================== -->
      <el-tab-pane label="角色" name="characters" lazy>
        <WorkspaceSplit
          storage-key="wg.characters"
          :panes="characterPanes"
          @close-pane="closeChildPane"
        >
          <template #pane-list>
            <el-segmented v-model="charType" :options="charTypeOptions" size="small" class="wg-seg" @change="loadCharacters" />
            <el-select
              v-model="charLocationId"
              size="small"
              clearable
              placeholder="按所在地点筛选"
              class="wg-select"
              @change="loadCharacters"
            >
              <el-option v-for="l in locations" :key="l.id" :label="l.name" :value="l.id" />
            </el-select>
            <div class="wg-scroll">
              <div
                v-for="c in characters"
                :key="c.id"
                class="wg-list-item"
                :class="{ 'is-active': activeCharacterId === c.id }"
                @click="openCharacter(c.id)"
                @contextmenu.prevent="openNodeMenu($event, '角色', c, () => openCharacter(c.id))"
              >
                <span class="wg-list-name">{{ c.name }}</span>
                <span class="wg-list-tag">{{ charTypeLabel(c.type) }}</span>
              </div>
              <div v-if="!characters.length && !loading.characters" class="wg-empty">无匹配角色</div>
            </div>
          </template>

          <template #pane-detail>
            <div v-loading="loading.characterView" class="wg-main">
              <div v-if="!characterView" class="wg-empty">左侧选一个角色，查看「档案 / 所在位置 / 关系 / 所属组织 / 参与事件」。</div>
              <template v-else>
                <div class="wg-detail-head">
                  <div class="wg-detail-title">
                    {{ characterView.character?.name }}
                    <el-tag v-if="characterView.character?.isProtagonist" size="small" type="danger" effect="light" class="wg-mini">主角</el-tag>
                    <el-tag v-if="characterView.character?.stage" size="small" type="warning" effect="plain" class="wg-mini">{{ characterView.character.stage }}</el-tag>
                  </div>
                  <div class="wg-detail-tags">
                    <el-tag size="small" effect="plain">{{ characterView.character?.ref }}</el-tag>
                    <el-tag size="small" effect="plain" type="info">{{ charTypeLabel(characterView.character?.type) }}</el-tag>
                  </div>
                </div>

                <div class="wg-kv">
                  <div class="wg-kv-item"><span class="wg-kv-label">头衔</span><span>{{ characterView.character?.title || '—' }}</span></div>
                  <div class="wg-kv-item"><span class="wg-kv-label">职业</span><span>{{ characterView.character?.occupation || '—' }}</span></div>
                  <div class="wg-kv-item"><span class="wg-kv-label">归属</span><span>{{ characterView.character?.affiliation || '—' }}</span></div>
                  <div class="wg-kv-item">
                    <span class="wg-kv-label">位置</span>
                    <span>{{ characterView.location?.name || characterView.character?.location || '未知' }}</span>
                  </div>
                </div>

                <div class="wg-section-title">关系（{{ characterView.relations?.length || 0 }}）</div>
                <el-table v-if="characterView.relations?.length" :data="characterView.relations" size="small" class="wg-table">
                  <el-table-column label="对方" min-width="110">
                    <template #default="{ row }">
                      {{ row.fromRef === characterView.character?.ref ? row.toName : row.fromName }}
                    </template>
                  </el-table-column>
                  <el-table-column prop="relationType" label="关系" width="110" />
                  <el-table-column prop="affinity" label="好感" width="80" />
                  <el-table-column prop="trust" label="信任" width="80" />
                  <el-table-column prop="relationState" label="状态" min-width="100" show-overflow-tooltip />
                  <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
                </el-table>
                <div v-else class="wg-dim">无</div>

                <div class="wg-section-title">所属组织（{{ characterView.factions?.length || 0 }}）</div>
                <div class="wg-chips">
                  <el-tag
                    v-for="f in characterView.factions || []"
                    :key="f.id"
                    size="small"
                    effect="light"
                    class="wg-chip"
                    title="点开：在右侧并置查看该势力（不影响当前角色）"
                    @click="openChildPane('faction', f.id, f.name, 'characters')"
                  >{{ f.name }}<template v-if="f.memberCount != null"> · {{ f.memberCount }} 人</template></el-tag>
                  <span v-if="!characterView.factions?.length" class="wg-dim">无</span>
                </div>

                <div class="wg-section-title">参与事件（{{ characterView.events?.length || 0 }}）</div>
                <div v-if="!characterView.events?.length" class="wg-dim">无</div>
                <div v-for="e in characterView.events || []" :key="e.id" class="wg-row">
                  <el-tag size="small" effect="plain" class="wg-kind">{{ kindLabel(e.kind) }}</el-tag>
                  <span class="wg-row-main">{{ e.title }}</span>
                  <span class="wg-row-sub">{{ fmtHour(e.gameHour) }}</span>
                </div>
              </template>
            </div>
          </template>

          <template #pane-child>
            <GraphChildCard
              :target="childTarget"
              :character-view="childCharView"
              :faction-view="childFactionView"
              :loading="childLoading"
              @open-tab="openFromChild"
            />
          </template>
        </WorkspaceSplit>
      </el-tab-pane>

      <!-- ==================== ④ 事件 ==================== -->
      <el-tab-pane label="事件" name="events" lazy>
        <div v-loading="loading.events" class="wg-pane">
          <div class="wg-toolbar tech-card">
            <el-select v-model="eventKind" size="small" clearable placeholder="事件类型（全部）" class="wg-select is-inline" @change="loadEvents">
              <el-option v-for="k in kindOptions" :key="k.value" :label="k.label" :value="k.value" />
            </el-select>
            <el-select v-model="eventCharacterId" size="small" clearable filterable placeholder="参与角色（全部）" class="wg-select is-inline" @change="loadEvents">
              <el-option v-for="c in allCharacters" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
            <el-select v-model="eventLimit" size="small" class="wg-select is-narrow" @change="loadEvents">
              <el-option :value="50" label="最近 50 条" />
              <el-option :value="200" label="最近 200 条" />
              <el-option :value="500" label="最近 500 条" />
            </el-select>
            <span class="wg-dim">共 {{ events.length }} 条</span>
          </div>

          <div v-if="!events.length && !loading.events" class="wg-empty">无匹配事件</div>
          <div v-for="e in events" :key="e.id" class="wg-event tech-card">
            <div class="wg-event-head">
              <el-tag size="small" effect="plain" class="wg-kind">{{ kindLabel(e.kind) }}</el-tag>
              <span class="wg-event-title">{{ e.title || '（无标题）' }}</span>
              <span class="wg-row-sub">{{ fmtHour(e.gameHour) }}<template v-if="e.locationName"> · {{ e.locationName }}</template></span>
              <el-tag v-if="e.visibility && e.visibility !== 'public'" size="small" type="info" effect="plain">{{ e.visibility }}</el-tag>
            </div>
            <div v-if="e.content" class="wg-event-content">{{ e.content }}</div>
            <div v-if="e.characterNames?.length" class="wg-chips">
              <el-tag v-for="n in e.characterNames" :key="n" size="small" effect="plain" class="wg-chip is-static">{{ n }}</el-tag>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ==================== ⑤ 关系 ==================== -->
      <el-tab-pane label="关系" name="relations" lazy>
        <div v-loading="loading.relations" class="wg-pane">
          <div class="wg-toolbar tech-card">
            <el-select v-model="relationCharacterId" size="small" clearable filterable placeholder="只看某角色的关系" class="wg-select is-inline" @change="loadRelations">
              <el-option v-for="c in allCharacters" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
            <el-select v-model="relationLimit" size="small" class="wg-select is-narrow">
              <el-option :value="100" label="前 100 条" />
              <el-option :value="300" label="前 300 条" />
              <el-option :value="0" label="全部" />
            </el-select>
            <span class="wg-dim">共 {{ relations.length }} 条关系边（from → to，显示 {{ relationRows.length }} 条）</span>
          </div>
          <el-table v-if="relationRows.length" :data="relationRows" size="small" class="wg-table is-page">
            <el-table-column label="起点" min-width="120">
              <template #default="{ row }">{{ row.fromName }}<span class="wg-dim"> · {{ row.fromRef }}</span></template>
            </el-table-column>
            <el-table-column label="" width="46" align="center">
              <template #default>→</template>
            </el-table-column>
            <el-table-column label="终点" min-width="120">
              <template #default="{ row }">{{ row.toName }}<span class="wg-dim"> · {{ row.toRef }}</span></template>
            </el-table-column>
            <el-table-column prop="relationType" label="关系" width="120" />
            <el-table-column prop="affinity" label="好感" width="90" sortable />
            <el-table-column prop="trust" label="信任" width="90" sortable />
            <el-table-column prop="relationState" label="状态" width="120" show-overflow-tooltip />
            <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
          </el-table>
          <div v-else class="wg-empty">无关系数据</div>
        </div>
      </el-tab-pane>

      <!-- ==================== ⑥ 势力 ==================== -->
      <el-tab-pane label="势力" name="factions" lazy>
        <WorkspaceSplit
          storage-key="wg.factions"
          :panes="factionPanes"
          @close-pane="closeChildPane"
        >
          <template #pane-list>
            <div
              v-for="f in factions"
              :key="f.id"
              class="wg-list-item"
              :class="{ 'is-active': activeFactionId === f.id }"
              @click="openFaction(f.id)"
              @contextmenu.prevent="openNodeMenu($event, '势力', f, () => openFaction(f.id))"
            >
              <span class="wg-list-name">{{ f.name }}</span>
              <span class="wg-list-tag">{{ f.memberCount ?? 0 }} 人</span>
            </div>
            <div v-if="!factions.length && !loading.factions" class="wg-empty">暂无势力</div>
          </template>

          <template #pane-detail>
            <div v-loading="loading.factionView" class="wg-main">
            <div v-if="!factionView" class="wg-empty">左侧选一个势力，查看「成员 / 组织间关系」。</div>
            <template v-else>
              <div class="wg-detail-head">
                <div class="wg-detail-title">{{ factionView.faction?.name }}</div>
                <div class="wg-detail-tags">
                  <el-tag size="small" effect="plain">{{ factionView.faction?.type || '—' }}</el-tag>
                  <el-tag size="small" effect="plain" :type="factionView.faction?.status === 'active' ? 'success' : 'info'">
                    {{ factionView.faction?.status || '—' }}
                  </el-tag>
                  <el-tag size="small" effect="plain" type="warning">影响力 {{ fmtNum(factionView.faction?.influence) }}</el-tag>
                </div>
              </div>

              <div class="wg-section-title">成员（{{ factionView.members?.length || 0 }}）</div>
              <el-table v-if="factionView.members?.length" :data="factionView.members" size="small" class="wg-table">
                <el-table-column label="成员" min-width="120">
                  <template #default="{ row }">
                    <span
                      class="wg-link"
                      title="点开：在右侧并置查看该角色（不影响当前势力）"
                      @click="openChildPane('character', row.actorId, row.actorName, 'factions')"
                    >{{ row.actorName }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="actorRef" label="标识" width="120" />
                <el-table-column prop="role" label="角色" width="120" />
                <el-table-column prop="influence" label="影响力" width="100" />
                <el-table-column prop="status" label="状态" width="100" />
              </el-table>
              <div v-else class="wg-dim">无</div>

              <div class="wg-section-title">组织间关系分</div>
              <div v-if="factionRelationRows.length" class="wg-chips">
                <el-tag v-for="r in factionRelationRows" :key="r.key" size="small" effect="plain" class="wg-chip is-static">
                  {{ r.key }} · {{ r.value }}
                </el-tag>
              </div>
              <div v-else class="wg-dim">无</div>
            </template>
            </div>
          </template>

          <template #pane-child>
            <GraphChildCard
              :target="childTarget"
              :character-view="childCharView"
              :faction-view="childFactionView"
              :loading="childLoading"
              @open-tab="openFromChild"
            />
          </template>
        </WorkspaceSplit>
      </el-tab-pane>

      <!-- ==================== ⑦ 宏观 ==================== -->
      <el-tab-pane label="宏观" name="macro" lazy>
        <div v-loading="loading.macro" class="wg-pane">
          <div class="wg-counts">
            <div v-for="m in macroCells" :key="m.label" class="wg-count tech-card">
              <div class="wg-count-num">{{ m.value }}</div>
              <div class="wg-count-label">{{ m.label }}</div>
            </div>
          </div>
          <div class="wg-card tech-card">
            <div class="wg-card-title">物资与民心</div>
            <div class="wg-kv is-two">
              <div class="wg-kv-item"><span class="wg-kv-label">人口</span><span>{{ macro?.population ?? '—' }}</span></div>
              <div class="wg-kv-item"><span class="wg-kv-label">粮价</span><span>{{ fmtNum(macro?.grainPrice) }}</span></div>
              <div class="wg-kv-item"><span class="wg-kv-label">粮库存</span><span>{{ fmtNum(macro?.grainStock) }}</span></div>
              <div class="wg-kv-item"><span class="wg-kv-label">粮食产出</span><span>{{ fmtNum(macro?.grainProduction) }}</span></div>
              <div class="wg-kv-item"><span class="wg-kv-label">市场活跃度</span><span>{{ fmtNum(macro?.marketActivity) }}</span></div>
              <div class="wg-kv-item"><span class="wg-kv-label">钱庄存款</span><span>{{ fmtNum(macro?.bankDeposit) }}</span></div>
              <div class="wg-kv-item"><span class="wg-kv-label">货币存量</span><span>{{ fmtNum(macro?.currencySupply) }}</span></div>
              <div class="wg-kv-item"><span class="wg-kv-label">民怨</span><span>{{ fmtNum(macro?.grievance) }}</span></div>
              <div class="wg-kv-item"><span class="wg-kv-label">季节</span><span>{{ macro?.season || '—' }}</span></div>
              <div class="wg-kv-item"><span class="wg-kv-label">收成系数</span><span>{{ fmtNum(macro?.harvestFactor) }}</span></div>
            </div>
            <div v-if="macro?.summary" class="wg-summary">{{ macro.summary }}</div>
            <div class="wg-macro-top">
              <span v-if="macro?.topFaction">最强势力：<b>{{ macro.topFaction }}</b></span>
              <span v-if="macro?.topIssue">当前议题：<b>{{ macro.topIssue }}</b></span>
            </div>
          </div>
          <div class="wg-dim wg-note">
            说明：宏观节点是「世界级快照」，由引擎按当前游戏时刻投影；数值口径以世界设定（世界详情）与演化控制台为准，图谱只做只读展示。
          </div>
        </div>
      </el-tab-pane>
    </PageTabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  fetchWorldGraphOverview,
  fetchWorldGraphLocations,
  fetchWorldGraphCharacters,
  fetchWorldGraphEvents,
  fetchWorldGraphRelations,
  fetchWorldGraphFactions,
  fetchWorldGraphMacro,
  fetchWorldGraphCharacterView,
  fetchWorldGraphLocationView,
  fetchWorldGraphFactionView
} from '@/shared/api'
import PageHeader from '@/shared/layout/PageHeader.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'
import WorkspaceSplit from '@/shared/layout/WorkspaceSplit.vue'
import { createPanelContext } from '@/shared/layout/usePanelContext'
import GraphChildCard from '@/features/observe/components/GraphChildCard.vue'
import { openContextMenu } from '@/shared/layout/useContextMenu'

const route = useRoute()
const projectId = Number(route.params.id)

/** 当前切面（第二层导航） */
const tab = ref('overview')
/** 统一错误提示（单域失败不阻断其他域） */
const error = ref('')
/** 各域加载态（按域独立，避免一个域转圈锁住全页） */
const loading = ref({
  overview: false,
  locations: false,
  locationView: false,
  characters: false,
  characterView: false,
  events: false,
  relations: false,
  factions: false,
  factionView: false,
  macro: false
})

const overview = ref(null)
const locations = ref([])
const locationView = ref(null)
const activeLocationId = ref(null)
const characters = ref([])
const allCharacters = ref([])
const characterView = ref(null)
const activeCharacterId = ref(null)
const events = ref([])
const relations = ref([])
const factions = ref([])
const factionView = ref(null)
const activeFactionId = ref(null)
const macro = ref(null)

// ===== 可并置多面板工作区（WorkspaceSplit + usePanelContext）=====
/**
 * 每个「列表↔详情」切面一组面板上下文（列表栏/详情栏/并置的关联对象栏各一份）。
 * <p>页面持有桶而不只是面板自建，是因为列表栏的选中需要驱动页面级数据加载；把桶交给
 * `WorkspaceSplit` 的 `pane.context` 后，页面与面板共享同一份状态，而**不同栏之间**互不干扰。</p>
 */
const panels = {
  locations: {
    list: createPanelContext('wg.locations.list'),
    detail: createPanelContext('wg.locations.detail'),
    child: createPanelContext('wg.locations.child')
  },
  characters: {
    list: createPanelContext('wg.characters.list'),
    detail: createPanelContext('wg.characters.detail'),
    child: createPanelContext('wg.characters.child')
  },
  factions: {
    list: createPanelContext('wg.factions.list'),
    detail: createPanelContext('wg.factions.detail'),
    child: createPanelContext('wg.factions.child')
  }
}

/** 当前并置出来的「关联对象」栏：{ owner 切面, kind, id, name }；null = 未并置 */
const childPane = ref(null)
/** 并置栏数据（**独立于**角色/势力切面的详情数据，避免互相覆盖） */
const childCharView = ref(null)
const childFactionView = ref(null)
const childLoading = ref(false)

/**
 * 打开并置栏（第三栏），加载被点开对象的视角数据。
 *
 * @param {'character'|'faction'} kind 对象类型
 * @param {number} id 对象 id
 * @param {string} name 对象名（用于标题占位）
 * @param {'locations'|'characters'|'factions'} owner 哪个切面并置出来的
 * @returns {Promise<void>} 无返回
 */
async function openChildPane(kind, id, name, owner) {
  childPane.value = { owner, kind, id, name }
  childLoading.value = true
  try {
    if (kind === 'character') {
      childCharView.value = null
      childFactionView.value = null
      childCharView.value = await fetchWorldGraphCharacterView(projectId, id)
    } else {
      childCharView.value = null
      childFactionView.value = null
      childFactionView.value = await fetchWorldGraphFactionView(projectId, id)
    }
  } catch (e) {
    ElMessage.warning(`并置面板数据加载失败：${e?.message || '未知错误'}`)
  } finally {
    childLoading.value = false
  }
}

/** 关闭并置栏 */
function closeChildPane() {
  childPane.value = null
  childCharView.value = null
  childFactionView.value = null
}

/**
 * 生成某切面的面板描述数组（列表栏 + 详情栏 [+ 并置的关联对象栏]）。
 *
 * @param {'locations'|'characters'|'factions'} facet 切面键
 * @param {string} listTitle 列表栏标题
 * @param {number} listCount 列表计数
 * @param {string} detailTitle 详情栏标题
 * @returns {Array} 面板描述数组
 */
function panesFor(facet, listTitle, listCount, detailTitle) {
  const arr = [
    {
      key: 'list',
      title: listTitle,
      count: listCount,
      resizable: true,
      collapsible: true,
      min: 190,
      max: 480,
      context: panels[facet].list
    },
    { key: 'detail', title: detailTitle, flex: true, context: panels[facet].detail }
  ]
  if (childPane.value?.owner === facet) {
    arr.push({ key: 'child', title: '关联对象', flex: true, closable: true, context: panels[facet].child })
  }
  return arr
}

/** 地点切面面板（详情栏标题用固定标签，避免与详情正文里的对象名重复显示） */
const locationPanes = computed(() => panesFor('locations', '地点', locations.value.length, '地点详情'))
/** 角色切面面板 */
const characterPanes = computed(() => panesFor('characters', '角色', characters.value.length, '角色详情'))
/** 势力切面面板 */
const factionPanes = computed(() => panesFor('factions', '势力', factions.value.length, '势力详情'))

/** 并置栏展示的目标（供 GraphChildCard 使用） */
const childTarget = computed(() => (childPane.value ? { kind: childPane.value.kind, id: childPane.value.id, name: childPane.value.name } : null))

/**
 * 从并置栏跳到对应切面看完整信息（并置栏是「顺手看」，完整信息仍在切面里）。
 *
 * @param {'characters'|'factions'} tabKey 目标切面
 * @param {number} id 对象 id
 * @returns {void} 无返回
 */
function openFromChild(tabKey, id) {
  tab.value = tabKey
  if (tabKey === 'characters') {
    resetCharFilters()
    openCharacter(id)
  } else {
    openFaction(id)
  }
}

/** 角色切面筛选 */
const charType = ref('')
const charLocationId = ref(null)
const charTypeOptions = [
  { label: '全部', value: '' },
  { label: '特殊型', value: 'special' },
  { label: '普通型', value: 'common' }
]

/** 事件 / 关系切面筛选 */
const eventKind = ref('')
const eventCharacterId = ref(null)
const eventLimit = ref(50)
const relationCharacterId = ref(null)
/** 关系表显示条数上限（0 = 全部）；图谱可能上千条关系边，默认截断保证渲染顺畅 */
const relationLimit = ref(100)
/**
 * 事件类型筛选项。
 * <p>取值来自**真实数据实测**（青禾村 project_id=1 的事件表里实际存在的 kind：relation / roleplay /
 * action / world / event），不是照抄枚举——初版只列了文档里的五种，结果筛不出真实最多的「关系变化」。</p>
 */
const kindOptions = [
  { label: '关系变化', value: 'relation' },
  { label: '角色扮演', value: 'roleplay' },
  { label: '角色行动', value: 'action' },
  { label: '世界事件', value: 'event' },
  { label: '世界演化', value: 'world' },
  { label: '人群事件', value: 'crowd' },
  { label: '演化里程碑', value: 'evolution' }
]

/** 六维存量卡片 */
const countCells = computed(() => {
  const c = overview.value?.counts || {}
  return [
    { key: 'locations', label: '地点', value: c.locations },
    { key: 'characters', label: '角色', value: c.characters },
    { key: 'events', label: '事件', value: c.events },
    { key: 'relations', label: '关系', value: c.relations },
    { key: 'factions', label: '势力', value: c.factions }
  ]
})

/** 宏观指标卡（总览页用 6 项摘要） */
const macroCells = computed(() => {
  const m = macro.value || overview.value?.macro || {}
  return [
    { label: '人口', value: m.population ?? '—' },
    { label: '粮价', value: fmtNum(m.grainPrice) },
    { label: '粮库存', value: fmtNum(m.grainStock) },
    { label: '市场活跃', value: fmtNum(m.marketActivity) },
    { label: '民怨', value: fmtNum(m.grievance) },
    { label: '收成系数', value: fmtNum(m.harvestFactor) }
  ]
})

/** 势力间关系分（Map → 列表，便于渲染） */
const factionRelationRows = computed(() => {
  const map = factionView.value?.relations || {}
  return Object.keys(map).map((k) => ({ key: k, value: map[k] }))
})

/** 关系表实际渲染行（relationLimit=0 时全量） */
const relationRows = computed(() => {
  const rows = relations.value || []
  return relationLimit.value > 0 ? rows.slice(0, relationLimit.value) : rows
})

/** 数字格式化（保留 2 位，空值显示 —） */
function fmtNum(v) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}

/** 游戏小时 → 「第 N 天 HH:00」（gameHour 自世界起点起算） */
function fmtHour(h) {
  if (h === null || h === undefined) return '—'
  const day = Math.floor(Number(h) / 24) + 1
  const hour = Number(h) % 24
  return `第 ${day} 天 ${String(hour).padStart(2, '0')}:00`
}

/** 时间节点兜底文案（gameTimeText 缺失时） */
function timeText(t) {
  if (!t) return '—'
  if (t.gameDay === null || t.gameDay === undefined) return '—'
  return `第 ${t.gameDay} 日 · 第 ${t.gameHour} 小时`
}

/** 事件类型中文名（未收录的类型原样显示，便于发现新的 kind 而不是被静默吞掉） */
function kindLabel(kind) {
  if (!kind) return '事件'
  const hit = kindOptions.find((k) => k.value === kind)
  if (hit) return hit.label
  const map = { memory: '记忆', system: '系统', stimulus: '刺激', scene: '场景', birth: '出生', death: '死亡' }
  return map[kind] || kind
}

/** 角色类型中文名 */
function charTypeLabel(t) {
  if (t === 'special') return '特殊型'
  if (t === 'common') return '普通型'
  return t || '—'
}

/** 包一层：任何域失败都只提示，不抛（本页是只读查询台，不能因为一个域失败整页白屏） */
async function guard(key, fn, label) {
  loading.value[key] = true
  try {
    await fn()
    if (error.value && error.value.includes(label)) error.value = ''
  } catch (e) {
    error.value = `${label}加载失败：${e?.message || e}`
  } finally {
    loading.value[key] = false
  }
}

/** 总览（同时缓存地点与角色，供其他切面筛选复用） */
async function loadOverview() {
  await guard('overview', async () => {
    overview.value = await fetchWorldGraphOverview(projectId)
  }, '总览')
}

/** 地点列表 */
async function loadLocations() {
  await guard('locations', async () => {
    locations.value = (await fetchWorldGraphLocations(projectId)) || []
  }, '地点')
}

/** 地点视角聚合 */
async function openLocation(id) {
  activeLocationId.value = id
  locationView.value = null
  await guard('locationView', async () => {
    locationView.value = await fetchWorldGraphLocationView(projectId, id)
  }, '地点详情')
}

/** 角色列表（切面内筛选） */
async function loadCharacters() {
  const params = {}
  if (charType.value) params.type = charType.value
  if (charLocationId.value) params.locationId = charLocationId.value
  await guard('characters', async () => {
    characters.value = (await fetchWorldGraphCharacters(projectId, params)) || []
    if (!charType.value && !charLocationId.value) allCharacters.value = characters.value
  }, '角色')
}

/** 角色视角聚合 */
async function openCharacter(id) {
  activeCharacterId.value = id
  characterView.value = null
  await guard('characterView', async () => {
    characterView.value = await fetchWorldGraphCharacterView(projectId, id)
  }, '角色详情')
}

/**
 * 从地点详情点开「在场角色」→ **并置第三栏**显示该角色，而不是整页切走。
 *
 * <p>上一版行为是 `tab = 'characters'`（丢掉正在看的地点）。本轮改为并置：用户保持左栏地点
 * 上下文的同时深入看角色；想用完整角色切面时，并置栏底部有「在角色切面打开」。
 * 两栏各自的选中项分属不同面板上下文，互不覆盖。</p>
 *
 * @param {object} c 角色（含 id/name）
 */
function openCharacterFrom(c) {
  openChildPane('character', c.id, c.name, 'locations')
}

/** 兼容旧入口：跨切面联动（清筛选后切到角色切面并打开） */
function goCharacterTab(c) {
  tab.value = 'characters'
  resetCharFilters()
  openCharacter(c.id)
}

/** 清掉角色切面筛选，保证跨切面联动时目标角色一定在列表里 */
function resetCharFilters() {
  charType.value = ''
  charLocationId.value = null
  loadCharacters()
}

/** 事件列表 */
async function loadEvents() {
  const params = { limit: eventLimit.value }
  if (eventKind.value) params.kind = eventKind.value
  if (eventCharacterId.value) params.characterId = eventCharacterId.value
  await guard('events', async () => {
    events.value = (await fetchWorldGraphEvents(projectId, params)) || []
  }, '事件')
}

/** 关系列表 */
async function loadRelations() {
  const params = {}
  if (relationCharacterId.value) params.characterId = relationCharacterId.value
  await guard('relations', async () => {
    relations.value = (await fetchWorldGraphRelations(projectId, params)) || []
  }, '关系')
}

/** 势力列表 */
async function loadFactions() {
  await guard('factions', async () => {
    factions.value = (await fetchWorldGraphFactions(projectId)) || []
  }, '势力')
}

/** 势力视角聚合 */
async function openFaction(id) {
  activeFactionId.value = id
  factionView.value = null
  await guard('factionView', async () => {
    factionView.value = await fetchWorldGraphFactionView(projectId, id)
  }, '势力详情')
}

/**
 * 列表项右键菜单：把「打开详情 / 取用该实体标识」变成一步可达的桌面端动作。
 *
 * <p>为什么不把「打开详情」做成菜单首项就够：图谱是查询台，用户常见动作是**把某个实体名或 ref
 * 复制到别处**（提问、写设定、去调试台查 trace），点击做不到这件事，所以右键菜单在这里有独立价值。</p>
 *
 * @param {MouseEvent} ev 右键事件
 * @param {string} kind 节点类型中文名（地点/角色/势力）
 * @param {object} node 节点数据（含 id/name/ref）
 * @param {Function} open 打开详情的动作（与点击行为一致）
 */
function openNodeMenu(ev, kind, node, open) {
  const idText = node.ref || String(node.id)
  openContextMenu(ev, {
    title: `${kind} · ${node.name || idText}`,
    context: { kind, node },
    items: [
      { key: 'open', label: '打开详情', icon: 'ic_public_arrow_right', run: open },
      {
        key: 'copy-name',
        label: '复制名称',
        icon: 'ic_public_copy',
        run: () => copyText(node.name, '名称')
      },
      {
        key: 'copy-id',
        label: `复制标识（${idText}）`,
        icon: 'ic_public_copy',
        run: () => copyText(idText, '标识')
      }
    ]
  })
}

/**
 * 复制文本到剪贴板（降级不抛：失败给可见提示）。
 *
 * @param {string} text 待复制文本
 * @param {string} what 人类可读的对象名（用于提示文案）
 * @returns {Promise<void>} 无返回
 */
async function copyText(text, what) {
  if (!text) {
    ElMessage.warning(`没有可复制的${what}`)
    return
  }
  try {
    await navigator.clipboard.writeText(String(text))
    ElMessage.success(`已复制${what}`)
  } catch (_) {
    ElMessage.warning('当前环境不允许写剪贴板，请手动复制')
  }
}

/** 宏观节点 */
async function loadMacro() {
  await guard('macro', async () => {
    macro.value = await fetchWorldGraphMacro(projectId)
  }, '宏观')
}

/** 首屏只取总览（切面懒加载，避免一次打 11 个接口） */
onMounted(async () => {
  await loadOverview()
})

/** 切面激活时按需加载（已加载过的不重复打；总览/地点/角色列表为公共依赖） */
watch(tab, async (t) => {
  if (t === 'locations' && !locations.value.length) await loadLocations()
  if (t === 'characters' && !characters.value.length) await loadCharacters()
  if (t === 'events' && !events.value.length) {
    if (!allCharacters.value.length) await loadCharacters()
    await loadEvents()
  }
  if (t === 'relations' && !relations.value.length) {
    if (!allCharacters.value.length) await loadCharacters()
    await loadRelations()
  }
  if (t === 'factions' && !factions.value.length) await loadFactions()
  if (t === 'macro' && !macro.value) await loadMacro()
})

/** 刷新：重取当前切面（总览另带时间条与计数，始终重取） */
async function refresh() {
  error.value = ''
  await loadOverview()
  const t = tab.value
  if (t === 'locations') {
    await loadLocations()
    if (activeLocationId.value) await openLocation(activeLocationId.value)
  } else if (t === 'characters') {
    await loadCharacters()
    if (activeCharacterId.value) await openCharacter(activeCharacterId.value)
  } else if (t === 'events') {
    await loadEvents()
  } else if (t === 'relations') {
    await loadRelations()
  } else if (t === 'factions') {
    await loadFactions()
    if (activeFactionId.value) await openFaction(activeFactionId.value)
  } else if (t === 'macro') {
    await loadMacro()
  }
}
</script>

<style scoped>
.world-graph-view {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.wg-alert {
  margin-bottom: 12px;
}

.wg-pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}

/* ===== 时间条 ===== */
.wg-clock {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  flex-wrap: wrap;
}
.wg-clock-main {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.wg-clock-label {
  font-size: 12px;
  color: var(--text-secondary);
}
.wg-clock-value {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
  letter-spacing: 0.5px;
}
.wg-clock-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

/* ===== 指标卡 ===== */
.wg-counts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}
.wg-count {
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.wg-count-num {
  font-size: 22px;
  font-weight: 600;
  color: var(--text-primary);
}
.wg-count-label {
  font-size: 12px;
  color: var(--text-secondary);
}

/* ===== 通用卡片 ===== */
.wg-card {
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.wg-card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.wg-two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  min-width: 0;
}
/* V2.0 批次二：断点 1200 → 1024（全站三档）。取 1024 而非 1440 是为了保持
   1440 档的两栏布局不变（该档有基线截图，改档位会造成无谓的视觉差异）。 */
@media (max-width: 1024px) {
  .wg-two-col {
    grid-template-columns: 1fr;
  }
}

/* ===== 宏观 ===== */
.wg-macro {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 10px;
}
.wg-macro-cell {
  background: var(--bg-hover);
  border-radius: 8px;
  padding: 8px 10px;
}
.wg-macro-label {
  font-size: 12px;
  color: var(--text-secondary);
}
.wg-macro-value {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}
.wg-summary {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-regular);
  background: var(--bg-hover);
  border-radius: 8px;
  padding: 10px 12px;
}
.wg-macro-top {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--text-regular);
}

/* ===== 列表行 ===== */
.wg-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--border-color);
  min-width: 0;
}
.wg-row:last-child {
  border-bottom: none;
}
.wg-row-main {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wg-row-sub {
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.wg-kind {
  flex: none;
}
.wg-day {
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.wg-dim {
  color: var(--text-secondary);
  font-size: 12px;
}
.wg-note {
  line-height: 1.7;
}
.wg-empty {
  padding: 22px 0;
  text-align: center;
  color: var(--text-secondary);
  font-size: 13px;
}

/* ===== 左列表 + 右详情（页内双栏，宽度走全局令牌） ===== */
/* 双栏/三栏几何已交给 WorkspaceSplit（并置 / 拖宽 / 折叠 / 记忆），页面不再自写 grid。
   上一版这里是 `grid-template-columns: 260px 1fr`——宽度不可调、不可折叠、刷新即回默认。 */
.wg-scroll {
  /* 自包含滚动盒：地图谱页是查询台，长列表（20+ 地点 / 100+ 普通角色）不应把整页撑到几屏高。
     注意这里**不是**被禁的 `calc(100vh − N)` 魔法高度——不依赖外壳任何高度，只是给列表一个上限。 */
  max-height: min(560px, 60vh);
  overflow-y: auto;
}
.wg-list-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}
.wg-list-item:hover {
  background: var(--bg-hover);
}
.wg-list-item.is-active {
  background: var(--el-color-primary-light-9);
  box-shadow: inset 2px 0 0 var(--el-color-primary);
}
.wg-list-name {
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wg-list-tag {
  font-size: 12.5px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.wg-main {
  /* 内边距已由 WorkspaceSplit 的面板体提供（统一 10px），这里只保留最小高度与收缩约束 */
  min-width: 0;
  min-height: 200px;
}

/* ===== 详情 ===== */
.wg-detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.wg-detail-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  display: flex;
  align-items: center;
  gap: 6px;
}
.wg-mini {
  transform: scale(0.9);
}
.wg-detail-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.wg-detail-desc {
  font-size: 13px;
  line-height: 1.75;
  color: var(--text-regular);
  margin-bottom: 10px;
  white-space: pre-wrap;
}
.wg-section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 14px 0 8px;
  padding-left: 8px;
  border-left: 3px solid var(--el-color-primary);
}
.wg-chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.wg-chip {
  cursor: pointer;
}
.wg-chip.is-static {
  cursor: default;
}
/* 表格里的可点实体名（点开「并置」面板；不改变表格语义，故用链接样式而非按钮） */
.wg-link {
  color: var(--color-primary, var(--el-color-primary));
  cursor: pointer;
}
.wg-link:hover {
  text-decoration: underline;
}
.wg-kv {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 18px;
}
.wg-kv.is-two {
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}
.wg-kv-item {
  display: flex;
  gap: 8px;
  font-size: 13px;
  color: var(--text-primary);
  min-width: 0;
}
.wg-kv-label {
  color: var(--text-secondary);
  flex: none;
  min-width: 56px;
}
.wg-table {
  width: 100%;
}
.wg-table.is-page {
  background: var(--bg-layer-1);
  border-radius: 10px;
  padding: 4px;
}

/* ===== 工具栏 / 事件卡 ===== */
.wg-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  flex-wrap: wrap;
}
.wg-seg {
  margin-bottom: 8px;
}
.wg-select {
  width: 100%;
}
.wg-select.is-inline {
  width: 200px;
}
.wg-select.is-narrow {
  width: 140px;
}
.wg-event {
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}
.wg-event-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.wg-event-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.wg-event-content {
  font-size: 13px;
  line-height: 1.75;
  color: var(--text-regular);
  white-space: pre-wrap;
}
</style>
