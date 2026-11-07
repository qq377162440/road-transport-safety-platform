<script setup>
/**
 * 一日三检 Tab · 按原型 mini/check.html 与需求文档 2.2 三步流程正式实现。
 * 读：PC 端下发的 check_task + vehicle_driver + check_item_dict（/api/mini/check/today）
 * 写：PC 端台账 check_record / check_item_result（复用 /api/flow/check/record，
 *     异常自动联动 danger_record 并登记电子档案柜）
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/api/request'
import MobileShell from '@/components/MobileShell.vue'
import { mini, ensureMini, goMini } from '@/store/mini'

const route = useRoute()
const router = useRouter()
const today = ref(null)
const items = ref([])
const records = ref([])
const view = ref('form')        // form | result
const phase = ref('out')
const note = ref('')
const photos = ref([])
const submitting = ref(false)
const confirmShow = ref(false)
const err = ref('')
const result = reactive({ ok: true, text: '', danger: false })

const phases = [
  { key: 'out', label: '出车前', ico: '早' },
  { key: 'mid', label: '行车中', ico: '中' },
  { key: 'in', label: '收车后', ico: '晚' },
]
// 每项：checked 是否已确认，abnormal 是否异常
const marks = reactive({})

async function loadToday() {
  const res = await request.get('/mini/check/today', { params: { personId: mini.personId } })
  today.value = res.data || {}
}
async function loadItems() {
  const res = await request.get('/mini/check/items', { params: { phase: phase.value } })
  items.value = res.data || []
  for (const it of items.value) {
    if (!marks[String(it.id)]) marks[String(it.id)] = { checked: true, abnormal: false }
  }
}
async function loadRecords() {
  const res = await request.get('/mini/check/records', { params: { personId: mini.personId } })
  records.value = res.data || []
}
async function reload() { await Promise.all([loadToday(), loadRecords()]) }
onMounted(async () => {
  await ensureMini(route)
  try {
    await reload()
    // 默认定位到第一个未完成的时段
    const ph = today.value?.phases || {}
    const next = phases.find(p => !ph[p.key]?.done)
    if (next) phase.value = next.key
    await loadItems()
  } catch (e) { err.value = e.message || '加载失败' }
})

const vehicle = computed(() => today.value?.vehicle)
const taskId = computed(() => today.value?.taskId)
const doneCount = computed(() => today.value?.doneCount || 0)
const phaseDone = computed(() => !!today.value?.phases?.[phase.value]?.done)
const checkedCount = computed(() => items.value.filter(x => marks[String(x.id)]?.checked).length)
const abnormalItems = computed(() => items.value.filter(x => marks[String(x.id)]?.abnormal))
const canSubmit = computed(() => !!taskId.value && items.value.length > 0 && submitting.value === false)

async function selectPhase(k) {
  phase.value = k
  await loadItems()
}
function toggleItem(it) {
  const m = marks[String(it.id)]
  if (!m.checked) { m.checked = true; m.abnormal = false; return }
  if (!m.abnormal) { m.abnormal = true; return }
  m.checked = false; m.abnormal = false
}
function itemState(it) {
  const m = marks[String(it.id)] || {}
  return !m.checked ? 'none' : (m.abnormal ? 'bad' : 'ok')
}
function onPhoto(e) {
  const f = e.target.files?.[0]
  if (!f) return
  const reader = new FileReader()
  reader.onload = () => photos.value.push(String(reader.result).slice(0, 200000))
  reader.readAsDataURL(f)
}
function askSubmit() {
  err.value = ''
  if (!taskId.value) { err.value = '暂无今日三检任务，请联系安全员在 PC 端下发'; return }
  if (abnormalItems.value.length && !note.value.trim()) {
    err.value = '发现异常项，请在下面简单写一句情况（也可拍照）'; return
  }
  confirmShow.value = true
}
async function submit() {
  confirmShow.value = false
  submitting.value = true
  try {
    const payload = {
      task_id: taskId.value,
      phase: phase.value,
      result: abnormalItems.value.length ? 'abnormal' : 'pass',
      items: items.value.map(it => ({
        itemId: it.id,
        checked: !!marks[String(it.id)]?.checked,
        abnormal: !!marks[String(it.id)]?.abnormal,
      })),
    }
    if (abnormalItems.value.length) {
      payload.abnormal_note = abnormalItems.value.map(x => x.item_name).join('、') + ' 异常；' + note.value.trim()
      if (photos.value.length) payload.abnormal_photos = JSON.stringify(photos.value)
    }
    const res = await request.post('/flow/check/record', payload)
    result.ok = true
    result.danger = !!res.data?.dangerId
    result.text = abnormalItems.value.length
      ? (result.danger ? '已上报异常，并自动生成隐患整改单' : '已上报异常，请等待安全员处理')
      : '本时段检查完成，全部合格'
    view.value = 'result'
    await reload()
  } catch (e) {
    result.ok = false
    result.text = '提交失败：' + (e.message || '请检查网络')
    view.value = 'result'
  } finally { submitting.value = false }
}
function again() {
  view.value = 'form'; note.value = ''; photos.value = []
  for (const it of items.value) marks[String(it.id)] = { checked: true, abnormal: false }
}
const phaseLabel = (k) => phases.find(p => p.key === k)?.label || k
</script>

<template>
  <MobileShell title="一日三检" tab="check">
    <div v-if="err" class="m-err">{{ err }}</div>

    <template v-if="view === 'result'">
      <div class="m-card m-result" :class="result.ok ? 'pass' : 'fail'">
        <div class="m-r-ico">{{ result.ok ? '✓' : '!' }}</div>
        <div class="m-r-title">{{ result.ok ? '已 记 录' : '提交失败' }}</div>
        <div class="m-r-desc">{{ result.text }}</div>
      </div>
      <button class="m-btn m-btn-primary" @click="again">再检一次</button>
      <button class="m-btn m-btn-outline" @click="goMini(router, '/m/list', { type: 'checks' })">查看我的三检记录</button>
      <div class="m-tip">记录已同步到 PC 端「一日三检」台账{{ result.danger ? '，并联动生成隐患整改单' : '' }}。</div>
    </template>

    <template v-else>
      <!-- 车辆信息 -->
      <div class="m-card">
        <div style="display:flex;align-items:center;gap:12px">
          <div style="width:52px;height:52px;border-radius:12px;background:#DBEAFE;color:#1E40AF;display:flex;align-items:center;justify-content:center;font-size:26px">车</div>
          <div style="flex:1;min-width:0">
            <div style="font-size:calc(20px * var(--m-scale));font-weight:800">{{ vehicle?.plate_no || '未绑定车辆' }}</div>
            <div style="font-size:calc(14px * var(--m-scale));color:#64748B">{{ vehicle?.vehicle_type || '—' }} · {{ vehicle?.brand_model || '' }}</div>
          </div>
          <div style="text-align:right;flex-shrink:0">
            <div style="font-size:calc(13px * var(--m-scale));color:#64748B">今日已检</div>
            <div style="font-size:calc(22px * var(--m-scale));font-weight:800;color:#15803D">{{ doneCount }}/3</div>
          </div>
        </div>
      </div>

      <!-- 第 1 步：选时段 -->
      <div class="m-card-title">第 1 步 · 选择检查时段</div>
      <div style="display:flex;gap:8px;margin-bottom:14px">
        <button v-for="p in phases" :key="p.key" class="m-chip" style="flex:1;justify-content:center;padding:0 6px"
                :class="{ on: phase === p.key }" @click="selectPhase(p.key)">
          {{ p.label }}
          <span v-if="today?.phases?.[p.key]?.done" style="color:#15803D;margin-left:4px">✓</span>
        </button>
      </div>
      <div v-if="phaseDone" class="m-banner" style="background:#F0FDF4;color:#15803D">
        本时段今日已检查（{{ today.phases[phase].result === 'pass' ? '合格' : '有异常' }}），可重新提交覆盖明细
      </div>

      <!-- 第 2 步：逐项打勾 -->
      <div class="m-card-title">第 2 步 · 逐项打勾（点一下就选中，不用打字）</div>
      <div v-for="it in items" :key="it.id" class="m-task"
           :class="itemState(it) === 'ok' ? 'done' : (itemState(it) === 'bad' ? 'active' : '')"
           :style="itemState(it) === 'bad' ? 'border:2px solid #DC2626;background:#FEF2F2' : ''"
           @click="toggleItem(it)">
        <div class="m-t-ico" :class="itemState(it) === 'ok' ? 'green' : (itemState(it) === 'bad' ? 'red' : '')">
          {{ itemState(it) === 'ok' ? '✓' : (itemState(it) === 'bad' ? '!' : '○') }}
        </div>
        <div class="m-t-info"><div class="m-t-title">{{ it.item_name }}</div></div>
        <span class="m-tag" :class="itemState(it) === 'ok' ? 'm-tag-green' : (itemState(it) === 'bad' ? 'm-tag-red' : 'm-tag-gray')">
          {{ itemState(it) === 'ok' ? '正常' : (itemState(it) === 'bad' ? '有毛病' : '未确认') }}
        </span>
      </div>
      <div v-if="!items.length" class="m-empty">暂无该时段检查项，请联系安全员在 PC 端配置</div>

      <!-- 异常说明 + 拍照 -->
      <template v-if="abnormalItems.length">
        <label class="m-label">发现 {{ abnormalItems.length }} 项异常，请简单说明</label>
        <textarea v-model="note" class="m-textarea" placeholder="例如：左后轮胎纹磨平了，需要更换" />
        <label class="m-label">拍照（可选，异常项建议拍一张）</label>
        <input type="file" accept="image/*" class="m-input" style="padding:10px" @change="onPhoto" />
        <div v-if="photos.length" style="font-size:calc(14px * var(--m-scale));color:#15803D;margin-top:6px">已附加 {{ photos.length }} 张照片</div>
      </template>

      <!-- 报修车快捷入口 -->
      <div class="m-banner red" @click="goMini(router, '/m/report', { type: 'repair' })">
        <span style="font-size:22px">!</span><span>车辆有毛病？点这里直接报修</span><span class="m-b-more">去报修 ›</span>
      </div>

      <!-- 第 3 步：提交 -->
      <button class="m-btn m-btn-primary" style="min-height:62px;font-size:calc(20px * var(--m-scale))"
              :disabled="!canSubmit" @click="askSubmit">
        {{ submitting ? '正在提交…' : '确 认 提 交' }}
      </button>
      <div class="m-foot">已确认 {{ checkedCount }} / {{ items.length }} 项 · 提交前会再确认一次</div>

      <!-- 我的记录 -->
      <div class="m-card-title">
        我的三检记录
        <span class="m-more" @click="goMini(router, '/m/list', { type: 'checks' })">全部 ›</span>
      </div>
      <div v-for="r in records.slice(0, 3)" :key="r.id" class="m-task" style="cursor:default">
        <div class="m-t-ico" :class="r.result === 'pass' ? 'green' : 'red'">{{ r.result === 'pass' ? '✓' : '!' }}</div>
        <div class="m-t-info">
          <div class="m-t-title">{{ phaseLabel(r.phase) }} · {{ r.plate_no || '车辆' }}</div>
          <div class="m-t-desc">{{ r.check_day }} · 检查 {{ r.item_count }} 项，异常 {{ r.abnormal_count }} 项</div>
        </div>
        <span class="m-tag" :class="r.result === 'pass' ? 'm-tag-green' : 'm-tag-red'">{{ r.result === 'pass' ? '合格' : '异常' }}</span>
      </div>
      <div v-if="!records.length" class="m-empty">还没有检查记录，完成第一次检查后会显示在这里</div>
    </template>

    <!-- 大字二次确认 -->
    <div v-if="confirmShow" style="position:fixed;inset:0;background:rgba(15,23,42,.55);display:flex;align-items:center;justify-content:center;z-index:50;padding:20px">
      <div style="background:#fff;border-radius:18px;padding:24px;width:100%;max-width:340px;text-align:center">
        <div style="font-size:calc(22px * var(--m-scale));font-weight:800;margin-bottom:12px">确认全部正常？</div>
        <div style="font-size:calc(16px * var(--m-scale));color:#475569;line-height:1.7">
          {{ phaseLabel(phase) }}检查 · 已确认 {{ checkedCount }} 项<br />
          {{ abnormalItems.length ? '其中 ' + abnormalItems.length + ' 项异常将自动生成隐患整改单' : '没有发现异常项' }}
        </div>
        <button class="m-btn m-btn-primary" @click="submit">✓ 确认提交</button>
        <button class="m-btn m-btn-gray" @click="confirmShow = false">✗ 返回修改</button>
      </div>
    </div>
  </MobileShell>
</template>
