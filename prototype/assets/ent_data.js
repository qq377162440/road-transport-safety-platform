/* ============================================================
 * 企业主数据共享层（Enterprise Master Data）
 * 服务商「企业管理」与企业「企业信息管理」共用同一份企业档案。
 * 内存单例 + localStorage 持久化，任一侧修改后另一侧读取即一致。
 * ============================================================ */
(function(){
  const STORE_KEY = 'rd_ent_data_v1';

  /* ---------- 服务商 ---------- */
  const PROVIDERS = [
    { id:'SP01', name:'广东悦通安全技术服务有限公司', contact:'李经理 138****0001' },
    { id:'SP02', name:'佛山安途运输咨询服务有限公司', contact:'王经理 139****0002' },
    { id:'SP03', name:'粤西道路安全科技服务有限公司', contact:'陈经理 137****0003' }
  ];

  /* ---------- 可选字典 ---------- */
  const REGIONS = ['佛山','广州','深圳','东莞','珠海'];
  const CARGOS  = ['普货','危货'];
  const GROUPS = ['重点客户','普通客户','新签约'];
  const STATUSES = ['正常','禁用'];

  /* ---------- 企业详细档案（补充到 BASE 之上） ---------- */
  const PROFILE = {
    E01:{found:'2003-03-28', prov:'广东省', dist:'南海区', tel:'0757-86603101',
         permitNo:'粤交运管许可佛字4406051234', mgmtType:'道路货物运输企业',
         scope:'普通货运，货物专用运输（集装箱）', capital:500, barea:37, parea:9000, safer:2, staff:47, adminUser:'tang_an_admin',
         mainRoute:'佛山—广州—深圳（珠三角干线）', mainCargo:'普货（建材 / 钢材 / 日用百货）',
         parking:{addr:'佛山市南海区狮山镇小塘工业大道 88 号', own:'自有', area:9000, lease:'—'}},
    E02:{found:'2019-06-18', prov:'广东省', dist:'南海区', tel:'0757-86603102',
         permitNo:'粤交运管许可佛字4406055678', mgmtType:'危险货物运输企业',
         scope:'危险货物运输（罐式车辆）；危险货物运输（其他）', capital:800, barea:60, parea:3200, safer:3, staff:26, adminUser:'tantan_admin',
         mainRoute:'佛山—茂名—湛江（危货专线）', mainCargo:'危货（3 类 / 8 类罐式）',
         parking:{addr:'佛山市南海区狮山镇塘安工业区 12 号', own:'租赁', area:3200, lease:'2024-01-01 至 2026-12-31'}},
    E03:{found:'2015-09-02', prov:'广东省', dist:'顺德区', tel:'0757-22338899',
         permitNo:'粤交运管许可佛字4406063210', mgmtType:'道路货物运输企业',
         scope:'普通货运；货运站（场）经营', capital:300, barea:42, parea:1500, safer:2, staff:15, adminUser:'hengtong_admin',
         mainRoute:'顺德—佛山—东莞', mainCargo:'普货（家电 / 家具）',
         parking:{addr:'佛山市顺德区容桂街道货运大道 6 号', own:'自有', area:1500, lease:'—'}},
    E04:{found:'2021-04-12', prov:'广东省', dist:'天河区', tel:'020-38887766',
         permitNo:'粤交运管许可穗字4401068899', mgmtType:'道路货物运输企业',
         scope:'普通货运；危险货物运输（3类）', capital:600, barea:55, parea:2600, safer:2, staff:21, adminUser:'changtong_admin',
         mainRoute:'广州—深圳—东莞', mainCargo:'普货 + 3 类危货',
         parking:{addr:'广州市天河区珠吉路物流园 3 号', own:'租赁', area:2600, lease:'2025-01-01 至 2027-12-31'}},
    E05:{found:'2022-11-08', prov:'广东省', dist:'龙岗区', tel:'0755-26667788',
         permitNo:'粤交运管许可深字4403077766', mgmtType:'道路货物运输企业',
         scope:'普通货运；装卸搬运', capital:450, barea:48, parea:2000, safer:2, staff:18, adminUser:'anyun_admin',
         mainRoute:'深圳—惠州—东莞', mainCargo:'普货（电子产品 / 日用百货）',
         parking:{addr:'深圳市龙岗区平湖街道货运路 9 号', own:'自有', area:2000, lease:'—'}}
  };

  /* ---------- 基础档案（业务子数据：人车/待办/视频/卷子） ---------- */
  function mkEnt(id, name, code, legal, phone, scale, status, sp, contract, modules, people, vehicles, todos, videos, questions, region, cargo, group){
    return { id, name, code, legal, phone, scale, status, sp, contract, modules,
      people, vehicles, todos, videos, questions, region, cargo, group };
  }

  window.ENT_BASE = [

  mkEnt('E01','佛山市南海区小塘塘安运输服务有限公司','9144060574616121X4','黄云云','13403741688','道路货物运输（货运）','正常','SP01',
    {no:'HT-2024-0012',start:'2024-01-01',end:'2026-12-31',amount:'38000',status:'有效'},
    ['archive','monitor','training','danger','risk','emergency','exam','meeting','budget'],
    [{n:'黄云云',r:'主要负责人',s:'在岗'},{n:'李强',r:'安全员',s:'在岗'},{n:'王芳',r:'监控员',s:'在岗'},{n:'陈刚',r:'驾驶员',s:'离职'}],
    [{p:'粤E·12345',t:'重型仓栅式货车',s:'运营'},{p:'粤E·67890',t:'重型栏板货车',s:'运营'},{p:'粤E·11223',t:'罐式运输车',s:'停用'}],
    [{t:'完成 2026 年度安全生产责任制重签',type:'待签文件',due:'2026-11-10',owner:'李强',done:false},
     {t:'组织冬季行车安全月度培训',type:'培训',due:'2026-11-15',owner:'王芳',done:false},
     {t:'车辆粤E·11223 维修后隐患复检',type:'隐患排查',due:'2026-11-05',owner:'李强',done:true}],
    [{n:'防御性驾驶基础教程',c:'驾驶安全',d:'12:30',date:'2026-09-12'},
     {n:'冬季冰雪路面行车要点',c:'季节安全',d:'08:15',date:'2026-10-20'},
     {n:'危货运输应急处置演练',c:'应急处置',d:'15:40',date:'2026-10-28'}],
    [{q:'道路运输企业应当至少每（ ）组织一次安全生产全面检查？',type:'单选',diff:'中',a:'B. 每月'},
     {q:'驾驶员连续驾驶时间不得超过 4 小时，停车休息时间不少于 20 分钟。',type:'判断',diff:'易',a:'正确'},
     {q:'下列不属于重大危险源的是？',type:'单选',diff:'难',a:'C. 普通办公电脑'}],
    '佛山','普货','重点客户'),

  mkEnt('E02','（示例）塘安物流分公司','91440605MA55X7Y93K','陈伟','0757-86603102','危险货物运输（危运）','正常','SP01',
    {no:'HT-2025-0078',start:'2025-03-01',end:'2026-11-30',amount:'52000',status:'临期'},
    ['archive','monitor','training','danger','risk','emergency','exam'],
    [{n:'陈伟',r:'主要负责人',s:'在岗'},{n:'赵敏',r:'安全员',s:'在岗'},{n:'孙强',r:'驾驶员',s:'在岗'}],
    [{p:'粤E·55667',t:'危化品罐车',s:'运营'},{p:'粤E·99001',t:'防爆厢式货车',s:'运营'}],
    [{t:'召开危货运输季度安全会议',type:'会议',due:'2026-11-20',owner:'赵敏',done:false},
     {t:'合同到期前 30 天提醒续签',type:'待签文件',due:'2026-11-01',owner:'服务商',done:false}],
    [{n:'危货装卸作业规范',c:'危货安全',d:'10:05',date:'2026-08-30'},
     {n:'押运员职责与应急处置',c:'应急处置',d:'13:20',date:'2026-09-15'}],
    [{q:'危险货物运输车辆应配备与其承运危险货物性质相适应的（ ）。',type:'单选',diff:'中',a:'B. 防护用品与应急器材'},
     {q:'押运人员可以临时离开运输车辆去就餐。',type:'判断',diff:'易',a:'错误'}],
    '佛山','危货','重点客户'),

  mkEnt('E03','顺德区恒通供应链有限公司','91440606MA4UW2R25T','刘建国','0757-22338899','道路货物运输（货运）','停用','SP01',
    {no:'HT-2023-0045',start:'2023-06-01',end:'2025-05-31',amount:'29000',status:'已到期'},
    ['archive','training','exam'],
    [{n:'刘建国',r:'主要负责人',s:'在岗'},{n:'周婷',r:'安全员',s:'在岗'}],
    [{p:'粤X·33445',t:'轻型厢式货车',s:'停用'}],
    [{t:'停用企业资料归档封存',type:'其他',due:'2026-06-30',owner:'服务商',done:true}],
    [{n:'新员工入职安全教育',c:'岗前培训',d:'09:00',date:'2026-07-01'}],
    [{q:'企业安全生产第一责任人是（ ）。',type:'单选',diff:'易',a:'A. 主要负责人'}],
    '佛山','普货','普通客户'),

  // 服务商 SP02 名下企业
  mkEnt('E04','广州天河畅通物流有限公司','91440106MA5CX9A88T','张伟','020-38887766','道路货物运输（货运）','正常','SP02',
    {no:'HT-2025-0111',start:'2025-05-01',end:'2027-04-30',amount:'41000',status:'有效'},
    ['archive','monitor','training','danger','exam'],
    [{n:'张伟',r:'主要负责人',s:'在岗'},{n:'林静',r:'安全员',s:'在岗'}],
    [{p:'粤A·22334',t:'重型仓栅式货车',s:'运营'},{p:'粤A·55667',t:'轻型厢式货车',s:'运营'}],
    [{t:'完成新员工岗前安全培训',type:'培训',due:'2026-11-25',owner:'林静',done:false}],
    [{n:'货运装载规范',c:'装卸安全',d:'07:40',date:'2026-10-10'}],
    [{q:'装载货物不得超过车辆核定的（ ）。',type:'单选',diff:'易',a:'A. 载质量'}],
    '广州','普货','普通客户'),

  mkEnt('E05','深圳龙岗安运货运有限公司','91440300MA5F12W77H','赵磊','0755-26667788','道路货物运输（货运）','正常','SP02',
    {no:'HT-2025-0155',start:'2025-08-01',end:'2026-12-31',amount:'36000',status:'有效'},
    ['archive','training','exam','monitor'],
    [{n:'赵磊',r:'主要负责人',s:'在岗'},{n:'吴敏',r:'安全员',s:'在岗'}],
    [{p:'粤B·77889',t:'重型栏板货车',s:'运营'}],
    [{t:'年度安全投入台账补录',type:'其他',due:'2026-11-30',owner:'吴敏',done:false}],
    [{n:'防御性驾驶',c:'驾驶安全',d:'11:20',date:'2026-09-01'}],
    [{q:'企业应当建立并落实（ ）排查治理制度。',type:'单选',diff:'中',a:'C. 生产安全事故隐患'}],
    '深圳','普货','普通客户')  ];

  /* ---------- 归一化：补全字段、展开卷子 ---------- */
  function normalize(list){
    const nameMap = {E01:'驾驶员上岗安全考试卷',E02:'危货押运员考试卷',E03:'主要负责人安全考试卷',E04:'驾驶员上岗安全考试卷',E05:'驾驶员上岗安全考试卷'};
    list.forEach(function(e){
      const p = PROFILE[e.id] || {};
      Object.keys(p).forEach(function(k){ if(e[k]===undefined) e[k]=p[k]; });
      if(!e.region) e.region = '佛山';
      if(!e.cargo)  e.cargo  = '普货';
      if(!e.group)  e.group  = '普通客户';
      if(!e.status) e.status = '正常';
      if(e.people) e.people.forEach(function(x,i){ if(x.id==null) x.id=e.id+'P'+i; if(!x.entry) x.entry='—'; });
      if(e.vehicles) e.vehicles.forEach(function(x,i){ if(x.id==null) x.id=e.id+'V'+i; });
      if(e.papers==null){
        e.papers = (e.questions && e.questions.length)
          ? [{ id:e.id+'PA', name:nameMap[e.id]||'安全考试卷', questions:e.questions }] : [];
        delete e.questions;
      }
      if(e.videos) e.videos.forEach(function(v){ if(v.paper===undefined) v.paper=null; });
      if(e.videos && e.videos[0] && e.papers[0] && !e.videos[0].paper) e.videos[0].paper = e.papers[0].id;
      if(!e.accounts) e.accounts = defaultAccounts(e);
    });
    return list;
  }

  /* ---------- 内存单例 + localStorage 持久化 ---------- */
  let MEM = null;

  function seed(){ return normalize(JSON.parse(JSON.stringify(window.ENT_BASE))); }

  function boot(){
    if(MEM) return MEM;
    let raw = null;
    try{ raw = localStorage.getItem(STORE_KEY); }catch(err){}
    if(raw){
      try{ MEM = normalize(JSON.parse(raw)); }catch(err){ MEM = null; }
    }
    if(!MEM) MEM = seed();
    return MEM;
  }
  function commit(){
    try{ localStorage.setItem(STORE_KEY, JSON.stringify(MEM)); }catch(err){}
    return MEM;
  }

  window.addEventListener('storage', function(ev){
    if(ev.key===STORE_KEY && ev.newValue){
      try{ MEM = normalize(JSON.parse(ev.newValue)); }catch(err){}
    }
  });

  /* ---------- 账号池 ---------- */
  function defaultAccounts(e){
    return [
      { u:'admin', n:'系统管理员', r:'企业管理员', s:'启用', last:'2026-11-02 09:12', pwd:'Abc123456' },
      { u:'safe_'+e.id, n:e.legal, r:'安全员', s:'启用', last:'2026-10-29 14:03', pwd:'Abc123456' },
      { u:'driver_'+e.id, n:'一线驾驶员', r:'驾驶员', s: e.id==='E01'?'禁用':'启用', last:'2026-10-21 18:40', pwd:'Abc123456' }
    ];
  }

  /* ---------- 对外 API ---------- */
  window.EntData = {
    PROVIDERS: PROVIDERS,
    REGIONS: REGIONS,
    CARGOS: CARGOS,
    GROUPS: GROUPS,
    STATUSES: STATUSES,

    list: function(){ return boot(); },
    commit: function(){ return commit(); },
    get: function(id){ return boot().filter(function(e){ return e.id===id; })[0] || null; },
    listByProvider: function(spId){ return boot().filter(function(e){ return e.sp===spId; }); },

    current: function(){
      const list = boot();
      const uid = localStorage.getItem('userName') || 'aa';
      const hit = list.filter(function(e){ return e.adminUser===uid; })[0];
      return hit || list[0] || null;
    },

    add: function(data){
      const list = boot();
      const maxN = list.reduce(function(mx,e){
        const n = parseInt(String(e.id).replace(/\D/g,''),10);
        return isNaN(n)? mx : Math.max(mx,n);
      }, 0);
      const id = 'E' + String(maxN+1).padStart(2,'0');
      const e = {
        id: id,
        name: (data && data.name) || ('新企业-'+id),
        code: (data && data.code) || ('91440600' + String(Math.floor(Math.random()*1e10)).padStart(10,'0')),
        legal: (data && data.legal) || '—',
        phone: (data && data.phone) || '—',
        scale: (data && data.scale) || '道路货物运输（货运）',
        status: '正常',
        sp: (data && data.sp) || 'SP01',
        contract: { no:'HT-2026-' + String(Math.floor(Math.random()*9000)+1000), start:'2026-01-01', end:'2027-12-31', amount:'36000', status:'有效' },
        modules: ['archive','training','exam'],
        people: [], vehicles: [], todos: [], videos: [], papers: [],
        region: (data && data.region) || '佛山',
        cargo: (data && data.cargo) || '普货',
        group: (data && data.group) || '普通客户',
        found:'2026-01-01', prov:'广东省', dist:'', tel:'', permitNo:'',
        mgmtType:'道路货物运输企业', scope:'普通货运', capital:0, barea:0, parea:0, safer:1, staff:1,
        mainRoute:'', mainCargo:'', parking:{addr:'', own:'自有', area:'', lease:''},
        adminUser: (data && data.adminUser) || ('ent_'+id.toLowerCase())
      };
      // 新增企业：主账号用户名为企业名称全称，初始密码 888888
      e.accounts = [
        { u: e.name, n:'系统管理员', r:'企业管理员', s:'启用', last:'—', pwd:'888888' },
        { u:'safe_'+id, n:e.legal, r:'安全员', s:'启用', last:'—', pwd:'888888' },
        { u:'driver_'+id, n:'一线驾驶员', r:'驾驶员', s:'启用', last:'—', pwd:'888888' }
      ];
      list.push(e);
      commit();
      return e;
    },

    update: function(id, patch){
      const e = this.get(id);
      if(!e) return null;
      Object.keys(patch).forEach(function(k){ e[k]=patch[k]; });
      commit();
      return e;
    },

    toggleStatus: function(id){
      const e = this.get(id);
      if(!e) return null;
      e.status = (e.status==='正常') ? '禁用' : '正常';
      if(e.status==='禁用' && e.accounts) e.accounts.forEach(function(a){ a.s='禁用'; });
      commit();
      return e;
    },

    setPassword: function(entId, username, pwd){
      const e = this.get(entId);
      if(!e || !e.accounts) return false;
      const a = e.accounts.filter(function(x){ return x.u===username; })[0];
      if(!a) return false;
      a.pwd = pwd;
      commit();
      return true;
    },

    addAccount: function(entId, acc){
      const e = this.get(entId);
      if(!e) return false;
      if(!e.accounts) e.accounts = defaultAccounts(e);
      e.accounts.push(Object.assign({ s:'启用', last:'—', pwd:'888888' }, acc));
      commit();
      return true;
    },
    updateAccount: function(entId, username, patch){
      const e = this.get(entId);
      if(!e || !e.accounts) return false;
      const a = e.accounts.filter(function(x){ return x.u===username; })[0];
      if(!a) return false;
      Object.keys(patch).forEach(function(k){ a[k]=patch[k]; });
      commit();
      return true;
    },
    toggleAccount: function(entId, username){
      const e = this.get(entId);
      if(!e || !e.accounts) return false;
      const a = e.accounts.filter(function(x){ return x.u===username; })[0];
      if(!a) return false;
      a.s = (a.s==='启用') ? '禁用' : '启用';
      commit();
      return true;
    },
    removeAccount: function(entId, username){
      const e = this.get(entId);
      if(!e || !e.accounts) return false;
      e.accounts = e.accounts.filter(function(x){ return x.u!==username; });
      commit();
      return true;
    },

    reset: function(){
      try{ localStorage.removeItem(STORE_KEY); }catch(err){}
      MEM = seed();
      return MEM;
    }
  };
})();
