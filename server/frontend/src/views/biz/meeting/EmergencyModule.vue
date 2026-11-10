<script setup>
/** B15 应急管理 · 完整流程闭环（详细设计 15，定稿 v1）
 *  多子域：应急预案（勾选场景组合生成，只读预览）/ 应急演练（系统自动生成计划+方案+评估，企业只补三要素）/
 *  应急资源（队伍+器材，到期预警）/ 危货专属（备案回执上传、值班制度+值班表打印表单）。
 *  预案正文只读预览、数据取自企业账号；一键生成盒七/盒八整盒应急 PDF（带签名+盖章）大归档。
 *  小程序：演练签到走统一签到入口（与会议签到同一入口）。 */
import { computed, onMounted, ref } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'
import Icon from '@/components/Icon.vue'

const ov = ref({
  company: '—', isDanger: false, stats: {}, planStatus: {}, dpStatus: {}, drillStatus: {}, drillTypes: [],
  planTpls: [], plans: [], drillPlans: [], drills: [], teams: [], equipment: [], expiringEquip: [], dutyList: [], archives: [], refs: [],
})
const loading = ref(false)
const busy = ref(false)
const mainTab = ref('plan') // plan 应急预案 / drill 应急演练 / resource 应急资源 / danger 危货专属

function statusText(map, s) { const v = (map || {})[s]; return Array.isArray(v) ? v[0] : (v || '—') }
function d10(v) { return v ? String(v).slice(0, 10) : '—' }
function d16(v) { return v ? String(v).slice(0, 16).replace('T', ' ') : '—' }

async function load() {
  loading.value = true
  try {
    const r = await request.get('/flow/emergency/overview')
    ov.value = r.data || ov.value
  } catch (e) { /* 拦截器已提示 */ }
  finally { loading.value = false }
}
onMounted(load)

