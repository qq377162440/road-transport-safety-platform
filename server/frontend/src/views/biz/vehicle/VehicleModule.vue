<script setup>
/** B2 车辆基础信息 · 一车一档完整闭环（详细设计 10）
 *  状态机：0缺 → 1齐 → 2已过期；13 项按静态生成4/证件6/动态台账3 分组逐项操作；
 *  齐全判定同时读状态表与真实子表（证件/保险/设备/等级/维修），同源数据自动点亮；
 *  子表：vehicle_doc 证件上传自动落归档项；PDF 生成并登记到电子档案柜盒一；
 *  B2 特有：车挂关系（一挂一主）、人车绑定、停运过滤三检、危货差异校验；
 *  联动：三表联合到期预警、车辆作为各模块选车数据源、小程序「我绑定的车辆」同源。 */
import { computed, onMounted, ref } from 'vue'
import request from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import Icon from '@/components/Icon.vue'

const overview = ref({ list: [], total: 0, complete: 0, pending: 0, expiring: 0, stopped: 0, trailer: 0, tractor: 0 })
const expiringList = ref([])
const keyword = ref('')
const bizFilter = ref('')
const statusFilter = ref('')
const loading = ref(false)

const filtered = computed(() => (overview.value.list || []).filter((v) => {
  const k = keyword.value.trim()
  if (k && !(String(v.plate_no || '') + String(v.vin || '') + String(v.vehicle_no || '')).includes(k)) return false
  if (bizFilter.value && v.biz_type !== bizFilter.value) return false
  if (statusFilter.value && (v.business_status || 'normal') !== statusFilter.value) return false
  return true
}))

async function load() {
  loading.value = true
  try {
    const [o, e] = await Promise.all([
      request.get('/flow/vehicle/overview'),
      request.get('/flow/vehicle/expiring', { params: { days: 30 } }),
    ])
    overview.value = o.data || {}
    expiringList.value = e.data?.list || []
  } catch (e) { /* 拦截器已提示 */ }
  finally { loading.value = false }
}
onMounted(load)

// ---------------- 一车一档详情抽屉 ----------------
const drawer = ref(false)
const detail = ref(null)
const refs = ref([])
const optionCount = ref(0)
// 雪花 ID 由后端序列化为字符串，必须全程用字符串传递（Number() 会超出 JS 安全整数范围丢精度）
const curId = ref('')
const busy = ref(false)
const tab = ref('archive')   // archive / link / bind / sub

async function openArchive(row) {
  curId.value = String(row.id)
  tab.value = 'archive'
  drawer.value = true
  await refreshDetail()
  try {
    const [r, o] = await Promise.all([
      request.get(`/flow/vehicle/${curId.value}/references`),
      request.get('/flow/vehicle/options'),
    ])
    refs.value = r.data || []
    optionCount.value = (o.data || []).length
  } catch { refs.value = [] }
}
async function refreshDetail() {
  try { detail.value = (await request.get(`/flow/vehicle/${curId.value}/archive`)).data }
  catch { detail.value = null }
  await load()
}
function groupEntries() {
  const g = detail.value?.groups || {}
  return Object.entries(g).filter(([, v]) => v && v.length)
}
function statusText(it) {
  if (it.status === 1) return it.fromReal ? '已齐全 · 子表同源' : '已齐全'
  if (it.status === 2) return '已过期'
  return it.optional ? '选填 · 缺' : '待补齐'
}
function statusClass(it) {
  if (it.status === 1) return 'tag-green'
  if (it.status === 2) return 'tag-red'
  return 'tag-yellow'
}
function expireText(it) {
  if (it.daysLeft === null || it.daysLeft === undefined) return it.expireDate ? String(it.expireDate).slice(0, 10) : '长期'
  if (it.daysLeft < 0) return `已过期 ${-it.daysLeft} 天`
  return `${it.daysLeft} 天后到期`
}
function sourceText(it) {
  if (it.sourceType === 'upload') return '证件扫描件'
  if (it.sourceType === 'print') return '空白表手写'
  if (it.sourceType === 'ledger') return '动态台账'
  return '系统范本生成'
}

