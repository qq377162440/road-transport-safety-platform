<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import request from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import Icon from '@/components/Icon.vue'
import { ARCHIVE_CATALOG, FREQ_LABEL, STATUS } from '@/config/archiveCatalog'
import { classifyItem, CATEGORY_LIST } from '@/config/archiveCategory'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const auth = useAuthStore()
// 范本定制与生成为服务商 / 超管增值服务；企业账号只读（可查看/下载成品、手动上传证件）
const canGenerateTpl = computed(() => auth.canGenerateTpl)
const router = useRouter()
const route = useRoute()
const locatedItem = ref('')

// ---- 同源范本库：「台账范本管理」与「电子档案柜」共用 tpl_template ----
// 范本管理中保存的修改，会直接决定这里的生成结果与预览正文。
const tplList = ref([])
const pageCss = ref('')
const currentSetName = ref('')
const previewPost = ref('')
const tplChoice = ref({})   // 行 key -> 用户为该归档项挑选的范本 code
const mappingData = ref(null) // 台账范本管理「档案柜对应」同源映射（catalog_id）

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

// 后端 catalog_id 精确映射：归档项名 -> 该归档项下的范本。
// 与台账范本管理「档案柜对应」面板同一份数据，保证「每一个范本都有档案柜落点」。
const catalogTplMap = computed(() => {
  const byCode = {}
  tplList.value.forEach((t) => { byCode[t.code] = t })
  const m = {}
  ;(mappingData.value?.catalogItems || []).forEach((c) => {
    const list = (c.tplCodes || []).map((code) => byCode[code]).filter(Boolean)
    if (!list.length) return
    // 「安全生产责任书（变量化范本）」作为主范本排最前
    list.sort((a, b) => (a.code === 'RESP_LETTER' ? 0 : 1) - (b.code === 'RESP_LETTER' ? 0 : 1))
    m[c.name] = list
  })
  return m
})

function findCatalogKey(name) {
  const keys = Object.keys(catalogTplMap.value)
  // 前端展示名常带说明文字（如「安全生产月度会议（会议记录…）」），后端用简名
  return keys.find((k) => k === name)
    || keys.find((k) => name.includes(k))
    || keys.find((k) => k.includes(name))
    || null
}

function matchTpl(name) {
  // 优先按后端 catalog_id 精确取范本（与「档案柜对应」面板同源）
  const ck = findCatalogKey(name)
  if (ck) return catalogTplMap.value[ck]
  // 兜底：既有 archiveItem 名称匹配（无映射数据或平台账号时仍可用）
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

// 盒十一「从业人员一人一档 + 一车一档」走专属浏览面板
const isCrewBox = computed(() => selectedBox.value?.special === 'crew')

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
    // 台账范本管理「定位档案柜」跳转时，高亮对应归档项（含带说明文字的展示名）
    const q = locatedItem.value
    const located = !!q && (it.name === q || it.name.includes(q) || q.includes(it.name))
    return {
      key, seq: idx + 1, name: it.name, freq: it.freq || '', tplDriven: !!it.tplDriven,
      isUploaded, tplOptions, tplCode, status, pdfId, located,
      ...classifyItem(it.name),
    }
  })
})

// ---- 文件分类（08 三大类 + ②′ 上传留存）----
// 类别只决定「怎么生产」；是否按岗位是独立维度，只有责任书/承诺书/告知书/任命书/
// 风险告知卡等按岗位拆分的文件才允许切换岗位，证件类与动态记录一律不带岗位参数。
const categoryFilter = ref('all')
const categoryCounts = computed(() => {
  const c = { all: rows.value.length }
  CATEGORY_LIST.forEach((x) => { c[x.key] = rows.value.filter((r) => r.categoryKey === x.key).length })
  return c
})
const visibleRows = computed(() => categoryFilter.value === 'all'
  ? rows.value
  : rows.value.filter((r) => r.categoryKey === categoryFilter.value))
const postFor = (item) => (item?.postRelated ? previewPost.value : '')

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
    const [list, cssRes, ovRes, mapRes] = await Promise.all([
      request.get('/tpl/library'),
      request.get('/tpl/library/css'),
      request.get('/tpl/ent/overview').catch(() => ({ data: null })),
      request.get('/tpl/library/mapping').catch(() => ({ data: null })),
    ])
    tplList.value = list.data || []
    pageCss.value = cssRes.data || ''
    const ov = ovRes.data || {}
    providerSets.value = ov.providers || []
    mySets.value = ov.mine || []
    currentSetMeta.value = ov.current || null
    currentSetName.value = ov.current?.name || '普货通用范本（内置）'
    mappingData.value = mapRes.data || null
  } catch (e) { /* 范本库不可用时退化为纯上传模式 */ }
}

watch([selectedNo, cargoType, selYear], () => {
  checked.value = new Set(); boxChecked.value = new Set(); tplChoice.value = {}; loadArchiveItems()
  if (isCrewBox.value) loadCrew()
})
onMounted(() => {
  // 台账范本管理「定位档案柜」跳转：按盒号定位，并提示对应归档项
  const CN = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十', '十一']
  const box = Number(route.query.box || 0)
  if (box >= 1 && box <= CN.length) selectedNo.value = CN[box - 1]
  locatedItem.value = String(route.query.item || '')
  loadTplLibrary(); loadArchiveItems()
})

