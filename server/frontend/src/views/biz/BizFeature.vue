<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import request from '@/api/request'
import Icon from '@/components/Icon.vue'

/** 重点业务模块的特色区块（对齐 prototype/pc/*.html 与详细设计文档）。
 *  person  一人一档完整度 + 小程序账号提示
 *  vehicle 一车一档证照概览
 *  check   行车日志 + 出车/行车/收车三检范本预览
 *  danger  重大事故隐患 / 基础管理 清单范本 + 状态机说明
 *  meeting 会议四段式（通知 / 签到 / 记录 / 照片）预览
 *  training 培训三类与学习闭环
 *  doc     制度范本清单说明
 */
const props = defineProps({
  code: { type: String, default: '' },
  feature: { type: String, default: 'none' },
  rows: { type: Array, default: () => [] },
  box: { type: String, default: '' },
})
const emit = defineEmits(['changed'])

// ---------- 三检检查项（原型 check.html 的 OUT/MID/IN 清单，收敛展示前 12 项）----------
const OUT_ITEMS = ['发动机运转情况', '制动液', '润滑油', '冷却液温度', '刮水器（雨刷）', '转向盘（方向盘）',
  '内、外后视镜', '仪表指示、报警灯', '卫星定位装置', '制动气压', '安全带', '灯光']
const MID_ITEMS = ['发动机运转情况', '制动性能', '转向系统', '轮胎温度与气压', '货物装载、捆绑情况', '危险品标识']
const IN_ITEMS = ['发动机熄火情况', '车辆停放、手刹', '货物卸载及残留', '随车证件收存', '消防器材复位', '里程记录']

// ---------- 隐患清单（详细设计 13 / 原型 danger.html）----------
const MAJOR_ITEMS = [
  ['重大', '未取得道路运输经营许可或使用无效证件从事经营'],
  ['重大', '驾驶员无相应从业资格证或驾驶证准驾不符'],
  ['重大', '危货车辆未按规定配备押运员 / 安全卡 / 应急器材'],
  ['重大', '车辆逾期未检验、未维护或带病上路'],
  ['重大', '动态监控平台长期离线、故意屏蔽信号'],
  ['重大', '超限超载、违规运输禁运物品'],
]
const BASE_ITEMS = [
  ['基础', '安全生产责任制与岗位操作规程是否健全'],
  ['基础', '从业人员安全教育与培训记录是否完整'],
  ['基础', '车辆一车一档、证照是否齐全有效'],
  ['基础', '隐患排查台账与整改闭环是否落实'],
  ['基础', '应急物资、消防器材是否完好有效'],
]
const dangerSeg = ref(0)
const checkList = computed(() => (dangerSeg.value === 0 ? MAJOR_ITEMS : BASE_ITEMS))

// ---------- 会议四段式（详细设计 12 / 原型 meeting.html）----------
const MEET_STAGES = ['① 会议通知', '② 签到表', '③ 会议记录', '④ 会场照片']
const meetSeg = ref(0)
const curMeeting = computed(() => props.rows[0] || {})

// ---------- 一人一档 / 一车一档 归档完整度 ----------
const archiveDict = ref([])
async function loadArchiveDict() {
  if (props.code !== 'person') return
  try {
    const res = await request.get('/biz/person_archive_dict?pageSize=50')
    archiveDict.value = res.data?.records || []
  } catch (e) { archiveDict.value = [] }
}
onMounted(loadArchiveDict)
watch(() => props.code, loadArchiveDict)

const personStats = computed(() => {
  const list = props.rows
  const complete = list.filter((r) => Number(r.archive_complete || 0) >= 11).length
  return { total: list.length, complete, missing: list.length - complete }
})
const vehicleStats = computed(() => ({
  total: props.rows.length,
  trailer: props.rows.filter((r) => Number(r.is_trailer) === 1).length,
}))

function docTitle(row) {
  return row?.title || row?.plan_name || row?.name || '请选择记录'
}

