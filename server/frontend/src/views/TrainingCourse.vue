<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import request from '@/api/request'
import Icon from '@/components/Icon.vue'
import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * C1 培训教育 · 线上学习（PC 下发端）
 * 对齐原型 pc/video_exam.html：课程库（多维筛选 / 分页 / 创建课程）+ 试题管理（卷子 / 出题）
 * 课程与考题为服务商全局课程库，企业在「岗中培训计划」里选用并下发给从业人员。
 */
const tab = ref('course')
const dict = ref({ categories: [], industries: [], posts: [], sources: [], scopes: [], questionTypes: [] })
const videos = ref([])
const courses = ref([])
const papers = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)

const kw = ref('')
const industry = ref('')
const post = ref('')
const catSet = ref(new Set())

const roleTag = computed(() => {
  const ut = localStorage.getItem('userType')
  return ut === 'provider' || ut === 'admin' ? '服务商（可管理）' : '企业视角（选用课程）'
})

async function loadAll() {
  await Promise.all([loadDict(), loadVideos(), loadCourses(), loadPapers()])
}
async function loadDict() {
  try { dict.value = (await request.get('/training/dict')).data || {} } catch (e) { /* 忽略 */ }
}
async function loadVideos() {
  try { videos.value = (await request.get('/training/video/list')).data || [] } catch (e) { /* 忽略 */ }
}
async function loadCourses() {
  loading.value = true
  try {
    const res = await request.get('/training/course/list', {
      params: {
        kw: kw.value, industry: industry.value, post: post.value,
        categories: [...catSet.value].join(','), page: page.value, pageSize: pageSize.value,
      },
    })
    const d = res.data || {}
    courses.value = d.records || []
    total.value = d.total || 0
  } finally { loading.value = false }
}
async function loadPapers() {
  try { papers.value = (await request.get('/training/paper/list')).data || [] } catch (e) { /* 忽略 */ }
}

function toggleCat(c) {
  const s = new Set(catSet.value)
  s.has(c) ? s.delete(c) : s.add(c)
  catSet.value = s
  page.value = 1
  loadCourses()
}
function resetFilter() {
  kw.value = ''; industry.value = ''; post.value = ''; catSet.value = new Set(); page.value = 1
  loadCourses()
}
function gotoPage(p) {
  const pages = Math.max(1, Math.ceil(total.value / pageSize.value))
  page.value = Math.min(pages, Math.max(1, p))
  loadCourses()
}
const pages = computed(() => Math.ceil(total.value / pageSize.value) || 1)

function switchTab(t) {
  tab.value = t
  if (t === 'paper') loadPapers()
}

// ===== 课程编辑 / 查看 =====
const showCourseEdit = ref(false)
const courseForm = reactive({ id: '', courseName: '', hours: 1, category: [], industry: '', post: [],
  duration: '', source: '服务商', scope: '全部', paperId: '', videoDesc: '', videoIds: [] })
const editingCats = ref(new Set())
const editingPosts = ref(new Set())

function openCourseEditor(row) {
  const c = row || null
  Object.assign(courseForm, {
    id: c ? String(c.id) : '', courseName: c ? c.course_name : '', hours: c ? c.hours : 1,
    category: c ? String(c.category || '').split(',').filter(Boolean) : [],
    industry: c ? c.industry : (dict.value.industries?.[0] || ''),
    post: c ? String(c.post || '').split(',').filter(Boolean) : [],
    duration: c ? c.duration : '', source: c ? c.source : '服务商', scope: c ? c.scope : '全部',
    paperId: c?.paper_id ? String(c.paper_id) : '', videoDesc: c ? c.video_desc : '',
    videoIds: [],
  })
  editingCats.value = new Set(courseForm.category)
  editingPosts.value = new Set(courseForm.post)
  if (c) {
    request.get(`/training/course/${c.id}`).then((res) => {
      courseForm.videoIds = (res.data?.videos || []).map((v) => String(v.id))
    })
  }
  showCourseEdit.value = true
}
function toggleEditChip(kind, val) {
  const s = kind === 'cat' ? new Set(editingCats.value) : new Set(editingPosts.value)
  s.has(val) ? s.delete(val) : s.add(val)
  if (kind === 'cat') editingCats.value = s; else editingPosts.value = s
}
async function saveCourse() {
  if (!courseForm.courseName.trim()) { ElMessage.warning('请填写课程名称'); return }
  await request.post('/training/course', {
    ...courseForm,
    id: courseForm.id || null,
    category: [...editingCats.value],
    post: [...editingPosts.value],
    paperId: courseForm.paperId || null,
  })
  ElMessage.success(courseForm.id ? '课程已更新' : '课程已创建')
  showCourseEdit.value = false
  loadCourses()
}
async function delCourse(row) {
  try {
    await ElMessageBox.confirm(`确认删除课程「${row.course_name}」？`, '删除课程', { type: 'warning' })
  } catch (e) { return }
  await request.delete(`/training/course/${row.id}`)
  ElMessage.success('课程已删除')
  loadCourses()
}

