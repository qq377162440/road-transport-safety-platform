import { defineStore } from 'pinia'

// 营运类型（普货 / 危货）全局唯一来源：顶栏切换、电子档案柜、台账范本管理共用，
// 保证「顶部切换普货/危货」时档案柜与范本内容同步切换。
export const useCargoStore = defineStore('cargo', {
  state: () => ({
    // 与服务端 bizType 对应：普货→GENERAL，危货→DANGER
    cargoType: localStorage.getItem('cargoType') || '普货',
  }),
  getters: {
    bizType: (s) => (s.cargoType === '危货' ? 'DANGER' : 'GENERAL'),
  },
  actions: {
    setCargo(c) {
      if (c !== '普货' && c !== '危货') return
      this.cargoType = c
      localStorage.setItem('cargoType', c)
    },
  },
})
