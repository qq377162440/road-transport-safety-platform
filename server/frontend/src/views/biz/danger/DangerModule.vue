<script setup>
/** B5 隐患排查治理 · 完整流程闭环（详细设计 13）
 *  状态机：pending 待整改 → rectifying 整改中 → verify 待验收 → closed 已闭环；
 *          验收 reject 退回整改中，超过期限自动标记 overdue 并督办。
 *  8 类排查类型统一汇入 danger_record；排查批次带出系统级清单范本逐项判定，
 *  不合格自动联动生成隐患单 + 整改单（H6）；闭环五要素（隐患情况/检查人/整改情况/整改人/验收人）留痕。
 *  联动：三检异常(B4) / 车辆(B2) / 人员(B1) / 制度(B3) / 电子档案柜盒八(B7)；
 *        小程序仅预留 /expiring、/mine 数据接口，不新增小程序页面。 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/api/request'
import { ElMessage } from 'element-plus'
import Icon from '@/components/Icon.vue'

const router = useRouter()
const ov = ref({ company: '—', stats: {}, flow: [], checkTypes: [], records: [], plans: [], templates: [], board: [], archives: [], refs: [] })
const loading = ref(false)
const busy = ref(false)
const mainTab = ref('record')           // record 隐患清单 / plan 排查批次 / template 清单范本
const statusFilter = ref('')
const typeFilter = ref('')
const keyword = ref('')

const STATUS = {
  pending: ['待整改', 'tag-red'], rectifying: ['整改中', 'tag-yellow'],
  verify: ['待验收', 'tag-blue'], closed: ['已闭环', 'tag-green'], overdue: ['超期', 'tag-red'],
}
function statusText(s) { return (STATUS[s] || ['—', 'tag-gray'])[0] }
function statusClass(s) { return (STATUS[s] || ['—', 'tag-gray'])[1] }
function levelClass(l) { return l === '重大' ? 'tag-red' : l === '较大' ? 'tag-yellow' : 'tag-gray' }
function d10(v) { return v ? String(v).slice(0, 10) : '—' }

async function load() {
  loading.value = true
  try {
    const r = await request.get('/flow/danger/overview', {
      params: { status: statusFilter.value, checkType: typeFilter.value, keyword: keyword.value },
    })
    ov.value = r.data || ov.value
  } catch (e) { /* 拦截器已提示 */ }
  finally { loading.value = false }
}
onMounted(load)

const filteredRecords = computed(() => {
  const k = keyword.value.trim()
  return (ov.value.records || []).filter((r) => {
    if (!k) return true
    return (String(r.title || '') + String(r.plate_no || '') + String(r.finder_name || '')).includes(k)
  })
})

