<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/api/request'
import { ElMessage } from 'element-plus'
import Icon from '@/components/Icon.vue'
import { useAuthStore } from '@/store/auth'

const router = useRouter()
const auth = useAuthStore()
// 范本定制与生成为服务商 / 超管增值服务；企业账号只读
const canEdit = computed(() => auth.canGenerateTpl)
const loading = ref(false)
const templates = ref([])   // 当前企业生效范本（含 content / baseContent / customized）
const pageCss = ref('')
const sets = ref([])        // 服务商全局范本套（基础默认模板）
const mySets = ref([])      // 我的范本套（另存为 / 应用产生的变体）
const currentSet = ref(null)
const currentMeta = ref(null)
const mapping = ref(null)   // 当前套 ↔ 电子档案柜归档项映射
const view = ref('templates') // templates | mapping
const curBox = ref(0)       // 0 = 全部
const keyword = ref('')

const BOX_NAMES = {
  1: '一、公司档案及管理层档案',
  2: '二、安全生产管理制度及操作规程',
  3: '三、安全生产会议',
  4: '四、安全生产教育和培训',
  5: '五、安全生产费用提取与使用',
  6: '六、动态监控及交通违法管理',
  7: '七、应急管理',
  8: '八、风险分级管控与隐患排查治理',
  9: '九、一日三检',
  10: '十、事故档案',
}

const boxGroups = computed(() => {
  const m = {}
  templates.value.forEach((t) => { const b = t.boxNo || 0; m[b] = (m[b] || 0) + 1 })
  return Object.keys(m).sort((a, b) => Number(a) - Number(b)).map((b) => ({
    no: Number(b), name: BOX_NAMES[b] || `其他（盒 ${b}）`, count: m[b],
  }))
})

const customizedCount = computed(() => templates.value.filter((t) => t.customized).length)
const currentName = computed(() => currentMeta.value?.name || currentSet.value?.name || '普货通用范本（内置）')
const currentCount = computed(() => currentMeta.value?.templateCount ?? templates.value.length)
const defaultProvider = computed(() => sets.value.find((s) => s.isDefault === 1) || sets.value[0] || null)
const mappingGroups = computed(() => {
  const m = {}
  ;(mapping.value?.catalogItems || []).forEach((it) => {
    const k = it.boxNo || 0
    ;(m[k] = m[k] || { boxNo: k, boxName: it.boxName, items: [] }).items.push(it)
  })
  return Object.keys(m).sort((a, b) => Number(a) - Number(b)).map((k) => m[k])
})

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return templates.value.filter((t) => {
    if (curBox.value && t.boxNo !== curBox.value) return false
    if (!kw) return true
    return [t.name, t.code, t.category, t.archiveItem].some((x) => (x || '').toLowerCase().includes(kw))
  })
})

const posts = computed(() => templates.value
  .filter((t) => (t.code || '').startsWith('RESP_LETTER_P'))
  .map((t) => (t.name || '').replace(/^安全生产责任书（/, '').replace(/）$/, '')))

async function loadAll() {
  loading.value = true
  try {
    const [full, cssRes, ovRes, mapRes] = await Promise.all([
      request.get('/tpl/library/full'),
      request.get('/tpl/library/css'),
      request.get('/tpl/ent/overview').catch(() => ({ data: null })),
      request.get('/tpl/library/mapping').catch(() => ({ data: null })),
    ])
    templates.value = full.data || []
    pageCss.value = cssRes.data || ''
    const ov = ovRes.data || {}
    sets.value = ov.providers || []
    mySets.value = ov.mine || []
    currentMeta.value = ov.current || null
    currentSet.value = mySets.value.find((s) => s.isCurrent === 1) || null
    mapping.value = mapRes.data || null
  } finally {
    loading.value = false
  }
}
onMounted(loadAll)

// ---------- A4 预览（iframe srcdoc：与 PDF 共用同一份版式 CSS，完全隔离不污染页面） ----------
const showPreview = ref(false)
const previewTpl = ref(null)
const previewDoc = ref('')
const previewFrame = ref(null)
const postForPreview = ref('')

function srcdoc(body) {
  const screenFont = "body{font-family:'PingFang SC','Microsoft YaHei','Noto Sans CJK SC',sans-serif !important;padding:44px 50px;margin:0;}"
  return `<!DOCTYPE html><html lang="zh"><head><meta charset="UTF-8"><style>${pageCss.value}${screenFont}</style></head><body>${body}</body></html>`
}

async function openPreview(t) {
  previewTpl.value = t
  showPreview.value = true
  previewDoc.value = srcdoc('<div class="doc-p">正在渲染范本…</div>')
  try {
    const res = await request.get(`/tpl/library/render/${encodeURIComponent(t.code)}`, {
      // 空白范本模式：变量保持占位、无章、无真实人名
      params: { mode: 'blank', post: postForPreview.value },
    })
    previewDoc.value = srcdoc(res.data || '')
  } catch (e) {
    previewDoc.value = srcdoc('<div class="doc-p">范本渲染失败，请稍后重试。</div>')
  }
}

function printPreview() {
  try { previewFrame.value?.contentWindow?.focus(); previewFrame.value?.contentWindow?.print() }
  catch (e) { ElMessage.warning('当前浏览器不支持直接打印，请先下载 PDF') }
}

