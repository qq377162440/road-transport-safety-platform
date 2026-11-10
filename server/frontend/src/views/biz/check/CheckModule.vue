<script setup>
/** B4 一日三检 · 完整流程闭环（详细设计 11）
 *  状态机：not_out 未出车 → doing 出车中 → done 已完成；出/行/收三时段按天串行，集齐才算完成。
 *  子表：检查项明细 check_item_result、提醒记录 check_remind、流转日志 check_flow_log；
 *        PDF 成品 = 月报汇总表（装盒九）+ 单车单日行车日志（现查现打）。
 *  联动：车辆/人车绑定来自 B2、驾驶员来自 B1、检查依据引用 B3 已发布制度、
 *        异常自动转 B5 隐患、月报归档 B7 电子档案柜；小程序仅预留 /expiring、/mine 数据接口。 */
import { computed, onMounted, ref } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'
import Icon from '@/components/Icon.vue'

const ov = ref({ company: '—', month: '', tasks: [], stats: {}, flow: [], config: {}, expiring: [], archives: [] })
const refs = ref([])
const month = ref('')
const keyword = ref('')
const statusFilter = ref('')
const trailerFilter = ref('')
const loading = ref(false)
const busy = ref(false)

const STATUS = {
  not_out: ['未出车', 'tag-gray'], doing: ['出车中', 'tag-blue'], done: ['已完成', 'tag-green'],
}
function statusText(s) { return (STATUS[s] || STATUS.doing)[0] }
function statusClass(s) { return (STATUS[s] || STATUS.doing)[1] }
function tagOf(r) { return r === 'abnormal' ? 'tag-red' : r === 'pass' ? 'tag-green' : 'tag-gray' }
function resultText(r) { return r === 'abnormal' ? '异常' : r === 'pass' ? '合格' : '—' }

const filtered = computed(() => {
  const k = keyword.value.trim()
  return (ov.value.tasks || []).filter((t) => {
    if (k && !(String(t.plateNo || '') + String(t.driverName || '')).includes(k)) return false
    if (statusFilter.value && t.status !== statusFilter.value) return false
    if (trailerFilter.value !== '' && String(t.isTrailer || 0) !== trailerFilter.value) return false
    return true
  })
})

async function load() {
  loading.value = true
  try {
    const [o, r] = await Promise.all([
      request.get('/flow/dailycheck/overview', {
        params: { month: month.value, keyword: keyword.value, status: statusFilter.value, trailer: trailerFilter.value },
      }),
      request.get('/flow/dailycheck/references'),
    ])
    ov.value = o.data || ov.value
    refs.value = r.data || []
    if (!month.value) month.value = ov.value.month || ''
  } catch (e) { /* 拦截器已提示 */ }
  finally { loading.value = false }
}
onMounted(load)

// ---------------- 通用动作 ----------------
async function act(url, method, body, label, withDetail = true) {
  busy.value = true
  try {
    const r = method === 'put' ? await request.put(url, body || {}) : await request.post(url, body || {})
    if (r.data && r.data.ok === false) { ElMessage.warning(r.data.msg || '操作未成功'); return null }
    ElMessage.success(label + '成功')
    await load()
    if (withDetail && drawer.value && curId.value) await refreshDetail()
    return r.data
  } catch (e) { return null }
  finally { busy.value = false }
}

async function openPdf(url) {
  try {
    const raw = await request.get(url, { responseType: 'blob' })
    const blob = raw instanceof Blob ? raw : new Blob([raw], { type: 'application/pdf' })
    const u = URL.createObjectURL(blob)
    window.open(u, '_blank')
    setTimeout(() => URL.revokeObjectURL(u), 60000)
  } catch (e) { ElMessage.error('PDF 生成失败') }
}
function ym() {
  const m = month.value || ov.value.month || new Date().toISOString().slice(0, 7)
  const [y, mm] = m.split('-')
  return { year: Number(y), month: Number(mm) }
}
function viewMonthly() { const { year, month: m } = ym(); openPdf(`/flow/dailycheck/monthly/pdf?year=${year}&month=${m}`) }
function viewDaily() { openPdf(`/flow/dailycheck/daily/pdf?taskId=${curId.value}&day=${dayForm.value.day}`) }
async function doArchive() {
  const { year, month: m } = ym()
  const r = await act('/flow/dailycheck/archive', 'post', { year, month: m }, '月报装盒归档', false)
  if (r) ElMessage.success(`已生成 ${r.rows || 0} 行汇总，PDF ${r.size || 0} 字节`)
}

// ---------------- 详情抽屉 ----------------
const drawer = ref(false)
const detail = ref(null)
const tab = ref('log')
const curId = ref('')
const dayForm = ref({ day: '' })

async function openDetail(row) {
  curId.value = String(row.id)
  tab.value = 'log'
  drawer.value = true
  await refreshDetail()
}
async function refreshDetail() {
  busy.value = true
  try { detail.value = (await request.get(`/flow/dailycheck/task/${curId.value}`)).data || null }
  catch (e) { detail.value = null }
  finally { busy.value = false }
}
const cur = computed(() => (ov.value.tasks || []).find((x) => x.id === curId.value) || detail.value?.task || {})
const next = computed(() => detail.value?.nextPhase || 'out')
const phaseName = { out: '出车前', mid: '行车中', in: '收车后' }

// 表格行直接「填报」：先加载该任务的详情，避免弹窗沿用上一个任务的下一时段与检查项
async function quickFill(row) {
  await openDetail(row)
  await openDlg('phase')
}

// ---------------- 弹窗 ----------------
const dlg = ref('')
const form = ref({})
const vehicleOptions = ref([])
const driverOptions = ref([])
const itemRows = ref([])

async function loadOptions(kw = '') {
  try {
    const [v, d] = await Promise.all([
      request.get('/flow/dailycheck/vehicles/search', { params: { kw } }),
      request.get('/flow/dailycheck/drivers'),
    ])
    vehicleOptions.value = v.data || []
    driverOptions.value = d.data || []
  } catch (e) { /* 忽略 */ }
}
function today() { return new Date().toISOString().slice(0, 10) }

