<template>
  <div class="firstrun-page">
    <div class="fr-card">
      <!-- 头部：品牌 + 标题 -->
      <div class="fr-header">
        <div class="fr-logo">Holzyn<span class="fr-logo-sub">Actor</span></div>
        <h1 class="fr-title">欢迎来到你的世界</h1>
        <p class="fr-desc">
          设置你的本地个人账户。这些信息将作为「NPC 认识你的档案」注入对话——
          角色会基于你的身份、喜好与个人档案，做出更懂你的回应。所有字段均为选填，可随时在设置中修改。
        </p>
      </div>

      <!-- 表单区 -->
      <el-form :model="form" label-position="top" class="fr-form" @submit.prevent>
        <div class="fr-section">
          <div class="fr-section-title">基本信息</div>
          <div class="fr-grid-2">
            <el-form-item label="昵称（显示名）">
              <el-input v-model="form.nickname" placeholder="例如：林晚" maxlength="64" show-word-limit clearable />
            </el-form-item>
            <el-form-item label="头像">
              <div class="avatar-line">
                <el-avatar :size="44" :src="form.avatarUrl" class="fr-avatar">
                  {{ (form.nickname || 'H').charAt(0) }}
                </el-avatar>
                <el-input v-model="form.avatarUrl" placeholder="头像图片地址（可留空）" clearable />
              </div>
            </el-form-item>
          </div>
          <el-form-item label="个性签名">
            <el-input v-model="form.signature" placeholder="一句话介绍自己（选填）" maxlength="255" show-word-limit clearable />
          </el-form-item>
        </div>

        <div class="fr-section">
          <div class="fr-section-title">NPC 眼中的你（影响角色对你的了解与回应）</div>
          <div class="fr-grid-2">
            <el-form-item label="身份">
              <el-input v-model="form.identity" placeholder="例如：大学生 / 旅行者 / 编辑" maxlength="255" clearable />
            </el-form-item>
            <el-form-item label="职业">
              <el-input v-model="form.occupation" placeholder="例如：自由撰稿人" maxlength="255" clearable />
            </el-form-item>
            <el-form-item label="喜好">
              <el-input v-model="form.hobbies" placeholder="例如：咖啡、悬疑小说、深夜散步" maxlength="512" clearable />
            </el-form-item>
            <el-form-item label="禁忌（角色应避免冒犯）">
              <el-input v-model="form.taboos" placeholder="例如：不喜欢被问年龄" maxlength="512" clearable />
            </el-form-item>
          </div>
          <el-form-item label="个人档案（自由描述）">
            <el-input
              v-model="form.profileText"
              type="textarea"
              :rows="4"
              placeholder="你的背景、经历、性格、近期状态……角色会把它当作「对你的了解」来对话（选填）"
              clearable
            />
          </el-form-item>
        </div>
      </el-form>

      <!-- 底部操作 -->
      <div class="fr-actions">
        <el-button class="fr-skip" text @click="skip">以后再说</el-button>
        <el-button type="primary" class="fr-save" :loading="saving" @click="save">
          保存并开始
        </el-button>
      </div>
      <p class="fr-tip">提示：本地数据只保存在你的电脑上；跳过也可在「设置 → 通用设置」中随时补充。</p>
    </div>
  </div>
</template>

<script setup>
/**
 * 首次设置向导（本地个人账户，需求 3）。
 * <p>职责：首次启动时引导用户设置本地账户（昵称/头像/签名 + 面向 NPC 的结构化档案与个人档案），
 * 所有字段选填；「保存并开始」保存并标记向导完成，「以后再说」仅标记完成不保存。</p>
 * <p>数据：useAccountStore（本地账户 store），保存走 /api/local-account。</p>
 */
import { ref, reactive, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAccountStore } from '@/shared/store'

const router = useRouter()
const route = useRoute()
const account = useAccountStore()

const saving = ref(false)
const form = reactive({
  nickname: '',
  avatarUrl: '',
  signature: '',
  identity: '',
  occupation: '',
  hobbies: '',
  taboos: '',
  profileText: ''
})

/** 回跳目标（进向导前所在页面，默认画廊） */
function redirectTo() {
  const target = route.query.redirect
  return typeof target === 'string' && target.startsWith('/') ? target : '/'
}

/** 初始化：若已有账户内容则回填 */
async function init() {
  await account.loadAccount(true)
  const a = account.account
  if (a) {
    form.nickname = a.nickname || ''
    form.avatarUrl = a.avatarUrl || ''
    form.signature = a.signature || ''
    form.identity = a.identity || ''
    form.occupation = a.occupation || ''
    form.hobbies = a.hobbies || ''
    form.taboos = a.taboos || ''
    form.profileText = a.profileText || ''
  }
}

/** 保存并完成向导 */
async function save() {
  saving.value = true
  try {
    await account.save({ ...form, onboarded: 1 })
    ElMessage.success('账户设置完成')
    router.push(redirectTo())
  } catch (e) {
    ElMessage.error(e?.message || '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

/** 以后再说：仅标记完成，不保存 */
async function skip() {
  saving.value = true
  try {
    await account.completeOnboarding()
    router.push(redirectTo())
  } catch (e) {
    // 标记失败不阻塞进入（本地运行场景后端应始终可用）
    router.push(redirectTo())
  } finally {
    saving.value = false
  }
}

onMounted(init)
</script>

<style scoped>
.firstrun-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 16px;
  background:
    radial-gradient(1200px 600px at 10% -10%, rgba(64, 128, 255, 0.18), transparent 60%),
    radial-gradient(900px 500px at 110% 110%, rgba(120, 80, 255, 0.15), transparent 60%),
    var(--bg-page, #f2f5f9);
}
.fr-card {
  width: 100%;
  max-width: 760px;
  background: var(--bg-layer-1);
  border-radius: 20px;
  box-shadow: 0 16px 48px rgba(30, 60, 120, 0.12);
  padding: 36px 44px 28px;
}
.fr-header { text-align: center; margin-bottom: 8px; }
.fr-logo { font-size: 1.5rem; font-weight: 800; letter-spacing: 0.5px; color: var(--brand-primary, #1d5cff); }
.fr-logo-sub { font-size: 0.9rem; font-weight: 600; color: var(--text-secondary); margin-left: 4px; }
.fr-title { font-size: 1.5rem; font-weight: 700; margin: 14px 0 8px; color: var(--text-primary); }
.fr-desc { font-size: 0.88rem; color: var(--text-secondary); line-height: 1.7; max-width: 560px; margin: 0 auto 20px; }
.fr-form { text-align: left; }
.fr-section { margin-bottom: 8px; }
.fr-section-title { font-size: 0.9rem; font-weight: 600; color: var(--text-primary); margin: 18px 0 10px; padding-left: 10px; border-left: 3px solid var(--brand-primary, #1d5cff); }
.fr-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 20px; }
.avatar-line { display: flex; align-items: center; gap: 12px; width: 100%; }
.fr-avatar { flex-shrink: 0; background: var(--brand-gradient, linear-gradient(135deg, #1d5cff, #7b4dff)); }
.fr-actions { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--border-light, var(--border-l2)); }
.fr-skip { color: var(--text-secondary); }
.fr-save { min-width: 140px; }
.fr-tip { font-size: 0.75rem; color: var(--text-placeholder, var(--text-tertiary)); margin-top: 14px; text-align: center; }

@media (max-width: 640px) {
  .fr-grid-2 { grid-template-columns: 1fr; }
  .fr-card { padding: 24px 20px 20px; }
}
</style>
