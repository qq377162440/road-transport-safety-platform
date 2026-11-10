import { createRouter, createWebHistory } from 'vue-router'
import { bizModules } from '@/config/modules.generated.js'
import { AUTO_LOGIN, DEV_DEMO_USER, DEV_DEMO_PWD } from '@/config/devFlag'
import { useAuthStore } from '@/store/auth'

// A5：路由表 = 静态页面 + 由 modules.generated.js 动态生成的业务模块路由
const staticChildren = [
  { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '企业安全管理驾驶舱' } },
  { path: 'risk-dashboard', name: 'RiskDashboard', component: () => import('@/views/RiskDashboard.vue'), meta: { title: '风险可视驾驶舱' } },
  { path: 'company', name: 'Company', component: () => import('@/views/Company.vue'), meta: { title: '企业信息管理', roles: ['enterprise', 'admin'] } },
  { path: 'org', name: 'Org', component: () => import('@/views/Org.vue'), meta: { title: '安全管理架构', roles: ['enterprise', 'admin'] } },
  { path: 'post', name: 'Post', component: () => import('@/views/Post.vue'), meta: { title: '岗位信息管理', roles: ['enterprise', 'admin'] } },
  { path: 'template-manager', name: 'TemplateManager', component: () => import('@/views/TemplateManager.vue'), meta: { title: '台账范本管理', roles: ['provider', 'admin', 'enterprise'] } },
  { path: 'responsibility', name: 'Responsibility', component: () => import('@/views/Responsibility.vue'), meta: { title: '安全生产责任制', roles: ['enterprise', 'admin'] } },
  { path: 'archive', name: 'Archive', component: () => import('@/views/Archive.vue'), meta: { title: '电子档案柜', roles: ['enterprise', 'provider', 'admin'] } },
  { path: 'archive-overview', name: 'ArchiveOverview', component: () => import('@/views/ArchiveOverview.vue'), meta: { title: '档案柜全景与缺项' } },
  // ===== C1 培训教育（PC 下发端）=====
  { path: 'training/course', name: 'TrainingCourse', component: () => import('@/views/TrainingCourse.vue'), meta: { title: '线上学习（课程库+试题管理）', roles: ['enterprise', 'provider', 'admin'] } },
  { path: 'training/plan', name: 'TrainingPlan', component: () => import('@/views/TrainingPlan.vue'), meta: { title: '岗中培训计划', roles: ['enterprise', 'provider', 'admin'] } },
  { path: 'training/stat', name: 'TrainingStat', component: () => import('@/views/TrainingStat.vue'), meta: { title: '学习情况统计', roles: ['enterprise', 'provider', 'admin'] } },
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
  // C4 旧链接兼容：考核奖惩真实模块 code 为 assess
  { path: 'biz/credit', redirect: '/biz/assess' },
]

// 由元数据驱动：新增模块只改 tools/gen_biz_meta.py，路由自动出现
// 已完成「完整流程闭环」的模块走独立专用页（如 B1 人员基础信息），
// 其余模块暂走 BizModule.vue 配置化容器 + 特色区块，逐个深化后逐个替换。
// moduleCode 供专用页定位模块（原 route.params.module 恒为空，导致整批空白）
const CLOSED_LOOP_PAGES = {
  person: () => import('@/views/biz/person/PersonModule.vue'),
  vehicle: () => import('@/views/biz/vehicle/VehicleModule.vue'),
  institution: () => import('@/views/biz/institution/InstitutionModule.vue'),
  check: () => import('@/views/biz/check/CheckModule.vue'),
  danger: () => import('@/views/biz/danger/DangerModule.vue'),
  meeting: () => import('@/views/biz/meeting/MeetingModule.vue'),
  emergency: () => import('@/views/biz/meeting/EmergencyModule.vue'),
  accident: () => import('@/views/biz/meeting/AccidentModule.vue'),
  // 考核奖惩的模块 code 是 assess（见 modules.generated.js），必须用 code 作为 key，
  // 否则动态路由 biz/assess 会落到通用 BizModule.vue，专用页永远不会被加载。
  assess: () => import('@/views/biz/meeting/CreditModule.vue'),
  sign: () => import('@/views/biz/meeting/SignModule.vue'),
}
const dynamicChildren = bizModules.map((m) => ({
  path: `biz/${m.code}`,
  name: `Biz_${m.code}`,
  component: CLOSED_LOOP_PAGES[m.code] || (() => import('@/views/biz/BizModule.vue')),
  meta: { title: m.title, phase: m.phase, moduleCode: m.code, group: m.group, catalog: m.catalog },
}))

