<script setup>
/** 个人档案：与 PC 端「人员基础信息 / 一人一档 / 一车一档」同源 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/api/request'
import MobileShell from '@/components/MobileShell.vue'
import { mini, ensureMini, goMini, enterpriseName } from '@/store/mini'

const route = useRoute()
const router = useRouter()
const data = ref(null)
const err = ref('')

async function load() {
  try {
    const res = await request.get('/mini/mine', { params: { personId: mini.personId } })
    data.value = res.data || {}
  } catch (e) { err.value = e.message || '加载失败' }
}
onMounted(async () => { await ensureMini(route); await load() })

const person = computed(() => data.value?.person || {})
const vehicle = computed(() => data.value?.vehicle)
const docs = computed(() => data.value?.docs || [])
const rows = computed(() => [
  { k: '姓名', v: person.value?.name || '—' },
  { k: '手机号', v: person.value?.telephone || '—' },
  { k: '人员类型', v: person.value?.person_type === 'employee' ? '从业人员' : (person.value?.person_type || '—') },
  { k: '所属企业', v: enterpriseName() },
  { k: '绑定车辆', v: vehicle.value ? (vehicle.value.plate_no + ' · ' + (vehicle.value.vehicle_type || '')) : '未绑定' },
  { k: '岗位', v: person.value?.post_ids || '驾驶员' },
])
</script>

<template>
  <MobileShell title="个人档案" tab="mine" back>
    <div class="m-tip" style="margin-top:0">档案与 PC 端「人员基础信息 / 一人一档」同源，安全员在 PC 端修改后这里同步更新。</div>
    <div v-if="err" class="m-err">{{ err }}</div>

    <div class="m-card-title">基本信息</div>
    <div class="m-card" style="padding:0;overflow:hidden">
      <div v-for="r in rows" :key="r.k" style="display:flex;justify-content:space-between;gap:12px;padding:14px 16px;border-bottom:1px solid #E2E8F0">
        <span style="font-size:calc(16px * var(--m-scale));color:#64748B;flex-shrink:0">{{ r.k }}</span>
        <span style="font-size:calc(16px * var(--m-scale));font-weight:700;text-align:right">{{ r.v }}</span>
      </div>
    </div>

    <div class="m-card-title">我的证件（{{ docs.length }}）</div>
    <div class="m-card" style="padding:8px">
      <div v-for="d in docs" :key="d.owner_type + '-' + d.id" class="m-task" style="margin-bottom:8px;box-shadow:none;cursor:default">
        <div class="m-t-ico" :class="d.warning ? 'amber' : 'green'">{{ d.warning ? '!' : '✓' }}</div>
        <div class="m-t-info">
          <div class="m-t-title">{{ d.doc_name }}</div>
          <div class="m-t-desc">
            {{ d.owner_type === 'vehicle' ? '车辆证件' : '个人证件' }} · {{ String(d.expire_date || '').slice(0, 10) }} 到期
          </div>
        </div>
        <span class="m-tag" :class="d.warning ? 'm-tag-yellow' : 'm-tag-green'">
          {{ d.daysLeft >= 0 ? d.daysLeft + ' 天' : '已过期' }}
        </span>
      </div>
      <div v-if="!docs.length" class="m-empty" style="padding:16px">暂无证件记录</div>
    </div>

    <button class="m-btn m-btn-outline" @click="goMini(router, '/m/mine')">返回我的</button>
  </MobileShell>
</template>
