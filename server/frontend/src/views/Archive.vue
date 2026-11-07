<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import request from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import Icon from '@/components/Icon.vue'
import { ARCHIVE_CATALOG, FREQ_LABEL, STATUS } from '@/config/archiveCatalog'
import { useRouter } from 'vue-router'

const router = useRouter()

// ---- 同源范本库：「台账范本管理」与「电子档案柜」共用 tpl_template ----
// 范本管理中保存的修改，会直接决定这里的生成结果与预览正文。
const tplList = ref([])
const pageCss = ref('')
const currentSetName = ref('')
const previewPost = ref('')
const tplChoice = ref({})   // 行 key -> 用户为该归档项挑选的范本 code

function setTplChoice(key, code) {
  tplChoice.value = { ...tplChoice.value, [key]: code }
}

const previewPosts = computed(() => tplList.value
  .filter((t) => (t.code || '').startsWith('RESP_LETTER_P'))
  .map((t) => (t.name || '').replace(/^安全生产责任书（/, '').replace(/）$/, '')))

const tplMap = computed(() => {
  const m = {}
  tplList.value.forEach((t) => {
    const n = t.archiveItem
    if (!n) return
    ;(m[n] = m[n] || []).push(t)
  })
  // 「安全生产责任书（变量化范本）」作为盒一责任书的主范本排在最前
  Object.values(m).forEach((arr) => arr.sort((a, b) =>
    (a.code === 'RESP_LETTER' ? 0 : 1) - (b.code === 'RESP_LETTER' ? 0 : 1)))
  return m
})

function matchTpl(name) {
  if (tplMap.value[name]?.length) return tplMap.value[name]
  const k = Object.keys(tplMap.value).find((x) => x.length >= 4 && (name.includes(x) || x.includes(name)))
  return k ? tplMap.value[k] : []
}

// 盒二：由范本库动态注入 24 项制度 + 制度目录 + 责任制（与范本管理文件树一一对应）
const boxTwoTplItems = computed(() => {
  const staticBox2 = (ARCHIVE_CATALOG[cargoType.value] || [])[1]
  const seen = new Set((staticBox2?.items || []).map((i) => i.name))
  const out = []
  tplList.value.filter((t) => t.boxNo === 2).forEach((t) => {
    const n = t.archiveItem || t.name
    if (!n || seen.has(n)) return
    seen.add(n)
    out.push({ name: n, tplDriven: true })
  })
  return out
})

const cargoType = ref('普货')
const boxes = computed(() => {
  const base = ARCHIVE_CATALOG[cargoType.value] || []
  return base.map((b, idx) => idx === 1
    ? { ...b, items: [...b.items, ...boxTwoTplItems.value] }
    : b)
})
const selectedNo = ref('一')
const selectedBox = computed(() => boxes.value.find((b) => b.no === selectedNo.value) || boxes.value[0])
const boxNoNum = (no) => boxes.value.findIndex((b) => b.no === no) + 1
const selBoxNo = computed(() => boxNoNum(selectedNo.value))

const currentYear = new Date().getFullYear()
const years = [currentYear, currentYear - 1, currentYear - 2]
const selYear = ref(currentYear)

const archiveItems = ref([])  // 后端归档项：上传留存(genMode=3) 与 按范本生成(genMode=2)
const loading = ref(false)
const generating = ref(false)
const checked = ref(new Set())
const boxChecked = ref(new Set())

// 把目录子项与后端真实归档状态、当前应用范本合并成渲染行
const rows = computed(() => {
  const recMap = {}
  archiveItems.value.forEach((it) => { recMap[it.title] = it })
  const bno = selBoxNo.value
  return selectedBox.value.items.map((it, idx) => {
    const key = `${bno}-${idx}`
    const rec = recMap[it.name]
    const pdfId = rec?.pdfId || null
    const isUploaded = rec?.genMode === 3
    const tplOptions = matchTpl(it.name)
    const tplCode = tplOptions.length ? tplOptions[0].code : ''
    let status = STATUS.MISS
    if (pdfId) status = STATUS.DONE
    else if (tplOptions.length) status = STATUS.WAIT
    return {
      key, name: it.name, freq: it.freq || '', tplDriven: !!it.tplDriven,
      isUploaded, tplOptions, tplCode, status, pdfId,
    }
  })
})

// 四色统计
const summary = computed(() => {
  let miss = 0, wait = 0, done = 0, printed = 0
  rows.value.forEach((r) => {
    if (r.status === STATUS.MISS) miss++
    else if (r.status === STATUS.WAIT) wait++
    else if (r.status === STATUS.DONE) done++
    else printed++
  })
  return { miss, wait, done, printed }
})

// 到期重签横幅（一年一签类，且未在今年生成）
const renewItems = computed(() =>
  rows.value.filter((r) => r.freq === 'yearly' && r.status !== STATUS.DONE && r.status !== STATUS.PRINT)
)

async function loadArchiveItems() {
  loading.value = true
  try {
    const res = await request.get(`/archive/items?boxNo=${selBoxNo.value}&year=${selYear.value}`)
    archiveItems.value = res.data || []
  } catch (e) {
    archiveItems.value = []
  } finally {
    loading.value = false
  }
}
async function loadTplLibrary() {
  try {
    const [list, cssRes, curRes] = await Promise.all([
      request.get('/tpl/library'),
      request.get('/tpl/library/css'),
      request.get('/tpl/ent/current').catch(() => ({ data: null })),
    ])
    tplList.value = list.data || []
    pageCss.value = cssRes.data || ''
    currentSetName.value = curRes.data?.name || '普货通用范本（内置）'
  } catch (e) { /* 范本库不可用时退化为纯上传模式 */ }
}

