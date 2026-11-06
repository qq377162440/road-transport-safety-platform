<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/api/request'

/**
 * E2 企业侧三步接入填报（适老化 H5，对齐《详细设计 04》）。
 * 三步：① 企业基础资料 → ② 人档 + 车档 → ③ 安全管理现状 → 提交完成接入。
 * 未完成前业务功能锁定，仅显示本引导页；完成后跳转电子档案柜。
 */
const route = useRoute()
const step = ref(1)
const status = ref(null)
const msg = ref('')
const msgType = ref('info')
const submitting = ref(false)
const finished = ref(false)

const steps = ['企业资料', '人车档案', '安全现状']
const progressText = computed(() => `第 ${step.value} 步 / 共 3 步`)

const company = ref({
  legal_person: '', safety_charge: '', charge_phone: '', region: '', address: '',
  biz_term_type: 1, employee_count: null, vehicle_scale: null,
})
const persons = ref([{ name: '', telephone: '', person_type: 'employee' }])
const vehicles = ref([{ plate_no: '', plate_color: '黄', vehicle_type: '', biz_type: 'freight' }])
const diag = ref({
  has_institution: 'yes', has_emergency_plan: 'no',
  has_training_record: 'no', archive_complete: 'no', note: '',
})

function notify(text, type = 'info') {
  msg.value = text
  msgType.value = type
}

onMounted(async () => {
  const t = route.query.token
  if (t) localStorage.setItem('token', String(t))
  try {
    const res = await request.get('/onboarding/status')
    status.value = res.data
    if (res.data.step >= 3) step.value = 3
    else if (res.data.step >= 2) step.value = 2
  } catch (e) {
    notify('请先登录企业主账号（初始密码 888888）', 'warn')
  }
})

function addPerson() { persons.value.push({ name: '', telephone: '', person_type: 'employee' }) }
function addVehicle() { vehicles.value.push({ plate_no: '', plate_color: '黄', vehicle_type: '', biz_type: 'freight' }) }

async function submitStep1() {
  if (!company.value.safety_charge || !company.value.charge_phone) {
    notify('请填写安全主要负责人与电话', 'warn'); return
  }
  submitting.value = true
  try {
    const res = await request.post('/onboarding/step1', {
      ...company.value, license_file: 'uploaded',
    })
    status.value = res.data
    step.value = 2
    notify('第一步完成，请填写人车档案', 'ok')
  } catch (e) { notify(e.message || '提交失败', 'warn') }
  finally { submitting.value = false }
}

async function submitStep2() {
  const ps = persons.value.filter((p) => p.name && p.telephone)
  const vs = vehicles.value.filter((v) => v.plate_no)
  if (!ps.length) { notify('请至少填写一名人员（姓名 + 手机号）', 'warn'); return }
  submitting.value = true
  try {
    await request.post('/onboarding/step2/person', { persons: ps })
    if (vs.length) await request.post('/onboarding/step2/vehicle', { vehicles: vs })
    const res = await request.get('/onboarding/status')
    status.value = res.data
    step.value = 3
    notify(`已录入 ${ps.length} 名人员、${vs.length} 台车辆，手机号已自动开通小程序账号`, 'ok')
  } catch (e) { notify(e.message || '提交失败', 'warn') }
  finally { submitting.value = false }
}

async function submitStep3() {
  submitting.value = true
  try {
    await request.post('/onboarding/step3', { diagnosis: diag.value })
    await request.post('/onboarding/submit', {})
    finished.value = true
    notify('接入完成！现在可以使用全部业务功能了', 'ok')
  } catch (e) { notify(e.message || '提交失败', 'warn') }
  finally { submitting.value = false }
}

function goArchive() {
  window.location.href = '/archive-overview'
}
</script>

