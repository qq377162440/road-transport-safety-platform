/* 范本套数据层：服务商「范本套 / 模板集」 + 企业「当前应用范本 / 我的范本」
 *
 * 设计目标（对齐用户逻辑图与《09-静态资料范本引擎》三层体系）：
 *   1. 服务商侧维护多套【全局范本套】，所有企业均可调用；
 *      每套是一整套制度包（盒一~盒十一下的责任书 / 制度 / 台账范本等多份文件）。
 *   2. 企业侧从服务商提供的整套范本【创建自己的范本】——即「当前应用范本」；
 *      可在服务商基础上定制，并【另存为新的范本名字】形成「我的范本」。
 *   3. 企业所有改动（岗位调整、人员变更等涉及制度联动的），应用到企业当前范本，
 *      并提示「保存到当前范本」还是「另存为新范本」。
 *
 * 本文件只管数据（结构 + 持久化 + 种子），页面负责渲染与交互。
 * 依赖：archive_shared.js（window.BOXES / window.buildBox2Institutions / window.COMPANY_ABBR）
 *       sys_docs.js（window.SYS_DOCS，盒二制度正文）
 */
(function () {
  'use strict';

  var LS_PROVIDER = 'rd_tpl_provider_v1';      // 服务商全局范本套
  var LS_ENT_CURRENT = 'rd_tpl_ent_current_v1'; // 企业当前应用范本
  var LS_ENT_MY = 'rd_tpl_ent_my_v1';          // 企业我的范本（另存为产生的变体）
  var LS_REVISION = 'rd_ent_tpl_revision_v1';   // 待修订标记

  // —— 文件类型归类（决定左侧文件夹分组）——
  function inferType(name) {
    if (name.indexOf('操作规程') >= 0) return '操作规程';
    if (name.indexOf('手册') >= 0) return '手册';
    if (name.indexOf('预案') >= 0) return '预案';
    if (name.indexOf('目录') >= 0) return '目录';
    if (name.indexOf('规定') >= 0) return '规定';
    if (name.indexOf('措施') >= 0) return '措施';
    return '制度';
  }

  // 责任书与岗位职能（funcKey）的关联映射：岗位名称可自定义，但职能键稳定不变，
  // 内容生成时按 funcKey 反查实时岗位名/目标/职责/考核，实现「改名即联动」。
  var RESP_FUNC_KEY = {
    '总经理': 'gm', '安全员': 'safe', '运营调度员': 'disp', '监控员': 'mon',
    '驾驶员': 'driver', '押运员': 'escort', '装卸管理员': 'loader'
  };

  // —— 生成「普货通用范本」的完整文件清单（真实 24 制度 + 盒一/盒三~盒十一）——
  function buildFullFiles() {
    var files = [];
    var fid = 1;
    // 盒一：责任书（与责任制联动）/ 承诺书 / 告知书 / 任命书 / 通知
    [
      { name: '安全生产责任书（主要负责人）', type: '责任书', link: 'resp', role: '总经理', funcKey: 'gm' },
      { name: '安全生产责任书（安全管理员）', type: '责任书', link: 'resp', role: '安全员', funcKey: 'safe' },
      { name: '安全生产责任书（运营调度员）', type: '责任书', link: 'resp', role: '运营调度员', funcKey: 'disp' },
      { name: '安全生产责任书（动态监控管理员）', type: '责任书', link: 'resp', role: '监控员', funcKey: 'mon' },
      { name: '安全生产责任书（驾驶员）', type: '责任书', link: 'resp', role: '驾驶员', funcKey: 'driver' },
      { name: '安全生产责任书（押运员）', type: '责任书', link: 'resp', role: '押运员', funcKey: 'escort' },
      { name: '安全生产责任书（装卸管理员）', type: '责任书', link: 'resp', role: '装卸管理员', funcKey: 'loader' },
      { name: '安全生产承诺书（全员）', type: '承诺书' },
      { name: '岗位职业病危害因素告知书', type: '告知书' },
      { name: '安全生产管理人员任命书', type: '任命书' },
      { name: '成立安全生产领导小组的通知', type: '通知' }
    ].forEach(function (x) { files.push({ fileId: 'f' + (fid++), box: 1, name: x.name, type: x.type, builtin: true, ver: 'v1.0 内置', link: x.link, role: x.role, funcKey: x.funcKey || (RESP_FUNC_KEY[x.role] || null) }); });

    // 盒二：普货 24 项制度（来自 buildBox2Institutions），其中「安全生产责任制」为联动版本
    if (window.buildBox2Institutions) {
      var n = 1;
      window.buildBox2Institutions().forEach(function (row) {
        if (!window.SYS_DOCS || !window.SYS_DOCS[row.name]) return;
        if (row.name === '安全生产责任制') {
          files.push({ fileId: 'f' + (fid++), box: 2, name: '安全生产责任制', type: '制度', builtin: true, ver: 'v1.0 内置', link: 'resp', role: '__ALL__', zdNo: row.zdNo });
        } else {
          files.push({ fileId: 'f' + (fid++), box: 2, name: row.name, type: inferType(row.name), builtin: true, ver: 'v1.0 内置', doc: row.name, zdNo: row.zdNo });
        }
      });
      files.push({ fileId: 'f' + (fid++), box: 2, name: '安全生产管理制度目录', type: '目录', builtin: true, ver: 'v1.0 内置', toc: true });
    }

    // 盒三~盒十一：代表性台账范本
    [
      { box: 3, name: '会议通知 / 签到表 / 记录（四段式）', type: '会议' },
      { box: 4, name: '安全生产教育培训计划', type: '培训' },
      { box: 4, name: '安全生产月度培训记录', type: '培训' },
      { box: 5, name: '安全生产费用使用台账', type: '费用' },
      { box: 6, name: '动态监控工作情况记录台账', type: '监控' },
      { box: 7, name: '应急预案（7套场景）', type: '预案' },
      { box: 7, name: '应急演练计划 / 记录', type: '应急' },
      { box: 8, name: '全面风险辨识清单', type: '风险' },
      { box: 8, name: '风险分级管控清单', type: '风险' },
      { box: 8, name: '隐患排查记录', type: '隐患' },
      { box: 9, name: '一日三检记录', type: '三检' },
      { box: 10, name: '事故快报表', type: '事故' },
      { box: 11, name: '一人一档', type: '档案' },
      { box: 11, name: '一车一档', type: '档案' }
    ].forEach(function (x) { files.push({ fileId: 'f' + (fid++), box: x.box, name: x.name, type: x.type, builtin: true, ver: 'v1.0 内置' }); });

    return files;
  }

  // —— 占位骨架：通用文件清单（内容待补充）——
  function buildSkeletonFiles(bizType) {
    var files = [];
    var fid = 1;
    var isDanger = bizType === '危货';
    // 盒一：责任书 / 承诺书 / 告知书 / 任命书 / 通知
    [
      { name: '安全生产责任书（主要负责人）', type: '责任书', link: 'resp', role: '总经理', funcKey: 'gm' },
      { name: '安全生产责任书（安全管理员）', type: '责任书', link: 'resp', role: '安全员', funcKey: 'safe' },
      { name: '安全生产责任书（驾驶员）', type: '责任书', link: 'resp', role: '驾驶员', funcKey: 'driver' },
      { name: '安全生产承诺书（全员）', type: '承诺书' },
      { name: '岗位职业病危害因素告知书', type: '告知书' },
      { name: '安全生产管理人员任命书', type: '任命书' },
      { name: '成立安全生产领导小组的通知', type: '通知' }
    ].forEach(function (x) { files.push({ fileId: 'f' + (fid++), box: 1, name: x.name, type: x.type, builtin: true, ver: '占位待补', link: x.link, role: x.role, funcKey: x.funcKey || (RESP_FUNC_KEY[x.role] || null), placeholder: true }); });

    // 盒二：制度目录占位
    files.push({ fileId: 'f' + (fid++), box: 2, name: '安全生产管理制度目录', type: '目录', builtin: true, ver: '占位待补', toc: true, placeholder: true });
    files.push({ fileId: 'f' + (fid++), box: 2, name: (isDanger ? '危货' : '普货') + '安全生产管理制度（待补充）', type: '制度', builtin: true, ver: '占位待补', placeholder: true, docNote: (isDanger ? '危货' : '普货') + '全套制度正文，服务商后续提供' });
    return files;
  }

  // —— 服务商种子：普货通用（真实）+ 其余占位 ——
  function seedProviderSets() {
    var full = buildFullFiles();
    return [
      { id: 'P1', name: '普货通用范本', bizType: '普货', region: '全区域', isDefault: true,
        desc: '普货企业通用制度全集（24 项制度 + 责任书/承诺书/告知书/任命书/通知 + 各盒台账范本）', files: full },
      { id: 'P2', name: '普货小微范本', bizType: '普货', region: '全区域', isDefault: false,
        desc: '面向小微普货企业（车辆少、岗位精简）的精简制度包', files: buildSkeletonFiles('普货') },
      { id: 'P3', name: '普货规上范本', bizType: '普货', region: '全区域', isDefault: false,
        desc: '面向规模以上普货企业（多车、多岗、需更完整体系）的扩展制度包', files: buildSkeletonFiles('普货') },
      { id: 'P4', name: '危货通用范本', bizType: '危货', region: '全区域', isDefault: false,
        desc: '危货运输企业通用制度包（含押运员/装卸管理员/车辆技术管理员等岗位）', files: buildSkeletonFiles('危货') },
      { id: 'P5', name: '普货狮山范本', bizType: '普货', region: '狮山', isDefault: false,
        desc: '狮山区域监管特殊版（满足当地监管要求）', files: buildSkeletonFiles('普货') },
      { id: 'P6', name: '普货乐从范本', bizType: '普货', region: '乐从', isDefault: false,
        desc: '乐从区域监管特殊版（满足当地监管要求）', files: buildSkeletonFiles('普货') }
    ];
  }

  function load(key, def) {
    try { var s = localStorage.getItem(key); return s ? JSON.parse(s) : def; } catch (e) { return def; }
  }
  function save(key, val) {
    try { localStorage.setItem(key, JSON.stringify(val)); } catch (e) {}
  }

  function getProviderSets() {
    var arr = load(LS_PROVIDER, null);
    if (!arr) { arr = seedProviderSets(); save(LS_PROVIDER, arr); }
    // 数据升级：给老数据里缺少 funcKey 的责任书文件回填（岗位改名后仍能联动）
    var changed = false;
    arr.forEach(function (s) {
      (s.files || []).forEach(function (f) {
        if (f.link === 'resp' && f.role && f.role !== '__ALL__' && !f.funcKey && RESP_FUNC_KEY[f.role]) {
          f.funcKey = RESP_FUNC_KEY[f.role]; changed = true;
        }
      });
    });
    if (changed) save(LS_PROVIDER, arr);
    return arr;
  }
  function saveProviderSets(arr) { save(LS_PROVIDER, arr); }
  function getProviderSet(id) {
    return getProviderSets().filter(function (s) { return s.id === id; })[0] || null;
  }

  // —— 企业侧 ——
  function getEnterpriseCurrent() {
    var cur = load(LS_ENT_CURRENT, null);
    if (cur) backfillFuncKey(cur);
    return cur;
  }
  function setEnterpriseCurrent(obj) { save(LS_ENT_CURRENT, obj); }

  function getEnterpriseMySets() {
    var arr = load(LS_ENT_MY, []);
    arr.forEach(backfillFuncKey);
    return arr;
  }
  function addEnterpriseSet(obj) { var a = getEnterpriseMySets(); a.push(obj); save(LS_ENT_MY, a); return obj; }
  function getEnterpriseMySet(id) { return getEnterpriseMySets().filter(function (s) { return s.id === id; })[0] || null; }

  // 给某套内责任书文件回填 funcKey（兼容老数据，岗位改名后仍按 funcKey 定位）
  function backfillFuncKey(set) {
    if (!set || !set.files) return;
    var changed = false;
    set.files.forEach(function (f) {
      if (f.link === 'resp' && f.role && f.role !== '__ALL__' && !f.funcKey && RESP_FUNC_KEY[f.role]) {
        f.funcKey = RESP_FUNC_KEY[f.role]; changed = true;
      }
    });
    if (changed && set.id) {
      // 若是当前应用范本则回写
      if (load(LS_ENT_CURRENT, null) && load(LS_ENT_CURRENT, null).id === set.id) save(LS_ENT_CURRENT, set);
    }
  }

  // 从服务商整套范本实例化企业当前应用范本（深拷贝文件）
  function applyProviderSetToEnterprise(providerId, newName) {
    var p = getProviderSet(providerId);
    if (!p) return null;
    var cur = {
      id: 'E' + Date.now(),
      sourceId: p.id,
      sourceName: p.name,
      name: newName || p.name,
      bizType: p.bizType,
      region: p.region,
      customized: false,
      appliedAt: new Date().toISOString().slice(0, 10),
      files: JSON.parse(JSON.stringify(p.files))
    };
    setEnterpriseCurrent(cur);
    return cur;
  }

  // 另存为：把当前范本复制为一份新的「我的范本」变体
  function saveCurrentAsMySet(newName) {
    var cur = getEnterpriseCurrent();
    if (!cur) return null;
    var variant = JSON.parse(JSON.stringify(cur));
    variant.id = 'E' + Date.now();
    variant.name = newName || (cur.name + '（副本）');
    variant.sourceId = cur.sourceId;
    variant.sourceName = cur.sourceName;
    variant.fromCurrent = true;
    variant.customized = true;
    variant.savedAt = new Date().toISOString().slice(0, 10);
    addEnterpriseSet(variant);
    // 另存为后，当前应用范本切换为新变体
    setEnterpriseCurrent(variant);
    return variant;
  }

  // 从「我的范本」切换当前应用范本
  function setCurrentById(id) {
    var s = getEnterpriseMySet(id);
    if (s) { setEnterpriseCurrent(JSON.parse(JSON.stringify(s))); return true; }
    return false;
  }

  // —— 待修订标记（由组织架构等联动模块写入）——
  function markRevision(desc) {
    save(LS_REVISION, { ts: Date.now(), desc: desc || '组织架构调整' });
  }
  function getRevision() { return load(LS_REVISION, null); }
  function clearRevision() { try { localStorage.removeItem(LS_REVISION); } catch (e) {} }

  // 调试/重置
  function resetAll() {
    [LS_PROVIDER, LS_ENT_CURRENT, LS_ENT_MY, LS_REVISION].forEach(function (k) { try { localStorage.removeItem(k); } catch (e) {} });
  }

  window.TplSets = {
    getProviderSets: getProviderSets,
    saveProviderSets: saveProviderSets,
    getProviderSet: getProviderSet,
    getEnterpriseCurrent: getEnterpriseCurrent,
    setEnterpriseCurrent: setEnterpriseCurrent,
    getEnterpriseMySets: getEnterpriseMySets,
    addEnterpriseSet: addEnterpriseSet,
    getEnterpriseMySet: getEnterpriseMySet,
    applyProviderSetToEnterprise: applyProviderSetToEnterprise,
    saveCurrentAsMySet: saveCurrentAsMySet,
    setCurrentById: setCurrentById,
    markRevision: markRevision,
    getRevision: getRevision,
    clearRevision: clearRevision,
    resetAll: resetAll,
    buildFullFiles: buildFullFiles,
    buildSkeletonFiles: buildSkeletonFiles
  };
})();
