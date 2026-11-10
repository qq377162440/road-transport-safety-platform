<script setup>
/** B6 安全会议管理 · 完整流程闭环（详细设计 12）
 *  两类会议：安全生产领导小组会议 / 安全生产月度会议（对齐档案盒盒三两个子项）。
 *  状态机：draft 草稿 → published 未开始 →（时间驱动）ongoing 进行中 → finished 已结束 → archived 已归档；可 cancel 取消。
 *  会议通知（M6 模板占位）、参会多选、签到留痕（司机签名 + 安全员/超管补签/标请假）、会议纪要（记录+照片+PPT）、
 *  四段式会议 PDF、盒三按类型归档；小程序仅预留 /expiring、/mine 数据接口，不新增小程序页面。 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/api/request'
import { ElMessage } from 'element-plus'
import Icon from '@/components/Icon.vue'

const router = useRouter()
const ov = ref({ company: '—', stats: {}, flow: [], meetingTypes: [], records: [], board: [], archives: [], refs: [] })
const loading = ref(false)
const busy = ref(false)
const mainTab = ref('record')           // record 会议清单 / board 签到看板 / archive 归档记录
const statusFilter = ref('')
const typeFilter = ref('')
const keyword = ref('')

const STATUS = {
  draft: ['草稿', 'tag-gray'], published: ['未开始', 'tag-blue'], ongoing: ['进行中', 'tag-yellow'],
  finished: ['已结束', 'tag-yellow'], archived: ['已归档', 'tag-green'], cancelled: ['已取消', 'tag-gray'],
}
function statusText(s) { return (STATUS[s] || ['—', 'tag-gray'])[0] }
function statusClass(s) { return (STATUS[s] || ['—', 'tag-gray'])[1] }
function d10(v) { return v ? String(v).slice(0, 10) : '—' }
function d16(v) { return v ? String(v).slice(0, 16).replace('T', ' ') : '—' }

async function load() {
  loading.value = true
  try {
    const r = await request.get('/flow/meeting/overview', {
      params: { status: statusFilter.value, meetingType: typeFilter.value, keyword: keyword.value },
    })
    ov.value = r.data || ov.value
  } catch (e) { /* 拦截器已提示 */ }
  finally { loading.value = false }
}
onMounted(load)

const filteredRecords = computed(() => {
  const k = keyword.value.trim()
  return (ov.value.records || []).filter((r) => !k || String(r.title || '').includes(k))
})

