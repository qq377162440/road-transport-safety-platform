<script setup>
/** C6 自定义文件签署 · 完整流程闭环（设计 21 §7.12 / 详细设计 09 §7.12）
 *  新建任务(自定义名称+地方模板+归属档案盒) → 拖拽放置盖章/签名锚点
 *  → 一键签署(复用印章引擎+签字库，支持打印手签) → 归档到指定档案盒 */
import { ref, onMounted } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'

const ov = ref({ company: '—', taskStatus: {}, stats: {}, boxes: [], tasks: [] })
const posts = ref([])
const loading = ref(false)
const SCALE = 0.62 // 画布视觉缩放；锚点坐标仍为 A4 794×1123 逻辑像素，与后端 PDF 一致

async function load() {
  loading.value = true
  try {
    const r = await request.get('/flow/sign/overview')
    ov.value = r.data || ov.value
    const o = await request.get('/flow/sign/options').catch(() => ({ data: {} }))
    posts.value = (o.data && o.data.posts) || []
  } catch (e) { /* 概览失败保持占位 */ }
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
    const u = URL.createObjectURL(blob)
    window.open(u, '_blank')
    setTimeout(() => URL.revokeObjectURL(u), 60000)
  } catch (e) { ElMessage.error('PDF 打开失败') }
}

const ST = { draft: ['草稿', 'tag-gray'], pending: ['待签署', 'tag-blue'], signed: ['已签署', 'tag-green'] }
const stText = (s) => ((ST[s] || [s])[0])
const stClass = (s) => ((ST[s] || ['', 'tag-gray'])[1])
const boxLabel = (t) => t.boxName || t.archive_box || '—'
const d16 = (v) => v ? String(v).slice(0, 16).replace('T', ' ') : '—'

// ---------- 新建签署任务 ----------
const createDlg = ref(false)
const createForm = ref({ title: '', sourcePdf: '', archiveBox: '' })
function openCreate() {
  createForm.value = { title: '', sourcePdf: '', archiveBox: (ov.value.boxes[0] || {}).code || '' }
  createDlg.value = true
}
async function submitCreate() {
  if (!createForm.value.title.trim()) { ElMessage.warning('请填写文件名称'); return }
  if (!createForm.value.archiveBox) { ElMessage.warning('请选择归属档案盒'); return }
  const r = await act('/flow/sign/task', 'post', { ...createForm.value }, '创建签署任务')
  if (r) { createDlg.value = false; ElMessage.info('已创建，下一步点「放置签署模块」') }
}

// ---------- 锚点编辑（可视化拖拽） ----------
const anchorDlg = ref(false)
const curTask = ref(null)
const anchors = ref([])
const drag = ref(null)

async function openAnchors(t) {
  const r = await request.get('/flow/sign/task/' + String(t.id))
  const d = r.data || {}
  curTask.value = { ...t, ...d }
  let arr = []
  try { arr = JSON.parse(d.anchors || '[]') } catch (e) { arr = [] }
  anchors.value = (Array.isArray(arr) ? arr : []).map(a => ({ ...a }))
  anchorDlg.value = true
}
function addAnchor(type) {
  const n = anchors.value.length
  if (type === 'seal') {
    anchors.value.push({ type: 'seal', page: 1, x: 500, y: 840 + n * 45, scale: 1.0, sealType: 'company', required: true })
  } else {
    anchors.value.push({ type: 'sign', page: 1, x: 110, y: 900 + n * 45, scale: 1.0, post: (posts.value[0] || {}).post || '主要负责人', signType: 'history', required: true })
  }
}
function delAnchor(i) { anchors.value.splice(i, 1) }
function startDrag(a, e) {
  e.preventDefault(); e.stopPropagation()
  drag.value = { a, sx: e.clientX, sy: e.clientY, ox: Number(a.x) || 0, oy: Number(a.y) || 0 }
  window.addEventListener('mousemove', onDrag)
  window.addEventListener('mouseup', endDrag)
}
function onDrag(e) {
  const d = drag.value
  if (!d) return
  d.a.x = Math.max(0, Math.min(700, Math.round(d.ox + (e.clientX - d.sx) / SCALE)))
  d.a.y = Math.max(0, Math.min(1040, Math.round(d.oy + (e.clientY - d.sy) / SCALE)))
}
function endDrag() {
  drag.value = null
  window.removeEventListener('mousemove', onDrag)
  window.removeEventListener('mouseup', endDrag)
}
async function saveAnchors() {
  if (!anchors.value.length) { ElMessage.warning('请至少放置一个盖章或签名模块'); return }
  const r = await act(`/flow/sign/task/${String(curTask.value.id)}/anchors`, 'put', { anchors: anchors.value }, '保存签署模块', false)
  if (r) {
    anchorDlg.value = false
    await load()
    ElMessage.success(`已放置 ${r.anchorCount} 个锚点（盖章 ${r.sealCount} / 签名 ${r.signCount}）`)
  }
}