const routes = [
  { path: '/login', name: 'Login', component: () => import('@/views/Login.vue'), meta: { title: '登录', public: true } },
  // ===== 小程序端（运管管）正式版：4 Tab + 功能子页，统一 MobileShell 外壳 =====
  { path: '/m', redirect: '/m/home' },
  { path: '/m/home', name: 'MobileHome', component: () => import('@/views/mobile/MobileHome.vue'), meta: { title: '首页', public: true } },
  { path: '/m/check', name: 'MobileCheck', component: () => import('@/views/mobile/MobileCheck.vue'), meta: { title: '一日三检', public: true } },
  // C1 学习中心 → 在线看视频（断点续播）→ 在线考核（自动判分）
  { path: '/m/study', name: 'MobileStudy', component: () => import('@/views/mobile/MobileStudy.vue'), meta: { title: '学习中心', public: true } },
  { path: '/m/mine', name: 'MobileMine', component: () => import('@/views/mobile/MobileMine.vue'), meta: { title: '我的', public: true } },
  { path: '/m/list', name: 'MobileList', component: () => import('@/views/mobile/MobileList.vue'), meta: { title: '台账记录', public: true } },
  { path: '/m/report', name: 'MobileReport', component: () => import('@/views/mobile/MobileReport.vue'), meta: { title: '上报', public: true } },
  { path: '/m/meeting', name: 'MobileMeeting', component: () => import('@/views/mobile/MobileMeeting.vue'), meta: { title: '会议签到', public: true } },
  { path: '/m/checkin', name: 'MobileCheckin', component: () => import('@/views/mobile/MobileCheckin.vue'), meta: { title: '统一签到', public: true } },
  { path: '/m/sign', name: 'MobileSign', component: () => import('@/views/mobile/MobileSign.vue'), meta: { title: '文件签署', public: true } },
  { path: '/m/notice', name: 'MobileNotice', component: () => import('@/views/mobile/MobileNotice.vue'), meta: { title: '消息通知', public: true } },
  { path: '/m/profile', name: 'MobileProfile', component: () => import('@/views/mobile/MobileProfile.vue'), meta: { title: '个人档案', public: true } },
  // E2 企业侧三步接入填报（独立 H5，不套小程序外壳）
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

router.beforeEach(async (to, from, next) => {
  // 移动端允许 URL 携带 token（由主站登录态透传），便于司机扫码直接进入
  if (to.path.startsWith('/m/') && to.query.token) {
    localStorage.setItem('token', String(to.query.token))
    if (to.query.userType) localStorage.setItem('userType', String(to.query.userType))
  }
  const token = localStorage.getItem('token')
  const userType = localStorage.getItem('userType')

  // AUTO_LOGIN：登录页直接进系统（已登录去驾驶舱；未登录静默自动登录）
  if (AUTO_LOGIN && to.path === '/login') {
    if (token) return next({ path: '/dashboard' })
    try {
      const auth = useAuthStore()
      await auth.devLogin({ username: DEV_DEMO_USER, password: DEV_DEMO_PWD })
      return next({ path: '/dashboard' })
    } catch (e) {
      return next()
    }
  }

  // AUTO_LOGIN：未登录访问受保护页，静默用演示账号登录（后续关开关即恢复）
  if (AUTO_LOGIN && !token && !to.meta.public) {
    try {
      const auth = useAuthStore()
      await auth.devLogin({ username: DEV_DEMO_USER, password: DEV_DEMO_PWD })
      return next()
    } catch (e) {
      return next({ path: '/login' })
    }
  }

  if (to.meta.public) { next(); return }
  if (!token) { next({ path: '/login', query: { redirect: to.fullPath } }); return }
  if (to.meta.roles && !to.meta.roles.includes(userType)) { next('/dashboard'); return }
  next()
})

export default router
