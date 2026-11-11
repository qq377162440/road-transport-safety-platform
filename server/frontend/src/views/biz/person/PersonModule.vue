<script setup>
/** B1 人员基础信息 · 一人一档完整闭环（详细设计 07）
 *  状态机：0缺 → 1齐 → 2已过期；13 项按三类分组逐项操作；
 *  子表：person_doc 证件上传自动落归档项；PDF 生成并登记到电子档案柜盒一；
 *  联动：证照到期预警、人员作为各模块选人数据源、小程序个人档案同源。 */
import { computed, onMounted, ref } from 'vue'
import request from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import Icon from '@/components/Icon.vue'

const overview = ref({ list: [], total: 0, complete: 0, pending: 0, expiring: 0 })
const expiringList = ref([])
const keyword = ref('')
const typeFilter = ref('')
const loading = ref(false)

const filtered = computed(() => (overview.value.list || []).filter((p) => {
  const k = keyword.value.trim()
  if (k && !(String(p.name || '') + String(p.telephone || '')).includes(k)) return false
  if (typeFilter.value && p.person_type !== typeFilter.value) return false
  return true
}))

async function load() {
  loading.value = true
  try {
    const [o, e] = await Promise.all([
      request.get('/flow/person/overview'),
      request.get('/flow/person/expiring', { params: { days: 30 } }),
    ])
    overview.value = o.data || {}
    expiringList.value = e.data?.list || []
  } catch (e) { /* 拦截器已提示 */ }
  finally { loading.value = false }
}
onMounted(load)

// ---------------- 一人一档详情抽屉 ----------------
const drawer = ref(false)
const detail = ref(null)
const refs = ref([])
const optionCount = ref(0)
// 雪花 ID 由后端序列化为字符串，必须全程用字符串传递（Number() 会超出 JS 安全整数范围丢精度）
const curId = ref('')
const busy = ref(false)

async function openArchive(row) {
  curId.value = String(row.id)
  drawer.value = true
  await refreshDetail()
  try {
    const [r, o] = await Promise.all([
      request.get(`/flow/person/${curId.value}/references`),
      request.get('/flow/person/options'),
    ])
    refs.value = r.data || []
    optionCount.value = (o.data || []).length
  } catch { refs.value = [] }
}
async function refreshDetail() {
  try { detail.value = (await request.get(`/flow/person/${curId.value}/archive`)).data }
  catch { detail.value = null }
  await load()
}
function groupEntries() {
  const g = detail.value?.groups || {}
  return Object.entries(g).filter(([, v]) => v && v.length)
}
function statusText(it) {
  if (it.status === 1) return '已齐全'
  if (it.status === 2) return '已过期'
  return it.optional ? '选填 · 缺' : '待补齐'
}
function sourceText(src) {
  return { upload: '证件扫描件', print: '空白表手写', sign: '签订生成', tpl: '范本生成', credit: '考核汇总' }[src] || '系统范本生成'
}
function statusClass(it) {
  if (it.status === 1) return 'tag-green'
  if (it.status === 2) return 'tag-red'
  return 'tag-yellow'
}
function expireText(it) {
  if (it.daysLeft === null || it.daysLeft === undefined) return it.expireDate ? String(it.expireDate).slice(0, 10) : '—'
  if (it.daysLeft < 0) return `已过期 ${-it.daysLeft} 天`
  return `${it.daysLeft} 天后到期`
}

