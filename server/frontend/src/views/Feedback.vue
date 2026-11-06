<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/api/request'
import { useAuthStore } from '@/store/auth'

/**
 * E3 种子客户试运行反馈闭环。
 * 企业/服务商提交反馈，平台受理→解决/驳回，看板展示解决率与分布。
 */
const auth = useAuthStore()
const isPlatform = computed(() => ['provider', 'admin'].includes(auth.userType))

const list = ref([])
const summary = ref(null)
const total = ref(0)
const filter = ref({ status: '', feedbackType: '' })
const submitting = ref(false)
const handleVisible = ref(false)
const handleForm = ref({ id: null, status: 'resolved', handle_note: '' })
const current = ref(null)

const form = ref({ module: '', feedback_type: 'suggestion', severity: 2, content: '', contact: '' })

const typeLabel = { bug: '缺陷', suggestion: '建议', praise: '表扬', question: '咨询' }
const statusLabel = { pending: '待受理', processing: '处理中', resolved: '已解决', rejected: '已驳回' }
const statusType = { pending: 'warning', processing: 'primary', resolved: 'success', rejected: 'info' }
const severityLabel = { 1: '致命', 2: '一般', 3: '轻微' }

async function load() {
  const params = { pageSize: 100 }
  if (filter.value.status) params.status = filter.value.status
  if (filter.value.feedbackType) params.feedbackType = filter.value.feedbackType
  const res = await request.get('/feedback', { params })
  list.value = res.data.records || []
  total.value = res.data.total || 0
  try {
    const s = await request.get('/feedback/summary')
    summary.value = s.data
  } catch (e) { /* 权限不足时忽略 */ }
}

async function submit() {
  if (!form.value.content.trim()) { ElMessage.warning('请填写反馈内容'); return }
  submitting.value = true
  try {
    await request.post('/feedback', form.value)
    ElMessage.success('反馈已提交，平台会尽快受理')
    form.value = { module: '', feedback_type: 'suggestion', severity: 2, content: '', contact: '' }
    await load()
  } finally { submitting.value = false }
}

function openHandle(row) {
  current.value = row
  handleForm.value = { id: row.id, status: 'resolved', handle_note: '' }
  handleVisible.value = true
}

async function doHandle() {
  await request.put(`/feedback/${handleForm.value.id}`, handleForm.value)
  ElMessage.success('处理已记录')
  handleVisible.value = false
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h2>试运行反馈</h2>
        <p class="sub">提交 → 受理 → 解决/驳回 → 汇总报告，形成种子客户反馈闭环</p>
      </div>
      <el-tag>{{ isPlatform ? '平台视角（全量反馈）' : '企业视角（本企业反馈）' }}</el-tag>
    </div>

    <!-- 汇总卡 -->
    <el-row v-if="summary" :gutter="12" class="cards">
      <el-col :span="6"><el-card shadow="never"><div class="kpi"><b>{{ summary.total }}</b><span>反馈总数</span></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="kpi"><b>{{ summary.open }}</b><span>未闭环</span></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="kpi"><b>{{ summary.closed }}</b><span>已闭环</span></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="kpi"><b>{{ summary.resolveRate }}%</b><span>解决率</span></div></el-card></el-col>
    </el-row>

    <el-card shadow="never" class="card">
      <template #header><b>提交反馈</b></template>
      <el-form :model="form" label-width="90px" class="form">
        <el-form-item label="所属模块">
          <el-input v-model="form.module" placeholder="如：一日三检 / 电子档案柜 / 范本套管理" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.feedback_type">
            <el-option v-for="(v, k) in typeLabel" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="严重程度">
          <el-radio-group v-model="form.severity">
            <el-radio v-for="(v, k) in severityLabel" :key="k" :value="Number(k)">{{ v }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="联系方式"><el-input v-model="form.contact" placeholder="选填，便于回访" /></el-form-item>
        <el-form-item label="反馈内容" required>
          <el-input v-model="form.content" type="textarea" :rows="3" maxlength="1000" show-word-limit
                    placeholder="请描述遇到的问题或建议，尽量写明操作步骤" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submit">提交反馈</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-head">
          <b>反馈列表（{{ total }}）</b>
          <el-select v-model="filter.status" clearable placeholder="全部状态" size="small" style="width:130px" @change="load">
            <el-option v-for="(v, k) in statusLabel" :key="k" :label="v" :value="k" />
          </el-select>
          <el-select v-model="filter.feedbackType" clearable placeholder="全部类型" size="small" style="width:120px" @change="load">
            <el-option v-for="(v, k) in typeLabel" :key="k" :label="v" :value="k" />
          </el-select>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>
      <el-table :data="list" size="small" stripe>
        <el-table-column prop="module" label="模块" width="150" show-overflow-tooltip />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ typeLabel[row.feedback_type] || row.feedback_type }}</template>
        </el-table-column>
        <el-table-column label="级别" width="80">
          <template #default="{ row }">{{ severityLabel[row.severity] || '-' }}</template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType[row.status]" size="small">{{ statusLabel[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="created_at" label="提交时间" width="165" />
        <el-table-column v-if="isPlatform" label="操作" width="100">
          <template #default="{ row }">
            <el-button v-if="!['resolved','rejected'].includes(row.status)" link type="primary" @click="openHandle(row)">处理</el-button>
            <span v-else class="done">已闭环</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="handleVisible" title="处理反馈" width="460px">
      <p class="sub">「{{ current?.content?.slice(0, 60) }}」</p>
      <el-form label-width="90px">
        <el-form-item label="处理结果">
          <el-radio-group v-model="handleForm.status">
            <el-radio value="processing">标记处理中</el-radio>
            <el-radio value="resolved">已解决</el-radio>
            <el-radio value="rejected">已驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="处理说明">
          <el-input v-model="handleForm.handle_note" type="textarea" :rows="3" placeholder="修复版本 / 驳回理由" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button type="primary" @click="doHandle">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 20px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
h2 { margin: 0 0 4px; font-size: 20px; }
.sub { color: #64748b; font-size: 13px; margin: 0; }
.card { margin-bottom: 16px; }
.card-head { display: flex; align-items: center; gap: 10px; }
.cards { margin-bottom: 16px; }
.kpi { display: flex; flex-direction: column; align-items: center; }
.kpi b { font-size: 26px; color: #0f1c3f; }
.kpi span { font-size: 13px; color: #64748b; }
.form { max-width: 720px; }
.done { color: #94a3b8; font-size: 12px; }
</style>
