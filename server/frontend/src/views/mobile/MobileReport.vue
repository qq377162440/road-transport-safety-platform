<script setup>
/**
 * 隐患上报 / 维修上报 · 按需求文档 2.1「少打字、选择代替输入、二次确认」。
 * 提交写回 PC 端 danger_record / vehicle_repair，PC 台账与档案柜立即可见。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/api/request'
import MobileShell from '@/components/MobileShell.vue'
import { mini, ensureMini, goMini, learnerName } from '@/store/mini'

const route = useRoute()
const router = useRouter()
const type = computed(() => String(route.query.type || 'danger') === 'repair' ? 'repair' : 'danger')
const meta = computed(() => type.value === 'repair'
  ? { title: '报修车', desc: '维修上报会进入 PC 端「维修保养管理」台账', path: '/mini/repair/report', list: '/m/list', q: { type: 'repairs' } }
  : { title: '报隐患', desc: '隐患上报会进入 PC 端「隐患排查治理」台账', path: '/mini/danger/report', list: '/m/list', q: { type: 'dangers' } })

const form = ref({ title: '', location: '', level: '一般', faultDesc: '', repairType: '报修', mileage: '', remark: '', photos: [] })
const vehicle = ref(null)
const submitting = ref(false)
const confirmShow = ref(false)
const view = ref('form')
const err = ref('')
const resultText = ref('')

onMounted(async () => {
  await ensureMini(route)
  try {
    const res = await request.get('/mini/mine', { params: { personId: mini.personId } })
    vehicle.value = res.data?.vehicle || null
  } catch (e) { /* 车辆可选 */ }
})

function onPhoto(e) {
  const f = e.target.files?.[0]
  if (!f) return
  const reader = new FileReader()
  reader.onload = () => form.value.photos.push(String(reader.result).slice(0, 200000))
  reader.readAsDataURL(f)
}
function ask() {
  err.value = ''
  if (type.value === 'danger' && !form.value.title.trim()) { err.value = '请用一句话写下隐患情况，例如「左后轮胎纹磨平了」'; return }
  if (type.value === 'repair' && !form.value.faultDesc.trim()) { err.value = '请用一句话写下车辆毛病，例如「空调不制冷」'; return }
  confirmShow.value = true
}
async function submit() {
  confirmShow.value = false
  submitting.value = true
  try {
    const b = {
      personId: mini.personId,
      vehicleId: vehicle.value?.id || 0,
      plateNo: vehicle.value?.plate_no || '',
      personName: learnerName(),
      photos: form.value.photos.length ? JSON.stringify(form.value.photos) : '',
    }
    if (type.value === 'danger') {
      Object.assign(b, { title: form.value.title.trim(), location: form.value.location.trim(), level: form.value.level })
    } else {
      Object.assign(b, {
        faultDesc: form.value.faultDesc.trim(), repairType: form.value.repairType,
        mileage: Number(form.value.mileage || 0), remark: form.value.remark.trim(),
      })
    }
    const res = await request.post(meta.value.path, b)
    resultText.value = (res.data?.title || res.data?.faultDesc || '已上报') + '，记录已同步到 PC 端台账'
    view.value = 'result'
  } catch (e) {
    resultText.value = '提交失败：' + (e.message || '请检查网络')
    view.value = 'result'
  } finally { submitting.value = false }
}
</script>

<template>
  <MobileShell :title="meta.title" tab="home" back>
    <template v-if="view === 'result'">
      <div class="m-card m-result pass">
        <div class="m-r-ico">✓</div>
        <div class="m-r-title">已 上 报</div>
        <div class="m-r-desc">{{ resultText }}</div>
      </div>
      <button class="m-btn m-btn-primary" @click="goMini(router, meta.list, meta.q)">查看台账记录</button>
      <button class="m-btn m-btn-outline" @click="goMini(router, '/m/home')">返回首页</button>
    </template>

    <template v-else>
      <div class="m-tip" style="margin-top:0">{{ meta.desc }}</div>
      <div v-if="err" class="m-err">{{ err }}</div>

      <div class="m-card">
        <div style="font-size:calc(17px * var(--m-scale));font-weight:700">
          当前车辆：{{ vehicle?.plate_no || '未绑定车辆' }}
        </div>
        <div style="font-size:calc(14px * var(--m-scale));color:#64748B;margin-top:4px">
          上报人：{{ learnerName() }} · {{ vehicle?.vehicle_type || '' }}
        </div>
      </div>

      <template v-if="type === 'danger'">
        <label class="m-label">哪里有问题？（一句话就行）</label>
        <textarea v-model="form.title" class="m-textarea" placeholder="例如：停车场 B 区消防通道被杂物堵住了" />
        <label class="m-label">在哪个位置？</label>
        <input v-model="form.location" class="m-input" placeholder="例如：停车场 B 区 / 粤E·12345" />
        <label class="m-label">严重程度</label>
        <div class="m-chips">
          <button v-for="l in ['一般', '较大', '重大']" :key="l" class="m-chip" :class="{ on: form.level === l }" @click="form.level = l">{{ l }}</button>
        </div>
      </template>

      <template v-else>
        <label class="m-label">车辆什么毛病？（一句话就行）</label>
        <textarea v-model="form.faultDesc" class="m-textarea" placeholder="例如：右后刹车片有异响，需要检查" />
        <label class="m-label">报修类型</label>
        <div class="m-chips">
          <button v-for="t in ['报修', '日常保养', '部件更换']" :key="t" class="m-chip" :class="{ on: form.repairType === t }" @click="form.repairType = t">{{ t }}</button>
        </div>
        <label class="m-label">当前里程（可不填）</label>
        <input v-model="form.mileage" class="m-input" inputmode="numeric" placeholder="例如：128500" />
        <label class="m-label">补充说明（可不填）</label>
        <input v-model="form.remark" class="m-input" placeholder="例如：希望本周内安排" />
      </template>

      <label class="m-label">拍照（可选，建议拍一张）</label>
      <input type="file" accept="image/*" class="m-input" style="padding:10px" @change="onPhoto" />
      <div v-if="form.photos.length" style="font-size:calc(14px * var(--m-scale));color:#15803D;margin-top:6px">已附加 {{ form.photos.length }} 张照片</div>

      <button class="m-btn m-btn-primary" style="min-height:62px;font-size:calc(20px * var(--m-scale))"
              :disabled="submitting" @click="ask">{{ submitting ? '正在提交…' : '确 认 上 报' }}</button>
      <div class="m-foot">提交前会再确认一次，避免点错</div>
    </template>

    <div v-if="confirmShow" style="position:fixed;inset:0;background:rgba(15,23,42,.55);display:flex;align-items:center;justify-content:center;z-index:50;padding:20px">
      <div style="background:#fff;border-radius:18px;padding:24px;width:100%;max-width:340px;text-align:center">
        <div style="font-size:calc(22px * var(--m-scale));font-weight:800;margin-bottom:12px">确认上报？</div>
        <div style="font-size:calc(16px * var(--m-scale));color:#475569;line-height:1.7">
          {{ type === 'danger' ? form.title : form.faultDesc }}<br />上报后安全员会在 PC 端看到并处理
        </div>
        <button class="m-btn m-btn-primary" @click="submit">✓ 确认</button>
        <button class="m-btn m-btn-gray" @click="confirmShow = false">✗ 返回修改</button>
      </div>
    </div>
  </MobileShell>
</template>
