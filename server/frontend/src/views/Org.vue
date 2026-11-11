<script setup>
/**
 * 安全管理架构（按原型 prototype/pc/org.html + assets/org_chart.js 正式版）
 *
 * - 架构图与 OrgChart 共享生成器同源：只渲染启用岗位、禁用中间层自动上提、防环、按职能序号排序
 * - 数据来自 PC 端真实表 org_post / org_member / person / tenant，不再用 localStorage 假数据
 * - 右键节点：更换人员 / 编辑岗位 / 关联制度与责任书 / 禁用恢复
 * - 下载 A4 横向架构图 PDF（后端真实文件）+ 打印另存为 PDF
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import Icon from '@/components/Icon.vue'

const router = useRouter()
const chart = ref(null)
const loading = ref(false)
const wrapEl = ref(null)

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]))
}

async function load() {
  loading.value = true
  try {
    const res = await request.get('/org/chart')
    chart.value = res.data || res
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------- 架构图 HTML（与原型 OC.html 同源）
function nodeHtml(n) {
  const person = n.person
    ? `<b>${esc(n.person)}</b>${n.group > 1 ? ' 等' + n.group + '人' : ''}`
    : '<span class="ocg-dim">待配置</span>'
  const fl = n.label && n.label !== n.name ? `<span class="ocg-fl">${esc(n.label)}</span>` : ''
  const mg = n.merged && n.merged.length
    ? `<div class="ocg-mg"><div class="ocg-m">兼并：${esc(n.merged.join('、'))}</div></div>` : ''
  // 架构图不显示「强制」角标：强制岗位只在「岗位信息管理」页标注，图面与打印件保持干净
  return `<div class="ocg-node${n.funcKey === 'gm' ? ' ocg-gm' : ''}" data-post="${esc(n.id)}">` +
    `<div class="ocg-t">${esc(n.name)}${fl}</div>` +
    `<div class="ocg-p">${person}</div>${mg}</div>`
}
function liHtml(n) {
  const kids = n.children || []
  const ul = kids.length ? `<ul>${kids.map(liHtml).join('')}</ul>` : ''
  return `<li>${nodeHtml(n)}${ul}</li>`
}
const chartHtml = computed(() => {
  const c = chart.value
  if (!c) return ''
  const roots = c.tree || []
  return `<div class="ocg"><ul class="ocg-tree"><li>` +
    `<div class="ocg-node ocg-company"><div class="ocg-t">${esc(c.company || '企业名称')}</div></div>` +
    (roots.length ? `<ul>${roots.map(liHtml).join('')}</ul>` : '') +
    `</li></ul></div>`
})

// ---------------------------------------------------------------- 右键菜单
const ctx = reactive({ show: false, x: 0, y: 0, post: null })
function hideCtx() { ctx.show = false }
function findNode(id) {
  return (chart.value?.nodes || []).find((n) => String(n.id) === String(id)) || null
}
function onContextMenu(e) {
  const node = e.target.closest ? e.target.closest('.ocg-node[data-post]') : null
  if (!node) return
  e.preventDefault()
  e.stopPropagation()
  const p = findNode(node.getAttribute('data-post'))
  if (!p) return
  ctx.post = p
  ctx.show = true
  const mw = 210, mh = 190
  ctx.x = Math.max(8, Math.min(e.clientX, window.innerWidth - mw - 8))
  ctx.y = Math.max(8, Math.min(e.clientY, window.innerHeight - mh - 8))
}

// ---------------------------------------------------------------- 弹窗状态
const dlg = reactive({ person: false, edit: false, docs: false, docPrev: false })
const cur = ref(null)
const personId = ref('')
const docsSel = ref([])
const respRows = ref([])
const form = reactive({
  name: '', parentId: '', mergedKeys: [], permissions: '', goals: '', duties: '', assess: '',
})

const personInfo = computed(() =>
  (chart.value?.people || []).find((u) => String(u.id) === String(personId.value)))

/** 自身 + 全部下级 ID（防环：上级不能选自己或自己的下级） */
const bannedIds = computed(() => {
  const ban = new Set()
  if (!cur.value || !chart.value) return ban
  const self = String(cur.value.id)
  ban.add(self)
  const nodes = chart.value.nodes || []
  const walk = (pid) => nodes.filter((n) => String(n.parentId || '') === String(pid))
    .forEach((n) => { ban.add(String(n.id)); walk(n.id) })
  walk(self)
  return ban
})
const parentOptions = computed(() => (chart.value?.allPosts || [])
  .filter((p) => p.enabled && !bannedIds.value.has(String(p.id))))