<template>
  <div class="onboard">
    <header class="top">
      <div class="brand">企业接入登记</div>
      <div class="prog">{{ progressText }}</div>
    </header>

    <div class="bar">
      <div v-for="(s, i) in steps" :key="s" class="bar-item" :class="{ on: step >= i + 1 }">
        <span class="num">{{ i + 1 }}</span><span class="lbl">{{ s }}</span>
      </div>
    </div>

    <div v-if="msg" class="msg" :class="msgType">{{ msg }}</div>

    <!-- 第一步：企业基础资料 -->
    <section v-if="step === 1 && !finished">
      <h1>第 1 步 · 企业资料</h1>
      <p class="hint">只填必填项，其他资料后续在电脑端补齐。</p>
      <label>法定代表人<input v-model="company.legal_person" placeholder="姓名" /></label>
      <label>安全主要负责人 <em>*</em><input v-model="company.safety_charge" placeholder="姓名" /></label>
      <label>负责人电话 <em>*</em><input v-model="company.charge_phone" inputmode="tel" placeholder="手机号" /></label>
      <label>所在地区<input v-model="company.region" placeholder="如 佛山市高明区" /></label>
      <label>实际经营地址<input v-model="company.address" placeholder="街道门牌" /></label>
      <label>从业人员数量<input v-model.number="company.employee_count" inputmode="numeric" placeholder="选填" /></label>
      <label>车辆规模<input v-model.number="company.vehicle_scale" inputmode="numeric" placeholder="选填" /></label>
      <button class="primary" :disabled="submitting" @click="submitStep1">下一步：填人车档案 →</button>
    </section>

    <!-- 第二步：人车档案 -->
    <section v-if="step === 2 && !finished">
      <h1>第 2 步 · 人车档案</h1>
      <p class="hint">填了手机号，系统会自动给这个人开通小程序账号。</p>
      <div class="group-title">人员（可添加多名）</div>
      <div v-for="(p, i) in persons" :key="i" class="row-card">
        <input v-model="p.name" placeholder="姓名" />
        <input v-model="p.telephone" inputmode="tel" placeholder="手机号" />
        <select v-model="p.person_type">
          <option value="manager">管理人员</option>
          <option value="employee">普通员工</option>
          <option value="driver">驾驶员</option>
        </select>
      </div>
      <button class="ghost" @click="addPerson">＋ 再加一名人员</button>

      <div class="group-title">车辆（可跳过）</div>
      <div v-for="(v, i) in vehicles" :key="i" class="row-card">
        <input v-model="v.plate_no" placeholder="车牌号" />
        <input v-model="v.vehicle_type" placeholder="车辆类型（选填）" />
      </div>
      <button class="ghost" @click="addVehicle">＋ 再加一台车</button>
      <button class="primary" :disabled="submitting" @click="submitStep2">下一步：安全现状 →</button>
    </section>

    <!-- 第三步：现状诊断 -->
    <section v-if="step === 3 && !finished">
      <h1>第 3 步 · 安全管理现状</h1>
      <p class="hint">几个选择题，帮服务商判断需要整改哪些资料。</p>
      <div class="choice">
        <span>是否已有安全生产制度？</span>
        <div class="opts">
          <button :class="{ sel: diag.has_institution === 'yes' }" @click="diag.has_institution = 'yes'">有</button>
          <button :class="{ sel: diag.has_institution === 'no' }" @click="diag.has_institution = 'no'">没有</button>
        </div>
      </div>
      <div class="choice">
        <span>是否已有应急预案？</span>
        <div class="opts">
          <button :class="{ sel: diag.has_emergency_plan === 'yes' }" @click="diag.has_emergency_plan = 'yes'">有</button>
          <button :class="{ sel: diag.has_emergency_plan === 'no' }" @click="diag.has_emergency_plan = 'no'">没有</button>
        </div>
      </div>
      <div class="choice">
        <span>是否已有培训记录？</span>
        <div class="opts">
          <button :class="{ sel: diag.has_training_record === 'yes' }" @click="diag.has_training_record = 'yes'">有</button>
          <button :class="{ sel: diag.has_training_record === 'no' }" @click="diag.has_training_record = 'no'">没有</button>
        </div>
      </div>
      <div class="choice">
        <span>纸质台账是否齐全？</span>
        <div class="opts">
          <button :class="{ sel: diag.archive_complete === 'yes' }" @click="diag.archive_complete = 'yes'">齐全</button>
          <button :class="{ sel: diag.archive_complete === 'no' }" @click="diag.archive_complete = 'no'">不齐全</button>
        </div>
      </div>
      <label>补充说明<textarea v-model="diag.note" rows="3" placeholder="选填，例如：去年资料找不到了" /></label>
      <button class="primary" :disabled="submitting" @click="submitStep3">提交，完成接入</button>
    </section>

    <section v-if="finished" class="finish">
      <div class="big-ok">✓</div>
      <h1>接入完成</h1>
      <p class="hint">服务商会按诊断结果帮你整改资料、补齐台账归档。</p>
      <button class="primary" @click="goArchive">去看电子档案柜 →</button>
    </section>

    <footer class="foot">道路运输安全生产管理平台 · 接入引导</footer>
  </div>
