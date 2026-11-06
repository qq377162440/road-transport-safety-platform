<script setup>
import { onMounted, reactive, ref } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'

/** D5 平台侧能力：企业开通总览、模块配置与授权、SSO 配置登记、增值服务计费。
 *  诚实边界：SSO 仅登记配置，未接入真实 IdP 登录跳转。 */
const summary = ref({ tenants: 0, moduleGrants: 0, unpaidAmount: 0, paidAmount: 0 })
const tenants = ref([])
const modules = ref([])
const bills = ref([])
const myAuth = ref({ enabled: [], disabled: [], trialAllOpen: true, valueAdded: [] })
const tab = ref('tenant')

const grant = reactive({ tenantId: null, moduleCode: 'risk', enabled: 1, expire_at: '' })
const sso = reactive({ tenantId: null, protocol: 'OIDC', idp_url: '', client_id: '', client_secret: '', enabled: 0 })
const ssoView = ref(null)
const billing = reactive({ period: new Date().toISOString().slice(0, 7), tenantId: null })
const editing = reactive({ code: null, monthly_fee: 0, enabled: 1 })

async function load() {
  const [s, t, m, b, my] = await Promise.all([
    request.get('/platform/summary'),
    request.get('/platform/tenants'),
    request.get('/platform/modules'),
    request.get('/platform/billing'),
    request.get('/platform/my-modules'),
  ])
  summary.value = s.data || summary.value
  tenants.value = t.data?.list || []
  modules.value = m.data?.list || []
  bills.value = b.data?.list || []
  myAuth.value = my.data || myAuth.value
  if (!grant.tenantId && tenants.value.length) grant.tenantId = tenants.value[0].id
  if (!sso.tenantId && tenants.value.length) sso.tenantId = tenants.value[0].id
}

async function act(label, fn) {
  try {
    const res = await fn()
    ElMessage.success(label + '成功')
    await load()
    return res.data
  } catch (e) {
    ElMessage.error(e.message || label + '失败')
    return null
  }
}

function grantModules() {
  return act('模块授权', () => request.post(`/platform/tenant/${grant.tenantId}/modules`, {
    modules: [{ code: grant.moduleCode, enabled: grant.enabled, expire_at: grant.expire_at || null }],
  }))
}
function saveSso() {
  return act('保存 SSO 配置', () => request.put(`/platform/tenant/${sso.tenantId}/sso`, { ...sso }))
}
async function viewSso() {
  try { ssoView.value = (await request.get(`/platform/tenant/${sso.tenantId}/sso`)).data }
  catch (e) { ElMessage.error(e.message) }
}
function generateBills() {
  return act('生成账期账单', () => request.post('/platform/billing', { ...billing }))
}
function settle(id) { return act('结清账单', () => request.put(`/platform/billing/${id}/settle`)) }
function saveModule() {
  return act('更新模块配置', () => request.put(`/platform/modules/${editing.code}`, {
    monthly_fee: editing.monthly_fee, enabled: editing.enabled,
  }))
}
onMounted(load)
</script>

