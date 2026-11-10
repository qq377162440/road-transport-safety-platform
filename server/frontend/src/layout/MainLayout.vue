<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { ElMessageBox } from 'element-plus'
import request from '@/api/request'
import { bizModules } from '@/config/modules.generated.js'
import { DEV_DEMO_ACCOUNTS } from '@/config/devFlag'
import Icon from '@/components/Icon.vue'
import DevMarker from '@/components/DevMarker.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// D5 模块授权：基础模块恒可用；增值（三期 D）模块需平台显式开通。
const dAuth = ref(null)

// 服务商 / 超管「当前服务企业」：范本定制与生成为增值服务，成品须落到指定企业档案柜
const targetTenants = ref([])
async function loadTargetTenants() {
  if (!auth.isPlatform) { targetTenants.value = []; return }
  try { targetTenants.value = (await request.get('/platform/target-tenants')).data || [] }
  catch { targetTenants.value = [] }
  const cur = String(auth.targetTenantId || '')
  if (targetTenants.value.length && !targetTenants.value.some((t) => String(t.id) === cur)) {
    const first = targetTenants.value[0]
    auth.setTargetTenant({ id: first.id, name: first.name })
  }
}
function onTargetTenant(id) {
  const t = targetTenants.value.find((x) => String(x.id) === String(id))
  auth.setTargetTenant({ id, name: t?.name || '' })
  // 已加载的范本 / 档案柜数据需按新企业重新拉取
  window.location.reload()
}

onMounted(async () => {
  // 企业名按当前租户实时获取。AUTO_LOGIN 走路由守卫的 devLogin，不经过登录页，
  // 若只在 Login.vue 里加载，驾驶舱等页面会拿不到 tenantName 而落到兜底文案。
  auth.loadTenantName()
  try { dAuth.value = (await request.get('/platform/my-modules')).data }
  catch { dAuth.value = null }
  await loadTargetTenants()
})
function dModuleAllowed(code) {
  const a = dAuth.value
  if (!a || a.trialAllOpen) return true
  return (a.enabled || []).includes(code)
}

// 菜单（含原型图标），结构与 prototype/index.html 对齐
const staticGroups = [
  { group: '首页', items: [
    { name: '企业安全管理驾驶舱', path: '/dashboard', icon: 'gauge' },
    { name: '风险可视驾驶舱', path: '/risk-dashboard', icon: 'chart' },
    { name: '试运行反馈', path: '/feedback', icon: 'megaphone' },
  ] },
  { group: '电子档案柜（核心）', items: [
    { name: '电子档案柜', path: '/archive', icon: 'archive' },
    { name: '全景与缺项', path: '/archive-overview', icon: 'folder' },
  ] },
  { group: '基础档案管理', roles: ['enterprise', 'admin'], items: [
    { name: '企业信息管理', path: '/company', icon: 'building' },
    { name: '安全管理架构', path: '/org', icon: 'users' },
    { name: '岗位信息管理', path: '/post', icon: 'user' },
  ] },
  { group: '制度档案管理', roles: ['enterprise', 'provider', 'admin'], items: [
    { name: '台账范本管理', path: '/template-manager', icon: 'book' },
    { name: '安全生产责任制', path: '/responsibility', roles: ['enterprise', 'admin'], icon: 'shieldCheck' },
  ] },
  { group: '培训教育管理', roles: ['enterprise', 'admin', 'provider'], items: [
    { name: '线上学习（课程库+试题）', path: '/training/course', icon: 'video' },
    { name: '岗中培训计划', path: '/training/plan', icon: 'bookOpen' },
    { name: '学习情况统计', path: '/training/stat', icon: 'chart' },
  ] },
  { group: '专用业务闭环', items: [
    { name: '业务闭环工具箱', path: '/flow-tools', icon: 'wrench' },
  ] },
  { group: '服务商', roles: ['provider', 'admin'], items: [
    { name: '新企业接入引导', path: '/onboarding', icon: 'map' },
    { name: '代录工作台', path: '/collect', icon: 'clipboard' },
  ] },
  { group: '三期业务闭环（v1.2）', items: [
    { name: '动态监控台账 · D1', path: '/monitor', dModule: 'monitor', icon: 'monitor' },
    { name: '安全生产费用 · D2', path: '/budget', dModule: 'budget', icon: 'trend' },
    { name: '风险分级管控 · D3', path: '/risk-control', dModule: 'risk', icon: 'shield' },
    { name: '危货专项深化 · D4', path: '/danger-special', dModule: 'dangerspecial', icon: 'truck' },
    { name: '平台侧能力 · D5', path: '/platform-admin', dModule: 'platform', roles: ['provider', 'admin'], icon: 'setting' },
  ] },
]

