<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// 侧边栏菜单（按角色过滤）
const menuGroups = [
  {
    group: '首页',
    items: [{ name: '企业安全管理驾驶舱', path: '/dashboard' }],
  },
  {
    group: '电子档案柜（核心）',
    items: [{ name: '电子档案柜', path: '/archive' }],
  },
  {
    group: '基础档案管理',
    roles: ['enterprise', 'admin'],
    items: [
      { name: '企业信息管理', path: '/company' },
      { name: '安全管理架构', path: '/org' },
      { name: '岗位信息管理', path: '/post' },
    ],
  },
  {
    group: '制度档案管理',
    roles: ['enterprise', 'provider', 'admin'],
    items: [
      { name: '范本套管理', path: '/template-manager' },
      { name: '安全生产责任制', path: '/responsibility', roles: ['enterprise', 'admin'] },
    ],
  },
]

// 按当前角色过滤菜单
const visibleGroups = computed(() => {
  const ut = auth.userType
  return menuGroups
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
        <span class="logo-ico">🚚</span>
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
  font-size: 22px;
}
.logo-text {
  font-size: 15px;
  font-weight: 700;
  white-space: nowrap;
}
.menu {
  border-right: none;
}
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
