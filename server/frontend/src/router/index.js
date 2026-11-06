import { createRouter, createWebHistory } from 'vue-router'
import { bizModules } from '@/config/modules.generated.js'

// A5：路由表 = 静态页面 + 由 modules.generated.js 动态生成的业务模块路由
const staticChildren = [
  { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '企业安全管理驾驶舱' } },
  { path: 'risk-dashboard', name: 'RiskDashboard', component: () => import('@/views/RiskDashboard.vue'), meta: { title: '风险可视驾驶舱' } },
  { path: 'company', name: 'Company', component: () => import('@/views/Company.vue'), meta: { title: '企业信息管理', roles: ['enterprise', 'admin'] } },
  { path: 'org', name: 'Org', component: () => import('@/views/Org.vue'), meta: { title: '安全管理架构', roles: ['enterprise', 'admin'] } },
  { path: 'post', name: 'Post', component: () => import('@/views/Post.vue'), meta: { title: '岗位信息管理', roles: ['enterprise', 'admin'] } },
  { path: 'template-manager', name: 'TemplateManager', component: () => import('@/views/TemplateManager.vue'), meta: { title: '范本套管理', roles: ['provider', 'admin', 'enterprise'] } },
  { path: 'responsibility', name: 'Responsibility', component: () => import('@/views/Responsibility.vue'), meta: { title: '安全生产责任制', roles: ['enterprise', 'admin'] } },
  { path: 'archive', name: 'Archive', component: () => import('@/views/Archive.vue'), meta: { title: '电子档案柜', roles: ['enterprise', 'provider', 'admin'] } },
  { path: 'archive-overview', name: 'ArchiveOverview', component: () => import('@/views/ArchiveOverview.vue'), meta: { title: '档案柜全景与缺项' } },
  { path: 'collect', name: 'Collect', component: () => import('@/views/Collect.vue'), meta: { title: '服务商代录工作台', roles: ['provider', 'admin'] } },
  { path: 'flow-tools', name: 'FlowTools', component: () => import('@/views/FlowTools.vue'), meta: { title: '业务闭环工具箱' } },
  { path: 'onboarding', name: 'Onboarding', component: () => import('@/views/Onboarding.vue'), meta: { title: '新企业接入引导', roles: ['provider', 'admin'] } },
  { path: 'feedback', name: 'Feedback', component: () => import('@/views/Feedback.vue'), meta: { title: '试运行反馈' } },
  // ===== 阶段 D：三期 v1.2 专用闭环（菜单由 MainLayout 按模块授权过滤）=====
  { path: 'monitor', name: 'Monitor', component: () => import('@/views/Monitor.vue'), meta: { title: '动态监控台账', phase: 'D1' } },
  { path: 'budget', name: 'Budget', component: () => import('@/views/Budget.vue'), meta: { title: '安全生产费用', phase: 'D2' } },
  { path: 'risk-control', name: 'RiskControl', component: () => import('@/views/RiskControl.vue'), meta: { title: '风险分级管控', phase: 'D3' } },
  { path: 'danger-special', name: 'DangerSpecial', component: () => import('@/views/DangerSpecial.vue'), meta: { title: '危货专项深化', phase: 'D4' } },
  { path: 'platform-admin', name: 'PlatformAdmin', component: () => import('@/views/PlatformAdmin.vue'), meta: { title: '平台侧能力', phase: 'D5', roles: ['provider', 'admin'] } },
]

// 由元数据驱动：新增模块只改 tools/gen_biz_meta.py，路由自动出现
const dynamicChildren = bizModules.map((m) => ({
  path: `biz/${m.code}`,
  name: `Biz_${m.code}`,
  component: () => import('@/views/BizView.vue'),
  meta: { title: m.title, phase: m.phase },
}))

const routes = [
  { path: '/login', name: 'Login', component: () => import('@/views/Login.vue'), meta: { title: '登录', public: true } },
  // B9 小程序端：独立 H5 页面，不套后台框架，适老化大字号
  { path: '/m/check', name: 'MobileCheck', component: () => import('@/views/MobileCheck.vue'), meta: { title: '一日三检', public: true } },
  // E2 企业侧三步接入填报（适老化 H5）
  { path: '/m/onboard', name: 'MobileOnboard', component: () => import('@/views/MobileOnboard.vue'), meta: { title: '企业接入登记', public: true } },
  {
    path: '/',
    component: () => import('@/layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [...staticChildren, ...dynamicChildren],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to, from, next) => {
  // 移动端允许 URL 携带 token（由主站登录态透传），便于司机扫码直接进入
  if (to.path.startsWith('/m/') && to.query.token) {
    localStorage.setItem('token', String(to.query.token))
    if (to.query.userType) localStorage.setItem('userType', String(to.query.userType))
  }
  const token = localStorage.getItem('token')
  const userType = localStorage.getItem('userType')

  if (to.meta.public) { next(); return }
  if (!token) { next({ path: '/login', query: { redirect: to.fullPath } }); return }
  if (to.meta.roles && !to.meta.roles.includes(userType)) { next('/dashboard'); return }
  next()
})

export default router
