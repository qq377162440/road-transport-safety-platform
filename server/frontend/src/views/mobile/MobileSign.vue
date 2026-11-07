<script setup>
/** 文件签署：读 PC 端制度发布下发的 sign_task，签署写回 sign_task + tpl_signature */
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/api/request'
import MobileShell from '@/components/MobileShell.vue'
import { mini, ensureMini, learnerName } from '@/store/mini'

const route = useRoute()
const rows = ref([])
const loading = ref(true)
const err = ref('')
const confirmId = ref(null)
const busy = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await request.get('/mini/sign/list', { params: { personId: mini.personId } })
    rows.value = res.data || []
  } catch (e) { err.value = e.message || '加载失败' }
  finally { loading.value = false }
}
onMounted(async () => { await ensureMini(route); await load() })

function fmt(t) { return String(t || '').replace('T', ' ').slice(0, 16) }
async function doSign() {
  const id = confirmId.value
  confirmId.value = null
  busy.value = true
  try {
    await request.post(`/mini/sign/${id}`, {
      personId: mini.personId, personName: learnerName(), signImage: 'data:image/png;base64,MINI_SIGN',
    })
    await load()
  } catch (e) { err.value = '签署失败：' + (e.message || '请重试') }
  finally { busy.value = false }
}
</script>

<template>
  <MobileShell title="文件签署" tab="home" back>
    <div class="m-tip" style="margin-top:0">待签制度文件由 PC 端「制度发布 / 自定义文件签署」下发，签署记录同步回 PC 端。</div>
    <div v-if="err" class="m-err">{{ err }}</div>
    <div v-if="loading" class="m-loading">正在加载待签文件…</div>
    <template v-else>
      <div v-for="s in rows" :key="s.id" class="m-card">
        <div style="display:flex;align-items:flex-start;gap:10px">
          <div style="flex:1;min-width:0">
            <div style="font-size:calc(19px * var(--m-scale));font-weight:800;line-height:1.5">{{ s.title }}</div>
            <div style="font-size:calc(14px * var(--m-scale));color:#64748B;margin-top:6px">下发时间 {{ fmt(s.created_at) }}</div>
          </div>
          <span class="m-tag" :class="s.status === 'signed' ? 'm-tag-green' : 'm-tag-yellow'">
            {{ s.status === 'signed' ? '已签署' : '待签署' }}
          </span>
        </div>
        <div v-if="s.status === 'signed'" class="m-tip" style="background:#F0FDF4;color:#15803D">
          {{ s.signer || '本人' }} 于 {{ fmt(s.signed_at) }} 签署完成
        </div>
        <button v-else class="m-btn m-btn-primary" :disabled="busy" @click="confirmId = s.id">✎ 阅读并签署</button>
      </div>
      <div v-if="!rows.length" class="m-empty">暂无待签文件</div>
    </template>

    <div v-if="confirmId" style="position:fixed;inset:0;background:rgba(15,23,42,.55);display:flex;align-items:center;justify-content:center;z-index:50;padding:20px">
      <div style="background:#fff;border-radius:18px;padding:24px;width:100%;max-width:340px;text-align:center">
        <div style="font-size:calc(22px * var(--m-scale));font-weight:800;margin-bottom:12px">确认已阅读并签署？</div>
        <div style="font-size:calc(16px * var(--m-scale));color:#475569;line-height:1.7">
          签署人：{{ learnerName() }}<br />点击确认后即视为本人已阅知并同意，记录会留痕。
        </div>
        <button class="m-btn m-btn-primary" @click="doSign">✓ 确认签署</button>
        <button class="m-btn m-btn-gray" @click="confirmId = null">✗ 再看看</button>
      </div>
    </div>
  </MobileShell>
</template>
