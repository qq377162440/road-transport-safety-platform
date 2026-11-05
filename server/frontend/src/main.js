import { createApp } from 'vue'
import { createPinia } from 'pinia'
// A5：Element Plus 不再全量注册 —— 组件与样式由 unplugin-vue-components 按需引入
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)

// 注册图标（体积较小，保留全量注册避免页面遗漏）
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)

app.mount('#app')
