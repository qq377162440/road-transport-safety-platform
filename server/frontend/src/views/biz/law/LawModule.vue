<script setup>
/** 法律法规清单 · 平台安全生产适用法律法规标准清单 + 法规原文库
 *  数据：law_catalog（tenant_id=0 平台内置 88 条 + 企业可补充）、law_document（法规原文）。
 *  权限：服务商/超管（isPlatform）可增删改、上传原文、标记引用、删除；企业只读 + 下载 PDF / 原文。
 *  refs 为引用关系 JSON；law_document.law_id + ref_note 实现「制度—法规—原文」可追溯。 */
import { computed, onMounted, ref } from 'vue'
import request from '@/api/request'
import { useAuthStore } from '@/store/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import Icon from '@/components/Icon.vue'

const auth = useAuthStore()
const canEdit = computed(() => auth.isPlatform)

const data = ref({ list: [], total: 0, active: 0, superseded: 0, refCount: 0, docTotal: 0, categories: [] })
const keyword = ref('')
const statusFilter = ref('')
const categoryFilter = ref('')
const levelFilter = ref('')
const bizFilter = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await request.get('/flow/law/overview', {
      params: {
        keyword: keyword.value || undefined,
        status: statusFilter.value || undefined,
        category: categoryFilter.value || undefined,
        applyLevel: levelFilter.value || undefined,
        bizType: bizFilter.value || undefined,
      },
    })
    data.value = r.data || { list: [], total: 0 }
  } catch (e) { /* 拦截器已提示 */ }
  finally { loading.value = false }
}
onMounted(load)

const statusText = { active: '使用中', superseded: '待更新', repealed: '已废止' }
const levelColor = { 法律: 'tag-blue', 行政法规: 'tag-green', 部门规章: 'tag-purple', 标准: 'tag-orange', 规范性文件: 'tag-gray' }
const applyLevelColor = { 国家: 'tag-blue', 省级: 'tag-purple', 市级: 'tag-green', 区县级: 'tag-gray' }
const refTypeText = { module: '业务模块', tpl: '台账范本', doc: '档案归档' }
const bizText = { all: '通用', cargo: '普货', danger: '危货' }
const bizTag = { all: 'tag-gray', cargo: 'tag-blue', danger: 'tag-red' }
const extTag = { pdf: 'tag-red', docx: 'tag-blue', doc: 'tag-purple', txt: 'tag-gray', rar: 'tag-orange' }
function textLabel(n) {
  if (!n) return '扫描件'
  if (n >= 10000) return (n / 10000).toFixed(1) + '万字'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k字'
  return n + '字'
}

// ---------------- 详情抽屉：引用制度 + 法规原文 ----------------
const drawer = ref(false)
const cur = ref(null)
const detailLoading = ref(false)

async function openDetail(row) {
  drawer.value = true
  detailLoading.value = true
  cur.value = row
  try {
    const r = await request.get(`/flow/law/${row.id}`)
    cur.value = r.data || row
  } catch (e) { /* 拦截器已提示 */ }
  finally { detailLoading.value = false }
}

// ---------------- 下载清单 PDF（企业只读也能用） ----------------
const exporting = ref(false)
function toBlob(r, fallbackType) {
  if (r instanceof Blob) return r
  if (r?.data instanceof Blob) return r.data
  return new Blob([r?.data ?? r], { type: fallbackType || 'application/octet-stream' })
}
async function downloadPdf() {
  exporting.value = true
  try {
    const r = await request.get('/flow/law/export/pdf', { responseType: 'blob' })
    const blob = toBlob(r, 'application/pdf')
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = '适用安全生产法律法规标准清单.pdf'
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 5000)
  } catch (e) { /* 拦截器已提示 */ }
  finally { exporting.value = false }
}

// ---------------- 法规原文库 ----------------
const docDrawer = ref(false)
const docData = ref({ list: [], total: 0, linked: 0, unlinked: 0, withText: 0, totalSizeText: '0 B' })
const docKeyword = ref('')
const docOnlyUnlinked = ref(false)
const docLawFilter = ref(0)
const docLoading = ref(false)
const allLaws = ref([])
const uploadLawId = ref(0)
const uploadRefNote = ref('')
const fileInput = ref(null)
const uploading = ref(false)

async function loadAllLaws() {
  try {
    const r = await request.get('/flow/law/overview')
    allLaws.value = r.data?.list || []
  } catch (e) { /* 拦截器已提示 */ }
}
async function loadDocs() {
  docLoading.value = true
  try {
    const r = await request.get('/flow/law/doc/list', {
      params: {
        keyword: docKeyword.value || undefined,
        lawId: docLawFilter.value || undefined,
        onlyUnlinked: docOnlyUnlinked.value || undefined,
      },
    })
    docData.value = r.data || { list: [], total: 0, linked: 0, unlinked: 0, withText: 0, totalSizeText: '0 B' }
  } catch (e) { /* 拦截器已提示 */ }
  finally { docLoading.value = false }
}
function openDocLib(lawId = 0) {
  docLawFilter.value = lawId || 0
  docKeyword.value = ''
  docOnlyUnlinked.value = false
  uploadLawId.value = lawId || 0
  docDrawer.value = true
  loadDocs()
  loadAllLaws()
}
function docFilterLawName() {
  const l = allLaws.value.find((x) => String(x.id) === String(docLawFilter.value))
  return l ? l.law_name : ''
}