async function markDone(it) {
  busy.value = true
  try {
    const r = await request.put(`/flow/person/${curId.value}/archive/${it.itemCode}`, { status: 1 })
    if (r.data?.ok === false) { ElMessage.warning(r.data.msg || '操作失败'); return }
    ElMessage.success(`已登记「${it.itemName}」，完整度 ${r.data.percent}%`)
    await refreshDetail()
  } finally { busy.value = false }
}
async function generate(it, blank = false) {
  busy.value = true
  try {
    const r = await request.post(`/flow/person/${curId.value}/generate`, { itemCode: it.itemCode, blank })
    if (r.data?.ok === false) { ElMessage.warning(r.data.msg || '生成失败'); return }
    ElMessage.success(`已生成${blank ? '空白表' : '成品 PDF'}并归档到盒一，完整度 ${r.data.percent}%`)
    await refreshDetail()
  } finally { busy.value = false }
}
async function viewPdf(it) {
  try {
    const blob = await request.get(`/flow/person/${curId.value}/archive/${it.itemCode}/pdf`, { responseType: 'blob' })
    const b = blob instanceof Blob ? blob : new Blob([blob], { type: 'application/pdf' })
    const url = URL.createObjectURL(b)
    window.open(url, '_blank')
    setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch (e) { ElMessage.warning('PDF 预览失败') }
}

// ---------------- 证件上传（person_doc 子表 → 自动落归档项） ----------------
const ITEM_DOC = {
  P01: ['id_card', '身份证复印件'], P02: ['driver_license', '机动车驾驶证'],
  P03: ['qualification', '道路运输从业人员从业资格证'], P04: ['labor_contract', '劳动合同或聘用协议'],
  P09: ['two_type_exam', '两类人员考核合格证明'], P11: ['health_check', '职业健康检查报告'],
  P13: ['continuing_edu', '继续教育及培训考核证明'],
}
const docDialog = ref(false)
const docForm = ref({ docCode: '', docName: '', fileUrl: '', issueDate: '', expireDate: '' })
const uploading = ref(false)

function openDoc(it) {
  const d = ITEM_DOC[it.itemCode]
  docForm.value = {
    docCode: d ? d[0] : 'id_card', docName: d ? d[1] : it.itemName,
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
    fd.append('bizModule', 'person')
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
    const r = await request.post(`/flow/person/${curId.value}/doc`, docForm.value)
    if (r.data?.ok === false) { ElMessage.warning(r.data.msg || '登记失败'); return }
    ElMessage.success(`证件已登记并自动落入归档项，完整度 ${r.data.percent}%`)
    docDialog.value = false
    await refreshDetail()
  } finally { busy.value = false }
}

// ---------------- 人员主数据 CRUD（通用引擎） ----------------
const dialogVisible = ref(false)
const editing = ref(false)
const editingId = ref('')
const blankForm = () => ({ name: '', telephone: '', person_type: 'employee', gender: 1, politic: '群众', id_card: '', post_ids: '' })
const form = ref(blankForm())
const saving = ref(false)

function openCreate() { editing.value = false; form.value = blankForm(); dialogVisible.value = true }
function openEdit(row) {
  editing.value = true; editingId.value = String(row.id)
  form.value = { ...blankForm(), ...row }
  dialogVisible.value = true
}
async function save() {
  if (!form.value.name || !form.value.telephone) {
    ElMessage.warning('姓名与手机号必填（手机号即小程序登录账号）'); return
  }
  saving.value = true
  try {
    if (editing.value) await request.put(`/biz/person/${editingId.value}`, form.value)
    else await request.post('/biz/person', form.value)
    ElMessage.success('保存成功，已自动登记归档项')
    dialogVisible.value = false
    await load()
  } finally { saving.value = false }
}
async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除人员「${row.name}」？该人员将从会议签到、三检、培训、考核等模块的选人列表中同步移除。`,
      '提示', { type: 'warning' })
  } catch (e) { return }
  await request.delete(`/biz/person/${row.id}`)
  ElMessage.success('已删除')
  await load()
}

function goArchive() { window.location.href = '/archive?box=' + encodeURIComponent('盒一') }
function personTypeText(v) { return v === 'manager' ? '管理人员' : '从业人员' }
function levelText(l) { return { expired: '已过期', d7: '7天内', d15: '15天内', d30: '30天内' }[l] || l }
</script>

<template>
  <div class="bm-page">
    <div class="pc-header bm-header">
      <div>
        <h1>人员基础信息</h1>
        <div class="desc">从业人员基础档案管理 · 一人一档（以档案盒目录为骨架）</div>
        <div class="bm-meta">
          <span class="tag tag-blue">阶段 B1</span>
          <span class="tag tag-gray">基础档案</span>
          <span class="tag tag-green">归档 盒一</span>
          <span class="bm-tables">13 项归档目录 · 2 张子表 · 状态机闭环</span>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-outline" @click="goArchive"><Icon name="archive" :size="15" /> 存档到电子档案柜（盒一）</button>
        <button class="btn btn-outline" @click="load()"><Icon name="refresh" :size="15" /> 刷新</button>
        <button class="btn btn-primary" @click="openCreate"><Icon name="plus" :size="15" /> 新增人员</button>
      </div>
    </div>

    <div class="pf-hint"><Icon name="info" :size="15" />
      录入人员即自动开通小程序账号（登录账号 = 手机号，初始密码 123456）；归档完整度随证件上传、范本生成实时重算。
    </div>

    <div class="pf-stat-row">
      <div class="stat-card"><div class="num">{{ overview.total || 0 }}</div><div class="lbl">在册人员</div></div>
      <div class="stat-card"><div class="num" style="color:var(--ok,#16a34a)">{{ overview.complete || 0 }}</div><div class="lbl">一人一档完整</div></div>
      <div class="stat-card"><div class="num" style="color:var(--danger,#dc2626)">{{ overview.pending || 0 }}</div><div class="lbl">档案待补齐</div></div>
      <div class="stat-card"><div class="num" style="color:#d97706">{{ overview.expiring || 0 }}</div><div class="lbl">证照到期预警</div></div>
    </div>

    <div v-if="expiringList.length" class="card pf-warn">
      <div class="card-title">证照到期预警 <span class="pf-sub">30 / 15 / 7 天分级，同步推送小程序消息与驾驶舱</span></div>
      <div class="pf-warn-list">
        <div v-for="(d, i) in expiringList.slice(0, 8)" :key="d.id || i" class="pf-warn-item">
          <span class="tag" :class="d.level === 'expired' ? 'tag-red' : 'tag-yellow'">{{ levelText(d.level) }}</span>
          <span class="nm">{{ d.person_name }} · {{ d.doc_name }}</span>
          <span class="dt">{{ String(d.expire_date || '').slice(0, 10) }}（{{ d.daysLeft < 0 ? '已过期 ' + (-d.daysLeft) + ' 天' : d.daysLeft + ' 天后' }}）</span>
        </div>
      </div>
    </div>

    <div class="filter-bar bm-filter">
      <div class="f-item"><label>关键字</label><input v-model="keyword" type="text" placeholder="姓名 / 手机号"></div>
      <div class="f-item"><label>人员类型</label>
        <select v-model="typeFilter">
          <option value="">全部</option><option value="employee">从业人员</option><option value="manager">管理人员</option>
        </select>
      </div>
      <div class="f-actions">
        <button class="btn btn-primary btn-sm" @click="load()"><Icon name="search" :size="14" /> 查询</button>
        <button class="btn btn-outline btn-sm" @click="keyword = ''; typeFilter = ''">重置</button>
      </div>
    </div>

    <div class="card" style="padding:0;overflow:hidden">
      <div class="card-title" style="padding:16px 20px 0">人员档案记录
        <span class="pf-sub">共 {{ filtered.length }} 条 · 归档完整度实时计算 · 数据来源：业务真实表</span>
      </div>
      <div style="padding:0 20px 16px">
        <table class="table bm-table" v-loading="loading">
          <thead><tr>
            <th>姓名</th><th>人员类型</th><th>手机号</th><th>岗位</th>
            <th style="width:190px">归档完整度</th><th>缺项</th><th>证照</th><th style="width:200px">操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="p in filtered" :key="p.id">
              <td><b>{{ p.name }}</b></td>
              <td>{{ personTypeText(p.person_type) }}</td>
              <td>{{ p.telephone || '—' }}</td>
              <td class="pf-ellipsis">{{ p.post_name || '—' }}</td>
              <td>
                <div class="pf-bar"><div class="pf-bar-in" :class="p.percent >= 100 ? 'done' : ''" :style="{ width: (p.percent || 0) + '%' }"></div></div>
                <span class="pf-pct">{{ p.percent || 0 }}%</span>
              </td>
              <td><span class="tag" :class="p.missingCount ? 'tag-yellow' : 'tag-green'">{{ p.missingCount ? p.missingCount + ' 项' : '齐全' }}</span></td>
              <td><span class="tag" :class="p.expiring ? 'tag-red' : 'tag-gray'">{{ p.expiring ? p.expiring + ' 项临期' : '正常' }}</span></td>
              <td>
                <a class="btn btn-primary btn-sm" @click="openArchive(p)">一人一档</a>
                <a class="btn btn-outline btn-sm" @click="openEdit(p)">编辑</a>
                <a class="btn btn-outline btn-sm" @click="remove(p)">删除</a>
              </td>
            </tr>
            <tr v-if="!filtered.length && !loading">
              <td colspan="8" style="text-align:center;color:var(--text-3);padding:28px">暂无人员，点右上角「新增人员」录入</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ============ 一人一档详情抽屉 ============ -->
    <div class="pf-mask" :class="{ on: drawer }" @click.self="drawer = false">
      <div class="pf-drawer">
        <div class="pf-dh">
          <div>
            <h2>一人一档 · {{ detail?.person?.name || '—' }}</h2>
            <div class="pf-sub">{{ detail?.person?.telephone || '' }} · {{ personTypeText(detail?.person?.person_type) }} · 归档盒一</div>
          </div>
          <button class="pf-close" @click="drawer = false">✕</button>
        </div>
        <div v-if="detail" class="pf-body" v-loading="busy">
          <div class="pf-progress">
            <div class="pf-prog-num">{{ detail.percent }}<span>%</span></div>
            <div class="pf-prog-info">
              <div class="pf-prog-title">归档完整度（{{ detail.done }} / {{ detail.required }} 项必备）</div>
              <div class="pf-bar big"><div class="pf-bar-in" :class="detail.percent >= 100 ? 'done' : ''" :style="{ width: detail.percent + '%' }"></div></div>
              <div class="pf-sub">缺 {{ (detail.missing || []).length }} 项 · 已过期 {{ detail.expired }} 项 · 任一项状态变化实时重算</div>
            </div>
          </div>

          <div v-for="[g, list] in groupEntries()" :key="g" class="card pf-group">
            <div class="card-title">{{ g }} <span class="pf-sub">{{ list.length }} 项</span></div>
            <div v-for="it in list" :key="it.itemCode" class="pf-item">
              <span class="pf-code">{{ it.itemCode }}</span>
              <div class="pf-item-main">
                <div class="pf-item-name">{{ it.itemName }}
                  <span class="tag" :class="statusClass(it)">{{ statusText(it) }}</span>
                  <span v-if="it.optional" class="tag tag-gray">选填</span>
                </div>
                <div class="pf-sub">{{ sourceText(it.sourceType) }} · 有效期 {{ expireText(it) }}</div>
              </div>
              <div class="pf-item-actions">
                <button v-if="it.sourceType === 'upload'" class="btn btn-primary btn-sm" @click="openDoc(it)">上传证件</button>
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
            <div class="card-title">我的证件（{{ (detail.docs || []).length }}）<span class="pf-sub">person_doc 子表 · 到期自动进入预警</span></div>
            <div v-if="!(detail.docs || []).length" class="pf-sub" style="padding:8px 0">暂无证件记录，到「证照」分组上传。</div>
            <div v-for="d in (detail.docs || [])" :key="d.id" class="pf-doc">
              <span class="tag" :class="d.warning ? 'tag-yellow' : 'tag-green'">{{ d.warning ? '临期' : '有效' }}</span>
              <span class="nm">{{ d.doc_name }}</span>
              <span class="pf-sub">{{ String(d.expire_date || '').slice(0, 10) || '长期有效' }}</span>
            </div>
          </div>

          <div class="card pf-group">
            <div class="card-title">模块间联动 <span class="pf-sub">本人员作为以下模块的选人数据源（person 主表同源）</span></div>
            <div class="pf-ref-row">
              <span v-for="r in refs" :key="r.module" class="tag tag-blue">{{ r.name }} · {{ r.count }}</span>
              <span v-if="!refs.length" class="pf-sub">读取中…</span>
            </div>
            <div class="pf-sub" style="margin-top:10px">选人数据源：person 主表共 {{ optionCount }} 人，各模块通过 /api/flow/person/options 同源取数；人员停用后同步退出选人列表。</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 证件上传弹窗 -->
    <el-dialog v-model="docDialog" title="上传证件扫描件 · 自动落入归档项" width="520px" destroy-on-close>
      <div class="pf-form-grid">
        <div class="form-item"><label class="form-label">证件类型</label>
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

    <!-- 新增 / 编辑人员弹窗 -->
    <el-dialog v-model="dialogVisible" :title="(editing ? '编辑' : '新增') + '人员'" width="640px" destroy-on-close>
      <div class="pf-form-grid">
        <div class="form-item"><label class="form-label">姓名 <span class="req">*</span></label>
          <input v-model="form.name" class="form-input" placeholder="请输入姓名"></div>
        <div class="form-item"><label class="form-label">手机号 <span class="req">*</span></label>
          <input v-model="form.telephone" class="form-input" placeholder="即小程序登录账号"></div>
        <div class="form-item"><label class="form-label">人员类型</label>
          <select v-model="form.person_type" class="form-input">
            <option value="employee">从业人员</option><option value="manager">管理人员</option>
          </select></div>
        <div class="form-item"><label class="form-label">政治面貌</label>
          <input v-model="form.politic" class="form-input" placeholder="群众 / 中共党员等"></div>
        <div class="form-item"><label class="form-label">身份证号</label>
          <input v-model="form.id_card" class="form-input" placeholder="18 位身份证号"></div>
        <div class="form-item"><label class="form-label">岗位 ID</label>
          <input v-model="form.post_ids" class="form-input" placeholder="org_post.id，逗号分隔"></div>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存并登记归档</el-button>
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
.pf-hint { background: #FFFBEB; border: 1px solid #FCD34D; border-radius: 8px; padding: 10px 14px; font-size: 13px; color: #8a5a00; display: flex; align-items: center; gap: 8px; }
.pf-stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.stat-card { background: #fff; border: 1px solid var(--border); border-radius: 10px; padding: 16px 18px; }
.stat-card .num { font-size: 26px; font-weight: 700; color: var(--brand-600, #1a5bb0); line-height: 1.2; }
.stat-card .lbl { font-size: 12.5px; color: var(--text-3); margin-top: 4px; }
.pf-warn { padding: 16px 20px; border-left: 3px solid #F59E0B; }
.pf-warn-list { margin-top: 10px; display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.pf-warn-item { display: flex; align-items: center; gap: 8px; font-size: 13px; padding: 7px 10px; background: #FFFBEB; border-radius: 8px; }
.pf-warn-item .nm { flex: 1; }
.pf-warn-item .dt { color: var(--text-3); font-size: 12px; }
.pf-ellipsis { max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pf-bar { width: 120px; height: 7px; background: #E2E8F0; border-radius: 6px; display: inline-block; vertical-align: middle; overflow: hidden; }
.pf-bar.big { width: 100%; height: 10px; }
.pf-bar-in { height: 100%; background: var(--brand-500, #3b82f6); border-radius: 6px; transition: width .3s; }
.pf-bar-in.done { background: #16a34a; }
.pf-pct { font-size: 12.5px; font-weight: 700; margin-left: 8px; }
/* 抽屉 */
.pf-mask { position: fixed; inset: 0; background: rgba(20,28,43,.45); z-index: 10000; display: none; justify-content: flex-end; }
.pf-mask.on { display: flex; }
.pf-drawer { width: 880px; max-width: 94vw; height: 100%; background: #F5F7FB; display: flex; flex-direction: column; box-shadow: -8px 0 30px rgba(0,0,0,.15); }
.pf-dh { background: #fff; padding: 18px 24px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: flex-start; }
.pf-dh h2 { margin: 0; font-size: 19px; }
.pf-close { border: none; background: #F1F5F9; width: 32px; height: 32px; border-radius: 8px; cursor: pointer; font-size: 15px; }
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
.pf-item-actions { display: flex; gap: 6px; flex-shrink: 0; flex-wrap: wrap; justify-content: flex-end; max-width: 330px; }
.pf-doc { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--border); font-size: 13px; }
.pf-doc:last-child { border-bottom: none; }
.pf-doc .nm { flex: 1; }
.pf-ref-row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.pf-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
.pf-form-grid .form-input { width: 100%; }
</style>