const showCourseView = ref(false)
const viewData = ref({})
function viewCourse(row) {
  request.get(`/training/course/${row.id}`).then((res) => {
    viewData.value = res.data || {}
    showCourseView.value = true
  })
}
function catList(s) { return String(s || '').split(',').filter(Boolean) }

// ===== 试题管理 =====
const showPaperView = ref(false)
const paperData = ref({})
const showQuestionEdit = ref(false)
const qForm = reactive({ paperId: '', stem: '', questionType: 'single', difficulty: '易', answer: '', options: [] })

function viewPaper(row) {
  request.get(`/training/paper/${row.id}`).then((res) => {
    paperData.value = res.data || {}
    showPaperView.value = true
  })
}
async function newPaper() {
  try {
    const { value } = await ElMessageBox.prompt('请输入卷子名称', '新建考试卷', {
      inputPlaceholder: '如：2026 年 11 月岗中安全教育考试卷',
    })
    if (!value?.trim()) return
    await request.post('/training/paper', { paperName: value.trim() })
    ElMessage.success('考试卷已创建')
    loadPapers()
  } catch (e) { /* 取消 */ }
}
async function delPaper(row) {
  try {
    await ElMessageBox.confirm(`确认删除卷子「${row.paper_name}」？绑定该卷子的课程将变为未绑定。`, '删除卷子', { type: 'warning' })
  } catch (e) { return }
  await request.delete(`/training/paper/${row.id}`)
  ElMessage.success('卷子已删除')
  loadPapers()
}
function openQuestionEditor() {
  Object.assign(qForm, { paperId: String(paperData.value.id), stem: '', questionType: 'single',
    difficulty: '易', answer: '', options: [
      { key: 'A', text: '' }, { key: 'B', text: '' }, { key: 'C', text: '' }, { key: 'D', text: '' },
    ] })
  showQuestionEdit.value = true
}
function onQTypeChange() {
  qForm.options = qForm.questionType === 'judge'
    ? [{ key: '正确', text: '正确' }, { key: '错误', text: '错误' }]
    : [{ key: 'A', text: '' }, { key: 'B', text: '' }, { key: 'C', text: '' }, { key: 'D', text: '' }]
  qForm.answer = ''
}
async function saveQuestion() {
  if (!qForm.stem.trim() || !qForm.answer.trim()) { ElMessage.warning('请填写题干与正确答案'); return }
  const opts = qForm.options.filter((o) => o.text.trim())
  if (qForm.questionType !== 'judge' && opts.length < 2) { ElMessage.warning('请至少填写两个选项'); return }
  await request.post(`/training/paper/${qForm.paperId}/question`, {
    stem: qForm.stem.trim(), questionType: qForm.questionType, difficulty: qForm.difficulty,
    answer: qForm.answer.trim(), options: opts,
  })
  ElMessage.success('试题已添加')
  showQuestionEdit.value = false
  const res = await request.get(`/training/paper/${qForm.paperId}`)
  paperData.value = res.data || {}
  loadPapers()
}
async function delQuestion(q) {
  await request.delete(`/training/question/${q.id}`)
  const res = await request.get(`/training/paper/${paperData.value.id}`)
  paperData.value = res.data || {}
  loadPapers()
}
function typeLabel(t) { return { single: '单选', multi: '多选', judge: '判断' }[t] || t }

onMounted(loadAll)
</script>

