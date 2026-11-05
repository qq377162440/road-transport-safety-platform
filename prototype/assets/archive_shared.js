/* 共享档案数据层：范本管理 & 电子档案柜 共用，保证信息"一一对应"
 *
 * 设计目的：
 *   1. 11 个档案盒的元数据（盒序 / 盒名）统一来源；
 *   2. 盒二「安全生产管理制度」24 项制度名称 + 编号（YT-ZD01…YT-ZD24）
 *      只在这里定义一次，范本管理与电子档案柜两侧均通过 buildBox2Institutions()
 *      读取，天然名称 / 编号一致；
 *   3. 通用文档渲染（esc / renderDoc / renderTocArchive）两侧复用，确保 PDF 正文一致。
 *
 * 任何一侧的盒二制度清单、制度编号、盒元数据修改，只改本文件即可同步两边。
 */
(function () {
  'use strict';

  // 公司名称字母简写（制度编号前缀，演示公司：佛山市悦通物流有限公司 → YT）
  window.COMPANY_ABBR = 'YT';

  // 11 档案盒统一元数据（两页共用，保证盒名 / 盒序一致）
  window.BOXES = [
    { id: 1,  name: '盒一 · 公司档案及管理层档案' },
    { id: 2,  name: '盒二 · 安全生产管理制度' },
    { id: 3,  name: '盒三 · 安全生产会议' },
    { id: 4,  name: '盒四 · 安全生产教育培训' },
    { id: 5,  name: '盒五 · 安全生产费用' },
    { id: 6,  name: '盒六 · 动态监控' },
    { id: 7,  name: '盒七 · 应急管理' },
    { id: 8,  name: '盒八 · 风险辨识与隐患排查' },
    { id: 9,  name: '盒九 · 一日三检' },
    { id: 10, name: '盒十 · 事故管理' },
    { id: 11, name: '盒十一 · 一人一档 / 一车一档' },
  ];

  // 盒二 24 项制度名称（顺序即制度编号顺序）。
  // 末项「安全生产管理制度目录」非制度正文，由 renderTocArchive 动态生成，故不在此列表。
  window.INSTITUTION_ORDER = [
    '安全生产责任制',
    '安全生产会议制度',
    '安全生产台账及档案管理制度',
    '安全生产费用提取和使用管理制度',
    '车辆安全管理制度',
    '安全生产教育培训制度',
    '安全生产监督检查制度',
    '隐患排查与治理制度',
    '安全隐患报告和举报奖励制度',
    '安全生产考核与奖惩制度',
    '安全事故报告、统计与处理制度',
    '驾驶员管理制度',
    '动态监控管理制度',
    '风险分级管控制度',
    '风险动态监控管理制度',
    '应急预案管理制度',
    '劳动保护用品配备和使用管理制度',
    '超限超载运输管理规定',
    '车辆装载及装卸作业安全规定',
    '防止货物脱落、扬撒工作措施',
    '消防安全管理制度',
    '重大危险源管理制度',
    '安全生产操作规程（普货）',
    '风险辨识与风险评价手册（普货）',
  ];

  // 生成盒二 24 项制度（带 YT-ZDxx 编号）。两页共用 → 名称与编号天然一一对应。
  window.buildBox2Institutions = function () {
    return window.INSTITUTION_ORDER.map(function (name, i) {
      return { name: name, zdNo: window.COMPANY_ABBR + '-ZD' + String(i + 1).padStart(2, '0') };
    });
  };

  // 通用 HTML 转义
  window.esc = function (s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  };

  // 把 Word 正文段落数组渲染为结构化 HTML（范本管理 / 电子档案柜共用，保证 PDF 正文一致）
  window.renderDoc = function (paras) {
    if (typeof paras === 'string') return paras;
    return paras.map(function (p) {
      const t = p.trim();
      if (!t) return '';
      if (/^(\d+)\.(\d+)\.\d*\s*[一-龥]/.test(t)) return '<div class="doc-h3">' + window.esc(t) + '</div>';
      if (/^\d+\.\d+\s*[一-龥]/.test(t)) return '<div class="doc-h2">' + window.esc(t) + '</div>';
      if (/^\d+\.\s*[一-龥]/.test(t) && !/^\d+\.\d+/.test(t)) return '<div class="doc-h1">' + window.esc(t) + '</div>';
      if (/^[（(]?\d+[、）)）]/.test(t) || /^[①-⑳]/.test(t)) return '<div class="doc-li">' + window.esc(t) + '</div>';
      return '<div class="doc-p">' + window.esc(t) + '</div>';
    }).join('');
  };

  // 安全生产管理制度目录：按盒二实际存在的 24 项制度自动生成，并填写制度编号
  window.renderTocArchive = function () {
    const items = window.buildBox2Institutions();
    let h = '<div class="doc-h0">安全生产管理制度目录</div>';
    h += '<div class="doc-p">本目录所列制度编号采用「公司字母简写 + ZD + 两位序号」规则自动生成（当前简写：' + window.COMPANY_ABBR + '）。</div>';
    h += '<div class="doc-h1">一、安全生产管理制度清单（共 ' + items.length + ' 项）</div>';
    items.forEach(function (t) {
      h += '<div class="doc-li">' + window.esc(t.zdNo) + '　' + window.esc(t.name) + '</div>';
    });
    return h;
  };
})();
