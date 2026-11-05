<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/api/request'
import { ElMessage } from 'element-plus'
import { bizModules } from '@/config/modules.generated.js'
import BizTable from '@/components/BizTable.vue'
import BizForm from '@/components/BizForm.vue'

/**
 * A5 通用业务视图：一个路由承载一个模块下的所有表（Tab 切换）。
 * 新增业务模块只需在 tools/gen_biz_meta.py 配置表清单，无需手写页面。
 */
const route = useRoute()
const module = computed(() => bizModules.find((m) => m.code === route.params.module) || null)
const activeTable = ref('')
const fields = ref([])
const dialogVisible = ref(false)
const editing = ref(false)
const form = ref({})
const tableRef = ref(null)
const saving = ref(false)

watch(
  module,
  (m) => {
    if (m && m.tables.length) switchTable(m.tables[0].name)
  },
  { immediate: true },
)

async function switchTable(name) {
  activeTable.value = name
  const res = await request.get(`/biz/${name}/fields`)
  fields.value = res.data.fields || []
  if (tableRef.value) tableRef.value.reload()
}

function openCreate() {
  editing.value = false
  form.value = {}
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = true
  form.value = { ...row }
  dialogVisible.value = true
}

async function save() {
  saving.value = true
  try {
    // 只提交元数据允许的可写字段，避免把数据库返回的系统列回写
    const allowed = new Set(fields.value.filter((f) => f.control !== 'hidden').map((f) => f.name))
    const payload = {}
    for (const [k, v] of Object.entries(form.value)) {
      if (allowed.has(k) && v !== null && v !== '') payload[k] = v
    }
    if (editing.value) {
      await request.put(`/biz/${activeTable.value}/${form.value.id}`, payload)
    } else {
      await request.post(`/biz/${activeTable.value}`, payload)
    }
    ElMessage.success('保存成功（已自动登记归档项）')
    dialogVisible.value = false
    tableRef.value?.reload()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="module" class="biz-view">
    <el-card shadow="never" class="head">
      <div class="head-row">
        <div>
          <h2>{{ module.title }}</h2>
          <p class="sub">
            阶段 {{ module.phase }} · {{ module.tables.length }} 张业务表 ·
            归档归属 <el-tag size="small" type="success" effect="plain">{{ module.catalog || '独立档案袋' }}</el-tag>
          </p>
        </div>
        <el-tag type="info" effect="plain">{{ module.group }}</el-tag>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="activeTable" @tab-change="switchTable">
        <el-tab-pane v-for="t in module.tables" :key="t.name" :label="t.title" :name="t.name" />
      </el-tabs>
      <BizTable
        ref="tableRef"
        :table="activeTable"
        :fields="fields"
        @create="openCreate"
        @edit="openEdit"
      />
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="(editing ? '编辑' : '新增') + ' · ' + (module.tables.find(t => t.name === activeTable)?.title || '')"
      width="720px"
      destroy-on-close
    >
      <BizForm v-model="form" :fields="fields" :biz-module="module.code" />
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
  <el-empty v-else description="未找到该业务模块" />
</template>

<style scoped>
.biz-view { display: flex; flex-direction: column; gap: 14px; }
.head-row { display: flex; justify-content: space-between; align-items: flex-start; }
h2 { margin: 0 0 6px; font-size: 19px; }
.sub { margin: 0; font-size: 13px; color: var(--el-text-color-secondary); }
</style>
