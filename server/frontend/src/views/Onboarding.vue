<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/api/request'

/**
 * E2 新企业接入引导（服务商 / 超管）。
 * 对齐《产品需求确认文档》§十一 阶段 0~5：开户 → 六阶段交付进度 → 检查项逐项推进。
 */
const router = useRouter()
const list = ref([])
const current = ref(null)
const status = ref(null)
const readiness = ref(null)
const openVisible = ref(false)
const openResult = ref(null)
const loading = ref(false)

const stageNames = [
  '签约与开户', '资料采集', '静态资料整改', '账号开通', '归档就位', '日常运营',
]
const statusLabel = {
  pending: '待接入', in_progress: '进行中', completed: '已完成', operating: '运营中',
}

const blankForm = () => ({
  name: '', credit_code: '', biz_type: 'GENERAL', region: '', contact: '', phone: '',
  contract_start: '', contract_end: '',
})
const form = ref(blankForm())

const groupedItems = computed(() => {
  const g = {}
  for (const it of status.value?.items || []) (g[it.stage] ||= []).push(it)
  return g
})
const progress = computed(() => status.value?.progress || { done: 0, total: 0, percent: 0 })

async function loadList() {
  try {
    const res = await request.get('/onboarding/list')
    list.value = res.data || []
  } catch (e) { /* 非平台账号无权限，忽略 */ }
}

async function select(row) {
  current.value = row
  readiness.value = null
  const res = await request.get('/onboarding/status', { params: { tenantId: row.tenant_id } })
  status.value = res.data
}

async function openAccount() {
  if (!form.value.name || !form.value.credit_code) {
    ElMessage.warning('企业全称与统一社会信用代码必填')
    return
  }
  loading.value = true
  try {
    const res = await request.post('/onboarding/open', form.value)
    openResult.value = res.data
    openVisible.value = false
    form.value = blankForm()
    ElMessage.success('开户成功，已自动创建企业主账号与 12 个默认岗位')
    await loadList()
  } finally {
    loading.value = false
  }
}

async function toggle(item) {
  if (!current.value) return
  const done = !item.done
  const res = await request.put(`/onboarding/items/${item.item_code}`, {
    tenant_id: current.value.tenant_id, done,
  })
  status.value = res.data
  await loadList()
}

async function checkReadiness() {
  if (!current.value) return
  const res = await request.get('/onboarding/readiness', { params: { tenantId: current.value.tenant_id } })
  readiness.value = res.data
  ElMessage.info(res.data.ready ? '归档就位自检通过' : '存在缺项，请按清单补齐')
}

