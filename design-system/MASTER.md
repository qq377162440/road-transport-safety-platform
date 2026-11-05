# Design System Master File —— 道路运输安全生产管理平台

> **Project:** 道路运输安全生产管理平台
> **Generated:** 2026-11-02 · 数据源：ui-ux-pro-max（Data-Dense Dashboard 风格）
> **Scope:** PC 网页端（企业/服务商管理后台）+ 微信小程序端（一线执行）
> **Target User:** 文化水平参差、对电脑/手机不熟的运输从业人员（适老化优先）

---

## 0. 设计原则（不可违背）

1. **一眼看懂、一用就会、功能直给** —— 数据密集后台，信息密度高但不杂乱。
2. **颜色表意** —— 绿=正常、橙=注意、红=紧急，安全状态色全局统一。
3. **适老化** —— 大字号、大触控目标、高对比，一线司机 30 秒完成一次操作。
4. **留痕可信** —— 界面传递"可靠、权威、可被政府检查"的信任感。

---

## 1. Global Rules

### 1.1 Color Palette（配色 —— Light Mode 为主）

#### 品牌主色 Blue（稳重可信的企业蓝，来自 ui-ux-pro-max Analytics Dashboard 推荐）

| Token | Hex | 用途 |
|-------|-----|------|
| `--blue-50` | `#EFF6FF` | 主色浅底（选中背景、hover 底） |
| `--blue-100` | `#DBEAFE` | 标签蓝底 |
| `--blue-400` | `#60A5FA` | 焦点 ring |
| `--blue-500` | `#3B82F6` | 链接、交互强调 |
| `--blue-600` | `#2563EB` | 主按钮 hover |
| `--blue-700` | `#1D4ED8` | 主按钮 active |
| **`--blue-800`** | **`#1E40AF`** | **主色 Primary**（白字对比度 8.6:1 ✓） |
| `--blue-900` | `#1E3A8A` | 深色（侧边栏底、标题强调） |

> 主按钮：`background: #1E40AF`；hover `#2563EB`；active `#1D4ED8`。

#### 语义色（安全状态色 —— 业务核心，全局统一）

| Token | 深字（文字用，≥4.5:1） | 主色（背景/图标） | 浅底（标签背景） | 语义 |
|-------|----------------------|------------------|-----------------|------|
| Success | `#15803D` | `#16A34A` | `#F0FDF4` | 正常/合格/已完成 |
| Warning | `#B45309` | `#F59E0B` | `#FFFBEB` | 注意/待办/临期 |
| Danger  | `#B91C1C` | `#DC2626` | `#FEF2F2` | 紧急/不合格/逾期 |
| Info    | `#0369A1` | `#0284C7` | `#F0F9FF` | 提示/进行中 |

#### 中性色（Slate）

| Token | Hex | 用途 |
|-------|-----|------|
| `--bg` | `#F8FAFC` | 页面背景（slate-50） |
| `--surface` | `#FFFFFF` | 卡片/表面 |
| `--border` | `#E2E8F0` | 边框（slate-200） |
| `--border-strong` | `#CBD5E1` | 强边框/输入框（slate-300） |
| `--text` | `#1E293B` | 主文字（slate-800，对比 14:1 ✓） |
| `--text-2` | `#475569` | 次文字（slate-600，对比 7.6:1 ✓） |
| `--text-3` | `#64748B` | 辅助文字（slate-500，对比 4.7:1 ✓ 仅用于非关键说明） |

> ⚠️ 正文/标签文字禁用 `--text-3` 以下灰度的文字色（对比度不足）。

### 1.2 Typography（字体）

```css
--font: "Source Han Sans SC","PingFang SC","HarmonyOS Sans SC","Microsoft YaHei",system-ui,-apple-system,sans-serif;
```

- **统一中文字体栈**，不做多字体混排（Fira Code 等英文等宽字体不适配中文标题）。
- **数字对齐**：数据表格、指标卡数字用 `font-variant-numeric: tabular-nums`。
- **字号阶梯**（适老化，整体偏大）：

| Token | 字号 | 用途 |
|-------|------|------|
| `--text-xs` | `12px` | 辅助标注 |
| `--text-sm` | `14px` | 表格正文、次要说明 |
| `--text-base` | `15px` | PC 正文默认 |
| `--text-lg` | `17px` | 小程序正文、强调 |
| `--text-xl` | `20px` | 卡片标题 |
| `--text-2xl` | `24px` | 指标数值/页面标题 |
| `--text-3xl` | `28px` | 大指标 |

### 1.3 Spacing（8px 网格）

| Token | Value | 用途 |
|-------|-------|------|
| `--space-1` | `4px` | 紧贴间距 |
| `--space-2` | `8px` | 图标与文字、内联间距 |
| `--space-3` | `12px` | 小组件内间距 |
| `--space-4` | `16px` | 标准内边距 |
| `--space-5` | `20px` | 卡片内边距 |
| `--space-6` | `24px` | 区块间距 |
| `--space-8` | `32px` | 大区块间距 |
| `--space-12` | `48px` | 页级留白 |

### 1.4 Radius & Shadow

| Token | Value | 用途 |
|-------|-------|------|
| `--radius-sm` | `6px` | 按钮、标签、输入框 |
| `--radius-md` | `8px` | 卡片、下拉 |
| `--radius-lg` | `12px` | 大卡片、弹窗 |
| `--radius-xl` | `16px` | 手机壳、模态 |
| `--shadow-sm` | `0 1px 2px 0 rgba(15,23,42,.05)` | 卡片轻阴影 |
| `--shadow-md` | `0 4px 6px -1px rgba(15,23,42,.08),0 2px 4px -2px rgba(15,23,42,.05)` | 悬浮/hover |
| `--shadow-lg` | `0 10px 15px -3px rgba(15,23,42,.10)` | 弹窗/下拉 |

