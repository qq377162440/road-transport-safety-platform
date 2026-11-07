/**
 * 小程序端共享上下文（模块级单例，供 MobileShell 与各 /m 页面共用）。
 *
 * 身份：测试阶段支持 URL 指定 personId / role（对齐原型 role-switch，
 * 便于逐人逐角色走查）；正式上线时由登录态自动判定，去掉 URL 参数即可。
 * 所有 /m 页面跳转都带 personId / role / token，保证刷新后身份不丢。
 */
import { reactive } from 'vue'
import request from '@/api/request'
import { AUTO_LOGIN, DEV_DEMO_USER, DEV_DEMO_PWD } from '@/config/devFlag'
import { useAuthStore } from '@/store/auth'

export const mini = reactive({
  ready: false,
  personId: '',
  role: 'worker',          // worker 司机 | manager 管理
  bootstrap: {},
  scale: Number(localStorage.getItem('miniScale') || 1),
  pop: '',                 // '' | 'person' | 'role'
})

export async function ensureLogin() {
  if (localStorage.getItem('token')) return
  if (AUTO_LOGIN) {
    try {
      await useAuthStore().devLogin({ username: DEV_DEMO_USER, password: DEV_DEMO_PWD })
    } catch (e) { /* 静默，由接口 401 提示 */ }
  }
}

export async function initMini(route) {
  await ensureLogin()
  const q = route.query
  if (q.personId) mini.personId = String(q.personId)
  if (q.role) mini.role = String(q.role)
  try {
    const res = await request.get('/mini/bootstrap', {
      params: { personId: mini.personId || 0, role: mini.role },
    })
    mini.bootstrap = res.data || {}
    mini.personId = String(mini.bootstrap.personId || mini.personId || '')
    mini.role = String(mini.bootstrap.role || mini.role)
  } catch (e) { /* 保留已有身份，页面各自提示 */ }
  mini.ready = true
}

/** 幂等初始化：MobileShell 与各页面都会调用，实际只发一次 bootstrap 请求 */
let initPromise = null
export function ensureMini(route) {
  if (!initPromise) initPromise = initMini(route)
  return initPromise
}

/** 页面跳转统一携带身份与 token（token 供 router 透传，刷新不掉登录态） */
export function miniQuery(extra = {}) {
  const q = { personId: mini.personId, role: mini.role, ...extra }
  const t = localStorage.getItem('token')
  if (t) q.token = t
  return q
}

export function goMini(router, path, extra = {}) {
  router.push({ path, query: miniQuery(extra) }).catch(() => {})
}

export function setScale(v) {
  mini.scale = v
  localStorage.setItem('miniScale', String(v))
}

function reloadWithContext(router) {
  // 整页刷新让所有页面重新按新身份拉数据；token 经 URL 透传，刷新不掉登录态
  const q = new URLSearchParams(miniQuery()).toString()
  window.location.href = router.currentRoute.value.path + '?' + q
}

export function switchRole(router, role) {
  mini.role = role
  mini.pop = ''
  reloadWithContext(router)
}

export function switchPerson(router, id) {
  mini.personId = String(id)
  mini.pop = ''
  reloadWithContext(router)
}

export const learnerName = () => mini.bootstrap?.person?.name || '从业人员'
export const enterpriseName = () => mini.bootstrap?.enterprise || ''
