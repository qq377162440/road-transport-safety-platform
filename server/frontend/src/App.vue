<script setup>
// A5：全局中文语言包（按需引入后通过 ConfigProvider 生效）
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>
