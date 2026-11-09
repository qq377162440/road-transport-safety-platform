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
const archiveData = ref(null)
const vehicleArchive = ref(null)

async function load() {
  try {
    const res = await request.get('/mini/mine', { params: { personId: mini.personId } })
    data.value = res.data || {}
    // 一人一档完整度与缺项：与 PC 端 B1 人员基础信息同源（/api/flow/person/{id}/archive）
    const pid = data.value?.person?.id || mini.personId || 0
    try { archiveData.value = (await request.get(`/flow/person/${pid}/archive`)).data || null }
    catch { archiveData.value = null }
    // 我绑定车辆的一车一档完整度：与 PC 端 B2 车辆基础信息同源（/api/flow/vehicle/mine）
    try { vehicleArchive.value = (await request.get('/flow/vehicle/mine', { params: { personId: pid } })).data || null }
    catch { vehicleArchive.value = null }
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

    <!-- 一人一档归档完整度（PC 端 B1 同源） -->
    <div v-if="archiveData?.ok" class="m-card" style="border:2px solid #1E40AF">
      <div style="display:flex;align-items:center;gap:14px">
        <div style="font-size:calc(36px * var(--m-scale));font-weight:800;color:#1D4ED8;line-height:1">{{ archiveData.percent }}<span style="font-size:calc(17px * var(--m-scale))">%</span></div>
        <div style="flex:1;min-width:0">
          <div style="font-size:calc(17px * var(--m-scale));font-weight:700">一人一档归档完整度</div>
          <div style="font-size:calc(13px * var(--m-scale));color:#64748B;margin-top:4px">
            已齐 {{ archiveData.done }}/{{ archiveData.required }} 项 · 缺 {{ (archiveData.missing || []).length }} 项
          </div>
          <div style="height:8px;background:#E2E8F0;border-radius:6px;margin-top:8px;overflow:hidden">
            <div :style="{ width: archiveData.percent + '%', height: '100%', background: archiveData.percent >= 100 ? '#16A34A' : '#3B82F6' }"></div>
          </div>
        </div>
      </div>
      <div v-if="(archiveData.missing || []).length" style="margin-top:12px;padding-top:12px;border-top:1px solid #E2E8F0">
        <div style="font-size:calc(14px * var(--m-scale));font-weight:700;margin-bottom:6px">待补齐归档项</div>
        <div v-for="m in archiveData.missing.slice(0, 6)" :key="m.itemCode"
             style="display:flex;gap:8px;align-items:center;padding:5px 0;font-size:calc(14px * var(--m-scale))">
          <span style="color:#DC2626">●</span><span style="flex:1">{{ m.itemName }}</span>
          <span style="color:#64748B;font-size:calc(12px * var(--m-scale))">{{ m.expired ? '已过期' : '待补' }}</span>
        </div>
        <div v-if="archiveData.missing.length > 6" style="font-size:calc(13px * var(--m-scale));color:#64748B">另有 {{ archiveData.missing.length - 6 }} 项…</div>
        <div class="m-tip" style="margin-top:8px">请安全员在 PC 端「人员基础信息 → 一人一档」上传证件或生成范本，完成后这里实时更新。</div>
      </div>
    </div>

    <!-- 我绑定车辆的一车一档完整度（PC 端 B2 同源） -->
    <div v-if="vehicleArchive?.ok" class="m-card" style="border:2px solid #0D9488">
      <div style="display:flex;align-items:center;gap:14px">
        <div style="font-size:calc(36px * var(--m-scale));font-weight:800;color:#0F766E;line-height:1">{{ vehicleArchive.percent }}<span style="font-size:calc(17px * var(--m-scale))">%</span></div>
        <div style="flex:1;min-width:0">
          <div style="font-size:calc(17px * var(--m-scale));font-weight:700">我绑定的车辆 · 一车一档</div>
          <div style="font-size:calc(13px * var(--m-scale));color:#64748B;margin-top:4px">
            {{ vehicleArchive.plateNo }} · {{ vehicleArchive.vehicleType || '车辆' }} · 已齐 {{ vehicleArchive.done }}/{{ vehicleArchive.required }} 项
          </div>
          <div style="height:8px;background:#E2E8F0;border-radius:6px;margin-top:8px;overflow:hidden">
            <div :style="{ width: vehicleArchive.percent + '%', height: '100%', background: vehicleArchive.percent >= 100 ? '#16A34A' : '#0D9488' }"></div>
          </div>
        </div>
      </div>
      <div v-if="(vehicleArchive.missing || []).length" style="margin-top:12px;padding-top:12px;border-top:1px solid #E2E8F0">
        <div style="font-size:calc(14px * var(--m-scale));font-weight:700;margin-bottom:6px">车辆档案待补齐</div>
        <div v-for="m in vehicleArchive.missing.slice(0, 6)" :key="m.itemCode"
             style="display:flex;gap:8px;align-items:center;padding:5px 0;font-size:calc(14px * var(--m-scale))">
          <span style="color:#DC2626">●</span><span style="flex:1">{{ m.itemName }}</span>
          <span style="color:#64748B;font-size:calc(12px * var(--m-scale))">{{ m.expired ? '已过期' : '待补' }}</span>
        </div>
        <div v-if="vehicleArchive.expiring" class="m-tip" style="margin-top:8px">另有 {{ vehicleArchive.expiring }} 项证照 / 保险 / 等级临期，请及时处理。</div>
      </div>
    </div>

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