const menuGroups = computed(() => {
  const ut = auth.userType
  const dyn = {}
  for (const m of bizModules) {
    if (m.phase === 'A2' && ut !== 'admin') continue
    if (m.code === 'collect' && !['provider', 'admin'].includes(ut)) continue
    if (m.phase.startsWith('D') && !dModuleAllowed(m.code)) continue
    ;(dyn[m.group] ||= []).push({
      name: `${m.title} · ${m.phase}`,
      path: `/biz/${m.code}`,
      icon: 'table',
      dModule: m.phase.startsWith('D') ? m.code : null,
    })
  }
  return [
    ...staticGroups,
    ...Object.entries(dyn).map(([group, items]) => ({ group, items })),
  ]
})

const visibleGroups = computed(() => {
  const ut = auth.userType
  return menuGroups.value
    .map((g) => ({
      ...g,
      items: (g.items || []).filter(
        (it) => (!it.roles || it.roles.includes(ut)) && (!it.dModule || dModuleAllowed(it.dModule)),
      ),
    }))
    .filter((g) => (!g.roles || g.roles.includes(ut)) && g.items.length > 0)
})

// 角色切换（与登录账号一致：切换即切换账号）
const roleOptions = [
  { key: 'enterprise', label: '企业' },
  { key: 'provider', label: '服务商' },
  { key: 'admin', label: '超管' },
]
async function onRole(role) {
  if (role === auth.userType) return
  const label = roleOptions.find(r => r.key === role).label
  try {
    await ElMessageBox.confirm(`切换为「${label}」账号将退出当前登录，是否继续？`, '切换账号', {
      type: 'warning',
    })
  } catch { return }
  // 演示态：切换角色 = 用该角色的演示账号重新登录（AUTO_LOGIN 下跳登录页会被自动登回企业账号）
  await auth.logout()
  await auth.devLogin(DEV_DEMO_ACCOUNTS[role])
  await loadTargetTenants()
  router.push('/dashboard')
}

// 客户类型切换（演示态，持久化到 localStorage）
const cargoOptions = ['普货', '危货', '客运']
const cargoType = ref(localStorage.getItem('cargoType') || '普货')
function onCargo(c) {
  if (c === '客运') { ElMessageInfo('客运已暂缓，以后再说'); return }
  cargoType.value = c
  localStorage.setItem('cargoType', c)
}
function ElMessageInfo(msg) {
  // 轻量提示，避免额外 import ElMessage 命名冲突
  import('element-plus').then(({ ElMessage }) => ElMessage(msg))
}

const tabMode = ref('pc')
function onMode(mode) {
  tabMode.value = mode
  if (mode === 'mini') router.push('/m/check')
}

