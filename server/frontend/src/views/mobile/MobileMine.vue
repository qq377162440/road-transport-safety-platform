<script setup>
/**
 * 「我的」Tab · 按原型 mini/mine.html 与需求文档 1.5。
 * 个人档案、绑定车辆、证件到期、履职统计均来自 /api/mini/mine（PC 端同源台账）；
 * 设置内含字号三档、修改密码（真实接口）、意见反馈（真实写入 sys_trial_feedback）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/api/request'
import MobileShell from '@/components/MobileShell.vue'
import { mini, ensureMini, goMini, setScale, learnerName, enterpriseName } from '@/store/mini'

const route = useRoute()
const router = useRouter()
const data = ref(null)
const err = ref('')
const panel = ref('')            // '' | settings | feedback | about | password
const feedbackText = ref('')
const feedbackMsg = ref('')
const pwd = ref({ oldPassword: '', newPassword: '' })
const tip = ref('')

async function load() {
  try {
    const res = await request.get('/mini/mine', { params: { personId: mini.personId } })
    data.value = res.data || {}
  } catch (e) { err.value = e.message || '加载失败' }
}
onMounted(async () => { await ensureMini(route); await load() })

const person = computed(() => data.value?.person || {})
const vehicle = computed(() => data.value?.vehicle)
const docs = computed(() => data.value?.docs || [])
const stats = computed(() => data.value?.stats || {})
const study = computed(() => data.value?.study || {})

const menus = [
  { ico: '▤', name: '我的三检记录', path: '/m/list', q: { type: 'checks' } },
  { ico: '★', name: '我的考核', path: '/m/list', q: { type: 'credit' } },
  { ico: '!', name: '消息通知', path: '/m/notice' },
  { ico: '◉', name: '个人档案与证件', path: '/m/profile' },
]

async function submitFeedback() {
  if (!feedbackText.value.trim()) { feedbackMsg.value = '请先写下您的意见'; return }
  try {
    await request.post('/feedback', {
      content: feedbackText.value.trim(),
      feedback_type: 'suggestion',
      module: '小程序端',
      severity: 2,
      contact: person.value?.telephone || '',
    })
    feedbackMsg.value = '已提交，感谢您的反馈！'
    feedbackText.value = ''
  } catch (e) { feedbackMsg.value = '提交失败：' + (e.message || '请稍后再试') }
}

async function changePassword() {
  if (!pwd.value.oldPassword || !pwd.value.newPassword) { tip.value = '请填写原密码和新密码'; return }
  try {
    await request.post('/auth/change-password', { ...pwd.value })
    tip.value = '密码已修改，请重新登录'
    pwd.value = { oldPassword: '', newPassword: '' }
  } catch (e) { tip.value = '修改失败：' + (e.message || '请检查原密码') }
}

function logout() {
  localStorage.removeItem('token')
  panel.value = ''
  goMini(router, '/m/home')
}
function scaleLabel(v) { return v >= 1.28 ? '超大' : (v >= 1.1 ? '大字' : '标准') }
</script>

<template>
  <MobileShell title="我的" tab="mine">
    <div v-if="err" class="m-err">{{ err }}</div>

    <!-- 用户信息 -->
    <div class="m-hero" style="display:flex;align-items:center;gap:14px" @click="goMini(router, '/m/profile')">
      <div style="width:58px;height:58px;border-radius:50%;background:rgba(255,255,255,.22);display:flex;align-items:center;justify-content:center;font-size:26px;font-weight:800;flex-shrink:0">
        {{ (learnerName() || '从')[0] }}
      </div>
      <div style="flex:1;min-width:0">
        <div style="font-size:calc(21px * var(--m-scale));font-weight:800">{{ learnerName() }}</div>
        <div style="font-size:calc(14px * var(--m-scale));opacity:.9;margin-top:2px">
          {{ person.telephone || '—' }} · {{ vehicle?.plate_no || '未绑定车辆' }}
        </div>
        <div style="font-size:calc(13px * var(--m-scale));opacity:.85;margin-top:2px">{{ enterpriseName() }}</div>
      </div>
      <span style="font-size:20px">›</span>
    </div>

    <!-- 履职统计 -->
    <div class="m-card">
      <div style="display:grid;grid-template-columns:repeat(4,1fr);gap:6px;text-align:center">
        <div><div style="font-size:calc(22px * var(--m-scale));font-weight:800;color:#1E40AF">{{ stats.checks || 0 }}</div><div style="font-size:calc(12px * var(--m-scale));color:#64748B">三检</div></div>
        <div><div style="font-size:calc(22px * var(--m-scale));font-weight:800;color:#1E40AF">{{ stats.dangers || 0 }}</div><div style="font-size:calc(12px * var(--m-scale));color:#64748B">报隐患</div></div>
        <div><div style="font-size:calc(22px * var(--m-scale));font-weight:800;color:#1E40AF">{{ stats.meetings || 0 }}</div><div style="font-size:calc(12px * var(--m-scale));color:#64748B">签到</div></div>
        <div><div style="font-size:calc(22px * var(--m-scale));font-weight:800;color:#1E40AF">{{ stats.exams || 0 }}</div><div style="font-size:calc(12px * var(--m-scale));color:#64748B">考试</div></div>
      </div>
      <div v-if="study.hasPlan" style="margin-top:14px;border-top:1px solid #E2E8F0;padding-top:12px">
        <div style="font-size:calc(14px * var(--m-scale));color:#475569">
          本月学习 {{ study.progress }}% · {{ study.passed ? '考核已通过' : '待完成考核' }}
        </div>
        <div class="m-bar dark" style="margin-top:8px"><i :style="{ width: (study.progress || 0) + '%' }" /></div>
      </div>
    </div>

    <!-- 我的证件 -->
    <div class="m-card-title">我的证件 <span class="m-more" @click="goMini(router, '/m/profile')">查看全部 ›</span></div>
    <div class="m-card" style="padding:8px">
      <div v-for="d in docs" :key="d.owner_type + '-' + d.id" class="m-task" style="margin-bottom:8px;box-shadow:none">
        <div class="m-t-ico" :class="d.warning ? 'amber' : 'green'">{{ d.warning ? '!' : '✓' }}</div>
        <div class="m-t-info">
          <div class="m-t-title">{{ d.doc_name }}</div>
          <div class="m-t-desc">{{ String(d.expire_date || '').slice(0, 10) }} 到期 · {{ d.daysLeft >= 0 ? '还有 ' + d.daysLeft + ' 天' : '已过期' }}</div>
        </div>
        <span class="m-tag" :class="d.warning ? 'm-tag-yellow' : 'm-tag-green'">{{ d.warning ? '快到期' : '正常' }}</span>
      </div>
      <div v-if="!docs.length" class="m-empty" style="padding:16px">暂无证件记录</div>
    </div>

    <!-- 功能菜单 -->
    <div class="m-card" style="padding:0;overflow:hidden">
      <div v-for="m in menus" :key="m.name" class="m-task" style="margin:0;border-radius:0;border:none;border-bottom:1px solid #E2E8F0;box-shadow:none"
           @click="goMini(router, m.path, m.q || {})">
        <div class="m-t-ico blue" style="width:40px;height:40px;font-size:20px">{{ m.ico }}</div>
        <div class="m-t-info"><div class="m-t-title">{{ m.name }}</div></div>
        <span style="color:#CBD5E1;font-size:20px">›</span>
      </div>
      <div class="m-task" style="margin:0;border-radius:0;border:none;border-bottom:1px solid #E2E8F0;box-shadow:none" @click="panel = 'feedback'">
        <div class="m-t-ico blue" style="width:40px;height:40px;font-size:20px">✎</div>
        <div class="m-t-info"><div class="m-t-title">意见反馈</div></div><span style="color:#CBD5E1;font-size:20px">›</span>
      </div>
      <div class="m-task" style="margin:0;border-radius:0;border:none;box-shadow:none" @click="panel = 'settings'">
        <div class="m-t-ico blue" style="width:40px;height:40px;font-size:20px">⚙</div>
        <div class="m-t-info"><div class="m-t-title">设置</div></div>
        <span class="m-tag m-tag-gray">{{ scaleLabel(mini.scale) }}</span>
      </div>
    </div>

    <div class="m-foot" @click="panel = 'about'">运管管 · 道路运输安全助手 v1.0 · 关于我们</div>

    <!-- 设置 -->
    <div v-if="panel === 'settings'" class="m-card">
      <div class="m-card-title" style="margin-top:0">设置 <span class="m-more" @click="panel = ''">关闭 ✕</span></div>
      <label class="m-label" style="margin-top:0">字号大小（适老化）</label>
      <div class="m-chips">
        <button v-for="s in [1, 1.15, 1.3]" :key="s" class="m-chip" :class="{ on: Math.abs(mini.scale - s) < 0.01 }" @click="setScale(s)">
          {{ scaleLabel(s) }}
        </button>
      </div>
      <label class="m-label">账号</label>
      <button class="m-btn m-btn-outline" @click="panel = 'password'">修改密码</button>
      <button class="m-btn m-btn-gray" @click="logout">退出登录</button>
      <div class="m-tip">演示环境开启自动登录，退出后会自动重新登录；正式上线后由登录页接管。</div>
    </div>

    <!-- 修改密码 -->
    <div v-if="panel === 'password'" class="m-card">
      <div class="m-card-title" style="margin-top:0">修改密码 <span class="m-more" @click="panel = 'settings'">返回 ‹</span></div>
      <input v-model="pwd.oldPassword" type="password" class="m-input" placeholder="原密码" />
      <input v-model="pwd.newPassword" type="password" class="m-input" style="margin-top:10px" placeholder="新密码" />
      <button class="m-btn m-btn-primary" @click="changePassword">确认修改</button>
      <div v-if="tip" class="m-tip">{{ tip }}</div>
    </div>

    <!-- 意见反馈 -->
    <div v-if="panel === 'feedback'" class="m-card">
      <div class="m-card-title" style="margin-top:0">意见反馈 <span class="m-more" @click="panel = ''">关闭 ✕</span></div>
      <textarea v-model="feedbackText" class="m-textarea" placeholder="用大白话写下您遇到的问题或建议，不用打字很多" />
      <button class="m-btn m-btn-primary" @click="submitFeedback">提交反馈</button>
      <div v-if="feedbackMsg" class="m-tip">{{ feedbackMsg }}</div>
      <div class="m-foot" style="padding:8px">反馈会进入 PC 端「试运行反馈」台账</div>
    </div>

    <!-- 关于 -->
    <div v-if="panel === 'about'" class="m-card">
      <div class="m-card-title" style="margin-top:0">关于我们 <span class="m-more" @click="panel = ''">关闭 ✕</span></div>
      <div style="font-size:calc(16px * var(--m-scale));line-height:1.9;color:#475569">
        运管管 · 道路运输安全生产平台小程序端。<br />
        首页、一日三检、在线学习、我的档案与各功能台账，
        与 PC 管理端使用同一套业务数据，安全员下发、司机填报，实时互通。
      </div>
    </div>
  </MobileShell>
</template>
