<script setup>
/** C4 考核奖惩 + 诚信考核 · 完整流程闭环（详细设计 19）
 *  创建年度考核 → 系统按岗位自动生成考核明细(默认100分) → 驾驶员违规联动减分(人工确认)
 *  → 一键签名(主要负责人+安全管理员) → 一键提交生成台账(带盖章) → 归档盒一 + 沉淀一人一档 */
import { ref, onMounted } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'

const ov = ref({ company: '—', isDanger: false, assessStatus: {}, level: {}, stats: {}, assesses: [] })
const loading = ref(false)
const mainTab = ref('assess')

async function load() {
  loading.value = true
  try { const r = await request.get('/flow/credit/overview'); ov.value = r.data || ov.value } catch (e) {}
  finally { loading.value = false }
}
onMounted(load)

async function act(url, method, body, label, reload = true) {
  try {
    const r = method === 'put' ? await request.put(url, body || {})
      : method === 'get' ? await request.get(url)
      : await request.post(url, body || {})
    if (r.data && r.data.ok === false) { ElMessage.warning(r.data.msg || '操作未成功'); return null }
    if (label) ElMessage.success(label + '成功')
    if (reload) await load()
    return r.data
  } catch (e) { return null }
}
async function openPdf(pdfId) {
  if (!pdfId) { ElMessage.warning('PDF 尚未生成'); return }
  try {
    const raw = await request.get('/archive/pdf/' + pdfId + '/download', { responseType: 'blob' })
    const blob = raw instanceof Blob ? raw : new Blob([raw], { type: 'application/pdf' })
    const u = URL.createObjectURL(blob); window.open(u, '_blank'); setTimeout(() => URL.revokeObjectURL(u), 60000)
  } catch (e) { ElMessage.error('PDF 打开失败') }
}
const STATUS = { draft: ['草稿', 'tag-gray'], confirming: ['待确认', 'tag-blue'], completed: ['已公示', 'tag-green'] }
const LV = { excellent: ['优秀', 'tag-green'], pass: ['合格', 'tag-blue'], fail: ['不合格', 'tag-red'] }
const stText = (m, s) => ((m[s] || [s])[0])
const stClass = (m, s) => ((m[s] || ['', 'tag-gray'])[1])

// 年度考核列表 + 创建
const cur = ref(null)
const items = ref([])
const createDlg = ref(false)
const createForm = ref({ year: new Date().getFullYear() })
function pickAssess() {
  const list = (ov.value.assesses || [])
  cur.value = list.length ? list[0] : null
  if (cur.value) loadItems(cur.value.id)
}
async function loadItems(id) {
  const r = await request.get('/flow/credit/assess/' + String(id) + '/items')
  items.value = r.data || []
}
async function openCreate() { createForm.value = { year: new Date().getFullYear() }; createDlg.value = true }
async function submitCreate() {
  const r = await act('/flow/credit/assess', 'post', { ...createForm.value }, '创建年度考核', false)
  if (r) { createDlg.value = false; await load(); pickAssess(); ElMessage.success('已按岗位自动生成考核明细（默认100分）') }
}

// 驾驶员违规联动（提示）
const vioDlg = ref(false)
const vios = ref([])
async function openVio() {
  if (!cur.value) { ElMessage.warning('请先创建/选择年度考核'); return }
  const r = await request.get('/flow/credit/assess/' + String(cur.value.id) + '/violations')
  vios.value = r.data || []; vioDlg.value = true
}

// 酌情减分
const deductDlg = ref(false)
const deductForm = ref({ itemId: '', postName: '', deduction: 0, deductionNote: '' })
function openDeduct(it) { deductForm.value = { itemId: it.id, postName: it.post_name, deduction: it.deduction || 0, deductionNote: it.deduction_note || '' }; deductDlg.value = true }
async function submitDeduct() {
  if (!cur.value) return
  const r = await act(`/flow/credit/assess/${String(cur.value.id)}/deduct`, 'post', { ...deductForm.value }, '酌情减分', false)
  if (r) { deductDlg.value = false; await loadItems(cur.value.id) }
}

// 一键签名 + 一键提交（生成台账 + 归档盒一 + 一人一档）
async function doSign() {
  if (!cur.value) return
  const r = await act(`/flow/credit/assess/${String(cur.value.id)}/sign`, 'post', {}, '一键签名', false)
  if (r) ElMessage.success('已签名：' + (r.signInfo || []).map(s => s.role).join(' / '))
}
async function doSubmit() {
  if (!cur.value) return
  const r = await act(`/flow/credit/assess/${String(cur.value.id)}/submit`, 'post', {}, '生成台账并归档', false)
  if (r) { ElMessage.success(`已公示，台账PDF已生成并归档${ov.value.isDanger ? '（危货·盒一）' : '（普货·盒一）'}`); if (r.pdfId) openPdf(r.pdfId); await loadItems(cur.value.id) }
}
async function doGenerate() {
  if (!cur.value) return
  const r = await act(`/flow/credit/assess/${String(cur.value.id)}/generate`, 'post', {}, '生成台账', false)
  if (r) openPdf(r.pdfId)
}

