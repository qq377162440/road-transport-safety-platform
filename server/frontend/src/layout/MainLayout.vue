<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { ElMessageBox } from 'element-plus'
import { bizModules } from '@/config/modules.generated.js'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// A5：菜单由配置驱动 —— 静态入口 + 业务模块元数据自动生成
const staticGroups = [
  { group: '首页', items: [
    { name: '企业安全管理驾驶舱', path: '/dashboard' },
    { name: '风险可视驾驶舱', path: '/risk-dashboard' },
    { name: '试运行反馈', path: '/feedback' },
  ] },
  { group: '电子档案柜（核心）', items: [
    { name: '电子档案柜', path: '/archive' },
    { name: '全景与缺项', path: '/archive-overview' },
  ] },
  { group: '基础档案管理', roles: ['enterprise', 'admin'], items: [
    { name: '企业信息管理', path: '/company' },
    { name: '安全管理架构', path: '/org' },
    { name: '岗位信息管理', path: '/post' },
  ] },
  { group: '制度档案管理', roles: ['enterprise', 'provider', 'admin'], items: [
    { name: '范本套管理', path: '/template-manager' },
    { name: '安全生产责任制', path: '/responsibility', roles: ['enterprise', 'admin'] },
  ] },
  { group: '专用业务闭环', items: [
    { name: '业务闭环工具箱', path: '/flow-tools' },
  ] },
  { group: '服务商', roles: ['provider', 'admin'], items: [
    { name: '新企业接入引导', path: '/onboarding' },
    { name: '代录工作台', path: '/collect' },
  ] },
]

const menuGroups = computed(() => {
  const ut = auth.userType
  const dyn = {}
  for (const m of bizModules) {
    if (m.phase === 'A2' && ut !== 'admin') continue
    if (m.code === 'collect' && !['provider', 'admin'].includes(ut)) continue
    ;(dyn[m.group] ||= []).push({ name: `${m.title} · ${m.phase}`, path: `/biz/${m.code}` })
  }
  return [
    ...staticGroups,
    ...Object.entries(dyn).map(([group, items]) => ({ group, items })),
  ]
})

// 按当前角色过滤菜单
const visibleGroups = computed(() => {
  const ut = auth.userType
  return menuGroups.value
    .map((g) => ({
      ...g,
      items: (g.items || []).filter(
        (it) => !it.roles || it.roles.includes(ut),
      ),
    }))
    .filter((g) => (!g.roles || g.roles.includes(ut)) && g.items.length > 0)
})

const collapsed = ref(false)

async function handleLogout() {
  await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <!-- 侧边栏 -->
    <el-aside :width="collapsed ? '64px' : '220px'" class="aside">
      <div class="logo">
        <span class="logo-ico">运</span>
        <span v-if="!collapsed" class="logo-text">道路运输安全管理</span>
      </div>
      <el-menu
        :default-active="route.path"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        background-color="#0f1c3f"
        text-color="#cbd5e1"
        active-text-color="#ffffff"
        class="menu"
      >
        <template v-for="g in visibleGroups" :key="g.group">
          <el-menu-item-group v-if="!collapsed" :title="g.group">
            <el-menu-item v-for="it in g.items" :key="it.path" :index="it.path">
              {{ it.name }}
            </el-menu-item>
          </el-menu-item-group>
          <template v-else>
            <el-menu-item v-for="it in g.items" :key="it.path" :index="it.path">
              {{ it.name.slice(0, 2) }}
            </el-menu-item>
          </template>
        </template>
      </el-menu>
    </el-aside>

    <!-- 主区域 -->
    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-button text @click="collapsed = !collapsed">
            <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
          </el-button>
          <span class="page-title">{{ route.meta.title }}</span>
        </div>
        <div class="header-right">
          <el-tag size="small" type="primary" effect="plain">{{ auth.roleLabel }}</el-tag>
          <span v-if="auth.tenantName" class="tenant">{{ auth.tenantName }}</span>
          <el-dropdown @command="(c) => c === 'logout' && handleLogout()">
            <span class="user">
              {{ auth.username || '未登录' }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}
.aside {
  background: #0f1c3f;
  transition: width 0.2s;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  color: #fff;
  gap: 10px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.logo-ico {
  width: 26px; height: 26px; flex: 0 0 26px;
  display: inline-flex; align-items: center; justify-content: center;
  background: #2563eb; color: #fff; border-radius: 6px;
  font-size: 15px; font-weight: 700;
}
.logo-text {
  font-size: 15px;
  font-weight: 700;
  white-space: nowrap;
}
.menu {
  border-right: none;
  flex: 1;
  overflow-y: auto;
}
.menu::-webkit-scrollbar { width: 4px; }
.menu::-webkit-scrollbar-thumb { background: rgba(255,255,255,.2); border-radius: 2px; }
.header {
  background: #fff;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  padding: 0 20px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}
.tenant {
  font-size: 13px;
  color: var(--text-2);
}
.user {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
}
.main {
  background: var(--bg);
  padding: 20px;
  overflow: auto;
}
</style>