// ---------------- 通用动作 ----------------
async function act(url, method, body, label, withDetail = true) {
  busy.value = true
  try {
    const r = method === 'put' ? await request.put(url, body || {})
      : method === 'delete' ? await request.delete(url, { data: body || {} })
      : await request.post(url, body || {})
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
function goArchive() { router.push('/archive') }
async function doArchive() {
  const r = await act('/flow/danger/archive', 'post', {}, '一键归档装盒八', false)
  if (r) ElMessage.success(`已装 ${r.box}，生成 ${(r.items || []).length} 份 PDF`)
}

// ---------------- 详情抽屉（隐患） ----------------
const drawer = ref(false)
const detail = ref(null)
const tab = ref('info')
const curId = ref('')
const cur = computed(() => (ov.value.records || []).find((x) => x.id === curId.value) || detail.value || {})

async function openDetail(row) {
  curId.value = String(row.id)
  tab.value = 'info'
  drawer.value = true
  await refreshDetail()
}
async function refreshDetail() {
  busy.value = true
  try { detail.value = (await request.get(`/flow/danger/record/${curId.value}`)).data || null }
  catch (e) { detail.value = null }
  finally { busy.value = false }
}

// ---------------- 排查批次详情 ----------------
const planDrawer = ref(false)
const planDetail = ref(null)
async function openPlan(row) {
  planDrawer.value = true
  busy.value = true
  try { planDetail.value = (await request.get(`/flow/danger/plan/${String(row.id)}`)).data || null }
  catch (e) { planDetail.value = null }
  finally { busy.value = false }
}
async function judgeItem(it, result) {
  const r = await act(`/flow/danger/plan/${String(planDetail.value?.id)}/item`, 'put',
    { itemId: String(it.id), result, remark: it.remark || '' }, '判定', false)
  if (r) { ElMessage.success(`联动生成隐患 ${r.created || 0} 条（H6）`); await openPlan({ id: planDetail.value.id }); await load() }
}
async function passAll() {
  const r = await act(`/flow/danger/plan/${String(planDetail.value?.id)}/item`, 'put', { mode: 'pass' }, '一键全部合格', false)
  if (r) { await openPlan({ id: planDetail.value.id }); await load() }
}
async function finishPlan() {
  const r = await act(`/flow/danger/plan/${String(planDetail.value?.id)}/finish`, 'post', {}, '完成排查', false)
  if (r) { await openPlan({ id: planDetail.value.id }); await load() }
}

// ---------------- 弹窗 ----------------
const dlg = ref('')
const form = ref({})
const options = ref({ persons: [], vehicles: [], institutions: [] })
const tplItems = ref([])          // 范本编辑用
async function loadOptions() {
  try { options.value = (await request.get('/flow/danger/options')).data || options.value } catch (e) { }
}
async function openDlg(type, row) {
  dlg.value = type
  await loadOptions()
  const tpls = ov.value.templates || []
  if (type === 'record') {
    form.value = { checkType: 'driver_report', title: '', dangerLevel: '一般', location: '',
      vehicleId: '', finderId: '', rectifyRequire: '', photos: '', voiceUrl: '' }
  } else if (type === 'plan') {
    form.value = { checkType: 'monthly_major', title: '', checkDate: new Date().toISOString().slice(0, 10),
      templateId: (tpls.find((t) => t.checkType === 'monthly_major') || {}).id || '', checkerId: '', remark: '' }
  } else if (type === 'rectify') {
    curId.value = String(row.id)
    form.value = { responsibleId: '', deadline: '', rectifyRequire: '' }
  } else if (type === 'feedback') {
    curId.value = String(row.id)
    form.value = { rectifyNote: '', rectifyPhotos: '' }
  } else if (type === 'accept') {
    curId.value = String(row.id)
    form.value = { acceptResult: 'pass', acceptorId: '', acceptNote: '' }
  } else if (type === 'remind') {
    curId.value = String(row.id)
    form.value = { content: '' }
  } else if (type === 'correct') {
    curId.value = String(row.id)
    form.value = { title: row.title || '', dangerLevel: row.danger_level || '一般', status: row.status || 'pending' }
  } else if (type === 'template') {
    const t = tpls.find((x) => x.checkType === 'monthly_major') || tpls[0]
    form.value = { checkType: 'monthly_major', templateName: '', sourceId: t?.id || '' }
    tplItems.value = (t?.items || []).map((x) => ({ ...x }))
  } else if (type === 'templateEdit' && row) {
    form.value = { id: String(row.id), templateName: row.templateName, checkType: row.checkType, sourceId: '' }
    tplItems.value = (row.items || []).map((x) => ({ ...x }))
  }
}
function onTypeChange() {
  const t = (ov.value.templates || []).find((x) => x.checkType === form.value.checkType)
  form.value.templateId = t?.id || ''
}

async function submitDlg() {
  const f = form.value
  let r = null
  if (dlg.value === 'record') {
    r = await act('/flow/danger/record', 'post', f, '登记隐患', false)
    if (r) ElMessage.success(`隐患已登记，自动联动整改单 ${r.rectifyNo || ''}`)
  } else if (dlg.value === 'plan') {
    r = await act('/flow/danger/plan', 'post', f, '发起排查', false)
    if (r) { const p = (ov.value.plans || []).find((x) => x.id === r.planId); await load(); if (p) await openPlan(p) }
  } else if (dlg.value === 'rectify') {
    r = await act(`/flow/danger/record/${curId.value}/rectify`, 'post', f, '下发整改')
  } else if (dlg.value === 'feedback') {
    r = await act(`/flow/danger/record/${curId.value}/feedback`, 'post', f, '整改反馈')
  } else if (dlg.value === 'accept') {
    r = await act(`/flow/danger/record/${curId.value}/accept`, 'post', f, '验收')
  } else if (dlg.value === 'remind') {
    r = await act(`/flow/danger/record/${curId.value}/remind`, 'post', f, '督办')
  } else if (dlg.value === 'correct') {
    r = await act(`/flow/danger/record/${curId.value}`, 'put', f, '超管纠错')
  } else if (dlg.value === 'template') {
    const body = { checkType: f.checkType, templateName: f.templateName || (f.checkType + ' 定制清单'),
      sourceId: f.sourceId, items: tplItems.value }
    r = await act('/flow/danger/template', 'post', body, '保存企业自定义范本', false)
  } else if (dlg.value === 'templateEdit') {
    r = await act(`/flow/danger/template/${f.id}`, 'put',
      { templateName: f.templateName, items: tplItems.value }, '保存范本', false)
  }
  if (r) dlg.value = ''
}
function addTplItem() { tplItems.value.push({ name: '', category: '基础管理', standard: '', major: false }) }
function delTplItem(idx) { tplItems.value.splice(idx, 1) }
async function delTemplate(row) {
  await act(`/flow/danger/template/${String(row.id)}`, 'delete', {}, '删除范本', false)
}

// 行内快捷动作（隐患清单）：直接弹整改窗，避免与详情抽屉叠加
async function quickRectify(row) { await openDlg('rectify', row) }
</script>

<template>
  <div class="bm-page">
    <div class="page-header bm-header">
      <div>
        <h1>隐患排查治理</h1>
        <div class="desc">隐患排查、登记、整改、验收、闭环 · 三检异常 / 定期专项 / 司机上报统一治理 · 闭环率计入企业安全分</div>
        <div class="bm-meta">
          <span class="tag tag-blue">B5 · 盒八 / 危货盒九</span>
          <span class="tag tag-green">8 类排查类型</span>
          <span class="tag tag-yellow">闭环率 {{ ov.stats?.closureRate ?? 0 }}%</span>
          <span class="bm-tables">{{ ov.company }} · {{ ov.stats?.total || 0 }} 条隐患 · {{ ov.stats?.plans || 0 }} 次排查 · 小程序仅预留数据接口</span>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-primary" @click="openDlg('plan')"><Icon name="plus" :size="15" /> 发起检查</button>
        <button class="btn btn-outline" @click="openDlg('record')"><Icon name="alertTriangle" :size="15" /> 登记隐患</button>
        <button class="btn btn-outline" @click="openDlg('template')"><Icon name="clipboard" :size="15" /> 范本定制</button>
        <button class="btn btn-outline" @click="openPdf('/flow/danger/ledger/pdf')"><Icon name="fileText" :size="15" /> 治理台账</button>
        <button class="btn btn-outline" :disabled="busy" @click="doArchive"><Icon name="archive" :size="15" /> 一键归档</button>
        <button class="btn btn-outline" @click="goArchive"><Icon name="folder" :size="15" /> 电子档案柜</button>
        <button class="btn btn-outline" @click="load()"><Icon name="refresh" :size="15" /> 刷新</button>
      </div>
    </div>

    <div class="pf-hint"><Icon name="info" :size="15" />
      发现隐患即自动联动生成整改单（H6），闭环五要素「隐患情况 / 检查人 / 整改情况 / 整改人 / 验收人」全程留痕；
      超期未闭环自动标记并督办；排查清单重大事故隐患项为一票否决类，判定不合格自动建重大隐患。
    </div>

    <div class="pf-stat-row">
      <div class="stat-card"><div class="num">{{ ov.stats?.total || 0 }}</div><div class="lbl">隐患总数 / 重大 {{ ov.stats?.major || 0 }}</div></div>
      <div class="stat-card"><div class="num" style="color:#dc2626">{{ ov.stats?.pending || 0 }}</div><div class="lbl">待整改 · 整改中 {{ ov.stats?.rectifying || 0 }}</div></div>
      <div class="stat-card"><div class="num" style="color:#2563eb">{{ ov.stats?.verify || 0 }}</div><div class="lbl">待验收 · 未闭环 {{ ov.stats?.open || 0 }}</div></div>
      <div class="stat-card"><div class="num" style="color:#16a34a">{{ ov.stats?.closureRate ?? 0 }}%</div><div class="lbl">闭环率 · 已闭环 {{ ov.stats?.closed || 0 }} / 超期 {{ ov.stats?.overdue || 0 }}</div></div>
    </div>

    <div class="card pf-flow">
      <div class="card-title">隐患闭环状态机 <span class="pf-sub">逐条操作 · 每次流转留痕 · 验收不通过退回整改 · 超期自动督办</span></div>
      <div class="pf-flow-row">
        <template v-for="(f, i) in ov.flow || []" :key="f.code">
          <div class="pf-flow-node" :class="{ on: (ov.stats?.[f.code] || 0) > 0 }"><b>{{ i + 1 }}</b><span>{{ f.name }}（{{ ov.stats?.[f.code] || 0 }}）</span></div>
          <span v-if="i < (ov.flow || []).length - 1" class="pf-arrow">→</span>
        </template>
        <span class="pf-sub" style="margin-left:12px">验收退回 → 整改中　·　超期 → 自动标记 + 督办</span>
      </div>
    </div>

    <div v-if="(ov.board || []).length" class="card pf-warn">
      <div class="card-title">督办看板 <span class="pf-sub">未闭环隐患持续盯梢 · 超期高亮</span></div>
      <div class="pf-warn-list">
        <div v-for="w in (ov.board || []).slice(0, 8)" :key="w.id" class="pf-warn-item">
          <span class="tag" :class="w.status === 'overdue' ? 'tag-red' : 'tag-yellow'">{{ w.statusName }}</span>
          <span class="nm">{{ w.title }}</span>
          <span class="dt">{{ w.danger_level }} · 督办 {{ w.remind_count || 0 }} 次</span>
          <a class="btn btn-outline btn-sm" @click="openDetail(w)">处理</a>
        </div>
      </div>
    </div>

    <div class="filter-bar bm-filter">
      <div class="f-item"><label>状态</label>
        <select v-model="statusFilter">
          <option value="">全部</option><option value="pending">待整改</option>
          <option value="rectifying">整改中</option><option value="verify">待验收</option>
          <option value="closed">已闭环</option><option value="overdue">超期</option>
        </select>
      </div>
      <div class="f-item"><label>排查类型</label>
        <select v-model="typeFilter">
          <option value="">全部</option>
          <option v-for="t in ov.checkTypes || []" :key="t.code" :value="t.code">{{ t.name }}</option>
        </select>
      </div>
      <div class="f-item"><label>隐患名称 / 车牌</label><input v-model="keyword" type="text" placeholder="模糊搜索"></div>
      <div class="f-actions">
        <button class="btn btn-primary btn-sm" @click="load()"><Icon name="search" :size="14" /> 查询</button>
        <button class="btn btn-outline btn-sm" @click="keyword = ''; statusFilter = ''; typeFilter = ''; load()">重置</button>
      </div>
    </div>

    <div class="card" style="padding:0;overflow:hidden">
      <div class="card-title" style="padding:16px 20px 0;display:flex;align-items:center;gap:8px">
        <button class="pf-tab" :class="{ on: mainTab === 'record' }" @click="mainTab = 'record'">隐患清单</button>
        <button class="pf-tab" :class="{ on: mainTab === 'plan' }" @click="mainTab = 'plan'">排查批次</button>
        <button class="pf-tab" :class="{ on: mainTab === 'template' }" @click="mainTab = 'template'">清单范本</button>
        <span class="pf-sub" style="margin-left:auto">排查记录 + 整改通知书 + 验收单 → 归档盒八</span>
      </div>

      <!-- 隐患清单 -->
      <div v-if="mainTab === 'record'" style="overflow:auto">
        <table class="table bm-table" v-loading="loading">
          <thead><tr>
            <th>隐患名称 / 描述</th><th>排查类型</th><th>等级</th><th>位置 / 车辆</th>
            <th>检查人</th><th>发现时间</th><th>状态</th><th>操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="r in filteredRecords" :key="r.id">
              <td><b>{{ r.title || '—' }}</b></td>
              <td>{{ r.checkTypeName || '—' }}</td>
              <td><span class="tag" :class="levelClass(r.danger_level)">{{ r.danger_level || '—' }}</span></td>
              <td>{{ r.location || r.plate_no || '—' }}</td>
              <td>{{ r.finder_name || '—' }}</td>
              <td>{{ d10(r.find_time) }}</td>
              <td><span class="tag" :class="statusClass(r.status)">{{ r.statusName || statusText(r.status) }}</span></td>
              <td>
                <a class="btn btn-primary btn-sm" @click="openDetail(r)">详情</a>
                <a v-if="r.status !== 'closed'" class="btn btn-outline btn-sm" @click="quickRectify(r)">处理</a>
              </td>
            </tr>
            <tr v-if="!filteredRecords.length"><td colspan="8" style="text-align:center;color:var(--text-3);padding:26px">暂无隐患记录，点右上「发起检查」或「登记隐患」开始</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 排查批次 -->
      <div v-else-if="mainTab === 'plan'" style="overflow:auto">
        <table class="table bm-table" v-loading="loading">
          <thead><tr>
            <th>检查名称</th><th>排查类型</th><th>清单范本</th><th>检查日期</th>
            <th>检查项目</th><th>整改项</th><th>状态</th><th>操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="p in ov.plans || []" :key="p.id">
              <td><b>{{ p.title }}</b><span class="pf-sub" style="display:block">{{ p.plan_no }}</span></td>
              <td>{{ p.checkTypeName }}</td>
              <td>{{ p.template_name || '—' }}</td>
              <td>{{ d10(p.check_date) }}</td>
              <td>{{ p.item_total || 0 }}</td>
              <td><span class="tag" :class="(p.problems || p.problem_total) ? 'tag-red' : 'tag-gray'">{{ p.problems || p.problem_total || 0 }}</span></td>
              <td><span class="tag" :class="p.status === 'closed' ? 'tag-green' : p.status === 'done' ? 'tag-blue' : 'tag-yellow'">{{ p.status === 'done' ? '排查完成' : p.status === 'closed' ? '全部闭环' : '排查中' }}</span></td>
              <td>
                <a class="btn btn-primary btn-sm" @click="openPlan(p)">逐项判定</a>
                <a class="btn btn-outline btn-sm" @click="openPdf('/flow/danger/plan/' + String(p.id) + '/pdf')">排查记录</a>
              </td>
            </tr>
            <tr v-if="!(ov.plans || []).length"><td colspan="8" style="text-align:center;color:var(--text-3);padding:26px">暂无排查批次，点「发起检查」选择类型自动带出清单范本</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 清单范本 -->
      <div v-else style="overflow:auto">
        <table class="table bm-table" v-loading="loading">
          <thead><tr><th>清单名称</th><th>适用排查类型</th><th>范围</th><th>检查项数</th><th>重大项</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="t in ov.templates || []" :key="t.id">
              <td><b>{{ t.templateName }}</b></td>
              <td>{{ t.checkTypeName }}</td>
              <td><span class="tag" :class="t.scope === 'system' ? 'tag-blue' : 'tag-green'">{{ t.scope === 'system' ? '系统级范本' : '企业自定义' }}</span></td>
              <td>{{ (t.items || []).length }}</td>
              <td><span class="tag" :class="(t.items || []).some((x) => x.major) ? 'tag-red' : 'tag-gray'">{{ (t.items || []).filter((x) => x.major).length }}</span></td>
              <td>
                <a v-if="t.scope === 'enterprise'" class="btn btn-outline btn-sm" @click="openDlg('templateEdit', t)">编辑</a>
                <a v-if="t.scope === 'enterprise'" class="btn btn-outline btn-sm" @click="delTemplate(t)">删除</a>
                <a class="btn btn-outline btn-sm" @click="openDlg('template')">复制为企业范本</a>
              </td>
            </tr>
            <tr v-if="!(ov.templates || []).length"><td colspan="6" style="text-align:center;color:var(--text-3);padding:26px">系统级范本将在首次访问时自动初始化</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 联动引用 -->
    <div class="card pf-group">
      <div class="card-title">模块间联动 <span class="pf-sub">三检异常自动建单 · 车辆/人员/制度同源 · 归档盒八</span></div>
      <div class="pf-ref-row">
        <span v-for="r in ov.refs || []" :key="r.module" class="tag tag-gray">{{ r.name }} · {{ r.usage }}（{{ r.count }}）</span>
      </div>
    </div>

    <!-- ============ 隐患详情抽屉 ============ -->
    <div class="pf-mask" :class="{ on: drawer }" @click.self="drawer = false">
      <div class="pf-drawer">
        <div class="pf-dh">
          <div>
            <h2>{{ (detail || cur).title || '隐患详情' }}</h2>
            <div class="pf-sub">{{ ov.company }} · {{ (detail || cur).checkTypeName || '' }}
              <span class="tag" :class="statusClass((detail || cur).status)" style="margin-left:6px">{{ (detail || cur).statusName || statusText((detail || cur).status) }}</span>
              <span class="tag" :class="levelClass((detail || cur).danger_level)" style="margin-left:4px">{{ (detail || cur).danger_level }}</span>
            </div>
          </div>
          <button class="pf-close" @click="drawer = false">✕</button>
        </div>
        <div class="pf-tabs">
          <button v-for="t in [['info','隐患情况 / 五要素'],['rectify','整改单'],['accept','验收记录'],['log','流转时间线'],['remind','督办记录'],['ref','PDF 与归档']]" :key="t[0]"
                  class="pf-tab" :class="{ on: tab === t[0] }" @click="tab = t[0]">{{ t[1] }}</button>
        </div>
        <div v-if="detail" class="pf-body" v-loading="busy">
          <div class="card pf-actions">
            <div class="card-title">逐条操作 <span class="pf-sub">当前 {{ statusText(detail.status) }} · 每次流转写日志</span></div>
            <div class="pf-btn-row">
              <a class="btn btn-primary btn-sm" @click="openDlg('rectify', detail)">下发整改</a>
              <a class="btn btn-outline btn-sm" @click="openDlg('feedback', detail)">整改反馈</a>
              <a class="btn btn-outline btn-sm" @click="openDlg('accept', detail)">验收（通过/退回）</a>
              <a class="btn btn-outline btn-sm" @click="openDlg('remind', detail)">督办提醒</a>
              <a class="btn btn-outline btn-sm" @click="openDlg('correct', detail)">超管纠错</a>
              <a class="btn btn-outline btn-sm" @click="openPdf('/flow/danger/record/' + curId + '/pdf')">闭环单 PDF</a>
            </div>
          </div>

          <!-- Tab1 隐患情况 + 五要素 -->
          <template v-if="tab === 'info'">
            <div class="card pf-group">
              <div class="card-title">闭环五要素（H7） <span class="pf-sub">隐患情况 / 检查人 / 整改情况 / 整改人 / 验收人，缺一不可</span></div>
              <table class="pf-subtable">
                <tbody>
                  <tr><th>隐患情况</th><td colspan="3">{{ detail.five?.dangerInfo || detail.title }}</td></tr>
                  <tr><th>检查人</th><td>{{ detail.five?.finder || '—' }}</td><th>整改人</th><td>{{ detail.five?.responsible || '—' }}</td></tr>
                  <tr><th>整改情况</th><td>{{ detail.five?.rectifyInfo || '—' }}</td><th>验收人</th><td>{{ detail.five?.acceptor || '—' }}</td></tr>
                </tbody>
              </table>
            </div>
            <div class="card pf-group">
              <div class="card-title">隐患登记信息</div>
              <table class="pf-subtable">
                <tbody>
                  <tr><th>隐患名称</th><td colspan="3">{{ detail.title || '—' }}</td></tr>
                  <tr><th>排查类型</th><td>{{ detail.checkTypeName }}</td><th>隐患等级</th><td>{{ detail.danger_level || '—' }}</td></tr>
                  <tr><th>隐患位置</th><td>{{ detail.location || '—' }}</td><th>关联车辆</th><td>{{ detail.plate_no || '—' }}</td></tr>
                  <tr><th>发现时间</th><td>{{ detail.find_time || '—' }}</td><th>整改期限</th><td>{{ detail.deadline || '—' }}</td></tr>
                </tbody>
              </table>
            </div>
          </template>

          <!-- Tab2 整改单 -->
          <template v-if="tab === 'rectify'">
            <div class="card pf-group">
              <div class="card-title">整改通知书（H6 登记隐患时自动联动生成） <span class="pf-sub">共 {{ (detail.rectifies || []).length }} 张</span></div>
              <div v-for="r in detail.rectifies || []" :key="r.id" class="pf-day">
                <div class="pf-day-h"><b>{{ r.rectify_no }}</b><span class="pf-sub">整改人 {{ r.responsible_name || '未指定' }} · 期限 {{ d10(r.deadline) }}</span></div>
                <table class="pf-subtable"><tbody>
                  <tr><th>整改要求</th><td colspan="3">{{ r.rectify_require || '—' }}</td></tr>
                  <tr><th>整改说明</th><td>{{ r.rectify_note || '—' }}</td><th>反馈时间</th><td>{{ r.submit_time || '—' }}</td></tr>
                </tbody></table>
              </div>
              <div v-if="!(detail.rectifies || []).length" class="pf-sub" style="padding:14px">暂无整改单</div>
            </div>
          </template>

          <!-- Tab3 验收记录 -->
          <template v-if="tab === 'accept'">
            <div class="card pf-group">
              <div class="card-title">验收记录（可多次：退回后重新验收） <span class="pf-sub">共 {{ (detail.accepts || []).length }} 条</span></div>
              <table class="pf-subtable">
                <thead><tr><th>结果</th><th>验收人</th><th>验收意见</th><th>时间</th></tr></thead>
                <tbody>
                  <tr v-for="a in detail.accepts || []" :key="a.id">
                    <td><span class="tag" :class="a.accept_result === 'pass' ? 'tag-green' : 'tag-red'">{{ a.accept_result === 'pass' ? '通过' : '退回整改' }}</span></td>
                    <td>{{ a.acceptor_name || '—' }}</td><td>{{ a.accept_note || '—' }}</td><td>{{ a.accept_time || '—' }}</td>
                  </tr>
                  <tr v-if="!(detail.accepts || []).length"><td colspan="4" style="text-align:center;color:var(--text-3)">暂无验收记录</td></tr>
                </tbody>
              </table>
            </div>
          </template>

          <!-- Tab4 流转时间线 -->
          <template v-if="tab === 'log'">
            <div class="card pf-group">
              <div class="card-title">状态流转时间线 <span class="pf-sub">全流程留痕</span></div>
              <table class="pf-subtable">
                <thead><tr><th>动作</th><th>状态变化</th><th>说明</th><th>操作人</th><th>时间</th></tr></thead>
                <tbody>
                  <tr v-for="(g, i) in detail.logs || []" :key="i">
                    <td>{{ g.action }}</td><td>{{ g.from_status || '—' }} → {{ g.to_status || '—' }}</td>
                    <td>{{ g.detail || '—' }}</td><td>{{ g.operator || '—' }}</td><td>{{ g.created_at || '—' }}</td>
                  </tr>
                  <tr v-if="!(detail.logs || []).length"><td colspan="5" style="text-align:center;color:var(--text-3)">暂无流转日志</td></tr>
                </tbody>
              </table>
            </div>
          </template>

          <!-- Tab5 督办记录 -->
          <template v-if="tab === 'remind'">
            <div class="card pf-group">
              <div class="card-title">督办提醒记录 <span class="pf-sub">累计 {{ detail.remind_count || 0 }} 次</span></div>
              <table class="pf-subtable">
                <thead><tr><th>类型</th><th>内容</th><th>操作人</th><th>时间</th></tr></thead>
                <tbody>
                  <tr v-for="(m, i) in detail.reminds || []" :key="i">
                    <td><span class="tag" :class="m.remind_type === 'overdue' ? 'tag-red' : 'tag-yellow'">{{ m.remind_type === 'overdue' ? '超期' : m.remind_type === 'near' ? '临期' : '人工' }}</span></td>
                    <td>{{ m.content || '—' }}</td><td>{{ m.operator || '—' }}</td><td>{{ m.created_at || '—' }}</td>
                  </tr>
                  <tr v-if="!(detail.reminds || []).length"><td colspan="4" style="text-align:center;color:var(--text-3)">暂无督办记录</td></tr>
                </tbody>
              </table>
            </div>
          </template>

          <!-- Tab6 PDF 与归档 -->
          <template v-if="tab === 'ref'">
            <div class="card pf-group">
              <div class="card-title">PDF 成品与归档 <span class="pf-sub">隐患排查记录 / 整改通知书 / 验收单 → 盒八</span></div>
              <div class="pf-btn-row">
                <a class="btn btn-outline btn-sm" @click="openPdf('/flow/danger/record/' + curId + '/pdf')">隐患闭环单 PDF</a>
                <a class="btn btn-outline btn-sm" @click="openPdf('/flow/danger/ledger/pdf')">治理台账 PDF</a>
                <a class="btn btn-outline btn-sm" @click="doArchive">一键归档装盒八</a>
              </div>
              <table class="pf-subtable">
                <thead><tr><th>归档成品</th><th>归档日期</th><th>状态</th></tr></thead>
                <tbody>
                  <tr v-for="a in ov.archives || []" :key="a.id"><td>{{ a.title }}</td><td>{{ d10(a.occur_date) }}</td><td>已归档</td></tr>
                  <tr v-if="!(ov.archives || []).length"><td colspan="3" style="text-align:center;color:var(--text-3)">暂无归档记录</td></tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- ============ 排查批次逐项判定抽屉 ============ -->
    <div class="pf-mask" :class="{ on: planDrawer }" @click.self="planDrawer = false">
      <div class="pf-drawer">
        <div class="pf-dh">
          <div>
            <h2>{{ planDetail?.title || '排查批次' }}</h2>
            <div class="pf-sub">{{ ov.company }} · {{ planDetail?.checkTypeName }} · 清单范本 {{ planDetail?.template_name || '—' }}
              · 检查项目 {{ (planDetail?.items || []).length }}</div>
          </div>
          <button class="pf-close" @click="planDrawer = false">✕</button>
        </div>
        <div class="pf-tabs"><button class="pf-tab on">逐项判定（不合格自动联动隐患单）</button></div>
        <div v-if="planDetail" class="pf-body" v-loading="busy">
          <div class="card pf-actions">
            <div class="pf-btn-row">
              <a class="btn btn-primary btn-sm" @click="passAll"><Icon name="check" :size="14" /> 一键全部合格</a>
              <a class="btn btn-outline btn-sm" @click="finishPlan">完成排查</a>
              <a class="btn btn-outline btn-sm" @click="openPdf('/flow/danger/plan/' + String(planDetail.id) + '/pdf')">排查记录 PDF</a>
            </div>
          </div>
          <div class="card pf-group">
            <table class="pf-subtable">
              <thead><tr><th>序号</th><th>项目类别</th><th>检查项目</th><th>检查内容与标准</th><th>结果</th><th>备注 / 操作</th></tr></thead>
              <tbody>
                <tr v-for="(it, idx) in planDetail.items || []" :key="it.id">
                  <td>{{ idx + 1 }}</td>
                  <td><span class="tag" :class="it.is_major == 1 ? 'tag-red' : 'tag-gray'">{{ it.category || '—' }}</span></td>
                  <td><b>{{ it.item_name }}</b></td>
                  <td class="pf-sub">{{ it.standard }}</td>
                  <td><span class="tag" :class="it.result === 'pass' ? 'tag-green' : it.result === 'fail' ? 'tag-red' : 'tag-gray'">{{ it.result === 'pass' ? '合格' : it.result === 'fail' ? '不合格' : '未判定' }}</span>
                    <span v-if="it.dangerId" class="pf-sub" style="display:block">隐患 {{ it.dangerId }}</span></td>
                  <td>
                    <input v-model="it.remark" placeholder="备注/不合格说明" style="margin-bottom:4px">
                    <a v-if="it.result !== 'pass'" class="btn btn-outline btn-sm" @click="judgeItem(it, 'pass')">合格</a>
                    <a v-if="it.result !== 'fail'" class="btn btn-outline btn-sm" @click="judgeItem(it, 'fail')">不合格</a>
                  </td>
                </tr>
                <tr v-if="!(planDetail.items || []).length"><td colspan="6" style="text-align:center;color:var(--text-3)">该批次无检查项</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <!-- ============ 弹窗 ============ -->
    <div class="pf-mask" :class="{ on: dlg }" @click.self="dlg = ''" style="justify-content:center;align-items:center">
      <div class="pf-modal" @click.stop>
        <div class="pf-modal-h">
          <h3>{{ {
            record: '登记隐患（自动联动整改单）', plan: '发起排查（自动带出系统级清单范本）',
            rectify: '下发整改', feedback: '整改反馈（上传凭证）', accept: '验收销号',
            remind: '督办提醒', correct: '超管纠错', template: '复制/新建企业排查清单范本',
            templateEdit: '编辑企业自定义范本',
          }[dlg] || '' }}</h3>
          <button class="pf-close" @click="dlg = ''">✕</button>
        </div>
        <div class="pf-modal-b">
          <div class="pf-form-grid">
            <template v-if="dlg === 'record'">
              <div class="f-item"><label>排查类型 *</label><select v-model="form.checkType"><option v-for="t in ov.checkTypes || []" :key="t.code" :value="t.code">{{ t.name }}</option></select></div>
              <div class="f-item"><label>隐患等级</label><select v-model="form.dangerLevel"><option>一般</option><option>较大</option><option>重大</option></select></div>
              <div class="f-item" style="grid-column:1/3"><label>隐患名称 / 描述 *</label><input v-model="form.title" placeholder="如：左后轮胎纹磨损接近限值"></div>
              <div class="f-item"><label>隐患位置</label><input v-model="form.location" placeholder="车辆/场地/设备"></div>
              <div class="f-item"><label>关联车辆</label><select v-model="form.vehicleId"><option value="">无</option><option v-for="v in options.vehicles || []" :key="v.id" :value="String(v.id)">{{ v.plate_no }}</option></select></div>
              <div class="f-item"><label>检查人 / 发现人</label><select v-model="form.finderId"><option value="">未指定</option><option v-for="p in options.persons || []" :key="p.id" :value="String(p.id)">{{ p.name }}</option></select></div>
              <div class="f-item"><label>整改要求（可选）</label><input v-model="form.rectifyRequire" placeholder="留空则使用默认要求"></div>
            </template>

            <template v-if="dlg === 'plan'">
              <div class="f-item"><label>排查类型 *</label><select v-model="form.checkType" @change="onTypeChange"><option v-for="t in ov.checkTypes || []" :key="t.code" :value="t.code">{{ t.name }}</option></select></div>
              <div class="f-item"><label>清单范本</label><select v-model="form.templateId"><option value="">按类型自动带出</option><option v-for="t in ov.templates || []" :key="t.id" :value="t.id">{{ t.templateName }}（{{ t.scope === 'system' ? '系统级' : '企业' }}）</option></select></div>
              <div class="f-item" style="grid-column:1/3"><label>检查名称</label><input v-model="form.title" placeholder="留空按类型+月份自动生成"></div>
              <div class="f-item"><label>检查日期</label><input v-model="form.checkDate" type="date"></div>
              <div class="f-item"><label>检查人 / 负责人</label><select v-model="form.checkerId"><option value="">未指定</option><option v-for="p in options.persons || []" :key="p.id" :value="String(p.id)">{{ p.name }}</option></select></div>
              <div class="f-item" style="grid-column:1/3"><label>备注</label><input v-model="form.remark" placeholder="可选"></div>
              <div class="pf-sub" style="grid-column:1/3">选择「月度重大事故隐患排查」将自动带出 6 项重大事故隐患专项排查项（一票否决类）。</div>
            </template>

            <template v-if="dlg === 'rectify'">
              <div class="f-item" style="grid-column:1/3"><label>整改要求</label><input v-model="form.rectifyRequire" placeholder="限期整改并反馈凭证"></div>
              <div class="f-item"><label>整改责任人</label><select v-model="form.responsibleId"><option value="">未指定</option><option v-for="p in options.persons || []" :key="p.id" :value="String(p.id)">{{ p.name }}</option></select></div>
              <div class="f-item"><label>整改期限</label><input v-model="form.deadline" type="date"></div>
            </template>

            <template v-if="dlg === 'feedback'">
              <div class="f-item" style="grid-column:1/3"><label>整改情况说明</label><input v-model="form.rectifyNote" placeholder="已完成更换并复核"></div>
              <div class="f-item" style="grid-column:1/3"><label>整改凭证照片（路径/URL）</label><input v-model="form.rectifyPhotos" placeholder="多张用逗号分隔"></div>
            </template>

            <template v-if="dlg === 'accept'">
              <div class="f-item"><label>验收结果</label><select v-model="form.acceptResult"><option value="pass">通过（闭环销号）</option><option value="reject">退回整改</option></select></div>
              <div class="f-item"><label>验收人</label><select v-model="form.acceptorId"><option value="">未指定</option><option v-for="p in options.persons || []" :key="p.id" :value="String(p.id)">{{ p.name }}</option></select></div>
              <div class="f-item" style="grid-column:1/3"><label>验收意见</label><input v-model="form.acceptNote" placeholder="可选"></div>
            </template>

            <template v-if="dlg === 'remind'">
              <div class="f-item" style="grid-column:1/3"><label>督办内容</label><input v-model="form.content" placeholder="请尽快完成整改并反馈凭证"></div>
            </template>

            <template v-if="dlg === 'correct'">
              <div class="f-item" style="grid-column:1/3"><label>隐患名称</label><input v-model="form.title"></div>
              <div class="f-item"><label>隐患等级</label><select v-model="form.dangerLevel"><option>一般</option><option>较大</option><option>重大</option></select></div>
              <div class="f-item"><label>状态（超管可任意纠正）</label><select v-model="form.status"><option value="pending">待整改</option><option value="rectifying">整改中</option><option value="verify">待验收</option><option value="closed">已闭环</option><option value="overdue">超期</option></select></div>
              <div class="pf-sub" style="grid-column:1/3">仅服务商 / 超管账号可纠错，企业账号提交将返回 403。</div>
            </template>

            <template v-if="dlg === 'template' || dlg === 'templateEdit'">
              <div class="f-item"><label>适用排查类型</label><select v-model="form.checkType" :disabled="dlg === 'templateEdit'"><option v-for="t in ov.checkTypes || []" :key="t.code" :value="t.code">{{ t.name }}</option></select></div>
              <div class="f-item"><label>清单名称</label><input v-model="form.templateName" placeholder="如：月度全面隐患排查清单（企业版）"></div>
            </template>
          </div>

          <div v-if="dlg === 'template' || dlg === 'templateEdit'" style="margin-top:14px">
            <div class="card-title">检查项目清单 <span class="pf-sub">项目类别 / 检查项目 / 内容与标准 / 是否重大项</span></div>
            <table class="pf-subtable">
              <thead><tr><th>项目类别</th><th>检查项目</th><th>检查内容与标准</th><th>重大</th><th></th></tr></thead>
              <tbody>
                <tr v-for="(it, idx) in tplItems" :key="idx">
                  <td><input v-model="it.category" style="width:110px"></td>
                  <td><input v-model="it.name"></td>
                  <td><input v-model="it.standard"></td>
                  <td style="text-align:center"><input type="checkbox" v-model="it.major"></td>
                  <td><a class="btn btn-outline btn-sm" @click="delTplItem(idx)">删</a></td>
                </tr>
              </tbody>
            </table>
            <a class="btn btn-outline btn-sm" style="margin-top:8px" @click="addTplItem"><Icon name="plus" :size="13" /> 增加检查项</a>
          </div>
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
.bm-table { width: 100%; min-width: 1080px; }
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
.pf-subtable td input[type=checkbox] { width: 18px; height: 18px; }
.pf-day { border: 1px solid var(--border); border-radius: 8px; padding: 12px 14px; margin-top: 10px; background: #fff; }
.pf-day-h { display: flex; align-items: center; gap: 10px; font-size: 14px; }
.pf-ref-row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.pf-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
.pf-form-grid .f-item { display: flex; flex-direction: column; gap: 5px; }
.pf-form-grid label { font-size: 12.5px; color: var(--text-3); }
.pf-form-grid input, .pf-form-grid select { height: 34px; border: 1px solid var(--border); border-radius: 8px; padding: 0 9px; }
.pf-modal { width: 720px; max-width: 94vw; max-height: 88vh; margin: auto; background: #fff; border-radius: 12px; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,.25); }
.pf-modal-h { padding: 16px 22px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; }
.pf-modal-h h3 { margin: 0; font-size: 17px; }
.pf-modal-b { padding: 18px 22px; overflow: auto; }
.pf-modal-f { padding: 14px 22px; border-top: 1px solid var(--border); display: flex; justify-content: flex-end; gap: 10px; }
</style>
