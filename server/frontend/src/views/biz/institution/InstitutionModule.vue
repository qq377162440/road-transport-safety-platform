<script setup>
/** B3 制度档案引擎 · 完整流程闭环（详细设计 09 §7.3 / §7.10 / §7.11）
 *  与「台账范本管理」分工：范本管理管空白范本（多套版本），本页管企业实际生效的
 *  24 项制度成品——文件编号 YT-ZDxx、实施日期、签发人、盖章、审批状态、版本留痕。
 *  状态机：0未起草 → 1起草中 → 2待审核 → 3待发布 → 4已发布 → 5已作废；
 *          2 可驳回为 6审核驳回，4 可修订生成新版本回到 1。
 *  子表：版本历史 / 签署盖章进度 / 流转日志；PDF 成品归档电子档案柜盒二；
 *  联动：正文取自 tpl_template 同名范本版本、签发人取自 B1 person、印章取自印章设置、
 *        已发布制度通过 options 供 B4/B5/B6/C1 等模块引用；小程序仅预留数据接口。 */
import { computed, onMounted, ref } from 'vue'
import request from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import Icon from '@/components/Icon.vue'

const overview = ref({ items: [], groups: {}, total: 0, published: 0, percent: 0, company: '—', abbr: 'YT' })
const expiringList = ref([])
const optionCount = ref(0)
const keyword = ref('')
const statusFilter = ref('')
const groupFilter = ref('')
const loading = ref(false)
const busy = ref(false)

const STATUS = {
  0: ['未起草', 'tag-gray'], 1: ['起草中', 'tag-blue'], 2: ['待审核', 'tag-yellow'],
  3: ['待发布', 'tag-blue'], 4: ['已发布', 'tag-green'], 5: ['已作废', 'tag-gray'], 6: ['审核驳回', 'tag-red'],
}
const FLOW = [['0', '未起草'], ['1', '起草中'], ['2', '待审核'], ['3', '待发布'], ['4', '已发布'], ['5', '已作废']]
function statusText(s) { return (STATUS[s] || STATUS[0])[0] }
function statusClass(s) { return (STATUS[s] || STATUS[0])[1] }

const filtered = computed(() => (overview.value.items || []).filter((it) => {
  const k = keyword.value.trim()
  if (k && !(String(it.itemCode || '') + String(it.itemName || '') + String(it.docNo || '')).includes(k)) return false
  if (statusFilter.value !== '' && String(it.status) !== statusFilter.value) return false
  if (groupFilter.value && it.group !== groupFilter.value) return false
  return true
}))
const groupEntries = computed(() => Object.entries(overview.value.groups || {}))

async function load() {
  loading.value = true
  try {
    const [o, e, op] = await Promise.all([
      request.get('/flow/institution/overview'),
      request.get('/flow/institution/expiring', { params: { days: 30 } }),
      request.get('/flow/institution/options'),
    ])
    overview.value = o.data || overview.value
    expiringList.value = e.data?.list || []
    optionCount.value = (op.data || []).length
  } catch (e) { /* 拦截器已提示 */ }
  finally { loading.value = false }
}
onMounted(load)

// ---------------- 详情抽屉（四 Tab） ----------------
const drawer = ref(false)
const detail = ref(null)
const tab = ref('doc')
const curCode = ref('')

async function openDetail(row) {
  curCode.value = row.itemCode
  tab.value = 'doc'
  drawer.value = true
  await refreshDetail()
}
async function refreshDetail() {
  busy.value = true
  try { detail.value = (await request.get(`/flow/institution/${curCode.value}`)).data || null }
  catch (e) { detail.value = null }
  finally { busy.value = false }
}
const cur = computed(() => (overview.value.items || []).find((x) => x.itemCode === curCode.value) || {})

// ---------------- 通用状态动作 ----------------
async function act(url, method, body, label) {
  busy.value = true
  try {
    const r = method === 'put' ? await request.put(url, body || {}) : await request.post(url, body || {})
    if (r.data && r.data.ok === false) { ElMessage.warning(r.data.msg || '操作未成功'); return null }
    ElMessage.success(label + '成功')
    await Promise.all([load(), refreshDetail()])
    return r.data
  } catch (e) { return null }
  finally { busy.value = false }
}

