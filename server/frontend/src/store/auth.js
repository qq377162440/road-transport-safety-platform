import { defineStore } from 'pinia'
import request from '@/api/request'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    userType: localStorage.getItem('userType') || '',
    username: localStorage.getItem('username') || '',
    tenantName: localStorage.getItem('tenantName') || '',
    // 服务商 / 超管「当前服务企业」：范本定制与生成为增值服务，需指定归档到哪家企业
    targetTenantId: localStorage.getItem('targetTenantId') || '',
    targetTenantName: localStorage.getItem('targetTenantName') || '',
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    isPlatform: (state) => state.userType === 'provider' || state.userType === 'admin',
    // 范本定制与生成能力：服务商 / 超管专属（企业只读）
    canGenerateTpl: (state) => state.userType === 'provider' || state.userType === 'admin',
    roleLabel: (state) => {
      const map = { enterprise: '企业', provider: '服务商', admin: '超管' }
      return map[state.userType] || state.userType
    },
  },

  actions: {
    // 获取验证码
    async getCaptcha() {
      const res = await request.get('/auth/captcha')
      return res.data
    },

    // 登录
    async login({ username, password, captchaId, captcha }) {
      const res = await request.post('/auth/login', {
        username,
        password,
        captchaId,
        captcha,
      })
      const data = res.data
      // LoginResp 结构：{ accessToken, refreshToken, userInfo: { username, userType, ... } }
      const info = data.userInfo || data
      this.token = data.accessToken
      this.userType = info.userType
      this.username = info.username
      localStorage.setItem('token', data.accessToken)
      localStorage.setItem('userType', info.userType || '')
      localStorage.setItem('username', info.username || '')
      return data
    },

    // 开发免验证码登录（AUTO_LOGIN 模式）：跳过图形验证码，直接用演示账号进入系统
    async devLogin({ username, password }) {
      const res = await request.post('/auth/dev-login', { username, password })
      const data = res.data
      const info = data.userInfo || data
      this.token = data.accessToken
      this.userType = info.userType
      this.username = info.username
      localStorage.setItem('token', data.accessToken)
      localStorage.setItem('userType', info.userType || '')
      localStorage.setItem('username', info.username || '')
      return data
    },

    // 加载企业名称（进入主框架后）
    async loadTenantName() {
      try {
        const res = await request.get('/tenant/name')
        this.tenantName = res.data
        localStorage.setItem('tenantName', res.data)
      } catch (e) {
        // 平台账号可能无企业名，忽略
      }
    },

    // 登出
    async logout() {
      try {
        await request.post('/auth/logout')
      } catch (e) {
        // 忽略登出接口错误
      }
      this.token = ''
      this.userType = ''
      this.username = ''
      this.tenantName = ''
      localStorage.removeItem('token')
      localStorage.removeItem('userType')
      localStorage.removeItem('username')
      localStorage.removeItem('tenantName')
      this.clearTargetTenant()
    },

    // 「当前服务企业」：仅服务商 / 超管使用，用于把范本生成结果落到指定企业
    setTargetTenant({ id, name }) {
      this.targetTenantId = id ? String(id) : ''
      this.targetTenantName = name || ''
      if (id) {
        localStorage.setItem('targetTenantId', String(id))
        localStorage.setItem('targetTenantName', name || '')
      } else {
        this.clearTargetTenant()
      }
    },
    clearTargetTenant() {
      this.targetTenantId = ''
      this.targetTenantName = ''
      localStorage.removeItem('targetTenantId')
      localStorage.removeItem('targetTenantName')
    },
  },
})