---

## 2. Component Specs（组件状态）

### 2.1 Buttons（按钮）

尺寸：默认高 `40px`（PC）/ `54px`（小程序触控）。圆角 `--radius-sm`。

| 状态 | 规则 |
|------|------|
| Default | 主按钮 `#1E40AF` 白字；次按钮白底 `--border-strong` 边框 |
| Hover | 主按钮 `#2563EB`；次按钮边框变 `--blue-400`、文字变 `--blue-800`、底 `--blue-50`。**仅色变，不位移** |
| Active | 主按钮 `#1D4ED8` |
| Focus-visible | `outline: 3px solid --blue-400; outline-offset: 2px` |
| Disabled | `opacity: .45; cursor: not-allowed` |

> ❌ 禁止 hover 用 `transform: translateY()` 或 `scale()`（布局位移/抖动）。

### 2.2 Inputs / Selects / Textareas

| 状态 | 规则 |
|------|------|
| Default | 白底，`1px solid --border-strong`，高 40px，字号 15px |
| Focus | 边框 `--blue-800`，`box-shadow: 0 0 0 3px rgba(37,99,235,.15)` |
| Error | 边框 `--danger`，浅红底 `--danger-bg`，下方红字错误提示 |
| Disabled | `background: --bg; color: --text-3` |

### 2.3 Cards

- 白底、`1px solid --border`、圆角 `--radius-lg`、内边距 `20px`。
- 默认 `--shadow-sm`；hover 仅阴影增强（`--shadow-md`），**不位移不缩放**。

### 2.4 Tables（数据密集核心组件）

- 表头：`--bg` 底、`--text-2` 字、`font-weight:600`。
- 行高：`padding: 12px 14px`，正文 14.5px。
- 行 hover：`#F1F5F9` 底高亮。
- 数字列：`tabular-nums` 右对齐。

### 2.5 Tags（状态标签）

浅底 + 深字（保证对比度）：

| 类型 | 背景 | 文字 |
|------|------|------|
| tag-green | `#F0FDF4` | `#15803D` |
| tag-yellow | `#FFFBEB` | `#B45309` |
| tag-red | `#FEF2F2` | `#B91C1C` |
| tag-blue | `#DBEAFE` | `#1E3A8A` |
| tag-gray | `#F1F5F9` | `#475569` |

---

## 3. Responsive Layout（响应式断点）

| 断点 | 宽度 | 布局规则 |
|------|------|----------|
| Mobile | `375px` | 指标卡 1 列；筛选栏纵向堆叠；表格横向滚动；按钮全宽 ≥44px |
| Tablet | `768px` | 指标卡 2 列；侧边栏折叠 |
| Laptop | `1024px` | 指标卡 3 列；侧边栏展开 |
| Desktop | `1440px` | 指标卡 4 列；最大内容宽 `1400px` 居中 |

```css
/* 指标卡自适应：默认 4 列，逐级降为 1 列 */
.stat-grid { display:grid; grid-template-columns:repeat(4,1fr); gap:16px; }
@media (max-width:1024px){ .stat-grid{ grid-template-columns:repeat(2,1fr);} }
@media (max-width:640px){ .stat-grid{ grid-template-columns:1fr; } }
```

---

## 4. Accessibility Rules（可访问性 —— 交付前必检）

1. **对比度 ≥ 4.5:1**：正文、标签文字、按钮文字。正文禁用 `#64748B` 以下灰度的文字色。
2. **触控目标**：移动端 `≥ 44×44px`；PC 按钮高 `≥ 40px`；触控目标间距 `≥ 8px`。
3. **焦点可见**：所有可交互元素 `:focus-visible` 显示 3px 描边。
4. **颜色非唯一指示**：状态不能只靠颜色，需配文字/图标（如"合格/不合格"标签 + 颜色）。
5. **`prefers-reduced-motion`**：关闭动画/过渡。
6. **SVG 图标非 emoji**：统一 Lucide 风格线框图标。
7. **cursor:pointer**：所有可点击元素。
8. **响应式**：375/768/1024/1440 四档，移动端无横向滚动。

---

## 5. Anti-Patterns（禁止）

- ❌ Emoji 作图标（用 SVG）
- ❌ 低对比度文字（`#94A3B8` 等浅灰做正文）
- ❌ Hover 布局位移（`translateY`/`scale` 改变占位）
- ❌ 花哨渐变/紫色 AI 风/玻璃拟态过度
- ❌ 触控目标 < 44px
- ❌ 焦点不可见
- ❌ 颜色作为唯一状态指示
- ❌ PC 与小程序配色不一致

---

## 6. Pre-Delivery Checklist

- [ ] 无 emoji 图标（SVG 统一）
- [ ] 对比度 ≥ 4.5:1（正文/标签/按钮）
- [ ] 触控目标 ≥ 44px（移动）/ ≥ 40px（PC 按钮）
- [ ] `cursor:pointer` 全部可点击元素
- [ ] Hover 状态平滑过渡（150-300ms），无布局位移
- [ ] `:focus-visible` 焦点可见
- [ ] `prefers-reduced-motion` 生效
- [ ] 响应式 375/768/1024/1440，无横向滚动
- [ ] PC 与小程序配色、字体、间距统一

---

*本文档为全局设计源头（Source of Truth）。页面级覆盖规则见 `pages/[page-name].md`，若不存在则严格遵循本 MASTER。*
