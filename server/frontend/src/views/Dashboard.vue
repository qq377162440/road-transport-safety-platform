<script setup>
import { useAuthStore } from '@/store/auth'
const auth = useAuthStore()
</script>

<template>
  <div>
    <el-card shadow="never" style="margin-bottom: 16px">
      <div style="display: flex; align-items: center; gap: 16px">
        <el-icon size="32" color="#1e40af"><DataAnalysis /></el-icon>
        <div>
          <div style="font-size: 18px; font-weight: 700">
            欢迎回来，{{ auth.username }}
          </div>
          <div style="color: var(--text-3); font-size: 13px; margin-top: 4px">
            {{ auth.tenantName || '道路运输安全生产管理平台' }}
          </div>
        </div>
      </div>
    </el-card>

    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>快速入口</template>
          <el-space direction="vertical" fill style="width: 100%">
            <router-link to="/company">
              <el-button style="width: 100%">企业信息管理</el-button>
            </router-link>
            <router-link to="/org">
              <el-button style="width: 100%">安全管理架构</el-button>
            </router-link>
            <router-link to="/post">
              <el-button style="width: 100%">岗位信息管理</el-button>
            </router-link>
            <router-link to="/archive">
              <el-button style="width: 100%">电子档案柜</el-button>
            </router-link>
          </el-space>
        </el-card>
      </el-col>
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>第一阶段垂直切片进度</template>
          <el-steps :active="7" align-center>
            <el-step title="登录" />
            <el-step title="企业信息" />
            <el-step title="组织架构" />
            <el-step title="范本套" />
            <el-step title="责任制" />
            <el-step title="归档" />
            <el-step title="PDF" />
          </el-steps>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>