function goto(item) {
  if (item.target_route) router.push(item.target_route)
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h2>新企业接入引导</h2>
        <p class="sub">签约 → 开户 → 资料采集 → 整改 → 归档就位 → 日常运营（六阶段）</p>
      </div>
      <el-button type="primary" size="large" @click="openVisible = true">＋ 签约开户</el-button>
    </div>

    <!-- 开户结果（初始凭据仅展示一次） -->
    <el-alert v-if="openResult" type="success" :closable="true" class="result" title="开户成功">
      <template #default>
        企业「{{ openResult.tenantName }}」已开通：
        主账号 <b>{{ openResult.username }}</b> / 初始密码 <b>{{ openResult.initialPassword }}</b>，
        营运类型 {{ openResult.bizType }}，已建 {{ openResult.defaultPosts }} 个默认岗位、
        {{ openResult.checkItems }} 项交付检查项。请提醒企业首次登录后立即修改密码。
      </template>
    </el-alert>

    <el-card shadow="never" class="card">
      <template #header><b>企业接入进度看板</b>（{{ list.length }} 家）</template>
      <el-table :data="list" size="small" stripe @row-click="select" highlight-current-row>
        <el-table-column prop="tenant_name" label="企业名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="credit_code" label="统一社会信用代码" width="190" />
        <el-table-column prop="biz_type" label="营运类型" width="100" />
        <el-table-column label="阶段" width="90">
          <template #default="{ row }">{{ row.stage ?? '-' }} · {{ stageNames[row.stage ?? 0] }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'operating' ? 'success' : 'warning'" size="small">
              {{ statusLabel[row.status] || '未接入' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="检查项进度" width="160">
          <template #default="{ row }">
            <el-progress :percentage="row.total_count ? Math.round(row.done_count * 100 / row.total_count) : 0"
                         :stroke-width="10" />
          </template>
        </el-table-column>
        <el-table-column prop="contract_end" label="合同到期" width="120" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="select(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 单企业六阶段详情 -->
    <el-card v-if="status" shadow="never" class="card">
      <template #header>
        <div class="card-head">
          <b>{{ current.tenant_name }} · 接入详情</b>
          <span class="sub">进度 {{ progress.done }}/{{ progress.total }}（{{ progress.percent }}%） · {{ status.stageName }}</span>
          <el-button size="small" @click="checkReadiness">归档就位自检</el-button>
        </div>
      </template>

      <el-steps :active="status.stage" finish-status="success" align-center class="steps">
        <el-step v-for="(n, i) in stageNames" :key="i" :title="`阶段${i}`" :description="n" />
      </el-steps>

      <div v-for="stage in [0,1,2,3,4,5]" :key="stage" class="stage-block">
        <div class="stage-title">阶段 {{ stage }} · {{ stageNames[stage] }}</div>
        <div v-for="it in (groupedItems[stage] || [])" :key="it.item_code" class="item-row">
          <el-checkbox :model-value="!!it.done" @change="toggle(it)">
            <span :class="{ done: it.done }">{{ it.item_name }}</span>
            <el-tag v-if="!it.required" size="small" type="info" class="opt">可选</el-tag>
          </el-checkbox>
          <el-button v-if="it.target_route" link type="primary" size="small" @click="goto(it)">
            前往办理 →
          </el-button>
        </div>
      </div>

      <div v-if="readiness" class="readiness">
        <b>归档就位自检</b> ·
        <el-tag :type="readiness.ready ? 'success' : 'danger'" size="small">
          {{ readiness.ready ? '通过' : '存在缺项' }}
        </el-tag>
        <span class="sub">耗时 {{ readiness.elapsedMs }} ms，归档项 {{ readiness.archiveItems }} 项，
          人员 {{ readiness.persons }}，车辆 {{ readiness.vehicles }}，检查项 {{ readiness.checkProgress }}</span>
        <ul>
          <li v-for="c in readiness.checks" :key="c.name">
            <span :class="c.pass ? 'pass' : 'fail'">{{ c.pass ? '✓' : '✗' }}</span>
            {{ c.name }}（期望 ≥{{ c.expectedAtLeast }}，实际 {{ c.actual }}）
          </li>
        </ul>
      </div>
    </el-card>

    <!-- 开户弹窗 -->
    <el-dialog v-model="openVisible" title="签约开户 · 阶段 0" width="560px">
      <el-form :model="form" label-width="130px">
        <el-form-item label="企业全称" required>
          <el-input v-model="form.name" placeholder="营业执照上的企业全称" />
        </el-form-item>
        <el-form-item label="统一社会信用代码" required>
          <el-input v-model="form.credit_code" placeholder="18 位信用代码" />
        </el-form-item>
        <el-form-item label="营运类型">
          <el-radio-group v-model="form.biz_type">
            <el-radio value="GENERAL">普货（10 盒）</el-radio>
            <el-radio value="DANGER">危货（11 盒）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="所属地区"><el-input v-model="form.region" /></el-form-item>
        <el-form-item label="安全主要负责人"><el-input v-model="form.contact" /></el-form-item>
        <el-form-item label="负责人电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="合同起始">
          <el-date-picker v-model="form.contract_start" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="合同到期">
          <el-date-picker v-model="form.contract_end" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="openVisible = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="openAccount">确认开户</el-button>
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
.card-head { display: flex; align-items: center; gap: 14px; }
.card-head .sub { flex: 1; }
.result { margin-bottom: 16px; }
.steps { margin: 8px 0 24px; }
.stage-block { margin-bottom: 14px; }
.stage-title { font-weight: 600; color: #0f1c3f; margin-bottom: 6px; font-size: 14px; }
.item-row { display: flex; justify-content: space-between; align-items: center; padding: 5px 8px; border-bottom: 1px dashed #e2e8f0; }
.done { color: #94a3b8; text-decoration: line-through; }
.opt { margin-left: 8px; }
.readiness { margin-top: 18px; padding: 14px; background: #f8fafc; border-radius: 8px; }
.readiness ul { margin: 8px 0 0; padding-left: 20px; }
.pass { color: #16a34a; font-weight: 700; }
.fail { color: #dc2626; font-weight: 700; }
</style>