function finalScore(it) { return (it.score || 100) - (it.deduction || 0) }
function d16(v) { return v ? String(v).slice(0, 16).replace('T', ' ') : '—' }
</script>

<template>
  <div class="bm-page">
    <div class="pf-head">
      <div>
        <div class="pf-title">{{ ov.company }} · 考核奖惩与诚信考核</div>
        <div class="pf-sub">{{ ov.isDanger ? '危货企业 · 归档至盒一(危货)' : '普货企业 · 归档至盒一' }}</div>
      </div>
      <button class="btn btn-primary" @click="openCreate">+ 创建年度考核</button>
    </div>

    <div class="pf-stats">
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).assesses || 0 }}</div><div class="l">年度考核</div></div>
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).completed || 0 }}</div><div class="l">已公示</div></div>
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).items || 0 }}</div><div class="l">考核明细</div></div>
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).posts || 0 }}</div><div class="l">责任岗位</div></div>
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).persons || 0 }}</div><div class="l">从业人员</div></div>
    </div>

    <div class="pf-tabs">
      <span :class="['pf-tab', mainTab === 'assess' && 'on']" @click="mainTab = 'assess'">年度考核</span>
      <span :class="['pf-tab', mainTab === 'violation' && 'on']" @click="mainTab = 'violation'">违规联动</span>
    </div>

    <!-- 年度考核 -->
    <div v-if="mainTab === 'assess'" style="overflow:auto">
      <div class="pf-sub" style="padding:12px 20px 0">年度考核列表（{{ (ov.assesses || []).length }} 个）</div>
      <table class="table bm-table" style="min-width:760px">
        <thead><tr><th>年度</th><th>考核名称</th><th>明细数</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="a in (ov.assesses || [])" :key="a.id" :class="cur && cur.id === a.id ? 'on' : ''">
            <td><b>{{ a.year }}</b></td><td>{{ a.assess_name }}</td>
            <td>{{ a.itemCount }}</td>
            <td><span class="tag" :class="stClass(ov.assessStatus, a.status)">{{ stText(ov.assessStatus, a.status) }}</span></td>
            <td><a class="btn btn-outline btn-sm" @click="cur = a; loadItems(a.id)">查看明细</a></td>
          </tr>
          <tr v-if="!(ov.assesses || []).length"><td colspan="5" style="text-align:center;color:var(--text-3);padding:26px">暂无年度考核，点右上「创建年度考核」</td></tr>
        </tbody>
      </table>

      <div v-if="cur" style="padding:16px 20px">
        <div class="pf-sub" style="margin-bottom:8px">
          考核明细（{{ (items || []).length }} 项，按岗位生成 · 默认 100 分）
          <span style="margin-left:10px">
            <button class="btn btn-primary btn-sm" @click="doSign">一键签名</button>
            <button class="btn btn-primary btn-sm" style="margin-left:8px" @click="doSubmit">一键提交并归档(盒一)</button>
            <button class="btn btn-outline btn-sm" style="margin-left:8px" @click="doGenerate">仅生成台账</button>
          </span>
        </div>
        <table class="table bm-table" style="min-width:900px">
          <thead><tr><th>岗位</th><th>应得分</th><th>减分</th><th>实得分</th><th>等级</th><th>状态/签字</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="it in items" :key="it.id">
              <td><b>{{ it.post_name }}</b></td>
              <td>{{ it.score }}</td>
              <td style="color:#c0392b">{{ it.deduction || 0 }}</td>
              <td><b>{{ finalScore(it) }}</b></td>
              <td><span class="tag" :class="stClass(ov.level, it.level)">{{ stText(ov.level, it.level) || '—' }}</span></td>
              <td>{{ it.sign_image ? '已签' : '未签' }}</td>
              <td><a class="btn btn-outline btn-sm" @click="openDeduct(it)">酌情减分</a></td>
            </tr>
            <tr v-if="!(items || []).length"><td colspan="7" style="text-align:center;color:var(--text-3);padding:22px">该年度暂无考核明细</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 违规联动 -->
    <div v-else-if="mainTab === 'violation'" style="overflow:auto;padding:16px 20px">
      <div class="pf-tip" style="margin-bottom:12px">系统自动关联该驾驶员本年靶向教育违规记录，提示安全员「酌情减分」（人工确认，不自动扣，保留裁量权）。</div>
      <button class="btn btn-primary" @click="openVio">查看驾驶员违规（联动 14 培训教育）</button>
    </div>

    <!-- 创建年度考核 -->
    <el-dialog v-model="createDlg" title="创建年度考核" width="480px">
      <div class="form-row"><label>考核年度</label><input v-model.number="createForm.year" type="number" /></div>
      <div class="form-row"><label>说明</label><input value="系统按岗位自动生成考核明细，默认100分" disabled /></div>
      <template #footer><button class="btn btn-primary" @click="submitCreate">创建并生成明细</button></template>
    </el-dialog>

    <!-- 违规联动 -->
    <el-dialog v-model="vioDlg" title="驾驶员违规联动（提示）" width="640px">
      <table class="table bm-table" style="min-width:560px">
        <thead><tr><th>时间</th><th>驾驶员</th><th>违规类型</th><th>教育落实</th></tr></thead>
        <tbody>
          <tr v-for="v in vios" :key="v.id"><td>{{ d16(v.violation_time) }}</td><td>{{ v.driver_name }}</td><td>{{ v.violation_type }}</td><td>{{ v.edu_status }}</td></tr>
          <tr v-if="!vios.length"><td colspan="4" style="text-align:center;color:var(--text-3);padding:22px">本年无靶向教育违规记录</td></tr>
        </tbody>
      </table>
    </el-dialog>

    <!-- 酌情减分 -->
    <el-dialog v-model="deductDlg" title="酌情减分（人工确认）" width="480px">
      <div class="form-row"><label>岗位</label><input :value="deductForm.postName" disabled /></div>
      <div class="form-row"><label>减分幅度</label><input v-model.number="deductForm.deduction" type="number" min="0" /></div>
      <div class="form-row"><label>减分说明</label><textarea v-model="deductForm.deductionNote" rows="3" placeholder="如：超速违规，酌情减5分"></textarea></div>
      <template #footer><button class="btn btn-primary" @click="submitDeduct">确认减分</button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bm-page { padding: 0 0 30px; }