// 预览 / 下载 / 正文阅读
const previewOpen = ref(false)
const previewUrl = ref('')
const previewName = ref('')
const textOpen = ref(false)
const textData = ref({ fileName: '', textLen: 0, contentText: '' })

async function previewDoc(d) {
  if (d.ext !== 'pdf') { ElMessage.info('该格式为 ' + d.ext.toUpperCase() + '，请点「下载」后用本地软件打开'); return }
  try {
    const r = await request.get(`/flow/law/doc/${d.id}/file`, { params: { inline: true }, responseType: 'blob' })
    if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = URL.createObjectURL(toBlob(r, 'application/pdf'))
    previewName.value = d.file_name
    previewOpen.value = true
  } catch (e) { /* 拦截器已提示 */ }
}
function closePreview() {
  previewOpen.value = false
  if (previewUrl.value) { URL.revokeObjectURL(previewUrl.value); previewUrl.value = '' }
}
async function downloadDoc(d) {
  try {
    const r = await request.get(`/flow/law/doc/${d.id}/file`, { params: { inline: false }, responseType: 'blob' })
    const url = URL.createObjectURL(toBlob(r))
    const a = document.createElement('a')
    a.href = url
    a.download = d.file_name
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 5000)
  } catch (e) { /* 拦截器已提示 */ }
}
async function readDoc(d) {
  if (!d.text_len) { ElMessage.info('该原件为扫描件/压缩包，未提取到可检索正文，请下载查看'); return }
  try {
    const r = await request.get(`/flow/law/doc/${d.id}/text`)
    textData.value = r.data || { fileName: d.file_name, textLen: 0, contentText: '' }
    textOpen.value = true
  } catch (e) { /* 拦截器已提示 */ }
}

// 上传原文（服务商）
function pickFile() { fileInput.value?.click() }
async function onFileChange(e) {
  const f = e.target.files?.[0]
  if (!f) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', f)
    fd.append('lawId', String(uploadLawId.value || 0))
    fd.append('refNote', uploadRefNote.value || '')
    await request.post('/flow/law/doc/upload', fd)
    ElMessage.success('已上传：' + f.name)
    uploadRefNote.value = ''
    e.target.value = ''
    await loadDocs()
    await load()
  } catch (err) { /* 拦截器已提示 */ }
  finally { uploading.value = false }
}

// 标记引用（服务商）
const markOpen = ref(false)
const markSaving = ref(false)
const markForm = ref({ id: 0, fileName: '', lawId: 0, refNote: '' })
function openMark(d) {
  markForm.value = { id: d.id, fileName: d.file_name, lawId: d.law_id || 0, refNote: d.ref_note || '' }
  markOpen.value = true
}
async function saveMark() {
  markSaving.value = true
  try {
    await request.post('/flow/law/doc/mark', { ...markForm.value })
    ElMessage.success('引用已标记')
    markOpen.value = false
    await loadDocs()
    await load()
  } catch (e) { /* 拦截器已提示 */ }
  finally { markSaving.value = false }
}
async function removeDoc(d) {
  try {
    await ElMessageBox.confirm(`确认删除原文「${d.file_name}」？`, '提示', { type: 'warning' })
  } catch (e) { return }
  try {
    await request.delete(`/flow/law/doc/${d.id}`)
    ElMessage.success('已删除')
    await loadDocs()
    await load()
  } catch (e) { /* 拦截器已提示 */ }
}

// ---------------- 新增 / 编辑（仅服务商/超管） ----------------
const dialog = ref(false)
const editing = ref(false)
const editingId = ref('')
const blankForm = () => ({
  lawName: '', lawNo: '', lawLevel: '部门规章', applyLevel: '国家', region: '全国',
  category: '综合安全', versionLabel: '', effectiveDate: '', reviseDate: '',
  status: 'active', bizType: 'all', isApplicable: 'yes',
  summary: '', remark: '', refsText: '',
})
const form = ref(blankForm())
const saving = ref(false)

