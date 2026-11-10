<script setup>
/** C3 事故管理 + 交通违法 · 完整流程闭环（详细设计 16 + 20）
 *  事故登记 → 自动生成标准格式事故快报表（带盖章） → 年度自动汇总；
 *  事故处理走人工增值服务（联系平台，收费）；交通违法登记联动靶向教育。
 *  一键生成盒十（普货）/盒十一（危货）·事故档案 整盒 PDF。 */
import { ref, onMounted } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'

const ov = ref({ company: '—', isDanger: false, accStatus: {}, svcStatus: {}, eduStatus: {}, accType: {}, severity: {}, stats: {}, records: [], violations: [], services: [], summaries: [] })
const loading = ref(false)
const mainTab = ref('record')

async function load() {
  loading.value = true
  try { const r = await request.get('/flow/accident/overview'); ov.value = r.data || ov.value } catch (e) {}
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
const STATUS = { reported: ['已登记', 'tag-gray'], processing: ['处理中', 'tag-blue'], closed: ['已结案', 'tag-green'] }
const SVC = { pending: ['待受理', 'tag-gray'], processing: ['服务中', 'tag-blue'], completed: ['已完成', 'tag-green'] }
const EDU = { none: ['未推送', 'tag-gray'], pushed: ['已推送', 'tag-blue'], learned: ['已学习', 'tag-green'], filed: ['已归档', 'tag-green'] }
const stText = (m, s) => ((m[s] || [s])[0])
const stClass = (m, s) => ((m[s] || ['', 'tag-gray'])[1])

// 事故登记
const recDlg = ref(false)
const recForm = ref({ accidentTime: '', location: '', vehiclePlate: '', accidentType: 'collision', involvePerson: '', description: '', lossAmount: '', responsibility: '', severity: 'minor', photos: '', source: 'pc' })
function openRec() { recForm.value = { accidentTime: '', location: '', vehiclePlate: '', accidentType: 'collision', involvePerson: '', description: '', lossAmount: '', responsibility: '', severity: 'minor', photos: '', source: 'pc' }; recDlg.value = true }
async function submitRec() {
  const r = await act('/flow/accident/record', 'post', { ...recForm.value }, '登记事故', false)
  if (r) { recDlg.value = false; ElMessage.success(`已登记 ${r.accidentNo}，快报表已生成`); await load() }
}

// 事故详情抽屉
const drawer = ref(false)
const cur = ref({})
async function openDetail(row) {
  const r = await request.get('/flow/accident/record/' + String(row.id))
  cur.value = r.data || {}; drawer.value = true
}
async function regenReport() { const r = await act(`/flow/accident/record/${String(cur.value.id)}/quick-report`, 'post', {}, '重新生成快报', false); if (r) await openDetail(cur.value) }
async function changeStatus(st) { const r = await act(`/flow/accident/record/${String(cur.value.id)}/status`, 'post', { status: st }, '状态变更', false); if (r) await openDetail(cur.value) }

// 交通违法
const vioDlg = ref(false)
const vioForm = ref({ violationTime: '', vehiclePlate: '', driverName: '', violationType: '', location: '', punishNo: '', fineAmount: '', deductPoints: '', description: '', source: 'pc' })
function openVio() { vioForm.value = { violationTime: '', vehiclePlate: '', driverName: '', violationType: '', location: '', punishNo: '', fineAmount: '', deductPoints: '', description: '', source: 'pc' }; vioDlg.value = true }
async function submitVio() { const r = await act('/flow/accident/violation', 'post', { ...vioForm.value }, '登记违法', false); if (r) { vioDlg.value = false; await load() } }
async function pushEdu(row) {
  const r = await act(`/flow/accident/violation/${String(row.id)}/edu`, 'post', {}, '推送靶向教育', false)
  if (r) ElMessage.success('已推送靶向教育：' + r.planName)
  await load()
}

// 增值服务
const svcDlg = ref(false)
const svcForm = ref({ accidentId: '', serviceType: '', serviceDesc: '', contactName: '', contactPhone: '' })
function openSvc() { svcForm.value = { accidentId: '', serviceType: '', serviceDesc: '', contactName: '', contactPhone: '' }; svcDlg.value = true }
async function submitSvc() { const r = await act('/flow/accident/service', 'post', { ...svcForm.value }, '提交服务申请', false); if (r) { svcDlg.value = false; await load() } }

// 整盒归档
async function doArchive() {
  const r = await act('/flow/accident/archive', 'post', {}, '整盒归档', false)
  if (r) { ElMessage.success(`已归档到 ${r.box}`); openPdf(r.pdfId) }
}

function d10(v) { return v ? String(v).slice(0, 10) : '—' }
function d16(v) { return v ? String(v).slice(0, 16).replace('T', ' ') : '—' }
</script>

<template>
  <div class="bm-page">
    <div class="pf-head">
      <div>
        <div class="pf-title">{{ ov.company }} · 事故管理与交通违法</div>
        <div class="pf-sub">{{ ov.isDanger ? '危货企业 · 归档至盒十一' : '普货企业 · 归档至盒十' }}</div>
      </div>
      <button class="btn btn-primary" @click="doArchive">整盒归档（盒{{ ov.isDanger ? '十一' : '十' }}）</button>
    </div>

    <div class="pf-stats">
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).accidents || 0 }}</div><div class="l">事故登记</div></div>
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).reports || 0 }}</div><div class="l">快报表</div></div>
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).violations || 0 }}</div><div class="l">交通违法</div></div>
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).eduPushed || 0 }}</div><div class="l">已推靶向教育</div></div>
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).services || 0 }}</div><div class="l">增值服务</div></div>
    </div>

    <div class="pf-tabs">
      <span :class="['pf-tab', mainTab === 'record' && 'on']" @click="mainTab = 'record'">事故登记</span>
      <span :class="['pf-tab', mainTab === 'violation' && 'on']" @click="mainTab = 'violation'">交通违法</span>
      <span :class="['pf-tab', mainTab === 'summary' && 'on']" @click="mainTab = 'summary'">年度汇总</span>
      <span :class="['pf-tab', mainTab === 'service' && 'on']" @click="mainTab = 'service'">增值服务</span>
    </div>

    <!-- 事故登记 -->
    <div v-if="mainTab === 'record'" style="overflow:auto">
      <div class="pf-sub" style="padding:12px 20px 0">事故列表（{{ (ov.records || []).length }} 起）</div>
      <button class="btn btn-primary" style="margin:10px 20px" @click="openRec">+ 新增事故</button>
      <table class="table bm-table" style="min-width:880px">
        <thead><tr><th>事故编号</th><th>时间</th><th>地点</th><th>类型</th><th>等级</th><th>损失(元)</th><th>状态</th><th>快报</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="r in (ov.records || [])" :key="r.id">
            <td><b>{{ r.accident_no }}</b></td><td>{{ d16(r.accident_time) }}</td><td>{{ r.location }}</td>
            <td>{{ stText(ov.accType, r.accident_type) }}</td><td>{{ stText(ov.severity, r.severity) }}</td>
            <td>{{ r.loss_amount || '—' }}</td>
            <td><span class="tag" :class="stClass(ov.accStatus, r.status)">{{ stText(ov.accStatus, r.status) }}</span></td>
            <td>{{ r.hasReport ? '✓' : '—' }}</td>
            <td><a class="btn btn-outline btn-sm" @click="openDetail(r)">详情</a></td>
          </tr>
          <tr v-if="!(ov.records || []).length"><td colspan="9" style="text-align:center;color:var(--text-3);padding:26px">暂无事故登记，点右上「新增事故」</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 交通违法 -->
    <div v-else-if="mainTab === 'violation'" style="overflow:auto">
      <div class="pf-sub" style="padding:12px 20px 0">交通违法/违规登记（{{ (ov.violations || []).length }} 条）</div>
      <button class="btn btn-primary" style="margin:10px 20px" @click="openVio">+ 登记违法</button>
      <table class="table bm-table" style="min-width:840px">
        <thead><tr><th>时间</th><th>车牌</th><th>驾驶员</th><th>违法类型</th><th>罚款</th><th>扣分</th><th>教育落实</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="v in (ov.violations || [])" :key="v.id">
            <td>{{ d10(v.violation_time) }}</td><td>{{ v.vehicle_plate }}</td><td>{{ v.driver_name }}</td>
            <td>{{ v.violation_type }}</td><td>{{ v.fine_amount || '—' }}</td><td>{{ v.deduct_points || 0 }}</td>
            <td><span class="tag" :class="stClass(ov.eduStatus, v.edu_status)">{{ stText(ov.eduStatus, v.edu_status) }}</span></td>
            <td><a class="btn btn-outline btn-sm" v-if="v.edu_status === 'none'" @click="pushEdu(v)">推靶向教育</a>
                <span v-else style="color:var(--text-3)">已推送</span></td>
          </tr>
          <tr v-if="!(ov.violations || []).length"><td colspan="8" style="text-align:center;color:var(--text-3);padding:26px">暂无违法登记</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 年度汇总 -->
    <div v-else-if="mainTab === 'summary'" style="overflow:auto">
      <div class="pf-sub" style="padding:12px 20px 0">年度事故汇总统计表（按年自动聚合）</div>
      <table class="table bm-table" style="min-width:640px">
        <thead><tr><th>年度</th><th>事故总数</th><th>直接经济损失</th><th>轻微</th><th>一般</th><th>较大及以上</th><th>汇总PDF</th></tr></thead>
        <tbody>
          <tr v-for="s in (ov.summaries || [])" :key="s.id">
            <td><b>{{ s.year }}</b></td><td>{{ s.total_count }}</td><td>{{ s.total_loss }} 元</td>
            <td>{{ s.minor_count }}</td><td>{{ s.general_count }}</td><td>{{ s.major_count }}</td>
            <td><a class="btn btn-outline btn-sm" @click="openPdf(s.pdf_id)">查看</a></td>
          </tr>
          <tr v-if="!(ov.summaries || []).length"><td colspan="7" style="text-align:center;color:var(--text-3);padding:26px">暂无汇总，登记事故后点击对应年度生成</td></tr>
        </tbody>
      </table>
      <div style="padding:10px 20px">
        <button class="btn btn-primary" @click="(async () => { const y = new Date().getFullYear(); await act(`/flow/accident/summary/${y}/pdf`,'post',{},'生成'+y+'年度汇总',true); })()">生成 {{ new Date().getFullYear() }} 年度汇总 PDF</button>
      </div>
    </div>

    <!-- 增值服务 -->
    <div v-else-if="mainTab === 'service'" style="overflow:auto">
      <div class="pf-sub" style="padding:12px 20px 0">联系人工处理（增值服务，收费）</div>
      <button class="btn btn-primary" style="margin:10px 20px" @click="openSvc">+ 提交服务申请</button>
      <div class="pf-tip" style="padding:0 20px 8px">事故处理（四不放过资料、对接交警/保险/主管部门）由平台人工服务完成，系统仅做登记。</div>
      <table class="table bm-table" style="min-width:680px">
        <thead><tr><th>服务项目</th><th>需求描述</th><th>联系人</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="s in (ov.services || [])" :key="s.id">
            <td><b>{{ s.service_type }}</b></td><td>{{ s.service_desc }}</td><td>{{ s.contact_name }}</td>
            <td><span class="tag" :class="stClass(ov.svcStatus, s.status)">{{ stText(ov.svcStatus, s.status) }}</span></td>
          </tr>
          <tr v-if="!(ov.services || []).length"><td colspan="4" style="text-align:center;color:var(--text-3);padding:26px">暂无服务申请</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 事故详情抽屉 -->
    <el-drawer v-model="drawer" title="事故详情" size="60%" direction="rtl">
      <div v-if="cur.id">
        <div class="pf-sub">事故编号 {{ cur.accident_no }} · 状态 <span class="tag" :class="stClass(ov.accStatus, cur.status)">{{ stText(ov.accStatus, cur.status) }}</span></div>
        <div style="padding:0 12px">
          <p><b>时间：</b>{{ d16(cur.accident_time) }} ｜ <b>地点：</b>{{ cur.location }}</p>
          <p><b>类型：</b>{{ stText(ov.accType, cur.accident_type) }} ｜ <b>等级：</b>{{ stText(ov.severity, cur.severity) }} ｜ <b>损失：</b>{{ cur.loss_amount || '—' }} 元</p>
          <p><b>责任初判：</b>{{ cur.responsibility || '—' }}</p>
          <p><b>经过：</b>{{ cur.description || '—' }}</p>
          <p><b>涉及人员：</b>{{ cur.involve_person || '—' }}</p>
        </div>
        <div v-if="cur.quickReport" class="pf-sub">事故快报表（标准格式 · 带盖章）</div>
        <div class="ql-preview" v-html="cur.quickReport ? cur.quickReport.reportContent : ''" style="padding:0 12px;border:1px solid #eee;border-radius:6px;min-height:60px"></div>
        <div style="padding:12px;display:flex;gap:8px;flex-wrap:wrap">
          <button class="btn btn-outline" @click="regenReport">重新生成快报</button>
          <button class="btn btn-outline" v-if="cur.quickReport" @click="openPdf(cur.quickReport.pdfId)">查看快报PDF</button>
          <button class="btn btn-outline" v-if="cur.status === 'reported'" @click="changeStatus('processing')">标记处理中</button>
          <button class="btn btn-primary" v-if="cur.status !== 'closed'" @click="changeStatus('closed')">结案</button>
        </div>
      </div>
    </el-drawer>

    <!-- 新增事故 -->
    <el-dialog v-model="recDlg" title="新增事故登记" width="560px">
      <div class="form-row"><label>发生时间</label><input v-model="recForm.accidentTime" type="datetime-local" /></div>
      <div class="form-row"><label>事故地点</label><input v-model="recForm.location" placeholder="如：G15沈海高速K123+500" /></div>
      <div class="form-row"><label>车牌号</label><input v-model="recForm.vehiclePlate" /></div>
      <div class="form-row"><label>事故类型</label>
        <select v-model="recForm.accidentType"><option v-for="(n,k) in ov.accType" :key="k" :value="k">{{ n }}</option></select>
      </div>
      <div class="form-row"><label>事故等级</label>
        <select v-model="recForm.severity"><option v-for="(n,k) in ov.severity" :key="k" :value="k">{{ n }}</option></select>
      </div>
      <div class="form-row"><label>直接经济损失</label><input v-model="recForm.lossAmount" placeholder="元" /></div>
      <div class="form-row"><label>责任初判</label><input v-model="recForm.responsibility" /></div>
      <div class="form-row"><label>涉及人员</label><input v-model="recForm.involvePerson" placeholder="姓名/岗位/伤亡，可分号分隔" /></div>
      <div class="form-row"><label>事故经过</label><textarea v-model="recForm.description" rows="3"></textarea></div>
      <div class="form-row"><label>现场照片URL</label><input v-model="recForm.photos" placeholder="逗号分隔多个URL" /></div>
      <template #footer><button class="btn btn-primary" @click="submitRec">登记并生成快报</button></template>
    </el-dialog>

    <!-- 登记违法 -->
    <el-dialog v-model="vioDlg" title="登记交通违法/违规" width="560px">
      <div class="form-row"><label>违法时间</label><input v-model="vioForm.violationTime" type="datetime-local" /></div>
      <div class="form-row"><label>车牌号</label><input v-model="vioForm.vehiclePlate" /></div>
      <div class="form-row"><label>驾驶员</label><input v-model="vioForm.driverName" /></div>
      <div class="form-row"><label>违法类型</label><input v-model="vioForm.violationType" placeholder="如：超速、疲劳驾驶" /></div>
      <div class="form-row"><label>地点</label><input v-model="vioForm.location" /></div>
      <div class="form-row"><label>处罚决定书号</label><input v-model="vioForm.punishNo" /></div>
      <div class="form-row"><label>罚款(元)</label><input v-model="vioForm.fineAmount" /></div>
      <div class="form-row"><label>扣分</label><input v-model="vioForm.deductPoints" /></div>
      <div class="form-row"><label>说明</label><input v-model="vioForm.description" /></div>
      <template #footer><button class="btn btn-primary" @click="submitVio">登记</button></template>
    </el-dialog>

    <!-- 增值服务 -->
    <el-dialog v-model="svcDlg" title="提交人工增值服务申请" width="560px">
      <div class="form-row"><label>关联事故ID</label><input v-model="svcForm.accidentId" placeholder="可选" /></div>
      <div class="form-row"><label>服务项目</label>
        <select v-model="svcForm.serviceType">
          <option value="资料整理">事故资料整理</option>
          <option value="四不放过">四不放过资料制作</option>
          <option value="处理指导">事故处理指导（对接交警/保险/主管部门）</option>
          <option value="责任分析">事故责任分析+整改建议</option>
          <option value="其他">其他咨询</option>
        </select>
      </div>
      <div class="form-row"><label>需求描述</label><textarea v-model="svcForm.serviceDesc" rows="3"></textarea></div>
      <div class="form-row"><label>联系人</label><input v-model="svcForm.contactName" /></div>
      <div class="form-row"><label>联系电话</label><input v-model="svcForm.contactPhone" /></div>
      <template #footer><button class="btn btn-primary" @click="submitSvc">提交申请</button></template>
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
.tag { display:inline-block;padding:2px 8px;border-radius:10px;font-size:12px }
.tag-gray { background:#eee;color:#666 } .tag-blue { background:#e3f0ff;color:#1a73e8 } .tag-green { background:#e6f7ec;color:#1a8a4a }
.btn { padding:7px 14px;border-radius:8px;border:1px solid #d0d7de;background:#fff;cursor:pointer;font-size:13px }
.btn-primary { background:#1a73e8;color:#fff;border-color:#1a73e8 }
.btn-outline { background:#fff;color:#1a73e8;border-color:#1a73e8 }
.btn-sm { padding:4px 10px;font-size:12px;margin-right:6px }
.form-row { display:flex;align-items:center;margin:10px 0 }
.form-row label { width:110px;color:var(--text-3);font-size:13px }
.form-row input,.form-row select,.form-row textarea { flex:1;padding:7px 10px;border:1px solid #d0d7de;border-radius:7px;font-size:13px }
.ql-preview { font-size:12px;line-height:1.7 }
.pf-tip { color:#a05a00;background:#fff7e6;border:1px solid #ffe0a3;border-radius:7px;padding:8px 12px;margin:0 20px }
</style>