async function handleLogout() {
  await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="app-shell">
    <!-- 顶栏 -->
    <header class="topbar">
      <div class="logo">
        <span class="mark"><Icon name="truck" :size="17" /></span>
        道路运输安全生产管理平台
      </div>
      <div class="sub">安全生产 · 台账合规 · 服务商一体化运营</div>

      <div class="cargo-switch" id="roleSwitch">
        <span class="cs-label">角色</span>
        <button
          v-for="r in roleOptions"
          :key="r.key"
          class="cs-btn"
          :class="{ on: auth.userType === r.key }"
          @click="onRole(r.key)"
        >{{ r.label }}</button>
      </div>

      <div v-if="auth.isPlatform" class="cargo-switch" id="tenantSwitch">
        <span class="cs-label">服务企业</span>
        <select class="tenant-select" :value="auth.targetTenantId" @change="onTargetTenant($event.target.value)">
          <option value="">请选择企业</option>
          <option v-for="t in targetTenants" :key="t.id" :value="String(t.id)">{{ t.name }}</option>
        </select>
      </div>

      <!-- 客户类型是企业视角的业务属性；服务商/超管视角改由「服务企业」下拉承载，避免顶栏拥挤 -->
      <div v-if="!auth.isPlatform" class="cargo-switch" id="cargoSwitch">
        <span class="cs-label">客户类型</span>
        <button
          v-for="c in cargoOptions"
          :key="c"
          class="cs-btn"
          :class="{ on: cargoType === c }"
          @click="onCargo(c)"
        >{{ c }}</button>
      </div>

      <div class="tag-demo">Demo v1.2</div>
      <button class="logout-btn" @click="handleLogout" title="退出登录">
        <Icon name="logOut" :size="15" /> 退出
      </button>
    </header>

    <!-- PC / 小程序 切换 -->
    <nav class="tabs">
      <div class="tab" :class="{ active: tabMode === 'pc' }" @click="onMode('pc')">
        <Icon name="monitor" :size="16" /> 网页端（PC）
      </div>
      <div class="tab" :class="{ active: tabMode === 'mini' }" @click="onMode('mini')">
        <Icon name="smartphone" :size="16" /> 小程序端
      </div>
    </nav>

    <!-- 主体 -->
    <div class="layout">
      <aside class="sidebar">
        <template v-for="g in visibleGroups" :key="g.group">
          <div class="group">{{ g.group }}</div>
          <router-link
            v-for="it in g.items"
            :key="it.path"
            :to="it.path"
            class="item"
            :class="{ active: route.path === it.path }"
          >
            <Icon :name="it.icon" :size="16" /> {{ it.name }}
          </router-link>
        </template>
      </aside>

      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
  <DevMarker />
</template>

<style scoped>
.app-shell { height: 100vh; display: flex; flex-direction: column; overflow: hidden; }

/* ── 顶栏（对齐 prototype 蓝顶栏）── */
.topbar {
  height: 56px; background: var(--blue-900); display: flex; align-items: center;
  padding: 0 20px; color: #fff; box-shadow: 0 2px 8px rgba(0,0,0,.15); position: relative; z-index: 10;
  flex-shrink: 0;
}
.topbar .logo { font-size: 17px; font-weight: 700; letter-spacing: .5px; display: flex; align-items: center; gap: 10px; white-space: nowrap; flex-shrink: 0; }
.topbar .logo .mark { width: 30px; height: 30px; border-radius: 8px; background: var(--blue-800); display: flex; align-items: center; justify-content: center; }
.topbar .logo .mark :deep(.ico) { font-size: 17px; color: #fff; }
.topbar .sub { font-size: 12.5px; opacity: .75; margin-left: 14px; white-space: nowrap; }
.topbar .tag-demo { background: rgba(255,255,255,.14); padding: 5px 14px; border-radius: 20px; font-size: 12.5px; margin-left: 14px; }
.topbar .logout-btn {
  margin-left: 14px; border: none; background: rgba(255,255,255,.14); color: #fff; padding: 7px 14px;
  border-radius: 8px; font-size: 13px; cursor: pointer; display: flex; align-items: center; gap: 6px;
}
.topbar .logout-btn:hover { background: rgba(255,255,255,.24); }

.cargo-switch { margin-left: auto; display: flex; align-items: center; gap: 4px; background: rgba(255,255,255,.12); padding: 4px; border-radius: 10px; margin-right: 0; }
.cargo-switch + .cargo-switch { margin-left: 14px; }
.cs-label { font-size: 12px; opacity: .8; padding: 0 8px; white-space: nowrap; }
.cs-btn { border: none; background: transparent; color: #fff; font-size: 12.5px; font-weight: 600; padding: 5px 12px; border-radius: 7px; cursor: pointer; transition: .12s; }
.cs-btn.on { background: #fff; color: var(--blue-900); }
.tenant-select { border: none; background: #fff; color: var(--blue-900, #1E3A8A); font-size: 12.5px; font-weight: 600; padding: 5px 8px; border-radius: 7px; max-width: 170px; cursor: pointer; }

/* ── PC / 小程序 tabs ── */
.tabs { height: 46px; background: #fff; border-bottom: 1px solid var(--border); display: flex; align-items: center; padding: 0 16px; gap: 6px; flex-shrink: 0; }
.tabs .tab { padding: 8px 16px; border-radius: 8px; cursor: pointer; font-size: 14.5px; color: var(--text); transition: .15s; white-space: nowrap; font-weight: 500; display: flex; align-items: center; gap: 7px; }
.tabs .tab:hover { background: #f3f4f6; }
.tabs .tab.active { background: var(--blue-800); color: #fff; }

/* ── 布局 ── */
.layout { display: flex; flex: 1; min-height: 0; }

/* ── 白色分组侧栏 ── */
.sidebar { width: 240px; background: #fff; border-right: 1px solid var(--border); overflow-y: auto; padding: 10px 0; flex-shrink: 0; }
.sidebar .group { font-size: 12px; color: var(--text-3); padding: 12px 20px 6px; font-weight: 600; letter-spacing: .5px; }
.sidebar .item {
  display: flex; align-items: center; gap: 10px; padding: 10px 20px; font-size: 14px; color: var(--text-2);
  cursor: pointer; transition: .12s; border-left: 3px solid transparent;
}
.sidebar .item :deep(.ico) { font-size: 16px; width: 18px; text-align: center; flex-shrink: 0; }
.sidebar .item:hover { background: #f3f4f6; color: var(--blue-800); }
.sidebar .item.active { background: #e8f1fc; color: var(--blue-800); border-left-color: var(--blue-800); font-weight: 600; }
.sidebar .item.router-link-active { background: #e8f1fc; color: var(--blue-800); border-left-color: var(--blue-800); font-weight: 600; }

/* ── 内容区 ── */
.content { flex: 1; min-width: 0; padding: 22px 26px; overflow-y: auto; background: var(--bg); }
</style>