watch([selectedNo, cargoType, selYear], () => {
  checked.value = new Set(); boxChecked.value = new Set(); tplChoice.value = {}; loadArchiveItems()
})
onMounted(() => { loadTplLibrary(); loadArchiveItems() })

// ---- 三级勾选 ----
function toggleItem(key, val) {
  if (val) checked.value.add(key)
  else checked.value.delete(key)
  checked.value = new Set(checked.value)
}
function toggleBox(val) {
  const bno = selBoxNo.value
  selectedBox.value.items.forEach((_, idx) => {
    const k = `${bno}-${idx}`
    if (val) checked.value.add(k); else checked.value.delete(k)
  })
  checked.value = new Set(checked.value)
}
function toggleBoxCheck(no, val) {
  const bno = boxNoNum(no)
  const box = boxes.value.find((b) => b.no === no)
  box.items.forEach((_, idx) => {
    const k = `${bno}-${idx}`
    if (val) checked.value.add(k); else checked.value.delete(k)
  })
  if (val) boxChecked.value.add(no); else boxChecked.value.delete(no)
  boxChecked.value = new Set(boxChecked.value)
  checked.value = new Set(checked.value)
}
const allChecked = computed(() => {
  const bno = selBoxNo.value
  return selectedBox.value.items.length > 0 &&
    selectedBox.value.items.every((_, idx) => checked.value.has(`${bno}-${idx}`))
})

// ---- 生成：一律按「台账范本管理」当前应用范本渲染 PDF ----
function currentTpl(item) {
  if (!item?.tplOptions?.length) return null
  const code = tplChoice.value[item.key] || item.tplCode
  return item.tplOptions.find((t) => t.code === code) || item.tplOptions[0]
}

async function generateOne(item, silent = false) {
  const tpl = currentTpl(item)
  if (!tpl) return false
  // 单行生成：先检查岗位人名，读不到则提醒（批量生成由 batchGenerate 统一提醒一次）
  if (!silent && !await confirmMissing(await checkMissing(tpl))) return false
  await request.post('/archive/template/generate', {
    code: tpl.code,
    itemName: item.name,
    boxNo: selBoxNo.value,
    year: selYear.value,
    post: previewPost.value,
  })
  if (!silent) ElMessage.success(`已按「${tpl.name}」生成并归档`)
  return true
}

async function doGenerate(item) {
  generating.value = true
  try {
    const ok = await generateOne(item)
    if (!ok) {
      if (!currentTpl(item)) ElMessage.info('该归档项暂无台账范本，请使用「上传」留存')
      return
    }
    await loadArchiveItems()
  } finally {
    generating.value = false
  }
}

async function batchGenerate(scope) {
  const targets = (scope === 'checked'
    ? rows.value.filter((r) => checked.value.has(r.key))
    : rows.value).filter((r) => r.tplOptions.length)
  if (!targets.length) {
    ElMessage.info('当前选择没有可按范本生成的归档项（其余请使用「上传」按钮留存）')
    return
  }
  generating.value = true
  try {
    // 批量只提醒一次：先汇总所有归档项缺失的人名字段
    const allMissing = []
    for (const r of targets) allMissing.push(...await checkMissing(currentTpl(r)))
    if (!await confirmMissing(allMissing)) return
    let n = 0
    for (const r of targets) if (await generateOne(r, true)) n++
    ElMessage.success(`已按当前应用范本生成 ${n} 份台账`)
    await loadArchiveItems()
  } finally {
    generating.value = false
  }
}

// ---- 下载 ----
async function download(item) {
  if (!item.pdfId) { ElMessage.warning('尚未生成/上传'); return }
  const res = await fetch(`/api/archive/pdf/${item.pdfId}/download`, {
    headers: {
      Authorization: `Bearer ${localStorage.getItem('token')}`,
      'X-Token': `Bearer ${localStorage.getItem('token')}`,
    },
  })
  if (!res.ok) { ElMessage.error('下载失败'); return }
  const blob = await res.blob()
  // 取后端返回的真实文件名（含正确扩展名：pdf / png / jpg ...），避免图片被错存为 .pdf
  // 优先取 RFC 5987 filename*（中文原名），否则取 ASCII filename 兜底
  const cd = res.headers.get('Content-Disposition') || ''
  let m = cd.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)
  if (!m) m = cd.match(/filename\s*=\s*"?([^";]+)"?/i)
  let fname = m ? decodeURIComponent(m[1].trim()) : `${item.name}_${selYear.value}.pdf`
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fname
  a.click()
  URL.revokeObjectURL(url)
}
async function downloadBox() {
  const done = rows.value.filter((r) => r.pdfId)
  if (!done.length) { ElMessage.warning('本盒暂无已生成/已上传文件'); return }
  for (const r of done) await download(r)
}

function selectBox(no) { selectedNo.value = no }

// ---- 上传留存 ----
const showUpload = ref(false)
const uploadTarget = ref(null)
const uploadFile = ref(null)
const uploading = ref(false)
const fileInput = ref(null)