// ---------------- 通用动作 ----------------
async function act(url, method, body, label, withDetail = true) {
  busy.value = true
  try {
    const r = method === 'put' ? await request.put(url, body || {})
      : method === 'delete' ? await request.delete(url, { data: body || {} })
      : await request.post(url, body || {})
    if (r.data && r.data.ok === false) { ElMessage.warning(r.data.msg || '操作未成功'); return null }
    if (label) ElMessage.success(label + '成功')
    await load()
    if (withDetail && drawer.value && curId.value) await refreshDetail()
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
function goArchive() { router.push('/archive') }
async function doArchive(meetingId) {
  const r = await act('/flow/meeting/archive', 'post', { meetingId: String(meetingId) }, '', false)
  if (r) ElMessage.success(`已归档到 ${r.box}（${r.sub === 'S01' ? '领导小组会议' : '月度会议'}），生成 ${r.size} 字节 PDF`)
}
async function publish(row) { await act(`/flow/meeting/${String(row.id)}/publish`, 'post', {}, '发布通知') }
async function cancel(row) { await act(`/flow/meeting/${String(row.id)}/cancel`, 'post', {}, '取消会议') }

// ---------------- 详情抽屉 ----------------
const drawer = ref(false)
const detail = ref(null)
const tab = ref('info')
const curId = ref('')
const cur = computed(() => (ov.value.records || []).find((x) => x.id === curId.value) || detail.value || {})

async function openDetail(row) {
  curId.value = String(row.id)
  tab.value = 'info'
  drawer.value = true
  await refreshDetail()
}
async function refreshDetail() {
  busy.value = true
  try { detail.value = (await request.get(`/flow/meeting/${curId.value}`)).data || null }
  catch (e) { detail.value = null }
  finally { busy.value = false }
}

// 签到动作（详情内逐条）
async function signAttendee(a, source) {
  const body = source === 'self' ? { userId: String(a.user_id) }
    : source === 'replenish' ? { userId: String(a.user_id), signImage: 'data:replenish' }
    : { userId: String(a.user_id), reason: '事假' }
  const url = source === 'self' ? `/flow/meeting/${curId.value}/sign`
    : source === 'replenish' ? `/flow/meeting/${curId.value}/replenish`
    : `/flow/meeting/${curId.value}/mark-leave`
  await act(url, 'post', body, source === 'self' ? '签到' : source === 'replenish' ? '补签' : '标请假')
}

// ---------------- 弹窗 ----------------
const dlg = ref('')
const form = ref({})
const options = ref({ persons: [] })
async function loadOptions() {
  try { options.value = (await request.get('/flow/meeting/options')).data || options.value } catch (e) { }
}
async function openDlg(type, row) {
  dlg.value = type
  await loadOptions()
  if (type === 'create') {
    form.value = { meetingType: 'monthly', title: '', topic: '', mode: 'offline', meetingNo: '', link: '',
      location: '', startTime: '', endTime: '', hostId: '', recorderId: '', attendeeIds: [] }
  } else if (type === 'edit') {
    form.value = { id: String(row.id), title: row.title, topic: row.topic, mode: row.mode || 'offline',
      meetingNo: row.meeting_no || '', link: row.link || '', location: row.location || '',
      startTime: (row.start_time || '').slice(0, 16), endTime: (row.end_time || '').slice(0, 16),
      hostId: String(row.host_id || ''), recorderId: String(row.recorder_id || ''), attendeeIds: [] }
  } else if (type === 'record') {
    curId.value = String(row.id)
    const rec = (detail.value && detail.value.record) || {}
    form.value = { content: rec.content || '', venuePhotos: (rec.venuePhotos || []).join(','), pptFiles: (rec.pptFiles || []).join(',') }
  } else if (type === 'correct') {
    curId.value = String(row.id)
    form.value = { title: row.title || '', meetingType: row.meeting_type || 'monthly', status: row.status || 'draft' }
  }
}
async function submitDlg() {
  const f = form.value
  let r = null
  if (dlg.value === 'create') {
    const body = { ...f, hostId: f.hostId ? String(f.hostId) : '', recorderId: f.recorderId ? String(f.recorderId) : '',
      attendeeIds: (f.attendeeIds || []).map(String) }
    r = await act('/flow/meeting', 'post', body, '创建会议', false)
    if (r) ElMessage.success(`已创建，参会 ${r.attendeeCount} 人，通知已生成：${r.noticeTitle}`)
  } else if (dlg.value === 'edit') {
    const body = { title: f.title, topic: f.topic, mode: f.mode, meetingNo: f.meetingNo, link: f.link,
      location: f.location, startTime: f.startTime ? f.startTime.replace('T', ' ').slice(0, 19) : '',
      endTime: f.endTime ? f.endTime.replace('T', ' ').slice(0, 19) : '',
      hostId: f.hostId ? String(f.hostId) : '', recorderId: f.recorderId ? String(f.recorderId) : '',
      attendeeIds: (f.attendeeIds || []).map(String) }
    r = await act(`/flow/meeting/${f.id}`, 'put', body, '保存编辑')
  } else if (dlg.value === 'record') {
    const body = { content: f.content, venuePhotos: f.venuePhotos.split(/[,\n]/).map((x) => x.trim()).filter(Boolean),
      pptFiles: f.pptFiles.split(/[,\n]/).map((x) => x.trim()).filter(Boolean) }
    r = await act(`/flow/meeting/${curId.value}/record`, 'put', body, '保存纪要')
  } else if (dlg.value === 'correct') {
    r = await act(`/flow/meeting/${curId.value}/correct`, 'put', f, '超管纠错')
  }
  if (r) dlg.value = ''
}
function toggleAtt(id) {
  const arr = form.value.attendeeIds || []
  const i = arr.indexOf(id)
  if (i >= 0) arr.splice(i, 1); else arr.push(id)
}
async function delArchive() { /* 预留：从档案柜撤销，本页不实现 */ }
</script>

<template>
  <div class="bm-page">
    <div class="page-header bm-header">
      <div>
        <h1>安全会议管理</h1>
        <div class="desc">会议通知、签到、纪要、照片四段式 · 两类会议（领导小组 / 月度）· 一键归档盒三</div>
        <div class="bm-meta">
          <span class="tag tag-blue">B6 · 盒三 / 危货盒三</span>
          <span class="tag tag-green">两类会议</span>
          <span class="tag tag-yellow">完成率 {{ ov.stats?.completionRate ?? 0 }}%</span>
          <span class="bm-tables">{{ ov.company }} · {{ ov.stats?.total || 0 }} 场会议 · 签到 {{ ov.stats?.signed || 0 }}/{{ ov.stats?.attendees || 0 }} · 小程序仅预留数据接口</span>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-primary" @click="openDlg('create')"><Icon name="plus" :size="15" /> 新增会议</button>
        <button class="btn btn-outline" @click="openPdf('/flow/meeting/ledger/pdf')"><Icon name="fileText" :size="15" /> 会议台账</button>
        <button class="btn btn-outline" @click="goArchive"><Icon name="folder" :size="15" /> 电子档案柜</button>
        <button class="btn btn-outline" @click="load()"><Icon name="refresh" :size="15" /> 刷新</button>
      </div>
    </div>

    <div class="pf-hint"><Icon name="info" :size="15" />
      会议创建即生成通知；状态机草稿→未开始→进行中→已结束→已归档，按会议时间自动推进；签到签名留痕不可随意改，
      补签必留来源；纪要录入后一键归档到盒三对应子项（领导小组 / 月度），归档内容 = 记录 + PPT + 签到 + 照片。
    </div>

    <div class="pf-stat-row">
      <div class="stat-card"><div class="num">{{ ov.stats?.total || 0 }}</div><div class="lbl">会议总数</div></div>
      <div class="stat-card"><div class="num" style="color:#2563eb">{{ ov.stats?.published || 0 }}</div><div class="lbl">未开始 · 进行中 {{ ov.stats?.ongoing || 0 }}</div></div>
      <div class="stat-card"><div class="num" style="color:#d97706">{{ ov.stats?.finished || 0 }}</div><div class="lbl">已结束 · 已取消 {{ ov.stats?.cancelled || 0 }}</div></div>
      <div class="stat-card"><div class="num" style="color:#16a34a">{{ ov.stats?.completionRate ?? 0 }}%</div><div class="lbl">完成率 · 已归档 {{ ov.stats?.archived || 0 }} / 签到 {{ ov.stats?.signed || 0 }}</div></div>
    </div>

    <div class="card pf-flow">
      <div class="card-title">会议生命周期状态机 <span class="pf-sub">逐条操作 · 每次流转留痕 · 时间驱动推进 · 可取消</span></div>
      <div class="pf-flow-row">
        <template v-for="(f, i) in ov.flow || []" :key="f.code">
          <div class="pf-flow-node" :class="{ on: (ov.stats?.[f.code] || 0) > 0 }"><b>{{ i + 1 }}</b><span>{{ f.name }}（{{ ov.stats?.[f.code] || 0 }}）</span></div>
          <span v-if="i < (ov.flow || []).length - 1" class="pf-arrow">→</span>
        </template>
        <span class="pf-sub" style="margin-left:12px">已取消可走单独分支　·　未开始前可编辑/取消</span>
      </div>
    </div>

    <div class="filter-bar bm-filter">
      <div class="f-item"><label>类型</label>
        <select v-model="typeFilter">
          <option value="">全部</option><option value="leader">领导小组会议</option><option value="monthly">月度会议</option>
        </select>
      </div>
      <div class="f-item"><label>状态</label>
        <select v-model="statusFilter">
          <option value="">全部</option><option value="draft">草稿</option><option value="published">未开始</option>
          <option value="ongoing">进行中</option><option value="finished">已结束</option><option value="archived">已归档</option><option value="cancelled">已取消</option>
        </select>
      </div>
      <div class="f-item"><label>会议名称</label><input v-model="keyword" type="text" placeholder="模糊搜索"></div>
      <div class="f-actions">
        <button class="btn btn-primary btn-sm" @click="load()"><Icon name="search" :size="14" /> 查询</button>
        <button class="btn btn-outline btn-sm" @click="keyword = ''; statusFilter = ''; typeFilter = ''; load()">重置</button>
      </div>
    </div>

    <div class="card" style="padding:0;overflow:hidden">
      <div class="card-title" style="padding:16px 20px 0;display:flex;align-items:center;gap:8px">
        <button class="pf-tab" :class="{ on: mainTab === 'record' }" @click="mainTab = 'record'">会议清单</button>
        <button class="pf-tab" :class="{ on: mainTab === 'board' }" @click="mainTab = 'board'">签到看板</button>
        <button class="pf-tab" :class="{ on: mainTab === 'archive' }" @click="mainTab = 'archive'">归档记录</button>
        <span class="pf-sub" style="margin-left:auto">会议通知 + 签到表 + 会议记录 + 会场照片 → 归档盒三</span>
      </div>

      <!-- 会议清单 -->
      <div v-if="mainTab === 'record'" style="overflow:auto">
        <table class="table bm-table" v-loading="loading">
          <thead><tr>
            <th>会议名称 / 类型</th><th>召开时间</th><th>主持人</th><th>参会/签到</th>
            <th>状态</th><th>操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="r in filteredRecords" :key="r.id">
              <td><b>{{ r.title || '—' }}</b><span class="pf-sub" style="display:block">{{ r.typeName }}</span></td>
              <td>{{ d16(r.start_time) }}</td>
              <td>{{ r.host_name || '—' }}</td>
              <td>{{ r.sign_total || 0 }}/{{ r.attendee_total || 0 }}（{{ r.signProgress || 0 }}%）</td>
              <td><span class="tag" :class="statusClass(r.status)">{{ r.statusName || statusText(r.status) }}</span></td>
              <td>
                <a class="btn btn-primary btn-sm" @click="openDetail(r)">详情</a>
                <a v-if="r.status === 'draft'" class="btn btn-outline btn-sm" @click="publish(r)">发布</a>
                <a v-if="['draft','published'].includes(r.status)" class="btn btn-outline btn-sm" @click="openDlg('edit', r)">编辑</a>
                <a v-if="['draft','published'].includes(r.status)" class="btn btn-outline btn-sm" @click="cancel(r)">取消</a>
                <a v-if="r.status !== 'archived' && r.status !== 'cancelled'" class="btn btn-outline btn-sm" @click="openPdf('/flow/meeting/' + String(r.id) + '/pdf')">PDF</a>
              </td>
            </tr>
            <tr v-if="!filteredRecords.length"><td colspan="6" style="text-align:center;color:var(--text-3);padding:26px">暂无会议记录，点右上「新增会议」开始</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 签到看板 -->
      <div v-else-if="mainTab === 'board'" style="overflow:auto;padding:8px 0">
        <div v-for="w in ov.board || []" :key="w.id" class="pf-warn-item" style="margin:8px 20px">
          <span class="tag" :class="statusClass(w.status)">{{ w.statusName }}</span>
          <span class="nm"><b>{{ w.title }}</b> · {{ w.typeName }} · {{ d10(w.start_time) }}</span>
          <span class="dt">签到 {{ w.signed }}/{{ w.total }}（{{ w.progress || 0 }}%）</span>
          <a class="btn btn-outline btn-sm" @click="openDetail({ id: w.id })">去签到</a>
          <span v-if="w.unsignedNames" class="pf-sub" style="flex-basis:100%;margin-top:4px">未签：{{ w.unsignedNames }}</span>
        </div>
        <div v-if="!(ov.board || []).length" style="text-align:center;color:var(--text-3);padding:26px">暂无会议签到数据</div>
      </div>

      <!-- 归档记录 -->
      <div v-else style="overflow:auto">
        <table class="table bm-table" v-loading="loading">
          <thead><tr><th>归档成品</th><th>归档日期</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="a in ov.archives || []" :key="a.id">
              <td><b>{{ a.title }}</b></td>
              <td>{{ d10(a.occur_date) }}</td>
              <td><span class="tag tag-green">已归档</span></td>
              <td><a class="btn btn-outline btn-sm" @click="openPdf('/flow/meeting/' + String(a.id) + '/pdf')">查看 PDF</a></td>
            </tr>
            <tr v-if="!(ov.archives || []).length"><td colspan="4" style="text-align:center;color:var(--text-3);padding:26px">暂无会议归档记录</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 联动引用 -->
    <div class="card pf-group">
      <div class="card-title">模块间联动 <span class="pf-sub">主持人/记录人/参会人同源 B1 人员 · 一键归档盒三（B7）</span></div>
      <div class="pf-ref-row">
        <span v-for="r in ov.refs || []" :key="r.module" class="tag tag-gray">{{ r.name }} · {{ r.usage }}（{{ r.count }}）</span>
      </div>
    </div>

    <!-- ============ 会议详情抽屉 ============ -->
    <div class="pf-mask" :class="{ on: drawer }" @click.self="drawer = false">
      <div class="pf-drawer">
        <div class="pf-dh">
          <div>
            <h2>{{ (detail || cur).title || '会议详情' }}</h2>
            <div class="pf-sub">{{ ov.company }} · {{ (detail || cur).typeName || '' }}
              <span class="tag" :class="statusClass((detail || cur).status)" style="margin-left:6px">{{ (detail || cur).statusName || statusText((detail || cur).status) }}</span>
              <span class="pf-sub" style="margin-left:8px">主持 {{ (detail || cur).host_name }} · 记录 {{ (detail || cur).recorder_name }}</span>
            </div>
          </div>
          <button class="pf-close" @click="drawer = false">✕</button>
        </div>
        <div class="pf-tabs">
          <button v-for="t in [['info','基本信息 / 通知'],['sign','签到表'],['record','会议纪要'],['log','流转时间线'],['ref','PDF 与归档']]" :key="t[0]"
                  class="pf-tab" :class="{ on: tab === t[0] }" @click="tab = t[0]">{{ t[1] }}</button>
        </div>
        <div v-if="detail" class="pf-body" v-loading="busy">
          <!-- 基本信息 / 通知 -->
          <template v-if="tab === 'info'">
            <div class="card pf-actions">
              <div class="card-title">逐条操作 <span class="pf-sub">当前 {{ statusText(detail.status) }} · 每次流转写日志</span></div>
              <div class="pf-btn-row">
                <a v-if="detail.status === 'draft'" class="btn btn-primary btn-sm" @click="act(`/flow/meeting/${curId}/publish`, 'post', {}, '发布通知')">发布通知</a>
                <a v-if="['draft','published'].includes(detail.status)" class="btn btn-outline btn-sm" @click="cancel(detail)">取消会议</a>
                <a class="btn btn-outline btn-sm" @click="openDlg('edit', detail)">编辑</a>
                <a class="btn btn-outline btn-sm" @click="openDlg('correct', detail)">超管纠错</a>
                <a class="btn btn-outline btn-sm" @click="openPdf('/flow/meeting/' + curId + '/pdf')">四段式 PDF</a>
              </div>
            </div>
            <div class="card pf-group">
              <div class="card-title">会议通知（M6 模板占位，创建即生成）</div>
              <div class="pf-notice" style="white-space:pre-wrap">{{ detail.notice_title }}<br/><br/>{{ detail.notice_content }}</div>
            </div>
            <div class="card pf-group">
              <div class="card-title">会议登记信息</div>
              <table class="pf-subtable">
                <tbody>
                  <tr><th>会议名称</th><td colspan="3">{{ detail.title || '—' }}</td></tr>
                  <tr><th>会议类型</th><td>{{ detail.typeName }}</td><th>方式</th><td>{{ detail.mode === 'online' ? '线上' : '线下' }}</td></tr>
                  <tr><th>开始时间</th><td>{{ d16(detail.start_time) }}</td><th>结束时间</th><td>{{ d16(detail.end_time) }}</td></tr>
                  <tr><th>会议地点</th><td colspan="3">{{ detail.mode === 'online' ? ('腾讯会议' + (detail.meeting_no ? ' ' + detail.meeting_no : '') + (detail.link ? ' / ' + detail.link : '')) : (detail.location || '公司会议室') }}</td></tr>
                  <tr><th>主持人</th><td>{{ detail.host_name || '—' }}</td><th>记录人</th><td>{{ detail.recorder_name || '—' }}</td></tr>
                  <tr><th>会议议题</th><td colspan="3">{{ detail.topic || '—' }}</td></tr>
                </tbody>
              </table>
            </div>
          </template>

          <!-- 签到表 -->
          <template v-if="tab === 'sign'">
            <div class="card pf-group">
              <div class="card-title">会议签到表（M3 签名留痕，岗位顺序固定） <span class="pf-sub">共 {{ (detail.attendees || []).length }} 人</span></div>
              <table class="pf-subtable">
                <thead><tr><th>序号</th><th>姓名</th><th>岗位</th><th>签名</th><th>签到时间</th><th>状态</th><th>补签人</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-for="(a, idx) in detail.attendees || []" :key="a.id">
                    <td>{{ idx + 1 }}</td>
                    <td><b>{{ a.name }}</b></td>
                    <td>{{ a.post || '—' }}</td>
                    <td>{{ a.sign_image ? (a.sign_image.startsWith('http') ? '✓已签名' : '✓已签名') : '—' }}</td>
                    <td>{{ d16(a.sign_time) }}</td>
                    <td><span class="tag" :class="a.sign_status === 'signed' ? 'tag-green' : a.sign_status === 'leave' ? 'tag-yellow' : 'tag-gray'">{{ a.signStatusName }}</span></td>
                    <td>{{ a.replenishedByName || '—' }}</td>
                    <td>
                      <a v-if="a.sign_status !== 'signed'" class="btn btn-outline btn-sm" @click="signAttendee(a, 'self')">签到</a>
                      <a v-if="a.sign_status !== 'signed'" class="btn btn-outline btn-sm" @click="signAttendee(a, 'replenish')">补签</a>
                      <a v-if="a.sign_status !== 'leave' && a.sign_status !== 'signed'" class="btn btn-outline btn-sm" @click="signAttendee(a, 'leave')">请假</a>
                    </td>
                  </tr>
                  <tr v-if="!(detail.attendees || []).length"><td colspan="8" style="text-align:center;color:var(--text-3)">暂无参会人</td></tr>
                </tbody>
              </table>
            </div>
          </template>

          <!-- 会议纪要 -->
          <template v-if="tab === 'record'">
            <div class="card pf-group">
              <div class="card-title">会议纪要（M4 富文本 + 照片 + PPT） <span class="pf-sub">归档完整度 {{ detail.completeness?.rate || 0 }}%</span></div>
              <div class="pf-btn-row">
                <a class="btn btn-primary btn-sm" @click="openDlg('record', detail)">录入 / 编辑纪要</a>
              </div>
              <div class="pf-notice" style="margin-top:10px;white-space:pre-wrap">{{ (detail.record && detail.record.content) || '（尚未录入会议纪要）' }}</div>
              <div v-if="detail.record && (detail.record.venuePhotos || []).length" style="margin-top:10px">
                <div class="pf-sub">会场照片（{{ (detail.record.venuePhotos || []).length }}）：</div>
                <div class="pf-ref-row"><span v-for="(p, i) in (detail.record.venuePhotos || [])" :key="i" class="tag tag-gray">{{ p }}</span></div>
              </div>
              <div v-if="detail.record && (detail.record.pptFiles || []).length" style="margin-top:10px">
                <div class="pf-sub">会议 PPT（{{ (detail.record.pptFiles || []).length }}）：</div>
                <div class="pf-ref-row"><span v-for="(p, i) in (detail.record.pptFiles || [])" :key="i" class="tag tag-gray">{{ p }}</span></div>
              </div>
            </div>
          </template>

          <!-- 流转时间线 -->
          <template v-if="tab === 'log'">
            <div class="card pf-group">
              <div class="card-title">状态流转时间线 <span class="pf-sub">全流程留痕</span></div>
              <table class="pf-subtable">
                <thead><tr><th>动作</th><th>状态变化</th><th>说明</th><th>操作人</th><th>时间</th></tr></thead>
                <tbody>
                  <tr v-for="(g, i) in detail.logs || []" :key="i">
                    <td>{{ g.action }}</td><td>{{ g.from_status || '—' }} → {{ g.to_status || '—' }}</td>
                    <td>{{ g.detail || '—' }}</td><td>{{ g.operator || '—' }}</td><td>{{ g.created_at || '—' }}</td>
                  </tr>
                  <tr v-if="!(detail.logs || []).length"><td colspan="5" style="text-align:center;color:var(--text-3)">暂无流转日志</td></tr>
                </tbody>
              </table>
            </div>
          </template>

          <!-- PDF 与归档 -->
          <template v-if="tab === 'ref'">
            <div class="card pf-group">
              <div class="card-title">PDF 成品与归档 <span class="pf-sub">通知/签到/记录/照片四段 → 盒三（按类型）</span></div>
              <div class="pf-btn-row">
                <a class="btn btn-outline btn-sm" @click="openPdf('/flow/meeting/' + curId + '/pdf')">四段式 PDF</a>
                <a class="btn btn-primary btn-sm" v-if="detail.status !== 'archived' && detail.status !== 'cancelled'"
                   @click="doArchive(curId)">一键归档盒三</a>
              </div>
              <table class="pf-subtable">
                <thead><tr><th>归档成品</th><th>归档日期</th><th>状态</th></tr></thead>
                <tbody>
                  <tr v-for="a in ov.archives || []" :key="a.id"><td>{{ a.title }}</td><td>{{ d10(a.occur_date) }}</td><td>已归档</td></tr>
                  <tr v-if="!(ov.archives || []).length"><td colspan="3" style="text-align:center;color:var(--text-3)">暂无归档记录</td></tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- ============ 弹窗 ============ -->
    <div class="pf-mask" :class="{ on: dlg }" @click.self="dlg = ''" style="justify-content:center;align-items:center">
      <div class="pf-modal" @click.stop>
        <div class="pf-modal-h">
          <h3>{{ { create: '新增会议（生成通知）', edit: '编辑会议', record: '录入会议纪要', correct: '超管纠错' }[dlg] || '' }}</h3>
          <button class="pf-close" @click="dlg = ''">✕</button>
        </div>
        <div class="pf-modal-b">
          <div class="pf-form-grid">
            <template v-if="dlg === 'create' || dlg === 'edit'">
              <div class="f-item"><label>会议类型 *</label><select v-model="form.meetingType"><option value="leader">安全生产领导小组会议</option><option value="monthly">安全生产月度会议</option></select></div>
              <div class="f-item"><label>会议方式</label><select v-model="form.mode"><option value="offline">线下（填地点）</option><option value="online">线上（填腾讯会议号/链接）</option></select></div>
              <div class="f-item" style="grid-column:1/3"><label>会议名称 *</label><input v-model="form.title" placeholder="如：2026年11月安全生产月度会议"></div>
              <div class="f-item" style="grid-column:1/3"><label>会议议题</label><input v-model="form.topic" placeholder="强调的问题、做出的决定、部署的工作"></div>
              <div class="f-item" v-if="form.mode === 'offline'"><label>会议地点</label><input v-model="form.location" placeholder="办公室"></div>
              <div class="f-item" v-if="form.mode === 'online'"><label>腾讯会议号</label><input v-model="form.meetingNo" placeholder="123 456 789"></div>
              <div class="f-item" v-if="form.mode === 'online'" style="grid-column:1/3"><label>会议链接</label><input v-model="form.link" placeholder="https://...（通用，不锁厂商）"></div>
              <div class="f-item"><label>开始时间</label><input v-model="form.startTime" type="datetime-local"></div>
              <div class="f-item"><label>结束时间</label><input v-model="form.endTime" type="datetime-local"></div>
              <div class="f-item"><label>主持人 *</label><select v-model="form.hostId"><option value="">未指定</option><option v-for="p in options.persons || []" :key="p.id" :value="String(p.id)">{{ p.name }}</option></select></div>
              <div class="f-item"><label>记录人 *</label><select v-model="form.recorderId"><option value="">未指定</option><option v-for="p in options.persons || []" :key="p.id" :value="String(p.id)">{{ p.name }}</option></select></div>
              <div class="f-item" style="grid-column:1/3"><label>参会人员（多选）</label>
                <div class="pf-choose">
                  <label v-for="p in options.persons || []" :key="p.id" class="pf-chip" :class="{ on: (form.attendeeIds||[]).includes(String(p.id)) }">
                    <input type="checkbox" :checked="(form.attendeeIds||[]).includes(String(p.id))" @change="toggleAtt(String(p.id))"> {{ p.name }}
                  </label>
                </div>
              </div>
            </template>

            <template v-if="dlg === 'record'">
              <div class="f-item" style="grid-column:1/3"><label>会议记录（富文本）</label><textarea v-model="form.content" rows="6" placeholder="会议记录正文"></textarea></div>
              <div class="f-item" style="grid-column:1/3"><label>会场照片 URL（逗号/换行分隔）</label><input v-model="form.venuePhotos" placeholder="https://.../photo1.jpg, https://.../photo2.jpg"></div>
              <div class="f-item" style="grid-column:1/3"><label>会议 PPT URL（逗号/换行分隔）</label><input v-model="form.pptFiles" placeholder="https://.../meeting.pptx"></div>
            </template>

            <template v-if="dlg === 'correct'">
              <div class="f-item" style="grid-column:1/3"><label>会议名称</label><input v-model="form.title"></div>
              <div class="f-item"><label>会议类型</label><select v-model="form.meetingType"><option value="leader">领导小组会议</option><option value="monthly">月度会议</option></select></div>
              <div class="f-item"><label>状态（超管可任意纠正）</label><select v-model="form.status"><option value="draft">草稿</option><option value="published">未开始</option><option value="ongoing">进行中</option><option value="finished">已结束</option><option value="archived">已归档</option><option value="cancelled">已取消</option></select></div>
              <div class="pf-sub" style="grid-column:1/3">仅服务商 / 超管账号可纠错，企业账号提交将返回 403。</div>
            </template>
          </div>
        </div>
        <div class="pf-modal-f">
          <button class="btn btn-outline" @click="dlg = ''">取消</button>
          <button class="btn btn-primary" :disabled="busy" @click="submitDlg">确定</button>
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
.bm-filter input, .bm-filter select { height: 36px; border: 1px solid var(--border); border-radius: 8px; padding: 0 10px; min-width: 160px; }
.bm-table { width: 100%; min-width: 980px; }
.bm-table a { cursor: pointer; margin-right: 6px; }
.pf-sub { font-size: 12.5px; color: var(--text-3); font-weight: 400; }
.pf-hint { background: #EFF6FF; border: 1px solid #93C5FD; border-radius: 8px; padding: 10px 14px; font-size: 13px; color: #1e40af; display: flex; align-items: center; gap: 8px; }
.pf-stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.stat-card { background: #fff; border: 1px solid var(--border); border-radius: 10px; padding: 16px 18px; }
.stat-card .num { font-size: 26px; font-weight: 700; color: var(--brand-600, #1a5bb0); line-height: 1.2; }
.stat-card .lbl { font-size: 12.5px; color: var(--text-3); margin-top: 4px; }
.pf-warn { padding: 16px 20px; border-left: 3px solid #F59E0B; }
.pf-warn-list { margin-top: 10px; display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.pf-warn-item { display: flex; align-items: center; gap: 8px; font-size: 13px; padding: 7px 10px; background: #FFFBEB; border-radius: 8px; flex-wrap: wrap; }
.pf-warn-item .nm { flex: 1; }
.pf-warn-item .dt { color: var(--text-3); font-size: 12px; }
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
.pf-choose { display: flex; flex-wrap: wrap; gap: 6px; max-height: 160px; overflow: auto; border: 1px solid var(--border); border-radius: 8px; padding: 8px; }
.pf-chip { display: inline-flex; align-items: center; gap: 4px; font-size: 12.5px; padding: 4px 8px; border: 1px solid var(--border); border-radius: 16px; cursor: pointer; background: #fff; }
.pf-chip.on { border-color: var(--brand-600, #1a5bb0); background: #EFF6FF; color: var(--brand-600, #1a5bb0); }
.pf-chip input { display: none; }
.pf-modal { width: 720px; max-width: 94vw; max-height: 88vh; margin: auto; background: #fff; border-radius: 12px; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,.25); }
.pf-modal-h { padding: 16px 22px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; }
.pf-modal-h h3 { margin: 0; font-size: 17px; }
.pf-modal-b { padding: 18px 22px; overflow: auto; }
.pf-modal-f { padding: 14px 22px; border-top: 1px solid var(--border); display: flex; justify-content: flex-end; gap: 10px; }
</style>