async function openDlg(type, row) {
  dlg.value = type
  await loadOptions()
  const t = row || cur.value || {}
  if (type === 'start') {
    form.value = {
      vehicleId: '', kw: '', startDate: today(), isTrailer: 0, driverId: '',
      mileageStart: '', mileageEnd: '', cargoName: '', cargoQty: '',
      origin: '', stopover: '', destination: '',
    }
  } else if (type === 'phase') {
    const day = (detail.value?.days || []).slice(-1)[0]?.day || t.startDate || today()
    dayForm.value.day = day
    form.value = {
      phase: next.value, checkDay: day, result: 'pass', abnormalNote: '',
      abnormalVoice: '', abnormalPhotos: '', finish: next.value === 'in',
    }
    buildItemRows()
  } else if (type === 'driver') {
    form.value = { driverId: String(t.driverId || ''), escortId: '' }
  } else if (type === 'logistics') {
    const lg = detail.value?.logistics || {}
    form.value = {
      mileageStart: lg.mileage_start || '', mileageEnd: lg.mileage_end || '',
      cargoName: lg.cargo_name || '', cargoQty: lg.cargo_qty || '',
      origin: lg.origin || '', stopover: lg.stopover || '', destination: lg.destination || '',
    }
  } else if (type === 'config') {
    const c = ov.value.config || {}
    form.value = {
      defaultPolicy: c.defaultPolicy || 'not_out',
      forceCamera: !!c.forceCamera, allowDeclare: !!c.allowDeclare,
    }
  } else if (type === 'items') {
    try {
      const r = await request.get('/flow/dailycheck/items', { params: { vehicleClass: t.vehicleClass || 'heavy' } })
      itemRows.value = (r.data?.items || []).map((x) => ({ ...x, _deleted: false }))
    } catch (e) { itemRows.value = [] }
    form.value = { vehicleClass: t.vehicleClass || 'heavy' }
  } else if (type === 'remind') {
    form.value = { way: '小程序推送', content: '今天三检还没做，抓紧咯' }
  } else if (type === 'correct') {
    const recs = detail.value?.records || []
    form.value = {
      recordId: recs.length ? recs[recs.length - 1].id : '', result: 'pass',
      checkTime: '', abnormalNote: '',
    }
  } else if (type === 'monthly') {
    form.value = { ...ym() }
  }
}
function buildItemRows() {
  const all = detail.value?.items || []
  itemRows.value = all.filter((x) => x.phase === form.value.phase)
    .map((x) => ({ itemId: x.id, itemName: x.itemName, result: 'pass' }))
}
function onPhaseChange() { buildItemRows() }

async function searchVehicle() {
  try {
    const r = await request.get('/flow/dailycheck/vehicles/search', { params: { kw: form.value.kw } })
    vehicleOptions.value = r.data || []
  } catch (e) { /* 忽略 */ }
}
function pickVehicle(v) {
  form.value.vehicleId = v.id
  form.value.kw = v.plateNo
  form.value.isTrailer = v.isTrailer || 0
  form.value.driverId = v.driverId && v.driverId !== '0' ? v.driverId : ''
}

async function submitDlg() {
  const type = dlg.value
  const f = form.value
  let ok = false
  if (type === 'start') {
    if (!f.vehicleId) { ElMessage.warning('请先搜索并点选车辆'); return }
    ok = !!(await act('/flow/dailycheck/task/start', 'post', f, '标记出车', false))
  } else if (type === 'phase') {
    const items = itemRows.value.map((x) => ({ itemId: x.itemId, result: x.result, checked: x.result === 'pass' ? 1 : 0, abnormal: x.result === 'abnormal' ? 1 : 0 }))
    ok = !!(await act(`/flow/dailycheck/task/${curId.value}/phase`, 'post', { ...f, items }, '提交' + phaseName[f.phase] + '检查'))
  } else if (type === 'driver') {
    ok = !!(await act(`/flow/dailycheck/task/${curId.value}/driver`, 'put', f, '切换当天驾驶员'))
  } else if (type === 'logistics') {
    ok = !!(await act(`/flow/dailycheck/task/${curId.value}/logistics`, 'put', f, '补录行车日志'))
  } else if (type === 'config') {
    ok = !!(await act('/flow/dailycheck/config', 'put', f, '保存三检开关', false))
  } else if (type === 'items') {
    // 只提交企业自定义项：平台预置项只读，原样提交会被后端当成新增项重复插入
    const r = await act('/flow/dailycheck/items', 'put', {
      vehicleClass: f.vehicleClass,
      items: itemRows.value.filter((x) => x.custom).map((x) => ({
        id: x.id || '', itemCode: x.itemCode || 'CUSTOM_' + Date.now(),
        itemName: x.itemName, phase: x.phase || 'out', vehicleClass: f.vehicleClass,
        sortNo: x.sortNo || 99, deleted: !!x._deleted,
      })),
    }, '保存检查项', false)
    ok = !!r
    if (ok) ElMessage.success(`检查项已保存（新增 ${r.added || 0} / 删除 ${r.removed || 0}）`)
  } else if (type === 'remind') {
    ok = !!(await act(`/flow/dailycheck/task/${curId.value}/remind`, 'post', f, '发送提醒'))
  } else if (type === 'correct') {
    if (!f.recordId) { ElMessage.warning('请选择要纠错的检查记录'); return }
    ok = !!(await act(`/flow/dailycheck/record/${f.recordId}`, 'put', f, '超管纠错'))
  }
  if (ok) dlg.value = ''
}

function addItemRow() {
  itemRows.value.push({
    id: '', custom: true, itemCode: 'CUSTOM_' + Date.now(), itemName: '',
    phase: dlg.value === 'phase' ? form.value.phase : 'out', sortNo: 99,
  })
}

