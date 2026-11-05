<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/api/request'

/**
 * B9 小程序端（独立 H5 形态，适老化重设计）。
 * 决策：选 H5 而非微信小程序原生 —— 免审核、可复用现有前端、最快上线。
 * 验收标准 1：不会用电脑的中老年司机 30 秒内独立完成一次三检。
 * 交互只有三步：选任务 → 选时段 → 点「合格」或「异常」，全大按钮、大字号、高对比。
 */
const route = useRoute()
const tasks = ref([])
const taskId = ref(null)
const phase = ref('out')
const note = ref('')
const done = ref(false)
const resultText = ref('')
const submitting = ref(false)

const phases = [
  { key: 'out', label: '出车前' },
  { key: 'mid', label: '行车中' },
  { key: 'in', label: '收车后' },
]
const phaseLabel = computed(() => phases.find((p) => p.key === phase.value)?.label || '')

onMounted(async () => {
  const t = route.query.token
  if (t) localStorage.setItem('token', String(t))
  try {
    const res = await request.get('/biz/check_task', { params: { pageSize: 50 } })
    tasks.value = res.data.records || []
    if (tasks.value.length) taskId.value = tasks.value[0].id
  } catch (e) { /* 未登录时仅提示 */ }
})

async function submit(result) {
  if (!taskId.value) { resultText.value = '请先由安全员创建三检任务'; done.value = true; return }
  submitting.value = true
  try {
    const res = await request.post('/flow/check/record', {
      task_id: taskId.value, phase: phase.value, result,
      abnormal_note: result === 'abnormal' ? note.value : null,
    })
    resultText.value = result === 'pass'
      ? `${phaseLabel.value}检查完成，合格 ✔`
      : `已上报异常${res.data.dangerId ? '，并自动生成隐患整改单' : ''}`
  } catch (e) {
    resultText.value = '提交失败：' + (e.message || '请检查网络')
  } finally {
    submitting.value = false
    done.value = true
  }
}

function again() { done.value = false; note.value = ''; resultText.value = '' }
</script>

<template>
  <div class="mobile-check">
    <header class="top">车辆一日三检</header>

    <div v-if="!done" class="body">
      <section class="step">
        <div class="step-label"><span class="num">1</span> 选择车辆任务</div>
        <select v-model="taskId" class="big-select">
          <option v-for="t in tasks" :key="t.id" :value="t.id">
            任务 #{{ t.id }} · {{ t.cargo_name || '日常运输' }}
          </option>
          <option v-if="!tasks.length" value="">暂无任务（请联系安全员下发）</option>
        </select>
      </section>

      <section class="step">
        <div class="step-label"><span class="num">2</span> 现在是哪个时段？</div>
        <div class="phase-grid">
          <button
            v-for="p in phases" :key="p.key"
            class="phase-btn" :class="{ active: phase === p.key }"
            @click="phase = p.key"
          >{{ p.label }}</button>
        </div>
      </section>

      <section class="step">
        <div class="step-label"><span class="num">3</span> 车辆检查结果</div>
        <textarea v-model="note" class="note" rows="2" placeholder="有异常？可在此简单说明（可不填）" />
        <div class="result-grid">
          <button class="ok-btn" :disabled="submitting" @click="submit('pass')">✔ 合格</button>
          <button class="bad-btn" :disabled="submitting" @click="submit('abnormal')">! 有异常</button>
        </div>
      </section>
    </div>

    <div v-else class="result">
      <div class="result-icon">✓</div>
      <h1>{{ resultText }}</h1>
      <button class="ok-btn" @click="again">再检一次</button>
    </div>

    <footer class="foot">字号大 · 按钮大 · 三步完成</footer>
  </div>
</template>

<style scoped>
.mobile-check {
  min-height: 100vh; background: #f5f7fa; color: #1a1a1a;
  font-size: 20px; display: flex; flex-direction: column;
  max-width: 560px; margin: 0 auto;
}
.top {
  background: #0f1c3f; color: #fff; font-size: 26px; font-weight: 700;
  padding: 20px; text-align: center;
}
.body { flex: 1; padding: 20px; display: flex; flex-direction: column; gap: 26px; }
.step-label { font-size: 22px; font-weight: 700; margin-bottom: 12px; display: flex; align-items: center; gap: 10px; }
.num {
  background: #0f1c3f; color: #fff; width: 34px; height: 34px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center; font-size: 20px;
}
.big-select, .note {
  width: 100%; font-size: 20px; padding: 14px; border: 2px solid #334155;
  border-radius: 10px; background: #fff; box-sizing: border-box;
}
.phase-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.phase-btn {
  font-size: 21px; padding: 18px 4px; border: 3px solid #334155; border-radius: 12px;
  background: #fff; color: #1a1a1a; font-weight: 700; cursor: pointer;
}
.phase-btn.active { background: #0f1c3f; color: #fff; border-color: #0f1c3f; }
.result-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-top: 14px; }
.ok-btn, .bad-btn {
  font-size: 26px; font-weight: 800; padding: 26px 8px; border-radius: 14px;
  border: none; color: #fff; cursor: pointer;
}
.ok-btn { background: #16794a; }
.bad-btn { background: #b42318; }
.foot { text-align: center; padding: 14px; font-size: 16px; color: #475569; }
.result { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 24px; padding: 30px; }
.result-icon { font-size: 80px; color: #16794a; }
.result h1 { font-size: 26px; text-align: center; }
</style>
