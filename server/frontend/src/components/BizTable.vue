<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import request from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * A5 通用表格：分页 / 关键字搜索 / 编辑 / 逻辑删除，字段由元数据驱动。
 */
const props = defineProps({
  table: { type: String, required: true },
  fields: { type: Array, default: () => [] },
  title: { type: String, default: '' },
})
const emit = defineEmits(['edit', 'create'])

const rows = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const loading = ref(false)

const columns = computed(() =>
  (props.fields || [])
    .filter((f) => f.control !== 'hidden' && f.name !== 'id')
    .slice(0, 7),
)

async function reload() {
  loading.value = true
  try {
    const res = await request.get(`/biz/${props.table}`, {
      params: { pageNo: pageNo.value, pageSize: pageSize.value, keyword: keyword.value },
    })
    rows.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function remove(row) {
  await ElMessageBox.confirm('确认删除这条记录？删除后将同步撤下对应归档项。', '提示', { type: 'warning' })
  await request.delete(`/biz/${props.table}/${row.id}`)
  ElMessage.success('已删除')
  reload()
}

function short(v) {
  const s = v == null ? '' : String(v)
  return s.length > 40 ? s.slice(0, 40) + '…' : s
}

watch(() => props.table, () => { pageNo.value = 1; reload() })
onMounted(reload)
defineExpose({ reload })
</script>

<template>
  <div class="biz-table">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="关键字搜索"
        clearable
        style="width: 240px"
        @keyup.enter="pageNo = 1; reload()"
      />
      <el-button @click="pageNo = 1; reload()">查询</el-button>
      <el-button type="primary" @click="emit('create')">新增</el-button>
      <span class="spacer" />
      <span class="count">共 {{ total }} 条</span>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe size="small" style="width: 100%">
      <el-table-column prop="id" label="ID" width="170" />
      <el-table-column
        v-for="c in columns"
        :key="c.name"
        :prop="c.name"
        :label="c.label"
        min-width="130"
        show-overflow-tooltip
      >
        <template #default="{ row }">{{ short(row[c.name]) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="emit('edit', row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>暂无数据，点击「新增」录入</template>
    </el-table>

    <el-pagination
      class="pager"
      background
      layout="prev, pager, next, sizes"
      :total="total"
      v-model:current-page="pageNo"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="reload"
      @size-change="pageNo = 1; reload()"
    />
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.spacer { flex: 1; }
.count { font-size: 13px; color: var(--el-text-color-secondary); }
.pager { margin-top: 14px; justify-content: flex-end; }
</style>
