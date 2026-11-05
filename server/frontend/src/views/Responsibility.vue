<script setup>
import { ref, onMounted } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'

const rows = ref([])
const loading = ref(false)
const generating = ref(false)
const activeNames = ref(['0'])

async function load() {
  loading.value = true
  try {
    const res = await request.get('/tpl/responsibility')
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

// 生成并归档
async function generateArchive() {
  generating.value = true
  try {
    const res = await request.post('/archive/responsibility/generate')
    ElMessage.success(`已生成并归档：${res.data.fileName}`)
  } finally {
    generating.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never" v-loading="loading">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span style="font-weight: 700">安全生产责任制</span>
        <el-button type="primary" :loading="generating" @click="generateArchive">
          生成并归档
        </el-button>
      </div>
    </template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
      title="责任制内容动态生成：读启用岗位的目标/职责/考核，岗位改名即联动，禁用岗位自动剔除。"
    />

    <el-collapse v-model="activeNames">
      <el-collapse-item
        v-for="(r, idx) in rows"
        :key="r.funcKey"
        :name="String(idx)"
      >
        <template #title>
          <b>{{ r.name }}</b>
          <span style="color: var(--text-3); margin-left: 8px">{{ r.funcKey }}</span>
        </template>

        <el-descriptions :column="1" border>
          <el-descriptions-item label="岗位目标">
            <ul class="plain-list">
              <li v-for="(g, i) in r.goals" :key="i">{{ g }}</li>
            </ul>
          </el-descriptions-item>
          <el-descriptions-item label="岗位职责">
            <ul class="plain-list">
              <li v-for="(d, i) in r.duties" :key="i">{{ d }}</li>
            </ul>
          </el-descriptions-item>
          <el-descriptions-item label="考核标准">
            <ul class="plain-list">
              <li v-for="(a, i) in r.assess" :key="i">{{ a }}</li>
            </ul>
          </el-descriptions-item>
        </el-descriptions>
      </el-collapse-item>
    </el-collapse>
  </el-card>
</template>

<style scoped>
.plain-list {
  margin: 0;
  padding-left: 18px;
}
.plain-list li {
  margin: 2px 0;
}
</style>