async function markDone(it) {
  busy.value = true
  try {
    const r = await request.put(`/flow/vehicle/${curId.value}/archive/${it.itemCode}`, { status: 1 })
    if (r.data?.ok === false) { ElMessage.warning(r.data.msg || '操作失败'); return }
    ElMessage.success(`已登记「${it.itemName}」，完整度 ${r.data.percent}%`)
    await refreshDetail()
  } finally { busy.value = false }
}
async function generate(it, blank = false) {
  busy.value = true
  try {
    const r = await request.post(`/flow/vehicle/${curId.value}/generate`, { itemCode: it.itemCode, blank })
    if (r.data?.ok === false) { ElMessage.warning(r.data.msg || '生成失败'); return }
    ElMessage.success(`已生成${blank ? '空白表' : '成品 PDF'}并归档到盒一，完整度 ${r.data.percent}%`)
    await refreshDetail()
  } finally { busy.value = false }
}
async function viewPdf(it) {
  try {
    const blob = await request.get(`/flow/vehicle/${curId.value}/archive/${it.itemCode}/pdf`, { responseType: 'blob' })
    const b = blob instanceof Blob ? blob : new Blob([blob], { type: 'application/pdf' })
    const url = URL.createObjectURL(b)
    window.open(url, '_blank')
    setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch (e) { ElMessage.warning('PDF 预览失败') }
}

// ---------------- 证件上传（vehicle_doc 子表 → 自动落归档项） ----------------
const docDialog = ref(false)
const docForm = ref({ itemCode: '', docName: '', docCode: '', fileUrl: '', issueDate: '', expireDate: '' })
const uploading = ref(false)

function openDoc(it) {
  docForm.value = {
    itemCode: it.itemCode, docName: it.itemName, docCode: it.itemCode,
    fileUrl: '', issueDate: '', expireDate: '',
  }
  docDialog.value = true
}
async function onFile(e) {
  const f = e.target.files?.[0]
  if (!f) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', f)
    fd.append('bizModule', 'vehicle')
    fd.append('bizId', String(curId.value))
    const r = await request.post('/file/upload', fd)
    docForm.value.fileUrl = r.data?.objectKey || r.data?.url || ''
    ElMessage.success('扫描件已上传')
  } finally { uploading.value = false; e.target.value = '' }
}
async function submitDoc() {
  if (!docForm.value.fileUrl) { ElMessage.warning('请先上传扫描件或填写文件地址'); return }
  busy.value = true
  try {
    const r = await request.post(`/flow/vehicle/${curId.value}/doc`, docForm.value)
    if (r.data?.ok === false) { ElMessage.warning(r.data.msg || '登记失败'); return }
    ElMessage.success(`证件已登记并自动落入归档项，完整度 ${r.data.percent}%`)
    docDialog.value = false
    await refreshDetail()
  } finally { busy.value = false }
}

// ---------------- 车挂关系 ----------------
const linkDialog = ref(false)
const trailerOptions = ref([])
const linkForm = ref({ trailerId: '' })
async function openLink() {
  try {
    const all = (await request.get('/flow/vehicle/options')).data || []
    trailerOptions.value = all.filter((x) => String(x.is_trailer) === '1')
  } catch { trailerOptions.value = [] }
  linkForm.value = { trailerId: '' }
  linkDialog.value = true
}
async function submitLink() {
  if (!linkForm.value.trailerId) { ElMessage.warning('请选择挂车'); return }
  busy.value = true
  try {
    const r = await request.post(`/flow/vehicle/${curId.value}/link`, { trailerId: linkForm.value.trailerId })
    if (r.data?.ok === false) { ElMessage.warning(r.data.msg || '建立关联失败'); return }
    ElMessage.success('车挂关联已建立（一挂一主）')
    linkDialog.value = false
    await refreshDetail()
  } finally { busy.value = false }
}
async function removeLink(row) {
  try { await ElMessageBox.confirm('确认解除该牵引车与挂车的关联？', '提示', { type: 'warning' }) }
  catch (e) { return }
  await request.delete(`/flow/vehicle/link/${row.id}`)
  ElMessage.success('已解除关联')
  await refreshDetail()
}

// ---------------- 人车绑定 ----------------
const bindDialog = ref(false)
const personOptions = ref([])
const bindForm = ref({ driverId: '', escortId: '' })
async function openBind() {
  try { personOptions.value = (await request.get('/flow/person/options')).data || [] }
  catch { personOptions.value = [] }
  bindForm.value = { driverId: '', escortId: '' }
  bindDialog.value = true
}
async function submitBind() {
  if (!bindForm.value.driverId) { ElMessage.warning('请选择驾驶员'); return }
  busy.value = true
  try {
    const r = await request.post(`/flow/vehicle/${curId.value}/bind`, bindForm.value)
    if (r.data?.ok === false) { ElMessage.warning(r.data.msg || '绑定失败'); return }
    ElMessage.success('人车绑定成功（同车驾驶员唯一）')
    bindDialog.value = false
    await refreshDetail()
  } finally { busy.value = false }
}
async function removeBind(row) {
  try { await ElMessageBox.confirm('确认解绑该驾驶员？', '提示', { type: 'warning' }) }
  catch (e) { return }
  await request.delete(`/flow/vehicle/bind/${row.id}`)
  ElMessage.success('已解绑')
  await refreshDetail()
}