// ---------- 其余 11 个模块：按详细设计 15~21 + 00 总纲核心模型的配置化特色区块 ----------
// block 类型：hint 提示 / stats 统计 / list 定义清单 / flow 流程 / tags 标签云
// stats 的 num 用 '@rows' 表示取当前页真实记录数。
const TONE = ['tag-gray', 'tag-yellow', 'tag-blue', 'tag-blue', 'tag-green']
const DATA_BLOCKS = {
  emergency: [
    { type: 'stats', items: [['6', '场景预案范本'], ['3', '演练阶段'], ['盒七', '归档归属'], ['@rows', '预案/记录']] },
    { type: 'hint', text: '企业按实际承运场景勾选专项预案，系统把「综合预案 + 专项预案 + 现场处置方案」拼装成套生成；仅普货生成，危货预案客户自备。' },
    { type: 'list', title: '场景预案范本（可组合调用）', sub: '详细设计 15 · E1/E2', items: [
      ['综合应急预案', '总纲：总则 / 组织 / 响应 / 保障', 'comprehensive'],
      ['道路交通事故专项', '碰撞、追尾、刮擦处置', 'road_accident'],
      ['车辆火灾事故专项', '车辆起火现场处置', 'vehicle_fire'],
      ['恶劣天气专项', '台风 / 暴雨 / 大雾 / 结冰', 'bad_weather'],
      ['货物散落、受损专项', '货物散落 / 受损处置', 'cargo_spill'],
      ['人员伤亡专项', '人员伤亡现场处置', 'casualty'],
    ]},
    { type: 'flow', title: '演练自动生成模型', sub: '系统代劳文案，企业只做「真演练 + 拍照 + 签到」', steps: ['调用预案范本', '年度演练计划', '演练方案 + 评估表', '企业演练 · 拍照签到', '签名盖章归档'] },
  ],
  accident: [
    { type: 'stats', items: [['@rows', '事故记录'], ['4', '四不放过'], ['盒十', '归档归属'], ['自动', '快报生成']] },
    { type: 'hint', text: '事故登记（企业自助、免费）→ 系统自动生成事故快报表与年度汇总；四不放过资料制作、处理指导等为平台人工增值服务，通过「联系人工」衔接。' },
    { type: 'list', title: '事故登记 → 快报字段映射', sub: '详细设计 16 · 3.2', items: [
      ['事故时间 / 地点', '快报必填，按年 / 月分组汇总'],
      ['事故车辆（车牌 / 车型）', '快报必填，按车辆统计'],
      ['事故人员（姓名 / 岗位 / 伤亡）', '快报必填，按伤亡统计'],
      ['事故经过（简述）', '快报正文'],
      ['直接经济损失', '快报必填，按损失统计'],
      ['责任初判 / 现场照片', '快报可选字段 / 附件'],
    ]},
    { type: 'flow', title: '事故处理「四不放过」', steps: ['原因未查清不放过', '责任人未处理不放过', '整改措施未落实不放过', '有关人员未受教育不放过'] },
  ],
  assess: [
    { type: 'stats', items: [['100', '默认分值'], ['3', '考核等级'], ['1次/年', '考核频率'], ['2', '产出台账']] },
    { type: 'hint', text: '责任制考核一年一次，每岗位默认 100 分；系统自动提示驾驶员违规情况，由安全员手动确认减分幅度——系统「提示」、人「决定」，避免误伤。' },
    { type: 'list', title: '考核评分规则', sub: '详细设计 19 · 3.2', items: [
      ['默认分值', '100 分（满分），按岗位取责任目标'],
      ['违规减分', '驾驶员有靶向教育违规记录时酌情减分'],
      ['等级划分', '≥90 优秀 · 60~89 合格 · <60 不合格'],
      ['结果沉淀', '写入一人一档（诚信 / 奖惩记录）'],
    ]},
    { type: 'flow', title: '年度考核流程', steps: ['按岗位取责任目标', '联动违规记录提示', '安全员确认减分', '一键签名', '生成两类台账'] },
    { type: 'tags', title: '产出两类台账', tags: ['安全生产责任制考核记录', '安全生产考核奖惩公示', '归档：从业人员考核记录', '归档：从业人员奖惩公示'] },
  ],
  sign: [
    { type: 'stats', items: [['@rows', '签署任务'], ['5', '签署环节'], ['留痕', '不可抵赖'], ['盒一', '归档归属']] },
    { type: 'hint', text: '企业上传自有 PDF → 在页面上放置签署锚点 → 发起签署 → 在线签名留痕 → 成品自动归档到电子档案柜。' },
    { type: 'flow', title: '自定义文件签署闭环', steps: ['上传 PDF 文件', '设置签署锚点', '指定签署人 / 发起', '在线签署', '签署留痕 + 归档'] },
    { type: 'list', title: '签署要素', items: [
      ['签署锚点', '页码 + 坐标定位，支持签字 / 盖章 / 日期'],
      ['签署留痕', '签署人、时间、IP、签名图，不可抵赖'],
      ['成品归档', '签署后的 PDF 回写电子档案柜指定盒'],
    ]},
  ],
  monitor: [
    { type: 'stats', items: [['@rows', '留存台账'], ['6', '闭环环节'], ['盒六', '归档归属'], ['复用', '人员/培训']] },
    { type: 'hint', text: '本模块增量价值：把动态监控平台 / 交警抄报的外部监测结果台账化，并驱动针对性教育落实闭环（复用人员、车辆、培训模块能力）。' },
    { type: 'flow', title: '台账留存 → 教育落实闭环', sub: '详细设计 20 · M1~M8', steps: ['① 上传台账留存', '② 识别违规车辆', '③ 登记驾驶员', '④ 生成靶向教育', '⑤ 跟踪落实', '⑥ 归档盒六'] },
    { type: 'tags', title: '台账来源与归档', tags: ['动态监控平台导出', '交警抄报', '人工录入', '归档盒六：台账 + 针对性违规教育'] },
  ],
  budget: [
    { type: 'stats', items: [['1%', '普货计提'], ['1.5%', '危货计提'], ['11', '费用类别'], ['盒五', '归档归属']] },
    { type: 'hint', text: '当年应提安全费用 = 上年度营业收入 × 计提比例（普货 1% / 危货 1.5%，按营运类型自动匹配，依据《企业安全生产费用提取和使用管理办法》）。' },
    { type: 'list', title: '计提模型', sub: '详细设计 17 · 3.1', items: [
      ['普货（道路普通货物运输）', '上年度营业收入 × 1%'],
      ['危货（道路危险货物运输）', '上年度营业收入 × 1.5%'],
      ['支出口径', '11 类标准支出范围，允许企业增补「其他」'],
    ]},
    { type: 'tags', title: '安全费用 11 类（财资〔2022〕136 号）', sub: '政府检查按此 11 类对账', tags: [
      '1 安全防护设施设备', '2 车辆定位监控装置', '3 应急救援器材及演练', '4 危险源监控与隐患整改',
      '5 安全检查评价咨询', '6 安全防护用品', '7 安全宣传教育培训', '8 新技术新装备推广',
      '9 设施检测检验', '10 安全责任保险', '11 其他安全生产支出',
    ]},
  ],
  risk: [
    { type: 'stats', items: [['5', '风险点分类'], ['4', '四色分级'], ['6', '辨识资料'], ['盒八', '归档归属']] },
    { type: 'hint', text: '系统预置通用风险点库（5 类运输场景），企业勾选适用风险点 + 增补自定义项，作为「风险辨识清单」与「风险告知卡」的内容来源。' },
    { type: 'list', title: '风险辨识资料清单', sub: '详细设计 18 · 3.1 · 普货 / 危货', items: [
      ['风险辨识手册', '普货 ✅ · 危货 ✅', '模板生成'],
      ['风险辨识清单', '普货 ✅ · 危货 ✅', '勾选风险点'],
      ['风险告知卡（每岗位一份）', '普货 ✅ · 危货 ✅', '按岗位生成'],
      ['无重大风险的说明', '普货 ✅ · 危货 ✅', '模板生成'],
      ['安全评估 / 无重大隐患声明', '仅危货', '模板生成'],
    ]},
    { type: 'tags', title: '风险点库分类 + 四色分级', tags: ['驾驶员风险', '车辆风险', '货物风险', '环境风险', '管理风险', '红 重大风险', '橙 较大风险', '黄 一般风险', '蓝 低风险'] },
  ],
  dangerspecial: [
    { type: 'stats', items: [['1.5%', '危货计提'], ['7', '专项台账'], ['电子运单', '核心增量'], ['盒六', '归档归属']] },
    { type: 'hint', text: '危货专项增量（详细设计 21）：计提比例 1.5%、专项台账、电子运单、一车一档专项留痕与专用标识喷涂证明；危货应急预案客户自备，系统不生成。' },
    { type: 'tags', title: '危货专项台账清单', tags: ['电子运单', '一车一档专项留痕', '专用标识喷涂证明', '职业健康检查', '押运员 / 安全卡', '应急器材配备', '危货应急预案（客户自备）'] },
    { type: 'flow', title: '电子运单留痕', steps: ['运单开具', '车辆 / 人员核验', '运输过程监控', '运单归档盒六'] },
  ],
  collect: [
    { type: 'stats', items: [['@rows', '代录任务'], ['5', '流程环节'], ['服务商', '操作角色'], ['盒一', '默认归档']] },
    { type: 'hint', text: '服务商代企业录入资料与签署文件（代录工作台）：适用于企业尚无专人维护、由服务商协助补齐台账的场景；证件类资料无范本，需手动上传。' },
    { type: 'flow', title: '代录工作台流程', steps: ['选择代录企业', '选择资料类型', '上传 / 填报', '关联归档项', '提交归档'] },
    { type: 'tags', title: '常见代录资料', tags: ['营业执照等证件', '人员证照', '车辆证照', '制度文件', '签署文件', '手动上传（无范本）'] },
  ],
  platform: [
    { type: 'stats', items: [['4', '能力域'], ['18', '动态模块'], ['按租户', '授权粒度'], ['月费', '计费方式']] },
    { type: 'hint', text: '平台侧能力：模块计费配置、租户模块授权、SSO 配置与服务账单。此处维护「哪些模块对哪些租户开放、如何计费」的元数据。' },
    { type: 'list', title: '平台侧能力清单', items: [
      ['模块计费配置', '基础模块 / 增值模块，按月费计价'],
      ['租户模块授权', '按租户开通 / 关闭模块，控制菜单可见性'],
      ['SSO 统一认证', '统一登录与各业务系统免登'],
      ['服务账单', '按租户汇总月度费用'],
    ]},
    { type: 'tags', title: '阶段分组', tags: ['A 基础设施', 'B 核心业务', 'C 运营提升', 'D 专项深化'] },
  ],
  common: [
    { type: 'stats', items: [['@rows', '操作日志'], ['3', '能力域'], ['RBAC', '权限模型'], ['实时', '日志查询']] },
    { type: 'hint', text: '系统级基础设施：所有操作留痕可追溯，菜单权限按角色下发，预警规则统一配置并驱动各业务模块的到期 / 超期提醒。' },
    { type: 'list', title: '系统运维能力', items: [
      ['操作留痕', '记录模块、操作、目标、IP、操作人与时间'],
      ['菜单权限', 'RBAC 角色 → 模块 / 菜单可见性与操作权限'],
      ['预警规则', '证照到期、隐患超期、演练计划等统一提醒'],
    ]},
  ],
}