async function doSubmit() { await act(`/flow/institution/${curCode.value}/submit`, 'put', {}, '提交审核') }
async function doPublish() {
  try {
    await ElMessageBox.confirm('发布后将生成盖章成品 PDF，并归档到电子档案柜盒二（制度汇编）。确认发布？', '发布制度', { type: 'warning' })
  } catch (e) { return }
  await act(`/flow/institution/${curCode.value}/publish`, 'put', {}, '发布')
}
async function doAbolish() {
  try {
    await ElMessageBox.confirm('作废后该制度不再作为其他模块的引用数据源，版本历史仍保留。确认作废？', '作废制度', { type: 'warning' })
  } catch (e) { return }
  await act(`/flow/institution/${curCode.value}/abolish`, 'put', { reason: '页面发起作废' }, '作废')
}

// ---------------- 起草 / 修订 ----------------
const draftDialog = ref(false)
const draftMode = ref('draft')
const personOptions = ref([])
const tplOptions = ref([])
const draftForm = ref(blankDraft())
function blankDraft() {
  return { tplId: '', issuerId: '', effectiveDate: new Date().toISOString().slice(0, 10), reviewDate: '', reason: '' }
}
async function openDraft(row, mode = 'draft') {
  curCode.value = row.itemCode
  draftMode.value = mode
  draftForm.value = blankDraft()
  draftDialog.value = true
  busy.value = true
  try {
    const [p, t] = await Promise.all([
      request.get('/flow/institution/persons'),
      request.get('/flow/institution/templates', { params: { code: row.itemCode } }),
    ])
    personOptions.value = p.data || []
    tplOptions.value = t.data?.list || []
    if (tplOptions.value.length) draftForm.value.tplId = String(tplOptions.value[0].id)
    if (personOptions.value.length) draftForm.value.issuerId = String(personOptions.value[0].id)
    const d = (await request.get(`/flow/institution/${row.itemCode}`)).data
    if (mode === 'revise' && d?.item) {
      if (d.item.effective_date) draftForm.value.effectiveDate = String(d.item.effective_date).slice(0, 10)
      if (d.item.review_date) draftForm.value.reviewDate = String(d.item.review_date).slice(0, 10)
      if (d.item.tpl_id) draftForm.value.tplId = String(d.item.tpl_id)
    }
  } catch (e) { /* ignore */ }
  finally { busy.value = false }
}
async function submitDraft() {
  const revise = draftMode.value === 'revise'
  const r = await act(`/flow/institution/${curCode.value}/${revise ? 'revise' : 'draft'}`, 'post', {
    ...draftForm.value,
    reason: draftForm.value.reason || (revise ? '制度定期评审修订' : '首次发布起草'),
  }, revise ? '修订' : '起草')
  if (r) draftDialog.value = false
}

// ---------------- 审核 ----------------
const approveDialog = ref(false)
const approveForm = ref({ pass: true, remark: '' })
function openApprove(row) { curCode.value = row.itemCode; approveForm.value = { pass: true, remark: '' }; approveDialog.value = true }
async function submitApprove() {
  const r = await act(`/flow/institution/${curCode.value}/approve`, 'put', approveForm.value,
    approveForm.value.pass ? '审核通过' : '驳回')
  if (r) approveDialog.value = false
}

// ---------------- 签署 / 盖章进度 ----------------
const signDialog = ref(false)
const signForm = ref({ signerId: '', signType: 'sign', signerName: '' })
async function openSign() {
  signForm.value = { signerId: '', signType: 'sign', signerName: '' }
  signDialog.value = true
  if (!personOptions.value.length) {
    try { personOptions.value = (await request.get('/flow/institution/persons')).data || [] } catch (e) { /* ignore */ }
  }
}
async function submitSign() {
  const r = await act(`/flow/institution/${curCode.value}/sign`, 'post', signForm.value, '新增签署项')
  if (r) signDialog.value = false
}
async function markSigned(row) {
  await request.put(`/flow/institution/${curCode.value}/sign/${row.id}/done`, {})
  ElMessage.success('已标记完成')
  await Promise.all([load(), refreshDetail()])
}

