<template>
  <div class="account-page">
    <div class="ac-card">
      <div class="ac-header">
        <h2>本地账户设置</h2>
        <p>这些信息将作为「NPC 认识你的档案」注入对话，影响角色对你的了解与回应。所有字段选填。</p>
      </div>

      <el-form :model="form" label-position="top" class="ac-form">
        <div class="ac-section">
          <div class="ac-section-title">基本信息</div>
          <div class="ac-grid-2">
            <el-form-item label="昵称（显示名）">
              <el-input v-model="form.nickname" placeholder="例如：林晚" maxlength="64" show-word-limit clearable />
            </el-form-item>
            <el-form-item label="头像">
              <div class="avatar-line">
                <el-avatar :size="44" :src="form.avatarUrl" class="ac-avatar">
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

        <div class="ac-section">
          <div class="ac-section-title">NPC 眼中的你</div>
          <div class="ac-grid-2">
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

      <div class="ac-actions">
        <el-button @click="router.back()">返回</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 本地账户设置页（全局 /account，独立于项目空间）。
 * <p>职责：随时编辑本地账户（昵称/头像/签名/结构化档案/个人档案），供顶栏账户菜单与设置页入口使用。</p>
 * <p>数据：useAccountStore。</p>
 */
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAccountStore } from '@/shared/store'

const router = useRouter()
const account = useAccountStore()
const saving = ref(false)
const form = reactive({
  nickname: '', avatarUrl: '', signature: '',
  identity: '', occupation: '', hobbies: '', taboos: '', profileText: ''
})

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

async function save() {
  saving.value = true
  try {
    await account.save({ ...form, onboarded: 1 })
    ElMessage.success('账户设置已保存')
    router.back()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

onMounted(init)
</script>

<style scoped>
.account-page { min-height: 100vh; padding: 32px 16px; background: var(--bg-page, #eef1f6); display: flex; justify-content: center; }
.ac-card { width: 100%; max-width: 720px; background: var(--bg-layer-1); border-radius: 18px; box-shadow: 0 10px 32px rgba(24, 50, 100, 0.10); padding: 32px 40px; height: fit-content; }
.ac-header h2 { margin: 0 0 6px; color: var(--text-primary); }
.ac-header p { margin: 0 0 8px; color: var(--text-secondary); font-size: 0.85rem; line-height: 1.7; }
.ac-section-title { font-size: 0.9rem; font-weight: 600; color: var(--text-primary); margin: 18px 0 10px; padding-left: 10px; border-left: 3px solid var(--brand-primary, #1d5cff); }
.ac-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 20px; }
.avatar-line { display: flex; align-items: center; gap: 12px; width: 100%; }
.ac-avatar { flex-shrink: 0; background: var(--brand-gradient, linear-gradient(135deg, #1d5cff, #7b4dff)); }
.ac-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--border-light, var(--border-l2)); }
@media (max-width: 640px) { .ac-grid-2 { grid-template-columns: 1fr; } .ac-card { padding: 24px 20px; } }
</style>