// ---------- 一键签署 / 归档 ----------
async function doSign(t) {
  const r = await act(`/flow/sign/task/${String(t.id)}/sign`, 'post', {}, '一键签署', false)
  if (r) {
    ElMessage.success(`合成完成：盖章 ${r.sealCount} 处 / 签名 ${r.signCount} 处 / 打印手写位 ${r.handCount || 0} 处`)
    await load()
    if (r.pdfId) openPdf(r.pdfId)
  }
}
async function doArchive(t) {
  const r = await act(`/flow/sign/task/${String(t.id)}/archive`, 'post', {}, '归档', false)
  if (r) { ElMessage.success(`已归档到「${r.boxName || r.box}」`); await load() }
}
</script>

<template>
  <div class="bm-page">
    <div class="pf-head">
      <div>
        <div class="pf-title">{{ ov.company }} · 自定义文件签署</div>
        <div class="pf-sub">地方性/区域性文件不硬编码：服务商上传当地模板、自定义名称、选择档案盒、拖拽放置盖章与签名模块</div>
      </div>
      <button class="btn btn-primary" @click="openCreate">+ 新建签署任务</button>
    </div>

    <div class="pf-stats">
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).tasks || 0 }}</div><div class="l">签署任务</div></div>
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).pending || 0 }}</div><div class="l">待签署</div></div>
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).signed || 0 }}</div><div class="l">已签署</div></div>
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).archived || 0 }}</div><div class="l">已归档</div></div>
      <div class="pf-card"><div class="n">{{ (ov.stats || {}).boxes || 0 }}</div><div class="l">可选档案盒</div></div>
    </div>

    <div class="pf-tip" style="margin:6px 20px 12px">
      覆盖危货 P2 地方要求（剧毒/易制爆长时间停车报告、限行区域管控、剧毒化学品道路运输通行证等）。
      盖章复用印章引擎（企业章/打印手盖），签名复用签字库（历史签字/线上签/打印手签），成品自动回写电子档案柜。
    </div>

    <div style="overflow:auto">
      <table class="table bm-table" style="min-width:1000px">
        <thead><tr><th>文件名称</th><th>归属档案盒</th><th>锚点</th><th>状态</th><th>签署人 / 时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="t in (ov.tasks || [])" :key="t.id">
            <td><b>{{ t.title }}</b><div class="cell-sub">模板：{{ t.source_pdf || '（未上传，使用系统合成页）' }}</div></td>
            <td>{{ boxLabel(t) }}<div class="cell-sub">{{ t.archive_box }}</div></td>
            <td>{{ t.anchorCount || 0 }} 个</td>
            <td>
              <span class="tag" :class="stClass(t.status)">{{ t.statusName || stText(t.status) }}</span>
              <span v-if="t.archived" class="tag tag-green" style="margin-left:5px">已归档</span>
            </td>
            <td>{{ t.signer || '—' }}<div class="cell-sub">{{ d16(t.signed_at) }}</div></td>
            <td style="white-space:nowrap">
              <a v-if="t.status !== 'signed'" class="btn btn-outline btn-sm" @click="openAnchors(t)">放置签署模块</a>
              <a v-if="t.status === 'pending'" class="btn btn-primary btn-sm" @click="doSign(t)">一键签署</a>
              <a v-if="t.pdfId" class="btn btn-outline btn-sm" @click="openPdf(t.pdfId)">查看成品</a>
              <a v-if="t.status === 'signed'" class="btn btn-primary btn-sm" @click="doArchive(t)">归档到档案盒</a>
              <a v-if="t.status === 'signed'" class="btn btn-outline btn-sm" @click="doSign(t)">重新签署</a>
            </td>
          </tr>
          <tr v-if="!(ov.tasks || []).length"><td colspan="6" style="text-align:center;color:var(--text-3);padding:26px">暂无签署任务，点右上「新建签署任务」</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 新建签署任务 -->
    <el-dialog v-model="createDlg" title="新建自定义文件签署任务" width="520px">
      <div class="form-row"><label>文件名称</label><input v-model="createForm.title" placeholder="如：剧毒化学品道路运输通行证（本地版）" /></div>
      <div class="form-row"><label>归属档案盒</label>
        <select v-model="createForm.archiveBox">
          <option v-for="b in (ov.boxes || [])" :key="b.code" :value="b.code">{{ b.name }}（{{ b.code }}）</option>
        </select>
      </div>
      <div class="form-row"><label>地方模板</label><input v-model="createForm.sourcePdf" placeholder="MinIO 对象名，可留空（系统生成合成页）" /></div>
      <div class="pf-tip" style="margin:8px 0 0">模板由服务商按当地监管要求上传，系统不预设固定模板清单；留空时以标准 A4 合成页承载盖章/签名锚点。</div>
      <template #footer><button class="btn btn-primary" @click="submitCreate">创建任务</button></template>
    </el-dialog>

    <!-- 锚点编辑：A4 画布拖拽 + 属性微调 -->
    <el-dialog v-model="anchorDlg" :title="'放置签署模块 · ' + (curTask ? curTask.title : '')" width="950px" top="5vh">
      <div class="anchor-bar">
        <button class="btn btn-outline btn-sm" @click="addAnchor('seal')">+ 盖章模块</button>
        <button class="btn btn-outline btn-sm" @click="addAnchor('sign')">+ 签名模块</button>
        <span class="anchor-hint">在左侧 A4 页面上直接拖动模块调整位置；右侧可微调类型、岗位与签名方式。共 {{ anchors.length }} 个锚点</span>
      </div>
      <div class="anchor-body">
        <div class="canvas-view">
          <div class="canvas">
            <div class="canvas-title">{{ ov.company }}</div>
            <div class="canvas-doc">{{ curTask ? curTask.title : '' }}</div>
            <div class="canvas-note">（自定义文件 · 服务商上传地方模板 + 盖章/签名锚点合成）</div>
            <div v-for="(a, i) in anchors" :key="i"
                 :class="['canvas-anchor', a.type === 'seal' ? 'ca-seal' : 'ca-sign']"
                 :style="{ left: a.x + 'px', top: a.y + 'px' }"
                 @mousedown="startDrag(a, $event)">
              <template v-if="a.type === 'seal'">{{ a.sealType === 'blank' ? '（此处加盖企业公章）' : '企业公章' }}</template>
              <template v-else>{{ a.signType === 'blank' ? '签字（打印后手写）' : '签字' }}：{{ a.post || '负责人' }}</template>
            </div>
            <div class="canvas-foot">按锚点合成盖章/签名 · 归档盒：{{ curTask ? boxLabel(curTask) : '' }}</div>
          </div>
        </div>
        <div class="anchor-list">
          <div v-for="(a, i) in anchors" :key="i" class="anchor-item">
            <div class="ai-head"><b>#{{ i + 1 }} {{ a.type === 'seal' ? '盖章模块' : '签名模块' }}</b><a class="ai-del" @click="delAnchor(i)">删除</a></div>
            <div class="ai-row"><label>X</label><input v-model.number="a.x" type="number" /><label>Y</label><input v-model.number="a.y" type="number" /></div>
            <div v-if="a.type === 'seal'" class="ai-row">
              <label>盖章方式</label>
              <select v-model="a.sealType"><option value="company">自动盖企业章</option><option value="blank">打印后手盖</option></select>
            </div>
            <div v-else class="ai-col">
              <div class="ai-row"><label>岗位</label>
                <select v-model="a.post"><option v-for="p in posts" :key="p.post" :value="p.post">{{ p.post }}</option><option value="主要负责人">主要负责人</option></select>
              </div>
              <div class="ai-row"><label>签名方式</label>
                <select v-model="a.signType"><option value="history">历史签字</option><option value="online">线上签</option><option value="blank">打印手签</option></select>
              </div>
            </div>
          </div>
          <div v-if="!anchors.length" class="anchor-empty">尚未放置模块，点上方「+ 盖章模块 / + 签名模块」</div>
        </div>
      </div>
      <template #footer><button class="btn btn-primary" @click="saveAnchors">保存锚点并进入待签署</button></template>
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
.table { width:100%;border-collapse:collapse;margin:0 20px;max-width:calc(100% - 40px) }
.bm-table td,.bm-table th { border:1px solid #e3e3e3;padding:8px 10px;font-size:13px }
.bm-table th { background:#f6f7f9 }
.cell-sub { color:var(--text-3);font-size:11px;margin-top:3px }
.tag { display:inline-block;padding:2px 8px;border-radius:10px;font-size:12px }
.tag-gray { background:#eee;color:#666 } .tag-blue { background:#e3f0ff;color:#1a73e8 } .tag-green { background:#e6f7ec;color:#1a8a4a }
.btn { padding:7px 14px;border-radius:8px;border:1px solid #d0d7de;background:#fff;cursor:pointer;font-size:13px }
.btn-primary { background:#1a73e8;color:#fff;border-color:#1a73e8 }
.btn-outline { background:#fff;color:#1a73e8;border-color:#1a73e8 }
.btn-sm { padding:4px 10px;font-size:12px;margin-right:6px }
.form-row { display:flex;align-items:center;margin:10px 0 }
.form-row label { width:90px;color:var(--text-3);font-size:13px }
.form-row input,.form-row select,.form-row textarea { flex:1;padding:7px 10px;border:1px solid #d0d7de;border-radius:7px;font-size:13px }
.pf-tip { color:#a05a00;background:#fff7e6;border:1px solid #ffe0a3;border-radius:7px;padding:8px 12px;font-size:12px;line-height:1.7 }
/* 锚点编辑器 */
.anchor-bar { display:flex;align-items:center;gap:8px;padding:0 0 12px }
.anchor-hint { color:var(--text-3);font-size:12px;margin-left:6px }
.anchor-body { display:flex;gap:16px;align-items:flex-start }
.canvas-view { width:492px;height:696px;overflow:hidden;border:1px solid #d0d7de;border-radius:6px;flex:none;background:#fff }
.canvas { position:relative;width:794px;height:1123px;transform:scale(.62);transform-origin:top left;background:#fff;padding:20px;box-sizing:border-box;font-family:'WenQuanYi Zen Hei',sans-serif }
.canvas-title { text-align:center;font-size:22px;font-weight:700;letter-spacing:2px;padding-top:40px }
.canvas-doc { text-align:center;font-size:20px;font-weight:700;margin-top:24px }
.canvas-note { text-align:center;color:#777;font-size:14px;margin-top:18px }
.canvas-anchor { position:absolute;font-size:15px;cursor:move;user-select:none;padding:6px 12px;border-radius:6px;white-space:nowrap }
.ca-seal { border:2px solid #c0392b;color:#c0392b;border-radius:50%;width:120px;height:120px;display:flex;align-items:center;justify-content:center;text-align:center;background:#fffafa }
.ca-sign { border:1px dashed #c0392b;color:#c0392b;background:#fff7f7 }
.canvas-foot { position:absolute;bottom:20px;left:20px;right:20px;border-top:1px solid #ddd;padding-top:8px;font-size:12px;color:#777 }
.anchor-list { flex:1;max-height:696px;overflow:auto;min-width:340px }
.anchor-item { border:1px solid #e3e3e3;border-radius:8px;padding:10px 12px;margin-bottom:10px;background:#fafbfc }
.ai-head { display:flex;justify-content:space-between;font-size:13px;margin-bottom:8px }
.ai-del { color:#c0392b;cursor:pointer;font-size:12px }
.ai-row { display:flex;align-items:center;gap:6px;margin:6px 0 }
.ai-row label { width:62px;color:var(--text-3);font-size:12px;flex:none }
.ai-row input,.ai-row select { flex:1;min-width:0;padding:5px 8px;border:1px solid #d0d7de;border-radius:6px;font-size:12px }
.anchor-empty { color:var(--text-3);font-size:13px;text-align:center;padding:40px 10px }
</style>