// ---------------- PDF / 目录 / 拼装 ----------------
async function viewPdf(sealed = true) {
  try {
    const blob = await request.get(`/flow/institution/${curCode.value}/pdf`, { params: { sealed }, responseType: 'blob' })
    const b = blob instanceof Blob ? blob : new Blob([blob], { type: 'application/pdf' })
    const url = URL.createObjectURL(b)
    window.open(url, '_blank')
    setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch (e) { ElMessage.warning('PDF 预览失败') }
}
async function genToc() {
  busy.value = true
  try {
    const r = await request.post('/flow/institution/toc', {})
    if (r.data?.ok) ElMessage.success(`制度目录已生成（${r.data.itemCount} 项 / 已发布 ${r.data.published} 项），已归档盒二`)
    await load()
  } finally { busy.value = false }
}
const assembleDialog = ref(false)
const assembleForm = ref({ bizType: 'GENERAL', vehicleTypes: '' })
const assembleResult = ref(null)
async function runAssemble() {
  busy.value = true
  try {
    const r = await request.post('/flow/institution/assemble', {
      bizType: assembleForm.value.bizType,
      vehicleTypes: assembleForm.value.vehicleTypes.split(/[,，、\s]+/).filter(Boolean),
    })
    assembleResult.value = r.data || null
  } finally { busy.value = false }
}
function openAssemble() { assembleResult.value = null; assembleDialog.value = true }

function goArchive() { window.location.href = '/archive?box=' + encodeURIComponent('盒二') }
function levelText(l) { return { expired: '已过期', d7: '7天内', d15: '15天内', d30: '30天内' }[l] || l }
function dateText(v) { return v ? String(v).slice(0, 10) : '—' }
</script>

<template>
  <div class="bm-page">
    <div class="pc-header bm-header">
      <div>
        <h1>制度档案引擎</h1>
        <div class="desc">24 项制度成品档案 · 起草 / 审核 / 发布 / 修订 / 作废全流程 · 归档电子档案柜盒二</div>
        <div class="bm-meta">
          <span class="tag tag-blue">阶段 B3</span>
          <span class="tag tag-gray">制度范本</span>
          <span class="tag tag-green">归档 盒二</span>
          <span class="bm-tables">{{ overview.company }} · 编号规则 {{ overview.abbr }}-ZDxx · 24 项制度 · 3 张子表 · 7 态状态机</span>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-outline" @click="goArchive"><Icon name="archive" :size="15" /> 存档到电子档案柜（盒二）</button>
        <button class="btn btn-outline" :disabled="busy" @click="genToc"><Icon name="fileText" :size="15" /> 生成制度目录</button>
        <button class="btn btn-outline" @click="openAssemble"><Icon name="clipboard" :size="15" /> 制度包拼装</button>
        <button class="btn btn-outline" @click="load()"><Icon name="refresh" :size="15" /> 刷新</button>
      </div>
    </div>

    <div class="pf-hint"><Icon name="info" :size="15" />
      台账范本管理管「空白范本」，本页管「企业实际生效的制度成品」；正文取自同名范本的选中版本，签发人取自 B1 人员主表，
      发布时套用企业印章生成成品 PDF 并逐项归档盒二。制度目录按已发布制度自动生成。
    </div>

    <div class="pf-stat-row">
      <div class="stat-card"><div class="num">{{ overview.total || 0 }}</div><div class="lbl">制度字典总数（ZD01~ZD24）</div></div>
      <div class="stat-card"><div class="num" style="color:var(--ok,#16a34a)">{{ overview.published || 0 }}</div><div class="lbl">已发布生效 · 完整度 {{ overview.percent || 0 }}%</div></div>
      <div class="stat-card"><div class="num" style="color:#d97706">{{ (overview.reviewing || 0) + (overview.pendingPublish || 0) }}</div><div class="lbl">待审核 / 待发布</div></div>
      <div class="stat-card"><div class="num" style="color:var(--danger,#dc2626)">{{ overview.expiring || 0 }}</div><div class="lbl">复审到期（30 天内）</div></div>
    </div>

    <div class="card pf-flow">
      <div class="card-title">制度生命周期状态机 <span class="pf-sub">逐条操作 · 每次流转留痕 · 已发布可修订生成新版本，旧版本永久保留</span></div>
      <div class="pf-flow-row">
        <template v-for="(f, i) in FLOW" :key="f[0]">
          <div class="pf-flow-node" :class="{ on: Number(f[0]) === 4 }"><b>{{ f[0] }}</b><span>{{ f[1] }}</span></div>
          <span v-if="i < FLOW.length - 1" class="pf-arrow">→</span>
        </template>
        <span class="pf-sub" style="margin-left:12px">另：待审核可驳回为「6 审核驳回」，重新起草后回到 1</span>
      </div>
    </div>

    <div v-if="expiringList.length" class="card pf-warn">
      <div class="card-title">制度复审到期预警 <span class="pf-sub">已发布制度的复审日期 · 30 / 15 / 7 天分级</span></div>
      <div class="pf-warn-list">
        <div v-for="(d, i) in expiringList.slice(0, 8)" :key="d.item_code + i" class="pf-warn-item">
          <span class="tag" :class="d.level === 'expired' ? 'tag-red' : 'tag-yellow'">{{ levelText(d.level) }}</span>
          <span class="nm">{{ d.doc_no }} · {{ d.item_name }}</span>
          <span class="dt">{{ dateText(d.review_date) }}（{{ d.daysLeft < 0 ? '已过期 ' + (-d.daysLeft) + ' 天' : d.daysLeft + ' 天后' }}）</span>
        </div>
      </div>
    </div>

    <div class="filter-bar bm-filter">
      <div class="f-item"><label>关键字</label><input v-model="keyword" type="text" placeholder="制度编号 / 名称"></div>
      <div class="f-item"><label>状态</label>
        <select v-model="statusFilter">
          <option value="">全部</option>
          <option v-for="(v, k) in STATUS" :key="k" :value="k">{{ v[0] }}</option>
        </select>
      </div>
      <div class="f-item"><label>分组</label>
        <select v-model="groupFilter">
          <option value="">全部</option><option>通用管理制度</option><option>专项规程与风险手册</option>
        </select>
      </div>
      <div class="f-actions">
        <button class="btn btn-primary btn-sm" @click="load()"><Icon name="search" :size="14" /> 查询</button>
        <button class="btn btn-outline btn-sm" @click="keyword = ''; statusFilter = ''; groupFilter = ''">重置</button>
      </div>
    </div>

    <div class="card" style="padding:0;overflow:hidden">
      <div class="card-title" style="padding:16px 20px 0">制度成品档案
        <span class="pf-sub">共 {{ filtered.length }} 项 · 已发布 {{ optionCount }} 项作为其他模块引用数据源 · 完整度 {{ overview.percent || 0 }}%</span>
      </div>
      <div style="padding:0 20px 16px;overflow:auto">
        <table class="table bm-table" v-loading="loading">
          <thead><tr>
            <th>文件编号</th><th>制度名称</th><th>分组</th><th>状态</th><th>版本</th>
            <th>范本版本</th><th>签发人</th><th>实施日期</th><th>复审日</th><th>签署</th><th style="width:190px">操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="it in filtered" :key="it.itemCode">
              <td><b>{{ it.docNo }}</b></td>
              <td>{{ it.itemName }}<span v-if="it.bizScope !== 'ALL'" class="tag tag-yellow" style="margin-left:5px">普货</span></td>
              <td><span class="pf-sub">{{ it.group }}</span></td>
              <td><span class="tag" :class="statusClass(it.status)">{{ it.statusText }}</span></td>
              <td>{{ it.version || '—' }}<span class="pf-sub" v-if="it.versionCount"> / {{ it.versionCount }} 版</span></td>
              <td><span class="pf-sub">{{ it.tplLabel || '—' }}</span><span class="pf-sub" v-if="it.tplCount">（{{ it.tplCount }} 套可选）</span></td>
              <td>{{ it.issuerName || '—' }}</td>
              <td>{{ dateText(it.effectiveDate) }}</td>
              <td>{{ dateText(it.reviewDate) }}<span v-if="it.reviewDaysLeft !== null && it.reviewDaysLeft <= 30" class="tag tag-red" style="margin-left:4px">{{ it.reviewDaysLeft }}天</span></td>
              <td><span class="tag" :class="it.signDone === it.signTotal && it.signTotal ? 'tag-green' : 'tag-gray'">{{ it.signDone }}/{{ it.signTotal }}</span></td>
              <td>
                <a class="btn btn-primary btn-sm" @click="openDetail(it)">详情</a>
                <a v-if="[0, 1, 6].includes(it.status)" class="btn btn-outline btn-sm" @click="openDraft(it, 'draft')">起草</a>
                <a v-if="it.status === 2" class="btn btn-outline btn-sm" @click="openApprove(it)">审核</a>
                <a v-if="it.status === 3" class="btn btn-outline btn-sm" @click="curCode = it.itemCode; doPublish()">发布</a>
                <a v-if="it.status === 4" class="btn btn-outline btn-sm" @click="openDraft(it, 'revise')">修订</a>
              </td>
            </tr>
            <tr v-if="!filtered.length && !loading">
              <td colspan="11" style="text-align:center;color:var(--text-3);padding:28px">暂无制度档案</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ============ 制度详情抽屉（四 Tab） ============ -->
    <div class="pf-mask" :class="{ on: drawer }" @click.self="drawer = false">
      <div class="pf-drawer">
        <div class="pf-dh">
          <div>
            <h2>{{ cur.docNo }} · {{ cur.itemName || '制度档案' }}</h2>
            <div class="pf-sub">{{ overview.company }} · {{ cur.group }} · 归档盒二
              <span class="tag" :class="statusClass(cur.status)" style="margin-left:6px">{{ cur.statusText }}</span>
              <span v-if="cur.version" class="tag tag-blue" style="margin-left:4px">{{ cur.version }}</span>
            </div>
          </div>
          <button class="pf-close" @click="drawer = false">✕</button>
        </div>
        <div class="pf-tabs">
          <button v-for="t in [['doc','制度文件'],['version','版本历史'],['sign','签署盖章'],['log','流转日志']]" :key="t[0]"
                  class="pf-tab" :class="{ on: tab === t[0] }" @click="tab = t[0]">{{ t[1] }}</button>
        </div>
        <div v-if="detail" class="pf-body" v-loading="busy">
          <!-- 操作条 -->
          <div class="card pf-actions">
            <div class="card-title">逐条操作 <span class="pf-sub">当前状态：{{ statusText(cur.status) }} · 文件编号 {{ cur.docNo }}</span></div>
            <div class="pf-btn-row">
              <a v-if="[0, 1, 6].includes(cur.status)" class="btn btn-primary btn-sm" @click="openDraft(cur, 'draft')">起草新版本</a>
              <a v-if="cur.status === 1" class="btn btn-primary btn-sm" @click="doSubmit">提交审核</a>
              <a v-if="cur.status === 2" class="btn btn-primary btn-sm" @click="openApprove(cur)">审核 / 驳回</a>
              <a v-if="cur.status === 3" class="btn btn-primary btn-sm" @click="doPublish">发布并归档盒二</a>
              <a v-if="cur.status === 4" class="btn btn-primary btn-sm" @click="openDraft(cur, 'revise')">发起修订</a>
              <a v-if="cur.status === 4" class="btn btn-outline btn-sm" @click="doAbolish">作废</a>
              <a v-if="cur.version" class="btn btn-outline btn-sm" @click="viewPdf(cur.status === 4)">查看{{ cur.status === 4 ? '成品' : '草稿' }} PDF</a>
              <a v-if="cur.version" class="btn btn-outline btn-sm" @click="viewPdf(false)">查看无章版</a>
              <a class="btn btn-outline btn-sm" @click="openSign">新增签署项</a>
              <span class="pf-sub" style="margin-left:auto">正文同源范本：{{ cur.tplLabel || '未选择' }}</span>
            </div>
          </div>

          <!-- Tab 1: 制度文件 -->
          <template v-if="tab === 'doc'">
            <div class="card pf-group">
              <div class="card-title">制度文件头 <span class="pf-sub">文件编号 + 实施日期 + 签发人 + 企业盖章（发布态）</span></div>
              <div class="pf-doc-head">
                <div class="pf-doc-no">文件编号：{{ cur.docNo }}　版本号：{{ cur.version || '—' }}</div>
                <div class="pf-doc-title">{{ cur.itemName }}</div>
                <table class="pf-subtable">
                  <tbody>
                    <tr><th>实施日期</th><td>{{ dateText(cur.effectiveDate) }}</td><th>签 发 人</th><td>{{ cur.issuerName || '—' }}</td></tr>
                    <tr><th>范本版本</th><td>{{ cur.tplLabel || '—' }}</td><th>复审日期</th><td>{{ dateText(cur.reviewDate) }}</td></tr>
                    <tr><th>适用营运类型</th><td>{{ cur.bizScope === 'ALL' ? '通用（普货/危货共用）' : '普货专用' }}</td><th>归档位置</th><td>电子档案柜 · 盒二 制度汇编</td></tr>
                  </tbody>
                </table>
                <div class="pf-doc-foot">{{ overview.company }}（盖章）　{{ dateText(cur.effectiveDate) }}</div>
              </div>
            </div>
            <div class="card pf-group">
              <div class="card-title">可用范本版本（台账范本管理同源） <span class="pf-sub">共 {{ (detail.templates || []).length }} 套，起草 / 修订时选用</span></div>
              <table class="pf-subtable">
                <thead><tr><th>范本版本</th><th>版本号</th><th>范本名称</th><th>需盖章</th></tr></thead>
                <tbody>
                  <tr v-for="t in detail.templates || []" :key="t.id">
                    <td>{{ t.label }}</td><td>v{{ t.version || 1 }}</td><td>{{ t.name }}</td>
                    <td>{{ String(t.need_seal) === '1' ? '是' : '否' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="card pf-group">
              <div class="card-title">模块间联动 <span class="pf-sub">已发布制度作为其他模块「依据制度」数据源</span></div>
              <div class="pf-ref-row">
                <span v-for="r in detail.references || []" :key="r.module" class="tag" :class="r.count ? 'tag-blue' : 'tag-gray'">
                  {{ r.name }} · {{ r.count }}
                </span>
              </div>
              <div class="pf-sub" style="margin-top:8px">签发人 / 签署人来自 B1 人员主表；印章取自印章设置；企业名称取自当前租户；
                已发布制度通过 <code>/flow/institution/options</code> 供 B4 一日三检、B5 隐患、B6 会议、C1 培训等模块引用。</div>
            </div>
          </template>

          <!-- Tab 2: 版本历史 -->
          <template v-if="tab === 'version'">
            <div class="card pf-group">
              <div class="card-title">版本历史 <span class="pf-sub">旧版本不覆盖，可回溯（设计文档 §7.10.3）</span></div>
              <table class="pf-subtable">
                <thead><tr><th>版本</th><th>范本版本</th><th>签发人</th><th>实施日期</th><th>复审日期</th><th>状态</th><th>原因 / 说明</th></tr></thead>
                <tbody>
                  <tr v-for="v in detail.versions || []" :key="v.id">
                    <td><b>{{ v.version }}</b></td><td>{{ v.tpl_label || '—' }}</td><td>{{ v.issuer_name || '—' }}</td>
                    <td>{{ dateText(v.effective_date) }}</td><td>{{ dateText(v.review_date) }}</td>
                    <td><span class="tag" :class="String(v.status) === '4' ? 'tag-green' : 'tag-blue'">{{ ({ 1: '草稿', 2: '待审核', 3: '待发布', 4: '已发布', 5: '已作废', 6: '驳回' })[v.status] || '草稿' }}</span></td>
                    <td>{{ v.reason || '—' }}</td>
                  </tr>
                  <tr v-if="!(detail.versions || []).length"><td colspan="7" style="text-align:center;color:var(--text-3)">暂无版本，先起草</td></tr>
                </tbody>
              </table>
            </div>
          </template>

          <!-- Tab 3: 签署盖章 -->
          <template v-if="tab === 'sign'">
            <div class="card pf-group">
              <div class="card-title">签署 / 盖章进度 <span class="pf-sub">已办 {{ cur.signDone || 0 }} / 共 {{ cur.signTotal || 0 }} 项</span></div>
              <table class="pf-subtable">
                <thead><tr><th>签署人</th><th>类型</th><th>关联版本</th><th>状态</th><th>完成时间</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-for="x in detail.signs || []" :key="x.id">
                    <td>{{ x.signer_name || '—' }}</td>
                    <td>{{ x.sign_type === 'seal' ? '公司盖章' : '法人签字' }}</td>
                    <td>{{ x.version || '—' }}</td>
                    <td><span class="tag" :class="String(x.status) === '1' ? 'tag-green' : 'tag-yellow'">{{ String(x.status) === '1' ? '已完成' : '待办' }}</span></td>
                    <td>{{ x.signed_at ? String(x.signed_at).slice(0, 19) : '—' }}</td>
                    <td><a v-if="String(x.status) !== '1'" class="btn btn-outline btn-sm" @click="markSigned(x)">标记完成</a></td>
                  </tr>
                  <tr v-if="!(detail.signs || []).length"><td colspan="6" style="text-align:center;color:var(--text-3)">暂无签署项，点上方「新增签署项」</td></tr>
                </tbody>
              </table>
            </div>
          </template>

          <!-- Tab 4: 流转日志 -->
          <template v-if="tab === 'log'">
            <div class="card pf-group">
              <div class="card-title">流转日志 <span class="pf-sub">状态变迁、审批意见、操作人、时间全量留痕</span></div>
              <table class="pf-subtable">
                <thead><tr><th>时间</th><th>动作</th><th>状态变化</th><th>版本</th><th>操作人</th><th>说明</th></tr></thead>
                <tbody>
                  <tr v-for="l in detail.logs || []" :key="l.id">
                    <td>{{ String(l.created_at).slice(0, 19) }}</td>
                    <td><span class="tag tag-blue">{{ l.action }}</span></td>
                    <td>{{ statusText(Number(l.from_status)) }} → {{ statusText(Number(l.to_status)) }}</td>
                    <td>{{ l.version || '—' }}</td><td>{{ l.operator || '—' }}</td><td>{{ l.remark || '—' }}</td>
                  </tr>
                  <tr v-if="!(detail.logs || []).length"><td colspan="6" style="text-align:center;color:var(--text-3)">暂无流转记录</td></tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- ============ 起草 / 修订弹窗 ============ -->
    <el-dialog v-model="draftDialog" :title="(draftMode === 'revise' ? '发起修订' : '起草制度') + ' · ' + curCode" width="560px" destroy-on-close>
      <div class="pf-form-grid">
        <div class="form-item"><label class="form-label">采用范本版本 <span class="req">*</span></label>
          <select v-model="draftForm.tplId" class="form-input">
            <option v-for="t in tplOptions" :key="t.id" :value="String(t.id)">{{ t.label }} · {{ t.name }}</option>
          </select></div>
        <div class="form-item"><label class="form-label">签发人（B1 人员同源）</label>
          <select v-model="draftForm.issuerId" class="form-input">
            <option value="">不指定</option>
            <option v-for="p in personOptions" :key="p.id" :value="String(p.id)">{{ p.name }} · {{ p.telephone || '' }}</option>
          </select></div>
        <div class="form-item"><label class="form-label">实施日期</label>
          <input v-model="draftForm.effectiveDate" type="date" class="form-input"></div>
        <div class="form-item"><label class="form-label">复审到期日</label>
          <input v-model="draftForm.reviewDate" type="date" class="form-input"></div>
        <div class="form-item" style="grid-column:1/-1"><label class="form-label">{{ draftMode === 'revise' ? '修订原因' : '起草说明' }}</label>
          <input v-model="draftForm.reason" class="form-input" placeholder="如：按 2026 年度评审要求修订"></div>
      </div>
      <div class="pf-sub" style="margin-top:10px">将生成「不盖章草稿 PDF」并写入版本历史；审核通过后发布才会套用企业印章并归档盒二。</div>
      <template #footer>
        <el-button @click="draftDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitDraft">{{ draftMode === 'revise' ? '生成新版本' : '保存草稿' }}</el-button>
      </template>
    </el-dialog>

    <!-- 审核弹窗 -->
    <el-dialog v-model="approveDialog" :title="'审核制度 · ' + curCode" width="460px" destroy-on-close>
      <div class="form-item"><label class="form-label">审核结论</label>
        <select v-model="approveForm.pass" class="form-input">
          <option :value="true">通过（进入待发布）</option><option :value="false">驳回（退回起草）</option>
        </select></div>
      <div class="form-item" style="margin-top:12px"><label class="form-label">审核意见</label>
        <input v-model="approveForm.remark" class="form-input" placeholder="选填"></div>
      <template #footer>
        <el-button @click="approveDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitApprove">提交审核结论</el-button>
      </template>
    </el-dialog>

    <!-- 签署弹窗 -->
    <el-dialog v-model="signDialog" :title="'新增签署项 · ' + curCode" width="460px" destroy-on-close>
      <div class="form-item"><label class="form-label">类型</label>
        <select v-model="signForm.signType" class="form-input">
          <option value="sign">法人签字</option><option value="seal">公司盖章</option>
        </select></div>
      <div class="form-item" style="margin-top:12px"><label class="form-label">签署人（B1 人员同源）</label>
        <select v-model="signForm.signerId" class="form-input">
          <option value="">不指定</option>
          <option v-for="p in personOptions" :key="p.id" :value="String(p.id)">{{ p.name }}</option>
        </select></div>
      <div class="form-item" style="margin-top:12px"><label class="form-label">签署人姓名（可直接填写）</label>
        <input v-model="signForm.signerName" class="form-input" placeholder="留空则取所选人员姓名"></div>
      <template #footer>
        <el-button @click="signDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitSign">保存</el-button>
      </template>
    </el-dialog>

    <!-- 制度包拼装 -->
    <el-dialog v-model="assembleDialog" title="制度包拼装（详细设计 09 §7.3 / §7.4）" width="680px" destroy-on-close>
      <div class="pf-form-grid">
        <div class="form-item"><label class="form-label">营运类型</label>
          <select v-model="assembleForm.bizType" class="form-input">
            <option value="GENERAL">普货</option><option value="DANGER">危货</option><option value="PASSENGER">客运</option>
          </select></div>
        <div class="form-item"><label class="form-label">企业车型（逗号分隔）</label>
          <input v-model="assembleForm.vehicleTypes" class="form-input" placeholder="厢车,牵引车,罐车"></div>
      </div>
      <div style="margin-top:12px"><button class="btn btn-primary btn-sm" :disabled="busy" @click="runAssemble">生成拼装方案</button></div>
      <div v-if="assembleResult" class="pf-assemble">
        <div v-for="l in assembleResult.layers || []" :key="l.layer" class="pf-layer">
          <div class="pf-layer-title">{{ l.layer }} <span class="pf-sub">{{ l.count }} 项</span></div>
          <div class="pf-sub">{{ l.desc }}</div>
          <div v-if="l.classes" class="pf-ref-row" style="margin-top:8px">
            <span v-for="c in l.classes" :key="c.name" class="tag" :class="c.applicable ? 'tag-blue' : 'tag-gray'">{{ c.name }}（{{ c.vehicles }}）</span>
          </div>
          <div v-if="l.vehicleStruct" class="pf-ref-row" style="margin-top:8px">
            <span v-for="c in l.vehicleStruct" :key="c" class="tag tag-blue">{{ c }}</span>
            <span class="pf-sub" style="margin-left:6px">× {{ (l.stages || []).length }} 个作业环节/岗位</span>
          </div>
          <div v-if="l.note" class="pf-sub" style="margin-top:6px;color:#b45309">{{ l.note }}</div>
        </div>
        <div class="pf-sub" style="margin-top:10px">{{ assembleResult.note }}</div>
      </div>
      <template #footer><el-button @click="assembleDialog = false">关闭</el-button></template>
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
.bm-table { width: 100%; min-width: 1180px; }
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
/* 抽屉 */
.pf-mask { position: fixed; inset: 0; background: rgba(20,28,43,.45); z-index: 10000; display: none; justify-content: flex-end; }
.pf-mask.on { display: flex; }
.pf-drawer { width: 940px; max-width: 95vw; height: 100%; background: #F5F7FB; display: flex; flex-direction: column; box-shadow: -8px 0 30px rgba(0,0,0,.15); }
.pf-dh { background: #fff; padding: 18px 24px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: flex-start; }
.pf-dh h2 { margin: 0; font-size: 19px; }
.pf-close { border: none; background: #F1F5F9; width: 32px; height: 32px; border-radius: 8px; cursor: pointer; font-size: 15px; }
.pf-tabs { display: flex; gap: 4px; background: #fff; padding: 0 24px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
.pf-tab { border: none; background: transparent; padding: 12px 14px; font-size: 13.5px; color: var(--text-3); cursor: pointer; border-bottom: 2px solid transparent; }
.pf-tab.on { color: var(--brand-600, #1a5bb0); font-weight: 700; border-bottom-color: var(--brand-600, #1a5bb0); }
.pf-body { flex: 1; overflow: auto; padding: 18px 24px 40px; display: flex; flex-direction: column; gap: 14px; }
.pf-actions { padding: 16px 20px; }
.pf-btn-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 10px; }
.pf-group { padding: 16px 20px; }
.pf-ref-row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.pf-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
.pf-form-grid .form-input { width: 100%; }
.pf-subtable { width: 100%; margin-top: 10px; border-collapse: collapse; }
.pf-subtable th, .pf-subtable td { padding: 7px 10px; border-bottom: 1px solid var(--border); font-size: 13px; text-align: left; }
.pf-subtable th { background: #F8FAFC; color: var(--text-3); font-weight: 600; }
.pf-doc-head { background: #fff; border: 1px solid var(--border); padding: 22px 26px; margin-top: 12px; font-family: "SimSun","宋体",serif; }
.pf-doc-no { text-align: right; font-size: 13px; }
.pf-doc-title { text-align: center; font-size: 21px; font-weight: 700; letter-spacing: 3px; margin: 10px 0 18px; }
.pf-doc-foot { text-align: right; margin-top: 26px; font-size: 13px; }
.pf-assemble { margin-top: 14px; display: flex; flex-direction: column; gap: 10px; }
.pf-layer { border: 1px solid var(--border); border-radius: 8px; padding: 12px 14px; background: #FAFCFF; }
.pf-layer-title { font-weight: 700; font-size: 14px; margin-bottom: 4px; }
</style>