<template>
  <div class="phase3">
    <el-card shadow="never" class="head">
      <h2>平台侧能力 <el-tag size="small" effect="plain">D5 · 运营台</el-tag></h2>
      <p>企业开通总览 / 功能模块配置与授权 / SSO 配置登记 / 增值服务按账期计费。<strong>SSO 仅登记配置，未接入真实 IdP 登录跳转。</strong></p>
      <el-row :gutter="12" class="kpi">
        <el-col :span="6"><el-statistic title="入驻企业" :value="summary.tenants" /></el-col>
        <el-col :span="6"><el-statistic title="模块授权数" :value="summary.moduleGrants" /></el-col>
        <el-col :span="6"><el-statistic title="待收（元）" :value="Number(summary.unpaidAmount)" :precision="2" /></el-col>
        <el-col :span="6"><el-statistic title="已收（元）" :value="Number(summary.paidAmount)" :precision="2" /></el-col>
      </el-row>
      <el-alert type="info" :closable="false" class="hint-alert"
        :title="`当前账号模块授权：${myAuth.enabled.length} 个可用${myAuth.trialAllOpen ? '（试运行期全量开放）' : ''}，增值模块 ${myAuth.valueAdded.length} 个`" />
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane label="企业开通与授权" name="tenant">
          <el-table :data="tenants" border size="small" max-height="260">
            <el-table-column prop="id" label="租户ID" width="190" />
            <el-table-column prop="name" label="企业名称" />
            <el-table-column prop="biz_type" label="营运类型" width="110" />
            <el-table-column prop="status" label="状态" width="80" />
            <el-table-column prop="module_count" label="已开通模块" width="110" />
          </el-table>
          <el-divider content-position="left">按企业授权模块</el-divider>
          <el-form inline>
            <el-form-item label="企业">
              <el-select v-model="grant.tenantId" filterable style="width: 240px">
                <el-option v-for="t in tenants" :key="t.id" :label="t.name" :value="t.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="模块">
              <el-select v-model="grant.moduleCode" style="width: 180px">
                <el-option v-for="m in modules" :key="m.module_code" :label="`${m.module_name} · ${m.phase}`" :value="m.module_code" />
              </el-select>
            </el-form-item>
            <el-form-item label="到期"><el-input v-model="grant.expire_at" placeholder="2027-12-31" style="width: 130px" /></el-form-item>
            <el-form-item label="启用"><el-switch v-model="grant.enabled" :active-value="1" :inactive-value="0" /></el-form-item>
            <el-form-item><el-button type="primary" @click="grantModules">保存授权</el-button></el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="功能模块配置" name="module">
          <el-table :data="modules" border size="small" max-height="330">
            <el-table-column prop="module_code" label="编码" width="140" />
            <el-table-column prop="module_name" label="名称" />
            <el-table-column prop="phase" label="阶段" width="80" />
            <el-table-column prop="is_base" label="基础" width="70" />
            <el-table-column prop="monthly_fee" label="月费（元）" width="110" />
            <el-table-column prop="enabled" label="上架" width="70" />
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="Object.assign(editing, { code: row.module_code, monthly_fee: Number(row.monthly_fee), enabled: Number(row.enabled) })">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-form v-if="editing.code" inline class="edit">
            <el-form-item :label="`配置 ${editing.code}`"><el-input-number v-model="editing.monthly_fee" :controls="false" :precision="2" :min="0" style="width: 120px" /></el-form-item>
            <el-form-item label="上架"><el-switch v-model="editing.enabled" :active-value="1" :inactive-value="0" /></el-form-item>
            <el-form-item><el-button type="primary" @click="saveModule">保存</el-button></el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="SSO 配置" name="sso">
          <el-form :model="sso" label-width="110px" style="max-width: 620px">
            <el-form-item label="企业">
              <el-select v-model="sso.tenantId" filterable style="width: 300px">
                <el-option v-for="t in tenants" :key="t.id" :label="t.name" :value="t.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="协议">
              <el-select v-model="sso.protocol" style="width: 160px">
                <el-option v-for="p in ['OIDC','SAML2','CAS']" :key="p" :label="p" :value="p" />
              </el-select>
            </el-form-item>
            <el-form-item label="IdP 地址"><el-input v-model="sso.idp_url" /></el-form-item>
            <el-form-item label="Client ID"><el-input v-model="sso.client_id" /></el-form-item>
            <el-form-item label="Client Secret"><el-input v-model="sso.client_secret" type="password" show-password placeholder="留空表示不修改" /></el-form-item>
            <el-form-item label="启用"><el-switch v-model="sso.enabled" :active-value="1" :inactive-value="0" /></el-form-item>
            <el-form-item>
              <el-button type="primary" @click="saveSso">保存配置</el-button>
              <el-button @click="viewSso">读取（密钥脱敏）</el-button>
            </el-form-item>
          </el-form>
          <pre v-if="ssoView" class="out">{{ JSON.stringify(ssoView, null, 2) }}</pre>
        </el-tab-pane>

        <el-tab-pane label="增值服务计费" name="billing">
          <el-form inline>
            <el-form-item label="账期"><el-input v-model="billing.period" placeholder="YYYY-MM" style="width: 120px" /></el-form-item>
            <el-form-item label="企业ID"><el-input v-model="billing.tenantId" placeholder="留空=全部" style="width: 180px" /></el-form-item>
            <el-form-item><el-button type="primary" @click="generateBills">生成账期账单（幂等）</el-button></el-form-item>
          </el-form>
          <el-table :data="bills" border size="small" max-height="300">
            <el-table-column prop="bill_no" label="账单号" width="180" />
            <el-table-column prop="tenant_name" label="企业" />
            <el-table-column prop="module_code" label="模块" width="140" />
            <el-table-column prop="period" label="账期" width="100" />
            <el-table-column prop="amount" label="金额" width="110" />
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button v-if="row.status === 'unpaid'" link type="primary" size="small" @click="settle(row.id)">结清</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.phase3 { display: flex; flex-direction: column; gap: 14px; }
.head h2 { margin: 0 0 8px; font-size: 19px; }
.head p { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
.kpi { margin-top: 14px; }
.hint-alert { margin-top: 12px; }
.edit { margin-top: 12px; }
.out { background: #f7f8fa; padding: 12px; border-radius: 6px; font-size: 12px; max-height: 220px; overflow: auto; }
</style>
