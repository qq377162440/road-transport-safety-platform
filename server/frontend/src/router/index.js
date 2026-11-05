import { createRouter, createWebHistory } from 'vue-router'

// 路由表（第一阶段垂直切片涉及的页面 + 占位页）
const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    component: () => import('@/layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '企业安全管理驾驶舱' },
      },
      {
        path: 'company',
        name: 'Company',
        component: () => import('@/views/Company.vue'),
        meta: { title: '企业信息管理', roles: ['enterprise', 'admin'] },
      },
      {
        path: 'org',
        name: 'Org',
        component: () => import('@/views/Org.vue'),
        meta: { title: '安全管理架构', roles: ['enterprise', 'admin'] },
      },
      {
        path: 'post',
        name: 'Post',
        component: () => import('@/views/Post.vue'),
        meta: { title: '岗位信息管理', roles: ['enterprise', 'admin'] },
      },
      {
        path: 'template-manager',
        name: 'TemplateManager',
        component: () => import('@/views/TemplateManager.vue'),
        meta: { title: '范本套管理', roles: ['provider', 'admin', 'enterprise'] },
      },
      {
        path: 'responsibility',
        name: 'Responsibility',
        component: () => import('@/views/Responsibility.vue'),
        meta: { title: '安全生产责任制', roles: ['enterprise', 'admin'] },
      },
      {
        path: 'archive',
        name: 'Archive',
        component: () => import('@/views/Archive.vue'),
        meta: { title: '电子档案柜', roles: ['enterprise', 'provider', 'admin'] },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫：未登录跳登录页；按角色过滤菜单
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  const userType = localStorage.getItem('userType')

  if (to.meta.public) {
    next()
    return
  }

  if (!token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  // 角色校验（若页面标注了 roles）
  if (to.meta.roles && !to.meta.roles.includes(userType)) {
    next('/dashboard')
    return
  }

  next()
})

export default router