</template>

<style scoped>
.onboard { max-width: 560px; margin: 0 auto; padding: 16px; font-size: 19px; color: #0f172a; }
.top { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 12px; }
.brand { font-size: 24px; font-weight: 800; color: #0f1c3f; }
.prog { font-size: 17px; color: #475569; }
.bar { display: flex; gap: 8px; margin-bottom: 20px; }
.bar-item { flex: 1; text-align: center; padding: 10px 4px; border-radius: 12px; background: #e2e8f0; color: #64748b; }
.bar-item.on { background: #1d4ed8; color: #fff; }
.num { display: inline-block; width: 28px; height: 28px; line-height: 28px; border-radius: 50%; background: rgba(255,255,255,.25); margin-right: 6px; font-weight: 800; }
.lbl { font-weight: 700; }
.msg { padding: 12px 14px; border-radius: 10px; margin-bottom: 16px; background: #e0f2fe; }
.msg.ok { background: #dcfce7; color: #166534; }
.msg.warn { background: #fef3c7; color: #92400e; }
h1 { font-size: 26px; margin: 8px 0; }
.hint { font-size: 17px; color: #475569; margin: 0 0 18px; line-height: 1.6; }
label { display: block; font-weight: 700; margin-bottom: 14px; }
label em { color: #dc2626; font-style: normal; }
input, select, textarea { display: block; width: 100%; box-sizing: border-box; margin-top: 7px;
  font-size: 20px; padding: 12px 14px; min-height: 54px; border: 2px solid #cbd5e1;
  border-radius: 10px; background: #fff; color: #0f172a; }
textarea { min-height: 90px; }
.group-title { font-weight: 800; font-size: 20px; margin: 20px 0 10px; }
.row-card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; padding: 12px; margin-bottom: 10px; }
.row-card input, .row-card select { margin-top: 0; margin-bottom: 8px; }
button { font-size: 20px; font-weight: 800; min-height: 60px; border-radius: 12px; width: 100%;
  border: 2px solid transparent; cursor: pointer; margin-bottom: 12px; }
button.primary { background: #1d4ed8; color: #fff; }
button.primary:disabled { opacity: .6; }
button.ghost { background: #fff; color: #1d4ed8; border-color: #1d4ed8; }
.choice { margin-bottom: 16px; font-weight: 700; }
.opts { display: flex; gap: 10px; margin-top: 8px; }
.opts button { flex: 1; min-height: 56px; background: #fff; color: #0f172a; border: 2px solid #cbd5e1; }
.opts button.sel { background: #1d4ed8; color: #fff; border-color: #1d4ed8; }
.finish { text-align: center; padding: 40px 0; }
.big-ok { font-size: 72px; color: #16a34a; font-weight: 800; }
.foot { text-align: center; color: #94a3b8; font-size: 14px; margin-top: 28px; }
</style>