async function downloadPreviewPdf() {
  const t = previewTpl.value
  if (!t) return
  const res = await fetch(`/api/tpl/library/pdf/${encodeURIComponent(t.code)}?mode=blank&post=${encodeURIComponent(postForPreview.value || '')}`, {
    headers: {
      Authorization: `Bearer ${localStorage.getItem('token')}`,
      'X-Token': `Bearer ${localStorage.getItem('token')}`,
    },
  })
  if (!res.ok) { ElMessage.error('PDF 生成失败'); return }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${t.name}-空白范本.pdf`
  a.click()
  URL.revokeObjectURL(url)
}

// ---------- 在线编辑范本 HTML（含 {{变量}} / {% if %} 条件段） ----------
const showEdit = ref(false)
const editing = ref(null)
const editCode = ref('')
const editLabel = ref('')
const codeArea = ref(null)

const VARS = [
  { k: 'company_name', d: '企业名称' },
  { k: 'year', d: '归档年度' },
  { k: 'post', d: '岗位（决定条件段）' },
  { k: 'title', d: '文档标题' },
  { k: 'doc_no', d: '文件编号' },
  { k: 'sign_date', d: '签订/填表日期' },
  { k: 'person_name', d: '责任人' },
  { k: 'leader_name', d: '责任管理人' },
  { k: 'issuer', d: '签发人' },
  { k: '公司名称', d: '企业名称（中文变量）' },
  { k: '企业地址', d: '企业地址' },
  { k: '值守电话', d: '值守电话' },
]

// 模板中不能直接写 {{ '{{' + k + '}}' }}（会被 Vue 当成插值提前闭合），用函数生成字面量
const varLabel = (k) => `{{${k}}}`

function openEdit(t) {
  editing.value = t
  editCode.value = t.content || ''
  editLabel.value = t.customized ? (t.versionLabel || '本企业定制') : ''
  showEdit.value = true
}
function resetEditCode() {
  editCode.value = editing.value?.content || editing.value?.baseContent || ''
}
function insertVar(k) {
  const ta = codeArea.value
  const snippet = `{{${k}}}`
  if (!ta) { editCode.value += snippet; return }
  const p = ta.selectionStart ?? editCode.value.length
  editCode.value = editCode.value.slice(0, p) + snippet + editCode.value.slice(p)
  nextTick(() => { ta.focus(); ta.selectionStart = ta.selectionEnd = p + snippet.length })
}
function insertIf() {
  const ta = codeArea.value
  const snippet = "{% if post == '主要负责人' %}\n\n{% else %}\n\n{% endif %}"
  if (!ta) { editCode.value += snippet; return }
  const p = ta.selectionStart ?? editCode.value.length
  editCode.value = editCode.value.slice(0, p) + snippet + editCode.value.slice(p)
  nextTick(() => { ta.focus(); ta.selectionStart = ta.selectionEnd = p + snippet.length })
}
async function saveEdit() {
  if (!editing.value) return
  await request.put(`/tpl/library/doc/${encodeURIComponent(editing.value.code)}`, {
    content: editCode.value,
    versionLabel: editLabel.value.trim() || '本企业定制',
  })
  ElMessage.success('已保存为当前应用范本，电子档案柜将按此范本生成台账')
  showEdit.value = false
  await loadAll()
  openPreview({ ...editing.value, content: editCode.value })
}
async function resetTpl(t) {
  await request.delete(`/tpl/library/doc/${encodeURIComponent(t.code)}`)
  ElMessage.success('已恢复服务商内置范本')
  await loadAll()
}
function previewEdit() {
  if (!editing.value) return
  previewTpl.value = editing.value
  showPreview.value = true
  previewDoc.value = srcdoc(editCode.value
    .replace(/\{\{\s*[^}]+\s*\}\}/g, '<span class="fill">&nbsp;&nbsp;&nbsp;&nbsp;</span>')
    .replace(/\{%[^%]*%\}/g, ''))
}

// ---------- 联动编辑：责任制 ↔ 责任书 ----------
const showResp = ref(false)
const respPost = ref('')
const respTarget = ref('')
const respDuty = ref('')
const respAssess = ref('')

function parseResp(content, post) {
  const doc = new DOMParser().parseFromString(content || '', 'text/html')
  const block = doc.querySelector(`.resp-post[data-post="${post}"]`)
  if (!block) return null
  const pick = (k) => Array.from(block.querySelectorAll(`.resp-${k} .doc-li`))
    .map((e) => e.textContent.trim()).filter(Boolean).join('\n')
  return { target: pick('target'), duty: pick('duty'), assess: pick('assess') }
}
function openResp() {
  const list = posts.value
  respPost.value = list[0] || '主要负责人'
  fillResp()
  showResp.value = true
}
function fillResp() {
  const sys = templates.value.find((t) => t.code === 'RESPONSIBILITY')
  const r = parseResp(sys?.content, respPost.value)
  respTarget.value = r?.target || ''
  respDuty.value = r?.duty || ''
  respAssess.value = r?.assess || ''
}
async function saveResp() {
  await request.post('/tpl/library/responsibility', {
    post: respPost.value,
    target: respTarget.value,
    duty: respDuty.value,
    assess: respAssess.value,
  })
  ElMessage.success(`已联动更新《安全生产责任制》与《安全生产责任书（${respPost.value}）》`)
  showResp.value = false
  await loadAll()
}

// ---------- 范本套：切换 / 应用服务商套 / 另存为新套 ----------
const showSaveAs = ref(false)
const saveAsName = ref('')
const showApply = ref(false)
const applySetId = ref(null)
const applyName = ref('')

function openSaveAs() {
  saveAsName.value = currentName.value + '（副本）'
  showSaveAs.value = true
}
async function doSaveAs() {
  const name = saveAsName.value.trim()
  if (!name) { ElMessage.warning('请输入新范本套名称'); return }
  const res = await request.post('/tpl/ent/save-as', { name })
  ElMessage.success(`已另存为新范本套「${name}」，并设为当前应用`)
  showSaveAs.value = false
  await loadAll()
  return res
}

function openApply(row) {
  const s = row || defaultProvider.value
  if (!s) { ElMessage.warning('暂无服务商范本套'); return }
  applySetId.value = s.id
  applyName.value = s.name + '（企业应用）'
  showApply.value = true
}
async function doApply() {
  const name = applyName.value.trim()
  if (!name) { ElMessage.warning('请输入应用后的范本套名称'); return }
  await request.post('/tpl/ent/apply', { sourceSetId: applySetId.value, name })
  ElMessage.success(`已以服务商范本套为基础创建「${name}」，并设为当前应用`)
  showApply.value = false
  await loadAll()
}

async function switchSet(row) {
  if (!row || String(row.id) === String(currentMeta.value?.id)) return
  await request.put(`/tpl/ent/${row.id}/current`)
  ElMessage.success(`已切换当前应用范本套为「${row.name}」`)
  await loadAll()
}
function onSwitchSelect(e) {
  const row = mySets.value.find((s) => String(s.id) === String(e.target.value))
  if (row) switchSet(row)
}
async function deleteSet(row) {
  if (row.isCurrent === 1) { ElMessage.warning('当前应用范本套不可删除'); return }
  await request.delete(`/tpl/ent/${row.id}`)
  ElMessage.success(`已删除范本套「${row.name}」`)
  await loadAll()
}

function goArchive() { router.push('/archive') }
function goArchiveBox(boxNo, name) {
  router.push({ path: '/archive', query: { box: boxNo || 1, item: name || '' } })
}
function catalogNameOf(t) {
  const hit = (mapping.value?.templates || []).find((x) => x.code === t.code)
  return hit?.catalogName || t.archiveItem || '—'
}

// ---------- 印章设置（原型《09-静态资料范本引擎》6.2：两种印章来源） ----------
const showSeal = ref(false)
const sealInfo = ref(null)
const sealUploading = ref(false)

async function loadSeal() {
  try { sealInfo.value = (await request.get('/tpl/seal')).data } catch (e) { sealInfo.value = null }
}
async function openSeal() {
  showSeal.value = true
  await loadSeal()
}
function pickSealFile(e) {
  const f = e.target.files?.[0]
  if (!f) return
  if (!/^image\/(png|jpeg|gif|webp)$/.test(f.type)) { ElMessage.error('请上传 PNG / JPG 图片'); return }
  if (f.size > 2 * 1024 * 1024) { ElMessage.error('印章图片请压缩到 2MB 以内'); return }
  const reader = new FileReader()
  reader.onload = async () => {
    sealUploading.value = true
    try {
      sealInfo.value = (await request.post('/tpl/seal/upload', { image: reader.result })).data
      ElMessage.success('已应用真实印章，电子档案柜生成的台账成品将统一使用此章')
    } catch (err) { ElMessage.error('印章上传失败，请重试') }
    finally { sealUploading.value = false }
  }
  reader.readAsDataURL(f)
  e.target.value = ''
}
async function resetSeal() {
  try {
    sealInfo.value = (await request.delete('/tpl/seal')).data
    ElMessage.success('已恢复按企业全称自动生成的圆形红章')
  } catch (e) { ElMessage.error('操作失败，请重试') }
}
</script>

<template>
  <div class="pc-page" v-loading="loading">
    <div class="pc-header">
      <div>
        <h1>台账范本管理</h1>
        <div class="desc">维护企业当前应用的空白范本 · 企业信息、岗位人名与盖章只在电子档案柜生成成品时填入</div>
      </div>
      <div class="actions">
        <button v-if="canEdit" class="btn btn-outline" @click="openApply()"><Icon name="copy" :size="16" /> 应用服务商范本套</button>
        <button v-if="canEdit" class="btn btn-outline" @click="openSaveAs"><Icon name="save" :size="16" /> 另存为新范本套</button>
        <button class="btn btn-outline" @click="view = view === 'mapping' ? 'templates' : 'mapping'">
          <Icon name="table" :size="16" /> {{ view === 'mapping' ? '返回范本清单' : '档案柜对应' }}
        </button>
        <button v-if="canEdit" class="btn btn-outline" @click="openSeal"><Icon name="award" :size="16" /> 印章设置</button>
        <button v-if="canEdit" class="btn btn-outline" @click="openResp"><Icon name="users" :size="16" /> 责任制联动编辑</button>
        <button class="btn btn-primary" @click="goArchive"><Icon name="archive" :size="16" /> 去电子档案柜验证</button>
      </div>
    </div>

    <!-- 增值服务权限提示 -->
    <div v-if="!canEdit" class="vas-tip">
      <Icon name="info" :size="15" />
      <span><b>台账范本定制与生成为服务商增值服务</b>：企业账号可只读查看当前应用范本套、预览空白范本、查看档案柜对应关系与下载成品；如需应用 / 另存范本套、在线修改范本、设置印章或生成台账，请联系服务商。</span>
    </div>
    <div v-else class="vas-tip vas-tip-ok">
      <Icon name="building" :size="15" />
      <span><b>正在为「{{ auth.targetTenantName || '未选择企业' }}」维护台账范本</b>：改动将作用于该企业的当前应用范本套，电子档案柜生成成品时按此套渲染。可在右上角「服务企业」切换。</span>
    </div>

    <!-- 当前应用范本套：切换器 + 联动提示条 -->
    <div class="tpl-curbar">
      <div class="cb-left">
        <Icon name="bookOpen" :size="20" />
        <div>
          <div class="cb-t">当前应用范本套：
            <select class="set-select" :disabled="!canEdit" :value="String(currentMeta?.id || '')" @change="onSwitchSelect">
              <option v-for="s in mySets" :key="s.id" :value="String(s.id)">{{ s.name }}（{{ s.templateCount }} 份）</option>
              <option v-if="!mySets.length" value="">普货通用范本（内置）</option>
            </select>
          </div>
          <div class="cb-d">共 {{ currentCount }} 份范本 · 已定制 {{ customizedCount }} 份 · 基础来源：{{ currentSet?.sourceName || '服务商默认范本套' }}</div>
        </div>
      </div>
      <div class="cb-tip">
        <Icon name="info" :size="15" /> 切换范本套后，电子档案柜点「生成」即按当前套的范本渲染并归档
      </div>
    </div>

    <!-- 模板层 / 成品层分层说明 -->
    <div class="layer-note">
      <Icon name="info" :size="16" />
      <div>
        <b>这里是模板层：预览与下载的都是「空白范本」</b>
        <span>变量以灰色 <code v-pre>{{变量名}}</code> 占位，不填企业名与人名、不盖公司章。</span>
        <span>带真实岗位人名与公司章的正式台账，在 <a href="javascript:;" @click="goArchive">电子档案柜</a> 按范本生成。</span>
      </div>
    </div>

    <div class="tpl-shell">
      <!-- 左：档案盒树 + 我的范本 -->
      <div class="tpl-left card">
        <div class="card-title">档案盒目录 <span class="more">{{ templates.length }} 份</span></div>
        <div class="tree-item" :class="{ on: curBox === 0 }" @click="curBox = 0">
          <Icon name="folder" :size="16" /> <span>全部范本</span> <em>{{ templates.length }}</em>
        </div>
        <div v-for="g in boxGroups" :key="g.no" class="tree-item" :class="{ on: curBox === g.no }" @click="curBox = g.no">
          <Icon name="folder" :size="16" /> <span :title="g.name">{{ g.name }}</span> <em>{{ g.count }}</em>
        </div>

        <div class="card-title" style="margin-top:22px">当前应用范本套 <span class="more">{{ mySets.length }} 套</span></div>
        <div v-if="!mySets.length" class="empty-mini">暂无，点「应用服务商范本套」或「另存为新范本套」创建</div>
        <div v-for="s in mySets" :key="s.id" class="set-item" :class="{ on: s.isCurrent === 1 }" @click="canEdit && switchSet(s)" :title="`切换为当前应用范本套：${s.name}`">
          <Icon name="book" :size="15" />
          <span>{{ s.name }}</span>
          <span class="set-cnt">{{ s.templateCount }}</span>
          <span v-if="s.isCurrent === 1" class="tag tag-green">当前</span>
          <span v-else-if="canEdit" class="set-del" title="删除此套" @click.stop="deleteSet(s)">×</span>
        </div>
        <div class="set-hint">点击某套即设为「当前应用范本套」，电子档案柜生成随之切换</div>

        <div class="card-title" style="margin-top:22px">服务商范本套 <span class="more">基础默认模板</span></div>
        <div v-for="s in sets" :key="s.id" class="set-item provider" @click="canEdit && openApply(s)" :title="`以「${s.name}」为基础应用为新的当前范本套`">
          <Icon name="bookOpen" :size="15" /> <span>{{ s.name }}</span>
          <span class="set-cnt">{{ s.templateCount }}</span>
          <span v-if="s.isDefault === 1" class="tag tag-blue">默认</span>
          <span v-else class="set-apply">应用</span>
        </div>
        <div class="set-hint">服务商套为只读基线；「应用」会复制整套内容成为企业自己的范本套</div>
      </div>

      <!-- 右：范本清单 / 档案柜对应 -->
      <div class="tpl-right card">
        <template v-if="view === 'templates'">
          <div class="list-head">
            <div>
              <h2>{{ curBox ? BOX_NAMES[curBox] : '全部台账范本' }}</h2>
              <div class="sub">共 {{ filtered.length }} 份 · 当前套「{{ currentName }}」· 预览为空白范本（变量占位、不盖章、无人名）</div>
            </div>
            <input v-model="keyword" class="form-input search" placeholder="搜索范本名称 / 编码 / 归档项" />
          </div>

          <div class="tpl-grid">
            <div v-for="t in filtered" :key="t.code" class="tpl-card">
              <div class="tc-top">
                <span class="tc-box">盒 {{ t.boxNo }}</span>
                <span v-if="t.customized" class="tag tag-yellow">已定制</span>
                <span v-else class="tag tag-gray">内置</span>
              </div>
              <div class="tc-name" :title="t.name">{{ t.name }}</div>
              <div class="tc-meta">
                <div><span>编码</span>{{ t.code }}</div>
                <div><span>类型</span>{{ t.category || '—' }}</div>
                <div><span>归档项</span>{{ catalogNameOf(t) }}</div>
                <div><span>版本</span>{{ t.versionLabel || ('v' + (t.version || 1)) }}</div>
              </div>
              <div class="tc-actions">
                <button class="btn btn-outline btn-sm" @click="openPreview(t)"><Icon name="fileText" :size="14" /> 预览</button>
                <button v-if="canEdit" class="btn btn-primary btn-sm" @click="openEdit(t)"><Icon name="edit" :size="14" /> 在线修改</button>
                <button class="btn btn-outline btn-sm" @click="goArchiveBox(t.boxNo, catalogNameOf(t))"><Icon name="link" :size="14" /> 定位档案柜</button>
                <button v-if="t.customized && canEdit" class="btn btn-outline btn-sm" @click="resetTpl(t)"><Icon name="x" :size="14" /> 恢复内置</button>
              </div>
            </div>
          </div>
          <div v-if="!filtered.length" class="empty">没有匹配的范本</div>
        </template>

        <template v-else>
          <div class="list-head">
            <div>
              <h2>台账范本 ↔ 电子档案柜对应关系</h2>
              <div class="sub">当前应用范本套「{{ currentName }}」· 每份范本都有归档去向；档案柜中「营业执照」等证件无范本，需手动上传留存</div>
            </div>
            <div class="map-stat">
              <span>范本 <b>{{ mapping?.summary?.templateCount || 0 }}</b></span>
              <span>已对应 <b>{{ mapping?.summary?.mappedCount || 0 }}</b></span>
              <span>归档项 <b>{{ mapping?.summary?.catalogItemCount || 0 }}</b></span>
              <span class="warn">手动上传 <b>{{ mapping?.summary?.uploadOnlyCount || 0 }}</b></span>
            </div>
          </div>
          <div v-for="g in mappingGroups" :key="g.boxNo" class="map-group">
            <div class="map-gt">盒 {{ g.boxNo }} · {{ g.boxName }}</div>
            <div class="map-rows">
              <div v-for="it in g.items" :key="it.id" class="map-row" :class="{ off: !it.hasTemplate }">
                <div class="mr-name">{{ it.name }}</div>
                <div v-if="it.hasTemplate" class="mr-tag auto">
                  按范本生成 · {{ it.tplCodes.length }} 份
                  <span class="mr-codes">{{ it.tplCodes.slice(0, 3).join('、') }}{{ it.tplCodes.length > 3 ? '…' : '' }}</span>
                </div>
                <div v-else class="mr-tag upload">无范本 · 手动上传留存</div>
                <button class="btn btn-outline btn-sm" @click="goArchiveBox(g.boxNo, it.name)">去档案柜</button>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 在线修改范本 -->
    <div class="modal-mask" :class="{ show: showEdit }" @click.self="showEdit = false">
      <div class="modal-box wide">
        <div class="modal-head">
          在线修改范本 <span class="mh-sub">{{ editing?.name }} · {{ editing?.code }}</span>
          <span class="x" @click="showEdit = false">×</span>
        </div>
        <div class="modal-body">
          <div class="edit-layout">
            <div class="edit-main">
              <div class="eh-t">范本 HTML 源码（保留 <code v-pre>{{变量名}}</code> 与 <code v-pre>{% if %}</code> 条件段）</div>
              <textarea ref="codeArea" v-model="editCode" class="editor-code" spellcheck="false"></textarea>
            </div>
            <div class="edit-side">
              <div class="es-t">可用变量（点击插入光标处）</div>
              <div class="var-list">
                <button v-for="v in VARS" :key="v.k" class="var-chip" @click="insertVar(v.k)">
                  <code>{{ varLabel(v.k) }}</code><span>{{ v.d }}</span>
                </button>
              </div>
              <div class="es-t" style="margin-top:16px">条件段落</div>
              <button class="btn btn-outline btn-sm" @click="insertIf">插入 {% if %} 片段</button>
              <p class="hint">条件段按岗位解析，例如 <code>{% if post == '驾驶员' %}</code>；未提供的变量在成品中留空白填写栏。</p>
            </div>
          </div>
          <div class="edit-foot">
            <label>版本说明 <input v-model="editLabel" class="form-input" placeholder="例如：2026 修订版" /></label>
            <div class="ef-btns">
              <button class="btn btn-outline btn-sm" @click="resetEditCode">还原</button>
              <button class="btn btn-outline btn-sm" @click="previewEdit">预览效果</button>
              <button class="btn btn-primary btn-sm" @click="saveEdit">保存本范本</button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- A4 预览 -->
    <div class="modal-mask" :class="{ show: showPreview }" @click.self="showPreview = false">
      <div class="modal-box preview-box">
        <div class="modal-head">
          空白范本预览 <span class="mh-sub">{{ previewTpl?.name }} · 当前应用范本（模板层）</span>
          <span class="x" @click="showPreview = false">×</span>
        </div>
        <div class="pdf-toolbar">
          <select v-model="postForPreview" class="form-input" style="width:150px" @change="openPreview(previewTpl)">
            <option value="">默认岗位</option>
            <option v-for="p in posts" :key="p" :value="p">{{ p }}</option>
          </select>
          <span class="tb-tip">岗位仅决定条件段版式；灰色 <code v-pre>{{变量}}</code> 为待填字段，成品在电子档案柜生成</span>
          <div style="margin-left:auto; display:flex; gap:8px">
            <button class="btn btn-outline btn-sm" @click="printPreview"><Icon name="fileText" :size="14" /> 打印</button>
            <button class="btn btn-primary btn-sm" @click="downloadPreviewPdf"><Icon name="download" :size="14" /> 下载 PDF</button>
          </div>
        </div>
        <div class="pdf-body"><iframe ref="previewFrame" class="pdf-frame" :srcdoc="previewDoc" /></div>
      </div>
    </div>

    <!-- 联动编辑 -->
    <div class="modal-mask" :class="{ show: showResp }" @click.self="showResp = false">
      <div class="modal-box">
        <div class="modal-head">责任制 / 责任书 联动编辑 <span class="x" @click="showResp = false">×</span></div>
        <div class="modal-body">
          <p class="hint" style="margin-top:0">一次修改同时写回《安全生产责任制》中该岗位区块，以及该岗位《安全生产责任书》全文，两处始终一致。</p>
          <div class="form-item"><label class="form-label">岗位</label>
            <select v-model="respPost" class="form-input" @change="fillResp">
              <option v-for="p in posts" :key="p" :value="p">{{ p }}</option>
            </select>
          </div>
          <div class="form-item"><label class="form-label">安全生产目标（每行一条）</label><textarea v-model="respTarget" class="form-input area"></textarea></div>
          <div class="form-item"><label class="form-label">安全职责（每行一条）</label><textarea v-model="respDuty" class="form-input area"></textarea></div>
          <div class="form-item"><label class="form-label">考核标准（每行一条）</label><textarea v-model="respAssess" class="form-input area"></textarea></div>
          <div style="text-align:right; margin-top:16px">
            <button class="btn btn-outline btn-sm" @click="showResp = false">取消</button>
            <button class="btn btn-primary btn-sm" style="margin-left:8px" @click="saveResp">保存并联动两份范本</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 另存为新范本套 -->
    <div class="modal-mask" :class="{ show: showSaveAs }" @click.self="showSaveAs = false">
      <div class="modal-box small">
        <div class="modal-head">另存为新范本套 <span class="x" @click="showSaveAs = false">×</span></div>
        <div class="modal-body">
          <div class="form-item"><label class="form-label">新范本套名称 <span style="color:#DC2626">*</span></label>
            <input v-model="saveAsName" class="form-input" placeholder="例如：普货通用范本（2026 修订版）" />
          </div>
          <p class="hint">把当前应用套的<b>全部范本内容（含已定制修改）</b>整体复制成一套独立的新范本套，并自动设为当前应用。原套保留，可在顶部切换器或左侧列表随时切回。</p>
          <div style="text-align:right; margin-top:16px"><button class="btn btn-primary btn-sm" @click="doSaveAs">另存为新套</button></div>
        </div>
      </div>
    </div>

    <!-- 应用服务商范本套 -->
    <div class="modal-mask" :class="{ show: showApply }" @click.self="showApply = false">
      <div class="modal-box small">
        <div class="modal-head">应用服务商范本套 <span class="x" @click="showApply = false">×</span></div>
        <div class="modal-body">
          <div class="form-item"><label class="form-label">选择服务商范本套 <span style="color:#DC2626">*</span></label>
            <select v-model="applySetId" class="form-input">
              <option v-for="s in sets" :key="s.id" :value="s.id">{{ s.name }}（{{ s.templateCount }} 份）{{ s.isDefault === 1 ? ' · 基础默认' : '' }}</option>
            </select>
          </div>
          <div class="form-item"><label class="form-label">应用后的范本套名称 <span style="color:#DC2626">*</span></label>
            <input v-model="applyName" class="form-input" placeholder="例如：普货小微范本（企业应用）" />
          </div>
          <p class="hint">以服务商账号提供的范本套作为<b>基础默认模板</b>，复制整套内容成为企业自己的「当前应用范本套」，之后可在线修改定制、另存为新套。切换后电子档案柜的生成结果会随之改变。</p>
          <div style="text-align:right; margin-top:16px"><button class="btn btn-primary btn-sm" @click="doApply">应用并设为当前</button></div>
        </div>
      </div>
    </div>

    <!-- 印章设置：原型要求所有台账生成时在落款处自动盖公司章 -->
    <div class="modal-mask" :class="{ show: showSeal }" @click.self="showSeal = false">
      <div class="modal-box small">
        <div class="modal-head">印章设置 <span class="x" @click="showSeal = false">×</span></div>
        <div class="modal-body">
          <p class="hint" style="margin-top:0">
            原型《09-静态资料范本引擎》第六章：所有台账生成时统一在落款处盖公司章。
            优先使用客户上传的真实印章；未上传时按企业全称自动生成标准圆形红章（内部文件章，非法定公章）。
          </p>
          <p class="hint" style="margin:0 0 4px;color:#B45309">
            <b>注意：</b>印章只出现在「电子档案柜」生成的成品上；本页的空白范本预览与下载均不盖章。
          </p>
          <div class="seal-preview">
            <img v-if="sealInfo" :src="sealInfo.dataUri" alt="当前生效印章" />
            <span v-else style="color:#94A3B8;font-size:13px">读取中…</span>
          </div>
          <div v-if="sealInfo" class="seal-meta">
            <div>当前来源：<b>{{ sealInfo.label }}</b></div>
            <div>企业全称：{{ sealInfo.companyName }}</div>
            <div>规格：圆形 · 直径 40mm · 印泥红 · 上弧公司全称 · 中心五角星 · 内外双圈</div>
          </div>
          <div class="seal-actions">
            <label class="btn btn-primary btn-sm" style="cursor:pointer">
              <Icon name="upload" :size="14" /> 上传真实印章
              <input type="file" accept="image/png,image/jpeg,image/webp" style="display:none" :disabled="sealUploading" @change="pickSealFile" />
            </label>
            <button class="btn btn-outline btn-sm" @click="resetSeal"><Icon name="rotateCcw" :size="14" /> 恢复自动章</button>
          </div>
          <p class="hint">透明底 PNG 效果最佳（建议 300×300 以上、2MB 以内）。切换后电子档案柜的成品预览与生成 PDF 立即生效。</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 模板层 / 成品层分层说明条 */
.layer-note { display: flex; align-items: flex-start; gap: 10px; margin: 0 0 16px;
  padding: 11px 14px; background: #EFF6FF; border: 1px solid #BFDBFE; border-radius: 8px;
  color: #1E40AF; font-size: 12.5px; line-height: 1.8; }
.layer-note > svg { flex: none; margin-top: 3px; }
.layer-note div { display: flex; flex-direction: column; }
.layer-note code { padding: 0 4px; background: #DBEAFE; border-radius: 3px; }
.layer-note a { color: #1D4ED8; text-decoration: underline; }

/* 印章设置 */
.seal-preview { display: flex; align-items: center; justify-content: center; height: 186px;
  background: #fff; border: 1px dashed var(--border, #CBD5E1); border-radius: 10px; margin: 14px 0; }
.seal-preview img { width: 152px; height: 152px; object-fit: contain; }
.seal-meta { font-size: 12.5px; line-height: 1.95; color: var(--text-2, #475569); }
.seal-actions { display: flex; gap: 8px; margin: 14px 0 8px; }

.tpl-curbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap;
  background: var(--blue-50, #EFF6FF); border: 1px solid var(--blue-200, #BFDBFE); border-radius: var(--r-lg, 12px);
  padding: 14px 18px; margin-bottom: 16px; }
.cb-left { display: flex; align-items: center; gap: 12px; color: var(--blue-800, #1E40AF); }
.cb-t { font-size: 15px; font-weight: 700; }
.cb-d { font-size: 12.5px; color: var(--text-3, #64748B); margin-top: 3px; }
.cb-tip { display: flex; align-items: center; gap: 7px; font-size: 12.5px; color: var(--blue-800, #1E40AF); }
.tpl-shell { display: flex; gap: 16px; align-items: flex-start; }
.tpl-left { width: 272px; flex-shrink: 0; max-height: calc(100vh - 210px); overflow-y: auto; }
.tpl-right { flex: 1; min-width: 0; }
.tree-item { display: flex; align-items: center; gap: 9px; padding: 9px 10px; border-radius: 8px; cursor: pointer;
  font-size: 13.5px; color: var(--text-2, #475569); transition: .12s; }
.tree-item span { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-item em { font-style: normal; font-size: 12px; color: var(--text-3, #94A3B8); }
.tree-item:hover { background: #F1F5F9; }
.tree-item.on { background: var(--blue-50, #EFF6FF); color: var(--blue-800, #1E40AF); font-weight: 700; }
.set-item { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 8px; font-size: 13px;
  color: var(--text-2); cursor: pointer; }
.set-item span:nth-child(2) { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.set-item.on { background: #F1F5F9; color: var(--blue-800); }
.set-item:hover { background: #F8FAFC; }
.empty-mini { font-size: 12.5px; color: var(--text-3); padding: 8px 10px; line-height: 1.6; }
.list-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
.list-head h2 { font-size: 17px; font-weight: 700; }
.list-head .sub { font-size: 12.5px; color: var(--text-3); margin-top: 4px; }
.search { width: 280px; }
.tpl-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(268px, 1fr)); gap: 14px; }
.tpl-card { border: 1px solid var(--border, #E2E8F0); border-radius: 12px; padding: 14px; background: #fff; transition: .15s; }
.tpl-card:hover { border-color: var(--blue-400, #60A5FA); box-shadow: 0 4px 14px rgba(30,64,175,.08); }
.tc-top { display: flex; align-items: center; gap: 6px; margin-bottom: 10px; }
.tc-box { font-size: 12px; font-weight: 700; color: var(--blue-800); background: var(--blue-50); padding: 2px 8px; border-radius: 6px; }
.tc-name { font-size: 14.5px; font-weight: 700; line-height: 1.45; min-height: 42px; color: var(--text-1, #1E293B); }
.tc-meta { margin: 8px 0 12px; font-size: 12px; color: var(--text-2); }
.tc-meta div { display: flex; gap: 8px; padding: 2px 0; }
.tc-meta span { color: var(--text-3); width: 46px; flex-shrink: 0; }
.tc-actions { display: flex; gap: 6px; flex-wrap: wrap; }
.tc-actions .btn { min-height: 30px; padding: 5px 10px; font-size: 12.5px; }
.empty { text-align: center; color: var(--text-3); padding: 60px 0; }
/* 增值服务权限提示条 */
.vas-tip { display: flex; align-items: flex-start; gap: 8px; padding: 10px 14px; margin: 0 0 14px; border-radius: 8px;
  background: #fff7ed; border: 1px solid #fed7aa; color: #9a3412; font-size: 13px; line-height: 1.7; }
.vas-tip b { color: #7c2d12; }
.vas-tip-ok { background: #eff6ff; border-color: #bfdbfe; color: #1e40af; }
.vas-tip-ok b { color: #1e3a8a; }
.modal-mask { position: fixed; inset: 0; background: rgba(20,28,43,.5); display: none; align-items: center;
  justify-content: center; z-index: 300; }
.modal-mask.show { display: flex; }
.modal-box { background: #fff; border-radius: 12px; width: 620px; max-width: 94vw; max-height: 92vh; display: flex;
  flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,.22); }
.modal-box.wide { width: 1020px; }
.modal-box.small { width: 480px; }
.modal-box.preview-box { width: 940px; height: 92vh; }
.modal-head { display: flex; align-items: center; gap: 10px; padding: 15px 18px; border-bottom: 1px solid var(--border);
  font-size: 16px; font-weight: 700; }
.mh-sub { font-size: 12.5px; color: var(--text-3); font-weight: 400; }
.modal-head .x { margin-left: auto; cursor: pointer; font-size: 24px; line-height: 1; color: var(--text-3); font-weight: 400; }
.modal-head .x:hover { color: #DC2626; }
.modal-body { padding: 16px 18px; overflow-y: auto; }
.edit-layout { display: flex; gap: 16px; }
.edit-main { flex: 1; min-width: 0; }
.edit-side { width: 250px; flex-shrink: 0; }
.eh-t, .es-t { font-size: 13px; font-weight: 700; margin-bottom: 8px; color: var(--text-2); }
.eh-t code, .hint code { background: #F1F5F9; padding: 1px 4px; border-radius: 4px; font-size: 12px; }
.editor-code { width: 100%; height: 460px; border: 1px solid var(--border); border-radius: 8px; padding: 12px;
  font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px; line-height: 1.65; resize: vertical; }
.var-list { display: flex; flex-direction: column; gap: 5px; max-height: 360px; overflow-y: auto; }
.var-chip { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; border: 1px solid var(--border);
  background: #fff; border-radius: 6px; padding: 6px 8px; cursor: pointer; text-align: left; }
.var-chip:hover { border-color: var(--blue-400); background: var(--blue-50); }
.var-chip code { font-size: 11.5px; color: var(--blue-800); }
.var-chip span { font-size: 11.5px; color: var(--text-3); }
.hint { font-size: 12.5px; color: var(--text-3); line-height: 1.7; margin-top: 10px; }
.edit-foot { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 14px; flex-wrap: wrap; }
.edit-foot label { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-2); }
.edit-foot .form-input { width: 220px; }
.ef-btns { display: flex; gap: 8px; }
.ef-btns .btn { min-height: 34px; padding: 6px 14px; }
.pdf-toolbar { display: flex; align-items: center; gap: 10px; padding: 10px 16px; background: #F8FAFC;
  border-bottom: 1px solid var(--border); flex-wrap: wrap; }
.tb-tip { font-size: 12px; color: var(--text-3); }
.pdf-body { flex: 1; overflow-y: auto; background: #E9EDF3; padding: 22px; display: flex; justify-content: center; }
.pdf-frame { width: 794px; min-height: 100%; background: #fff; border: none; box-shadow: 0 2px 18px rgba(0,0,0,.14); }
.form-item { margin-bottom: 14px; }
.form-label { display: block; font-size: 13px; font-weight: 600; margin-bottom: 6px; color: var(--text-2); }
.area { min-height: 92px; resize: vertical; line-height: 1.7; }

/* 当前应用范本套切换器 */
.set-select { margin-left: 4px; min-width: 230px; max-width: 340px; height: 30px; padding: 0 8px;
  border: 1px solid var(--blue-300, #93C5FD); border-radius: 7px; background: #fff;
  color: var(--blue-800, #1E40AF); font-size: 13.5px; font-weight: 700; cursor: pointer; }
.set-cnt { font-size: 11.5px; color: var(--text-3, #94A3B8); }
.set-del { color: var(--text-3); font-size: 17px; line-height: 1; padding: 0 4px; }
.set-del:hover { color: #DC2626; }
.set-apply { font-size: 11.5px; color: var(--blue-700, #1D4ED8); }
.provider:hover .set-apply { text-decoration: underline; }
.set-hint { font-size: 11.5px; color: var(--text-3); line-height: 1.65; padding: 4px 10px 0; }

/* 台账范本 ↔ 档案柜对应视图 */
.map-stat { display: flex; gap: 14px; flex-wrap: wrap; font-size: 12.5px; color: var(--text-2); }
.map-stat b { color: var(--blue-800); font-size: 15px; }
.map-stat .warn b { color: #B45309; }
.map-group { margin-bottom: 18px; }
.map-gt { font-size: 13.5px; font-weight: 700; color: var(--blue-800); background: var(--blue-50, #EFF6FF);
  border-radius: 7px; padding: 8px 12px; margin-bottom: 8px; }
.map-rows { border: 1px solid var(--border, #E2E8F0); border-radius: 9px; overflow: hidden; }
.map-row { display: flex; align-items: center; gap: 12px; padding: 9px 12px; border-bottom: 1px solid #F1F5F9; font-size: 13px; }
.map-row:last-child { border-bottom: none; }
.map-row.off { background: #FFFBEB; }
.mr-name { flex: 1; min-width: 0; color: var(--text-1); }
.mr-tag { font-size: 12px; padding: 3px 9px; border-radius: 6px; white-space: nowrap; }
.mr-tag.auto { background: #EFF6FF; color: #1D4ED8; }
.mr-tag.upload { background: #FEF3C7; color: #92400E; }
.mr-codes { display: block; font-size: 11px; color: var(--text-3); margin-top: 2px; max-width: 300px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.map-row .btn { min-height: 28px; padding: 4px 10px; font-size: 12px; flex-shrink: 0; }
</style>