<template>
  <div class="pc-page">
    <div class="pc-header">
      <div>
        <h1>线上学习（课程库 + 试题管理）</h1>
        <div class="desc">服务商统一维护安全培训课程与考试卷，企业按岗位 / 行业选用并下发学习
          <span class="role-tag">{{ roleTag }}</span>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-outline btn-sm" @click="loadAll"><Icon name="refresh" :size="15" /> 刷新</button>
      </div>
    </div>

    <div class="layer-note">
      <Icon name="info" :size="16" />
      <div>
        <b>PC 端负责「下发数据」，小程序端负责「看和做」</b>
        <span>这里维护课程视频与考题 → 在<a href="javascript:;" @click="$router.push('/training/plan')">岗中培训计划</a>里选用课程并下发人员 → 从业人员在小程序端看视频、做考核 → <a href="javascript:;" @click="$router.push('/training/stat')">学习情况统计</a>实时汇总。</span>
      </div>
    </div>

    <div class="tabs">
      <button class="tab" :class="{ on: tab === 'course' }" @click="switchTab('course')">课程库</button>
      <button class="tab" :class="{ on: tab === 'paper' }" @click="switchTab('paper')">试题管理</button>
    </div>

    <!-- 课程库 -->
    <div v-show="tab === 'course'">
      <div class="filter-bar">
        <div class="filter-item"><span class="lbl">课程分类（可多选）</span>
          <div class="chips">
            <span v-for="c in dict.categories" :key="c" class="chip" :class="{ on: catSet.has(c) }" @click="toggleCat(c)">{{ c }}</span>
          </div>
        </div>
        <div class="filter-item"><span class="lbl">行业分类</span>
          <select v-model="industry" class="form-select" @change="page = 1; loadCourses()">
            <option value="">全部行业</option>
            <option v-for="x in dict.industries" :key="x" :value="x">{{ x }}</option>
          </select>
        </div>
        <div class="filter-item"><span class="lbl">岗位分类</span>
          <select v-model="post" class="form-select" @change="page = 1; loadCourses()">
            <option value="">全部岗位</option>
            <option v-for="x in dict.posts" :key="x" :value="x">{{ x }}</option>
          </select>
        </div>
        <div class="filter-item"><span class="lbl">课程名称</span>
          <input v-model="kw" class="form-input" placeholder="搜索课程" style="width: 170px" @keyup.enter="page = 1; loadCourses()" />
        </div>
        <div class="filter-item"><span class="lbl">&nbsp;</span>
          <button class="btn btn-outline btn-sm" @click="resetFilter">重置筛选</button>
        </div>
        <div class="spacer" />
        <button class="btn btn-primary btn-sm" @click="openCourseEditor()"><Icon name="plus" :size="15" /> 创建课程</button>
      </div>

      <div class="card">
        <div class="card-title">企业课程列表 <span class="tag tag-gray">{{ total }}</span></div>
        <table class="table">
          <thead><tr>
            <th>序号</th><th>操作</th><th>课程名称</th><th>课时数</th><th>课程分类</th>
            <th>总时长</th><th>课程来源</th><th>绑定考题</th><th>使用范围</th><th>创建时间</th>
          </tr></thead>
          <tbody>
            <tr v-for="(c, idx) in courses" :key="c.id">
              <td>{{ (page - 1) * pageSize + idx + 1 }}</td>
              <td class="ops">
                <a class="btn btn-primary btn-sm" @click="viewCourse(c)">查看</a>
                <a class="btn btn-outline btn-sm" @click="openCourseEditor(c)">编辑</a>
                <a class="btn btn-outline btn-sm" @click="delCourse(c)">删除</a>
              </td>
              <td class="tl">{{ c.course_name }}</td>
              <td>{{ c.hours }}</td>
              <td>{{ catList(c.category).join('、') || '—' }}</td>
              <td>{{ c.duration || '—' }}</td>
              <td>{{ c.source }}</td>
              <td><span class="tag" :class="c.paper_id ? 'tag-blue' : 'tag-gray'">{{ c.paper_name || '未绑定' }}</span></td>
              <td>{{ c.scope }}</td>
              <td>{{ String(c.created_at || '').slice(0, 10) }}</td>
            </tr>
            <tr v-if="!courses.length"><td colspan="10" class="empty">暂无课程，点击右上角「创建课程」新建</td></tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        共 {{ total }} 条，{{ pages }} 页，当前第 {{ page }} 页
        <button class="pg-btn" :disabled="page <= 1" @click="gotoPage(1)">首页</button>
        <button class="pg-btn" :disabled="page <= 1" @click="gotoPage(page - 1)">上一页</button>
        <button v-for="p in Math.min(6, pages)" :key="p" class="pg-btn" :class="{ on: p === page }" @click="gotoPage(p)">{{ p }}</button>
        <button class="pg-btn" :disabled="page >= pages" @click="gotoPage(page + 1)">下一页</button>
        <select v-model.number="pageSize" class="form-select" style="width: auto; min-width: 0; display: inline-block; min-height: 32px" @change="page = 1; loadCourses()">
          <option :value="10">10</option><option :value="20">20</option><option :value="50">50</option>
        </select> 条/页
      </div>
    </div>

    <!-- 试题管理 -->
    <div v-show="tab === 'paper'">
      <div class="filter-bar">
        <div class="filter-item"><span class="lbl">考试卷列表</span></div>
        <div class="spacer" />
        <button class="btn btn-primary btn-sm" @click="newPaper"><Icon name="plus" :size="15" /> 新建卷子</button>
      </div>
      <div class="card">
        <table class="table">
          <thead><tr><th>序号</th><th>卷子名称</th><th>题目数</th><th>绑定课程</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="(p, idx) in papers" :key="p.id">
              <td>{{ idx + 1 }}</td>
              <td class="tl">{{ p.paper_name }}</td>
              <td>{{ p.question_count }}</td>
              <td>{{ p.bind_count || 0 }} 门</td>
              <td><span class="tag" :class="p.question_count > 0 ? 'tag-blue' : 'tag-gray'">{{ p.question_count > 0 ? '已出题' : '待出题' }}</span></td>
              <td class="ops">
                <a class="btn btn-outline btn-sm" @click="delPaper(p)">删除</a>
                <a class="btn btn-primary btn-sm" @click="viewPaper(p)">查看 / 出题</a>
              </td>
            </tr>
            <tr v-if="!papers.length"><td colspan="6" class="empty">暂无考试卷，点击右上角「新建卷子」</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 课程编辑弹窗 -->
    <div class="modal-mask" :class="{ show: showCourseEdit }" @click.self="showCourseEdit = false">
      <div class="modal-box">
        <div class="modal-head"><span>{{ courseForm.id ? '编辑课程' : '创建课程' }}</span><span class="x" @click="showCourseEdit = false">×</span></div>
        <div class="modal-body">
          <div class="form-mini">
            <div class="form-item"><label class="form-label">课程名称 <span class="req">*</span></label>
              <input v-model="courseForm.courseName" class="form-input" placeholder="如：冬季行车安全要点" /></div>
            <div class="filter-item"><span class="lbl">课程分类（可多选）</span>
              <div class="chips"><span v-for="c in dict.categories" :key="c" class="chip" :class="{ on: editingCats.has(c) }" @click="toggleEditChip('cat', c)">{{ c }}</span></div>
            </div>
            <div class="filter-item"><span class="lbl">岗位分类（可多选）</span>
              <div class="chips"><span v-for="p in dict.posts" :key="p" class="chip" :class="{ on: editingPosts.has(p) }" @click="toggleEditChip('post', p)">{{ p }}</span></div>
            </div>
            <div class="form-row">
              <div class="form-item"><label class="form-label">行业分类</label>
                <select v-model="courseForm.industry" class="form-select"><option v-for="x in dict.industries" :key="x" :value="x">{{ x }}</option></select></div>
              <div class="form-item"><label class="form-label">使用范围</label>
                <select v-model="courseForm.scope" class="form-select"><option v-for="x in dict.scopes" :key="x" :value="x">{{ x }}</option></select></div>
            </div>
            <div class="form-row">
              <div class="form-item"><label class="form-label">来源</label>
                <select v-model="courseForm.source" class="form-select"><option v-for="x in dict.sources" :key="x" :value="x">{{ x }}</option></select></div>
              <div class="form-item"><label class="form-label">课时数</label>
                <input v-model.number="courseForm.hours" class="form-input" type="number" min="1" /></div>
            </div>
            <div class="form-item"><label class="form-label">课程视频（可多选）</label>
              <div class="vid-pick">
                <label v-for="v in videos" :key="v.id" class="vid-opt">
                  <input v-model="courseForm.videoIds" type="checkbox" :value="String(v.id)" />
                  <span>{{ v.video_name }}</span><em>{{ v.duration_sec }}s</em>
                </label>
              </div>
            </div>
            <div class="form-item"><label class="form-label">绑定考题（考试卷）</label>
              <select v-model="courseForm.paperId" class="form-select">
                <option value="">（不绑定）</option>
                <option v-for="p in papers" :key="p.id" :value="String(p.id)">{{ p.paper_name }}</option>
              </select></div>
            <div class="form-item"><label class="form-label">培训内容文字描述（台账「培训内容」来源）</label>
              <textarea v-model="courseForm.videoDesc" class="form-input" rows="2" placeholder="用于生成培训台账的培训内容"></textarea></div>
          </div>
        </div>
        <div class="modal-foot">
          <button class="btn btn-outline" @click="showCourseEdit = false">取消</button>
          <button class="btn btn-primary" @click="saveCourse">{{ courseForm.id ? '保存修改' : '创建课程' }}</button>
        </div>
      </div>
    </div>

    <!-- 课程详情弹窗 -->
    <div class="modal-mask" :class="{ show: showCourseView }" @click.self="showCourseView = false">
      <div class="modal-box">
        <div class="modal-head"><span>课程详情 · {{ viewData.course_name }}</span><span class="x" @click="showCourseView = false">×</span></div>
        <div class="modal-body">
          <div class="tag-row">
            <span v-for="c in catList(viewData.category)" :key="c" class="tag tag-blue">{{ c }}</span>
            <span class="tag tag-gray">{{ viewData.industry }}</span>
            <span class="tag tag-gray">{{ viewData.post }}</span>
            <span class="tag">课时 {{ viewData.hours }}</span>
            <span class="tag">时长 {{ viewData.duration }}</span>
            <span class="tag" :class="viewData.source === '平台' ? 'tag-green' : 'tag-blue'">{{ viewData.source }}</span>
          </div>
          <p v-if="viewData.video_desc" class="desc-line">{{ viewData.video_desc }}</p>
          <div class="mini-h">课程视频</div>
          <div v-for="v in viewData.videos || []" :key="v.id" class="vid-item">
            <Icon name="video" :size="15" /><span>{{ v.video_name }}</span><span class="vd">{{ v.duration_sec }} 秒</span>
          </div>
          <div v-if="!(viewData.videos || []).length" class="empty">暂无视频</div>
          <div class="mini-h">绑定考题：{{ viewData.paper_name || '未绑定' }}（{{ (viewData.questions || []).length }} 题）</div>
          <table class="mini-table" v-if="(viewData.questions || []).length">
            <thead><tr><th>题干</th><th>类型</th><th>难度</th><th>答案</th></tr></thead>
            <tbody><tr v-for="q in viewData.questions" :key="q.id">
              <td class="tl">{{ q.stem }}</td><td>{{ typeLabel(q.question_type) }}</td><td>{{ q.difficulty }}</td><td>{{ q.answer }}</td>
            </tr></tbody>
          </table>
          <div v-else class="empty">未绑定考题</div>
        </div>
        <div class="modal-foot"><button class="btn btn-primary" @click="showCourseView = false">关闭</button></div>
      </div>
    </div>

    <!-- 卷子详情 / 出题弹窗 -->
    <div class="modal-mask" :class="{ show: showPaperView }" @click.self="showPaperView = false">
      <div class="modal-box">
        <div class="modal-head"><span>出题 · {{ paperData.paper_name }}</span><span class="x" @click="showPaperView = false">×</span></div>
        <div class="modal-body">
          <button class="btn btn-primary btn-sm" style="margin-bottom: 12px" @click="openQuestionEditor"><Icon name="plus" :size="15" /> 新增试题</button>
          <table class="mini-table">
            <thead><tr><th>序号</th><th>题干</th><th>类型</th><th>难度</th><th>答案</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="(q, idx) in paperData.questions || []" :key="q.id">
                <td>{{ idx + 1 }}</td><td class="tl">{{ q.stem }}</td><td>{{ typeLabel(q.question_type) }}</td>
                <td>{{ q.difficulty }}</td><td>{{ q.answer }}</td>
                <td><a class="btn btn-outline btn-sm" @click="delQuestion(q)">删除</a></td>
              </tr>
              <tr v-if="!(paperData.questions || []).length"><td colspan="6" class="empty">本卷暂无试题</td></tr>
            </tbody>
          </table>
        </div>
        <div class="modal-foot"><button class="btn btn-primary" @click="showPaperView = false">关闭</button></div>
      </div>
    </div>

    <!-- 新增试题弹窗（二级） -->
    <div class="modal-mask sub-mask" :class="{ show: showQuestionEdit }" @click.self="showQuestionEdit = false">
      <div class="modal-box">
        <div class="modal-head"><span>新增试题 · {{ paperData.paper_name }}</span><span class="x" @click="showQuestionEdit = false">×</span></div>
        <div class="modal-body">
          <div class="form-mini">
            <div class="form-item"><label class="form-label">题干 <span class="req">*</span></label>
              <textarea v-model="qForm.stem" class="form-input" rows="2" placeholder="如：驾驶员连续驾驶不得超过几小时？"></textarea></div>
            <div class="form-row">
              <div class="form-item"><label class="form-label">类型</label>
                <select v-model="qForm.questionType" class="form-select" @change="onQTypeChange">
                  <option value="single">单选</option><option value="multi">多选</option><option value="judge">判断</option>
                </select></div>
              <div class="form-item"><label class="form-label">难度</label>
                <select v-model="qForm.difficulty" class="form-select"><option>易</option><option>中</option><option>难</option></select></div>
            </div>
            <div class="form-item"><label class="form-label">选项（留空自动忽略）</label>
              <div v-for="(o, idx) in qForm.options" :key="idx" class="opt-row">
                <span class="opt-key">{{ o.key }}</span>
                <input v-model="o.text" class="form-input" :disabled="qForm.questionType === 'judge'" :placeholder="'选项 ' + o.key" />
              </div>
            </div>
            <div class="form-item"><label class="form-label">正确答案 <span class="req">*</span></label>
              <input v-model="qForm.answer" class="form-input" :placeholder="qForm.questionType === 'multi' ? '多个用逗号分隔，如 A,B,C' : (qForm.questionType === 'judge' ? '正确 / 错误' : '如 B')" />
              <div class="hint-line">单选/判断填选项 key 或「正确/错误」；多选填多个 key，用英文逗号分隔。</div>
            </div>
          </div>
        </div>
        <div class="modal-foot">
          <button class="btn btn-outline" @click="showQuestionEdit = false">取消</button>
          <button class="btn btn-primary" @click="saveQuestion">添加</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.layer-note { display: flex; align-items: flex-start; gap: 10px; padding: 11px 16px; margin-bottom: 14px;
  background: #EFF6FF; border: 1px solid #BFDBFE; border-radius: 8px; color: #1E40AF; font-size: 13px; line-height: 1.7; }
.layer-note b { display: block; font-size: 13.5px; margin-bottom: 2px; }
.layer-note span { color: #475569; }
.layer-note a { color: #1D4ED8; font-weight: 600; text-decoration: underline; }
.role-tag { font-size: 11px; padding: 2px 9px; border-radius: 8px; background: #DBEAFE; color: #1E40AF; font-weight: 600; margin-left: 8px; }

.tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--border); margin-bottom: 16px; }
.tab { border: none; background: none; padding: 12px 18px; font-size: 14px; font-weight: 600; color: var(--text-3); cursor: pointer; border-bottom: 2px solid transparent; transition: .12s; }
.tab:hover { color: var(--brand-600); }
.tab.on { color: var(--brand-600); border-bottom-color: var(--brand-600); }

.filter-bar { display: flex; align-items: flex-end; gap: 14px; flex-wrap: wrap; margin-bottom: 14px; }
.filter-item { display: flex; flex-direction: column; gap: 6px; }
.filter-item .lbl { font-size: 12px; color: var(--text-3); font-weight: 600; }
.spacer { flex: 1; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; max-width: 760px; }
.chip { padding: 5px 11px; border: 1px solid var(--border-strong); border-radius: 20px; font-size: 12.5px; cursor: pointer; background: #fff; color: var(--text-2); transition: .12s; user-select: none; }
.chip:hover { border-color: var(--brand-400); }
.chip.on { background: var(--brand-500); border-color: var(--brand-500); color: #fff; }
.form-select, .form-input { min-height: 34px; font-size: 13px; }
.form-select { padding: 6px 10px; border: 1px solid var(--border-strong); border-radius: 6px; background: #fff; }
.form-input { padding: 6px 10px; border: 1px solid var(--border-strong); border-radius: 6px; width: 100%; }
.form-row { display: flex; gap: 12px; }
.form-row .form-item { flex: 1; }
.form-label { font-size: 12.5px; font-weight: 600; color: var(--text-2); display: block; margin-bottom: 5px; }
.form-mini { display: flex; flex-direction: column; gap: 12px; }
.req { color: #DC2626; }

.table th, .table td { font-size: 13px; }
.table td.tl, .mini-table td.tl { text-align: left; }
.ops { white-space: nowrap; }
.ops .btn { margin-right: 4px; }
.empty { padding: 24px; text-align: center; color: var(--text-3); font-size: 14px; }
.pager { display: flex; align-items: center; gap: 8px; margin-top: 14px; flex-wrap: wrap; font-size: 13px; color: var(--text-3); }
.pg-btn { border: 1px solid var(--border); background: #fff; border-radius: 6px; padding: 5px 10px; cursor: pointer; font-size: 13px; }
.pg-btn:hover:not(:disabled) { border-color: var(--brand-400); }
.pg-btn:disabled { opacity: .45; cursor: not-allowed; }
.pg-btn.on { background: var(--brand-500); color: #fff; border-color: var(--brand-500); }

.modal-mask { position: fixed; inset: 0; background: rgba(15, 23, 42, .48); display: none; align-items: center; justify-content: center; z-index: 200; padding: 20px; }
.modal-mask.show { display: flex; }
.modal-mask.sub-mask { z-index: 210; }
.modal-box { background: #fff; border-radius: 12px; width: min(720px, 100%); max-height: 90vh; overflow-y: auto; box-shadow: 0 8px 40px rgba(0,0,0,.28); }
.modal-head { display: flex; align-items: center; justify-content: space-between; padding: 16px 22px; border-bottom: 1px solid var(--border); font-size: 16px; font-weight: 700; }
.modal-head .x { cursor: pointer; color: var(--text-3); font-size: 22px; line-height: 1; }
.modal-body { padding: 18px 22px; }
.modal-foot { padding: 14px 22px; border-top: 1px solid var(--border); display: flex; justify-content: flex-end; gap: 10px; }
.mini-h { font-size: 13px; font-weight: 700; color: var(--text-2); margin: 14px 0 8px; }
.mini-table { width: 100%; border-collapse: collapse; }
.mini-table th, .mini-table td { border: 1px solid var(--border); padding: 8px 10px; text-align: left; font-size: 12.5px; line-height: 1.6; }
.mini-table th { background: #f8fafd; font-weight: 700; color: var(--text-2); }
.vid-item { display: flex; align-items: center; gap: 8px; padding: 9px 12px; border: 1px solid var(--border); border-radius: 8px; margin-bottom: 6px; font-size: 13px; background: #fff; }
.vid-item .vd { margin-left: auto; color: var(--text-3); font-size: 12px; }
.tag-row { display: flex; gap: 8px; flex-wrap: wrap; }
.desc-line { font-size: 13px; color: var(--text-2); line-height: 1.8; margin: 12px 0 0; }
.vid-pick { display: flex; flex-direction: column; gap: 4px; max-height: 160px; overflow-y: auto; border: 1px solid var(--border); border-radius: 8px; padding: 8px; }
.vid-opt { display: flex; align-items: center; gap: 8px; font-size: 13px; padding: 5px 6px; border-radius: 6px; cursor: pointer; }
.vid-opt:hover { background: #F8FAFC; }
.vid-opt em { margin-left: auto; color: var(--text-3); font-style: normal; font-size: 12px; }
.opt-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.opt-key { width: 32px; text-align: center; font-weight: 700; color: var(--brand-600); }
.hint-line { font-size: 12px; color: var(--text-3); margin-top: 5px; }
</style>