function openCreate() {
  editing.value = false; form.value = blankForm(); dialog.value = true
}
function openEdit(row) {
  editing.value = true; editingId.value = String(row.id)
  form.value = {
    lawName: row.law_name || '', lawNo: row.law_no || '', lawLevel: row.law_level || '部门规章',
    applyLevel: row.apply_level || '国家', region: row.region || '全国',
    category: row.category || '综合安全', versionLabel: row.version_label || '',
    effectiveDate: row.effective_date || '', reviseDate: row.revise_date || '',
    status: row.status || 'active', bizType: row.biz_type || 'all',
    isApplicable: row.is_applicable || 'yes', summary: row.summary || '', remark: row.remark || '',
    refsText: (row.refs || []).map((r) => r.name).join('、'),
  }
  dialog.value = true
}
async function save() {
  if (!form.value.lawName.trim()) { ElMessage.warning('法规名称必填'); return }
  saving.value = true
  try {
    await request.post('/flow/law/save', { ...form.value, id: editing.value ? editingId.value : 0 })
    ElMessage.success(editing.value ? '法规已更新' : '法规已新增')
    dialog.value = false
    await load()
  } finally { saving.value = false }
}
async function remove(row) {
  try {
    await ElMessageBox.confirm(`确认删除「${row.law_name}」？仅可删除自行新增的条目。`, '提示', { type: 'warning' })
  } catch (e) { return }
  await request.delete(`/flow/law/${row.id}`)
  ElMessage.success('已删除')
  await load()
}
</script>

