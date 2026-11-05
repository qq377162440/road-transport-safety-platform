/* 服务商课程库数据层：课程（含视频与绑定考题）+ 考试卷（含试题）
 * 作为「视频库及考题管理」模块的单一数据源，通过 localStorage 持久化（刷新不丢）。
 * 课程分类 / 行业分类 / 岗位分类 / 使用范围 / 来源 字典集中定义，供筛选与表单复用。
 */
window.CourseData = (function () {
  'use strict';
  const KEY = 'rd_course_v1';

  const CATEGORIES = ['安全管理知识','警示教育案例','法律法规','职业道德和生理健康','疫情防控','道路运输业务知识','应急救援处置','安全操作规程','安全行车规范','接打手机报警','玩手机报警','抽烟报警','驾驶超时报警','超速报警','设备遮挡失效报警','未系安全带报警','双手脱离方向盘报警','生理疲劳报警'];
  const INDUSTRIES = ['危险货物运输','普通货物运输','道路旅客运输','出租客运','公交客运'];
  const POSTS = ['管理人员','驾驶员','押运员','装卸人员','动态监控人员'];
  const SCOPES = ['全部','会员'];
  const SOURCES = ['服务商','平台'];

  // 演示种子数据（与参考设计图示例一致）
  function seed() {
    return {
      total: 726,
      courses: [
        {id:'C1', name:'佛山市安全生产委员会办公室 佛山市消防安全委员会办公室关于开展2026年佛山市消防宣传月活动的通知', hours:1, category:['法律法规'], industry:'普通货物运输', post:['管理人员','驾驶员'], duration:'—', source:'服务商', paperId:null, scope:'全部', createTime:'2026-11-02 17:01:16', videos:[{n:'消防宣传月活动通知宣贯',d:'08:30'}]},
        {id:'C2', name:'消防控制室“四快”处置规程和“五知”评价标准', hours:1, category:['安全管理知识'], industry:'普通货物运输', post:['管理人员'], duration:'10分', source:'服务商', paperId:null, scope:'全部', createTime:'2026-11-02 17:00:00', videos:[{n:'消防控制室“四快”处置规程讲解',d:'10:00'}]},
        {id:'C3', name:'消防基本技能实操实训指南', hours:1, category:['安全管理知识'], industry:'普通货物运输', post:['管理人员','驾驶员'], duration:'10分', source:'服务商', paperId:null, scope:'全部', createTime:'2026-11-02 16:55:00', videos:[{n:'消防基本技能实操演示',d:'10:00'}]},
        {id:'C4', name:'客运车辆火灾扑救与乘客应急疏散', hours:1, category:['道路运输业务知识'], industry:'道路旅客运输', post:['驾驶员','押运员'], duration:'13分', source:'平台', paperId:'P1', scope:'会员', createTime:'2026-11-02 16:45:49', videos:[{n:'客运车辆火灾扑救演练',d:'13:00'}]},
        {id:'C5', name:'道路运输企业冬季安全生产提醒', hours:1, category:['安全操作规程','安全行车规范'], industry:'普通货物运输', post:['驾驶员'], duration:'08分', source:'平台', paperId:'P1', scope:'会员', createTime:'2026-10-29 10:30:00', videos:[{n:'冬季行车安全风险提醒',d:'08:00'}]}
      ],
      papers: [
        {id:'P1', name:'消防安全与应急知识考试卷', questions:[
          {q:'消防控制室“四快”处置规程中的“四快”不包括以下哪项？', type:'单选', diff:'易', a:'快确认'},
          {q:'客运车辆发生火灾时，应优先组织乘客从哪个车门疏散？', type:'单选', diff:'中', a:'就近车门'},
          {q:'冬季冰雪路面行车，应加大安全车距、降低车速。', type:'判断', diff:'易', a:'正确'}
        ]}
      ]
    };
  }

  let MEM = load() || seed();
  function load(){ try { const s = localStorage.getItem(KEY); return s ? JSON.parse(s) : null; } catch(e){ return null; } }
  function commit(){ try { localStorage.setItem(KEY, JSON.stringify(MEM)); } catch(e){} }
  function uid(p){ return p + Date.now().toString(36) + Math.floor(Math.random()*1e4).toString(36); }

  return {
    CATEGORIES, INDUSTRIES, POSTS, SCOPES, SOURCES,
    get total(){ return MEM.total; },
    list(){ return MEM.courses; },
    papers(){ return MEM.papers; },
    paper(id){ return MEM.papers.find(p=>p.id===id) || null; },
    addCourse(c){ c.id = c.id || uid('C'); MEM.courses.unshift(c); commit(); return c; },
    updateCourse(id, patch){ const i=MEM.courses.findIndex(x=>x.id===id); if(i>=0){ MEM.courses[i]=Object.assign(MEM.courses[i], patch); commit(); } },
    delCourse(id){ MEM.courses = MEM.courses.filter(x=>x.id!==id); commit(); },
    addPaper(p){ p.id = p.id || uid('P'); MEM.papers.push(p); commit(); return p; },
    delPaper(pid){ MEM.papers = MEM.papers.filter(p=>p.id!==pid); MEM.courses.forEach(c=>{ if(c.paperId===pid) c.paperId=null; }); commit(); },
    addQuestion(pid, q){ const p=MEM.papers.find(x=>x.id===pid); if(p){ p.questions.push(q); commit(); } },
    delQuestion(pid, qi){ const p=MEM.papers.find(x=>x.id===pid); if(p){ p.questions.splice(qi,1); commit(); } },
    reset(){ MEM = seed(); commit(); }
  };
})();