// ---- 三级勾选 ----
function toggleItem(key, val) {
  if (val) checked.value.add(key)
  else checked.value.delete(key)
  checked.value = new Set(checked.value)
}
function toggleBox(val) {
  // 分类筛选后只操作当前可见项，避免「全选」把被筛掉的项一起勾上
  visibleRows.value.forEach((r) => {
    if (val) checked.value.add(r.key); else checked.value.delete(r.key)
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
const allChecked = computed(() =>
  visibleRows.value.length > 0 && visibleRows.value.every((r) => checked.value.has(r.key)))

// ---- 生成：一律按「台账范本管理」当前应用范本渲染 PDF ----
function currentTpl(item) {
  if (!item?.tplOptions?.length) return null
  const code = tplChoice.value[item.key] || item.tplCode
  return item.tplOptions.find((t) => t.code === code) || item.tplOptions[0]
}

async function generateOne(item, silent = false) {
  const tpl = currentTpl(item)
  if (!tpl) return false
  // 单行生成：仅「按岗位拆分」的文件需要检查岗位人名（批量生成由 batchGenerate 统一提醒一次）
  if (!silent && item.postRelated && !await confirmMissing(await checkMissing(tpl, item))) return false
  await request.post('/archive/template/generate', {
    code: tpl.code,
    itemName: item.name,
    boxNo: selBoxNo.value,
    year: selYear.value,
    post: postFor(item),
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

async function batchGenerate() {
  if (!checked.value.size) {
    ElMessage.info('请先勾选要生成的归档项（可点「全选本盒」快速选择）')
    return
  }
  const targets = visibleRows.value.filter((r) => checked.value.has(r.key) && r.tplOptions.length)
  if (!targets.length) {
    ElMessage.info('勾选项中没有可按范本生成的归档项（其余请使用「上传」按钮留存）')
    return
  }
  generating.value = true
  try {
    // 批量只提醒一次：先汇总【按岗位拆分】的归档项缺失的人名字段；
    // 证件 / 动态记录 / 上传留存不参与岗位人名检查，避免误弹补录提示。
    const allMissing = []
    for (const r of targets) {
      if (r.postRelated) allMissing.push(...await checkMissing(currentTpl(r), r))
    }
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

// ---- 盒一「管理人员档案」：按人分类的一人一档 PDF 集合 ----
// 数据来源是人员基础信息里 person_type=manager 的管理人员，每人一份档案册 PDF，
// 册内含岗前培训、考核记录、三书、任命书、证照等，归档时 person_id 落到本人。
const showManagerPanel = ref(false)
const managerData = ref({ persons: [], total: 0, generated: 0 })
const managerBusy = ref(false)
const managerGenerating = ref(0)
const expandedManager = ref(0)

async function loadManagerOverview() {
  const res = await request.get('/archive/manager/overview', { params: { year: selYear.value } })
  managerData.value = res.data || { persons: [], total: 0, generated: 0 }
}
async function openManagerPanel() {
  showRespPanel.value = false
  showManagerPanel.value = true
  managerBusy.value = true
  try { await loadManagerOverview() }
  catch (e) { ElMessage.error('读取管理人员档案失败') }
  finally { managerBusy.value = false }
}
async function generateManager(personId = null) {
  managerGenerating.value = personId || -1
  try {
    const res = await request.post('/archive/manager/generate', { year: selYear.value, personId })
    ElMessage.success(`已生成 ${res.data?.length || 0} 份管理人员档案册并按人归档`)
    await loadManagerOverview()
    await loadArchiveItems()
  } catch (e) { ElMessage.error('生成失败：' + (e?.message || '请重试')) }
  finally { managerGenerating.value = 0 }
}
function downloadPdf(pdfId, name) { return download({ pdfId, name }) }

// ---- 盒十一「从业人员一人一档 + 一车一档」：按人/按车浏览，点击文件名预览真实 PDF ----
const crewData = ref({ employees: [], vehicles: [] })
const crewBusy = ref(false)
const crewTab = ref('emp')           // emp=从业人员一人一档 | veh=一车一档
const expandedCrew = ref(new Set())  // 展开的人员/车辆 id 集合
const crewPreview = ref(null)        // { name, blobUrl, kind }
const crewPreviewing = ref(false)
const crewDownloading = ref(false)    // 盒十一一键下载整盒（ZIP）
const albumLoading = ref('')         // 合并版档案册下载中的人员/车辆 key：'e'+id / 'v'+id

function toggleCrew(id) {
  const s = new Set(expandedCrew.value)
  s.has(id) ? s.delete(id) : s.add(id)
  expandedCrew.value = s
}

async function loadCrew() {
  crewBusy.value = true
  try {
    const [e, v] = await Promise.all([
      request.get('/archive/employee-archive/overview'),
      request.get('/archive/vehicle-archive/overview'),
    ])
    crewData.value = { employees: e.data?.employees || [], vehicles: v.data?.vehicles || [] }
  } catch (err) {
    ElMessage.error('读取盒十一档案失败')
  } finally {
    crewBusy.value = false
  }
}

// 点击文件名：按 MinIO 对象名下载真实 PDF 并内嵌预览
async function previewCrewFile(fileUrl, name) {
  if (!fileUrl) { ElMessage.warning('该归档项暂无文件'); return }
  crewPreviewing.value = true
  clearCrewPreviewBlob()
  crewPreview.value = { name, blobUrl: '', kind: '' }
  try {
    const token = localStorage.getItem('token')
    const res = await fetch(`/api/archive/file?path=${encodeURIComponent(fileUrl)}`, {
      headers: { Authorization: `Bearer ${token}`, 'X-Token': `Bearer ${token}` },
    })
    if (!res.ok) throw new Error('fetch failed')
    const blob = await res.blob()
    crewPreview.value = { name, blobUrl: URL.createObjectURL(blob), kind: blob.type.startsWith('image/') ? 'image' : 'pdf' }
  } catch (e) {
    crewPreview.value = { name, blobUrl: '', kind: 'error' }
  } finally {
    crewPreviewing.value = false
  }
}

function clearCrewPreviewBlob() {
  if (crewPreview.value?.blobUrl) { URL.revokeObjectURL(crewPreview.value.blobUrl); crewPreview.value = null }
}

function closeCrewPreview() {
  clearCrewPreviewBlob()
}

// 通用：带鉴权拉取二进制并触发浏览器下载（整盒 ZIP / 合并版档案册 PDF 共用）
async function downloadBlob(urlPath, fname, mime) {
  const token = localStorage.getItem('token')
  const res = await fetch(urlPath, {
    headers: { Authorization: `Bearer ${token}`, 'X-Token': `Bearer ${token}` },
  })
  if (!res.ok) {
    let msg = '下载失败'
    try { const j = await res.json(); if (j && j.message) msg = j.message } catch (_) { /* ignore */ }
    throw new Error(msg)
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fname
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 3000)
}

// 盒十一「一键下载整盒」：全部从业人员 + 车辆归档 PDF 打包成 ZIP
async function downloadCrewAll() {
  crewDownloading.value = true
  try {
    await downloadBlob('/api/archive/crew/download-all',
      `盒十一_从业人员一人一档及一车一档.zip`, 'application/zip')
    ElMessage.success('整盒打包下载已开始')
  } catch (e) {
    ElMessage.error('整盒下载失败：' + (e?.message || '请重试'))
  } finally {
    crewDownloading.value = false
  }
}

// 盒十一「合并版档案册」：把某人/某车的分散归档项合并成一本 PDF 下载
async function downloadCrewAlbum(kind, id, name) {
  const key = (kind === 'emp' ? 'e' : 'v') + id
  albumLoading.value = key
  try {
    const qs = kind === 'emp' ? `personId=${id}` : `vehicleId=${id}`
    const path = kind === 'emp' ? `/api/archive/crew/employee-album?${qs}` : `/api/archive/crew/vehicle-album?${qs}`
    const fname = kind === 'emp' ? `${name}_一人一档档案册.pdf` : `${name}_一车一档档案册.pdf`
    await downloadBlob(path, fname, 'application/pdf')
    ElMessage.success('档案册已开始下载')
  } catch (e) {
    ElMessage.error('档案册下载失败：' + (e?.message || '请重试'))
  } finally {
    albumLoading.value = ''
  }
}

// ---- 盒一「安全生产责任书」：组织架构上下级双签生效 ----
// 上级来自 org_post.parent_id 汇报线（跳过禁用岗位后上提），最顶层主要负责人与公司签；
// 只有 sup/sub 双方都签署才 effective，此时才生成带双方签名与公司章的 PDF。
const showRespPanel = ref(false)
const respData = ref({ rows: [], total: 0, effective: 0, partial: 0, pending: 0 })
const respBusy = ref(false)
const respSigning = ref(0)
const respHistory = ref({ show: false, rows: [], name: '' })

async function loadRespOverview() {
  const res = await request.get('/archive/resp-sign/overview', { params: { year: selYear.value } })
  respData.value = res.data || { rows: [], total: 0, effective: 0, partial: 0, pending: 0 }
}
async function openRespPanel() {
  showManagerPanel.value = false
  showRespPanel.value = true
  respBusy.value = true
  try { await loadRespOverview() }
  catch (e) { ElMessage.error('读取责任书签订状态失败') }
  finally { respBusy.value = false }
}
async function signResp(row, side) {
  respSigning.value = row.id
  try {
    const res = await request.post('/archive/resp-sign/sign', {
      id: row.id,
      side,
      signName: side === 'sup' ? row.supName : row.subName,
    })
    if (res.data?.status === 'effective') {
      ElMessage.success('上下级双方均已签署，责任书生效并已归档 PDF')
    } else {
      ElMessage.success('已记录签署，等待另一方签署后生效')
    }
    await loadRespOverview()
  } catch (e) { ElMessage.error('签署失败：' + (e?.message || '请重试')) }
  finally { respSigning.value = 0 }
}
async function openRespHistory(row) {
  try {
    const res = await request.get('/archive/resp-sign/history', {
      params: { subPersonId: row.subPersonId, year: selYear.value },
    })
    respHistory.value = { show: true, rows: res.data || [], name: row.subName }
  } catch (e) { ElMessage.error('读取历史版本失败') }
}

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

async function checkMissing(tpl, item) {
  if (!tpl) return []
  try {
    const res = await request.get('/archive/template/check', {
      // 非岗位相关文件一律不传 post，避免把上一份责任书的岗位串到证件/记录类
      params: { code: tpl.code, post: postFor(item) },
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
  // 切到另一份文件时清空岗位选择，避免把上一份责任书的岗位串到证件/记录类文件；
  // 同一份文件内由岗位下拉触发的刷新不重置。
  if (preview.value && preview.value.key !== item.key) previewPost.value = ''
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
  // 只有按岗位拆分的文件才需要「未读取到岗位人名」提醒；证件/记录类不弹这个提示
  if (item.postRelated) {
    checkMissing(tpl, item).then((list) => { if (preview.value?.key === key) missingPersons.value = list })
  }
  try {
    const res = await request.get(`/tpl/library/render/${encodeURIComponent(tpl.code)}`, {
      params: { mode: 'final', itemName: item.name, year: selYear.value, post: postFor(item) },
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

// ---------- 范本套管理抽屉：在电子档案柜现场切换 / 应用服务商套 / 另存新套 ----------
// 后端接口与「台账范本管理」完全同源；切换后本柜范本列表、归档行、对应关系一并刷新。
const showSetPanel = ref(false)
const setTab = ref('sets')            // sets | mapping
const providerSets = ref([])
const mySets = ref([])
const currentSetMeta = ref(null)
const setBusy = ref(false)
const inlineForm = ref('')            // '' | 'saveAs' | 'apply'
const formName = ref('')
const formTarget = ref(null)

const mappingGroups = computed(() => {
  const m = {}
  ;(mappingData.value?.catalogItems || []).forEach((it) => {
    const k = it.boxNo || 0
    ;(m[k] = m[k] || { boxNo: k, boxName: it.boxName, items: [] }).items.push(it)
  })
  return Object.keys(m).sort((a, b) => Number(a) - Number(b)).map((k) => m[k])
})

async function loadSetOverview() {
  try {
    const ov = (await request.get('/tpl/ent/overview')).data || {}
    providerSets.value = ov.providers || []
    mySets.value = ov.mine || []
    currentSetMeta.value = ov.current || null
  } catch (e) { /* 概览不可用时保留既有数据 */ }
}

async function refreshTpl() {
  await Promise.all([loadTplLibrary(), loadArchiveItems(), loadSetOverview()])
}

async function openSetPanel() {
  showSetPanel.value = true
  inlineForm.value = ''
  setTab.value = 'sets'
  await loadSetOverview()
}
function closeSetPanel() { showSetPanel.value = false; inlineForm.value = '' }

function onSetSelect(e) {
  const row = mySets.value.find((s) => String(s.id) === String(e.target.value))
  if (row) switchSet(row)
}
async function switchSet(row) {
  if (!row || String(row.id) === String(currentSetMeta.value?.id)) return
  setBusy.value = true
  try {
    await request.put(`/tpl/ent/${row.id}/current`)
    ElMessage.success(`已切换当前应用范本套为「${row.name}」，本柜已按新套刷新`)
    await refreshTpl()
  } finally { setBusy.value = false }
}
function openSaveAs() {
  inlineForm.value = 'saveAs'
  formName.value = (currentSetMeta.value?.name || '普货通用范本') + '（副本）'
}
function openApply(row) {
  inlineForm.value = 'apply'
  formTarget.value = row
  formName.value = row.name + '（企业应用）'
}
async function submitInlineForm() {
  const name = formName.value.trim()
  if (!name) { ElMessage.warning('请输入范本套名称'); return }
  setBusy.value = true
  try {
    if (inlineForm.value === 'apply') {
      await request.post('/tpl/ent/apply', { sourceSetId: formTarget.value?.id, name })
      ElMessage.success(`已以服务商范本套为基础创建「${name}」，并设为当前应用`)
    } else {
      await request.post('/tpl/ent/save-as', { name })
      ElMessage.success(`已另存为新范本套「${name}」，并设为当前应用`)
    }
    inlineForm.value = ''
    await refreshTpl()
  } finally { setBusy.value = false }
}
async function deleteSet(row) {
  if (row.isCurrent === 1) { ElMessage.warning('当前应用范本套不可删除'); return }
  await request.delete(`/tpl/ent/${row.id}`)
  ElMessage.success(`已删除范本套「${row.name}」`)
  await refreshTpl()
}
function locateItem(boxNo, name) {
  const CN = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十', '十一']
  selectedNo.value = CN[boxNo - 1] || '一'
  locatedItem.value = name
  closeSetPanel()
  ElMessage.success(`已定位到档案盒 ${boxNo} · ${name}`)
}
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

    <!-- 增值服务权限提示：企业只读，服务商/超管需指定目标企业 -->
    <div v-if="!canGenerateTpl" class="vas-tip">
      <Icon name="info" :size="15" />
      <span><b>台账范本定制与生成为服务商增值服务</b>：企业账号可查看范本、下载 / 打印已生成的成品，并自行上传营业执照等证件材料；如需按范本生成或调整范本套、印章，请联系服务商。</span>
    </div>
    <div v-else class="vas-tip vas-tip-ok">
      <Icon name="building" :size="15" />
      <span><b>正在为「{{ auth.targetTenantName || '未选择企业' }}」管理台账范本</b>：生成的成品按该企业的范本套、岗位人名与公司章渲染，并归档到该企业的电子档案柜。可在右上角「服务企业」切换。</span>
    </div>

    <!-- 同源联动条：明确告知当前台账由哪套范本生成，并可就地切换 -->
    <div class="tpl-linkbar">
      <div class="lb-left">
        <Icon name="bookOpen" :size="19" />
        <div>
          <div class="lb-t">当前应用范本套：<b>{{ currentSetName }}</b> · 已加载 {{ tplList.length }} 份台账范本</div>
          <div class="lb-d">本柜点「生成」时，<b>利用当前应用的范本套</b>渲染带岗位人名与公司章的成品并归档到此柜；点右侧「范本套管理」即可在本页切换、应用服务商套或另存新套，切换后即时生效</div>
        </div>
      </div>
      <div class="lb-ops">
        <el-button size="small" @click="refreshTpl"><Icon name="refresh" :size="13" /> 刷新范本</el-button>
        <el-button size="small" @click="goTplManager"><Icon name="edit" :size="13" /> 台账范本管理</el-button>
        <el-button size="small" type="primary" @click="openSetPanel"><Icon name="bookOpen" :size="13" /> 范本套管理 / 切换</el-button>
      </div>
    </div>
    <div v-if="locatedItem" class="located-tip">
      <Icon name="link" :size="14" /> 已从台账范本管理定位到归档项：<b>{{ locatedItem }}</b>
      <span>（证照类归档项无范本，请使用「上传」留存）</span>
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
            <el-button v-if="!isCrewBox" @click="downloadBox"><Icon name="download" :size="15" /> 一键下载整盒</el-button>
            <el-button v-else :loading="crewDownloading" @click="downloadCrewAll">
              <Icon name="download" :size="15" /> 一键下载整盒
            </el-button>
          </div>
        </div>

        <!-- 文件分类：依据《08》三大类资料 + ②′ 上传留存；类别决定生产方式，与岗位无关 -->
        <div class="cat-filter" v-if="!isCrewBox">
          <span class="cat-filter-t">文件分类</span>
          <button :class="['cat-pill', categoryFilter === 'all' && 'on']" @click="categoryFilter = 'all'">
            全部 <b>{{ categoryCounts.all }}</b>
          </button>
          <button v-for="c in CATEGORY_LIST" :key="c.key"
                  :class="['cat-pill', categoryFilter === c.key && 'on']"
                  :title="c.desc" @click="categoryFilter = c.key">
            {{ c.label }} <b>{{ categoryCounts[c.key] || 0 }}</b>
          </button>
          <span class="cat-filter-tip">依据《08》三大类 + ②′ 上传留存划分；证件 / 动态记录 / 上传留存与岗位无关，不提供岗位切换</span>
        </div>

        <div class="list-toolbar" v-if="!isCrewBox">
          <el-checkbox class="selall-cb" :model-value="allChecked" @change="toggleBox">
            {{ categoryFilter === 'all' ? '全选本盒' : '全选当前分类' }}
          </el-checkbox>
          <el-button v-if="canGenerateTpl" size="small" type="primary" :loading="generating" @click="batchGenerate">
            <Icon name="fileText" :size="14" /> 批量生成勾选（{{ checked.size }}）
          </el-button>
        </div>

        <!-- 盒十一：从业人员一人一档 + 一车一档（按人/按车浏览，点击文件名预览真实 PDF） -->
        <div v-if="isCrewBox" v-loading="crewBusy" class="crew-panel">
          <div class="crew-tabs">
            <button :class="['crew-tab', crewTab === 'emp' && 'on']" @click="crewTab = 'emp'">
              从业人员一人一档 <b>{{ crewData.employees.length }}</b>
            </button>
            <button :class="['crew-tab', crewTab === 'veh' && 'on']" @click="crewTab = 'veh'">
              一车一档 <b>{{ crewData.vehicles.length }}</b>
            </button>
            <span class="crew-tip">点击文件名即可预览该归档项的真实 PDF / 扫描件</span>
          </div>

          <!-- 从业人员一人一档 -->
          <div v-if="crewTab === 'emp'" class="crew-body">
            <div v-if="!crewData.employees.length && !crewBusy" class="sd-empty">
              人员基础信息里还没有从业人员（驾驶员 / 押运员等），请先录入后再查看一人一档。
            </div>
            <div v-for="p in crewData.employees" :key="p.personId" class="crew-card">
              <div class="crew-card-h" @click="toggleCrew('e' + p.personId)">
                <div class="ma-avatar">{{ (p.name || '—').slice(0, 1) }}</div>
                <div class="ma-id">
                  <div class="ma-nm">{{ p.name }} <span class="ma-post">{{ p.postName || '未分配岗位' }}</span></div>
                  <div class="ma-sub">{{ p.telephone || '—' }} · 完整度 {{ p.done }}/{{ p.required }} 项（{{ p.percent }}%）</div>
                </div>
                <span :class="['ma-badge', p.done === p.required && 'on']">{{ p.done === p.required ? '齐全' : '待补齐' }}</span>
                <span class="ma-exp">{{ expandedCrew.has('e' + p.personId) ? '收起' : '明细' }}</span>
              </div>
              <div v-if="expandedCrew.has('e' + p.personId)" class="crew-detail">
                <div class="crew-detail-bar">
                  <el-button size="small" :loading="albumLoading==='e'+p.personId"
                             @click.stop="downloadCrewAlbum('emp', p.personId, p.name)">
                    <Icon name="download" :size="13" /> 下载一人一档档案册（合并版 PDF）
                  </el-button>
                </div>
                <div class="ma-sec-t">一人一档归档目录（{{ p.items?.length || 0 }} 项）</div>
                <div v-for="it in p.items" :key="it.code" class="crew-item">
                  <span class="ma-code">{{ it.code }}</span>
                  <span class="ma-inm">{{ it.name }}</span>
                  <span :class="['ma-st', it.status === 1 && 'ok']">{{ it.status === 1 ? '已归档' : it.status === 2 ? '已过期' : '缺失' }}</span>
                  <span v-if="it.fileUrl" class="crew-file clickable" @click="previewCrewFile(it.fileUrl, it.name)">
                    <Icon name="search" :size="13" /> {{ it.name }}.pdf
                  </span>
                  <span v-else class="crew-file off">（暂无文件）</span>
                </div>
                <div class="ma-sec-t">证照与资格证明（{{ p.docs?.length || 0 }} 项）</div>
                <div v-if="!p.docs?.length" class="ma-empty">暂无证照记录</div>
                <div v-for="d in p.docs" :key="d.name" class="crew-item">
                  <span class="ma-inm">{{ d.name }}</span>
                  <span class="ma-grp">有效期至 {{ d.expire || '—' }}</span>
                  <span v-if="d.fileUrl" class="crew-file clickable" @click="previewCrewFile(d.fileUrl, d.name)">
                    <Icon name="search" :size="13" /> 预览
                  </span>
                  <span v-else class="crew-file off">（暂无文件）</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 一车一档 -->
          <div v-if="crewTab === 'veh'" class="crew-body">
            <div v-if="!crewData.vehicles.length && !crewBusy" class="sd-empty">
              车辆基础信息里还没有车辆，请先录入后再查看一车一档。
            </div>
            <div v-for="v in crewData.vehicles" :key="v.vehicleId" class="crew-card">
              <div class="crew-card-h" @click="toggleCrew('v' + v.vehicleId)">
                <div class="ma-avatar">{{ (v.plateNo || '—').slice(0, 1) }}</div>
                <div class="ma-id">
                  <div class="ma-nm">{{ v.plateNo }} <span class="ma-post">{{ v.vehicleType || '' }} · {{ v.owner || '' }}</span></div>
                  <div class="ma-sub">{{ v.bizType || '' }} · 品牌型号 {{ v.brandModel || '—' }} · 一车一档 {{ v.items?.length || 0 }} 项</div>
                </div>
                <span class="ma-exp">{{ expandedCrew.has('v' + v.vehicleId) ? '收起' : '明细' }}</span>
              </div>
              <div v-if="expandedCrew.has('v' + v.vehicleId)" class="crew-detail">
                <div class="crew-detail-bar">
                  <el-button size="small" :loading="albumLoading==='v'+v.vehicleId"
                             @click.stop="downloadCrewAlbum('veh', v.vehicleId, v.plateNo)">
                    <Icon name="download" :size="13" /> 下载一车一档档案册（合并版 PDF）
                  </el-button>
                </div>
                <div class="ma-sec-t">一车一档归档目录（{{ v.items?.length || 0 }} 项）</div>
                <div v-for="it in v.items" :key="it.code" class="crew-item">
                  <span class="ma-code">{{ it.code }}</span>
                  <span class="ma-inm">{{ it.name }}</span>
                  <span :class="['ma-st', it.status === 1 && 'ok']">{{ it.status === 1 ? '已归档' : it.status === 2 ? '已过期' : '缺失' }}</span>
                  <span v-if="it.fileUrl" class="crew-file clickable" @click="previewCrewFile(it.fileUrl, it.name)">
                    <Icon name="search" :size="13" /> {{ it.name }}.pdf
                  </span>
                  <span v-else class="crew-file off">（暂无文件）</span>
                </div>
                <div class="ma-sec-t">车辆证照（{{ v.docs?.length || 0 }} 项）</div>
                <div v-if="!v.docs?.length" class="ma-empty">暂无证照记录</div>
                <div v-for="d in v.docs" :key="d.name" class="crew-item">
                  <span class="ma-inm">{{ d.name }}</span>
                  <span class="ma-grp">有效期至 {{ d.expire || '—' }}</span>
                  <span v-if="d.fileUrl" class="crew-file clickable" @click="previewCrewFile(d.fileUrl, d.name)">
                    <Icon name="search" :size="13" /> 预览
                  </span>
                  <span v-else class="crew-file off">（暂无文件）</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div v-loading="loading" class="item-list" v-if="!isCrewBox">
          <div v-for="r in visibleRows" :key="r.key" class="item-row" :class="{ located: r.located }">
            <span class="item-idx">{{ r.seq }}</span>
            <el-checkbox class="item-cb" :model-value="checked.has(r.key)" @change="(v) => toggleItem(r.key, v)" />
            <div class="nm">
              <span class="n clickable" @click="openPreview(r)">{{ r.name }}</span>
              <div class="d">
                <span class="cat" :class="`cat-${r.categoryColor}`" :title="r.categoryDesc">{{ r.categoryShort }}</span>
                <span v-if="r.postRelated" class="cat cat-post" title="该文件按岗位拆分，可在预览中切换岗位">按岗位</span>
                <span v-if="r.tplOptions.length" class="src src-auto">按范本生成</span>
                <span v-else class="src src-up">上传留存</span>
                <span v-if="r.tplDriven" class="src src-tpl">范本目录项</span>
                <span v-if="r.freq" class="freq" :class="`freq-${r.freq}`">{{ FREQ_LABEL[r.freq] }}</span>
                <span v-if="r.name === '管理人员档案'" class="src src-tpl" title="按管理人员一人一档生成 PDF 集合，册内包含岗前培训、考核记录、三书等">按人分类</span>
                <span v-if="r.name === '安全生产责任书'" class="src src-tpl" title="上下级双方都签署后才生效；上下级关系取自安全管理架构">双签生效</span>
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
              <el-button v-if="r.name === '管理人员档案'" size="small" type="warning" plain @click="openManagerPanel">
                <Icon name="fileText" :size="13" /> 按人查看
              </el-button>
              <el-button v-if="r.name === '安全生产责任书'" size="small" type="warning" plain @click="openRespPanel">
                <Icon name="edit" :size="13" /> 签订管理
              </el-button>
              <el-button v-if="r.tplOptions.length && canGenerateTpl" size="small" @click="doGenerate(r)">
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
          <span v-if="preview" class="cat" :class="`cat-${preview.categoryColor}`" :title="preview.categoryDesc">
            {{ preview.categoryLabel }}
          </span>
          <span v-if="currentTpl(preview)?.name" class="tb-tpl">
            当前范本：{{ currentTpl(preview).name }}（{{ currentTpl(preview).versionLabel || '内置' }}）
          </span>
          <!-- 只有按岗位拆分的文件才提供岗位切换；证件类/动态记录/上传留存一律不显示 -->
          <select v-if="preview?.postRelated" v-model="previewPost" class="tb-sel" @change="openPreview(preview)">
            <option value="">默认岗位</option>
            <option v-for="p in previewPosts" :key="p" :value="p">{{ p }}</option>
          </select>
          <el-button v-if="currentTpl(preview) && !preview?.isUploaded && canGenerateTpl" size="small" @click="generateFromPreview">
            <Icon name="refresh" :size="13" /> 生成 / 重新生成
          </el-button>
          <el-button v-if="preview?.pdfId" size="small" type="primary" @click="download(preview)">
            <Icon name="download" :size="13" /> 下载 PDF
          </el-button>
          <button class="x" @click="closePreview">×</button>
        </div>
        <div v-if="missingPersons.length && preview?.postRelated" class="person-warn">
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

    <!-- 盒十一：归档项文件预览（按 MinIO 对象名下载真实 PDF / 扫描件） -->
    <div class="pdf-mask" :class="{ show: crewPreview }" @click.self="closeCrewPreview">
      <div class="pdf-wrap">
        <div class="pdf-toolbar">
          <span class="t">{{ crewPreview?.name }} · 文件预览</span>
          <button class="x" @click="closeCrewPreview">×</button>
        </div>
        <div class="pdf-body">
          <div v-if="crewPreviewing" class="sd-empty">正在加载文件…</div>
          <iframe v-else-if="crewPreview?.blobUrl && crewPreview?.kind === 'pdf'" :src="crewPreview.blobUrl" class="pdf-embed" />
          <img v-else-if="crewPreview?.blobUrl && crewPreview?.kind === 'image'" :src="crewPreview.blobUrl" class="pdf-embed-img" alt="扫描件" />
          <div v-else class="sd-empty">文件读取失败，请稍后重试。</div>
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

    <!-- 范本套管理抽屉：在电子档案柜现场切换 / 应用服务商套 / 另存新套 / 查看对应关系 -->
    <div class="sd-mask" :class="{ show: showSetPanel }" @click.self="closeSetPanel">
      <div class="sd-panel">
        <div class="sd-head">
          <div>
            <h2><Icon name="bookOpen" :size="18" /> 范本套管理</h2>
            <p>切换当前应用范本套、以服务商套为基础新建、或另存为新套；切换后本柜「生成」立即按新套渲染</p>
          </div>
          <span class="sd-x" @click="closeSetPanel">×</span>
        </div>
        <div class="sd-tabs">
          <button :class="{ on: setTab === 'sets' }" @click="setTab = 'sets'">范本套切换</button>
          <button :class="{ on: setTab === 'mapping' }" @click="setTab = 'mapping'">档案柜对应</button>
        </div>

        <div v-if="setTab === 'sets'" v-loading="setBusy" class="sd-body">
          <div class="sd-card">
            <div class="sd-card-t">当前应用范本套</div>
            <select class="sd-select" :value="String(currentSetMeta?.id || '')" @change="onSetSelect">
              <option v-for="s in mySets" :key="s.id" :value="String(s.id)">{{ s.name }}（{{ s.templateCount }} 份）</option>
              <option v-if="!mySets.length" value="">普货通用范本（内置）</option>
            </select>
            <div class="sd-note">共 {{ currentSetMeta?.templateCount ?? tplList.length }} 份范本 · 本柜已加载 {{ tplList.length }} 份；切换后档案盒各行的「当前范本」与生成结果同步刷新</div>
          </div>

          <div class="sd-sec-t">我的范本套 <span>{{ mySets.length }} 套</span></div>
          <div v-if="!mySets.length" class="sd-empty">暂无，可从下方服务商范本套「应用」，或点「另存为新范本套」创建</div>
          <div v-for="s in mySets" :key="s.id" class="sd-item"
               :class="{ on: String(s.id) === String(currentSetMeta?.id) }" @click="canGenerateTpl && switchSet(s)">
            <Icon name="book" :size="15" />
            <span class="sd-nm">{{ s.name }}</span>
            <span class="sd-cnt">{{ s.templateCount }} 份</span>
            <span v-if="String(s.id) === String(currentSetMeta?.id)" class="sd-tag green">当前应用</span>
            <span v-else-if="canGenerateTpl" class="sd-del" title="删除此套" @click.stop="deleteSet(s)">×</span>
          </div>
          <div class="sd-hint">{{ canGenerateTpl ? '点击某套即设为「当前应用范本套」；非当前套可点 × 删除' : '当前应用范本套由服务商维护，企业账号只读' }}</div>

          <div class="sd-sec-t">服务商范本套 <span>基础默认模板 · 只读基线</span></div>
          <div v-for="s in providerSets" :key="s.id" class="sd-item provider" @click="canGenerateTpl && openApply(s)">
            <Icon name="bookOpen" :size="15" />
            <span class="sd-nm">{{ s.name }}</span>
            <span class="sd-cnt">{{ s.templateCount }} 份</span>
            <span v-if="s.isDefault === 1" class="sd-tag blue">默认</span>
            <span v-if="canGenerateTpl" class="sd-apply">应用</span>
          </div>
          <div class="sd-hint">「应用」会深拷贝整套范本成为企业自己的新范本套，并自动设为当前应用</div>

          <el-button v-if="canGenerateTpl" class="sd-saveas" size="small" plain @click="openSaveAs"><Icon name="save" :size="15" /> 另存为新范本套</el-button>

          <div v-if="inlineForm" class="sd-form">
            <div class="sd-form-t">{{ inlineForm === 'apply' ? '以服务商范本套「' + (formTarget?.name || '') + '」为基础应用' : '把当前范本套另存为新套' }}</div>
            <input v-model="formName" class="form-input" placeholder="输入新范本套名称" @keyup.enter="submitInlineForm" />
            <div class="sd-form-ops">
              <el-button size="small" @click="inlineForm = ''">取消</el-button>
              <el-button size="small" type="primary" :loading="setBusy" @click="submitInlineForm">确认{{ inlineForm === 'apply' ? '应用' : '另存' }}</el-button>
            </div>
          </div>
        </div>

        <div v-else class="sd-body">
          <div class="sd-mapstat">
            <span>范本 <b>{{ mappingData?.summary?.templateCount || 0 }}</b></span>
            <span>已对应 <b>{{ mappingData?.summary?.mappedCount || 0 }}</b></span>
            <span>归档项 <b>{{ mappingData?.summary?.catalogItemCount || 0 }}</b></span>
            <span class="warn">手动上传 <b>{{ mappingData?.summary?.uploadOnlyCount || 0 }}</b></span>
          </div>
          <div class="sd-note">当前应用范本套「{{ currentSetName }}」· 每一份范本都有归档去向；证照 / 保单等无范本归档项请用「上传」留存</div>
          <div v-for="g in mappingGroups" :key="g.boxNo" class="sd-mg">
            <div class="sd-mgt">盒 {{ g.boxNo }} · {{ g.boxName }}</div>
            <div v-for="it in g.items" :key="it.id" class="sd-mr" :class="{ off: !it.hasTemplate }">
              <div class="sd-mrn">{{ it.name }}</div>
              <div v-if="it.hasTemplate" class="sd-tag auto">按范本生成 · {{ it.tplCodes.length }} 份</div>
              <div v-else class="sd-tag upload">无范本 · 手动上传留存</div>
              <el-button size="small" text type="primary" @click="locateItem(g.boxNo, it.name)">定位</el-button>
            </div>
          </div>
        </div>

        <div class="sd-foot">
          <span>需要在线修改范本正文、印章设置或责任制联动，请前往 <a href="javascript:;" @click="goTplManager">台账范本管理</a></span>
          <el-button size="small" @click="closeSetPanel">关闭</el-button>
        </div>
      </div>
    </div>

    <!-- 盒一「管理人员档案」：按人分类的一人一档 PDF 集合 -->
    <div class="sd-mask" :class="{ show: showManagerPanel }" @click.self="showManagerPanel = false">
      <div class="sd-panel ma-panel">
        <div class="sd-head">
          <div>
            <h2><Icon name="fileText" :size="18" /> 管理人员档案 · 一人一档</h2>
            <p>按人员基础信息中录入的管理人员逐人生成档案册 PDF，册内含岗前培训、考核记录、三书、任命书与证照</p>
          </div>
          <span class="sd-x" @click="showManagerPanel = false">×</span>
        </div>
        <div class="ma-stat">
          <span>归档年度 <b>{{ selYear }}</b></span>
          <span>管理人员 <b>{{ managerData.total }}</b></span>
          <span>已生成档案册 <b class="green">{{ managerData.generated }}</b></span>
          <span>企业 <b>{{ managerData.company || '—' }}</b></span>
          <el-button size="small" type="primary" :loading="managerGenerating === -1" @click="generateManager(null)">
            生成全部档案册
          </el-button>
        </div>
        <div v-loading="managerBusy" class="sd-body ma-body">
          <div v-if="!managerData.persons?.length && !managerBusy" class="sd-empty">
            人员基础信息里还没有管理人员，请先在「人员基础信息」录入管理人员后再生成一人一档。
          </div>
          <div v-for="p in managerData.persons" :key="p.personId" class="ma-card">
            <div class="ma-card-h" @click="expandedManager = expandedManager === p.personId ? 0 : p.personId">
              <div class="ma-avatar">{{ (p.name || '—').slice(0, 1) }}</div>
              <div class="ma-id">
                <div class="ma-nm">{{ p.name }} <span class="ma-post">{{ p.postName || '未分配岗位' }}</span></div>
                <div class="ma-sub">{{ p.telephone || '—' }} · 完整度 {{ p.done }}/{{ p.required }} 项（{{ p.percent }}%）</div>
              </div>
              <div class="ma-focus">
                <span v-for="(v, k) in p.focus" :key="k"
                      :class="['ma-chip', v.total > 0 && v.done === v.total && 'ok']">
                  {{ k }} {{ v.done }}/{{ v.total }}
                </span>
              </div>
              <span :class="['ma-badge', p.generated && 'on']">{{ p.generated ? '已生成' : '待生成' }}</span>
              <span class="ma-exp">{{ expandedManager === p.personId ? '收起' : '明细' }}</span>
            </div>
            <div class="ma-card-ops">
              <el-button size="small" :loading="managerGenerating === p.personId" @click="generateManager(p.personId)">
                {{ p.generated ? '重新生成' : '生成档案册' }}
              </el-button>
              <el-button v-if="p.generated" size="small" type="primary"
                         @click="downloadPdf(p.pdfId, `管理人员档案-${p.name}`)">下载 PDF</el-button>
              <span v-if="p.genTime" class="ma-time">{{ String(p.genTime).replace('T', ' ').slice(0, 16) }}</span>
            </div>
            <div v-if="expandedManager === p.personId" class="ma-detail">
              <div class="ma-sec-t">一人一档归档目录（{{ p.items?.length || 0 }} 项）</div>
              <div v-for="it in p.items" :key="it.code" class="ma-item">
                <span class="ma-code">{{ it.code }}</span>
                <span class="ma-grp">{{ it.group }}</span>
                <span class="ma-inm">{{ it.name }}</span>
                <span :class="['ma-st', it.status === 1 && 'ok']">
                  {{ it.status === 1 ? '已归档' : it.status === 2 ? '已过期' : '缺失' }}
                </span>
              </div>
              <div class="ma-sec-t">证照与资格证明（{{ p.docs?.length || 0 }} 项）</div>
              <div v-if="!p.docs?.length" class="ma-empty">暂无证照记录</div>
              <div v-for="(d, i) in p.docs" :key="i" class="ma-item">
                <span class="ma-inm">{{ d.name }}</span>
                <span class="ma-grp">{{ d.issue || '—' }}</span>
                <span class="ma-grp">有效期至 {{ d.expire || '—' }}</span>
              </div>
            </div>
          </div>
        </div>
        <div class="sd-foot">
          <span>档案册按人落到 person_id、标题带姓名；重新生成刷新成品，旧版本 PDF 仍可留存对照</span>
          <el-button size="small" @click="showManagerPanel = false">关闭</el-button>
        </div>
      </div>
    </div>

    <!-- 盒一「安全生产责任书」：组织架构上下级双签生效 -->
    <div class="sd-mask" :class="{ show: showRespPanel }" @click.self="showRespPanel = false">
      <div class="sd-panel rs-panel">
        <div class="sd-head">
          <div>
            <h2><Icon name="edit" :size="18" /> 安全生产责任书 · 上下级签订</h2>
            <p>签字双方取自安全管理架构的汇报关系，双方均签署后责任书才生效并生成 PDF</p>
          </div>
          <span class="sd-x" @click="showRespPanel = false">×</span>
        </div>
        <div class="rs-rule">
          <Icon name="info" :size="15" />
          <span>上下级关系取自<b>安全管理架构</b>（直接上级汇报线）；<b>上级与下级两个人都签署才生效</b>；
            最顶层主要负责人无人员上级，<b>与公司签署</b>（公司盖章 + 本人签字）。架构变化后受影响的责任书自动作废重签。</span>
        </div>
        <div class="ma-stat rs-stat">
          <span>本年度 <b>{{ selYear }}</b></span>
          <span>待签 <b>{{ respData.pending }}</b></span>
          <span>待生效 <b class="orange">{{ respData.partial }}</b></span>
          <span>已生效 <b class="green">{{ respData.effective }}</b></span>
          <el-button size="small" @click="loadRespOverview">刷新状态</el-button>
        </div>
        <div v-loading="respBusy" class="sd-body rs-body">
          <div v-if="!respData.rows?.length && !respBusy" class="sd-empty">
            本年度暂无签订记录，请在「安全管理架构」中启用岗位并分配人员后刷新。
          </div>
          <div v-for="r in respData.rows" :key="r.id" class="rs-card">
            <div class="rs-rel">
              <span class="rs-side sup">
                <span class="rs-lbl">{{ r.supType === 'company' ? '公司（盖章）' : '上级 · 责任管理人' }}</span>
                <b>{{ r.supType === 'company' ? r.supName : `${r.supPostName} ${r.supName}` }}</b>
              </span>
              <span class="rs-arrow">→ 签订 →</span>
              <span class="rs-side sub">
                <span class="rs-lbl">下级 · 责任人</span>
                <b>{{ r.subPostName }} {{ r.subName }}</b>
              </span>
              <span :class="['rs-status', 'st-' + r.status]">{{ r.statusLabel }}</span>
            </div>
            <div class="rs-signs">
              <span :class="['rs-sign', r.supSigned && 'ok']">
                {{ r.supType === 'company' ? '公司签署' : '上级签署' }}：{{ r.supSigned ? '已签' : '未签' }}
                <el-button v-if="!r.supSigned && r.status !== 'effective'" size="small" text type="primary"
                           :loading="respSigning === r.id" @click="signResp(r, 'sup')">签署</el-button>
              </span>
              <span :class="['rs-sign', r.subSigned && 'ok']">
                下级签署：{{ r.subSigned ? '已签' : '未签' }}
                <el-button v-if="!r.subSigned && r.status !== 'effective'" size="small" text type="primary"
                           :loading="respSigning === r.id" @click="signResp(r, 'sub')">签署</el-button>
              </span>
              <span class="rs-rev">第 {{ r.revision }} 版</span>
            </div>
            <div class="rs-ops">
              <el-button v-if="r.pdfId" size="small" type="primary"
                         @click="downloadPdf(r.pdfId, `安全生产责任书-${r.subName}`)">下载生效 PDF</el-button>
              <el-button size="small" text @click="openRespHistory(r)">历史版本</el-button>
              <span v-if="r.effectiveAt" class="ma-time">生效于 {{ String(r.effectiveAt).replace('T', ' ').slice(0, 16) }}</span>
            </div>
          </div>
        </div>
        <div class="sd-foot">
          <span>单方签署仅记为「待生效」；双方签齐才写入生效时间并生成带双方签名与公司章的 PDF</span>
          <el-button size="small" @click="showRespPanel = false">关闭</el-button>
        </div>
      </div>
    </div>

    <!-- 责任书历史版本（架构变化作废的旧签署件仍可下载对照） -->
    <div class="pdf-mask roster-mask" :class="{ show: respHistory.show }" @click.self="respHistory.show = false">
      <div class="roster-wrap">
        <div class="roster-head">
          「{{ respHistory.name }}」责任书历史版本
          <span class="rh-sub">组织架构变化会作废旧版本并生成新版本待签</span>
          <span class="x" @click="respHistory.show = false">×</span>
        </div>
        <div class="roster-body rs-history">
          <div v-for="h in respHistory.rows" :key="h.id" class="rs-hrow">
            <span>第 {{ h.revision }} 版</span>
            <span>{{ h.signRelation }}</span>
            <span :class="['rs-status', 'st-' + h.status]">{{ h.statusLabel }}</span>
            <span>{{ h.supSigned ? '上级已签' : '上级未签' }} / {{ h.subSigned ? '下级已签' : '下级未签' }}</span>
            <el-button v-if="h.pdfId" size="small" text type="primary"
                       @click="downloadPdf(h.pdfId, `安全生产责任书-${h.subName}-第${h.revision}版`)">下载</el-button>
          </div>
          <div v-if="!respHistory.rows?.length" class="sd-empty">暂无历史版本</div>
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

/* 文件分类筛选（08 三大类 + ②′ 上传留存） */
.cat-filter { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 10px 4px 12px; border-bottom: 1px solid var(--border); margin-bottom: 4px; }
.cat-filter-t { font-size: 12px; font-weight: 700; color: var(--text-2); margin-right: 2px; }
.cat-filter-tip { font-size: 11px; color: var(--text-3); margin-left: auto; }
.cat-pill { font-size: 12px; padding: 5px 11px; border-radius: 16px; border: 1px solid var(--border); background: #fff; color: var(--text-2); cursor: pointer; transition: .12s; }
.cat-pill b { font-weight: 700; margin-left: 3px; opacity: .75; }
.cat-pill:hover:not(.on) { border-color: var(--brand-500); color: var(--brand-600); }
.cat-pill.on { background: var(--brand-500); border-color: var(--brand-500); color: #fff; }
/* 行内/预览类别标签 */
.cat { font-size: 11px; padding: 2px 8px; border-radius: 8px; white-space: nowrap; }
.cat-blue { background: var(--brand-100); color: var(--brand-600); }
.cat-green { background: #F0FDF4; color: #15803D; }
.cat-amber { background: #FFFBEB; color: #B45309; }
.cat-red { background: var(--danger-bg); color: #B91C1C; }
.cat-post { background: #F3E8FF; color: #7C3AED; }
.list-toolbar { display: flex; align-items: center; gap: 14px; padding: 10px 4px; border-bottom: 1px solid var(--border); margin-bottom: 8px; }
.selall-cb { font-size: 13px; font-weight: 600; }

.item-list { background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-lg); overflow: hidden; }
.item-row { display: flex; align-items: center; gap: 12px; padding: 13px 16px; border-bottom: 1px solid var(--border); }
.item-row:last-child { border-bottom: none; }
.item-row.located { background: #EFF6FF; box-shadow: inset 3px 0 0 var(--brand-500, #1D4ED8); }
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
.located-tip { display: flex; align-items: center; gap: 7px; margin: -4px 0 16px; padding: 9px 14px;
  background: #EFF6FF; border: 1px solid #BFDBFE; border-radius: 8px; color: #1E40AF; font-size: 12.5px; }
.located-tip span { color: var(--text-3); }

/* 增值服务权限提示条 */
.vas-tip { display: flex; align-items: flex-start; gap: 8px; padding: 10px 14px; margin: 0 0 14px; border-radius: 8px;
  background: #fff7ed; border: 1px solid #fed7aa; color: #9a3412; font-size: 13px; line-height: 1.7; }
.vas-tip b { color: #7c2d12; }
.vas-tip-ok { background: #eff6ff; border-color: #bfdbfe; color: #1e40af; }
.vas-tip-ok b { color: #1e3a8a; }
/* 范本套管理抽屉（电子档案柜内就地切换模板） */
/* 高于全局 DEV 角标（z-index:9999），避免抽屉底部「关闭 / 台账范本管理」被遮挡 */
.sd-mask { position: fixed; inset: 0; background: rgba(20,28,43,.45); display: none; justify-content: flex-end; z-index: 10000; }
.sd-mask.show { display: flex; }
.sd-panel { width: 560px; max-width: 96vw; height: 100%; background: #fff; display: flex; flex-direction: column;
  box-shadow: -8px 0 32px rgba(0,0,0,.18); }
.sd-head { display: flex; align-items: flex-start; gap: 12px; padding: 18px 20px 14px;
  border-bottom: 1px solid var(--border); background: var(--brand-50); }
.sd-head h2 { display: flex; align-items: center; gap: 8px; font-size: 17px; margin: 0 0 6px; color: var(--brand-700); }
.sd-head p { margin: 0; font-size: 12px; color: var(--text-3); line-height: 1.6; }
.sd-x { margin-left: auto; font-size: 24px; line-height: 1; color: var(--text-3); cursor: pointer; }
.sd-x:hover { color: var(--text); }
.sd-tabs { display: flex; gap: 4px; padding: 10px 20px 0; border-bottom: 1px solid var(--border); }
.sd-tabs button { border: none; background: none; padding: 9px 16px; font-size: 13.5px; color: var(--text-3);
  cursor: pointer; border-bottom: 2px solid transparent; }
.sd-tabs button.on { color: var(--brand-600); font-weight: 700; border-bottom-color: var(--brand-600); }
.sd-body { flex: 1; overflow: auto; padding: 16px 20px 24px; }
.sd-card { background: var(--brand-50); border: 1px solid var(--brand-200, #BFDBFE); border-radius: 10px; padding: 14px; }
.sd-card-t { font-size: 13px; font-weight: 700; color: var(--brand-700); margin-bottom: 8px; }
.sd-select { width: 100%; height: 34px; padding: 0 8px; border: 1px solid var(--border); border-radius: 6px;
  background: #fff; color: var(--text); font-size: 13px; cursor: pointer; }
.sd-note { font-size: 12px; color: var(--text-3); line-height: 1.6; margin-top: 8px; }
.sd-sec-t { display: flex; align-items: baseline; gap: 8px; font-size: 13.5px; font-weight: 700; margin: 20px 0 8px; }
.sd-sec-t span { font-size: 11.5px; font-weight: 400; color: var(--text-3); }
.sd-empty, .sd-hint { font-size: 12px; color: var(--text-3); line-height: 1.6; }
.sd-hint { margin-top: 6px; }
.sd-item { display: flex; align-items: center; gap: 8px; padding: 9px 12px; margin-bottom: 6px;
  border: 1px solid var(--border); border-radius: 8px; cursor: pointer; background: #fff; transition: .12s; }
.sd-item:hover { border-color: var(--brand-400, #60A5FA); background: #FAFCFF; }
.sd-item.on { border-color: var(--brand-500); background: var(--brand-50); }
.sd-nm { flex: 1; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sd-cnt { font-size: 11.5px; color: var(--text-3); }
.sd-tag { font-size: 11px; padding: 2px 8px; border-radius: 10px; white-space: nowrap; }
.sd-tag.green, .sd-tag.auto { background: #DCFCE7; color: #15803D; }
.sd-tag.blue { background: #DBEAFE; color: #1D4ED8; }
.sd-tag.upload { background: #F1F5F9; color: #64748B; }
.sd-del { color: var(--text-3); font-size: 16px; padding: 0 4px; }
.sd-del:hover { color: #DC2626; }
.sd-apply { font-size: 12px; color: var(--brand-600); font-weight: 600; }
.sd-saveas { margin-top: 14px; }
.sd-form { margin-top: 14px; padding: 14px; border: 1px dashed var(--brand-300, #93C5FD);
  border-radius: 8px; background: #FAFCFF; }
.sd-form-t { font-size: 12.5px; font-weight: 600; margin-bottom: 8px; }
.sd-form .form-input { width: 100%; height: 32px; padding: 0 8px; border: 1px solid var(--border);
  border-radius: 6px; font-size: 13px; box-sizing: border-box; }
.sd-form-ops { display: flex; justify-content: flex-end; gap: 8px; margin-top: 10px; }
.sd-mapstat { display: flex; flex-wrap: wrap; gap: 8px; }
.sd-mapstat span { font-size: 12px; background: #F1F5F9; border-radius: 8px; padding: 5px 10px; color: var(--text-2); }
.sd-mapstat b { color: var(--brand-600); font-size: 14px; }
.sd-mapstat .warn { background: #FEF3C7; color: #92400E; }
.sd-mg { margin-top: 16px; }
.sd-mgt { font-size: 12.5px; font-weight: 700; color: var(--brand-700); padding: 6px 0;
  border-bottom: 1px solid var(--border); }
.sd-mr { display: flex; align-items: center; gap: 8px; padding: 7px 0; border-bottom: 1px dashed #E2E8F0; }
.sd-mrn { flex: 1; font-size: 12.5px; }
.sd-mr.off .sd-mrn { color: var(--text-3); }
.sd-foot { display: flex; align-items: center; gap: 10px; padding: 12px 20px;
  border-top: 1px solid var(--border); background: #FAFBFC; }
.sd-foot span { flex: 1; font-size: 11.5px; color: var(--text-3); line-height: 1.5; }
.sd-foot a { color: var(--brand-600); font-weight: 600; }

/* ===== 管理人员档案 · 按人分类的一人一档 PDF 集合 ===== */
.ma-panel, .rs-panel { width: min(960px, 94vw); }
.ma-stat { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 10px 20px;
  background: #F8FAFC; border-bottom: 1px solid var(--border); }
.ma-stat span { font-size: 12px; color: var(--text-2); background: #fff; border: 1px solid var(--border);
  border-radius: 8px; padding: 5px 10px; }
.ma-stat b { color: var(--brand-700); font-size: 14px; }
.ma-stat b.green { color: #15803D; }
.ma-stat b.orange { color: #C2410C; }
.ma-stat .el-button { margin-left: auto; }
.ma-body, .rs-body { padding: 16px 20px; }
.ma-card { border: 1px solid var(--border); border-radius: 10px; margin-bottom: 12px; background: #fff; overflow: hidden; }
.ma-card-h { display: flex; align-items: center; gap: 12px; padding: 12px 14px; cursor: pointer; }
.ma-card-h:hover { background: #FAFCFF; }
.ma-avatar { width: 36px; height: 36px; flex: 0 0 36px; border-radius: 50%; background: var(--brand-600, #2563EB);
  color: #fff; display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 700; }
.ma-id { min-width: 180px; }
.ma-nm { font-size: 14px; font-weight: 700; }
.ma-post { font-size: 11px; color: var(--text-3); font-weight: 400; margin-left: 6px; }
.ma-sub { font-size: 11px; color: var(--text-3); margin-top: 3px; }
.ma-focus { flex: 1; display: flex; flex-wrap: wrap; gap: 6px; }
.ma-chip { font-size: 11px; padding: 3px 8px; border-radius: 10px; background: #FEF3C7; color: #92400E; }
.ma-chip.ok { background: #DCFCE7; color: #15803D; }
.ma-badge { font-size: 11px; padding: 3px 9px; border-radius: 10px; background: #F1F5F9; color: #64748B; white-space: nowrap; }
.ma-badge.on { background: #DCFCE7; color: #15803D; }
.ma-exp { font-size: 11.5px; color: var(--brand-600); white-space: nowrap; }
.ma-card-ops { display: flex; align-items: center; gap: 8px; padding: 0 14px 12px 62px; }
.ma-time { font-size: 11px; color: var(--text-3); margin-left: auto; }
.ma-detail { border-top: 1px dashed var(--border); padding: 10px 14px 14px; background: #FAFCFF; }
.ma-sec-t { font-size: 12px; font-weight: 700; color: var(--brand-700); margin: 8px 0 6px; }
.ma-item { display: flex; align-items: center; gap: 10px; padding: 5px 0; border-bottom: 1px dashed #E2E8F0; font-size: 11.5px; }
.ma-code { width: 92px; color: var(--text-3); font-family: monospace; }
.ma-grp { width: 80px; color: var(--text-2); }
.ma-inm { flex: 1; }
.ma-st { color: #B91C1C; font-weight: 600; }
.ma-st.ok { color: #15803D; }
.ma-empty { font-size: 11.5px; color: var(--text-3); padding: 6px 0; }

/* ===== 盒十一：从业人员一人一档 + 一车一档 ===== */
.crew-panel { padding: 4px 0 12px; }
.crew-tabs { display: flex; align-items: center; gap: 8px; padding: 6px 2px 14px; flex-wrap: wrap; }
.crew-tab { font-size: 13px; padding: 7px 16px; border: 1px solid var(--border); border-radius: 8px;
  background: #fff; color: var(--text-2); cursor: pointer; }
.crew-tab b { color: var(--brand-700); margin-left: 4px; }
.crew-tab.on { background: var(--brand-600); border-color: var(--brand-600); color: #fff; }
.crew-tab.on b { color: #fff; }
.crew-tip { font-size: 11.5px; color: var(--text-3); margin-left: auto; }
.crew-body { display: flex; flex-direction: column; gap: 12px; }
.crew-card { border: 1px solid var(--border); border-radius: 10px; background: #fff; overflow: hidden; }
.crew-card-h { display: flex; align-items: center; gap: 12px; padding: 12px 14px; cursor: pointer; }
.crew-card-h:hover { background: #F8FAFC; }
.crew-card-h .ma-post { font-size: 11px; color: var(--text-3); font-weight: 400; margin-left: 6px; }
.crew-card-h .ma-exp { margin-left: auto; font-size: 11.5px; color: var(--brand-700); }
.crew-detail { padding: 4px 16px 14px; border-top: 1px dashed var(--border); background: #FCFDFE; }
.crew-detail-bar { padding: 10px 0 4px; }
.crew-item { display: flex; align-items: center; gap: 10px; padding: 6px 0; border-bottom: 1px dashed #E2E8F0; font-size: 11.5px; }
.crew-item .ma-code { width: 64px; color: var(--text-3); font-family: monospace; }
.crew-item .ma-inm { flex: 1; }
.crew-file { font-size: 11.5px; color: var(--brand-700); display: inline-flex; align-items: center; gap: 3px;
  padding: 2px 9px; border: 1px solid var(--brand-200); border-radius: 7px; background: var(--brand-50); }
.crew-file.off { color: var(--text-3); border-color: var(--border); background: #F8FAFC; }

/* ===== 安全生产责任书 · 上下级双签 ===== */
.rs-rule { display: flex; align-items: flex-start; gap: 8px; padding: 10px 20px;
  background: #FFFBEB; border-bottom: 1px solid #FDE68A; font-size: 11.5px; color: #78350F; line-height: 1.7; }
.rs-rule b { color: #92400E; }
.rs-card { border: 1px solid var(--border); border-radius: 10px; margin-bottom: 12px; overflow: hidden; }
.rs-rel { display: flex; align-items: center; gap: 10px; padding: 12px 14px; background: #F8FAFC; }
.rs-side { display: flex; flex-direction: column; min-width: 160px; }
.rs-lbl { font-size: 10.5px; color: var(--text-3); }
.rs-side b { font-size: 13px; }
.rs-side.sup b { color: var(--brand-700); }
.rs-side.sub b { color: #0F172A; }
.rs-arrow { font-size: 11.5px; color: var(--text-3); white-space: nowrap; }
.rs-status { margin-left: auto; font-size: 11px; padding: 3px 9px; border-radius: 10px; white-space: nowrap;
  background: #F1F5F9; color: #64748B; }
.rs-status.st-partial { background: #FEF3C7; color: #92400E; }
.rs-status.st-effective { background: #DCFCE7; color: #15803D; }
.rs-status.st-void { background: #FEE2E2; color: #B91C1C; }
.rs-signs { display: flex; align-items: center; gap: 14px; padding: 8px 14px; border-top: 1px dashed var(--border); }
.rs-sign { font-size: 11.5px; color: #B91C1C; display: flex; align-items: center; gap: 2px; }
.rs-sign.ok { color: #15803D; }
.rs-rev { font-size: 11px; color: var(--text-3); margin-left: auto; }
.rs-ops { display: flex; align-items: center; gap: 8px; padding: 0 14px 10px; }
.rs-history { max-height: 420px; overflow: auto; }
.rs-hrow { display: flex; align-items: center; gap: 10px; padding: 8px 4px; border-bottom: 1px dashed #E2E8F0; font-size: 11.5px; }
.rs-hrow > span:nth-child(2) { flex: 1; }
.rs-hrow .rs-status { margin-left: 0; }
</style>