.pf-head { display:flex;justify-content:space-between;align-items:center;padding:18px 20px 6px }
.pf-title { font-size:18px;font-weight:700 }
.pf-sub { color:var(--text-3);font-size:13px }
.pf-stats { display:flex;gap:12px;padding:10px 20px;flex-wrap:wrap }
.pf-card { flex:1;min-width:120px;background:#fafbfc;border:1px solid #eee;border-radius:10px;padding:14px 16px }
.pf-card .n { font-size:24px;font-weight:700;color:#1a73e8 }
.pf-card .l { color:var(--text-3);font-size:13px;margin-top:4px }
.pf-tabs { display:flex;gap:6px;padding:6px 20px;border-bottom:1px solid #eee }
.pf-tab { padding:8px 16px;cursor:pointer;border-radius:8px 8px 0 0;color:var(--text-3) }
.pf-tab.on { background:#eef3ff;color:#1a73e8;font-weight:600 }
.table { width:100%;border-collapse:collapse;margin:0 20px;max-width:calc(100% - 40px) }
.bm-table td,.bm-table th { border:1px solid #e3e3e3;padding:8px 10px;font-size:13px }
.bm-table th { background:#f6f7f9 }
.bm-table tr.on { background:#eef3ff }
.tag { display:inline-block;padding:2px 8px;border-radius:10px;font-size:12px }
.tag-gray { background:#eee;color:#666 } .tag-blue { background:#e3f0ff;color:#1a73e8 } .tag-green { background:#e6f7ec;color:#1a8a4a } .tag-red { background:#fde8e8;color:#c0392b }
.btn { padding:7px 14px;border-radius:8px;border:1px solid #d0d7de;background:#fff;cursor:pointer;font-size:13px }
.btn-primary { background:#1a73e8;color:#fff;border-color:#1a73e8 }
.btn-outline { background:#fff;color:#1a73e8;border-color:#1a73e8 }
.btn-sm { padding:4px 10px;font-size:12px;margin-right:6px }
.form-row { display:flex;align-items:center;margin:10px 0 }
.form-row label { width:90px;color:var(--text-3);font-size:13px }
.form-row input,.form-row select,.form-row textarea { flex:1;padding:7px 10px;border:1px solid #d0d7de;border-radius:7px;font-size:13px }
.pf-tip { color:#a05a00;background:#fff7e6;border:1px solid #ffe0a3;border-radius:7px;padding:8px 12px }
</style>
