/* ============================================================
 * 安全管理组织架构图 · 共享生成器（OrgChart）
 * ------------------------------------------------------------
 * 用途：org.html（架构配置页）与 archive_flow.html（电子档案柜归档预览）
 *      共用同一张图、同一套样式、同一份算法，保证「所见即所归档」。
 *
 * 核心规则（对齐需求）：
 *  1. 只渲染「启用中」岗位；被禁用岗位不再以灰色占位出现，避免层级错位/格式变形。
 *  2. 禁用岗位若作为中间层级，其启用下级沿 parentId 链自动上提到
 *     「最近的启用上级」，保证层级不断链。
 *  3. 总经理/主要负责人恒为岗位树根；按系统职能序号（FUNCS.no）稳定排序。
 *  4. 防环：上级链出现重复节点即断开；树构建再做一次访问标记兜底。
 *  5. 输出打印友好 CSS；wrapDoc() 生成自包含 A4 横向 HTML，
 *     浏览器打印对话框选「另存为 PDF」即可归档 PDF。
 * ============================================================ */
(function(){
  function esc(s){
    return String(s==null?'':s).replace(/[&<>"']/g, function(c){
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
  }

  /* ---------- 1. 规范化：只保留启用岗位 + 上提 + 组树 ---------- */
  function normalize(){
    const OD = window.OrgData;
    const empty = { company:'', roots:[], nodes:[], disabled:[], lifted:0 };
    if(!OD || !OD.posts) return empty;

    const all = OD.posts();
    const byId = Object.create(null);
    all.forEach(function(p){ byId[p.id] = p; });

    const noOf = function(p){
      return (p.funcKey && OD.FUNCS && OD.FUNCS[p.funcKey]) ? OD.FUNCS[p.funcKey].no : 999;
    };

    /* 沿 parentId 链向上找最近的启用岗位；跳过所有禁用节点（即「上提」） */
    function resolveParent(p){
      const seen = Object.create(null);
      seen[p.id] = 1;
      let pid = p.parentId, lifted = false;
      while(pid){
        if(seen[pid]) return { id:null, lifted:lifted };   /* 环：断开 */
        seen[pid] = 1;
        const q = byId[pid];
        if(!q) return { id:null, lifted:lifted };          /* 上级已不存在 */
        if(q.enabled) return { id:q.id, lifted:lifted };
        lifted = true;                                     /* 中间有禁用层级 */
        pid = q.parentId;
      }
      return { id:null, lifted:lifted };
    }

    const nodes = all.filter(function(p){ return p.enabled; }).map(function(p){
      const r = resolveParent(p);
      return {
        id: p.id, funcKey: p.funcKey, no: noOf(p),
        name: p.name || '', label: OD.funcLabel ? OD.funcLabel(p) : '',
        required: !!p.required,
        person: (p.person && p.person.name) ? p.person.name : '',
        group: p.group || 0,
        merged: (p.mergedIds||[]).map(function(k){
          return (OD.FUNCS && OD.FUNCS[k]) ? OD.FUNCS[k].label : k;
        }),
        /* 总经理恒为岗位树根，避免被误挂到下级 */
        parentId: p.funcKey==='gm' ? null : r.id,
        lifted: p.funcKey==='gm' ? false : r.lifted,
        children: []
      };
    }).sort(function(a,b){ return a.no - b.no; });

    const byNode = Object.create(null);
    nodes.forEach(function(n){ byNode[n.id] = n; });

    /* 分组挂树（含防环兜底） */
    const kids = Object.create(null);
    nodes.forEach(function(n){
      const k = n.parentId || '__ROOT__';
      (kids[k] = kids[k] || []).push(n);
    });
    const roots = (kids['__ROOT__'] || []).slice();
    if(!roots.length && nodes.length){
      const head = nodes.filter(function(n){ return n.funcKey==='gm'; })[0] || nodes[0];
      head.parentId = null; roots.push(head);
    }
    const visited = Object.create(null);
    function walk(list, ancestors){
      list.forEach(function(n){
        if(visited[n.id] || ancestors[n.id]) return;
        visited[n.id] = 1;
        n.children = (kids[n.id] || []).filter(function(c){
          return c.id!==n.id && !ancestors[c.id];
        }).sort(function(a,b){ return a.no - b.no; });
        const next = Object.assign(Object.create(null), ancestors);
        next[n.id] = 1;
        walk(n.children, next);
      });
    }
    walk(roots, Object.create(null));
    /* 环内未被访问的节点：降级为根，绝不丢失岗位 */
    nodes.forEach(function(n){
      if(!visited[n.id]){ n.children = []; roots.push(n); visited[n.id] = 1; }
    });
    roots.sort(function(a,b){ return a.no - b.no; });

    return {
      company: OD.COMPANY ? (OD.COMPANY() || '') : '',
      roots: roots, nodes: nodes,
      disabled: all.filter(function(p){ return !p.enabled; }).map(function(p){ return p.name; }),
      lifted: nodes.filter(function(n){ return n.lifted; }).length
    };
  }

  /* ---------- 2. 架构图 CSS（screen + print 同源） ---------- */
  function css(){
    return [
      '.ocg{overflow:auto;padding:10px 4px 18px;background:transparent}',
      '.ocg-compact{padding:2px 0 8px}',
      '.ocg-tree,.ocg-tree ul{list-style:none;margin:0;padding:0}',
      '.ocg-tree{display:flex;justify-content:center;min-width:760px}',
      '.ocg-tree ul{display:flex;justify-content:center;padding-top:22px;position:relative}',
      '.ocg-tree li{display:flex;flex-direction:column;align-items:center;padding:22px 6px 0;position:relative}',
      '.ocg-tree li::before,.ocg-tree li::after{content:"";position:absolute;top:0;right:50%;width:50%;height:22px;border-top:1px solid #A8C4E8}',
      '.ocg-tree li::after{right:auto;left:50%;border-left:1px solid #A8C4E8}',
      '.ocg-tree li:only-child{padding-top:0}',
      '.ocg-tree li:only-child::before,.ocg-tree li:only-child::after{display:none}',
      '.ocg-tree>li{padding-top:0}',
      '.ocg-tree>li::before,.ocg-tree>li::after{display:none}',
      '.ocg-tree li:first-child::before,.ocg-tree li:last-child::after{border-top:0 none}',
      '.ocg-tree li:last-child::before{border-right:1px solid #A8C4E8;border-radius:0 6px 0 0}',
      '.ocg-tree li:first-child::after{border-radius:6px 0 0 0}',
      /* 每个父节点到子层之间的竖向连线（含公司 → 主要负责人这一层） */
      '.ocg-tree li>ul::before{content:"";position:absolute;top:0;left:50%;border-left:1px solid #A8C4E8;width:0;height:22px}',
      /* 节点卡片 */
      '.ocg-node{min-width:150px;max-width:200px;border:1px solid #C9D9F0;border-radius:8px;overflow:hidden;background:#fff;box-shadow:0 2px 7px rgba(15,23,42,.09);font-family:inherit;box-sizing:border-box}',
      '.ocg-node[data-post]{cursor:context-menu;user-select:none;transition:.15s}',
      '.ocg-node[data-post]:hover{transform:translateY(-2px);box-shadow:0 6px 16px rgba(29,78,216,.22)}',
      '.ocg-node .ocg-t{padding:7px 12px;font-size:13px;font-weight:700;color:#fff;background:#2563EB;text-align:center;line-height:1.45;white-space:nowrap}',
      '.ocg-node .ocg-t .ocg-fl{display:block;font-size:11px;font-weight:500;opacity:.88;margin-top:1px}',
      '.ocg-node .ocg-p{padding:5px 8px;font-size:12px;color:#1E40AF;background:#DBEAFE;text-align:center;line-height:1.5;white-space:nowrap}',
      '.ocg-node .ocg-p b{font-weight:700}',
      '.ocg-node .ocg-p .ocg-dim{color:#64748B}',
      '.ocg-node .ocg-mg{background:#DBEAFE;padding:0 8px 5px;text-align:center}',
      '.ocg-node .ocg-mg .ocg-m{font-size:11px;color:#1E40AF;border-top:1px dashed #93C5FD;padding:3px 0;line-height:1.4}',
      '.ocg-node.ocg-gm .ocg-t{background:#7C3AED}',
      '.ocg-node.ocg-company{min-width:230px;max-width:none;border:none;box-shadow:0 2px 8px rgba(124,58,237,.2)}',
      '.ocg-node.ocg-company .ocg-t{background:#7C3AED;font-size:14px;padding:9px 20px}',
      /* 紧凑模式（PDF 预览 / 打印） */
      '.ocg-compact .ocg-tree{min-width:0}',
      '.ocg-compact .ocg-tree ul{padding-top:16px}',
      '.ocg-compact .ocg-tree li{padding:16px 3px 0}',
      '.ocg-compact .ocg-tree li::before,.ocg-compact .ocg-tree li::after{height:16px}',
      '.ocg-compact .ocg-tree li>ul::before{height:16px}',
      '.ocg-compact .ocg-node{min-width:104px;max-width:140px;border-radius:5px;box-shadow:none}',
      '.ocg-compact .ocg-node .ocg-t{padding:5px 6px;font-size:11px}',
      '.ocg-compact .ocg-node .ocg-t .ocg-fl{font-size:9.5px}',
      '.ocg-compact .ocg-node .ocg-p{padding:3px 5px;font-size:10px}',
      '.ocg-compact .ocg-node .ocg-mg .ocg-m{font-size:9.5px}',
      '.ocg-compact .ocg-node.ocg-company{min-width:210px}',
      '.ocg-compact .ocg-node.ocg-company .ocg-t{font-size:12px;padding:7px 14px}',
      /* 打印：去掉交互效果与滚动裁切，保留矢量连线 */
      '@media print{',
      '  .ocg{overflow:visible!important;background:#fff!important}',
      '  .ocg-node,.ocg-node[data-post]{box-shadow:none!important;transform:none!important;break-inside:avoid;page-break-inside:avoid}',
      '  .ocg-node .ocg-t,.ocg-node .ocg-p,.ocg-node .ocg-mg{-webkit-print-color-adjust:exact;print-color-adjust:exact}',
      '  .ocg-tree li,.ocg-tree ul{break-inside:avoid;page-break-inside:avoid}',
      '}'
    ].join('\n');
  }

  /* ---------- 3. 图 HTML ---------- */
  function nodeHtml(n, opts){
    const person = n.person
      ? '<b>'+esc(n.person)+'</b>'+(n.group ? ' 等'+n.group+'人' : '')
      : '<span class="ocg-dim">待配置</span>';
    /* 架构图不显示「强制」角标：强制岗位仅在「岗位信息管理」页以 * 标注，
       避免图面出现与归档无关的标签、也让打印件更干净。 */
    const fl = (n.label && n.label!==n.name) ? '<span class="ocg-fl">'+esc(n.label)+'</span>' : '';
    const mg = n.merged.length
      ? '<div class="ocg-mg"><div class="ocg-m">兼并：'+esc(n.merged.join('、'))+'</div></div>' : '';
    return '<div class="ocg-node'+(n.funcKey==='gm'?' ocg-gm':'')+'"'+
      (opts.interactive ? ' data-post="'+esc(n.id)+'"' : '')+'>'+
      '<div class="ocg-t">'+esc(n.name)+fl+'</div>'+
      '<div class="ocg-p">'+person+'</div>'+mg+'</div>';
  }
  function liHtml(n, opts){
    const ul = n.children.length
      ? '<ul>'+n.children.map(function(c){ return liHtml(c, opts); }).join('')+'</ul>' : '';
    return '<li>'+nodeHtml(n, opts)+ul+'</li>';
  }
  /** 生成架构图 HTML。
   *  opts.interactive  true → 节点带 data-post（org.html 用事件委托绑右键）
   *  opts.compact      true → 紧凑尺寸（PDF 预览 / 打印）
   */
  function html(opts){
    opts = opts || {};
    const t = normalize();
    const roots = t.roots.length
      ? '<ul>'+t.roots.map(function(r){ return liHtml(r, opts); }).join('')+'</ul>' : '';
    return '<div class="ocg'+(opts.compact?' ocg-compact':'')+'">'+
      '<ul class="ocg-tree"><li>'+
        '<div class="ocg-node ocg-company"><div class="ocg-t">'+esc(t.company || '企业名称')+'</div></div>'+
        roots+
      '</li></ul></div>';
  }

  /** 图例 / 统计说明文字（两个页面共用口径） */
  function summary(){
    const t = normalize();
    return '当前启用 '+t.nodes.length+' 个岗位'+
      (t.disabled.length ? '，'+t.disabled.length+' 个已禁用岗位不列入本图' : '')+
      (t.lifted ? '，'+t.lifted+' 个岗位因上级禁用已自动上提至最近的启用上级' : '')+'。';
  }

  /* ---------- 4. 自包含 A4 横向打印文档 ---------- */
  function docCss(){
    return [
      '@page{size:A4 landscape;margin:12mm}',
      'html,body{margin:0;padding:0;background:#F1F5F9;color:#000}',
      'body{font-family:"SimSun","宋体",serif;font-size:14px;line-height:1.8;-webkit-print-color-adjust:exact;print-color-adjust:exact}',
      '.ocg-doc-bar{max-width:1120px;margin:0 auto;padding:12px 18px;background:#fff;border-bottom:1px solid #E2E8F0;display:flex;align-items:center;gap:16px;font-family:system-ui,"Microsoft YaHei",sans-serif;font-size:13px;color:#334155}',
      '.ocg-doc-bar b{font-size:14px;color:#0F172A}',
      '.ocg-doc-bar .tip{flex:1;color:#64748B;font-size:12px}',
      '.ocg-doc-bar button{border:1px solid #2563EB;background:#2563EB;color:#fff;border-radius:7px;padding:7px 14px;font-size:13px;cursor:pointer;white-space:nowrap}',
      '.ocg-doc-paper{max-width:1120px;margin:18px auto 40px;background:#fff;padding:22px 30px 26px;box-shadow:0 2px 14px rgba(0,0,0,.12);box-sizing:border-box}',
      '.ocg-doc-h{text-align:center;font-size:13px;color:#555;border-bottom:2px solid #000;padding-bottom:8px;margin-bottom:14px}',
      '.ocg-doc-f{border-top:1px solid #999;margin-top:22px;padding-top:10px;display:flex;justify-content:space-between;font-size:11px;color:#555}',
      /* 预览页内容在打印文档中改为自适应宽度，不保留屏幕阴影 */
      '.ocg-doc-paper .pdf-page{width:auto!important;min-height:0!important;max-width:100%!important;padding:0!important;box-shadow:none!important;font-family:inherit}',
      '.ocg-doc-paper .pdf-page .ph{text-align:center;font-size:13px;color:#333;border-bottom:2px solid #000;padding-bottom:8px;margin-bottom:6px}',
      '.ocg-doc-paper .pdf-page .ph .org{font-size:17px;font-weight:bold;letter-spacing:1px}',
      '.ocg-doc-paper .pdf-page .ph .meta{font-size:11px;color:#555}',
      '.ocg-doc-paper .pdf-page .ptitle{text-align:center;font-size:20px;font-weight:bold;letter-spacing:3px;margin:18px 0 4px}',
      '.ocg-doc-paper .pdf-page .psub{text-align:center;font-size:12px;color:#333;margin-bottom:16px}',
      '.ocg-doc-paper .pdf-page table{width:100%;border-collapse:collapse;margin:10px 0;font-size:13px}',
      '.ocg-doc-paper .pdf-page table th,.ocg-doc-paper .pdf-page table td{border:1px solid #000;padding:6px 8px;text-align:center}',
      '.ocg-doc-paper .pdf-page table th{background:#f2f2f2;font-weight:bold}',
      '.ocg-doc-paper .pdf-page p{text-indent:2em;margin:5px 0}',
      '.ocg-doc-paper .pdf-page p.ni{text-indent:0}',
      '.ocg-doc-paper .pdf-page .fill{color:#1a4b8c;font-weight:bold}',
      '.ocg-doc-paper .pdf-page .pdf-foot{border-top:1px solid #999;margin-top:24px;padding-top:10px;display:flex;justify-content:space-between;font-size:11px;color:#555}',
      '.ocg-doc-paper .pdf-page .doc-h0{text-align:center;font-size:22px;font-weight:bold;letter-spacing:3px;margin:6px 0 14px}',
      '.ocg-doc-paper .pdf-page .doc-h1{font-size:16px;font-weight:bold;margin:16px 0 6px;padding-left:10px;border-left:4px solid #000}',
      '.ocg-doc-paper .pdf-page .doc-h2{font-size:14.5px;font-weight:bold;margin:12px 0 4px}',
      '.ocg-doc-paper .pdf-page .doc-h3{font-size:13.5px;font-weight:600;margin:9px 0 3px;color:#1E40AF}',
      '.ocg-doc-paper .pdf-page .doc-p{text-indent:2em;margin:4px 0;line-height:1.8}',
      '.ocg-doc-paper .pdf-page .doc-li{margin:3px 0 3px 1.6em;text-indent:-1.2em;line-height:1.7}',
      '.ocg-doc-paper .pdf-page .ocg{margin:10px 0 4px}',
      '@media print{',
      '  html,body{background:#fff}',
      '  .ocg-noprint,.ocg-doc-bar{display:none!important}',
      '  .ocg-doc-paper{max-width:none;margin:0;padding:0;box-shadow:none}',
      '  .ocg-doc-paper .pdf-page{font-size:12.5px}',
      '  .ocg-doc-paper .pdf-page .ptitle{margin:8px 0 4px}',
      '}'
    ].join('\n');
  }

  /** 把任意 HTML 内容包成 A4 横向、自包含（含架构图样式）的可打印文档 */
  function wrapDoc(inner, title, opts){
    opts = opts || {};
    const t = esc(title || '归档文件');
    return '<!DOCTYPE html>\n<html lang="zh-CN"><head><meta charset="UTF-8">'+
      '<meta name="viewport" content="width=device-width, initial-scale=1.0">'+
      '<title>'+t+'</title><style>\n'+docCss()+'\n'+css()+'\n</style></head>'+
      '<body class="ocg-doc">'+
      '<div class="ocg-doc-bar ocg-noprint"><b>'+t+' · A4 横向打印版</b>'+
      '<span class="tip">按 Ctrl / Cmd + P，在「目标打印机」选择「另存为 PDF」即可保存 PDF 文件。</span>'+
      '<button onclick="window.print()">打印 / 另存为 PDF</button></div>'+
      '<div class="ocg-doc-paper">'+
        (opts.header ? '<div class="ocg-doc-h">'+esc(opts.header)+'</div>' : '')+
        inner+
        (opts.footer ? '<div class="ocg-doc-f"><span>'+esc(opts.footer)+'</span><span>第 1 页 / 共 1 页</span></div>' : '')+
      '</div>'+
      (opts.autoPrint
        ? '<script>window.addEventListener("load",function(){setTimeout(function(){try{window.focus();window.print();}catch(e){}},300);});<\/script>'
        : '')+
      '</body></html>';
  }

  /** 架构图打印文档的正文（页面内容与下载文件内容同源） */
  function chartInner(){
    const t = normalize();
    return '<div class="pdf-page">'+
      '<div class="ph"><div class="org">'+esc(t.company)+'</div>'+
      '<div class="meta">安全管理组织架构图　归档日期：'+new Date().toLocaleDateString('zh-CN')+'</div></div>'+
      '<div class="ptitle">安全管理组织架构</div>'+
      '<div class="psub">公司 → 主要负责人 → 各启用岗位（按上级关系组树）</div>'+
      '<p class="ni" style="font-size:12px;color:#555">'+esc(summary())+'</p>'+
      html({ compact:true })+
      '<div class="pdf-foot"><div>编制：安全管理部门　审核：主要负责人</div><div>数据来源：组织架构实时数据</div></div>'+
      '</div>';
  }
  /** 仅架构图的打印文档（org.html「下载 / 打印架构图」用） */
  function chartDoc(opts){
    opts = opts || {};
    const t = normalize();
    const title = (t.company ? t.company + ' · ' : '') + '安全管理组织架构';
    return wrapDoc(chartInner(), title, {
      footer: '安全管理组织架构 · 按当前组织架构实时生成',
      autoPrint: opts.autoPrint !== false
    });
  }

  /* ---------- 5. 对外动作：注入样式 / 打印 / 下载 ---------- */
  function inject(){
    if(document.getElementById('ocg-style')) return;
    const s = document.createElement('style');
    s.id = 'ocg-style';
    s.textContent = css();
    document.head.appendChild(s);
  }
  function openPrint(doc){
    const w = window.open('', '_blank');
    if(!w) return null;
    w.document.open(); w.document.write(doc); w.document.close();
    return w;
  }
  function saveHtml(doc, filename){
    const blob = new Blob(['\ufeff'+doc], { type:'text/html;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = filename;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(function(){ URL.revokeObjectURL(url); }, 4000);
  }

  window.OrgChart = {
    css: css, inject: inject, data: normalize, html: html, summary: summary,
    wrapDoc: wrapDoc, chartDoc: chartDoc, docCss: docCss,
    openPrint: openPrint, saveHtml: saveHtml,
    /** 打印架构图 + 下载 A4 打印版文件（一步到位，便于离线归档） */
    printChart: function(){
      const t = normalize();
      const title = (t.company ? t.company + ' · ' : '') + '安全管理组织架构';
      const foot = { footer:'安全管理组织架构 · 按当前组织架构实时生成' };
      const inner = chartInner();
      saveHtml(wrapDoc(inner, title, Object.assign({}, foot, { autoPrint:false })),
        '安全管理组织架构_A4打印版.html');
      openPrint(wrapDoc(inner, title, Object.assign({}, foot, { autoPrint:true })));
      return true;
    }
  };
})();
