<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { ElMessage } from 'element-plus'
import Icon from '@/components/Icon.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const form = ref({
  username: '',
  password: '',
  captchaId: '',
  captcha: '',
})
const captchaImg = ref('')
const loading = ref(false)
const errorMsg = ref('')

// 获取验证码
async function refreshCaptcha() {
  try {
    const data = await auth.getCaptcha()
    form.value.captchaId = data.captchaId
    // 后端返回 base64Image（纯 base64），需加 data URI 前缀
    captchaImg.value = data.base64Image
      ? `data:image/png;base64,${data.base64Image}`
      : ''
  } catch (e) {
    // 已由拦截器提示
  }
}

async function handleLogin() {
  errorMsg.value = ''
  if (!form.value.username || !form.value.password) {
    errorMsg.value = '请输入账号和密码'
    return
  }
  if (!form.value.captcha) {
    errorMsg.value = '请输入验证码'
    return
  }
  loading.value = true
  try {
    await auth.login(form.value)
    ElMessage.success('登录成功')
    await auth.loadTenantName()
    const redirect = route.query.redirect || '/dashboard'
    router.push(redirect)
  } catch (e) {
    errorMsg.value = e.message || '登录失败'
    refreshCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  refreshCaptcha()
})
</script>

<template>
  <div class="login-page">
    <div class="deco d1"></div>
    <div class="deco d2"></div>
    <div class="deco d3"></div>

    <div class="login-card">
      <div class="login-head">
        <div class="logo"><Icon name="truck" :size="28" /></div>
        <h1>道路运输安全生产管理平台</h1>
        <div class="sub">安全生产 · 台账合规 · 服务商一体化运营</div>
      </div>

      <div class="login-body">
        <div class="fld">
          <label>账号</label>
          <el-input v-model="form.username" placeholder="请输入登录账号" size="large" />
        </div>
        <div class="fld">
          <label>密码</label>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            show-password
            @keyup.enter="handleLogin"
          />
        </div>
        <div class="fld">
          <label>验证码</label>
          <div class="captcha-row">
            <el-input
              v-model="form.captcha"
              placeholder="验证码"
              size="large"
              @keyup.enter="handleLogin"
            />
            <img
              v-if="captchaImg"
              :src="captchaImg"
              class="captcha-img"
              alt="验证码"
              title="点击刷新"
              @click="refreshCaptcha"
            />
          </div>
        </div>

        <div class="err">{{ errorMsg }}</div>
        <el-button
          type="primary"
          size="large"
          class="login-btn"
          :loading="loading"
          @click="handleLogin"
        >
          登 录
        </el-button>
      </div>

      <div class="login-foot">
        演示账号：企业 aa_gaozhou · 服务商 bb_provider · 超管 cc_admin（密码 123456）
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0f1c3f 0%, #1e3a8a 55%, #2563eb 100%);
  position: relative;
  overflow: hidden;
}
.deco {
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.05);
}
.d1 {
  width: 420px;
  height: 420px;
  top: -120px;
  left: -120px;
}
.d2 {
  width: 300px;
  height: 300px;
  bottom: -80px;
  right: -60px;
}
.d3 {
  width: 160px;
  height: 160px;
  top: 40%;
  right: 16%;
  background: rgba(255, 255, 255, 0.04);
}
.login-card {
  position: relative;
  z-index: 2;
  width: min(440px, 92vw);
  background: #fff;
  border-radius: 20px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.35);
  overflow: hidden;
}
.login-head {
  padding: 34px 36px 22px;
  text-align: center;
}
.login-head .logo {
  width: 54px;
  height: 54px;
  border-radius: 14px;
  background: var(--primary);
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 14px;
  font-size: 28px;
}
.login-head h1 {
  font-size: 21px;
  font-weight: 700;
}
.login-head .sub {
  font-size: 13px;
  color: var(--text-3);
  margin-top: 6px;
}
.login-body {
  padding: 0 36px 8px;
}
.fld {
  margin-bottom: 18px;
}
.fld label {
  display: block;
  font-size: 13px;
  color: var(--text-2);
  font-weight: 600;
  margin-bottom: 7px;
}
.captcha-row {
  display: flex;
  gap: 10px;
  align-items: center;
}
.captcha-img {
  height: 40px;
  width: 110px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid var(--border-strong);
  flex-shrink: 0;
}
.err {
  color: var(--danger-ink);
  font-size: 13px;
  margin-bottom: 12px;
  min-height: 18px;
}
.login-btn {
  width: 100%;
  height: 48px;
  font-size: 16px;
  font-weight: 700;
}
.login-foot {
  padding: 22px 36px 26px;
  text-align: center;
  font-size: 12.5px;
  color: var(--text-3);
}
</style>