const canMerge = computed(() => cur.value && !['gm', 'safe'].includes(cur.value.funcKey))
const mergeOptions = computed(() => (chart.value?.allPosts || [])
  .filter((p) => !['gm', 'safe'].includes(p.funcKey) && !bannedIds.value.has(String(p.id))))

function openPerson() {
  hideCtx()
  cur.value = ctx.post
  personId.value = String(cur.value.personId || '')
  dlg.person = true
}
async function savePerson() {
  await request.put(`/org/chart/post/${cur.value.id}/person`, { personId: personId.value })
  dlg.person = false
  ElMessage.success(personId.value ? '岗位人员已更新，已联动一人一档 / 责任书待签' : '岗位人员已清空（待配置）')
  await load()
  syncOrgArchive()
}

function openEdit() {
  hideCtx()
  cur.value = ctx.post
  const p = cur.value
  form.name = p.name
  form.parentId = String(p.rawParentId || p.parentId || '')
  form.mergedKeys = [...(p.mergedKeys || [])]
  form.permissions = p.permissions || ''
  form.goals = p.goals || ''
  form.duties = p.duties || ''
  form.assess = p.assess || ''
  dlg.edit = true
}
async function saveEdit() {
  if (!form.name.trim()) { ElMessage.warning('请填写岗位名称'); return }
  await request.put(`/org/chart/post/${cur.value.id}`, {
    name: form.name.trim(),
    parentId: form.parentId,
    mergedKeys: canMerge.value ? form.mergedKeys : (cur.value.mergedKeys || []),
    permissions: form.permissions,
    goals: form.goals,
    duties: form.duties,
    assess: form.assess,
    docs: cur.value.docs || [],
  })
  dlg.edit = false
  ElMessage.success('岗位已保存，制度与责任书已双向联动')
  await load()
  syncOrgArchive()
}

function openDocs() {
  hideCtx()
  cur.value = ctx.post
  docsSel.value = [...(cur.value.docs || [])]
  dlg.docs = true
}
async function saveDocs() {
  // 局部保存：只提交 docs，后端保留岗位名称 / 上级 / 兼并 / 目标职责考核原值
  await request.put(`/org/chart/post/${cur.value.id}`, { docs: docsSel.value })
  dlg.docs = false
  ElMessage.success(`已关联 ${docsSel.value.length} 项制度 / 责任书`)
  await load()
}

async function togglePost() {
  hideCtx()
  const p = ctx.post
  if (p.required) { ElMessage.warning('总经理、安全员、监控员为强制岗位，不可禁用'); return }
  try {
    await ElMessageBox.confirm(
      `确认禁用岗位「${p.name}」？禁用后节点立即从架构图消失，并从安全生产责任制等制度内容中剔除。`,
      '提示', { type: 'warning' },
    )
  } catch { return }
  await request.put(`/org/post/${p.id}/toggle`)
  ElMessage.success(`岗位「${p.name}」已禁用，制度内容已剔除`)
  await load()
  syncOrgArchive()
}

async function openDocPrev() {
  const res = await request.get('/org/chart/responsibility')
  respRows.value = res.data || []
  dlg.docPrev = true
}

// ---------------------------------------------------------------- 顶部动作
function goPost() { router.push('/post') }

