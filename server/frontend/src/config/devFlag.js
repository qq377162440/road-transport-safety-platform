// 测试中标记开关：模块逐项对齐阶段置 true；全部验收通过后改 false 一处，全局角标即清除。
export const DEV_MODE = true
export const DEV_LABEL = '测试中 DEV'

// AUTO_LOGIN：开发测试阶段跳过登录界面，用演示账号自动登录，提升逐项测试效率。
// 后续单独打磨登录页面时，把此处改为 false，登录界面即恢复。
export const AUTO_LOGIN = true
export const DEV_DEMO_USER = 'aa_gaozhou'
export const DEV_DEMO_PWD = '123456'
// 各角色演示账号（角色切换即切换账号；AUTO_LOGIN 默认走企业账号）
export const DEV_DEMO_ACCOUNTS = {
  enterprise: { username: 'aa_gaozhou', password: '123456' },
  provider: { username: 'bb_provider', password: '123456' },
  admin: { username: 'cc_admin', password: '123456' },
}