<template>
  <div class="law-page">
    <div class="pc-header law-header">
      <div>
        <h1>法律法规清单</h1>
        <div class="desc">安全生产适用法律法规标准清单 · 制度引用的法规可追溯 · 原文可查询</div>
        <div class="law-meta">
          <span class="tag tag-blue">合规依据</span>
          <span class="tag" :class="canEdit ? 'tag-purple' : 'tag-gray'">{{ canEdit ? '服务商可增删改' : '企业只读' }}</span>
          <span class="law-tables">覆盖消防 / 职业健康 / 交通 / 应急 / 教育培训等七类</span>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-outline" @click="load()"><Icon name="refresh" :size="15" /> 刷新</button>
        <button class="btn btn-outline" :disabled="exporting" @click="downloadPdf">
          <Icon name="download" :size="15" /> {{ exporting ? '生成中…' : '下载 PDF' }}
        </button>
        <button class="btn btn-primary" @click="openDocLib(0)"><Icon name="book" :size="15" /> 法规原文库 <span v-if="data.docTotal" class="btn-badge">{{ data.docTotal }}</span></button>
        <button v-if="canEdit" class="btn btn-primary" @click="openCreate"><Icon name="plus" :size="15" /> 新增法规</button>
      </div>
    </div>

    <div class="law-hint"><Icon name="info" :size="15" />
      <template v-if="canEdit">服务商 / 超管可维护清单、上传法规原文并标记引用关系；法规修订后请及时更新版本并同步修订对应制度。</template>
      <template v-else>清单与法规原文为平台维护的合规依据，企业只读；可在线预览、检索原文并下载留存备查。</template>
    </div>

    <div class="law-stat-row">
      <div class="stat-card"><div class="num">{{ data.total }}</div><div class="lbl">法规总数</div></div>
      <div class="stat-card"><div class="num" style="color:var(--ok,#16a34a)">{{ data.active }}</div><div class="lbl">使用中</div></div>
      <div class="stat-card"><div class="num" style="color:#d97706">{{ data.superseded || 0 }}</div><div class="lbl">待更新（有新版未同步）</div></div>
      <div class="stat-card"><div class="num" style="color:var(--brand-600,#1a5bb0)">{{ data.refCount }}</div><div class="lbl">制度引用条目</div></div>
      <div class="stat-card stat-click" @click="openDocLib(0)"><div class="num" style="color:#6d28d9">{{ data.docTotal || 0 }}</div><div class="lbl">法规原文（点击查看）</div></div>
    </div>

    <div class="filter-bar law-filter">
      <div class="f-item"><label>关键字</label><input v-model="keyword" type="text" placeholder="法规名 / 文号 / 备注 / 引用制度" @keyup.enter="load()"></div>
      <div class="f-item"><label>分类</label>
        <select v-model="categoryFilter">
          <option value="">全部</option>
          <option v-for="c in data.categories" :key="c" :value="c">{{ c }}</option>
        </select>
      </div>
      <div class="f-item"><label>适用层级</label>
        <select v-model="levelFilter">
          <option value="">全部</option><option value="国家">国家</option><option value="省级">省级</option><option value="市级">市级</option>
        </select>
      </div>
      <div class="f-item"><label>适用业务</label>
        <select v-model="bizFilter">
          <option value="">全部</option><option value="all">通用</option><option value="cargo">普货</option><option value="danger">危货</option>
        </select>
      </div>
      <div class="f-item"><label>状态</label>
        <select v-model="statusFilter">
          <option value="">全部</option><option value="active">使用中</option><option value="superseded">待更新</option><option value="repealed">已废止</option>
        </select>
      </div>
      <div class="f-actions">
        <button class="btn btn-primary btn-sm" @click="load()"><Icon name="search" :size="14" /> 查询</button>
        <button class="btn btn-outline btn-sm" @click="keyword=''; statusFilter=''; categoryFilter=''; levelFilter=''; bizFilter=''; load()">重置</button>
      </div>
    </div>

    <div class="card" style="padding:0;overflow:hidden">
      <div class="card-title" style="padding:16px 20px 0">
        法规清单 <span class="law-sub">共 {{ data.list.length }} 部 · 「原文」列为已上传的法规原文件份数</span>
      </div>
      <div style="padding:0 20px 16px">
        <table class="table law-table" v-loading="loading">
          <thead><tr>
            <th style="width:22%">法规名称</th><th style="width:76px">类型</th><th style="width:86px">分类</th>
            <th style="width:52px">层级</th><th style="width:74px">地区</th><th style="width:60px">业务</th>
            <th style="width:84px">施行日期</th><th style="width:64px">状态</th>
            <th style="width:52px">引用</th><th style="width:58px">原文</th><th>操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="row in data.list" :key="row.id">
              <td>
                <b>{{ row.law_name }}</b>
                <div class="law-sub" v-if="row.law_no">{{ row.law_no }}</div>
                <div class="law-sub" v-if="row.version_label">最新修订 {{ row.version_label }}</div>
              </td>
              <td><span class="tag" :class="levelColor[row.law_level] || 'tag-gray'">{{ row.law_level }}</span></td>
              <td>{{ row.category }}</td>
              <td><span class="tag" :class="applyLevelColor[row.apply_level] || 'tag-gray'">{{ row.apply_level }}</span></td>
              <td>{{ row.region }}</td>
              <td>
                <span class="tag" :class="bizTag[row.biz_type] || 'tag-gray'">{{ bizText[row.biz_type] || '通用' }}</span>
                <span v-if="row.is_applicable === 'conditional'" class="law-sub">（有条件）</span>
              </td>
              <td>{{ row.effective_date || '—' }}</td>
              <td><span class="tag" :class="row.status === 'active' ? 'tag-green' : row.status === 'superseded' ? 'tag-yellow' : 'tag-gray'">{{ statusText[row.status] || row.status }}</span></td>
              <td><span class="law-ref-cnt">{{ row.refCount }}</span></td>
              <td><a class="law-doc-cnt" :class="{ zero: !row.docCount }" @click="openDocLib(row.id)">{{ row.docCount || 0 }}</a></td>
              <td class="law-ops">
                <a class="btn btn-primary btn-sm" @click="openDetail(row)">详情</a>
                <template v-if="canEdit">
                  <a class="btn btn-outline btn-sm" @click="openEdit(row)">编辑</a>
                  <a class="btn btn-outline btn-sm" @click="remove(row)">删除</a>
                </template>
              </td>
            </tr>
            <tr v-if="!data.list.length && !loading">
              <td colspan="11" style="text-align:center;color:var(--text-3);padding:28px">暂无匹配的法规记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ============ 详情抽屉 ============ -->
    <div class="law-mask" :class="{ on: drawer }" @click.self="drawer = false">
      <div class="law-drawer">
        <div class="law-dh">
          <div>
            <h2>{{ cur?.law_name }}</h2>
            <div class="law-sub">
              {{ cur?.law_no }} · {{ cur?.law_level }} · {{ cur?.apply_level }} · {{ cur?.region }}
              <template v-if="cur?.effective_date"> · {{ cur.effective_date }} 施行</template>
            </div>
          </div>
          <button class="law-close" @click="drawer = false">✕</button>
        </div>
        <div v-if="cur" class="law-body" v-loading="detailLoading">
          <div class="card law-group">
            <div class="card-title">法规信息</div>
            <div class="law-kv"><span class="k">状态</span>
              <span class="tag" :class="cur.status === 'active' ? 'tag-green' : cur.status === 'superseded' ? 'tag-yellow' : 'tag-gray'">{{ statusText[cur.status] || cur.status }}</span></div>
            <div class="law-kv"><span class="k">类型</span>{{ cur.law_level || '—' }}</div>
            <div class="law-kv"><span class="k">适用层级</span>{{ cur.apply_level || '—' }}</div>
            <div class="law-kv"><span class="k">适用地区</span>{{ cur.region || '—' }}</div>
            <div class="law-kv"><span class="k">适用业务</span>{{ bizText[cur.biz_type] || '通用' }}{{ cur.is_applicable === 'conditional' ? '（有条件适用）' : '' }}</div>
            <div class="law-kv"><span class="k">最新修订</span>{{ cur.revise_date || cur.version_label || '—' }}</div>
            <div class="law-kv"><span class="k">备注</span><span style="flex:1">{{ cur.remark || '—' }}</span></div>
          </div>

          <div class="card law-group">
            <div class="card-title">引用本法规的制度（{{ (cur.refs || []).length }}）<span class="law-sub">制度—法规可追溯：修订法规后请同步检查以下制度</span></div>
            <div v-if="!(cur.refs || []).length" class="law-sub" style="padding:8px 0">暂无引用记录</div>
            <div v-for="(r, i) in cur.refs || []" :key="i" class="law-ref">
              <span class="tag" :class="r.type === 'module' ? 'tag-blue' : r.type === 'tpl' ? 'tag-purple' : 'tag-green'">{{ refTypeText[r.type] || '制度' }}</span>
              <span class="nm">{{ r.name }}</span>
            </div>
          </div>

          <div class="card law-group">
            <div class="card-title">法规原文（{{ (cur.docs || []).length }}）<span class="law-sub">在线预览 / 检索正文 / 下载原件</span></div>
            <div v-if="!(cur.docs || []).length" class="law-sub" style="padding:8px 0">暂无关联原文，可在「法规原文库」上传后标记引用</div>
            <div v-for="d in cur.docs || []" :key="d.id" class="doc-row">
              <span class="tag" :class="extTag[d.ext] || 'tag-gray'">{{ (d.ext || 'file').toUpperCase() }}</span>
              <span class="doc-nm" :title="d.file_name">{{ d.file_name }}</span>
              <span v-if="d.doc_tag" class="tag tag-yellow">{{ d.doc_tag }}</span>
              <span v-if="d.ref_note" class="law-sub doc-ref" :title="d.ref_note">引用：{{ d.ref_note }}</span>
              <a class="btn btn-outline btn-sm" @click="previewDoc(d)">预览</a>
              <a v-if="d.text_len" class="btn btn-outline btn-sm" @click="readDoc(d)">正文</a>
              <a class="btn btn-outline btn-sm" @click="downloadDoc(d)">下载</a>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ============ 法规原文库抽屉 ============ -->
    <div class="doc-mask" :class="{ on: docDrawer }" @click.self="docDrawer = false">
      <div class="doc-drawer">
        <div class="law-dh">
          <div>
            <h2>法规原文库</h2>
            <div class="law-sub">共 {{ docData.total }} 份 · 已关联 {{ docData.linked }} · 可全文检索 {{ docData.withText }} · 合计 {{ docData.totalSizeText }}</div>
          </div>
          <button class="law-close" @click="docDrawer = false">✕</button>
        </div>

        <div class="doc-toolbar">
          <div class="f-item"><label>关键字</label>
            <input v-model="docKeyword" type="text" placeholder="文件名 / 引用标记 / 关联法规 / 正文内容" @keyup.enter="loadDocs()">
          </div>
          <div class="f-item"><label>关联法规</label>
            <select v-model="docLawFilter" @change="loadDocs()">
              <option :value="0">全部法规</option>
              <option v-for="l in allLaws" :key="l.id" :value="l.id">{{ l.law_name }}</option>
            </select>
          </div>
          <div class="f-item"><label>状态</label>
            <select v-model="docOnlyUnlinked" @change="loadDocs()">
              <option :value="false">全部</option>
              <option :value="true">仅未标记引用</option>
            </select>
          </div>
          <div class="f-actions">
            <button class="btn btn-primary btn-sm" @click="loadDocs()"><Icon name="search" :size="14" /> 查询</button>
            <button class="btn btn-outline btn-sm" @click="docKeyword=''; docLawFilter=0; docOnlyUnlinked=false; loadDocs()">重置</button>
          </div>
        </div>

        <div v-if="canEdit" class="doc-upload">
          <div class="doc-upload-l">
            <b>上传法规原文</b>
            <div class="law-sub">自动提取 PDF / DOCX 正文用于全文检索；可同时关联法规并填写引用标记</div>
          </div>
          <div class="doc-upload-r">
            <select v-model="uploadLawId" class="form-input" style="max-width:230px">
              <option :value="0">不关联法规（稍后标记）</option>
              <option v-for="l in allLaws" :key="l.id" :value="l.id">{{ l.law_name }}</option>
            </select>
            <input v-model="uploadRefNote" class="form-input" style="max-width:200px" placeholder="引用标记（制度/条款，可空）">
            <input ref="fileInput" type="file" style="display:none" @change="onFileChange">
            <button class="btn btn-primary btn-sm" :disabled="uploading" @click="pickFile">
              <Icon name="upload" :size="14" /> {{ uploading ? '上传中…' : '选择文件并上传' }}
            </button>
          </div>
        </div>

        <div class="doc-list" v-loading="docLoading">
          <div v-if="docLawFilter" class="doc-filter-tip">
            已按法规筛选：<b>{{ docFilterLawName() }}</b>
            <a @click="docLawFilter = 0; loadDocs()">清除筛选</a>
          </div>
          <table class="table doc-table">
            <thead><tr>
              <th style="width:26%">原文文件</th><th style="width:52px">格式</th><th style="width:70px">大小</th>
              <th style="width:20%">关联法规 / 引用标记</th><th style="width:96px">正文</th><th>操作</th>
            </tr></thead>
            <tbody>
              <tr v-for="d in docData.list" :key="d.id">
                <td>
                  <b class="doc-nm" :title="d.file_name">{{ d.file_name }}</b>
                  <span v-if="d.doc_tag" class="tag tag-yellow" style="margin-left:6px">{{ d.doc_tag }}</span>
                </td>
                <td><span class="tag" :class="extTag[d.ext] || 'tag-gray'">{{ (d.ext || 'file').toUpperCase() }}</span></td>
                <td>{{ d.sizeText }}</td>
                <td>
                  <div v-if="d.law_name" class="doc-law">{{ d.law_name }}</div>
                  <div v-if="d.ref_note" class="law-sub">引用：{{ d.ref_note }}</div>
                  <span v-if="!d.law_name && !d.ref_note" class="tag tag-gray">未标记</span>
                </td>
                <td>
                  <span v-if="d.text_len" class="tag tag-green doc-text-tag" :title="`可检索正文 ${d.text_len} 字`">{{ textLabel(d.text_len) }}</span>
                  <span v-else class="tag tag-gray doc-text-tag">扫描件</span>
                </td>
                <td class="law-ops">
                  <a class="btn btn-outline btn-sm" @click="previewDoc(d)">预览</a>
                  <a v-if="d.text_len" class="btn btn-outline btn-sm" @click="readDoc(d)">正文</a>
                  <a class="btn btn-outline btn-sm" @click="downloadDoc(d)">下载</a>
                  <template v-if="canEdit">
                    <a class="btn btn-primary btn-sm" @click="openMark(d)">标记引用</a>
                    <a class="btn btn-outline btn-sm" @click="removeDoc(d)">删除</a>
                  </template>
                </td>
              </tr>
              <tr v-if="!docData.list.length && !docLoading">
                <td colspan="6" style="text-align:center;color:var(--text-3);padding:28px">暂无匹配的原文</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- ============ 标记引用弹窗 ============ -->
    <div class="pop-mask" :class="{ on: markOpen }" @click.self="markOpen = false">
      <div class="pop-card">
        <div class="pop-hd"><h3>标记引用</h3><button class="law-close" @click="markOpen = false">✕</button></div>
        <div class="pop-bd">
          <div class="law-sub" style="margin-bottom:12px">原文：<b>{{ markForm.fileName }}</b></div>
          <div class="form-item"><label class="form-label">关联法规</label>
            <select v-model="markForm.lawId" class="form-input">
              <option :value="0">不关联（未标记引用）</option>
              <option v-for="l in allLaws" :key="l.id" :value="l.id">{{ l.law_name }}</option>
            </select>
          </div>
          <div class="form-item" style="margin-top:12px"><label class="form-label">引用标记（制度 / 条款 / 章节）</label>
            <textarea v-model="markForm.refNote" class="form-input" rows="3" placeholder="如：安全生产责任制（逐级签订）第4条、档案盒一·安全组织架构"></textarea>
          </div>
          <div class="law-sub" style="margin-top:8px">保存后，该原文会出现在对应法规详情的「法规原文」中，并可按引用标记检索。</div>
        </div>
        <div class="pop-ft">
          <button class="btn btn-outline" @click="markOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="markSaving" @click="saveMark">{{ markSaving ? '保存中…' : '保存' }}</button>
        </div>
      </div>
    </div>

    <!-- ============ 正文阅读弹窗 ============ -->
    <div class="pop-mask" :class="{ on: textOpen }" @click.self="textOpen = false">
      <div class="pop-card pop-wide">
        <div class="pop-hd"><h3>{{ textData.fileName }} <span class="law-sub">· {{ textData.textLen }} 字</span></h3><button class="law-close" @click="textOpen = false">✕</button></div>
        <div class="pop-bd"><pre class="doc-text">{{ textData.contentText }}</pre></div>
        <div class="pop-ft"><button class="btn btn-outline" @click="textOpen = false">关闭</button></div>
      </div>
    </div>

    <!-- ============ PDF 在线预览弹窗 ============ -->
    <div class="pop-mask" :class="{ on: previewOpen }" @click.self="closePreview">
      <div class="pop-card pop-wide">
        <div class="pop-hd"><h3>{{ previewName }}</h3><button class="law-close" @click="closePreview">✕</button></div>
        <div class="pop-bd" style="padding:0;background:#333"><iframe :src="previewUrl" style="width:100%;height:70vh;border:0;display:block"></iframe></div>
      </div>
    </div>

    <!-- 新增 / 编辑法规弹窗（仅服务商/超管） -->
    <el-dialog v-model="dialog" :title="(editing ? '编辑' : '新增') + '法规'" width="680px" destroy-on-close>
      <div class="law-form-grid">
        <div class="form-item" style="grid-column:1/-1"><label class="form-label">法规名称 <span class="req">*</span></label>
          <input v-model="form.lawName" class="form-input" placeholder="《...》或全名"></div>
        <div class="form-item"><label class="form-label">令号 / 文号 / 版本</label>
          <input v-model="form.lawNo" class="form-input" placeholder="如 国务院令第406号 / GB 7258-2017"></div>
        <div class="form-item"><label class="form-label">法规类型</label>
          <select v-model="form.lawLevel" class="form-input">
            <option>法律</option><option>行政法规</option><option>部门规章</option><option>标准</option><option>规范性文件</option>
          </select></div>
        <div class="form-item"><label class="form-label">适用层级</label>
          <select v-model="form.applyLevel" class="form-input">
            <option>国家</option><option>省级</option><option>市级</option><option>区县级</option>
          </select></div>
        <div class="form-item"><label class="form-label">适用地区</label>
          <input v-model="form.region" class="form-input" placeholder="全国 / 广东省 / 佛山市"></div>
        <div class="form-item"><label class="form-label">分类</label>
          <input v-model="form.category" class="form-input" placeholder="消防安全 / 交通安全 / 职业健康…"></div>
        <div class="form-item"><label class="form-label">适用业务</label>
          <select v-model="form.bizType" class="form-input">
            <option value="all">通用</option><option value="cargo">普货</option><option value="danger">危货</option>
          </select></div>
        <div class="form-item"><label class="form-label">是否适用</label>
          <select v-model="form.isApplicable" class="form-input">
            <option value="yes">适用</option><option value="conditional">有条件适用</option>
          </select></div>
        <div class="form-item"><label class="form-label">施行日期</label>
          <input v-model="form.effectiveDate" class="form-input" placeholder="2023-08-21"></div>
        <div class="form-item"><label class="form-label">最新修订时间</label>
          <input v-model="form.reviseDate" class="form-input" placeholder="2023-08-21"></div>
        <div class="form-item"><label class="form-label">状态</label>
          <select v-model="form.status" class="form-input">
            <option value="active">使用中</option><option value="superseded">待更新</option><option value="repealed">已废止</option>
          </select></div>
        <div class="form-item" style="grid-column:1/-1"><label class="form-label">摘要</label>
          <input v-model="form.summary" class="form-input" placeholder="一句话说明该法规管什么"></div>
        <div class="form-item" style="grid-column:1/-1"><label class="form-label">备注</label>
          <input v-model="form.remark" class="form-input" placeholder="如：企业自有停车场适用 / 已更新至2026年修正版"></div>
        <div class="form-item" style="grid-column:1/-1"><label class="form-label">引用制度（顿号/逗号分隔）</label>
          <input v-model="form.refsText" class="form-input" placeholder="如：安全生产责任制（逐级签订）、安全生产责任书（各级岗位）"></div>
      </div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.law-page { display: flex; flex-direction: column; gap: 16px; }