const blocks = computed(() => {
  const defs = DATA_BLOCKS[props.code] || []
  return defs.map((b) => b.type === 'stats'
    ? { ...b, items: b.items.map(([n, l]) => [n === '@rows' ? String(props.rows.length) : n, l]) }
    : b)
})

function tagTone(t) {
  if (/^红|重大|不合格|异常|超期|不放过/.test(t)) return 'tag-red'
  if (/^橙|^黄|较大|一般|待|客户自备/.test(t)) return 'tag-yellow'
  if (/^绿|已闭环|合格|正常|优秀|✅/.test(t)) return 'tag-green'
  return 'tag-blue'
}
</script>

<template>
  <!-- ============ B1 人员基础信息：一人一档 ============ -->
  <template v-if="feature === 'person'">
    <div class="bf-hint"><Icon name="info" :size="15" />
      录入人员即自动开通小程序账号（登录账号 = 手机号，初始密码 123456），按岗位显示不同功能。
    </div>
    <div class="bf-stat-row">
      <div class="stat-card"><div class="num">{{ personStats.total }}</div><div class="lbl">在册人员</div></div>
      <div class="stat-card"><div class="num" style="color:var(--ok)">{{ personStats.complete }}</div><div class="lbl">一人一档完整</div></div>
      <div class="stat-card"><div class="num" style="color:var(--danger)">{{ personStats.missing }}</div><div class="lbl">档案待补齐</div></div>
      <div class="stat-card"><div class="num">{{ archiveDict.length }}</div><div class="lbl">归档项字典</div></div>
    </div>
    <div class="card bf-card">
      <div class="card-title">一人一档归档项（普货 11 项，危货 +禁毒承诺书共 12 项）</div>
      <div class="bf-archive-grid">
        <div v-for="(d, i) in archiveDict.slice(0, 12)" :key="d.id || i" class="a-item">
          <span class="code">{{ d.item_code || ('A' + (i + 1)) }}</span>
          <span class="nm">{{ d.item_name || d.name || '归档项' }}</span>
          <span class="st" :class="d.is_optional ? 'opt' : 'ok'">{{ d.is_optional ? '选填' : '必备' }}</span>
        </div>
        <div v-if="!archiveDict.length" class="bf-muted">归档项字典读取中或暂无数据</div>
      </div>
    </div>
  </template>

  <!-- ============ B2 车辆基础信息：一车一档 ============ -->
  <template v-else-if="feature === 'vehicle'">
    <div class="bf-stat-row">
      <div class="stat-card"><div class="num">{{ vehicleStats.total }}</div><div class="lbl">在册车辆</div></div>
      <div class="stat-card"><div class="num">{{ vehicleStats.total - vehicleStats.trailer }}</div><div class="lbl">车头</div></div>
      <div class="stat-card"><div class="num">{{ vehicleStats.trailer }}</div><div class="lbl">挂车</div></div>
      <div class="stat-card"><div class="num">13</div><div class="lbl">一车一档子表</div></div>
    </div>
    <div class="card bf-card">
      <div class="card-title">一车一档档案构成</div>
      <div class="bf-tag-row">
        <span class="tag tag-blue">车辆主表</span><span class="tag tag-blue">行驶证/道路运输证</span>
        <span class="tag tag-blue">保险记录</span><span class="tag tag-blue">卫星定位装置</span>
        <span class="tag tag-blue">驾驶员绑定</span><span class="tag tag-blue">技术等级评定</span>
        <span class="tag tag-blue">维护保养</span><span class="tag tag-blue">维修记录</span>
        <span class="tag tag-blue">车挂关联</span><span class="tag tag-blue">车辆类型配置</span>
      </div>
      <div class="bf-muted" style="margin-top:10px">车头 / 挂车分离管理，顶部筛选可按「车/挂」与营运类型过滤。</div>
    </div>
  </template>

  <!-- ============ B4 一日三检：行车日志 + 三检范本 ============ -->
  <template v-else-if="feature === 'check'">
    <div class="card bf-card">
      <div class="card-title">行车日志范本预览 <span class="bf-sub">车牌/日期/里程/货物/起止地 + 出车检查明细</span></div>
      <div class="doc-preview">
        <div class="title">企业车辆行车日志</div>
        <table>
          <tr><td>车牌号</td><td class="fill">{{ rows[0]?.plate_no || '粤Y37763' }}</td><td>日期</td><td class="fill">{{ (rows[0]?.check_day || '2026-11-09').slice(0, 10) }}</td></tr>
          <tr><td>出车公里数</td><td class="fill">—</td><td>收车公里数</td><td class="fill">—</td></tr>
          <tr><td>货物名称</td><td class="fill">—</td><td>日检情况</td><td class="fill">正常</td></tr>
        </table>
        <div class="title" style="font-size:15px">一日三检记录表 · 出车检查</div>
        <div class="grid">
          <div v-for="n in OUT_ITEMS" :key="n" class="cell"><span>{{ n }}</span><span>√</span></div>
        </div>
        <p style="text-indent:0">驾驶员/押运员签字：________</p>
      </div>
    </div>
    <div class="bf-three">
      <div v-for="(g, gi) in [['出车前检查', OUT_ITEMS], ['行车中检查', MID_ITEMS], ['收车后检查', IN_ITEMS]]" :key="gi" class="card bf-card">
        <div class="card-title">{{ g[0] }} <span class="bf-sub">{{ g[1].length }} 项</span></div>
        <div class="bf-check-list">
          <div v-for="n in g[1]" :key="n" class="bf-check-item"><span>{{ n }}</span><span class="tag tag-green">合格</span></div>
        </div>
      </div>
    </div>
  </template>

  <!-- ============ B5 隐患排查治理：清单范本 + 闭环状态机 ============ -->
  <template v-else-if="feature === 'danger'">
    <div class="card bf-card">
      <div class="card-title">排查清单范本预览 <span class="bf-sub">序号/项目类别/检查项目/检查内容与标准/检查结果/备注</span></div>
      <div class="seg">
        <div class="s" :class="{ on: dangerSeg === 0 }" @click="dangerSeg = 0">重大事故隐患项<span class="n">一票否决</span></div>
        <div class="s" :class="{ on: dangerSeg === 1 }" @click="dangerSeg = 1">基础管理项<span class="n">常规检查</span></div>
      </div>
      <div class="check-list">
        <div v-for="(it, i) in checkList" :key="i" class="check-item">
          <span class="cat" :class="it[0] === '重大' ? 'major' : 'base'">{{ it[0] }}</span>
          <span class="txt">{{ i + 1 }}. {{ it[1] }}</span>
          <span class="verdict"><span class="v yes">符合</span><span class="v no">不符合</span></span>
        </div>
      </div>
    </div>
    <div class="card bf-card">
      <div class="card-title">隐患闭环状态机 <span class="bf-sub">详细设计 13 · 一条隐患的生命周期</span></div>
      <div class="bf-flow">
        <span class="tag tag-gray">发现隐患</span><span class="bf-arrow">→</span>
        <span class="tag tag-yellow">待整改</span><span class="bf-arrow">→</span>
        <span class="tag tag-blue">整改中</span><span class="bf-arrow">→</span>
        <span class="tag tag-blue">待验收</span><span class="bf-arrow">→</span>
        <span class="tag tag-green">已闭环</span>
        <span class="bf-muted">（超期未整改 → 督办提醒；验收不通过 → 退回整改中）</span>
      </div>
    </div>
  </template>

  <!-- ============ B6 安全会议：四段式预览 ============ -->
  <template v-else-if="feature === 'meeting'">
    <div class="card bf-card">
      <div class="card-title">会议归档四段式预览 <span class="bf-sub">{{ docTitle(curMeeting) }}</span></div>
      <div class="seg">
        <div v-for="(s, i) in MEET_STAGES" :key="i" class="s" :class="{ on: meetSeg === i }" @click="meetSeg = i">{{ s }}</div>
      </div>
      <div class="doc-preview">
        <template v-if="meetSeg === 0">
          <div class="title">{{ curMeeting.notice_title || curMeeting.title || '安全生产会议通知' }}</div>
          <p class="ni">会议时间：{{ curMeeting.start_time || '—' }}　地点：{{ curMeeting.location || '—' }}</p>
          <p>{{ curMeeting.notice_content || curMeeting.topic || '（会议议题）' }}</p>
        </template>
        <template v-else-if="meetSeg === 1">
          <div class="title">会议签到表</div>
          <table><tr><th>序号</th><th>姓名</th><th>岗位</th><th>签到</th></tr>
            <tr v-for="i in 6" :key="i"><td>{{ i }}</td><td>—</td><td>—</td><td>________</td></tr></table>
        </template>
        <template v-else-if="meetSeg === 2">
          <div class="title">{{ curMeeting.title || '会议记录' }}</div>
          <h4>一、会议议题</h4><p>{{ curMeeting.topic || '（会议议题）' }}</p>
          <h4>二、会议决定</h4><p>（记录会议作出的决定与部署的工作）</p>
        </template>
        <template v-else>
          <div class="title">会场照片</div>
          <div class="photo">（照片附于表后，支持从电子档案柜上传）</div>
        </template>
      </div>
    </div>
  </template>

  <!-- ============ C1 培训教育 ============ -->
  <template v-else-if="feature === 'training'">
    <div class="bf-hint"><Icon name="info" :size="15" />
      三类培训：岗前 / 岗中 / 靶向。PC 下发计划 → 小程序在线看视频与考试 → 学习留痕自动回写统计。
    </div>
    <div class="bf-stat-row">
      <div class="stat-card"><div class="num">{{ rows.length }}</div><div class="lbl">培训计划</div></div>
      <div class="stat-card"><div class="num">3</div><div class="lbl">培训类型</div></div>
      <div class="stat-card"><div class="num">4</div><div class="lbl">学习状态</div></div>
      <div class="stat-card"><div class="num">盒四</div><div class="lbl">归档归属</div></div>
    </div>
  </template>

  <!-- ============ B3 制度档案引擎 ============ -->
  <template v-else-if="feature === 'doc'">
    <div class="card bf-card">
      <div class="card-title">制度 / 台账范本分级配置</div>
      <div class="bf-tag-row">
        <span class="tag tag-blue">范本组</span><span class="tag tag-blue">专项范本组</span>
        <span class="tag tag-blue">字段字典</span><span class="tag tag-blue">印章配置</span>
        <span class="tag tag-blue">签署锚点</span><span class="tag tag-blue">版本记录</span>
      </div>
      <div class="bf-muted" style="margin-top:10px">范本正文在线编辑、印章设置与责任制联动请前往「台账范本管理」；本页维护范本组的分级元数据。</div>
    </div>
  </template>

  <!-- ============ 其余 11 个模块：按详细设计核心模型的数据驱动特色区块 ============ -->
  <template v-else-if="blocks.length">
    <template v-for="(b, bi) in blocks" :key="bi">
      <div v-if="b.type === 'hint'" class="bf-hint"><Icon name="info" :size="15" /> {{ b.text }}</div>
      <div v-else-if="b.type === 'stats'" class="bf-stat-row">
        <div v-for="(s, si) in b.items" :key="si" class="stat-card">
          <div class="num">{{ s[0] }}</div><div class="lbl">{{ s[1] }}</div>
        </div>
      </div>
      <div v-else class="card bf-card">
        <div class="card-title">{{ b.title }} <span v-if="b.sub" class="bf-sub">{{ b.sub }}</span></div>
        <div v-if="b.type === 'list'" class="bf-def-grid">
          <div v-for="(it, ii) in b.items" :key="ii" class="bf-def-item">
            <div class="d-main">{{ it[0] }}<span v-if="it[2]" class="tag tag-gray">{{ it[2] }}</span></div>
            <div class="d-sub">{{ it[1] }}</div>
          </div>
        </div>
        <div v-else-if="b.type === 'tags'" class="bf-tag-row">
          <span v-for="(t, ti) in b.tags" :key="ti" class="tag" :class="tagTone(t)">{{ t }}</span>
        </div>
        <div v-else-if="b.type === 'flow'" class="bf-flow">
          <template v-for="(s, si) in b.steps" :key="si">
            <span class="tag" :class="TONE[si % TONE.length]">{{ s }}</span>
            <span v-if="si < b.steps.length - 1" class="bf-arrow">→</span>
          </template>
        </div>
      </div>
    </template>
  </template>
