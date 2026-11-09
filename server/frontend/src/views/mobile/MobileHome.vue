<script setup>
/**
 * 小程序首页 · 按原型 mini/home.html 与需求文档 2.2「今日任务卡」模式。
 * 司机 / 管理双视角；所有待办、角标、概览数字来自 /api/mini/home 聚合接口，
 * 底层是 PC 端同一批业务表（check_task / meeting / training_* / danger_record 等）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/api/request'
import MobileShell from '@/components/MobileShell.vue'
import { mini, ensureMini, goMini, learnerName, enterpriseName } from '@/store/mini'
import { useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const data = ref(null)
const err = ref('')
const personArchive = ref(null)
const vehicleExpiring = ref(0)

async function load() {
  try {
    const res = await request.get('/mini/home', {
      params: { personId: mini.personId, role: mini.role },
    })
    data.value = res.data || {}
  } catch (e) { err.value = e.message || '加载失败' }
  // 一人一档缺项进首页待办：与 PC 端 B1 同源
  try { personArchive.value = (await request.get(`/flow/person/${mini.personId || 0}/archive`)).data || null }
  catch { personArchive.value = null }
  // 车辆证照/保险/等级三表联合到期：与 PC 端 B2 同源，进首页待办
  try { vehicleExpiring.value = Number((await request.get('/flow/vehicle/expiring', { params: { days: 30 } })).data?.total || 0) }
  catch { vehicleExpiring.value = 0 }
}
onMounted(async () => { await ensureMini(route); await load() })

const isManager = computed(() => mini.role === 'manager')
const todos = computed(() => data.value?.todos || [])
const archiveTodo = computed(() => personArchive.value?.ok && (personArchive.value.missing || []).length
  ? { n: (personArchive.value.missing || []).length, percent: personArchive.value.percent } : null)
const vehicleTodo = computed(() => vehicleExpiring.value > 0 ? { n: vehicleExpiring.value } : null)
const totalSteps = computed(() => todos.value.length + (archiveTodo.value ? 1 : 0) + (vehicleTodo.value ? 1 : 0))
const badges = computed(() => data.value?.badges || {})
const manager = computed(() => data.value?.manager || {})
const greeting = computed(() => {
  const h = new Date().getHours()
  return h < 11 ? '早上好' : (h < 14 ? '中午好' : (h < 18 ? '下午好' : '晚上好'))
})

const workerGrid = [
  { ico: '▤', name: '三检记录', desc: '我的填报', path: '/m/list', q: { type: 'checks' }, badge: 'checks' },
  { ico: '修', name: '报修车', desc: '车辆故障', path: '/m/report', q: { type: 'repair' }, badge: 'repair' },
  { ico: '!', name: '报隐患', desc: '拍照上报', path: '/m/report', q: { type: 'danger' }, badge: 'danger' },
  { ico: '✓', name: '会议签到', desc: '一键签到', path: '/m/meeting', badge: 'meeting' },
  { ico: '✎', name: '文件签署', desc: '待签制度', path: '/m/sign', badge: 'sign' },
  { ico: '★', name: '查考核', desc: '奖惩记录', path: '/m/list', q: { type: 'credit' } },
  { ico: '◇', name: '风险辨识', desc: '岗位风险', path: '/m/list', q: { type: 'risk' }, badge: 'risk' },
  { ico: '¥', name: '安全投入', desc: '费用记录', path: '/m/list', q: { type: 'budget' }, badge: 'budget' },
  { ico: '!', name: '隐患台账', desc: '整改反馈', path: '/m/list', q: { type: 'dangers' } },
  { ico: '修', name: '维修记录', desc: '保养台账', path: '/m/list', q: { type: 'repairs' } },
  { ico: '◉', name: '公告通知', desc: '企业通知', path: '/m/notice' },
  { ico: '▦', name: '个人档案', desc: '我的证件', path: '/m/profile' },
]
const managerGrid = [
  { ico: '!', name: '隐患督办', desc: '逾期整改', path: '/m/list', q: { type: 'dangers' }, badge: 'danger' },
  { ico: '▤', name: '三检统计', desc: '全员记录', path: '/m/list', q: { type: 'checks' }, badge: 'checks' },
  { ico: '✓', name: '会议管理', desc: '签到情况', path: '/m/meeting', badge: 'meeting' },
  { ico: '▦', name: '培训管理', desc: '学习计划', path: '/m/study' },
  { ico: '✎', name: '文件签署', desc: '待签统计', path: '/m/sign', badge: 'sign' },
  { ico: '◉', name: '人员档案', desc: '证件到期', path: '/m/profile' },
  { ico: '◇', name: '风险辨识', desc: '风险清单', path: '/m/list', q: { type: 'risk' } },
  { ico: '¥', name: '安全投入', desc: '费用台账', path: '/m/list', q: { type: 'budget' } },
]
const grid = computed(() => isManager.value ? managerGrid : workerGrid)

function go(item) { goMini(router, item.path, item.q || {}) }
function badgeOf(item) { return item.badge ? Number(badges.value[item.badge] || 0) : 0 }
function stepIco(t) { return t.done ? '✓' : (t.key === 'check' ? '1' : (t.key === 'meeting' ? '2' : '3')) }
</script>

<template>
  <MobileShell title="运管管" tab="home">
    <div v-if="err" class="m-err">{{ err }}</div>

    <!-- 问候 -->
    <div style="display:flex;align-items:center;gap:14px;margin-bottom:16px">
      <div style="width:56px;height:56px;border-radius:50%;background:#1E40AF;color:#fff;display:flex;align-items:center;justify-content:center;font-size:26px;font-weight:800;flex-shrink:0">
        {{ (learnerName() || '从')[0] }}
      </div>
      <div style="min-width:0">
        <div style="font-size:calc(22px * var(--m-scale));font-weight:800">{{ learnerName() }}，{{ greeting }}</div>
        <div style="font-size:calc(14px * var(--m-scale));color:#64748B;margin-top:2px">
          {{ isManager ? '安全员' : '驾驶员' }} · {{ enterpriseName() }}
        </div>
      </div>
    </div>

    <!-- 今日必做 -->
    <div class="m-card" style="border:2px solid #1E40AF">
      <div style="font-size:calc(18px * var(--m-scale));font-weight:800;color:#1D4ED8;margin-bottom:12px">
        今天按顺序做这 {{ totalSteps }} 步
        <span style="float:right;font-size:calc(14px * var(--m-scale));color:#64748B">还剩 {{ (data?.todoLeft ?? 0) + (archiveTodo ? 1 : 0) + (vehicleTodo ? 1 : 0) }} 件</span>
      </div>
      <div v-for="t in todos" :key="t.key" class="m-task" :class="t.done ? 'done' : 'active'"
           :style="!t.done ? 'border:2px solid #FCD9B6;background:#FFF8F2' : ''" @click="goMini(router, t.route)">
        <div class="m-t-ico" :class="t.done ? 'green' : 'blue'">{{ stepIco(t) }}</div>
        <div class="m-t-info">
          <div class="m-t-title">{{ t.title }}</div>
          <div class="m-t-desc">{{ t.desc }}</div>
        </div>
        <span class="m-tag" :class="t.done ? 'm-tag-green' : 'm-tag-blue'">{{ t.done ? '已完成' : '去办 ›' }}</span>
      </div>
      <!-- 一人一档缺项：PC 端 B1 联动待办 -->
      <div v-if="archiveTodo" class="m-task active" style="border:2px solid #FCD9B6;background:#FFF8F2"
           @click="goMini(router, '/m/profile')">
        <div class="m-t-ico blue">档</div>
        <div class="m-t-info">
          <div class="m-t-title">一人一档待补齐</div>
          <div class="m-t-desc">还缺 {{ archiveTodo.n }} 项归档资料（当前 {{ archiveTodo.percent }}%）</div>
        </div>
        <span class="m-tag m-tag-blue">去查看 ›</span>
      </div>
      <!-- 车辆证照/保险/等级临期：PC 端 B2 联动待办 -->
      <div v-if="vehicleTodo" class="m-task active" style="border:2px solid #99F6E4;background:#F0FDFA"
           @click="goMini(router, '/m/profile')">
        <div class="m-t-ico green">车</div>
        <div class="m-t-info">
          <div class="m-t-title">车辆证照 / 保险临期</div>
          <div class="m-t-desc">有 {{ vehicleTodo.n }} 项证照、保险或等级评定 30 天内到期</div>
        </div>
        <span class="m-tag m-tag-blue">去查看 ›</span>
      </div>
    </div>

    <!-- 通知横幅 -->
    <div class="m-banner blue" @click="goMini(router, '/m/notice')">
      <span style="font-size:22px">!</span>
      <span>有新通知 {{ data?.unreadNotices ?? 0 }} 条</span>
      <span class="m-b-more">点我看 ›</span>
    </div>
    <div v-if="data?.latestNotice" class="m-card" style="padding:12px 14px" @click="goMini(router, '/m/notice')">
      <div style="font-size:calc(15px * var(--m-scale));font-weight:700">{{ data.latestNotice.title }}</div>
    </div>

    <!-- 管理视角：今日数据概览 -->
    <template v-if="isManager">
      <div class="m-card-title">今日数据（与 PC 端驾驶舱同源）</div>
      <div class="m-card">
        <div style="display:grid;grid-template-columns:repeat(2,1fr);gap:12px">
          <div style="background:#F1F5F9;border-radius:12px;padding:14px;text-align:center">
            <div style="font-size:calc(24px * var(--m-scale));font-weight:800">{{ manager.checkDone }}/{{ manager.checkTotal }}</div>
            <div style="font-size:calc(14px * var(--m-scale));color:#475569;margin-top:4px">今日三检完成</div>
          </div>
          <div style="background:#F1F5F9;border-radius:12px;padding:14px;text-align:center">
            <div style="font-size:calc(24px * var(--m-scale));font-weight:800;color:#DC2626">{{ manager.overdueDanger }}</div>
            <div style="font-size:calc(14px * var(--m-scale));color:#475569;margin-top:4px">逾期隐患</div>
          </div>
          <div style="background:#F1F5F9;border-radius:12px;padding:14px;text-align:center">
            <div style="font-size:calc(24px * var(--m-scale));font-weight:800">{{ manager.pendingStudy }}</div>
            <div style="font-size:calc(14px * var(--m-scale));color:#475569;margin-top:4px">待培训人次</div>
          </div>
          <div style="background:#F1F5F9;border-radius:12px;padding:14px;text-align:center">
            <div style="font-size:calc(24px * var(--m-scale));font-weight:800;color:#B45309">{{ manager.certExpiring }}</div>
            <div style="font-size:calc(14px * var(--m-scale));color:#475569;margin-top:4px">证件将到期</div>
          </div>
        </div>
        <div style="font-size:calc(14px * var(--m-scale));color:#64748B;margin-top:12px;text-align:center">
          在册人员 {{ manager.personCount }} 人 · 车辆 {{ manager.vehicleCount }} 台
        </div>
      </div>
    </template>

    <!-- 功能宫格 -->
    <div class="m-card-title">{{ isManager ? '管理功能' : '更多功能' }}</div>
    <div class="m-grid">
      <div v-for="g in grid" :key="g.name" class="m-grid-item" @click="go(g)">
        <span v-if="badgeOf(g) > 0" class="m-badge">{{ badgeOf(g) }}</span>
        <span class="m-gi-ico" style="color:#1E40AF">{{ g.ico }}</span>
        <span class="m-gi-name">{{ g.name }}</span>
        <span class="m-gi-desc">{{ g.desc }}</span>
      </div>
    </div>
    <div class="m-foot">数据来自 PC 端同一套台账 · 实时互通</div>
  </MobileShell>
</template>