async function act(url, method, body, label, withDetail = true) {
  busy.value = true
  try {
    const r = method === 'put' ? await request.put(url, body || {})
      : method === 'delete' ? await request.delete(url, { data: body || {} })
      : await request.post(url, body || {})
    if (r.data && r.data.ok === false) { ElMessage.warning(r.data.msg || '操作未成功'); return null }
    if (label) ElMessage.success(label + '成功')
    await load()
    if (withDetail && drawer.value) await refreshDetail()
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

// ---------------- 预案 ----------------
const genDlg = ref(false)
const genForm = ref({ planName: '', sceneCodes: [] })
function openGen() {
  genForm.value = { planName: '', sceneCodes: (ov.value.planTpls || []).map((t) => t.tpl_code) }
  genDlg.value = true
}
function toggleScene(code) {
  const arr = genForm.value.sceneCodes
  const i = arr.indexOf(code)
  if (i >= 0) arr.splice(i, 1); else arr.push(code)
}
async function submitGen() {
  const r = await act('/flow/emergency/plan/generate', 'post', { planName: genForm.value.planName, sceneCodes: genForm.value.sceneCodes }, '生成预案', false)
  if (r) { genDlg.value = false; ElMessage.success(`已生成预案，勾选 ${r.sceneCount} 个场景`) }
}
const PLAN_STATUS = { draft: ['草稿', 'tag-gray'], published: ['已发布', 'tag-blue'], archived: ['已归档', 'tag-green'] }
function planStatusText(s) { return (PLAN_STATUS[s] || ['—', 'tag-gray'])[0] }
function planStatusClass(s) { return (PLAN_STATUS[s] || ['—', 'tag-gray'])[1] }

// ---------------- 演练 ----------------
async function autoDrillPlan() {
  const r = await act('/flow/emergency/drill-plan/generate', 'post', { year: new Date().getFullYear() }, '生成演练计划', false)
  if (r) ElMessage.success(`已生成 ${r.itemCount} 个演练项目（含方案+评估表）`)
}
// start 作用于「演练项目(item)」；照片/评估/完成作用于其下「演练执行(drill)」
async function startDrill(row) { await act(`/flow/emergency/drill/${String(row.id)}/start`, 'post', {}, '开展演练') }
async function drillPhoto(row) {
  const url = window.prompt('演练照片 URL（可填占位）')
  if (url) await act(`/flow/emergency/drill/${String(row.drillId)}/photo`, 'post', { photoUrl: url }, '上传照片', false)
}
async function drillEval(row) {
  const txt = window.prompt('演练评估表内容')
  if (txt) await act(`/flow/emergency/drill/${String(row.drillId)}/eval`, 'post', { evalResult: txt }, '填写评估', false)
}
async function completeDrill(row) { await act(`/flow/emergency/drill/${String(row.drillId)}/complete`, 'post', {}, '完成演练') }
async function openDrillDetailId(id) {
  detailType.value = 'drill'; curId.value = String(id); tab.value = 'info'; drawer.value = true; await refreshDetail()
}

// ---------------- 整盒大归档 ----------------
async function archiveBox() {
  const r = await act('/flow/emergency/archive', 'post', {}, '', false)
  if (r) ElMessage.success(`已整盒归档到 ${r.box}，生成 ${r.size} 字节 PDF`)
}

// ---------------- 详情抽屉 ----------------
const drawer = ref(false)
const detailType = ref('plan')
const curId = ref('')
const detail = ref(null)
const tab = ref('info')
async function openPlanDetail(row) {
  detailType.value = 'plan'; curId.value = String(row.id); tab.value = 'info'; drawer.value = true; await refreshDetail()
}
async function openDrillDetail(row) {
  detailType.value = 'drill'; curId.value = String(row.id); tab.value = 'info'; drawer.value = true; await refreshDetail()
}
async function refreshDetail() {
  busy.value = true
  try {
    detail.value = (await request.get(`/flow/emergency/${detailType.value}/${curId.value}`)).data || null
  } catch (e) { detail.value = null }
  finally { busy.value = false }
}
async function signPlan() { await act(`/flow/emergency/plan/${curId.value}/sign`, 'post', {}, '一键签名') }
async function publishPlan() { await act(`/flow/emergency/plan/${curId.value}/publish`, 'post', {}, '发布预案') }
const DRILL_STATUS = { draft: ['未开始', 'tag-gray'], executing: ['进行中', 'tag-yellow'], completed: ['已完成', 'tag-green'] }
function drillStatusText(s) { return (DRILL_STATUS[s] || ['—', 'tag-gray'])[0] }
function drillStatusClass(s) { return (DRILL_STATUS[s] || ['—', 'tag-gray'])[1] }
</script>

<template>
  <div class="bm-page">
    <div class="page-header bm-header">
      <div>
        <h1>应急管理</h1>
        <div class="desc">预案（勾选场景自动组合）· 演练（系统生成计划，企业只补照片/签到/评估）· 资源（队伍/器材）· 一键整盒归档盒七/盒八</div>
        <div class="bm-meta">
          <span class="tag tag-blue">B15 · 盒七（普货）/ 盒八（危货）</span>
          <span class="tag tag-green">6 套场景范本</span>
          <span class="tag tag-yellow">演练完成率 {{ ov.stats?.completionRate ?? 0 }}%</span>
          <span class="bm-tables">{{ ov.company }} · {{ ov.isDanger ? '危货企业' : '普货企业' }} · 预案 {{ ov.stats?.plans || 0 }} · 演练 {{ ov.stats?.drills || 0 }} · 队伍 {{ ov.stats?.teams || 0 }} · 器材 {{ ov.stats?.equipment || 0 }}</span>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-primary" @click="openGen"><Icon name="plus" :size="15" /> 生成预案</button>
        <button class="btn btn-outline" @click="autoDrillPlan"><Icon name="calendar" :size="15" /> 生成演练计划</button>
        <button class="btn btn-primary" @click="archiveBox"><Icon name="folder" :size="15" /> 整盒归档</button>
        <button class="btn btn-outline" @click="load()"><Icon name="refresh" :size="15" /> 刷新</button>
      </div>
    </div>

    <div class="pf-hint"><Icon name="info" :size="15" />
      预案勾选场景后系统自动组合正文（只读预览，数据取自企业账号，企业不手输）；演练计划与方案/评估表由系统自动生成，企业演练后只补照片+签到+评估即完成；归档为一键生成盒七/盒八整盒 PDF（带签名+盖章）。
    </div>

    <div class="pf-stat-row">
      <div class="stat-card"><div class="num">{{ ov.stats?.plans || 0 }}</div><div class="lbl">预案总数 · 已发布 {{ ov.stats?.planPublished || 0 }}</div></div>
      <div class="stat-card"><div class="num" style="color:#2563eb">{{ ov.stats?.drills || 0 }}</div><div class="lbl">演练总数 · 已完成 {{ ov.stats?.drillCompleted || 0 }}</div></div>
      <div class="stat-card"><div class="num" style="color:#16a34a">{{ ov.stats?.completionRate ?? 0 }}%</div><div class="lbl">演练完成率 · 已归档预案 {{ ov.stats?.planArchived || 0 }}</div></div>
      <div class="stat-card"><div class="num" style="color:#d97706">{{ (ov.expiringEquip || []).length }}</div><div class="lbl">器材临期预警</div></div>
    </div>

    <div class="card pf-flow">
      <div class="card-title">生命周期状态机 <span class="pf-sub">预案：草稿→已发布→已归档　·　演练：未开始→进行中→已完成（三要素齐备）</span></div>
      <div class="pf-flow-row">
        <div class="pf-flow-node"><b>预案</b><span>草稿→发布→归档</span></div>
        <span class="pf-arrow">→</span>
        <div class="pf-flow-node"><b>演练计划</b><span>系统自动生成</span></div>
        <span class="pf-arrow">→</span>
        <div class="pf-flow-node"><b>演练执行</b><span>三要素：照片+签到+评估</span></div>
        <span class="pf-arrow">→</span>
        <div class="pf-flow-node"><b>整盒归档</b><span>盒七/盒八 PDF</span></div>
      </div>
    </div>

    <div class="card" style="padding:0;overflow:hidden">
      <div class="card-title" style="padding:16px 20px 0;display:flex;align-items:center;gap:8px">
        <button class="pf-tab" :class="{ on: mainTab === 'plan' }" @click="mainTab = 'plan'">应急预案</button>
        <button class="pf-tab" :class="{ on: mainTab === 'drill' }" @click="mainTab = 'drill'">应急演练</button>
        <button class="pf-tab" :class="{ on: mainTab === 'resource' }" @click="mainTab = 'resource'">应急资源</button>
        <button v-if="ov.isDanger" class="pf-tab" :class="{ on: mainTab === 'danger' }" @click="mainTab = 'danger'">危货专属</button>
      </div>

      <!-- 预案 -->
      <div v-if="mainTab === 'plan'" style="overflow:auto">
        <table class="table bm-table" v-loading="loading">
          <thead><tr><th>预案名称</th><th>场景数</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="r in (ov.plans || [])" :key="r.id">
              <td><b>{{ r.plan_name || '—' }}</b><span class="pf-sub" style="display:block">{{ r.scene_codes || '' }}</span></td>
              <td>{{ (r.scene_codes || '').split(',').filter(Boolean).length }}</td>
              <td><span class="tag" :class="planStatusClass(r.status)">{{ planStatusText(r.status) }}</span></td>
              <td>
                <a class="btn btn-primary btn-sm" @click="openPlanDetail(r)">详情</a>
                <a v-if="r.status === 'draft'" class="btn btn-outline btn-sm" @click="publishPlan.call(null, r)">发布</a>
                <a class="btn btn-outline btn-sm" @click="openPdf('/flow/emergency/plan/' + String(r.id) + '/preview')">预览</a>
              </td>
            </tr>
            <tr v-if="!(ov.plans || []).length"><td colspan="4" style="text-align:center;color:var(--text-3);padding:26px">暂无预案，点右上「生成预案」勾选场景</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 演练 -->
      <div v-else-if="mainTab === 'drill'" style="overflow:auto">
        <div class="pf-sub" style="padding:12px 20px 0">演练年度计划（{{ (ov.drillPlans || []).length }} 份）</div>
        <table class="table bm-table" style="min-width:680px">
          <thead><tr><th>计划名称</th><th>年度</th><th>项目数</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="p in (ov.drillPlans || [])" :key="p.id">
              <td><b>{{ p.plan_name }}</b></td><td>{{ p.year }}</td><td>{{ p.itemCount }}</td>
              <td><span class="tag tag-blue">{{ p.statusName }}</span></td>
            </tr>
          </tbody>
        </table>
        <div class="pf-sub" style="padding:12px 20px 0">演练项目与执行（{{ (ov.drillItems || []).length }} 项）</div>
        <table class="table bm-table" style="min-width:880px">
          <thead><tr><th>演练项目</th><th>计划时间</th><th>照片/签到</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="d in (ov.drillItems || [])" :key="d.id">
              <td><b>{{ d.drill_name }}</b><span class="pf-sub" style="display:block">{{ d.plan_name }}</span></td>
              <td>{{ d16(d.plan_time) }}</td>
              <td>照片 {{ d.photos || 0 }} / 签到 {{ d.signs || 0 }}</td>
              <td><span class="tag" :class="drillStatusClass(d.status === 'none' ? 'draft' : d.status)">{{ d.status === 'none' ? '未开展' : drillStatusText(d.status) }}</span></td>
              <td>
                <a v-if="d.status === 'none'" class="btn btn-primary btn-sm" @click="startDrill(d)">开展</a>
                <template v-if="d.drillId">
                  <a v-if="d.status === 'executing'" class="btn btn-outline btn-sm" @click="drillPhoto(d)">照片</a>
                  <a v-if="d.status === 'executing'" class="btn btn-outline btn-sm" @click="drillEval(d)">评估</a>
                  <a v-if="d.status === 'executing' && d.canComplete" class="btn btn-primary btn-sm" @click="completeDrill(d)">完成</a>
                  <a v-if="d.status === 'completed'" class="btn btn-outline btn-sm" @click="openDrillDetailId(d.drillId)">查看</a>
                </template>
              </td>
            </tr>
            <tr v-if="!(ov.drillItems || []).length"><td colspan="5" style="text-align:center;color:var(--text-3);padding:26px">暂无演练，点右上「生成演练计划」</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 资源 -->
      <div v-else-if="mainTab === 'resource'">
        <div class="pf-sub" style="padding:12px 20px 0">应急队伍（{{ (ov.teams || []).length }} 支）</div>
        <table class="table bm-table" style="min-width:600px">
          <thead><tr><th>队伍</th><th>角色</th><th>成员</th></tr></thead>
          <tbody>
            <tr v-for="t in (ov.teams || [])" :key="t.id"><td><b>{{ t.team_name }}</b></td><td>{{ t.team_role || '—' }}</td><td>{{ t.members || 0 }} 人</td></tr>
          </tbody>
        </table>
        <div class="pf-sub" style="padding:12px 20px 0">应急器材（临期 <span style="color:#d97706">{{ (ov.expiringEquip || []).length }}</span> 件）</div>
        <table class="table bm-table" style="min-width:760px">
          <thead><tr><th>器材</th><th>类型</th><th>数量</th><th>有效期</th><th>位置</th></tr></thead>
          <tbody>
            <tr v-for="e in (ov.equipment || [])" :key="e.id">
              <td><b>{{ e.equip_name }}</b></td><td>{{ e.equip_type || '—' }}</td><td>{{ e.quantity }}{{ e.unit || '' }}</td>
              <td><span :class="e.expiring ? 'tag tag-yellow' : ''">{{ d10(e.valid_until) }}</span></td><td>{{ e.location || '—' }}</td>
            </tr>
            <tr v-if="!(ov.equipment || []).length"><td colspan="5" style="text-align:center;color:var(--text-3);padding:20px">暂无器材</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 危货 -->
      <div v-else>
        <div class="pf-sub" style="padding:12px 20px 0">危货专属（备案回执上传、值班制度/值班表打印表单手写登记）</div>
        <div class="pf-btn-row" style="padding:12px 20px 0">
          <a class="btn btn-outline btn-sm" @click="openPdf('/flow/emergency/print/duty-rule')">值班制度空白表单</a>
          <a class="btn btn-outline btn-sm" @click="openPdf('/flow/emergency/print/duty')">值班表空白表单</a>
        </div>
        <table class="table bm-table" style="min-width:600px">
          <thead><tr><th>值班日期</th><th>值班人</th><th>班次</th></tr></thead>
          <tbody>
            <tr v-for="d in (ov.dutyList || [])" :key="d.id"><td>{{ d10(d.duty_date) }}</td><td>{{ d.name || '—' }}</td><td>{{ d.shift || '—' }}</td></tr>
            <tr v-if="!(ov.dutyList || []).length"><td colspan="3" style="text-align:center;color:var(--text-3);padding:20px">暂无值班记录</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 联动引用 -->
    <div class="card pf-group">
      <div class="card-title">模块间联动 <span class="pf-sub">队伍/签到同源 B1 人员 · 一键整盒归档盒七/盒八（B7）· 演练签到并入小程序统一签到入口</span></div>
      <div class="pf-ref-row">
        <span v-for="r in (ov.refs || [])" :key="r.module" class="tag tag-gray">{{ r.name }} · {{ r.usage }}（{{ r.count }}）</span>
      </div>
    </div>

    <!-- 详情抽屉 -->
    <div class="pf-mask" :class="{ on: drawer }" @click.self="drawer = false">
      <div class="pf-drawer">
        <div class="pf-dh">
          <div>
            <h2>{{ detail?.plan_name || detail?.drill_name || (detailType === 'plan' ? '预案详情' : '演练详情') }}</h2>
            <div class="pf-sub" v-if="detailType === 'plan'">{{ ov.company }}
              <span class="tag" :class="planStatusClass(detail?.status)" style="margin-left:6px">{{ planStatusText(detail?.status) }}</span>
              <span class="pf-sub" style="margin-left:8px">场景：{{ detail?.scene_codes }}</span>
            </div>
            <div class="pf-sub" v-else>{{ ov.company }}
              <span class="tag" :class="drillStatusClass(detail?.status)" style="margin-left:6px">{{ drillStatusText(detail?.status) }}</span>
            </div>
          </div>
          <button class="pf-close" @click="drawer = false">✕</button>
        </div>
        <div class="pf-tabs">
          <button class="pf-tab" :class="{ on: tab === 'info' }" @click="tab = 'info'">基本信息</button>
          <button v-if="detailType === 'plan'" class="pf-tab" :class="{ on: tab === 'preview' }" @click="tab = 'preview'">预案正文（只读预览）</button>
          <button v-if="detailType === 'drill'" class="pf-tab" :class="{ on: tab === 'sign' }" @click="tab = 'sign'">签到</button>
          <button v-if="detailType === 'drill'" class="pf-tab" :class="{ on: tab === 'photo' }" @click="tab = 'photo'">照片/评估</button>
          <button class="pf-tab" :class="{ on: tab === 'ref' }" @click="tab = 'ref'">归档</button>
        </div>
        <div v-if="detail" class="pf-body" v-loading="busy">
          <!-- 预案信息 -->
          <template v-if="detailType === 'plan' && tab === 'info'">
            <div class="card pf-actions">
              <div class="card-title">逐条操作 <span class="pf-sub">当前 {{ planStatusText(detail.status) }}</span></div>
              <div class="pf-btn-row">
                <a v-if="detail.status === 'draft'" class="btn btn-primary btn-sm" @click="signPlan">一键签名</a>
                <a v-if="detail.status === 'draft'" class="btn btn-outline btn-sm" @click="publishPlan">发布</a>
                <a class="btn btn-outline btn-sm" @click="openPdf('/flow/emergency/plan/' + curId + '/preview')">只读预览</a>
              </div>
            </div>
            <div class="card pf-group">
              <div class="card-title">预案登记信息</div>
              <table class="pf-subtable"><tbody>
                <tr><th>预案名称</th><td colspan="3">{{ detail.plan_name }}</td></tr>
                <tr><th>版本</th><td>{{ detail.version }}</td><th>场景</th><td>{{ detail.scene_codes }}</td></tr>
                <tr><th>状态</th><td>{{ planStatusText(detail.status) }}</td><th>类型</th><td>{{ detail.cargo_type === 'danger' ? '危货' : '普货' }}</td></tr>
              </tbody></table>
            </div>
            <div v-if="detail.signInfo && detail.signInfo.length" class="card pf-group">
              <div class="card-title">签名留痕</div>
              <div class="pf-ref-row"><span v-for="(s, i) in detail.signInfo" :key="i" class="tag tag-green">{{ s.role }} · {{ s.name }}</span></div>
            </div>
          </template>

          <!-- 预案只读预览 -->
          <template v-if="detailType === 'plan' && tab === 'preview'">
            <div class="card pf-group">
              <div class="card-title">预案正文（只读预览，数据取自企业账号） <span class="pf-sub">企业不手输</span></div>
              <div class="pf-notice" style="white-space:pre-wrap" v-html="detail.contentHtml"></div>
            </div>
          </template>

          <!-- 演练签到 -->
          <template v-if="detailType === 'drill' && tab === 'sign'">
            <div class="card pf-group">
              <div class="card-title">演练签到（并入小程序统一签到入口）</div>
              <table class="pf-subtable">
                <thead><tr><th>序号</th><th>姓名</th><th>状态</th></tr></thead>
                <tbody>
                  <tr v-for="(a, i) in (detail.signs || [])" :key="a.id"><td>{{ i + 1 }}</td><td>{{ a.name || '—' }}</td><td>{{ a.sign_status === 'signed' ? '已签到' : '待签到' }}</td></tr>
                  <tr v-if="!(detail.signs || []).length"><td colspan="3" style="text-align:center;color:var(--text-3)">暂无签到（企业真演练后由小程序统一入口签到）</td></tr>
                </tbody>
              </table>
            </div>
          </template>

          <!-- 演练照片/评估 -->
          <template v-if="detailType === 'drill' && tab === 'photo'">
            <div class="card pf-group">
              <div class="card-title">演练照片（{{ (detail.photos || []).length }}）</div>
              <div class="pf-ref-row"><span v-for="(p, i) in (detail.photos || [])" :key="i" class="tag tag-gray">{{ p.photo_url }}</span></div>
            </div>
            <div class="card pf-group">
              <div class="card-title">演练评估表</div>
              <div class="pf-notice" style="white-space:pre-wrap">{{ detail.eval_result || '（尚未填写）' }}</div>
            </div>
          </template>

          <!-- 归档 -->
          <template v-if="tab === 'ref'">
            <div class="card pf-group">
              <div class="card-title">一键整盒归档 <span class="pf-sub">盒七（普货）/ 盒八（危货）· 应急管理 整盒 PDF（带签名+盖章）</span></div>
              <div class="pf-btn-row"><a class="btn btn-primary btn-sm" @click="archiveBox">整盒归档</a></div>
              <table class="pf-subtable">
                <thead><tr><th>归档成品</th><th>归档日期</th><th>状态</th></tr></thead>
                <tbody>
                  <tr v-for="a in (ov.archives || [])" :key="a.id"><td>{{ a.title }}</td><td>{{ d10(a.occur_date) }}</td><td>已归档</td></tr>
                  <tr v-if="!(ov.archives || []).length"><td colspan="3" style="text-align:center;color:var(--text-3)">暂无归档记录</td></tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- 生成预案弹窗 -->
    <div class="pf-mask" :class="{ on: genDlg }" @click.self="genDlg = false" style="justify-content:center;align-items:center">
      <div class="pf-modal" @click.stop>
        <div class="pf-modal-h"><h3>生成应急预案（勾选场景，系统组合正文）</h3><button class="pf-close" @click="genDlg = false">✕</button></div>
        <div class="pf-modal-b">
          <div class="pf-form-grid">
            <div class="f-item" style="grid-column:1/3"><label>预案名称</label><input v-model="genForm.planName" placeholder="如：2026年度安全生产应急预案"></div>
            <div class="f-item" style="grid-column:1/3"><label>勾选场景（系统按综合→专项→现场处置顺序组合）</label>
              <div class="pf-choose">
                <label v-for="t in (ov.planTpls || [])" :key="t.id" class="pf-chip" :class="{ on: genForm.sceneCodes.includes(t.tpl_code) }">
                  <input type="checkbox" :checked="genForm.sceneCodes.includes(t.tpl_code)" @change="toggleScene(t.tpl_code)"> {{ t.tpl_name }}
                </label>
              </div>
            </div>
          </div>
        </div>
        <div class="pf-modal-f">
          <button class="btn btn-outline" @click="genDlg = false">取消</button>
          <button class="btn btn-primary" :disabled="busy" @click="submitGen">生成预案</button>
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
.bm-table { width: 100%; min-width: 760px; }
.bm-table a { cursor: pointer; margin-right: 6px; }
.pf-sub { font-size: 12.5px; color: var(--text-3); font-weight: 400; }
.pf-hint { background: #EFF6FF; border: 1px solid #93C5FD; border-radius: 8px; padding: 10px 14px; font-size: 13px; color: #1e40af; display: flex; align-items: center; gap: 8px; }
.pf-stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.stat-card { background: #fff; border: 1px solid var(--border); border-radius: 10px; padding: 16px 18px; }
.stat-card .num { font-size: 26px; font-weight: 700; color: var(--brand-600, #1a5bb0); line-height: 1.2; }
.stat-card .lbl { font-size: 12.5px; color: var(--text-3); margin-top: 4px; }
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
.pf-notice { background: #fff; border: 1px solid var(--border); border-radius: 8px; padding: 14px 16px; font-size: 13px; line-height: 1.8; }
.pf-ref-row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.pf-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
.pf-form-grid .f-item { display: flex; flex-direction: column; gap: 5px; }
.pf-form-grid label { font-size: 12.5px; color: var(--text-3); }
.pf-form-grid input, .pf-form-grid select, .pf-form-grid textarea { border: 1px solid var(--border); border-radius: 8px; padding: 8px 9px; font-size: 13px; font-family: inherit; }
.pf-choose { display: flex; flex-wrap: wrap; gap: 6px; max-height: 200px; overflow: auto; border: 1px solid var(--border); border-radius: 8px; padding: 8px; }
.pf-chip { display: inline-flex; align-items: center; gap: 4px; font-size: 12.5px; padding: 4px 8px; border: 1px solid var(--border); border-radius: 16px; cursor: pointer; background: #fff; }
.pf-chip.on { border-color: var(--brand-600, #1a5bb0); background: #EFF6FF; color: var(--brand-600, #1a5bb0); }
.pf-chip input { display: none; }
.pf-modal { width: 720px; max-width: 94vw; max-height: 88vh; margin: auto; background: #fff; border-radius: 12px; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,.25); }
.pf-modal-h { padding: 16px 22px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; }
.pf-modal-h h3 { margin: 0; font-size: 17px; }
.pf-modal-b { padding: 18px 22px; overflow: auto; }
.pf-modal-f { padding: 14px 22px; border-top: 1px solid var(--border); display: flex; justify-content: flex-end; gap: 10px; }
</style>