// ---- 组织架构变化 → 电子档案柜联动（用户 2026-11-11 需求）----
// 《安全组织架构》《关于成立安全生产领导小组的通知》《人员任命书》三份文件的内容
// 直接由岗位树 / 岗位人员渲染，架构一变即应重新生成；历史版本可选择是否留存。
async function syncOrgArchive() {
  let st = null
  try { st = (await request.get('/org/chart/archive-status')).data || {} } catch { return }
  if (!st.hasAny) return // 档案柜还没有这三份文件，首次由企业在档案柜自行生成，不打扰
  let keepHistory = true
  try {
    await ElMessageBox.confirm(
      '组织架构刚发生变化，档案柜中的《安全组织架构》《关于成立安全生产领导小组的通知》《人员任命书》已不是最新内容。\n\n是否立即按当前架构自动重新生成？\n\n· 「保留历史版本」可在档案柜下载旧版对照；\n· 「不保留」会把本年度旧版本下架。',
      '联动更新档案柜文件',
      {
        confirmButtonText: '更新并保留历史版本',
        cancelButtonText: '更新（不保留历史）',
        distinguishCancelAndClose: true,
        type: 'warning',
        customStyle: { whiteSpace: 'pre-line' },
      },
    )
  } catch (action) {
    if (action === 'cancel') keepHistory = false
    else return // 关闭 = 暂不更新
  }
  try {
    const r = (await request.post('/org/chart/regen-archive', { keepHistory })).data
    const n = (r.generated || []).length
    const extra = keepHistory ? '，历史版本已留存可下载对照'
      : (r.archivedOld ? `，已下架 ${r.archivedOld} 份旧版本` : '')
    ElMessage.success(`已按当前架构自动更新档案柜 ${n} 份文件${extra}`)
  } catch { /* 拦截器已统一提示 */ }
}

async function downloadPdf() {
  try {
    const r = await request.get('/org/chart/pdf', { responseType: 'blob' })
    const blob = r instanceof Blob ? r : (r?.data instanceof Blob ? r.data : new Blob([r]))
    if (blob.type && !blob.type.includes('pdf')) {
      const txt = await blob.text()
      ElMessage.error('PDF 生成失败：' + txt.slice(0, 120))
      return
    }
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = '安全管理组织架构.pdf'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    setTimeout(() => URL.revokeObjectURL(url), 4000)
    ElMessage.success('架构图 PDF 已下载（A4 横向，按当前架构实时生成）')
  } catch (e) {
    // 请求拦截器已统一提示
  }
}

async function printChart() {
  try {
    const r = await request.get('/org/chart/print', { responseType: 'text' })
    const html = typeof r === 'string' ? r : (r?.data || '')
    const w = window.open('', '_blank')
    if (!w) { ElMessage.warning('浏览器拦截了打印窗口，请允许弹出窗口后重试'); return }
    w.document.open()
    w.document.write(html)
    w.document.close()
  } catch (e) {
    // 请求拦截器已统一提示
  }
}

async function resetOrg() {
  try {
    await ElMessageBox.confirm(
      '确认重置组织架构演示数据？将按当前模板恢复启用岗位、层级关系、岗位人员与制度关联。',
      '提示', { type: 'warning' },
    )
  } catch { return }
  await request.post('/org/chart/reset')
  ElMessage.success('架构演示数据已重置')
  await load()
  syncOrgArchive()
}

onMounted(() => {
  document.addEventListener('click', hideCtx)
  document.addEventListener('scroll', hideCtx, true)
  load()
})
onBeforeUnmount(() => {
  document.removeEventListener('click', hideCtx)
  document.removeEventListener('scroll', hideCtx, true)
})
</script>