// ---------------- 停运状态 ----------------
async function setStatus(v, status) {
  busy.value = true
  try {
    await request.put(`/flow/vehicle/${v.id}/status`, { status })
    ElMessage.success(status === 'stop' ? '已停运，不再生成一日三检任务' : '营运状态已更新')
    await load()
  } finally { busy.value = false }
}

// ---------------- 车辆主数据 CRUD（通用引擎） ----------------
const dialogVisible = ref(false)
const editing = ref(false)
const editingId = ref('')
const blankForm = () => ({
  plate_no: '', plate_color: '黄', biz_type: 'freight', vehicle_type: '货车',
  vehicle_class: '', is_trailer: 0, vehicle_no: '', brand_model: '', vin: '',
  engine_no: '', owner: '', reg_date: '', business_status: 'normal',
})
const form = ref(blankForm())
const saving = ref(false)

function openCreate() { editing.value = false; form.value = blankForm(); dialogVisible.value = true }
function openEdit(row) {
  editing.value = true; editingId.value = String(row.id)
  form.value = { ...blankForm(), ...row }
  dialogVisible.value = true
}
async function save() {
  if (!form.value.plate_no) { ElMessage.warning('车牌号必填'); return }
  saving.value = true
  try {
    if (editing.value) await request.put(`/biz/vehicle/${editingId.value}`, form.value)
    else await request.post('/biz/vehicle', form.value)
    ElMessage.success('保存成功，已按 13 项归档目录初始化')
    dialogVisible.value = false
    await load()
  } finally { saving.value = false }
}
async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除车辆「${row.plate_no}」？该车将从一日三检、动态监控、危货运单等模块的选车列表中同步移除。`,
      '提示', { type: 'warning' })
  } catch (e) { return }
  await request.delete(`/biz/vehicle/${row.id}`)
  ElMessage.success('已删除')
  await load()
}

function goArchive() { window.location.href = '/archive?box=' + encodeURIComponent('盒一') }
function bizText(v) { return { freight: '普货', danger: '危货', passenger: '客运' }[v] || v || '—' }
function statusLabel(v) { return { normal: '正常营运', stop: '已停运', scrap: '已报废' }[v || 'normal'] || '正常营运' }
function statusTagClass(v) { return v === 'stop' ? 'tag-yellow' : v === 'scrap' ? 'tag-red' : 'tag-green' }
function levelText(l) { return { expired: '已过期', d7: '7天内', d15: '15天内', d30: '30天内' }[l] || l }
</script>

<template>
  <div class="bm-page">
    <div class="pc-header bm-header">
      <div>
        <h1>车辆基础信息</h1>
        <div class="desc">运输车辆静态档案管理 · 一车一档（以档案盒目录为骨架）</div>
        <div class="bm-meta">
          <span class="tag tag-blue">阶段 B2</span>
          <span class="tag tag-gray">基础档案</span>
          <span class="tag tag-green">归档 盒一</span>
          <span class="bm-tables">13 项归档目录 · 5 张子表 · 车挂/人车绑定 · 状态机闭环</span>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-outline" @click="goArchive"><Icon name="archive" :size="15" /> 存档到电子档案柜（盒一）</button>
        <button class="btn btn-outline" @click="load()"><Icon name="refresh" :size="15" /> 刷新</button>
        <button class="btn btn-primary" @click="openCreate"><Icon name="plus" :size="15" /> 新增车辆</button>
      </div>
    </div>

    <div class="pf-hint"><Icon name="info" :size="15" />
      车/挂分离维护；证照、保险、设备、等级评定、维修保养任一子表有数据即自动点亮对应归档项；
      危货车辆自动启用「各类检验报告多份 + 承运人责任险」校验；停运车辆不进入一日三检但完整度仍计算。
    </div>

    <div class="pf-stat-row">
      <div class="stat-card"><div class="num">{{ overview.total || 0 }}</div><div class="lbl">在册车辆（整车 {{ overview.tractor || 0 }} / 挂车 {{ overview.trailer || 0 }}）</div></div>
      <div class="stat-card"><div class="num" style="color:var(--ok,#16a34a)">{{ overview.complete || 0 }}</div><div class="lbl">一车一档完整</div></div>
      <div class="stat-card"><div class="num" style="color:var(--danger,#dc2626)">{{ overview.pending || 0 }}</div><div class="lbl">档案待补齐</div></div>
      <div class="stat-card"><div class="num" style="color:#d97706">{{ overview.expiring || 0 }}</div><div class="lbl">证照/保险/等级临期</div></div>
    </div>

    <div v-if="expiringList.length" class="card pf-warn">
      <div class="card-title">三表联合到期预警 <span class="pf-sub">证照 · 保险 · 等级评定，30 / 15 / 7 天分级，同步推送小程序消息与驾驶舱</span></div>
      <div class="pf-warn-list">
        <div v-for="(d, i) in expiringList.slice(0, 8)" :key="d.vehicleId + i" class="pf-warn-item">
          <span class="tag" :class="d.level === 'expired' ? 'tag-red' : 'tag-yellow'">{{ levelText(d.level) }}</span>
          <span class="nm">{{ d.plate_no }} · {{ d.doc_name }} <span class="pf-sub">({{ d.source }})</span></span>
          <span class="dt">{{ String(d.expire_date || '').slice(0, 10) }}（{{ d.daysLeft < 0 ? '已过期 ' + (-d.daysLeft) + ' 天' : d.daysLeft + ' 天后' }}）</span>
        </div>
      </div>
    </div>

    <div class="filter-bar bm-filter">
      <div class="f-item"><label>关键字</label><input v-model="keyword" type="text" placeholder="车牌 / VIN / 车辆编号"></div>
      <div class="f-item"><label>营运类型</label>
        <select v-model="bizFilter">
          <option value="">全部</option><option value="freight">普货</option><option value="danger">危货</option><option value="passenger">客运</option>
        </select>
      </div>
      <div class="f-item"><label>营运状态</label>
        <select v-model="statusFilter">
          <option value="">全部</option><option value="normal">正常营运</option><option value="stop">已停运</option><option value="scrap">已报废</option>
        </select>
      </div>
      <div class="f-actions">
        <button class="btn btn-primary btn-sm" @click="load()"><Icon name="search" :size="14" /> 查询</button>
        <button class="btn btn-outline btn-sm" @click="keyword = ''; bizFilter = ''; statusFilter = ''">重置</button>
      </div>
    </div>

    <div class="card" style="padding:0;overflow:hidden">
      <div class="card-title" style="padding:16px 20px 0">车辆档案记录
        <span class="pf-sub">共 {{ filtered.length }} 条 · 归档完整度实时计算 · 数据来源：业务真实表</span>
      </div>
      <div style="padding:0 20px 16px">
        <table class="table bm-table" v-loading="loading">
          <thead><tr>
            <th>车牌号</th><th>车/挂</th><th>营运类型</th><th>车辆类型</th><th>营运状态</th>
            <th style="width:170px">归档完整度</th><th>缺项</th><th>临期</th><th style="width:260px">操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="v in filtered" :key="v.id">
              <td><b>{{ v.plate_no }}</b></td>
              <td><span class="tag" :class="String(v.is_trailer) === '1' ? 'tag-yellow' : 'tag-blue'">{{ String(v.is_trailer) === '1' ? '挂车' : '整车' }}</span></td>
              <td>{{ bizText(v.biz_type) }}<span v-if="v.danger" class="tag tag-red" style="margin-left:4px">危</span></td>
              <td>{{ v.vehicle_type || '—' }}</td>
              <td><span class="tag" :class="statusTagClass(v.business_status)">{{ statusLabel(v.business_status) }}</span></td>
              <td>
                <div class="pf-bar"><div class="pf-bar-in" :class="v.percent >= 100 ? 'done' : ''" :style="{ width: (v.percent || 0) + '%' }"></div></div>
                <span class="pf-pct">{{ v.percent || 0 }}%</span>
              </td>
              <td><span class="tag" :class="v.missingCount ? 'tag-yellow' : 'tag-green'">{{ v.missingCount ? v.missingCount + ' 项' : '齐全' }}</span></td>
              <td><span class="tag" :class="v.expiring ? 'tag-red' : 'tag-gray'">{{ v.expiring ? v.expiring + ' 项' : '正常' }}</span></td>
              <td>
                <a class="btn btn-primary btn-sm" @click="openArchive(v)">一车一档</a>
                <a class="btn btn-outline btn-sm" @click="setStatus(v, (v.business_status || 'normal') === 'stop' ? 'normal' : 'stop')">{{ (v.business_status || 'normal') === 'stop' ? '复运' : '停运' }}</a>
                <a class="btn btn-outline btn-sm" @click="openEdit(v)">编辑</a>
                <a class="btn btn-outline btn-sm" @click="remove(v)">删除</a>
              </td>
            </tr>
            <tr v-if="!filtered.length && !loading">
              <td colspan="9" style="text-align:center;color:var(--text-3);padding:28px">暂无车辆，点右上角「新增车辆」录入</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ============ 一车一档详情抽屉（四 Tab） ============ -->
    <div class="pf-mask" :class="{ on: drawer }" @click.self="drawer = false">
      <div class="pf-drawer">
        <div class="pf-dh">
          <div>
            <h2>一车一档 · {{ detail?.vehicle?.plate_no || '—' }}</h2>
            <div class="pf-sub">{{ detail?.vehicle?.brand_model || '' }} · {{ bizText(detail?.vehicle?.biz_type) }} · {{ String(detail?.vehicle?.is_trailer) === '1' ? '挂车' : '整车' }} · 归档盒一 <span v-if="detail?.danger" class="tag tag-red">危货校验</span></div>
          </div>
          <button class="pf-close" @click="drawer = false">✕</button>
        </div>
        <div class="pf-tabs">
          <button v-for="t in [['archive','一车一档 13 项'],['link','车挂关系'],['bind','人车绑定'],['sub','证照·保险·设备·等级·维修']]" :key="t[0]"
                  class="pf-tab" :class="{ on: tab === t[0] }" @click="tab = t[0]">{{ t[1] }}</button>
        </div>
        <div v-if="detail" class="pf-body" v-loading="busy">
          <!-- Tab 1: 13 项归档 -->
          <template v-if="tab === 'archive'">
            <div class="pf-progress">
              <div class="pf-prog-num">{{ detail.percent }}<span>%</span></div>
              <div class="pf-prog-info">
                <div class="pf-prog-title">归档完整度（{{ detail.done }} / {{ detail.required }} 项必备）</div>
                <div class="pf-bar big"><div class="pf-bar-in" :class="detail.percent >= 100 ? 'done' : ''" :style="{ width: detail.percent + '%' }"></div></div>
                <div class="pf-sub">缺 {{ (detail.missing || []).length }} 项 · 已过期 {{ detail.expired }} 项 · 证件/保险/设备/等级/维修子表有数据即自动点亮</div>
              </div>
            </div>

            <div v-for="[g, list] in groupEntries()" :key="g" class="card pf-group">
              <div class="card-title">{{ g }} <span class="pf-sub">{{ list.length }} 项 · {{ g === '静态生成区' ? '有数据生成 PDF / 无数据打印空白表' : g === '证件上传区' ? '上传扫描件（无手写选项）' : '动态台账 / 打印空白登记表' }}</span></div>
              <div v-for="it in list" :key="it.itemCode" class="pf-item">
                <span class="pf-code">{{ it.itemCode }}</span>
                <div class="pf-item-main">
                  <div class="pf-item-name">{{ it.itemName }}
                    <span class="tag" :class="statusClass(it)">{{ statusText(it) }}</span>
                    <span v-if="it.optional" class="tag tag-gray">选填</span>
                  </div>
                  <div class="pf-sub">{{ sourceText(it) }} · 有效期 {{ expireText(it) }}<span v-if="it.realNote" style="color:#0d9488"> · {{ it.realNote }}</span></div>
                </div>
                <div class="pf-item-actions">
                  <button v-if="it.group === '证件上传区'" class="btn btn-primary btn-sm" @click="openDoc(it)">上传证件</button>
                  <template v-else>
                    <button class="btn btn-primary btn-sm" @click="generate(it, false)">生成并归档</button>
                    <button class="btn btn-outline btn-sm" @click="generate(it, true)">空白表</button>
                  </template>
                  <button v-if="it.fileUrl || it.status === 1" class="btn btn-outline btn-sm" @click="viewPdf(it)">查看PDF</button>
                  <button v-if="it.status !== 1" class="btn btn-outline btn-sm" @click="markDone(it)">标记齐全</button>
                </div>
              </div>
            </div>

            <div class="card pf-group">
              <div class="card-title">模块间联动 <span class="pf-sub">本车辆作为以下模块的选车数据源（vehicle 主表同源）</span></div>
              <div class="pf-ref-row">
                <span v-for="r in refs" :key="r.module" class="tag tag-blue">{{ r.name }} · {{ r.count }}</span>
                <span v-if="!refs.length" class="pf-sub">读取中…</span>
              </div>
              <div class="pf-sub" style="margin-top:10px">选车数据源：vehicle 主表共 {{ optionCount }} 台，各模块通过 /api/flow/vehicle/options 同源取数；停运车辆可用 excludeStopped=true 过滤，不生成一日三检任务。</div>
            </div>
          </template>

          <!-- Tab 2: 车挂关系 -->
          <template v-else-if="tab === 'link'">
            <div class="card pf-group">
              <div class="card-title">车挂关系（一挂一主）
                <button class="btn btn-primary btn-sm" style="float:right" @click="openLink"><Icon name="plus" :size="13" /> 建立关联</button>
              </div>
              <div class="pf-sub" style="margin:8px 0">牵引车与挂车建立/解除关联；系统保证同一挂车同一时刻只挂一辆牵引车。</div>
              <div v-if="!(detail.links || []).length" class="pf-sub" style="padding:12px 0">暂无车挂关联记录。</div>
              <div v-for="l in (detail.links || [])" :key="l.id" class="pf-link">
                <span class="tag tag-blue">{{ l.tractor_plate || '牵引车' }}</span>
                <span class="pf-arrow">→</span>
                <span class="tag tag-yellow">{{ l.trailer_plate || '挂车' }}</span>
                <span class="pf-sub" style="flex:1">自 {{ String(l.start_date || '').slice(0, 10) }}</span>
                <button class="btn btn-outline btn-sm" @click="removeLink(l)">解除</button>
              </div>
            </div>
          </template>

          <!-- Tab 3: 人车绑定 -->
          <template v-else-if="tab === 'bind'">
            <div class="card pf-group">
              <div class="card-title">人车绑定（驾驶员唯一 / 危货押运员）
                <button class="btn btn-primary btn-sm" style="float:right" @click="openBind"><Icon name="plus" :size="13" /> 绑定驾驶员</button>
              </div>
              <div class="pf-sub" style="margin:8px 0">驾驶员与押运员来自人员基础信息（B1 同源）；同一辆车当前活跃绑定驾驶员唯一，重绑自动解绑旧的。绑定结果同步到小程序「我绑定的车辆」与一日三检。</div>
              <div v-if="!(detail.binds || []).length" class="pf-sub" style="padding:12px 0">暂无人车绑定记录。</div>
              <div v-for="b in (detail.binds || [])" :key="b.id" class="pf-link">
                <span class="tag tag-green">驾驶员 {{ b.driver_name || b.driver_id }}</span>
                <span v-if="b.escort_id" class="tag tag-blue">押运员 {{ b.escort_name || b.escort_id }}</span>
                <span class="pf-sub" style="flex:1">自 {{ String(b.bind_date || '').slice(0, 10) }}</span>
                <button class="btn btn-outline btn-sm" @click="removeBind(b)">解绑</button>
              </div>
            </div>
          </template>

          <!-- Tab 4: 子表台账 -->
          <template v-else-if="tab === 'sub'">
            <div v-for="s in [
              { key: 'docs', title: '证照扫描件（vehicle_doc）', cols: [['doc_name','名称'],['expire_date','有效期'],['status','状态']] },
              { key: 'insurances', title: '保险（vehicle_insurance）', cols: [['insurance_type','险种'],['policy_no','保单号'],['expire_date','有效期']] },
              { key: 'devices', title: '随车设备清单（vehicle_device）', cols: [['device_name','设备'],['quantity','数量']] },
              { key: 'grades', title: '技术等级评定（vehicle_grade）', cols: [['grade','等级'],['grade_date','评定日期'],['valid_until','有效期至']] },
              { key: 'repairs', title: '维修保养（vehicle_repair）', cols: [['repair_type','类型'],['repair_date','日期'],['repair_org','承修单位']] },
            ]" :key="s.key" class="card pf-group">
              <div class="card-title">{{ s.title }} <span class="pf-sub">{{ (detail[s.key] || []).length }} 条</span></div>
              <table class="table pf-subtable">
                <thead><tr><th v-for="c in s.cols" :key="c[0]">{{ c[1] }}</th></tr></thead>
                <tbody>
                  <tr v-for="r in (detail[s.key] || [])" :key="r.id">
                    <td v-for="c in s.cols" :key="c[0]">{{ r[c[0]] === 1 ? '有效' : r[c[0]] === 0 ? '失效' : (r[c[0]] ?? '—') }}</td>
                  </tr>
                  <tr v-if="!(detail[s.key] || []).length"><td :colspan="s.cols.length" class="pf-sub" style="text-align:center;padding:10px">暂无记录；可在通用台账或本页「上传证件 / 生成并归档」补录，数据同源自动点亮归档项</td></tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- 证件上传弹窗 -->
    <el-dialog v-model="docDialog" title="上传证件扫描件 · 自动落入归档项" width="520px" destroy-on-close>
      <div class="pf-form-grid">
        <div class="form-item"><label class="form-label">归档项</label>
          <input v-model="docForm.docName" class="form-input" disabled></div>
        <div class="form-item"><label class="form-label">扫描件</label>
          <input type="file" class="form-input" @change="onFile" :disabled="uploading">
          <span class="pf-sub">{{ uploading ? '上传中…' : '支持 pdf / 图片，≤20MB' }}</span></div>
        <div class="form-item"><label class="form-label">文件地址</label>
          <input v-model="docForm.fileUrl" class="form-input" placeholder="上传后自动回填，也可手填对象存储路径"></div>
        <div class="form-item"><label class="form-label">发证 / 签订日期</label>
          <input v-model="docForm.issueDate" class="form-input" placeholder="2026-01-01"></div>
        <div class="form-item"><label class="form-label">有效期至</label>
          <input v-model="docForm.expireDate" class="form-input" placeholder="2029-01-01（填后接入 30/15/7 天预警）"></div>
      </div>
      <template #footer>
        <el-button @click="docDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitDoc">登记并归档</el-button>
      </template>
    </el-dialog>

    <!-- 车挂关联弹窗 -->
    <el-dialog v-model="linkDialog" title="建立车挂关联（一挂一主）" width="460px" destroy-on-close>
      <div class="pf-form-grid" style="grid-template-columns:1fr">
        <div class="form-item"><label class="form-label">选择挂车</label>
          <select v-model="linkForm.trailerId" class="form-input">
            <option value="">请选择挂车</option>
            <option v-for="t in trailerOptions" :key="t.id" :value="t.id">{{ t.plate_no }} · {{ t.vehicle_type || '' }}</option>
          </select>
          <span class="pf-sub">仅列出 is_trailer=1 的挂车；已被其他牵引车绑定的挂车会被引擎拒绝。</span></div>
      </div>
      <template #footer>
        <el-button @click="linkDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitLink">建立关联</el-button>
      </template>
    </el-dialog>

    <!-- 人车绑定弹窗 -->
    <el-dialog v-model="bindDialog" title="绑定驾驶员 / 押运员（B1 人员同源）" width="480px" destroy-on-close>
      <div class="pf-form-grid">
        <div class="form-item"><label class="form-label">驾驶员 <span class="req">*</span></label>
          <select v-model="bindForm.driverId" class="form-input">
            <option value="">请选择</option>
            <option v-for="p in personOptions" :key="p.id" :value="p.id">{{ p.name }} · {{ p.telephone || '' }}</option>
          </select></div>
        <div class="form-item"><label class="form-label">押运员（危货）</label>
          <select v-model="bindForm.escortId" class="form-input">
            <option value="">不设置</option>
            <option v-for="p in personOptions" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select></div>
      </div>
      <template #footer>
        <el-button @click="bindDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitBind">绑定</el-button>
      </template>
    </el-dialog>

    <!-- 新增 / 编辑车辆弹窗 -->
    <el-dialog v-model="dialogVisible" :title="(editing ? '编辑' : '新增') + '车辆'" width="680px" destroy-on-close>
      <div class="pf-form-grid">
        <div class="form-item"><label class="form-label">车牌号 <span class="req">*</span></label>
          <input v-model="form.plate_no" class="form-input" placeholder="粤E·12345"></div>
        <div class="form-item"><label class="form-label">车牌颜色</label>
          <select v-model="form.plate_color" class="form-input"><option>黄</option><option>蓝</option><option>绿</option><option>白</option><option>黑</option></select></div>
        <div class="form-item"><label class="form-label">营运类型</label>
          <select v-model="form.biz_type" class="form-input"><option value="freight">普货</option><option value="danger">危货</option><option value="passenger">客运</option></select></div>
        <div class="form-item"><label class="form-label">车 / 挂</label>
          <select v-model="form.is_trailer" class="form-input"><option :value="0">整车（牵引车/货车）</option><option :value="1">挂车</option></select></div>
        <div class="form-item"><label class="form-label">车辆类型</label>
          <input v-model="form.vehicle_type" class="form-input" placeholder="牵引车 / 挂车 / 货车 / 罐车"></div>
        <div class="form-item"><label class="form-label">品牌型号</label>
          <input v-model="form.brand_model" class="form-input" placeholder="解放 CA4250"></div>
        <div class="form-item"><label class="form-label">VIN 车架号</label>
          <input v-model="form.vin" class="form-input" placeholder="17 位车架号"></div>
        <div class="form-item"><label class="form-label">所有人</label>
          <input v-model="form.owner" class="form-input" placeholder="企业全称"></div>
        <div class="form-item"><label class="form-label">注册日期</label>
          <input v-model="form.reg_date" class="form-input" placeholder="2024-01-01"></div>
        <div class="form-item"><label class="form-label">营运状态</label>
          <select v-model="form.business_status" class="form-input"><option value="normal">正常营运</option><option value="stop">停运</option><option value="scrap">报废</option></select></div>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存并初始化归档</el-button>
      </template>
    </el-dialog>
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
.bm-table { width: 100%; }
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
.pf-bar { width: 110px; height: 7px; background: #E2E8F0; border-radius: 6px; display: inline-block; vertical-align: middle; overflow: hidden; }
.pf-bar.big { width: 100%; height: 10px; }
.pf-bar-in { height: 100%; background: var(--brand-500, #3b82f6); border-radius: 6px; transition: width .3s; }
.pf-bar-in.done { background: #16a34a; }
.pf-pct { font-size: 12.5px; font-weight: 700; margin-left: 8px; }
/* 抽屉 */
.pf-mask { position: fixed; inset: 0; background: rgba(20,28,43,.45); z-index: 10000; display: none; justify-content: flex-end; }
.pf-mask.on { display: flex; }
.pf-drawer { width: 920px; max-width: 95vw; height: 100%; background: #F5F7FB; display: flex; flex-direction: column; box-shadow: -8px 0 30px rgba(0,0,0,.15); }
.pf-dh { background: #fff; padding: 18px 24px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: flex-start; }
.pf-dh h2 { margin: 0; font-size: 19px; }
.pf-close { border: none; background: #F1F5F9; width: 32px; height: 32px; border-radius: 8px; cursor: pointer; font-size: 15px; }
.pf-tabs { display: flex; gap: 4px; background: #fff; padding: 0 24px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
.pf-tab { border: none; background: transparent; padding: 12px 14px; font-size: 13.5px; color: var(--text-3); cursor: pointer; border-bottom: 2px solid transparent; }
.pf-tab.on { color: var(--brand-600, #1a5bb0); font-weight: 700; border-bottom-color: var(--brand-600, #1a5bb0); }
.pf-body { flex: 1; overflow: auto; padding: 18px 24px 40px; display: flex; flex-direction: column; gap: 14px; }
.pf-progress { background: #fff; border: 1px solid var(--border); border-radius: 10px; padding: 18px 20px; display: flex; align-items: center; gap: 22px; }
.pf-prog-num { font-size: 40px; font-weight: 800; color: var(--brand-600, #1a5bb0); line-height: 1; }
.pf-prog-num span { font-size: 18px; }
.pf-prog-info { flex: 1; }
.pf-prog-title { font-size: 14px; font-weight: 700; margin-bottom: 8px; }
.pf-group { padding: 16px 20px; }
.pf-item { display: flex; align-items: center; gap: 12px; padding: 11px 0; border-bottom: 1px solid var(--border); }
.pf-item:last-child { border-bottom: none; }
.pf-code { font-size: 12px; font-weight: 700; color: var(--brand-500, #3b82f6); width: 34px; flex-shrink: 0; }
.pf-item-main { flex: 1; min-width: 0; }
.pf-item-name { font-size: 14px; font-weight: 600; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pf-item-actions { display: flex; gap: 6px; flex-shrink: 0; flex-wrap: wrap; justify-content: flex-end; max-width: 340px; }
.pf-ref-row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.pf-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
.pf-form-grid .form-input { width: 100%; }
.pf-link { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px solid var(--border); font-size: 13px; }
.pf-arrow { color: var(--text-3); }
.pf-subtable { width: 100%; margin-top: 10px; }
.pf-subtable th, .pf-subtable td { padding: 7px 10px; border-bottom: 1px solid var(--border); font-size: 13px; text-align: left; }
.pf-subtable th { background: #F8FAFC; color: var(--text-3); font-weight: 600; }
</style>