// 一键全部正常（适老化：正常情况 1 步完成，零打字）
async function quickPass() {
  const day = (detail.value?.days || []).slice(-1)[0]?.day || cur.value.startDate || today()
  const items = (detail.value?.items || []).filter((x) => x.phase === next.value)
    .map((x) => ({ itemId: x.id, result: 'pass', checked: 1, abnormal: 0 }))
  const body = {
    phase: next.value, checkDay: day, result: 'pass', items,
    abnormalNote: '', abnormalVoice: '', abnormalPhotos: '', finish: next.value === 'in',
  }
  await act(`/flow/dailycheck/task/${curId.value}/phase`, 'post', body, '一键全部正常')
}
async function notOut(declare) {
  await act(`/flow/dailycheck/task/${curId.value}/not-out`, 'post', { declare: !!declare }, declare ? '声明未出车' : '标记未出车')
}
async function finishTask() {
  await act(`/flow/dailycheck/task/${curId.value}/finish`, 'post', { endDate: today() }, '收车结束周期')
}
function goArchive() { window.open('/archive?box=9', '_blank') }
</script>

<template>
  <div class="bm-page">
    <div class="pc-header bm-header">
      <div>
        <h1>一日三检</h1>
        <div class="desc">行车日志 + 出车/行车/收车三检 · 出车周期状态机 · 月报装盒九、明细现查现打</div>
        <div class="bm-meta">
          <span class="tag tag-blue">阶段 B4</span>
          <span class="tag tag-gray">P0 保命功能</span>
          <span class="tag tag-green">归档 盒九 / 危货盒十</span>
          <span class="bm-tables">{{ ov.company }} · {{ ov.stats?.dictCount || 0 }} 项检查项 · 三时段串行 · 6 张业务表 · 小程序仅预留数据接口</span>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-primary" @click="openDlg('start')"><Icon name="plus" :size="15" /> 新增检查（标记出车）</button>
        <button class="btn btn-outline" @click="openDlg('config')"><Icon name="setting" :size="15" /> 三检开关</button>
        <button class="btn btn-outline" @click="openDlg('items')"><Icon name="clipboard" :size="15" /> 检查项配置</button>
        <button class="btn btn-outline" @click="viewMonthly"><Icon name="fileText" :size="15" /> 月报汇总表</button>
        <button class="btn btn-outline" :disabled="busy" @click="doArchive"><Icon name="archive" :size="15" /> 归档月报</button>
        <button class="btn btn-outline" @click="goArchive"><Icon name="folder" :size="15" /> 电子档案柜</button>
        <button class="btn btn-outline" @click="load()"><Icon name="refresh" :size="15" /> 刷新</button>
      </div>
    </div>

    <div class="pf-hint"><Icon name="info" :size="15" />
      绑定出车行为：不出车不强制三检；出车后按「出车前 → 行车中 → 收车后」串行执行，三时段集齐才完成。
      跨日长途以出车周期为单位，每天上下车各一次三检。异常自动转隐患排查，语音 + 文字 + 照片一并带出。
    </div>

    <div class="pf-stat-row">
      <div class="stat-card"><div class="num">{{ ov.stats?.total || 0 }}</div><div class="lbl">本月出车周期任务</div></div>
      <div class="stat-card"><div class="num" style="color:#2563eb">{{ ov.stats?.doing || 0 }}</div><div class="lbl">出车中 · 三检完成度 {{ ov.stats?.percent || 0 }}%</div></div>
      <div class="stat-card"><div class="num" style="color:var(--ok,#16a34a)">{{ ov.stats?.done || 0 }}</div><div class="lbl">已完成 / 未出车 {{ ov.stats?.notOut || 0 }}</div></div>
      <div class="stat-card"><div class="num" style="color:var(--danger,#dc2626)">{{ ov.stats?.abnormal || 0 }}</div><div class="lbl">异常转隐患 · 提醒 {{ ov.stats?.reminded || 0 }} 次</div></div>
    </div>

    <div class="card pf-flow">
      <div class="card-title">出车周期状态机 <span class="pf-sub">逐条操作 · 每次流转留痕 · 未出车豁免 / 已完成后需重新出车</span></div>
      <div class="pf-flow-row">
        <template v-for="(f, i) in ov.flow || []" :key="f.code">
          <div class="pf-flow-node" :class="{ on: (f.code === 'doing' && ov.stats?.doing) || (f.code === 'done' && ov.stats?.done) || (f.code === 'not_out' && ov.stats?.notOut) }"><b>{{ i + 1 }}</b><span>{{ f.name }}</span></div>
          <span v-if="i < (ov.flow || []).length - 1" class="pf-arrow">→</span>
        </template>
        <span class="pf-sub" style="margin-left:12px">三时段：出车前 → 行车中 → 收车后（同日串行，跨日按天续接）</span>
      </div>
    </div>

    <div v-if="(ov.expiring || []).length" class="card pf-warn">
      <div class="card-title">未检预警 <span class="pf-sub">出车中但三时段未集齐 · 可一键提醒</span></div>
      <div class="pf-warn-list">
        <div v-for="w in (ov.expiring || []).slice(0, 8)" :key="w.taskId" class="pf-warn-item">
          <span class="tag tag-yellow">{{ w.level }}</span>
          <span class="nm">{{ w.plateNo }} · {{ w.driverName || '未指定驾驶员' }}（{{ w.phaseDone }}/3）</span>
          <span class="dt">{{ w.date }}</span>
        </div>
      </div>
    </div>

    <div class="filter-bar bm-filter">
      <div class="f-item"><label>月份</label><input v-model="month" type="month" placeholder="YYYY-MM"></div>
      <div class="f-item"><label>车牌 / 驾驶员</label><input v-model="keyword" type="text" placeholder="模糊搜索"></div>
      <div class="f-item"><label>状态</label>
        <select v-model="statusFilter">
          <option value="">全部</option><option value="doing">出车中</option>
          <option value="done">已完成</option><option value="not_out">未出车</option>
        </select>
      </div>
      <div class="f-item"><label>车头 / 挂</label>
        <select v-model="trailerFilter">
          <option value="">全部</option><option value="0">车头</option><option value="1">挂车</option>
        </select>
      </div>
      <div class="f-actions">
        <button class="btn btn-primary btn-sm" @click="load()"><Icon name="search" :size="14" /> 查询</button>
        <button class="btn btn-outline btn-sm" @click="keyword = ''; statusFilter = ''; trailerFilter = ''; load()">重置</button>
      </div>
    </div>

    <div class="card" style="padding:0;overflow:hidden">
      <div class="card-title" style="padding:16px 20px 0">检查记录
        <span class="pf-sub">共 {{ filtered.length }} 个出车周期 ·  {{ ov.month }} · 明细不装盒，月报汇总表才装盒</span>
      </div>
      <div style="overflow:auto">
        <table class="table bm-table" v-loading="loading">
          <thead><tr>
            <th>车牌号</th><th>驾驶员 / 押运员</th><th>状态</th>
            <th>出车检查</th><th>行车检查</th><th>收车检查</th>
            <th>里程</th><th>进度</th><th>操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="t in filtered" :key="t.id">
              <td><b>{{ t.plateNo || '—' }}</b><span v-if="t.isTrailer === 1" class="tag tag-gray" style="margin-left:5px">挂</span></td>
              <td>{{ t.driverName || '未指定' }}<span v-if="t.escortName" class="pf-sub"> / {{ t.escortName }}</span></td>
              <td><span class="tag" :class="statusClass(t.status)">{{ statusText(t.status) }}</span></td>
              <td v-for="p in (t.phases || [])" :key="p.phase">
                <span class="tag" :class="tagOf(p.result)">{{ resultText(p.result) }}</span>
                <span v-if="p.time" class="pf-sub" style="display:block">{{ String(p.time).slice(11, 16) }}</span>
              </td>
              <td>{{ t.mileage || (t.mileageEnd - t.mileageStart) || '—' }}<span v-if="t.mileage" class="pf-sub"> km</span></td>
              <td><span class="pf-bar"><span class="pf-bar-in" :class="{ done: t.phaseDone >= 3 }" :style="{ width: (t.phaseDone / 3 * 100) + '%' }"></span></span><span class="pf-pct">{{ t.phaseDone }}/3</span></td>
              <td>
                <a class="btn btn-primary btn-sm" @click="openDetail(t)">详情</a>
                <a class="btn btn-outline btn-sm" @click="quickFill(t)">填报</a>
              </td>
            </tr>
            <tr v-if="!filtered.length"><td colspan="9" style="text-align:center;color:var(--text-3);padding:26px">本月暂无三检任务，点右上「新增检查（标记出车）」开始</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 详情抽屉 -->
    <div class="pf-mask" :class="{ on: drawer }" @click.self="drawer = false">
      <div class="pf-drawer">
        <div class="pf-dh">
          <div>
            <h2>{{ cur.plateNo || '车辆' }} · {{ cur.startDate || '' }} 出车周期</h2>
            <div class="pf-sub">{{ ov.company }} · 驾驶员 {{ cur.driverName || '未指定' }} · 归档盒九
              <span class="tag" :class="statusClass(cur.status)" style="margin-left:6px">{{ statusText(cur.status) }}</span>
              <span v-if="cur.isTrailer === 1" class="tag tag-gray" style="margin-left:4px">挂车</span>
              <span class="tag tag-blue" style="margin-left:4px">下一时段 {{ phaseName[next] }}</span>
            </div>
          </div>
          <button class="pf-close" @click="drawer = false">✕</button>
        </div>
        <div class="pf-tabs">
          <button v-for="t in [['log','行车日志'],['record','三检明细'],['item','检查项'],['remind','提醒记录'],['log2','流转日志'],['ref','联动与归档']]" :key="t[0]"
                  class="pf-tab" :class="{ on: tab === t[0] }" @click="tab = t[0]">{{ t[1] }}</button>
        </div>
        <div v-if="detail" class="pf-body" v-loading="busy">
          <div class="card pf-actions">
            <div class="card-title">逐条操作 <span class="pf-sub">当前 {{ statusText(cur.status) }} · 已完成 {{ cur.phaseDone || 0 }}/3 · 三检串行</span></div>
            <div class="pf-btn-row">
              <a class="btn btn-primary btn-sm" @click="quickPass"><Icon name="check" :size="14" /> 全部正常（一键 {{ phaseName[next] }}）</a>
              <a class="btn btn-outline btn-sm" @click="openDlg('phase')">车有毛病 / 逐项填报</a>
              <a class="btn btn-outline btn-sm" @click="finishTask">收车结束周期</a>
              <a class="btn btn-outline btn-sm" @click="notOut(false)">PC 标记未出车</a>
              <a v-if="ov.config?.allowDeclare" class="btn btn-outline btn-sm" @click="notOut(true)">司机声明未出车</a>
              <a class="btn btn-outline btn-sm" @click="openDlg('driver')">切换当天驾驶员</a>
              <a class="btn btn-outline btn-sm" @click="openDlg('logistics')">补录行车日志</a>
              <a class="btn btn-outline btn-sm" @click="openDlg('remind')">一键提醒（{{ cur.remindCount || 0 }}）</a>
              <a class="btn btn-outline btn-sm" @click="viewDaily">打印行车日志（现查现打）</a>
              <a class="btn btn-outline btn-sm" @click="openDlg('correct')">超管纠错</a>
            </div>
          </div>

          <!-- Tab1 行车日志 -->
          <template v-if="tab === 'log'">
            <div class="card pf-group">
              <div class="card-title">企业车辆行车日志 <span class="pf-sub">字段与线下台账「一日三检·行车日志」逐字段对应</span></div>
              <table class="pf-subtable">
                <tbody>
                  <tr><th>车牌号</th><td>{{ cur.plateNo }}</td><th>日期</th><td>{{ cur.startDate }}</td></tr>
                  <tr><th>出车公里数</th><td>{{ detail.logistics?.mileage_start || '—' }}</td><th>收车公里数</th><td>{{ detail.logistics?.mileage_end || '—' }}</td></tr>
                  <tr><th>货物名称</th><td>{{ detail.logistics?.cargo_name || '—' }}</td><th>货物数量</th><td>{{ detail.logistics?.cargo_qty || '—' }}</td></tr>
                  <tr><th>始发地</th><td>{{ detail.logistics?.origin || '—' }}</td><th>目的地</th><td>{{ detail.logistics?.destination || '—' }}</td></tr>
                  <tr><th>中途停靠地</th><td colspan="3">{{ detail.logistics?.stopover || '—' }}</td></tr>
                </tbody>
              </table>
            </div>
            <div class="card pf-group">
              <div class="card-title">一日三检记录表 <span class="pf-sub">按天分组，跨日长途不中断出车周期（C10）</span></div>
              <div v-for="d in detail.days || []" :key="d.day" class="pf-day">
                <div class="pf-day-h"><b>{{ d.day }}</b><span class="pf-sub">完成 {{ d.done }}/3</span>
                  <span class="tag" :class="d.complete ? 'tag-green' : 'tag-yellow'">{{ d.complete ? '三检齐全' : '进行中' }}</span></div>
                <table class="pf-subtable">
                  <thead><tr><th>时段</th><th>结果</th><th>检查时间</th><th>异常说明</th></tr></thead>
                  <tbody>
                    <tr v-for="p in d.phases || []" :key="p.phase">
                      <td>{{ p.name }}</td>
                      <td><span class="tag" :class="tagOf(p.result)">{{ resultText(p.result) }}</span></td>
                      <td>{{ p.time || '—' }}</td>
                      <td>{{ (detail.records || []).find((r) => r.checkDay === d.day && r.phase === p.phase)?.abnormalNote || '—' }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </template>

          <!-- Tab2 三检明细 -->
          <template v-if="tab === 'record'">
            <div class="card pf-group">
              <div class="card-title">检查记录与逐项结果 <span class="pf-sub">共 {{ (detail.records || []).length }} 条 · 检查项明细留痕</span></div>
              <div v-for="r in detail.records || []" :key="r.id" class="pf-day">
                <div class="pf-day-h"><b>{{ r.checkDay }} · {{ r.phaseName }}</b>
                  <span class="tag" :class="tagOf(r.result)">{{ resultText(r.result) }}</span>
                  <span class="pf-sub">{{ r.checkTime }}</span>
                  <span v-if="r.abnormalVoice" class="tag tag-blue">语音留痕</span>
                  <span v-if="r.abnormalPhotos" class="tag tag-blue">照片留痕</span>
                </div>
                <div v-if="r.abnormalNote" class="pf-sub">异常说明：{{ r.abnormalNote }}</div>
                <table class="pf-subtable" v-if="(r.items || []).length">
                  <thead><tr><th>检查项</th><th>编码</th><th>结果</th></tr></thead>
                  <tbody><tr v-for="it in r.items" :key="it.id">
                    <td>{{ it.item_name }}</td><td>{{ it.item_code }}</td>
                    <td><span class="tag" :class="String(it.abnormal) === '1' ? 'tag-red' : 'tag-green'">{{ String(it.abnormal) === '1' ? '异常' : '正常' }}</span></td>
                  </tr></tbody>
                </table>
                <div v-else class="pf-sub">一键「全部正常」提交（无逐项勾选）</div>
              </div>
              <div v-if="!(detail.records || []).length" class="pf-sub">暂无检查记录，点「全部正常」开始。</div>
            </div>
          </template>

          <!-- Tab3 检查项 -->
          <template v-if="tab === 'item'">
            <div class="card pf-group">
              <div class="card-title">检查项清单（按车型标签拼装） <span class="pf-sub">共 {{ (detail.items || []).length }} 项 · 企业自定义覆盖平台预置（C1/C7）</span></div>
              <table class="pf-subtable">
                <thead><tr><th>时段</th><th>检查项</th><th>编码</th><th>车型</th><th>来源</th></tr></thead>
                <tbody><tr v-for="it in detail.items || []" :key="it.id">
                  <td>{{ it.phaseName }}</td><td>{{ it.itemName }}</td><td>{{ it.itemCode }}</td>
                  <td>{{ it.vehicleClass }}</td>
                  <td><span class="tag" :class="it.custom ? 'tag-blue' : 'tag-gray'">{{ it.custom ? '企业自定义' : '平台预置' }}</span></td>
                </tr></tbody>
              </table>
              <div class="pf-btn-row"><a class="btn btn-outline btn-sm" @click="openDlg('items')">自定义检查项 / 上传模板</a></div>
            </div>
          </template>

          <!-- Tab4 提醒 -->
          <template v-if="tab === 'remind'">
            <div class="card pf-group">
              <div class="card-title">提醒记录 <span class="pf-sub">共 {{ (detail.reminds || []).length }} 次 · 预警引擎按策略分级提醒</span></div>
              <table class="pf-subtable">
                <thead><tr><th>时间</th><th>方式</th><th>内容</th></tr></thead>
                <tbody><tr v-for="r in detail.reminds || []" :key="r.id">
                  <td>{{ r.remind_date }}</td><td>{{ r.remind_way }}</td><td>{{ r.remind_content }}</td>
                </tr></tbody>
              </table>
              <div v-if="!(detail.reminds || []).length" class="pf-sub">暂无提醒记录。</div>
            </div>
          </template>

          <!-- Tab5 流转日志 -->
          <template v-if="tab === 'log2'">
            <div class="card pf-group">
              <div class="card-title">状态流转日志 <span class="pf-sub">每次出车 / 提交 / 收车 / 切换 / 纠错逐条留痕</span></div>
              <table class="pf-subtable">
                <thead><tr><th>时间</th><th>动作</th><th>时段</th><th>状态</th><th>说明</th><th>操作人</th></tr></thead>
                <tbody><tr v-for="g in detail.logs || []" :key="g.id">
                  <td>{{ g.created_at }}</td><td><b>{{ g.action }}</b></td><td>{{ g.phase || '—' }}</td>
                  <td>{{ g.to_status || g.from_status || '—' }}</td><td>{{ g.detail }}</td><td>{{ g.operator || '—' }}</td>
                </tr></tbody>
              </table>
              <div v-if="!(detail.logs || []).length" class="pf-sub">暂无流转日志。</div>
            </div>
          </template>

          <!-- Tab6 联动 -->
          <template v-if="tab === 'ref'">
            <div class="card pf-group">
              <div class="card-title">异常 → 隐患排查（B5） <span class="pf-sub">语音 + 文字 + 照片一并带出</span></div>
              <table class="pf-subtable">
                <thead><tr><th>隐患标题</th><th>级别</th><th>状态</th><th>发现时间</th></tr></thead>
                <tbody><tr v-for="d in detail.dangers || []" :key="d.id">
                  <td>{{ d.title }}</td><td>{{ d.danger_level }}</td><td>{{ d.status }}</td><td>{{ d.find_time }}</td>
                </tr></tbody>
              </table>
              <div v-if="!(detail.dangers || []).length" class="pf-sub">本周期暂无三检异常。</div>
            </div>
            <div class="card pf-group">
              <div class="card-title">模块间联动 <span class="pf-sub">B1 人员 / B2 车辆与人车绑定 / B3 制度依据 / B5 隐患 / B7 归档</span></div>
              <div class="pf-ref-row">
                <span v-for="r in refs" :key="r.module" class="tag" :class="r.count ? 'tag-blue' : 'tag-gray'">{{ r.name }} · {{ r.count }}</span>
              </div>
              <div class="pf-sub" style="margin-top:10px">
                车牌与车型来自 B2 车辆主表，驾驶员来自 B1 人员主表并按 vehicle_driver 活跃绑定预填；
                切换当天驾驶员只改本任务、不动长期绑定；已发布制度作为检查依据通过 /options 提供；
                月报汇总表归档到电子档案柜盒九（危货盒十），单车单日明细不装盒。
              </div>
            </div>
            <div class="card pf-group">
              <div class="card-title">本月归档项 <span class="pf-sub">仅月报汇总表装盒（C14）</span></div>
              <div class="pf-ref-row">
                <span v-for="a in ov.archives || []" :key="a.id" class="tag tag-green">{{ a.title }}</span>
              </div>
              <div v-if="!(ov.archives || []).length" class="pf-sub">本月尚未归档，点顶部「归档月报」生成 PDF 并装盒。</div>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- 通用弹窗 -->
    <div class="pf-mask" :class="{ on: dlg }" @click.self="dlg = ''">
      <div class="pf-modal">
        <div class="pf-modal-h">
          <h3>{{ {
            start: '新增检查 · 标记出车', phase: '三检填报', driver: '切换当天驾驶员',
            logistics: '补录行车日志', config: '三检开关配置', items: '检查项配置',
            remind: '一键提醒', correct: '超管纠错', monthly: '月报汇总',
          }[dlg] }}</h3>
          <button class="pf-close" @click="dlg = ''">✕</button>
        </div>
        <div class="pf-modal-b">
          <!-- 新增出车 -->
          <template v-if="dlg === 'start'">
            <div class="pf-form-grid">
              <div class="f-item"><label>车牌模糊搜索（输入数字/字母即弹出）</label>
                <input v-model="form.kw" type="text" placeholder="如 12 / 粤E" @input="searchVehicle">
                <div v-if="vehicleOptions.length" class="pf-pick">
                  <a v-for="v in vehicleOptions.slice(0, 8)" :key="v.id" @click="pickVehicle(v)">{{ v.label }} · {{ v.driverName || '无绑定' }}</a>
                </div>
              </div>
              <div class="f-item"><label>已选车辆</label><input :value="form.kw || '未选择'" disabled></div>
              <div class="f-item"><label>出车日期</label><input v-model="form.startDate" type="date"></div>
              <div class="f-item"><label>车头 / 挂车</label><select v-model.number="form.isTrailer"><option :value="0">车头</option><option :value="1">挂车</option></select></div>
              <div class="f-item"><label>当天实际驾驶员</label><select v-model="form.driverId"><option value="">按人车绑定预填</option><option v-for="d in driverOptions" :key="d.id" :value="d.id">{{ d.name }}</option></select></div>
              <div class="f-item"><label>出车公里数</label><input v-model="form.mileageStart" type="number" placeholder="里程表读数"></div>
              <div class="f-item"><label>收车公里数</label><input v-model="form.mileageEnd" type="number"></div>
              <div class="f-item"><label>货物名称</label><input v-model="form.cargoName" type="text"></div>
              <div class="f-item"><label>货物数量</label><input v-model="form.cargoQty" type="text"></div>
              <div class="f-item"><label>始发地</label><input v-model="form.origin" type="text"></div>
              <div class="f-item"><label>目的地</label><input v-model="form.destination" type="text"></div>
              <div class="f-item"><label>中途停靠地</label><input v-model="form.stopover" type="text"></div>
            </div>
            <div class="pf-sub" style="margin-top:10px">不出车不生成三检任务；停运车辆自动跳过。车头挂分离时分别标记出车。</div>
          </template>

          <!-- 三检填报 -->
          <template v-if="dlg === 'phase'">
            <div class="pf-form-grid">
              <div class="f-item"><label>时段</label><select v-model="form.phase" @change="onPhaseChange">
                <option value="out">出车前</option><option value="mid">行车中</option><option value="in">收车后</option>
              </select></div>
              <div class="f-item"><label>检查日期（跨日任务选第几天）</label><input v-model="form.checkDay" type="date"></div>
              <div class="f-item"><label>结果</label><select v-model="form.result">
                <option value="pass">全部正常</option><option value="abnormal">异常（车有毛病）</option>
              </select></div>
              <div class="f-item"><label>收车后结束周期</label><select v-model="form.finish"><option :value="true">是</option><option :value="false">否</option></select></div>
            </div>
            <div class="pf-sub" style="margin:10px 0">逐项检查（默认全部正常，异常项单独标记）：</div>
            <table class="pf-subtable">
              <thead><tr><th>检查项</th><th>结果</th></tr></thead>
              <tbody><tr v-for="(it, i) in itemRows" :key="it.itemId || i">
                <td>{{ it.itemName }}</td>
                <td><select v-model="it.result"><option value="pass">正常</option><option value="abnormal">异常</option></select></td>
              </tr></tbody>
            </table>
            <div v-if="form.result === 'abnormal'" class="pf-form-grid" style="margin-top:12px">
              <div class="f-item"><label>异常说明（语音转文字）</label><input v-model="form.abnormalNote" type="text"></div>
              <div class="f-item"><label>异常语音文件 URL</label><input v-model="form.abnormalVoice" type="text"></div>
              <div class="f-item"><label>异常照片 URL（多个逗号分隔）</label><input v-model="form.abnormalPhotos" type="text"></div>
            </div>
            <div class="pf-sub" style="margin-top:8px">异常提交会自动生成隐患单，语音 + 文字 + 照片一并流转 B5 隐患排查。</div>
          </template>

          <!-- 切换驾驶员 -->
          <template v-if="dlg === 'driver'">
            <div class="pf-form-grid">
              <div class="f-item"><label>当天实际驾驶员（换头换挂 / 顶班）</label>
                <select v-model="form.driverId"><option v-for="d in driverOptions" :key="d.id" :value="d.id">{{ d.name }}</option></select>
              </div>
              <div class="f-item"><label>押运员（危货）</label>
                <select v-model="form.escortId"><option value="">不变</option><option v-for="d in driverOptions" :key="d.id" :value="d.id">{{ d.name }}</option></select>
              </div>
            </div>
            <div class="pf-sub" style="margin-top:10px">只改当前出车周期的实际驾驶员，不修改 vehicle_driver 长期人车绑定（C9）。</div>
          </template>

          <!-- 行车日志 -->
          <template v-if="dlg === 'logistics'">
            <div class="pf-form-grid">
              <div class="f-item"><label>出车公里数</label><input v-model="form.mileageStart" type="number"></div>
              <div class="f-item"><label>收车公里数</label><input v-model="form.mileageEnd" type="number"></div>
              <div class="f-item"><label>货物名称</label><input v-model="form.cargoName" type="text"></div>
              <div class="f-item"><label>货物数量</label><input v-model="form.cargoQty" type="text"></div>
              <div class="f-item"><label>始发地</label><input v-model="form.origin" type="text"></div>
              <div class="f-item"><label>目的地</label><input v-model="form.destination" type="text"></div>
              <div class="f-item"><label>中途停靠地</label><input v-model="form.stopover" type="text"></div>
            </div>
          </template>

          <!-- 三检开关 -->
          <template v-if="dlg === 'config'">
            <div class="pf-switch"><div><b>默认策略（C12）</b><div class="pf-sub">不出车不强制三检 / 默认生成任务再手动豁免</div></div>
              <select v-model="form.defaultPolicy"><option value="not_out">默认未出车</option><option value="not_check">默认未检查</option></select>
            </div>
            <div class="pf-switch"><div><b>强制实时摄像头（C15）</b><div class="pf-sub">报修拍照只能实时拍摄，禁用相册上传</div></div>
              <input v-model="form.forceCamera" type="checkbox">
            </div>
            <div class="pf-switch"><div><b>允许司机声明未出车（C16）</b><div class="pf-sub">司机可自行声明当日未出车免做三检，留痕可追溯</div></div>
              <input v-model="form.allowDeclare" type="checkbox">
            </div>
          </template>

          <!-- 检查项配置 -->
          <template v-if="dlg === 'items'">
            <div class="pf-form-grid" style="margin-bottom:10px">
              <div class="f-item"><label>车型标签</label><input v-model="form.vehicleClass" type="text"></div>
            </div>
            <table class="pf-subtable">
              <thead><tr><th>时段</th><th>检查项名称</th><th>编码</th><th>来源</th><th>删除</th></tr></thead>
              <tbody><tr v-for="(it, i) in itemRows" :key="it.id || i">
                <td><select v-model="it.phase"><option value="out">出车前</option><option value="mid">行车中</option><option value="in">收车后</option></select></td>
                <td><input v-model="it.itemName" type="text" :disabled="!it.custom"></td>
                <td>{{ it.itemCode }}</td>
                <td><span class="tag" :class="it.custom ? 'tag-blue' : 'tag-gray'">{{ it.custom ? '自定义' : '预置' }}</span></td>
                <td><a v-if="it.custom" class="btn btn-outline btn-sm" @click="it._deleted = !it._deleted">{{ it._deleted ? '撤销' : '删除' }}</a></td>
              </tr></tbody>
            </table>
            <div class="pf-btn-row"><a class="btn btn-outline btn-sm" @click="addItemRow">+ 新增自定义检查项</a></div>
            <div class="pf-sub" style="margin-top:8px">平台预置项只读；企业自定义项按车型 + 时段覆盖预置项。支持纸质检查表模板对照录入。</div>
          </template>

          <!-- 提醒 -->
          <template v-if="dlg === 'remind'">
            <div class="pf-form-grid">
              <div class="f-item"><label>提醒方式</label><select v-model="form.way"><option>小程序推送</option><option>短信</option><option>电话</option></select></div>
              <div class="f-item"><label>提醒内容</label><input v-model="form.content" type="text"></div>
            </div>
          </template>

          <!-- 纠错 -->
          <template v-if="dlg === 'correct'">
            <div class="pf-form-grid">
              <div class="f-item"><label>检查记录</label><select v-model="form.recordId">
                <option v-for="r in detail?.records || []" :key="r.id" :value="r.id">{{ r.checkDay }} · {{ r.phaseName }} · {{ resultText(r.result) }}</option>
              </select></div>
              <div class="f-item"><label>改为</label><select v-model="form.result"><option value="pass">合格</option><option value="abnormal">异常</option></select></div>
              <div class="f-item"><label>检查时间（可留空保持原值）</label><input v-model="form.checkTime" type="text" placeholder="YYYY-MM-DD HH:mm:ss"></div>
              <div class="f-item"><label>异常说明</label><input v-model="form.abnormalNote" type="text"></div>
            </div>
            <div class="pf-sub" style="margin-top:8px">仅服务商 / 超管可纠错（C8 人工纠错优先），每次修改写入流转日志。</div>
          </template>
        </div>
        <div class="pf-modal-f">
          <button class="btn btn-outline" @click="dlg = ''">取消</button>
          <button class="btn btn-primary" :disabled="busy" @click="submitDlg">确定</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bm-page { display: flex; flex-direction: column; gap: 16px; }
.bm-header { align-items: flex-start; }
.bm-header h1 { margin: 0 0 6px; font-size: 22px; }
.bm-meta { display: flex; gap: 8px; align-items: center; margin-top: 10px; flex-wrap: wrap; }
.bm-tables { font-size: 12.5px; color: var(--text-3); }
.bm-filter { flex-wrap: wrap; }
.bm-filter input, .bm-filter select { height: 36px; border: 1px solid var(--border); border-radius: 8px; padding: 0 10px; min-width: 160px; }
.bm-table { width: 100%; min-width: 1120px; }
.bm-table a { cursor: pointer; margin-right: 6px; }
.pf-sub { font-size: 12.5px; color: var(--text-3); font-weight: 400; }
.pf-hint { background: #EFF6FF; border: 1px solid #93C5FD; border-radius: 8px; padding: 10px 14px; font-size: 13px; color: #1e40af; display: flex; align-items: center; gap: 8px; }
.pf-stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.stat-card { background: #fff; border: 1px solid var(--border); border-radius: 10px; padding: 16px 18px; }
.stat-card .num { font-size: 26px; font-weight: 700; color: var(--brand-600, #1a5bb0); line-height: 1.2; }
.stat-card .lbl { font-size: 12.5px; color: var(--text-3); margin-top: 4px; }
.pf-warn { padding: 16px 20px; border-left: 3px solid #F59E0B; }
.pf-warn-list { margin-top: 10px; display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.pf-warn-item { display: flex; align-items: center; gap: 8px; font-size: 13px; padding: 7px 10px; background: #FFFBEB; border-radius: 8px; }
.pf-warn-item .nm { flex: 1; }
.pf-warn-item .dt { color: var(--text-3); font-size: 12px; }
.pf-flow { padding: 16px 20px; }
.pf-flow-row { display: flex; align-items: center; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
.pf-flow-node { display: flex; align-items: center; gap: 6px; border: 1px solid var(--border); border-radius: 20px; padding: 6px 12px; font-size: 13px; }
.pf-flow-node b { color: var(--brand-500, #3b82f6); }
.pf-flow-node.on { border-color: #16a34a; background: #F0FDF4; }
.pf-arrow { color: var(--text-3); }
.pf-bar { width: 90px; height: 7px; background: #E2E8F0; border-radius: 6px; display: inline-block; vertical-align: middle; overflow: hidden; }
.pf-bar-in { height: 100%; background: var(--brand-500, #3b82f6); border-radius: 6px; display: block; }
.pf-bar-in.done { background: #16a34a; }
.pf-pct { font-size: 12.5px; font-weight: 700; margin-left: 8px; }
.pf-mask { position: fixed; inset: 0; background: rgba(20,28,43,.45); z-index: 10000; display: none; justify-content: flex-end; align-items: center; }
.pf-mask.on { display: flex; }
.pf-drawer { width: 940px; max-width: 95vw; height: 100%; background: #F5F7FB; display: flex; flex-direction: column; box-shadow: -8px 0 30px rgba(0,0,0,.15); }
.pf-dh { background: #fff; padding: 18px 24px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: flex-start; }
.pf-dh h2 { margin: 0; font-size: 19px; }
.pf-close { border: none; background: #F1F5F9; width: 32px; height: 32px; border-radius: 8px; cursor: pointer; font-size: 15px; }
.pf-tabs { display: flex; gap: 4px; background: #fff; padding: 0 24px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
.pf-tab { border: none; background: transparent; padding: 12px 14px; font-size: 13.5px; color: var(--text-3); cursor: pointer; border-bottom: 2px solid transparent; }
.pf-tab.on { color: var(--brand-600, #1a5bb0); font-weight: 700; border-bottom-color: var(--brand-600, #1a5bb0); }
.pf-body { flex: 1; overflow: auto; padding: 18px 24px 40px; display: flex; flex-direction: column; gap: 14px; }
.pf-actions, .pf-group { padding: 16px 20px; }
.pf-btn-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 10px; }
.pf-subtable { width: 100%; margin-top: 10px; border-collapse: collapse; }
.pf-subtable th, .pf-subtable td { padding: 7px 10px; border-bottom: 1px solid var(--border); font-size: 13px; text-align: left; }
.pf-subtable th { background: #F8FAFC; color: var(--text-3); font-weight: 600; }
.pf-subtable input, .pf-subtable select { width: 100%; height: 30px; border: 1px solid var(--border); border-radius: 6px; padding: 0 6px; }
.pf-day { border: 1px solid var(--border); border-radius: 8px; padding: 12px 14px; margin-top: 10px; background: #fff; }
.pf-day-h { display: flex; align-items: center; gap: 10px; font-size: 14px; }
.pf-ref-row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.pf-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
.pf-form-grid .f-item { display: flex; flex-direction: column; gap: 5px; }
.pf-form-grid label { font-size: 12.5px; color: var(--text-3); }
.pf-form-grid input, .pf-form-grid select { height: 34px; border: 1px solid var(--border); border-radius: 8px; padding: 0 9px; }
.pf-pick { border: 1px solid var(--border); border-radius: 8px; background: #fff; max-height: 150px; overflow: auto; margin-top: 4px; }
.pf-pick a { display: block; padding: 7px 10px; font-size: 13px; cursor: pointer; border-bottom: 1px solid #F1F5F9; }
.pf-pick a:hover { background: #EFF6FF; }
.pf-modal { width: 720px; max-width: 94vw; max-height: 88vh; margin: auto; background: #fff; border-radius: 12px; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,.25); }
.pf-modal-h { padding: 16px 22px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; }
.pf-modal-h h3 { margin: 0; font-size: 17px; }
.pf-modal-b { padding: 18px 22px; overflow: auto; }
.pf-modal-f { padding: 14px 22px; border-top: 1px solid var(--border); display: flex; justify-content: flex-end; gap: 10px; }
.pf-switch { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 12px 0; border-bottom: 1px solid var(--border); font-size: 14px; }
.pf-switch select { height: 34px; border: 1px solid var(--border); border-radius: 8px; padding: 0 8px; }
.pf-switch input[type=checkbox] { width: 20px; height: 20px; }
</style>
