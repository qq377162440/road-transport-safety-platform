<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'
import Icon from '@/components/Icon.vue'
import { ARCHIVE_CATALOG, FREQ_LABEL, STATUS } from '@/config/archiveCatalog'

const cargoType = ref('普货')
const boxes = computed(() => ARCHIVE_CATALOG[cargoType.value] || [])
const selectedNo = ref('一')
const selectedBox = computed(() => boxes.value.find((b) => b.no === selectedNo.value) || boxes.value[0])

// 盒号（数字，对齐后端 BOX_CATALOG_BASE + boxNo）
const boxNoNum = (no) => boxes.value.findIndex((b) => b.no === no) + 1
const selBoxNo = computed(() => boxNoNum(selectedNo.value))

const currentYear = new Date().getFullYear()
const years = [currentYear, currentYear - 1, currentYear - 2]
const selYear = ref(currentYear)

const realItems = ref([])   // 系统生成（责任制等）
const uploadItems = ref([]) // 上传留存
const loading = ref(false)
const generating = ref(false)
const checked = ref(new Set())     // 逐条勾选：key = `${boxNo}-${idx}`
const boxChecked = ref(new Set())  // 盒级勾选：盒中文号

// 把选中盒目录子项，与真实后端数据合并成渲染行
const rows = computed(() => {
  const realMap = {}
  realItems.value.forEach((it) => { realMap[realTitle(it.title)] = it })
  const upMap = {}
  uploadItems.value.forEach((it) => { upMap[it.title] = it })
  const bno = selBoxNo.value
  return selectedBox.value.items.map((it, idx) => {
    const key = `${bno}-${idx}`
    const isReal = !!it.real
    let status = STATUS.MISS
    let pdfId = null
    let isUp = false
    if (isReal) {
      const real = realMap[realTitle(it.real)]
      if (real) { status = real.status === 1 ? STATUS.DONE : STATUS.WAIT; pdfId = real.pdfId }
    } else {
      isUp = true
      const up = upMap[it.name]
      if (up && up.pdfId) { status = STATUS.DONE; pdfId = up.pdfId }
    }
    return { key, name: it.name, freq: it.freq || '', isReal, isUp, status, pdfId }
  })
})

function realTitle(kind) {
  if (kind === 'responsibility') return '安全生产责任制'
  return kind
}

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

async function loadReal() {
  const hasReal = selectedBox.value.items.some((i) => i.real)
  if (!hasReal) { realItems.value = []; return }
  loading.value = true
  try {
    const res = await request.get('/archive/responsibility/items')
    realItems.value = res.data || []
  } catch (e) {
    realItems.value = []
  } finally {
    loading.value = false
  }
}
async function loadUpload() {
  try {
    const res = await request.get(`/archive/items?boxNo=${selBoxNo.value}&year=${selYear.value}`)
    uploadItems.value = res.data || []
  } catch (e) {
    uploadItems.value = []
  }
}

watch([selectedNo, cargoType, selYear], () => { checked.value = new Set(); boxChecked.value = new Set(); loadReal(); loadUpload() })
onMounted(() => { loadReal(); loadUpload() })

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

// ---- 生成（系统生成类）----
async function doGenerate(item) {
  generating.value = true
  try {
    if (!item.isReal) { ElMessage.info('该归档项请使用「上传」留存佐证材料'); return }
    await request.post('/archive/responsibility/generate')
    ElMessage.success('已生成并归档（安全生产责任制）')
    await loadReal()
  } finally {
    generating.value = false
  }
}
async function batchGenerate(scope) {
  const bno = selBoxNo.value
  const targets = scope === 'checked'
    ? rows.value.filter((r) => checked.value.has(r.key))
    : rows.value
  const real = targets.filter((r) => r.isReal)
  if (!real.length) {
    ElMessage.info('当前选择无可系统生成的真实归档项（其余请使用「上传」按钮留存）')
    return
  }
  generating.value = true
  try {
    for (const _ of real) await request.post('/archive/responsibility/generate')
    ElMessage.success(`已生成 ${real.length} 项责任制 PDF`)
    await loadReal()
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
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${item.name}_${selYear.value}.pdf`
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
    await loadUpload()
    closeUpload()
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

// ---- PDF 预览 ----
const preview = ref(null)
function openPreview(item) {
  if (!item.pdfId) { ElMessage.warning('尚未生成/上传，无法预览'); return }
  preview.value = item
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
                <span v-if="r.isReal" class="src src-auto">系统生成</span>
                <span v-else class="src src-up">上传留存</span>
                <span v-if="r.freq" class="freq" :class="`freq-${r.freq}`">{{ FREQ_LABEL[r.freq] }}</span>
              </div>
            </div>
            <span class="st" :class="{
              'st-miss': r.status === STATUS.MISS,
              'st-wait': r.status === STATUS.WAIT,
              'st-done': r.status === STATUS.DONE,
              'st-print': r.status === STATUS.PRINT,
            }">{{ ['待上传/缺失', '待生成', '已生成/已上传', '已归档'][r.status] }}</span>
            <div class="ops">
              <el-button v-if="r.pdfId" size="small" type="primary" @click="download(r)">
                <Icon name="download" :size="13" /> 下载
              </el-button>
              <el-button v-else-if="r.isReal" size="small" @click="doGenerate(r)">
                <Icon name="plus" :size="13" /> 生成
              </el-button>
              <el-button v-else size="small" type="primary" plain @click="openUpload(r)">
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

    <!-- PDF 预览（A4 仿真） -->
    <div class="pdf-mask" :class="{ show: preview }" @click.self="preview = null">
      <div class="pdf-wrap">
        <div class="pdf-toolbar">
          <span class="t">{{ preview?.name }} · {{ selYear }} 年度</span>
          <el-button size="small" type="primary" @click="download(preview)"><Icon name="download" :size="13" /> 下载 PDF</el-button>
          <button class="x" @click="preview = null">×</button>
        </div>
        <div class="pdf-body">
          <div class="pdf-page">
            <div class="ph">
              <div class="org">道路运输安全生产管理平台</div>
              <div class="meta">{{ preview?.name }}（{{ selYear }} 年度）</div>
            </div>
            <div class="ptitle">{{ preview?.name }}</div>
            <p class="ni">本文档由系统自动生成或上传留存，供打印装盒、接受政府检查。</p>
            <p class="ni" style="color:#B91C1C">（测试中 DEV：正式版此处为完整 PDF 正文/扫描件，当前为预览占位）</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
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
.pdf-page .ptitle { text-align: center; font-size: 20px; font-weight: bold; letter-spacing: 3px; margin: 18px 0 4px; }
</style>