function openUpload(r) {
  uploadTarget.value = { title: r.name, boxNo: selBoxNo.value }
  uploadFile.value = null
  showUpload.value = true
}
function closeUpload() { showUpload.value = false; uploadTarget.value = null; uploadFile.value = null }
function pickFile() { fileInput.value && fileInput.value.click() }
function onFile(e) {
  const f = e.target.files && e.target.files[0]
  if (f) uploadFile.value = f
}
async function doUpload() {
  if (!uploadFile.value) { ElMessage.warning('请先选择文件'); return }
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', uploadFile.value)
    fd.append('boxNo', String(uploadTarget.value.boxNo))
    fd.append('itemTitle', uploadTarget.value.title)
    fd.append('year', String(selYear.value))
    const res = await fetch('/api/archive/upload', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${localStorage.getItem('token')}`,
        'X-Token': `Bearer ${localStorage.getItem('token')}`,
      },
      body: fd,
    })
    const json = await res.json()
    if (json.code !== 0) throw new Error(json.msg || '上传失败')
    ElMessage.success('上传成功，已留存至档案盒')
    await loadArchiveItems()
    closeUpload()
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

// ---- PDF 预览 ----
// 上传留存类 → 嵌入真实文件；按范本生成类 → 渲染【成品】（企业信息 + 岗位人名 + 公司章）
const preview = ref(null)
const previewBlobUrl = ref('')
const previewFileKind = ref('') // '' | 'pdf' | 'image'
const previewDoc = ref('')
const missingPersons = ref([])  // 当前预览范本读不到的人名字段

// ---- 岗位人名补录（读取不到时提醒，补录后成品自动带上真实姓名）----
const showRoster = ref(false)
const rosterList = ref([])
const assignPost = ref('')
const assignName = ref('')
const assigning = ref(false)

const missingLabels = computed(() =>
  [...new Set(missingPersons.value.map((m) => m.label))].join('、'))

async function checkMissing(tpl) {
  if (!tpl) return []
  try {
    const res = await request.get('/archive/template/check', {
      params: { code: tpl.code, post: previewPost.value },
    })
    return res.data || []
  } catch (e) { return [] }
}

/** 生成前提醒：读不到岗位人名时不静默留白，给用户「继续留空」或「去补录」两个选择 */
async function confirmMissing(list) {
  if (!list.length) return true
  const labels = [...new Set(list.map((m) => m.label))]
  try {
    await ElMessageBox.confirm(
      `以下岗位尚未维护人员，生成后台账中的姓名栏将留空：\n\n${labels.join('、')}\n\n建议先补录人名；也可以继续生成空白栏用于手写。`,
      '未读取到岗位人名',
      {
        confirmButtonText: '继续生成（姓名留空）',
        cancelButtonText: '去补录人名',
        type: 'warning',
        distinguishCancelAndClose: true,
        customStyle: { whiteSpace: 'pre-line' },
      },
    )
    return true
  } catch (action) {
    if (action === 'cancel') openRoster()
    return false
  }
}

async function loadRoster() {
  try { rosterList.value = (await request.get('/archive/template/roster')).data || [] }
  catch (e) { rosterList.value = [] }
  if (!assignPost.value && rosterList.value.length) assignPost.value = rosterList.value[0].post
}

async function openRoster() {
  showRoster.value = true
  await loadRoster()
}

async function doAssign() {
  if (!assignName.value.trim()) { ElMessage.warning('请填写人员姓名'); return }
  assigning.value = true
  try {
    const msg = (await request.post('/archive/template/assign', {
      post: assignPost.value, name: assignName.value.trim(),
    })).data
    ElMessage.success(`已补录：${msg}`)
    assignName.value = ''
    await loadRoster()
    if (preview.value) await openPreview(preview.value)
  } catch (e) { ElMessage.error('补录失败，请重试') }
  finally { assigning.value = false }
}

function srcdoc(body) {
  const screenFont = "body{font-family:'PingFang SC','Microsoft YaHei','Noto Sans CJK SC',sans-serif !important;padding:44px 50px;margin:0;}"
  return `<!DOCTYPE html><html lang="zh"><head><meta charset="UTF-8"><style>${pageCss.value}${screenFont}</style></head><body>${body}</body></html>`
}

function clearPreviewBlob() {
  if (previewBlobUrl.value) { URL.revokeObjectURL(previewBlobUrl.value); previewBlobUrl.value = '' }
  previewFileKind.value = ''
}

async function openPreview(item) {
  if (!item) return
  preview.value = item
  clearPreviewBlob()
  previewDoc.value = ''
  missingPersons.value = []

  // 上传留存的真实文件（PDF / 图片扫描件）：直接嵌入原文件，无需人名/盖章
  if (item.isUploaded && item.pdfId) {
    fetch(`/api/archive/pdf/${item.pdfId}/download`, {
      headers: {
        Authorization: `Bearer ${localStorage.getItem('token')}`,
        'X-Token': `Bearer ${localStorage.getItem('token')}`,
      },
    }).then(async (res) => {
      if (!res.ok) throw new Error('fetch failed')
      const blob = await res.blob()
      clearPreviewBlob()
      previewBlobUrl.value = URL.createObjectURL(blob)
      previewFileKind.value = blob.type.startsWith('image/') ? 'image' : 'pdf'
    }).catch(() => {
      previewDoc.value = srcdoc('<div class="doc-p">原文件读取失败，请尝试下载查看。</div>')
    })
    return
  }

  // 按当前应用范本渲染【成品】：真实企业信息 + 岗位人名 + 公司章
  const tpl = currentTpl(item)
  if (!tpl) {
    previewDoc.value = srcdoc(
      '<div class="doc-h0">暂无台账范本</div><div class="doc-p">该归档项尚未配置台账范本，请使用「上传」留存佐证材料，或到台账范本管理中新增。</div>')
    return
  }
  previewDoc.value = srcdoc('<div class="doc-p">正在按当前应用范本生成成品预览…</div>')
  // rows 是 computed，对象引用会被重算替换，用稳定的 key 判断是否为当前预览项
  const key = item.key
  checkMissing(tpl).then((list) => { if (preview.value?.key === key) missingPersons.value = list })
  try {
    const res = await request.get(`/tpl/library/render/${encodeURIComponent(tpl.code)}`, {
      params: { mode: 'final', itemName: item.name, year: selYear.value, post: previewPost.value },
    })
    if (preview.value?.key !== key) return  // 快速切换时丢弃过期响应
    previewDoc.value = srcdoc(res.data || '')
  } catch (e) {
    if (preview.value?.key === key) previewDoc.value = srcdoc('<div class="doc-p">范本渲染失败，请稍后重试。</div>')
  }
}

async function generateFromPreview() {
  const item = preview.value
  if (!item) return
  const ok = await generateOne(item)
  if (ok) { await loadArchiveItems(); await openPreview(item) }
}

function closePreview() {
  preview.value = null
  previewDoc.value = ''
  clearPreviewBlob()
}

function goTplManager() { router.push('/template-manager') }
function refreshTpl() { loadTplLibrary(); loadArchiveItems() }
</script>

<template>
  <div>
    <div class="pc-header">
      <div>
        <h1>电子档案柜</h1>
        <div class="desc">档案盒目录对齐政府检查 · 企业/服务商均可一键下载整盒台账 PDF</div>
      </div>
      <div class="cargo-switch">
        <span class="lbl">营运类型</span>
        <el-radio-group v-model="cargoType" size="small">
          <el-radio-button value="普货">普货</el-radio-button>
          <el-radio-button value="危货">危货</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <div class="hint">
      <b>电子档案柜</b>：把政府检查的档案盒做成「电子档案柜」，每个业务模块的数据最终落到某个档案盒并生成<b>可打印 PDF</b>，供打印装盒、接受检查。
      <b>企业</b>与<b>服务商</b>均可选盒一键下载整套台账；服务商额外拥有「代录台账」能力。
      <b>责任书/承诺书一年一签</b>，跨年度自动预警重签。　<b>档案按自然年度归档</b>，可切换上方「归档年度」查看各年度状态。
      可<b>勾选左侧档案盒或逐条勾选</b>后批量处理；证照/保单/台账扫描件等请使用<b>「上传」</b>按钮留存。
    </div>

    <!-- 同源联动条：明确告知当前台账由哪套范本生成 -->
    <div class="tpl-linkbar">
      <div class="lb-left">
        <Icon name="bookOpen" :size="19" />
        <div>
          <div class="lb-t">当前应用范本：<b>{{ currentSetName }}</b> · 已加载 {{ tplList.length }} 份台账范本</div>
          <div class="lb-d">本柜预览/生成的是带<b>岗位人名与公司章</b>的成品；台账范本管理里是空白范本。修改并保存范本后点右侧刷新即可生效</div>
        </div>
      </div>
      <div class="lb-ops">
        <el-button size="small" @click="refreshTpl"><Icon name="refresh" :size="13" /> 刷新范本</el-button>
        <el-button size="small" type="primary" plain @click="goTplManager"><Icon name="edit" :size="13" /> 去台账范本管理</el-button>
      </div>
    </div>

    <div class="year-bar">
      <span class="year-bar-label">归档年度</span>
      <div class="year-switch">
        <button v-for="y in years" :key="y" class="year-btn" :class="{ on: selYear === y }" @click="selYear = y">
          {{ y }}<span v-if="y === currentYear" class="yb-cur">今</span>
        </button>
      </div>
      <span class="year-hint">当前展示 {{ selYear }} 年度归档状态</span>
    </div>

    <div class="summ">
      <div class="sc miss"><div class="v">{{ summary.miss }}</div><div class="k">待上传/缺失</div></div>
      <div class="sc wait"><div class="v">{{ summary.wait }}</div><div class="k">待生成</div></div>
      <div class="sc done"><div class="v">{{ summary.done }}</div><div class="k">已生成/已上传</div></div>
      <div class="sc print"><div class="v">{{ summary.printed }}</div><div class="k">已打印归档</div></div>
    </div>

    <div v-if="renewItems.length" class="renew-banner">
      <span class="ico"><Icon name="alertTriangle" :size="20" /></span>
      <div class="rb-body">
        <div class="rb-title">待重新签订 / 到期更新（{{ renewItems.length }} 项）</div>
        <div class="rb-list">
          <span v-for="r in renewItems" :key="r.key" class="rb-item">{{ r.name }}</span>
        </div>
      </div>
    </div>

    <div class="shell">
      <!-- 左侧档案盒目录树（含盒级勾选）-->
      <div class="left">
        <div class="tree">
          <div class="tt">档案盒目录 <span class="cargo">{{ cargoType }} · {{ boxes.length }} 盒</span></div>
          <div
            v-for="b in boxes"
            :key="b.no"
            class="box-item"
            :class="{ on: b.no === selectedNo }"
          >
            <el-checkbox
              class="box-cb"
              :model-value="boxChecked.has(b.no)"
              @change="(v) => toggleBoxCheck(b.no, v)"
              @click.stop
            />
            <span class="bx-dot dot-green" />
            <span class="bx-name" @click="selectBox(b.no)">盒{{ b.no }} {{ b.name }}</span>
            <span class="bx-count">{{ b.items.length }} 项</span>
          </div>
        </div>
      </div>

      <!-- 右侧归档项 -->
      <div class="right">
        <div class="box-head">
          <div class="box-head-l">
            <h2>盒{{ selectedBox.no }} · {{ selectedBox.name }}</h2>
            <div class="desc">归档项按自然年度隔离；生成后存 MinIO，可下载 PDF 打印装盒。</div>
          </div>
          <div class="head-actions">
            <el-button :loading="generating" @click="batchGenerate('all')">
              <Icon name="zap" :size="15" /> 批量生成全部
            </el-button>
            <el-button @click="downloadBox"><Icon name="download" :size="15" /> 一键下载整盒</el-button>
          </div>
        </div>

        <div class="list-toolbar">
          <el-checkbox class="selall-cb" :model-value="allChecked" @change="toggleBox">全选本盒</el-checkbox>
          <el-button size="small" type="primary" :loading="generating" @click="batchGenerate('checked')">
            <Icon name="fileText" :size="14" /> 批量生成（{{ checked.size }}）
          </el-button>
        </div>

        <div v-loading="loading" class="item-list">
          <div v-for="(r, idx) in rows" :key="r.key" class="item-row">
            <span class="item-idx">{{ idx + 1 }}</span>
            <el-checkbox class="item-cb" :model-value="checked.has(r.key)" @change="(v) => toggleItem(r.key, v)" />
            <div class="nm">
              <span class="n clickable" @click="openPreview(r)">{{ r.name }}</span>
              <div class="d">
                <span v-if="r.tplOptions.length" class="src src-auto">按范本生成</span>
                <span v-else class="src src-up">上传留存</span>
                <span v-if="r.tplDriven" class="src src-tpl">范本目录项</span>
                <span v-if="r.freq" class="freq" :class="`freq-${r.freq}`">{{ FREQ_LABEL[r.freq] }}</span>
                <span v-if="r.tplOptions.length" class="tpl-name" :title="currentTpl(r)?.name">
                  当前范本：{{ currentTpl(r)?.name || '—' }}
                </span>
              </div>
              <select
                v-if="r.tplOptions.length > 1"
                class="tpl-sel"
                :value="tplChoice[r.key] || r.tplCode"
                @click.stop
                @change="setTplChoice(r.key, $event.target.value)"
              >
                <option v-for="t in r.tplOptions" :key="t.code" :value="t.code">{{ t.name }}</option>
              </select>
            </div>
            <span class="st" :class="{
              'st-miss': r.status === STATUS.MISS,
              'st-wait': r.status === STATUS.WAIT,
              'st-done': r.status === STATUS.DONE,
              'st-print': r.status === STATUS.PRINT,
            }">{{ ['待上传/缺失', '待生成', '已生成/已上传', '已归档'][r.status] }}</span>
            <div class="ops">
              <el-button v-if="r.tplOptions.length" size="small" @click="doGenerate(r)">
                <Icon name="plus" :size="13" /> {{ r.pdfId ? '重新生成' : '生成' }}
              </el-button>
              <el-button v-if="r.pdfId" size="small" type="primary" @click="download(r)">
                <Icon name="download" :size="13" /> 下载
              </el-button>
              <el-button size="small" plain @click="openUpload(r)">
                <Icon name="upload" :size="13" /> 上传
              </el-button>
            </div>
          </div>
          <div v-if="!rows.length" class="empty-state"><div class="big">📂</div>该盒暂无归档项</div>
        </div>
      </div>
    </div>

    <!-- 上传留存弹窗 -->
    <div class="pdf-mask" :class="{ show: showUpload }" @click.self="closeUpload">
      <div class="pdf-wrap up-wrap">
        <div class="pdf-toolbar">
          <span class="t">上传留存「{{ uploadTarget?.title }}」</span>
          <button class="x" @click="closeUpload">×</button>
        </div>
        <div class="up-body">
          <div class="upload-drop" @click="pickFile">
            <span class="ud-ico"><Icon name="upload" :size="26" /></span>
            <div>
              <b>点击选择文件上传</b>
              <div class="ud-tip">支持 PDF / JPG / PNG / 扫描件，上传后留存至本档案盒，状态标记为已生成</div>
            </div>
          </div>
          <input ref="fileInput" type="file" accept=".pdf,.jpg,.jpeg,.png" style="display:none" @change="onFile" />
          <div v-if="uploadFile" class="up-file">
            <Icon name="fileText" :size="14" /> 已选：{{ uploadFile.name }}
            <span class="up-size">（{{ (uploadFile.size / 1024).toFixed(0) }} KB）</span>
          </div>
        </div>
        <div class="pdf-toolbar up-foot">
          <el-button @click="closeUpload">取消</el-button>
          <el-button type="primary" :loading="uploading" @click="doUpload">
            <Icon name="upload" :size="13" /> 确认上传
          </el-button>
        </div>
      </div>
    </div>

    <!-- PDF 预览：上传留存嵌入原文件；按范本生成类渲染成品（岗位人名 + 公司章） -->
    <div class="pdf-mask" :class="{ show: preview }" @click.self="closePreview">
      <div class="pdf-wrap">
        <div class="pdf-toolbar">
          <span class="t">{{ preview?.name }} · {{ selYear }} 年度 · 成品预览</span>
          <span v-if="currentTpl(preview)?.name" class="tb-tpl">
            当前范本：{{ currentTpl(preview).name }}（{{ currentTpl(preview).versionLabel || '内置' }}）
          </span>
          <select v-model="previewPost" class="tb-sel" @change="openPreview(preview)">
            <option value="">默认岗位</option>
            <option v-for="p in previewPosts" :key="p" :value="p">{{ p }}</option>
          </select>
          <el-button v-if="currentTpl(preview) && !preview?.isUploaded" size="small" @click="generateFromPreview">
            <Icon name="refresh" :size="13" /> 生成 / 重新生成
          </el-button>
          <el-button v-if="preview?.pdfId" size="small" type="primary" @click="download(preview)">
            <Icon name="download" :size="13" /> 下载 PDF
          </el-button>
          <button class="x" @click="closePreview">×</button>
        </div>
        <div v-if="missingPersons.length" class="person-warn">
          <Icon name="info" :size="16" />
          <span><b>未读取到岗位人名</b>：{{ missingLabels }}。生成后台账中这些姓名栏将留空，建议先补录人员。</span>
          <el-button size="small" type="warning" plain @click="openRoster">补录人名</el-button>
        </div>
        <div class="pdf-body">
          <iframe v-if="previewBlobUrl && previewFileKind === 'pdf'" :src="previewBlobUrl" class="pdf-embed" />
          <img v-else-if="previewBlobUrl && previewFileKind === 'image'" :src="previewBlobUrl" class="pdf-embed-img" alt="扫描件" />
          <div v-else class="pdf-page-frame">
            <iframe :srcdoc="previewDoc" class="pdf-doc-frame" />
          </div>
        </div>
      </div>
    </div>

    <!-- 岗位人名补录：提醒可闭环，补录后成品自动带出真实姓名 -->
    <div class="pdf-mask roster-mask" :class="{ show: showRoster }" @click.self="showRoster = false">
      <div class="roster-wrap">
        <div class="roster-head">
          补录岗位人名
          <span class="rh-sub">写入人员-岗位分配，之后生成台账自动带出</span>
          <span class="x" @click="showRoster = false">×</span>
        </div>
        <div class="roster-body">
          <div class="roster-form">
            <label>岗位
              <select v-model="assignPost" class="tb-sel">
                <option v-for="r in rosterList" :key="r.post" :value="r.post">{{ r.post }}</option>
              </select>
            </label>
            <label>姓名
              <input v-model="assignName" class="form-input" placeholder="输入要绑定的人员姓名" @keyup.enter="doAssign" />
            </label>
            <el-button type="primary" size="small" :loading="assigning" @click="doAssign">保存分配</el-button>
          </div>
          <div class="roster-list">
            <div v-for="r in rosterList" :key="r.post" class="roster-row">
              <span class="rp">{{ r.post }}</span>
              <span :class="['rn', { empty: !r.names }]">{{ r.names || '未分配人员' }}</span>
            </div>
          </div>
          <p class="roster-hint">补录后当前成品预览立即刷新；空白范本管理页不会出现这些人名。</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 岗位人名缺失提醒 + 补录弹窗 */
.person-warn { display: flex; align-items: center; gap: 10px; padding: 9px 16px;
  background: #FFFBEB; border-bottom: 1px solid #FDE68A; color: #92400E;
  font-size: 12.5px; line-height: 1.6; }
.person-warn > svg { flex: none; }
.person-warn > span { flex: 1; }
.roster-mask { z-index: 210; }
.roster-wrap { width: 560px; max-width: 94vw; max-height: 80vh; display: flex; flex-direction: column;
  background: #fff; border-radius: 10px; overflow: hidden; box-shadow: 0 8px 40px rgba(0,0,0,.3); }
.roster-head { display: flex; align-items: center; gap: 10px; padding: 14px 18px;
  border-bottom: 1px solid var(--border, #E2E8F0); font-size: 15px; font-weight: 700; }
.rh-sub { font-size: 12px; font-weight: 400; color: var(--text-3, #94A3B8); }
.roster-head .x { margin-left: auto; cursor: pointer; font-size: 20px; color: #94A3B8; }
.roster-body { padding: 18px; overflow: auto; }
.roster-form { display: flex; align-items: flex-end; gap: 10px; margin-bottom: 16px; }
.roster-form label { display: flex; flex-direction: column; gap: 5px; font-size: 12px; color: var(--text-2); }
.roster-form .tb-sel, .roster-form .form-input { width: 190px; }
.roster-list { border: 1px solid var(--border, #E2E8F0); border-radius: 8px; overflow: hidden; }
.roster-row { display: flex; align-items: center; padding: 8px 12px; font-size: 13px; border-bottom: 1px solid #F1F5F9; }
.roster-row:last-child { border-bottom: none; }
.rp { width: 160px; font-weight: 600; }
.rn { color: #15803D; }
.rn.empty { color: #B45309; }
.roster-hint { margin: 12px 0 0; font-size: 12px; color: var(--text-3, #94A3B8); line-height: 1.7; }

.cargo-switch { display: flex; align-items: center; gap: 10px; }
.cargo-switch .lbl { font-size: 13px; font-weight: 700; color: var(--text-2); }
.hint { font-size: 12.5px; color: var(--text-3); background: var(--brand-50); border: 1px solid var(--brand-100); border-radius: 8px; padding: 10px 14px; margin-bottom: 16px; line-height: 1.8; }

.year-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.year-bar-label { font-size: 13px; font-weight: 700; color: var(--text-2); }
.year-switch { display: flex; background: #F1F5F9; border-radius: 10px; padding: 3px; gap: 2px; }
.year-btn { border: none; background: transparent; padding: 7px 15px; font-size: 13px; font-weight: 600; color: var(--text-3); border-radius: 8px; cursor: pointer; transition: .12s; }
.year-btn.on { background: #fff; color: var(--brand-600); box-shadow: 0 1px 4px rgba(0,0,0,.12); }
.yb-cur { font-size: 9px; font-weight: 700; background: var(--brand-600); color: #fff; border-radius: 6px; padding: 0 4px; margin-left: 5px; }

.summ { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
.summ .sc { background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-lg); padding: 14px 16px; }
.summ .sc .v { font-size: 24px; font-weight: 800; }
.summ .sc .k { font-size: 12px; color: var(--text-3); margin-top: 2px; }
.summ .sc.miss .v { color: var(--danger); }
.summ .sc.wait .v { color: #e8a200; }
.summ .sc.done .v { color: #15803D; }
.summ .sc.print .v { color: var(--brand-500); }

.renew-banner { display: flex; gap: 12px; background: var(--danger-bg); border: 1px solid #fecaca; border-radius: var(--r-lg); padding: 14px 16px; margin-bottom: 16px; align-items: flex-start; }
.renew-banner .ico { color: var(--danger); margin-top: 2px; }
.renew-banner .rb-title { font-size: 14px; font-weight: 700; color: #B91C1C; margin-bottom: 6px; }
.renew-banner .rb-list { display: flex; flex-wrap: wrap; gap: 8px; }
.renew-banner .rb-item { background: #fff; border: 1px solid #fecaca; border-radius: 8px; padding: 4px 10px; font-size: 12px; color: var(--text-2); }

.shell { display: flex; gap: 16px; align-items: flex-start; }
.left { width: 290px; flex-shrink: 0; position: sticky; top: 12px; }
.right { flex: 1; min-width: 0; }
.tree { background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-lg); box-shadow: var(--shadow-sm); overflow: hidden; }
.tree .tt { font-size: 15px; font-weight: 700; padding: 14px 16px; border-bottom: 1px solid var(--border); display: flex; align-items: center; justify-content: space-between; }
.tree .tt .cargo { font-size: 12px; font-weight: 500; color: var(--brand-500); }
.box-item { padding: 10px 12px; cursor: pointer; border-left: 3px solid transparent; display: flex; align-items: center; gap: 8px; transition: .12s; }
.box-item:hover { background: var(--brand-50); }
.box-item.on { background: var(--brand-50); border-left-color: var(--brand-500); }
.box-item .box-cb { flex-shrink: 0; }
.box-item .bx-name { font-size: 13.5px; font-weight: 600; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.box-item .bx-count { font-size: 12px; color: var(--text-3); }
.box-item .bx-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }

.box-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.box-head h2 { font-size: 18px; margin: 0 0 2px; }
.box-head .desc { font-size: 12px; color: var(--text-3); }
.head-actions { display: flex; gap: 8px; flex-shrink: 0; }

.list-toolbar { display: flex; align-items: center; gap: 14px; padding: 10px 4px; border-bottom: 1px solid var(--border); margin-bottom: 8px; }
.selall-cb { font-size: 13px; font-weight: 600; }

.item-list { background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-lg); overflow: hidden; }
.item-row { display: flex; align-items: center; gap: 12px; padding: 13px 16px; border-bottom: 1px solid var(--border); }
.item-row:last-child { border-bottom: none; }
.item-row .item-idx { width: 22px; height: 22px; flex-shrink: 0; border-radius: 6px; background: var(--brand-500); color: #fff; font-size: 12px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.item-row .item-cb { flex-shrink: 0; }
.item-row .nm { flex: 1; min-width: 0; }
.item-row .nm .n { font-size: 14.5px; font-weight: 600; cursor: pointer; }
.item-row .nm .n.clickable:hover { color: var(--brand-600); text-decoration: underline; }
.item-row .nm .d { margin-top: 4px; display: flex; gap: 6px; align-items: center; }
.src { font-size: 11px; padding: 2px 8px; border-radius: 8px; white-space: nowrap; }
.src-auto { background: var(--brand-100); color: var(--brand-600); }
.src-up { background: #EEF2F7; color: var(--text-2); }
.freq { font-size: 11px; padding: 2px 8px; border-radius: 8px; background: #EEF2F7; color: var(--text-2); }
.freq-yearly { background: #F0FDF4; color: #15803D; }
.freq-once { background: #FFFBEB; color: #B45309; }
.freq-monthly { background: var(--brand-100); color: var(--brand-600); }
.freq-daily { background: var(--brand-100); color: var(--brand-600); }
.freq-warn { background: var(--danger-bg); color: #B91C1C; font-weight: 700; }
.st { font-size: 12px; padding: 2px 10px; border-radius: 10px; font-weight: 600; white-space: nowrap; }
.st-miss { background: var(--danger-bg); color: #B91C1C; }
.st-wait { background: #fff4e0; color: #b26a00; }
.st-done { background: #F0FDF4; color: #1a8a4a; }
.st-print { background: var(--brand-100); color: var(--brand-600); }
.ops { flex-shrink: 0; }
.empty-state { text-align: center; padding: 50px; color: var(--text-3); }
.empty-state .big { font-size: 40px; margin-bottom: 12px; }

/* 上传弹窗 */
.up-wrap { width: 560px; max-width: 95vw; }
.up-body { padding: 20px; }
.upload-drop { display: flex; align-items: center; gap: 14px; cursor: pointer; border: 2px dashed var(--brand-200); border-radius: 12px; padding: 24px; background: var(--brand-50); color: var(--brand-700); transition: .12s; }
.upload-drop:hover { border-color: var(--brand-500); background: var(--brand-100); }
.ud-ico { color: var(--brand-600); flex-shrink: 0; }
.ud-tip { font-size: 12.5px; color: var(--text-3); margin-top: 4px; }
.up-file { margin-top: 14px; display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-2); background: #F8FAFC; border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; }
.up-size { color: var(--text-3); }
.up-foot { justify-content: flex-end; }

.pdf-mask { position: fixed; inset: 0; background: rgba(20,28,43,.5); display: none; align-items: center; justify-content: center; z-index: 200; }
.pdf-mask.show { display: flex; }
.pdf-wrap { background: #fff; width: 820px; max-width: 96vw; max-height: 92vh; display: flex; flex-direction: column; border-radius: 6px; overflow: hidden; box-shadow: 0 8px 40px rgba(0,0,0,.3); }
.pdf-toolbar { display: flex; align-items: center; gap: 10px; padding: 12px 16px; background: #F1F5F9; border-bottom: 1px solid var(--border); }
.pdf-toolbar .t { font-weight: 700; font-size: 14px; flex: 1; }
.pdf-toolbar .x { border: none; background: none; font-size: 22px; color: var(--text-3); cursor: pointer; }
.pdf-body { flex: 1; overflow: auto; background: #e9ecf1; padding: 24px; display: flex; justify-content: center; }
.pdf-page { background: #fff; width: 700px; min-height: 900px; padding: 50px 56px; box-shadow: 0 2px 12px rgba(0,0,0,.15); font-family: "SimSun","宋体",serif; color: #000; font-size: 14px; line-height: 1.8; }
.pdf-page .ph { text-align: center; font-size: 13px; color: #333; border-bottom: 2px solid #000; padding-bottom: 8px; margin-bottom: 6px; }
.pdf-page .ph .org { font-size: 17px; font-weight: bold; letter-spacing: 1px; }
.pdf-page .ph .meta { font-size: 11px; color: #555; }
.pdf-page .ptitle { text-align: center; font-size: 20px; font-weight: bold; letter-spacing: 3px; margin: 18px 0 14px; }
.pdf-embed { width: 700px; height: 900px; border: none; background: #fff; box-shadow: 0 2px 12px rgba(0,0,0,.15); }
.pdf-embed-img { max-width: 700px; max-height: 880px; background: #fff; box-shadow: 0 2px 12px rgba(0,0,0,.15); display: block; }

.sec-h1 { font-size: 19px; font-weight: bold; text-align: center; letter-spacing: 2px; margin: 4px 0 16px; }
.sec-h2 { font-size: 15px; font-weight: bold; margin: 16px 0 6px; }
.sec-p { font-size: 14px; text-indent: 2em; margin: 6px 0; text-align: justify; }
.sec-ol, .sec-ul { font-size: 13.5px; padding-left: 2.4em; margin: 6px 0; line-height: 1.9; }
.sec-ol li, .sec-ul li { margin: 3px 0; text-align: justify; }
.sec-tbl { width: 100%; border-collapse: collapse; font-size: 12.5px; margin: 10px 0; }
.sec-tbl th, .sec-tbl td { border: 1px solid #999; padding: 6px 8px; text-align: left; vertical-align: top; }
.sec-tbl th { background: #f2f2f2; font-weight: bold; }
.sec-sign { margin-top: 28px; font-size: 14px; }
.sec-sign .sg-org { font-weight: bold; margin-bottom: 10px; }
.sec-sign .sg-row { display: flex; gap: 10px; align-items: baseline; margin: 8px 0; flex-wrap: wrap; }
.sec-sign .sg-role { white-space: nowrap; }
.sec-sign .sg-name { min-width: 120px; border-bottom: 1px solid #000; }
.sec-sign .sg-date { color: #333; }

.sec-cert { text-align: center; }
.cert-kind { font-size: 16px; font-weight: bold; margin: 14px 0 12px; }
.cert-frame { border: 2px dashed #cbd5e1; border-radius: 8px; height: 420px; display: flex; align-items: center; justify-content: center; color: #94a3b8; font-size: 14px; background: #fafbfc; }
.cert-tip { letter-spacing: 2px; }
.cert-fields { width: 100%; border-collapse: collapse; font-size: 13.5px; margin-top: 18px; }
.cert-fields td { border: 1px solid #bbb; padding: 8px 10px; }
.cert-fields .cf-k { width: 150px; background: #f6f8fa; font-weight: 600; text-align: right; padding-right: 12px; }
.cert-fields .cf-v { text-align: left; }

/* 台账范本同源联动 */
.tpl-linkbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap;
  background: var(--brand-50); border: 1px solid var(--brand-200, #BFDBFE); border-radius: var(--r-lg, 12px);
  padding: 12px 16px; margin-bottom: 16px; }
.lb-left { display: flex; align-items: center; gap: 11px; color: var(--brand-700); }
.lb-t { font-size: 14px; font-weight: 700; }
.lb-d { font-size: 12px; color: var(--text-3); margin-top: 3px; }
.lb-ops { display: flex; gap: 8px; flex-shrink: 0; }
.src-tpl { background: #F0FDF4; color: #15803D; }
.tpl-name { font-size: 11.5px; color: var(--brand-600); background: var(--brand-50); padding: 2px 8px;
  border-radius: 8px; max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tpl-sel { margin-top: 6px; max-width: 300px; font-size: 12px; border: 1px solid var(--border);
  border-radius: 6px; padding: 3px 6px; background: #fff; color: var(--text-2); cursor: pointer; }
.pdf-toolbar .tb-tpl { flex-shrink: 0; font-size: 12px; color: var(--brand-700); background: var(--brand-50);
  padding: 3px 9px; border-radius: 8px; max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pdf-toolbar .tb-sel { flex-shrink: 0; font-size: 12px; border: 1px solid var(--border); border-radius: 6px;
  padding: 4px 6px; background: #fff; cursor: pointer; }
.pdf-page-frame { width: 700px; min-height: 900px; background: #fff; box-shadow: 0 2px 12px rgba(0,0,0,.15); }
.pdf-doc-frame { width: 100%; min-height: 900px; border: none; display: block; background: #fff; }
</style>