</template>

<style scoped>
.bf-hint { background: var(--warn-bg, #FFFBEB); border: 1px solid var(--warn, #FCD34D); border-radius: 8px; padding: 10px 14px; font-size: 13px; color: #8a5a00; display: flex; align-items: center; gap: 8px; }
.bf-stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.bf-card { padding: 16px 20px; }
.bf-sub { font-size: 13px; color: var(--text-3); font-weight: 400; }
.bf-muted { font-size: 12.5px; color: var(--text-3); }
.stat-card { background: #fff; border: 1px solid var(--border); border-radius: 10px; padding: 16px 18px; }
.stat-card .num { font-size: 26px; font-weight: 700; color: var(--brand-600, #1a5bb0); line-height: 1.2; }
.stat-card .lbl { font-size: 12.5px; color: var(--text-3); margin-top: 4px; }
.bf-tag-row { display: flex; flex-wrap: wrap; gap: 8px; }
.bf-archive-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 12px; }
.a-item { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border: 1px solid var(--border); border-radius: 9px; font-size: 14px; }
.a-item .code { font-weight: 700; color: var(--brand-500); font-size: 12px; white-space: nowrap; }
.a-item .nm { flex: 1; }
.a-item .st { font-size: 12px; padding: 2px 8px; border-radius: 10px; font-weight: 600; }
.st.ok { background: #F0FDF4; color: #15803D; }
.st.opt { background: #F1F5F9; color: var(--text-3); }
.doc-preview { border: 1px solid var(--border); background: #fff; padding: 24px 30px; font-family: "SimSun", "宋体", serif; color: #000; font-size: 13.5px; line-height: 1.9; margin-top: 12px; }
.doc-preview .title { text-align: center; font-size: 18px; letter-spacing: 1px; font-weight: bold; margin-bottom: 14px; }
.doc-preview table { width: 100%; border-collapse: collapse; margin: 6px 0; font-size: 12.5px; }
.doc-preview th, .doc-preview td { border: 1px solid #000; padding: 4px 8px; text-align: center; }
.doc-preview th { background: #f2f2f2; }
.doc-preview .grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 0 12px; margin: 8px 0; }
.doc-preview .grid .cell { display: flex; justify-content: space-between; gap: 6px; padding: 2px 0; }
.doc-preview h4 { font-size: 15px; margin: 12px 0 6px; }
.doc-preview .photo { height: 80px; border: 1px dashed #aaa; margin: 8px 0; display: flex; align-items: center; justify-content: center; color: #aaa; font-size: 12px; }
.fill { color: #1a4b8c; font-weight: bold; }
.bf-three { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.bf-check-list { margin-top: 10px; max-height: 240px; overflow: auto; }
.bf-check-item { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 6px 0; border-bottom: 1px solid var(--border); font-size: 13px; }
.bf-check-item:last-child { border-bottom: none; }
.seg { display: flex; gap: 4px; background: #F1F5F9; padding: 4px; border-radius: 10px; margin: 12px 0; }
.seg .s { flex: 1; padding: 9px; text-align: center; border-radius: 8px; font-size: 14px; cursor: pointer; color: var(--text-2); font-weight: 600; }
.seg .s.on { background: #fff; color: var(--brand-600); box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,.08)); }
.seg .s .n { display: block; font-size: 12px; color: var(--text-3); font-weight: 400; margin-top: 1px; }
.check-list { border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
.check-item { display: flex; align-items: flex-start; gap: 12px; padding: 11px 16px; border-bottom: 1px solid var(--border); }
.check-item:last-child { border-bottom: none; }
.check-item .cat { flex-shrink: 0; width: 56px; font-size: 12px; }
.check-item .cat.major { color: var(--danger); font-weight: 700; }
.check-item .cat.base { color: var(--text-2); }
.check-item .txt { flex: 1; font-size: 14px; line-height: 1.6; }
.check-item .verdict { flex-shrink: 0; display: flex; gap: 8px; }
.verdict .v { cursor: pointer; padding: 3px 10px; border: 1px solid var(--border-strong, #cbd5e1); border-radius: 6px; font-size: 13px; }
.bf-flow { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
.bf-arrow { color: var(--text-3); font-size: 15px; }
/* 数据驱动特色区块 */
.bf-stat-row .stat-card .num { font-size: 22px; }
.bf-def-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 12px; }
.bf-def-item { border: 1px solid var(--border); border-radius: 9px; padding: 11px 14px; }
.bf-def-item .d-main { font-size: 14px; font-weight: 600; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.bf-def-item .d-main .tag { font-size: 11px; font-weight: 500; }
.bf-def-item .d-sub { font-size: 12.5px; color: var(--text-3); margin-top: 5px; line-height: 1.5; }
.card-title + .bf-tag-row, .card-title + .bf-flow { margin-top: 12px; }
.bf-card .bf-tag-row .tag { font-size: 13px; padding: 4px 10px; }
</style>
