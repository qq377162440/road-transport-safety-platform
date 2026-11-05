/* ============================================================
 * 组织架构共享数据层 v2（Org Master Data）
 * 变更（对齐需求）：
 *  1. 去掉「部门」层 —— 架构 = 公司 → 总经理 → 各岗位（按上级关系 parentId 组树）
 *  2. 12 个系统职能（func）：职能名称企业侧不可改，岗位名称可自定义
 *  3. 强制岗位仅 3 个：总经理、安全员、监控员；其余可禁用
 *  4. 职能关联岗位权限 / 安全目标 / 职责 / 考核标准；制度与架构双向联动
 *     （被禁用岗位自动从「安全生产责任制」等制度内容中剔除）
 *  5. 服务商侧可配置架构模板：普货(1,2,4,10) / 危货(1,2,3,4,8,9,10,11,12)
 *  6. 岗位可兼并其他岗位职能（总经理、安全员除外）
 *  7. 人员一律从「系统已录入人员」（人员库）选取，不手动输入
 * 「安全管理架构（图示）」与「岗位信息管理」共用本数据层，
 *   localStorage 持久化 + storage 事件跨页实时联动。
 * ============================================================ */
(function(){
  const STORE_KEY = 'rd_org_data_v2';
  const TPL_KEY   = 'rd_org_templates_v1';

  /* ---------- 12 个系统职能字典（职能名企业侧不可改） ---------- */
  const FUNCS = {
    gm:      { no:1,  name:'总经理',        label:'主要负责人',     required:true,
      permissions:'安全生产第一责任人权限；重大决策审批；制度与预案签发；安全费用审批',
      goals:'全面领导公司安全生产工作，年度零重特大事故、死亡事故为零',
      duties:'建立健全并落实全员安全生产责任制；组织制定安全生产规章制度和操作规程；保证安全生产投入有效实施；组织建立并落实安全风险分级管控和隐患排查治理双重预防机制',
      assess:'年度安全生产目标完成情况、事故控制指标、责任制考核得分',
      docs:['安全生产责任制','主要负责人任命书','主要负责人告知书','年度安全生产承诺书','安全生产责任书（岗位版）'] },
    safe:    { no:2,  name:'安全员',        label:'安全管理员',     required:true,
      permissions:'安全检查与隐患督办权；培训组织权；违章纠正与越级上报权',
      goals:'组织或参与拟定安全生产规章制度、操作规程和应急救援预案，隐患整改闭环率 100%',
      duties:'组织安全生产教育培训；组织隐患排查并督促整改闭环；制止和纠正违章指挥、强令冒险作业行为；组织应急演练',
      assess:'制度修订完成率、隐患整改闭环率、培训计划完成率',
      docs:['安全生产责任制','安全管理人员任命书','安全检查制度','隐患排查治理制度','安全生产责任书（岗位版）'] },
    disp:    { no:3,  name:'运营调度员',    label:'运营调度员',     required:false,
      permissions:'运力调度派单权；出车审核权；违规调度一票否决',
      goals:'合理调度运力，杜绝超载超速疲劳驾驶派单',
      duties:'编制运输计划与车辆调度；核查驾驶员资格与车辆技术状况；动态跟踪运输任务执行',
      assess:'调度任务及时率、违规派单次数',
      docs:['安全生产责任制','运营调度管理制度','安全生产责任书（岗位版）'] },
    mon:     { no:4,  name:'监控员',        label:'动态监控管理员', required:true,
      permissions:'动态监控平台操作权；报警干预与纠正权；监控数据导出权',
      goals:'动态监控报警处置及时率 100%，监控台账完整率 100%',
      duties:'24 小时实时监控车辆动态；对超速、疲劳驾驶等报警及时提醒纠正并留档；填记动态监控台账',
      assess:'报警处置及时率、监控台账完整率',
      docs:['安全生产责任制','动态监控制度','安全生产责任书（岗位版）'] },
    clerk:   { no:5,  name:'资料员',        label:'行政办公管理员', required:false,
      permissions:'档案与文件管理权；会议组织权；台账归档权',
      goals:'行政后勤保障到位，文件档案归档及时',
      duties:'公文收发与档案管理；会议组织与后勤保障；安全台账归档',
      assess:'档案归档及时率、会议组织完成率',
      docs:['安全生产责任制','档案管理制度','安全生产责任书（岗位版）'] },
    finance: { no:6,  name:'财务',          label:'财务',           required:false,
      permissions:'安全费用核算权；资金拨付执行权',
      goals:'安全生产费用足额提取、专款专用',
      duties:'安全生产费用提取与使用核算；保险、赔偿等资金保障',
      assess:'安全费用提取率、使用台账合规率',
      docs:['安全生产责任制','安全投入管理制度','安全生产责任书（岗位版）'] },
    captain: { no:7,  name:'车队长',        label:'车队长',         required:false,
      permissions:'车队日常管理权；出车前叮嘱执行权；行车日志抽查权',
      goals:'车队安全行车零重大责任事故',
      duties:'组织车队日常安全例会与出车前叮嘱；管理驾驶员行车日志与三检执行',
      assess:'车队违章率、事故率、三检执行率',
      docs:['安全生产责任制','车队安全管理制度','一日三检制度','安全生产责任书（岗位版）'] },
    vehtech: { no:8,  name:'车辆技术管理员',label:'车辆技术管理员', required:false,
      permissions:'车辆维保计划编制权；技术档案管理权；车辆放行审核权',
      goals:'车辆技术状况完好率 ≥98%',
      duties:'车辆维护保养计划制定与执行；车辆技术档案管理；车辆证照年审跟踪',
      assess:'车辆完好率、维保计划完成率',
      docs:['安全生产责任制','车辆维护保养制度','安全生产责任书（岗位版）'] },
    parklot: { no:9,  name:'停车场管理员',  label:'停车场管理员',   required:false,
      permissions:'停车场进出管理权；消防设施巡查权',
      goals:'停车场秩序与消防设施完好',
      duties:'车辆进出登记与停放管理；停车场消防设施巡查',
      assess:'停车场巡查台账完整率',
      docs:['安全生产责任制','停车场管理制度','安全生产责任书（岗位版）'] },
    driver:  { no:10, name:'驾驶员',        label:'驾驶员',         required:false,
      permissions:'车辆驾驶操作权（持证）；三检执行义务；行车日志填记义务',
      goals:'安全行车零责任事故，三检执行率 100%',
      duties:'严格执行一日三检；填记行车日志；遵章驾驶不超速不疲劳驾驶',
      assess:'三检执行率、行车日志完整率、违章次数',
      docs:['安全生产责任制','一日三检制度','安全生产责任书（岗位版）'] },
    escort:  { no:11, name:'押运员',        label:'押运员',         required:false,
      permissions:'货物押运监护权；紧急情况处置权',
      goals:'押运货物零丢失零泄漏',
      duties:'装卸货物监装监卸；运输途中押运监护；紧急情况先期处置',
      assess:'押运任务完成率、监护记录完整率',
      docs:['安全生产责任制','装卸管理制度','安全生产责任书（岗位版）'] },
    loader:  { no:12, name:'装卸管理员',    label:'装卸管理员',     required:false,
      permissions:'装卸作业组织权；装卸区安全管控权',
      goals:'装卸作业零事故',
      duties:'组织装卸作业；核查装卸区安全条件；装卸设备状态确认',
      assess:'装卸作业合规率、装卸区隐患整改率',
      docs:['安全生产责任制','装卸管理制度','安全生产责任书（岗位版）'] }
  };

  /* ---------- 架构模板（服务商侧可配置，默认值如下） ---------- */
  const TPL_DEFAULT = {
    putong: ['gm','safe','mon','driver'],                                          /* 普货默认：1,2,4,10 */
    weihuo: ['gm','safe','disp','mon','vehtech','parklot','driver','escort','loader'] /* 危货默认：1,2,3,4,8,9,10,11,12 */
  };
  /* 默认上级关系（层级）：总经理为根，其余按管理关系挂接 */
  const PARENT_DEFAULT = {
    gm:null, safe:'gm', disp:'gm', mon:'safe', clerk:'gm', finance:'gm',
    captain:'disp', vehtech:'gm', parklot:'gm', driver:'captain', escort:'captain', loader:'captain'
  };
  /* 多人员岗位（班组）：架构图中显示“×× 等 N 人” */
  const GROUP_DEFAULT = { driver:14, escort:12, loader:6 };

  /* ---------- 系统已录入人员（人员库，来自「人员基础信息」录入） ---------- */
  const PEOPLE = [
    { id:'U1', name:'张伟',  phone:'138****0001', post:'法人/负责人' },
    { id:'U2', name:'李强',  phone:'138****0002', post:'安全员' },
    { id:'U3', name:'王芳',  phone:'138****0003', post:'安全员' },
    { id:'U4', name:'陈刚',  phone:'138****0004', post:'驾驶员' },
    { id:'U5', name:'刘洋',  phone:'138****0005', post:'驾驶员' }
  ];

  function makePost(key, tpl){
    const f = FUNCS[key];
    return {
      id: 'P'+f.no, funcKey: key, name: f.name,            /* 岗位名称默认=职能默认名，可自定义 */
      parentId: PARENT_DEFAULT[key] ? ('P'+FUNCS[PARENT_DEFAULT[key]].no) : null,
      required: f.required, enabled: tpl.indexOf(key)>=0,
      person: null, group: GROUP_DEFAULT[key] || 0,        /* person:{id,name,phone}；group>0 显示班组人数 */
      permissions: f.permissions, goals: f.goals, duties: f.duties, assess: f.assess,
      docs: f.docs.slice(), mergedIds: []                  /* 兼并的其他岗位 funcKey */
    };
  }
  /* 演示企业：按普货模板初始化（服务商模板可切换） */
  function seed(tplType){
    const tpl = getTemplates()[tplType || 'putong'];
    const posts = Object.keys(FUNCS).map(function(k){ return makePost(k, tpl); });
    /* 演示人员分配（全部来自系统已录入人员） */
    const assign = { gm:'U1', safe:'U2', mon:'U3', driver:'U4' };
    posts.forEach(function(p){
      if(assign[p.funcKey]){
        const u = PEOPLE.filter(function(x){ return x.id===assign[p.funcKey]; })[0];
        p.person = { id:u.id, name:u.name, phone:u.phone };
      }
    });
    return { company:'佛山市高明高洲燃气有限公司', tplType: tplType||'putong', posts: posts };
  }

  /* ---------- 模板配置存取（服务商侧） ---------- */
  function getTemplates(){
    let t = null;
    try{ t = JSON.parse(localStorage.getItem(TPL_KEY)); }catch(err){}
    if(!t || !t.putong || !t.weihuo){
      t = JSON.parse(JSON.stringify(TPL_DEFAULT));
      try{ localStorage.setItem(TPL_KEY, JSON.stringify(t)); }catch(err){}
    }
    return t;
  }
  function saveTemplates(t){
    try{ localStorage.setItem(TPL_KEY, JSON.stringify(t)); }catch(err){}
    return t;
  }

  let MEM = null;
  function boot(){
    if(MEM) return MEM;
    let raw = null;
    try{ raw = localStorage.getItem(STORE_KEY); }catch(err){}
    if(raw){ try{ MEM = JSON.parse(raw); }catch(err){ MEM = null; } }
    if(!MEM || !MEM.posts || MEM.posts.length!==12) MEM = seed('putong');
    return MEM;
  }
  function commit(){
    try{ localStorage.setItem(STORE_KEY, JSON.stringify(MEM)); }catch(err){}
    window.dispatchEvent(new StorageEvent('storage', { key:STORE_KEY, newValue:JSON.stringify(MEM) }));
    return MEM;
  }
  window.addEventListener('storage', function(ev){
    if(ev.key===STORE_KEY && ev.newValue){
      try{ MEM = JSON.parse(ev.newValue); }catch(err){}
    }
  });

  function descendants(id, list){
    list = list || boot().posts;
    const out = [];
    (function walk(pid){
      list.filter(function(p){ return p.parentId===pid; }).forEach(function(p){ out.push(p.id); walk(p.id); });
    })(id);
    return out;
  }
  /* 可兼并的岗位：排除自身、下级、总经理、安全员 */
  function mergeable(excludeId){
    return boot().posts.filter(function(p){
      return p.funcKey!=='gm' && p.funcKey!=='safe' && p.id!==excludeId && descendants(excludeId).indexOf(p.id)<0;
    });
  }

  window.OrgData = {
    FUNCS: FUNCS, PEOPLE: PEOPLE,
    COMPANY: function(){ return boot().company; },
    TPL_TYPE: function(){ return boot().tplType; },
    posts:  function(){ return boot().posts; },
    enabledPosts: function(){ return boot().posts.filter(function(p){ return p.enabled; }); },
    get: function(id){ return boot().posts.filter(function(p){ return p.id===id; })[0] || null; },
    getByFunc: function(k){ return boot().posts.filter(function(p){ return p.funcKey===k; })[0] || null; },
    funcLabel: function(p){ return p.funcKey ? FUNCS[p.funcKey].label : '自定义岗位'; },
    people: function(){ return PEOPLE; },
    descendants: descendants,
    mergeable: mergeable,
    templates: getTemplates,
    saveTemplates: saveTemplates,

    /* 更新岗位（岗位名称/上级/目标职责考核/兼并/关联制度）——职能不可改 */
    updatePost: function(id, patch){
      const p = this.get(id); if(!p) return null;
      if(patch.funcKey && patch.funcKey!==p.funcKey) delete patch.funcKey; /* 职能企业侧不可改 */
      Object.keys(patch).forEach(function(k){ p[k]=patch[k]; });
      commit(); return p;
    },
    /* 兼并：把 target 的职能并入 self，target 置为禁用+mergedInto */
    mergeInto: function(selfId, targetId){
      const s = this.get(selfId), t = this.get(targetId);
      if(!s || !t) return null;
      if(s.funcKey==='gm' || s.funcKey==='safe'){ toast0 && 0; return null; } /* 总经理/安全员不可兼并 */
      if(t.funcKey==='gm' || t.funcKey==='safe') return null;                 /* 不可兼并其职能 */
      if(s.mergedIds.indexOf(t.funcKey)<0) s.mergedIds.push(t.funcKey);
      t.enabled = false; t.mergedInto = s.id;
      t.parentId = s.id;
      commit(); return s;
    },
    /* 人员分配：从系统已录入人员中选取 */
    assignPerson: function(id, personId){
      const p = this.get(id); if(!p) return null;
      const u = PEOPLE.filter(function(x){ return x.id===personId; })[0];
      if(!u){ p.person = null; commit(); return p; }
      p.person = { id:u.id, name:u.name, phone:u.phone };
      commit(); return p;
    },
    /* 禁用/恢复（强制岗位不可禁用） */
    togglePost: function(id){
      const p = this.get(id);
      if(!p || p.required) return null;
      p.enabled = !p.enabled;
      if(!p.enabled && p.mergedInto) delete p.mergedInto;
      commit(); return p;
    },
    /* 应用服务商模板：重置企业岗位启用态（保留名称/人员等已配置信息） */
    applyTemplate: function(type){
      const tpl = getTemplates()[type];
      if(!tpl) return null;
      boot().tplType = type;
      boot().posts.forEach(function(p){
        p.enabled = tpl.indexOf(p.funcKey)>=0;
        if(!p.enabled) delete p.mergedInto;
      });
      commit(); return boot();
    },
    /* —— 制度双向联动 ——
     * 安全生产责任制等内容由「启用中」的岗位动态生成；
     * 被禁用/被兼并的岗位自动从制度内容中剔除。 */
    activeDocPosts: function(){
      return boot().posts.filter(function(p){ return p.enabled; })
        .sort(function(a,b){ return FUNCS[a.funcKey].no - FUNCS[b.funcKey].no; });
    },
    responsibilityDoc: function(){
      return this.activeDocPosts().map(function(p){
        return { name:p.name, label:OrgData.funcLabel(p), goals:p.goals, duties:p.duties, assess:p.assess,
                 merged:(p.mergedIds||[]).map(function(k){ return FUNCS[k].label; }) };
      });
    },
    /* 岗位是否在制度中出现（双向联动查询） */
    inDoc: function(id){ const p = this.get(id); return !!(p && p.enabled && !p.mergedInto); }
  };
  function toast0(){ /* 兼并保护占位 */ }
})();