<template>
  <div class="pc-page org-page" v-loading="loading">
    <div class="pc-header">
      <div>
        <h1>安全管理架构</h1>
        <div class="desc">
          公司 → 总经理 → 各岗位（按上级关系组树）· 与「岗位信息管理」同源联动 · 当前模板：<b>{{ chart?.tplName }}</b>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-outline" @click="goPost"><Icon name="users" :size="15" /> 岗位信息管理</button>
        <button class="btn btn-outline" @click="openDocPrev"><Icon name="fileText" :size="15" /> 责任制联动预览</button>
        <button class="btn btn-primary" @click="downloadPdf"><Icon name="download" :size="15" /> 下载架构图 PDF</button>
        <button class="btn btn-outline" @click="printChart"><Icon name="printer" :size="15" /> 打印 / 另存为 PDF</button>
        <button class="btn btn-outline" @click="resetOrg"><Icon name="undo" :size="15" /> 重置演示数据</button>
      </div>
    </div>

    <div class="org-hint">
      💡 <b>右键单击岗位节点</b>：① <b>更换岗位人员</b>（从系统已录入人员选取）；② <b>编辑岗位</b>（岗位名称自定义、调整上级层级关系、兼并其他岗位职能、目标 / 职责 / 考核标准——岗位职能由系统设定，企业侧不可更改）；③ <b>关联制度与责任书</b>。<b>已禁用岗位不再出现在架构图中</b>，其启用下级自动上提到最近的启用上级，保证层级不断链；制度与架构<b>双向联动</b>，禁用岗位自动从制度内容中剔除。本图与电子档案柜「安全管理组织架构」归档件同源，可一键下载 PDF。
    </div>

    <div class="card" style="padding:0">
      <div class="org-stat">
        <span>共 <b>{{ chart?.enabledCount ?? 0 }}</b> 个启用岗位</span>
        <span class="tag-off">{{ chart?.disabledCount ?? 0 }} 个已禁用岗位不显示</span>
        <span class="tag-up" v-if="chart?.lifted">{{ chart.lifted }} 个岗位已上提至最近的启用上级</span>
        <span class="stat-right">
          恢复禁用岗位请前往 <a @click="goPost">岗位信息管理</a>；本图与电子档案柜归档 PDF 同源
        </span>
      </div>
      <div class="org-wrap" ref="wrapEl" @contextmenu="onContextMenu" v-html="chartHtml"></div>
      <div v-if="chart && !chart.tree?.length" class="org-empty">暂无启用岗位，请前往「岗位信息管理」恢复岗位。</div>
    </div>

    <!-- 右键菜单 -->
    <div class="org-ctx" :class="{ show: ctx.show }" :style="{ left: ctx.x + 'px', top: ctx.y + 'px' }">
      <div class="ci head">{{ ctx.post?.name }} · {{ ctx.post?.person || '待配置' }}</div>
      <div class="ci" @click.stop="openPerson"><Icon name="user" :size="15" /> 更换岗位人员</div>
      <div class="ci" @click.stop="openEdit"><Icon name="edit" :size="15" /> 编辑岗位（层级 / 兼并 / 职责考核）</div>
      <div class="ci" @click.stop="openDocs"><Icon name="link" :size="15" /> 关联制度与责任书</div>
      <div class="ci" v-if="ctx.post && !ctx.post.required" @click.stop="togglePost">
        <Icon name="x" :size="15" /> 禁用该岗位
      </div>
    </div>

    <!-- 更换岗位人员 -->
    <el-dialog v-model="dlg.person" title="更换岗位人员" width="460px" class="org-dialog" :close-on-click-modal="false">
      <div class="org-lbl">岗位</div>
      <div class="org-inp org-readonly">{{ cur?.name }}（{{ cur?.label }}）</div>
      <div class="org-lbl org-req">选择人员（系统已录入人员，无需手动输入）</div>
      <select class="org-inp" v-model="personId">
        <option value="">— 待配置 —</option>
        <option v-for="u in chart?.people || []" :key="u.id" :value="String(u.id)">
          {{ u.name }}（{{ u.phone }}）
        </option>
      </select>
      <div class="org-note">
        {{ personInfo ? '手机号 ' + personInfo.phone + ' · 小程序账号已开通 · 一人一档自动挂接本岗位' : '' }}
      </div>
      <template #footer>
        <button class="btn btn-outline" @click="dlg.person = false">取消</button>
        <button class="btn btn-primary" @click="savePerson">确认更换</button>
      </template>
    </el-dialog>

    <!-- 编辑岗位 -->
    <el-dialog v-model="dlg.edit" :title="'编辑岗位 · ' + (cur?.name || '')" width="620px" class="org-dialog" :close-on-click-modal="false">
      <div class="org-grid">
        <div>
          <div class="org-lbl org-req">岗位名称（可自定义）</div>
          <input class="org-inp" v-model="form.name" />
        </div>
        <div>
          <div class="org-lbl">岗位职能（系统设定 · 企业侧不可更改）</div>
          <input class="org-inp org-readonly" :value="cur?.label" readonly />
          <div class="org-note">职能关联岗位权限、安全目标、职责与考核标准模板</div>
        </div>
        <div class="span2">
          <div class="org-lbl">上级岗位（调整后即调整岗位层级关系）</div>
          <select class="org-inp" v-model="form.parentId">
            <option value="">— 直属公司（顶层） —</option>
            <option v-for="p in parentOptions" :key="p.id" :value="String(p.id)">{{ p.name }}</option>
          </select>
        </div>
      </div>
      <div v-if="canMerge">
        <div class="org-lbl">
          兼并其他岗位职能<span class="org-sub">（兼并后对应岗位停用，其职能与制度内容并入本岗位；总经理、安全员不可被兼并）</span>
        </div>
        <div class="org-mg-grid">
          <label v-for="m in mergeOptions" :key="m.funcKey" :class="{ on: form.mergedKeys.includes(m.funcKey) }">
            <input type="checkbox" :value="m.funcKey" v-model="form.mergedKeys" />
            {{ m.name }}（{{ m.label }}）
          </label>
        </div>
      </div>
      <div class="org-lbl">岗位权限</div>
      <textarea class="org-inp org-ta" v-model="form.permissions" style="min-height:52px"></textarea>
      <div class="org-lbl">安全目标</div>
      <textarea class="org-inp org-ta" v-model="form.goals"></textarea>
      <div class="org-lbl">岗位职责</div>
      <textarea class="org-inp org-ta" v-model="form.duties" style="min-height:80px"></textarea>
      <div class="org-lbl">考核标准</div>
      <textarea class="org-inp org-ta" v-model="form.assess"></textarea>
      <div class="org-note">
        🔗 保存后与制度<b>双向联动</b>：「安全生产责任制」该岗位条目、责任书待签、考核奖惩 / 诚信考核岗位考核项实时更新。
      </div>
      <template #footer>
        <button class="btn btn-outline" @click="dlg.edit = false">取消</button>
        <button class="btn btn-primary" @click="saveEdit">保存并联动</button>
      </template>
    </el-dialog>

    <!-- 关联制度与责任书 -->
    <el-dialog v-model="dlg.docs" title="关联制度与责任书" width="540px" class="org-dialog" :close-on-click-modal="false">
      <div class="org-lbl">岗位</div>
      <div class="org-inp org-readonly">{{ cur?.name }}（{{ cur?.label }}）</div>
      <div class="org-lbl">勾选该岗位需签署 / 遵循的制度与责任书</div>
      <div class="org-doc-grid">
        <label v-for="d in chart?.docPool || []" :key="d" :class="{ on: docsSel.includes(d) }">
          <input type="checkbox" :value="d" v-model="docsSel" /> {{ d }}
        </label>
      </div>
      <div class="org-note" style="margin-top:10px">被<b>禁用 / 被兼并</b>的岗位不会出现在上述制度的正文内容中。</div>
      <template #footer>
        <button class="btn btn-outline" @click="dlg.docs = false">取消</button>
        <button class="btn btn-primary" @click="saveDocs">保存关联</button>
      </template>
    </el-dialog>

    <!-- 责任制联动预览 -->
    <el-dialog v-model="dlg.docPrev" title="安全生产责任制 · 联动预览" width="640px" class="org-dialog">
      <div class="org-doc-prev">
        <h4>{{ chart?.company }} · 全员安全生产责任制（自动生成）</h4>
        <template v-for="(r, i) in respRows" :key="i">
          <h4>{{ i + 1 }}、{{ r.name }}（{{ r.label }}）</h4>
          <div class="mgd" v-if="r.merged?.length">※ 本岗位兼并以下职能：{{ r.merged.join('、') }}</div>
          <div class="sec">【安全目标】{{ r.goals }}</div>
          <div class="sec">【岗位职责】{{ r.duties }}</div>
          <div class="sec">【考核标准】{{ r.assess }}</div>
        </template>
        <div class="ban">※ 已从本制度剔除的岗位：{{ (chart?.disabled || []).join('、') || '无' }}</div>
      </div>
      <div class="org-note" style="margin-top:8px">
        本制度正文由<b>启用中岗位</b>动态生成；禁用 / 兼并岗位已自动剔除，架构调整后此处内容同步变化。
      </div>
      <template #footer><button class="btn btn-primary" @click="dlg.docPrev = false">我知道了</button></template>
    </el-dialog>
  </div>
