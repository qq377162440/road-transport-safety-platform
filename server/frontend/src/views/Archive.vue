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

const currentYear = new Date().getFullYear()
const years = [currentYear, currentYear - 1, currentYear - 2]
const selYear = ref(currentYear)

const realItems = ref([]) // 真实后端数据（责任制等）
const loading = ref(false)
const generating = ref(false)
const checked = ref(new Set())

// 把选中盒的目录子项，与真实后端数据合并成渲染行
const rows = computed(() => {
  const map = {}
  realItems.value.forEach((it) => {
    map[it.title] = it
  })
  return selectedBox.value.items.map((it, idx) => {
    const real = it.real ? map[realTitle(it.real)] : null
    let status = STATUS.MISS
    let pdfId = null
    if (real) {
      // 后端：0=待生成 1=已生成 → 映射为 待生成/已生成
      status = real.status === 0 ? STATUS.WAIT : STATUS.DONE
      pdfId = real.pdfId
    }
    return {
      key: `${selectedBox.value.no}-${idx}`,
      name: it.name,
      freq: it.freq || '',
      isReal: !!it.real,
      realKind: it.real || '',
      status,
      pdfId,
      signedYear: status >= STATUS.DONE ? selYear.value : null,
    }
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
  // 当前仅「责任制」盒接入后端：盒一内的 责任书/承诺书 复用同一 PDF
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

watch([selectedNo, cargoType], () => { checked.value = new Set(); loadReal() })
onMounted(loadReal)

// ---- 批量勾选 ----
function toggleBox(val) {
  if (val) rows.value.forEach((r) => checked.value.add(r.key))
  else checked.value = new Set()
  checked.value = new Set(checked.value)
}
function toggleItem(key, val) {
  if (val) checked.value.add(key)
  else checked.value.delete(key)
  checked.value = new Set(checked.value)
}
const allChecked = computed(() => rows.value.length > 0 && rows.value.every((r) => checked.value.has(r.key)))

// ---- 生成 ----
async function doGenerate(item) {
  generating.value = true
  try {
    if (!item.isReal) {
      ElMessage.info('该归档项后端生成服务待接入（测试中 DEV）')
      return
    }
    await request.post('/archive/responsibility/generate')
    ElMessage.success('已生成并归档（安全生产责任制）')
    await loadReal()
  } finally {
    generating.value = false
  }
}
async function batchGenerate(scope) {
  const targets = scope === 'checked'
    ? rows.value.filter((r) => checked.value.has(r.key))
    : rows.value
  const real = targets.filter((r) => r.isReal)
  if (!real.length) {
    ElMessage.info('当前选择无可生成的真实归档项（其余为 DEV 占位）')
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
  if (!item.pdfId) { ElMessage.warning('尚未生成 PDF'); return }
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
  a.download = `安全生产责任制_${selYear.value}.pdf`
  a.click()
  URL.revokeObjectURL(url)
}
async function downloadBox() {
  const done = rows.value.filter((r) => r.pdfId)
  if (!done.length) { ElMessage.warning('本盒暂无已生成 PDF'); return }
  for (const r of done) await download(r)
}
async function downloadAll() {
  ElMessage.info('全量下载：跨盒逐一下载（测试中 DEV）')
}

function selectBox(no) { selectedNo.value = no }

// ---- PDF 预览 ----
const preview = ref(null)
function openPreview(item) {
  if (!item.pdfId) { ElMessage.warning('尚未生成，无法预览'); return }
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
      <div class="sc miss"><div class="v">{{ summary.miss }}</div><div class="k">缺失（无数据）</div></div>
      <div class="sc wait"><div class="v">{{ summary.wait }}</div><div class="k">待生成</div></div>
      <div class="sc done"><div class="v">{{ summary.done }}</div><div class="k">已生成 PDF</div></div>
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
      <!-- 左侧档案盒目录树 -->
      <div class="left">
        <div class="tree">
          <div class="tt">档案盒目录 <span class="cargo">{{ cargoType }} · {{ boxes.length }} 盒</span></div>
          <div
            v-for="b in boxes"
            :key="b.no"
            class="box-item"
            :class="{ on: b.no === selectedNo }"
            @click="selectBox(b.no)"
          >
            <span class="bx-dot dot-green" />
            <span class="bx-name">盒{{ b.no }} {{ b.name }}</span>
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
            <el-button @click="downloadAll"><Icon name="bolt" :size="15" /> 全量下载</el-button>
          </div>
        </div>

        <div class="list-toolbar">
          <label class="box-selall">
            <input type="checkbox" :checked="allChecked" @change="toggleBox($event.target.checked)" />
            全选本盒
          </label>
          <el-button size="small" type="primary" :loading="generating" @click="batchGenerate('checked')">
            <Icon name="fileText" :size="14" /> 一键批量生成（{{ checked.size }}）
          </el-button>
        </div>

        <div v-loading="loading" class="item-list">
          <div v-for="(r, idx) in rows" :key="r.key" class="item-row">
            <span class="item-idx">{{ idx + 1 }}</span>
            <label class="item-check">
              <input type="checkbox" :checked="checked.has(r.key)" @change="toggleItem(r.key, $event.target.checked)" />
            </label>
            <div class="nm">
              <span class="n clickable" @click="openPreview(r)">{{ r.name }}</span>
              <div class="d">
                <span v-if="r.isReal" class="src src-auto">系统生成</span>
                <span v-else class="src src-up">待接入</span>
                <span v-if="r.freq" class="freq" :class="`freq-${r.freq}`">{{ FREQ_LABEL[r.freq] }}</span>
              </div>
            </div>
            <span class="st" :class="{
              'st-miss': r.status === STATUS.MISS,
              'st-wait': r.status === STATUS.WAIT,
              'st-done': r.status === STATUS.DONE,
              'st-print': r.status === STATUS.PRINT,
            }">{{ ['缺失(无数据)', '待生成', '已生成', '已归档'][r.status] }}</span>
            <div class="ops">
              <el-button v-if="r.status === STATUS.DONE || r.status === STATUS.PRINT" size="small" type="primary" @click="download(r)">
                <Icon name="download" :size="13" /> 下载
              </el-button>
              <el-button v-else size="small" @click="doGenerate(r)">
                <Icon name="plus" :size="13" /> 生成
              </el-button>
            </div>
          </div>
          <div v-if="!rows.length" class="empty-state"><div class="big">📂</div>该盒暂无归档项</div>
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
            <p class="ni">本文档由系统自动生成，供打印装盒、接受政府检查。</p>
            <p class="ni" style="color:#B91C1C">（测试中 DEV：正式版此处为完整 PDF 正文，当前为预览占位）</p>
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
.box-item { padding: 11px 16px; cursor: pointer; border-left: 3px solid transparent; display: flex; align-items: center; gap: 10px; transition: .12s; }
.box-item:hover { background: var(--brand-50); }
.box-item.on { background: var(--brand-50); border-left-color: var(--brand-500); }
.box-item .bx-name { font-size: 13.5px; font-weight: 600; }
.box-item .bx-count { font-size: 12px; color: var(--text-3); margin-left: auto; }
.box-item .bx-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }

.box-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.box-head h2 { font-size: 18px; margin: 0 0 2px; }
.box-head .desc { font-size: 12px; color: var(--text-3); }
.head-actions { display: flex; gap: 8px; flex-shrink: 0; }

.list-toolbar { display: flex; align-items: center; gap: 14px; padding: 10px 4px; border-bottom: 1px solid var(--border); margin-bottom: 8px; }
.box-selall { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: var(--text-2); cursor: pointer; }

.item-list { background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-lg); overflow: hidden; }
.item-row { display: flex; align-items: center; gap: 12px; padding: 13px 16px; border-bottom: 1px solid var(--border); }
.item-row:last-child { border-bottom: none; }
.item-row .item-idx { width: 22px; height: 22px; flex-shrink: 0; border-radius: 6px; background: var(--brand-500); color: #fff; font-size: 12px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.item-row .item-check input { width: 16px; height: 16px; accent-color: var(--brand-600); cursor: pointer; }
.item-row .nm { flex: 1; min-width: 0; }
.item-row .nm .n { font-size: 14.5px; font-weight: 600; cursor: pointer; }
.item-row .nm .n.clickable:hover { color: var(--brand-600); text-decoration: underline; }
.item-row .nm .d { margin-top: 4px; display: flex; gap: 6px; align-items: center; }
.src { font-size: 11px; padding: 2px 8px; border-radius: 8px; white-space: nowrap; }
.src-auto { background: var(--brand-100); color: var(--brand-600); }
.src-up { background: #F1F5F9; color: var(--text-3); }
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