.law-header h1 { margin: 0 0 6px; font-size: 22px; }
.law-meta { display: flex; gap: 8px; align-items: center; margin-top: 10px; flex-wrap: wrap; }
.law-tables { font-size: 12.5px; color: var(--text-3); }
.btn-badge { display: inline-block; background: rgba(255,255,255,.25); border-radius: 9px; padding: 0 6px; font-size: 11px; margin-left: 3px; }
.law-filter { flex-wrap: wrap; }
.law-filter input, .law-filter select { height: 36px; border: 1px solid var(--border); border-radius: 8px; padding: 0 10px; min-width: 140px; }
.law-table { width: 100%; }
.law-table a { cursor: pointer; margin-right: 6px; }
.law-sub { font-size: 12.5px; color: var(--text-3); font-weight: 400; }
.law-hint { background: #FFFBEB; border: 1px solid #FCD34D; border-radius: 8px; padding: 10px 14px; font-size: 13px; color: #8a5a00; display: flex; align-items: center; gap: 8px; }
.law-stat-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; }
.stat-card { background: #fff; border: 1px solid var(--border); border-radius: 10px; padding: 16px 18px; }
.stat-card .num { font-size: 26px; font-weight: 700; color: var(--brand-600, #1a5bb0); line-height: 1.2; }
.stat-card .lbl { font-size: 12.5px; color: var(--text-3); margin-top: 4px; }
.stat-click { cursor: pointer; border-color: #ddd6fe; }
.stat-click:hover { box-shadow: 0 2px 10px rgba(109,40,217,.15); }
.law-ref-cnt { display: inline-block; min-width: 30px; text-align: center; background: #EFF6FF; color: var(--brand-600, #1a5bb0); border-radius: 10px; padding: 2px 8px; font-weight: 700; font-size: 12.5px; }
.law-doc-cnt { display: inline-block; min-width: 26px; text-align: center; background: #F5F3FF; color: #6d28d9; border-radius: 10px; padding: 2px 8px; font-weight: 700; font-size: 12.5px; }
.law-doc-cnt.zero { background: #F1F5F9; color: #94a3b8; }
.law-ops { white-space: nowrap; }
/* 抽屉 */
.law-mask, .doc-mask { position: fixed; inset: 0; background: rgba(20,28,43,.45); z-index: 12000; display: none; justify-content: flex-end; }
.law-mask.on, .doc-mask.on { display: flex; }
.law-drawer { width: 760px; max-width: 94vw; height: 100%; background: #F5F7FB; display: flex; flex-direction: column; box-shadow: -8px 0 30px rgba(0,0,0,.15); }
.doc-drawer { width: 1080px; max-width: 96vw; height: 100%; background: #F5F7FB; display: flex; flex-direction: column; box-shadow: -8px 0 30px rgba(0,0,0,.15); }
.law-dh { background: #fff; padding: 18px 24px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: flex-start; }
.law-dh h2 { margin: 0 0 4px; font-size: 19px; }
.law-close { border: none; background: #F1F5F9; width: 32px; height: 32px; border-radius: 8px; cursor: pointer; font-size: 15px; }
.law-body { flex: 1; overflow: auto; padding: 18px 24px 40px; display: flex; flex-direction: column; gap: 14px; }
.law-group { padding: 16px 20px; }
.law-kv { display: flex; gap: 10px; padding: 7px 0; font-size: 13.5px; border-bottom: 1px dashed var(--border); }
.law-kv:last-child { border-bottom: none; }
.law-kv .k { color: var(--text-3); width: 70px; flex-shrink: 0; }
.law-ref { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--border); font-size: 13px; }
.law-ref:last-child { border-bottom: none; }
.law-ref .nm { flex: 1; }
.doc-row { display: flex; align-items: center; gap: 8px; padding: 9px 0; border-bottom: 1px solid var(--border); font-size: 13px; flex-wrap: wrap; }
.doc-row:last-child { border-bottom: none; }
.doc-nm { flex: 1; min-width: 180px; word-break: break-all; }
.doc-ref { max-width: 100%; }
/* 原文库 */
.doc-toolbar { background: #fff; padding: 12px 24px; border-bottom: 1px solid var(--border); display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap; }
.doc-toolbar .f-item { display: flex; flex-direction: column; gap: 4px; }
.doc-toolbar label { font-size: 12px; color: var(--text-3); }
.doc-toolbar input, .doc-toolbar select { height: 34px; border: 1px solid var(--border); border-radius: 8px; padding: 0 10px; min-width: 160px; }
.doc-toolbar input { min-width: 260px; }
.doc-upload { margin: 14px 24px 0; background: #F5F3FF; border: 1px solid #ddd6fe; border-radius: 10px; padding: 12px 16px; display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap; }
.doc-upload-r { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.doc-upload .form-input { height: 34px; border: 1px solid var(--border); border-radius: 8px; padding: 0 10px; }
.doc-list { flex: 1; overflow: auto; padding: 14px 24px 40px; }
.doc-filter-tip { background: #EFF6FF; border-radius: 8px; padding: 8px 12px; margin-bottom: 12px; font-size: 13px; }
.doc-filter-tip a { color: var(--brand-600,#1a5bb0); cursor: pointer; margin-left: 10px; }
.doc-table { width: 100%; background: #fff; }
.doc-table td { vertical-align: middle; }
.doc-text-tag { white-space: nowrap; }
.doc-law { font-size: 12.5px; font-weight: 600; color: #6d28d9; }
/* 弹窗（层级高于抽屉） */
.pop-mask { position: fixed; inset: 0; background: rgba(20,28,43,.55); z-index: 13000; display: none; align-items: center; justify-content: center; }
.pop-mask.on { display: flex; }
.pop-card { background: #fff; border-radius: 12px; width: 540px; max-width: 94vw; box-shadow: 0 12px 40px rgba(0,0,0,.25); display: flex; flex-direction: column; max-height: 90vh; }
.pop-wide { width: 1000px; }
.pop-hd { padding: 16px 20px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; }
.pop-hd h3 { margin: 0; font-size: 16px; }
.pop-bd { padding: 18px 20px; overflow: auto; }
.pop-ft { padding: 12px 20px; border-top: 1px solid var(--border); display: flex; justify-content: flex-end; gap: 8px; }
.pop-card .form-input { width: 100%; }
.doc-text { white-space: pre-wrap; word-break: break-word; font-family: inherit; font-size: 13px; line-height: 1.8; margin: 0; color: #333; }
.law-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
.law-form-grid .form-input { width: 100%; }
.tag-purple { background: #F5F3FF; color: #6d28d9; }
.tag-orange { background: #FFF7ED; color: #c2410c; }
.tag-red { background: #FEF2F2; color: #dc2626; }
.tag-yellow { background: #FEFCE8; color: #a16207; }
</style>