</template>

<style>
/* ================= 页面（前缀隔离，避免污染其他模块） ================= */
.org-page .org-hint {
  background: #fff; border: 1px solid var(--border); border-left: 3px solid var(--blue-600);
  border-radius: 8px; padding: 11px 14px; font-size: 13px; color: var(--text-2);
  line-height: 1.85; margin-bottom: 16px;
}
.org-page .org-hint b { color: var(--text); }
.org-page .org-stat {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 12.5px;
  color: var(--text-2); padding: 10px 16px; border-bottom: 1px solid var(--border); background: #F8FAFC;
}
.org-page .org-stat b { color: var(--text); }
.org-page .org-stat .stat-right { margin-left: auto; }
.org-page .org-stat a { color: var(--blue-600); font-weight: 600; cursor: pointer; }
.org-page .org-stat a:hover { text-decoration: underline; }
.org-page .tag-off { background: #F1F5F9; color: #64748B; border-radius: 8px; padding: 2px 9px; font-size: 11.5px; }
.org-page .tag-up { background: #FFFBEB; color: #B45309; border-radius: 8px; padding: 2px 9px; font-size: 11.5px; }
.org-page .org-wrap {
  overflow: auto; padding: 22px 18px 30px; background:
    radial-gradient(circle at 1px 1px, #E2E8F0 1px, transparent 0) 0 0 / 22px 22px, #fff;
  min-height: 360px;
}
.org-page .org-empty { padding: 40px; text-align: center; color: var(--text-3); font-size: 14px; }

/* 右键菜单 */
.org-page .org-ctx {
  position: fixed; z-index: 2000; background: #fff; border: 1px solid #E2E8F0; border-radius: 10px;
  box-shadow: 0 12px 32px rgba(15, 23, 42, .18); padding: 6px; min-width: 210px; display: none;
}
.org-page .org-ctx.show { display: block; }
.org-page .org-ctx .ci {
  display: flex; align-items: center; gap: 9px; padding: 9px 12px; font-size: 13.5px;
  color: var(--text); border-radius: 7px; cursor: pointer;
}
.org-page .org-ctx .ci:hover { background: var(--blue-50); color: var(--blue-800); }
.org-page .org-ctx .ci svg { color: var(--blue-600); }
.org-page .org-ctx .ci.head {
  cursor: default; background: none; color: var(--text-3); font-size: 12px; font-weight: 700;
  padding-bottom: 5px; border-bottom: 1px solid #F1F5F9; border-radius: 0; margin-bottom: 4px;
}

/* 弹窗内表单（原生控件，1:1 对齐原型） */
.org-dialog .org-lbl { font-size: 13px; color: var(--text-2); font-weight: 600; margin: 14px 0 6px; }
.org-dialog .org-lbl:first-child { margin-top: 0; }
.org-dialog .org-req::before { content: '*'; color: var(--danger); margin-right: 2px; }
.org-dialog .org-sub { font-weight: 400; color: var(--text-3); font-size: 12px; }
.org-dialog .org-inp,
.org-dialog .org-ta {
  width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--border);
  border-radius: 8px; font-size: 14px; font-family: inherit; background: #fff; color: var(--text);
}
.org-dialog .org-ta { min-height: 68px; resize: vertical; line-height: 1.6; }
.org-dialog .org-inp:focus, .org-dialog .org-ta:focus {
  outline: none; border-color: var(--blue-400); box-shadow: 0 0 0 3px var(--blue-100);
}
.org-dialog .org-inp.org-readonly { background: var(--bg-2); color: var(--text-3); }
.org-dialog .org-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.org-dialog .org-grid .span2 { grid-column: 1 / -1; }
.org-dialog .org-note { font-size: 12px; color: var(--text-3); margin-top: 4px; line-height: 1.6; }
.org-dialog .org-note b { color: var(--blue-600); }
.org-dialog .org-doc-grid, .org-dialog .org-mg-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 14px; }
.org-dialog .org-doc-grid label, .org-dialog .org-mg-grid label {
  display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text);
  cursor: pointer; padding: 6px 10px; border: 1px solid var(--border); border-radius: 8px;
}
.org-dialog .org-doc-grid label.on { border-color: var(--blue-400); background: var(--blue-50); color: var(--blue-800); }
.org-dialog .org-mg-grid label.on { border-color: #F59E0B; background: #FFFBEB; color: #B45309; }
.org-dialog .org-doc-prev {
  border: 1px solid var(--border); border-radius: 10px; padding: 16px 18px;
  max-height: 46vh; overflow: auto; line-height: 1.8; font-size: 13.5px;
}
.org-dialog .org-doc-prev h4 { margin: 12px 0 4px; font-size: 14px; color: var(--text); }
.org-dialog .org-doc-prev h4:first-child { margin-top: 0; }
.org-dialog .org-doc-prev .sec { color: var(--text-2); font-size: 13px; }
.org-dialog .org-doc-prev .mgd { font-size: 12px; color: #B45309; }
.org-dialog .org-doc-prev .ban { font-size: 12px; color: var(--danger); }
.org-dialog .el-dialog__footer { display: flex; justify-content: flex-end; gap: 10px; }

/* ================= 架构图（与原型 assets/org_chart.js 同源） ================= */
.ocg { overflow: auto; padding: 10px 4px 18px; background: transparent; }
.ocg-compact { padding: 2px 0 8px; }
.ocg-tree, .ocg-tree ul { list-style: none; margin: 0; padding: 0; }
.ocg-tree { display: flex; justify-content: center; min-width: 760px; }
.ocg-tree ul { display: flex; justify-content: center; padding-top: 22px; position: relative; }
.ocg-tree li { display: flex; flex-direction: column; align-items: center; padding: 22px 6px 0; position: relative; }
.ocg-tree li::before, .ocg-tree li::after {
  content: ""; position: absolute; top: 0; right: 50%; width: 50%; height: 22px; border-top: 1px solid #A8C4E8;
}
.ocg-tree li::after { right: auto; left: 50%; border-left: 1px solid #A8C4E8; }
.ocg-tree li:only-child { padding-top: 0; }
.ocg-tree li:only-child::before, .ocg-tree li:only-child::after { display: none; }
.ocg-tree > li { padding-top: 0; }
.ocg-tree > li::before, .ocg-tree > li::after { display: none; }
.ocg-tree li:first-child::before, .ocg-tree li:last-child::after { border-top: 0 none; }
.ocg-tree li:last-child::before { border-right: 1px solid #A8C4E8; border-radius: 0 6px 0 0; }
.ocg-tree li:first-child::after { border-radius: 6px 0 0 0; }
.ocg-tree li > ul::before {
  content: ""; position: absolute; top: 0; left: 50%; border-left: 1px solid #A8C4E8; width: 0; height: 22px;
}
.ocg-node {
  min-width: 150px; max-width: 200px; border: 1px solid #C9D9F0; border-radius: 8px; overflow: hidden;
  background: #fff; box-shadow: 0 2px 7px rgba(15, 23, 42, .09); font-family: inherit; box-sizing: border-box;
}
.ocg-node[data-post] { cursor: context-menu; user-select: none; transition: .15s; }
.ocg-node[data-post]:hover { transform: translateY(-2px); box-shadow: 0 6px 16px rgba(29, 78, 216, .22); }
.ocg-node .ocg-t {
  padding: 7px 12px; font-size: 13px; font-weight: 700; color: #fff; background: #2563EB;
  text-align: center; line-height: 1.45; white-space: nowrap;
}
.ocg-node .ocg-t .ocg-fl { display: block; font-size: 11px; font-weight: 500; opacity: .88; margin-top: 1px; }
.ocg-node .ocg-p {
  padding: 5px 8px; font-size: 12px; color: #1E40AF; background: #DBEAFE;
  text-align: center; line-height: 1.5; white-space: nowrap;
}
.ocg-node .ocg-p b { font-weight: 700; }
.ocg-node .ocg-p .ocg-dim { color: #64748B; }
.ocg-node .ocg-mg { background: #DBEAFE; padding: 0 8px 5px; text-align: center; }
.ocg-node .ocg-mg .ocg-m {
  font-size: 11px; color: #1E40AF; border-top: 1px dashed #93C5FD; padding: 3px 0; line-height: 1.4;
}
.ocg-node.ocg-gm .ocg-t { background: #7C3AED; }
.ocg-node.ocg-company { min-width: 230px; max-width: none; border: none; box-shadow: 0 2px 8px rgba(124, 58, 237, .2); }
.ocg-node.ocg-company .ocg-t { background: #7C3AED; font-size: 14px; padding: 9px 20px; }
.ocg-compact .ocg-tree { min-width: 0; }
.ocg-compact .ocg-tree ul { padding-top: 16px; }
.ocg-compact .ocg-tree li { padding: 16px 3px 0; }
.ocg-compact .ocg-tree li::before, .ocg-compact .ocg-tree li::after { height: 16px; }
.ocg-compact .ocg-tree li > ul::before { height: 16px; }
.ocg-compact .ocg-node { min-width: 104px; max-width: 140px; border-radius: 5px; box-shadow: none; }
.ocg-compact .ocg-node .ocg-t { padding: 5px 6px; font-size: 11px; }
.ocg-compact .ocg-node .ocg-t .ocg-fl { font-size: 9.5px; }
.ocg-compact .ocg-node .ocg-p { padding: 3px 5px; font-size: 10px; }
.ocg-compact .ocg-node .ocg-mg .ocg-m { font-size: 9.5px; }
.ocg-compact .ocg-node.ocg-company { min-width: 210px; }
.ocg-compact .ocg-node.ocg-company .ocg-t { font-size: 12px; padding: 7px 14px; }
@media print {
  .ocg { overflow: visible !important; background: #fff !important; }
  .ocg-node, .ocg-node[data-post] {
    box-shadow: none !important; transform: none !important; break-inside: avoid; page-break-inside: avoid;
  }
  .ocg-node .ocg-t, .ocg-node .ocg-p, .ocg-node .ocg-mg {
    -webkit-print-color-adjust: exact; print-color-adjust: exact;
  }
  .ocg-tree li, .ocg-tree ul { break-inside: avoid; page-break-inside: avoid; }
}
</style>
