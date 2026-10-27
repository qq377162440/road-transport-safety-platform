#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量生成 PC 端页面（v4 规范：SVG 图标 + 高对比 + 统一设计系统）"""
import os

OUT = "/workspace/prototype/pc"
ASSET = "../assets"

def ico(name, size=None):
    s = f' style="font-size:{size}px"' if size else ''
    return f'<span class="ico"{s} data-icon="{name}"></span>'

def tag(text, kind):
    return f'<span class="tag tag-{kind}">{text}</span>'

def head(title, desc, actions=""):
    return f'''<div class="pc-header"><div><h1>{title}</h1><div class="desc">{desc}</div></div><div class="actions">{actions}</div></div>'''

def page(title, desc, body, extra_style=""):
    return f'''<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="UTF-8"><title>{title}</title>
<link rel="stylesheet" href="{ASSET}/common.css"><link rel="stylesheet" href="{ASSET}/pc.css">
<style>{extra_style}</style></head><body>
<div class="pc-page">
{body}
</div>
<script src="{ASSET}/icons.js"></script></body></html>'''

def filter_bar(items, actions):
    its = "".join(items)
    return f'<div class="filter-bar">{its}<div class="f-actions">{actions}</div></div>'

def fitem(label, control):
    return f'<div class="f-item"><label>{label}</label>{control}</div>'

def inp(ph):
    return f'<input type="text" placeholder="{ph}">'

def sel(opts):
    o = "".join(f'<option>{x}</option>' for x in opts)
    return f'<select>{o}</select>'

def table(headers, rows):
    th = "".join(f"<th>{h}</th>" for h in headers)
    trs = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in r) + "</tr>" for r in rows)
    return f'''<div class="card" style="padding:0;overflow:hidden"><table class="table"><thead><tr>{th}</tr></thead><tbody>{trs}</tbody></table>
<div class="table-foot"><span>共 {len(rows) if isinstance(rows,list) else '—'} 条记录</span>
<div class="pagination"><button>&lt;</button><button class="active">1</button><button>2</button><button>3</button><button>…</button><button>&gt;</button></div></div></div>'''

def opt_btn(primary="", outline=""):
    p = f'<a class="btn btn-primary btn-sm">{primary}</a> ' if primary else ""
    o = f'<a class="btn btn-outline btn-sm">{outline}</a>' if outline else ""
    return p + o

# ================= 页面定义 =================

# 1. 企业信息管理
def company():
    tb = table(['序号','名称','创建日期','更新日期','说明','操作'],
        [['1','佛山市南海区小塘塘安运输服务有限公司','2024-03-01','2026-10-20','道路货物运输（货运）', opt_btn('查看','编辑')],
         ['2','（示例）塘安物流分公司','2025-01-15','2026-09-30','危险货物运输（危运）', opt_btn('查看','编辑')]])
    return page('企业信息管理','企业主体信息与资质证照登记',
        head('企业信息管理','企业主体信息与资质证照登记', f'<button class="btn btn-primary">{ico("plus")} 新增企业</button>') +
        filter_bar([fitem('企业名称', inp('请输入企业名称')), fitem('状态', sel(['全部','正常','停用']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 2. 安全管理架构
def org():
    tb = table(['机构名称','组长','副组长','职责','操作'],
        [['安全生产领导小组','张伟（法人）','李强（安全经理）','全面领导企业安全生产工作', opt_btn('查看','编辑')],
         ['安全管理部门','李强','王芳','日常安全监督检查、隐患整改', opt_btn('查看','编辑')],
         ['驾驶员管理组','王芳','陈刚','驾驶员考核与培训管理', opt_btn('查看','编辑')]])
    return page('安全管理架构','企业安全管理组织架构与职责分工',
        head('安全管理架构','安全管理组织架构与职责分工', f'<button class="btn btn-primary">{ico("plus")} 新增机构</button>') + tb)

# 3. 人员基础信息
def person():
    tb = table(['人员姓名','政治面貌','性别','部门','岗位','手机号','创建时间','操作'],
        [['张伟','中共党员','男','管理层','法人/负责人','138****0001','2024-03-01', opt_btn('档案','编辑')],
         ['李强','群众','男','安全部','安全员','138****0002','2024-03-05', opt_btn('档案','编辑')],
         ['王芳','群众','女','安全部','安全员','138****0003','2024-03-05', opt_btn('档案','编辑')],
         ['陈刚','群众','男','驾驶部','驾驶员','138****0004','2024-04-10', opt_btn('档案','编辑')],
         ['刘洋','群众','男','驾驶部','驾驶员','138****0005','2024-04-10', opt_btn('档案','编辑')]])
    return page('人员基础信息','从业人员档案（一档一档核心）',
        head('人员基础信息','从业人员基础档案管理', f'<button class="btn btn-primary">{ico("plus")} 新增人员</button><button class="btn btn-outline">{ico("download")} 导出</button>') +
        filter_bar([fitem('姓名', inp('请输入姓名')), fitem('部门', sel(['全部','驾驶部','安全部','维修部'])),
                    fitem('岗位', sel(['全部','驾驶员','安全员','押运员'])), fitem('政治面貌', sel(['全部','中共党员','群众']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 4. 车辆基础信息
def vehicle():
    tb = table(['车牌号','营运类型','车辆类型','车/挂','车辆编号','操作'],
        [['粤E·12345','货运','牵引车','车','VEH-0001', opt_btn('档案','编辑')],
         ['粤E·67890','货运','挂车','挂','VEH-0002', opt_btn('档案','编辑')],
         ['粤E·54321','客运','货车','车','VEH-0003', opt_btn('档案','编辑')]])
    return page('车辆基础信息','车辆基础信息（一车一档核心）',
        head('车辆基础信息','车辆基础信息（一车一档核心）', f'<button class="btn btn-primary">{ico("plus")} 新增</button><button class="btn btn-outline">{ico("download")} 导出</button>') +
        filter_bar([fitem('车牌号', inp('请输入车牌号')), fitem('营运类型', sel(['全部','货运','客运','危险品'])),
                    fitem('车辆类型', sel(['全部','牵引车','挂车','货车']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb, 'max-width:1400px')

# 5. 企业分组
def tags():
    tb = table(['序号','分组名称','操作'],
        [['1','第一车队', opt_btn('编辑','删除')],
         ['2','第二车队', opt_btn('编辑','删除')],
         ['3','危货运输组', opt_btn('编辑','删除')]])
    return page('企业分组','企业内部机构/车队分组管理',
        head('企业分组','按车队/线路对企业人员车辆进行分组', f'<button class="btn btn-primary">{ico("plus")} 新增分组</button>') +
        filter_bar([fitem('分组名称', inp('请输入'))], f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button>') + tb)

# 6. 企业用户管理
def users():
    tb = table(['用户名','姓名','企业分组','角色','状态','操作'],
        [['admin','张伟','管理组','企业管理员', tag('启用','green'), opt_btn('编辑','重置密码')],
         ['liqiang','李强','安全部','安全员', tag('启用','green'), opt_btn('编辑','重置密码')],
         ['chengang','陈刚','第一车队','驾驶员', tag('禁用','gray'), opt_btn('编辑','重置密码')]])
    return page('企业用户管理','平台登录账号与角色权限管理',
        head('企业用户管理','账号、角色、状态管理', f'<button class="btn btn-primary">{ico("plus")} 新增用户</button>') +
        filter_bar([fitem('用户名', inp('请输入')), fitem('状态', sel(['全部','启用','禁用']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 7. 法律法规清单
def law():
    tb = table(['日期','文档名称','文档状态','操作'],
        [['2026-01-01','《中华人民共和国安全生产法》', tag('使用中','green'), opt_btn('查看','编辑')],
         ['2026-01-01','《道路运输车辆动态监督管理办法》', tag('使用中','green'), opt_btn('查看','编辑')],
         ['2024-06-01','《危险货物道路运输安全管理办法》', tag('使用中','green'), opt_btn('查看','编辑')],
         ['2023-05-01','《道路旅客运输及客运站管理规定》(旧)', tag('已过期','gray'), opt_btn('查看','编辑')]])
    return page('法律法规清单','国家及行业安全法规台账',
        head('法律法规清单','安全生产相关法律法规台账', f'<button class="btn btn-primary">{ico("plus")} 新增法规</button>') +
        filter_bar([fitem('文档状态', sel(['全部','使用中','已过期'])), fitem('关键字', inp('请输入关键字'))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 8. 制度范本管理
def doc_sample():
    tb = table(['范本名称','业务领域','签字盖章项','是否需要修订','更新时间','操作'],
        [['安全生产责任制（范本）','货运','法人签字+盖章','是','2026-09-01', opt_btn('查看','编辑')],
         ['驾驶员安全操作规程（范本）','货运','无','否','2026-08-15', opt_btn('查看','编辑')],
         ['危险品运输管理制度（范本）','危运','法人签字+盖章','是','2026-07-20', opt_btn('查看','编辑')]])
    return page('制度范本管理','企业安全制度范本库',
        head('制度范本管理','安全管理制度/规程范本', f'<button class="btn btn-primary">{ico("plus")} 新增范本</button>') +
        filter_bar([fitem('关键字', inp('请输入')), fitem('业务领域', sel(['全部','货运','客运','危运'])),
                    fitem('范本类型', sel(['全部','安全生产责任制','管理制度','操作规程']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 9. 制度发布管理
def doc_publish():
    tb = table(['范本名称','业务领域','签字盖章项','发布时间','需签署','待签字','待盖章','已完成','操作'],
        [['安全生产责任制','货运','法人签字+盖章','2026-09-05','35','5','3','27', opt_btn('详情')],
         ['驾驶员安全操作规程','货运','无','2026-08-20','35','0','0','35', opt_btn('详情')],
         ['危险品运输管理制度','危运','法人签字+盖章','2026-07-25','12','2','1','9', opt_btn('详情')]])
    return page('制度发布管理','制度发布与签署进度跟踪',
        head('制度发布管理','制度发布、签署/盖章进度跟踪', f'<button class="btn btn-primary">{ico("plus")} 发布制度</button>') + tb)

# 10. 制度评审修订申请
def doc_review():
    tb = table(['评审/修订名称','申请人','评审/修订时间','状态','操作'],
        [['安全生产责任制（2026修订）','李强','2026-09-10', tag('已完成','green'), opt_btn('查看')],
         ['危险品运输管理制度修订','王芳','2026-10-05', tag('评审中','yellow'), opt_btn('查看')],
         ['车辆维护制度新增','陈刚','2026-10-18', tag('待评审','blue'), opt_btn('查看')]])
    return page('制度评审修订申请','制度评审与修订流程管理',
        head('制度评审修订申请','制度评审/修订申请与审批', f'<button class="btn btn-primary">{ico("plus")} 发起申请</button>') + tb)

# 11. 一日三检
def check():
    tb = table(['车牌号','驾驶员/押运员','状态','出车检查','行车检查','收车检查','里程','操作'],
        [['粤E·12345','陈刚', tag('已完成','green'), tag('合格','green'), tag('合格','green'), tag('合格','green'),'256 km', opt_btn('详情')],
         ['粤E·67890','刘洋', tag('进行中','blue'), tag('合格','green'), '—', '—','180 km', opt_btn('详情')],
         ['粤E·54321','赵敏', tag('未开始','gray'), '—', '—', '—','—', opt_btn('详情')]])
    return page('一日三检','车辆出车前/行车中/收车后安全检查',
        head('一日三检','车辆一日三检记录', f'<button class="btn btn-primary">{ico("plus")} 新增检查</button>') +
        filter_bar([fitem('日检日期', inp('2026-10-27')), fitem('车牌号', inp('请输入车牌号')), fitem('人员', inp('请输入人员'))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 12. 隐患排查治理
def danger():
    tb = table(['检查名称','检查时间','检查类型','检查项目','整改项','状态','操作'],
        [['季度安全大检查','2026-09-28','专项检查','18','3', tag('整改中','yellow'), opt_btn('详情','处理')],
         ['日常隐患排查','2026-10-20','日常检查','10','1', tag('待整改','red'), opt_btn('详情','处理')],
         ['冬季安全检查','2026-10-15','季节检查','15','0', tag('已闭环','green'), opt_btn('详情')]])
    return page('隐患排查治理','安全隐患排查与整改闭环',
        head('隐患排查治理','隐患排査、登记、整改、闭环', f'<button class="btn btn-primary">{ico("plus")} 发起检查</button>') +
        filter_bar([fitem('检查时间', inp('开始日期 - 结束日期')), fitem('检查类型', sel(['全部','日常检查','专项检查','季节检查']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 13. 安全会议管理
def meeting():
    tb = table(['会议名称','会议类型','召开时间','主持人','参会人数','状态','操作'],
        [['2026年10月安全例会','安全例会','2026-10-25 09:00','李强','32', tag('已结束','gray'), opt_btn('详情','纪要')],
         ['冬季行车安全专题会','专题会议','2026-10-28 14:00','张伟','40', tag('未开始','blue'), opt_btn('详情')],
         ['消防应急演练','应急演练','2026-11-05 10:00','王芳','50', tag('未开始','blue'), opt_btn('详情')]])
    return page('安全会议管理','安全生产会议与培训记录',
        head('安全会议管理','会议安排、签到、纪要管理', f'<button class="btn btn-primary">{ico("plus")} 新增会议</button>') +
        filter_bar([fitem('会议名称', inp('请输入')), fitem('会议类型', sel(['全部','安全例会','专题会议','应急演练'])),
                    fitem('会议状态', sel(['全部','未开始','进行中','已结束']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 14. 安全投入管理
def budget():
    tb = table(['序号','预算周期','上期营收(万)','提取比例(%)','上期结余(万)','已使用(万)','剩余(万)','签字','操作'],
        [['1','2026年第三季度','1200','2.5','30','18','12', tag('已签','green'), opt_btn('详情')],
         ['2','2026年第二季度','1100','2.5','27','22','5', tag('已签','green'), opt_btn('详情')],
         ['3','2026年第一季度','1000','2.5','25','25','0', tag('已签','green'), opt_btn('详情')]])
    return page('安全投入管理','安全生产费用提取与使用台账',
        head('安全投入管理','安全费用提取、使用、结余', f'<button class="btn btn-primary">{ico("plus")} 新增预算</button>') +
        filter_bar([fitem('预算周期', inp('请选择'))], f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button>') + tb)

# 15. 风险辨识管控
def risk():
    tb = table(['序号','名称','年份','状态','识别人','更新时间','操作'],
        [['1','危险品运输风险辨识','2026', tag('已发布','green'),'李强','2026-06-30', opt_btn('查看','编辑')],
         ['2','货运车辆道路风险辨识','2026', tag('已发布','green'),'王芳','2026-06-30', opt_btn('查看','编辑')],
         ['3','客运线路风险辨识','2025', tag('已归档','gray'),'李强','2025-12-31', opt_btn('查看')]])
    return page('风险辨识管控','安全风险辨识、评估与分级管控',
        head('风险辨识管控','风险辨识、评估、分级、管控', f'<button class="btn btn-primary">{ico("plus")} 新增辨识</button>') +
        filter_bar([fitem('年份', sel(['2026','2025'])), fitem('名称', inp('请输入'))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 16. 车辆维修保养
def repair():
    tb = table(['序号','车牌号','维修日期','维修厂','维修费用(元)','当前公里数','维修类别','下次一级维护','操作'],
        [['1','粤E·12345','2026-10-05','塘安汽修厂','3500','182000','二级维护','2027-01-05', opt_btn('详情')],
         ['2','粤E·67890','2026-09-20','塘安汽修厂','1200','156000','一级维护','2026-12-20', opt_btn('详情')],
         ['3','粤E·54321','2026-08-15','塘安汽修厂','8000','98000','大修','2026-11-15', opt_btn('详情')]])
    return page('车辆维修保养','车辆维修与保养记录台账',
        head('车辆维修保养','维修、保养、里程记录', f'<button class="btn btn-primary">{ico("plus")} 新增记录</button>') +
        filter_bar([fitem('车牌号', inp('请输入')), fitem('维修类别', sel(['全部','一级维护','二级维护','大修']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 17. 考核奖惩规则
def reward_rule():
    tb = table(['序号','被考核对象(岗位)','日常考核项','责任制考核规范','操作'],
        [['1','驾驶员','出勤、违章、事故、三检','安全驾驶奖惩办法', opt_btn('查看','编辑')],
         ['2','安全员','隐患整改率、检查频次','安全管理考核办法', opt_btn('查看','编辑')],
         ['3','押运员','押运规范、应急处置','押运员考核办法', opt_btn('查看','编辑')]])
    return page('考核奖惩规则','岗位安全考核与奖惩规则',
        head('考核奖惩规则','岗位考核项与奖惩规范', f'<button class="btn btn-primary">{ico("plus")} 新增规则</button>') + tb)

# 18. 诚信考核
def credit():
    tb = table(['姓名','手机号','岗位','修改时间','操作'],
        [['陈刚','138****0004','驾驶员','2026-10-20', opt_btn('考核','记录')],
         ['刘洋','138****0005','驾驶员','2026-10-20', opt_btn('考核','记录')],
         ['赵敏','138****0006','押运员','2026-10-18', opt_btn('考核','记录')]])
    return page('诚信考核','从业人员诚信档案与考核',
        head('诚信考核','从业人员诚信考核记录', f'<button class="btn btn-primary">{ico("plus")} 新增考核</button>') +
        filter_bar([fitem('姓名', inp('请输入姓名')), fitem('电话', inp('请输入手机号')), fitem('岗位', sel(['全部','驾驶员','押运员']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 19. 岗中安全教育
def training():
    tb = table(['计划名称','类型','学习形式','开始时间','状态','操作'],
        [['冬季行车安全培训','安全教育','线上学习','2026-10-25 09:00', tag('进行中','blue'), opt_btn('详情')],
         ['危险驾驶行为靶向教育','靶向教育','线上学习','2026-10-28 10:00', tag('未开始','gray'), opt_btn('详情')],
         ['新员工岗前培训','岗前培训','线下集中','2026-11-01 09:00', tag('未开始','gray'), opt_btn('详情')]])
    return page('岗中安全教育','从业人员安全教育培训管理',
        head('岗中安全教育','培训计划、课程、学习记录', f'<button class="btn btn-primary">{ico("plus")} 新建培训</button>') +
        filter_bar([fitem('计划名称', inp('请输入')), fitem('类型', sel(['全部','安全教育','岗前培训','安全例会','靶向教育']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 20. 学习情况统计
def train_stat():
    tb = table(['用户姓名','计划名称','学习时长','学习状态','培训证明','操作'],
        [['陈刚','冬季行车安全培训','45分钟', tag('已完成','green'), tag('已生成','blue'), opt_btn('证明')],
         ['刘洋','冬季行车安全培训','20分钟', tag('学习中','yellow'),'—', opt_btn('证明')],
         ['赵敏','危险驾驶行为靶向教育','—', tag('未开始','gray'),'—', opt_btn('证明')]])
    return page('学习情况统计','培训学习进度统计',
        head('学习情况统计','人员学习进度与证明', f'<button class="btn btn-outline">{ico("download")} 导出</button>') +
        filter_bar([fitem('计划名称', inp('请输入')), fitem('学习状态', sel(['全部','学习中','已完成','未开始']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 21. 应急预案管理
def emergency():
    tb = table(['计划名称','计划编号','版本号','发布日期','实施日期','状态','操作'],
        [['道路运输事故应急预案','YJ-2026-001','V2.0','2026-06-01','2026-06-01', tag('生效','green'), opt_btn('查看','编辑')],
         ['危险品泄漏应急预案','YJ-2026-002','V1.0','2026-06-01','2026-06-01', tag('生效','green'), opt_btn('查看','编辑')],
         ['消防应急预案','YJ-2025-003','V1.0','2025-12-01','2026-01-01', tag('生效','green'), opt_btn('查看')]])
    return page('应急预案管理','企业应急预案编制与备案',
        head('应急预案管理','应急预案编制、版本、备案', f'<button class="btn btn-primary">{ico("plus")} 新增预案</button>') + tb)

# 22. 应急物资管理
def emergency_mat():
    tb = table(['物资名称','物资数量','储存维护标准','存放位置','操作'],
        [['灭火器','20 个','每月检查压力','仓库A区', opt_btn('编辑')],
         ['急救箱','5 套','每季度检查有效期','办公室', opt_btn('编辑')],
         ['反光锥桶','50 个','随时可用','车辆随车', opt_btn('编辑')],
         ['应急照明灯','10 个','每月充电','仓库B区', opt_btn('编辑')]])
    return page('应急物资管理','应急物资台账与维护',
        head('应急物资管理','应急物资配备与维护', f'<button class="btn btn-primary">{ico("plus")} 新增物资</button>') + tb)

# 23. 事故上报
def accident():
    tb = table(['序号','事故名称','事发时间','上报人','事故类型','事故等级','处理进度','操作'],
        [['1','粤E·12345 追尾事故','2026-10-18 14:30','李强','追尾','一般', tag('处理中','yellow'), opt_btn('详情')],
         ['2','粤E·54321 侧翻事故','2026-09-02 08:10','王芳','侧翻','较大', tag('已结案','green'), opt_btn('详情')]])
    return page('事故上报','道路运输事故上报与处理',
        head('事故上报','事故登记、上报、处理进度', f'<button class="btn btn-primary">{ico("plus")} 事故上报</button>') +
        filter_bar([fitem('事发时间', inp('开始 - 结束')), fitem('事故类型', sel(['全部','碰撞','追尾','侧翻','其他'])),
                    fitem('事故等级', sel(['全部','一般','较大','重大']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 24. 交通违法
def illegal():
    tb = table(['序号','违法人','违法时间','违法地点','违法行为','扣分','操作'],
        [['1','陈刚','2026-10-12 09:20','佛山市南海区桂丹路','超速行驶','3分', opt_btn('详情')],
         ['2','刘洋','2026-09-28 16:45','佛山市顺德区容桂大道','未按规定让行','3分', opt_btn('详情')]])
    return page('交通违法','驾驶员交通违法记录',
        head('交通违法','交通违法记录与处理', f'<button class="btn btn-primary">{ico("plus")} 新增记录</button>') +
        filter_bar([fitem('违法人', inp('请输入')), fitem('违法时间', inp('请选择日期'))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 25. 通知公告
def notice():
    tb = table(['序号','公告标题','文档类型','状态','创建时间','发布时间','操作'],
        [['1','关于开展冬季行车安全专项检查的通知','通知', tag('已发布','green'),'2026-10-20','2026-10-21', opt_btn('查看','编辑')],
         ['2','关于安全投入费用使用的公告','公告', tag('已发布','green'),'2026-10-15','2026-10-16', opt_btn('查看','编辑')],
         ['3','新版安全生产责任制（草稿）','制度', tag('草稿','gray'),'2026-10-22','—', opt_btn('编辑')]])
    return page('通知公告','企业通知公告发布管理',
        head('通知公告','通知、公告、制度发布', f'<button class="btn btn-primary">{ico("plus")} 发布公告</button>') +
        filter_bar([fitem('关键字', inp('请输入')), fitem('文档类型', sel(['全部','通知','公告','制度'])), fitem('状态', sel(['全部','草稿','已发布']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

# 26. 企业档案柜
def archive():
    # 档案柜是卡片式布局
    cards = ""
    items = [
        ('truck','车辆档案','128 份','一车一档'),
        ('user','人员档案','86 份','一人一档'),
        ('fileText','制度档案','42 份','制度/规程'),
        ('shield','隐患台账','156 条','排查整改'),
        ('clipboard','三检记录','2048 条','一日三检'),
        ('award','考核档案','64 份','奖惩考核'),
    ]
    for ic, name, cnt, desc in items:
        cards += f'''<div class="arch-card"><div class="a-ico">{ico(ic, 30)}</div>
        <div><div class="a-name">{name}</div><div class="a-desc">{cnt} · {desc}</div></div>
        <a class="btn btn-outline btn-sm">{ico("folder")} 打开</a></div>'''
    body = head('企业档案柜','企业安全生产档案集中管理', f'<button class="btn btn-primary">{ico("plus")} 新建档案</button>') + \
        f'<div class="arch-grid">{cards}</div>'
    return page('企业档案柜','企业安全生产档案集中管理', body,
        '.arch-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}.arch-card{background:var(--surface);border-radius:var(--r-lg);padding:20px;box-shadow:var(--shadow-sm);border:1px solid var(--border);display:flex;align-items:center;gap:14px}.arch-card .a-ico{width:52px;height:52px;border-radius:12px;background:var(--brand-100);color:var(--brand-500);display:flex;align-items:center;justify-content:center}.arch-card .a-name{font-size:16px;font-weight:700}.arch-card .a-desc{font-size:13px;color:var(--text-3);margin-top:2px}.arch-card .btn{margin-left:auto}')

# 27. 动态监控（模拟）
def monitor():
    tb = table(['车辆','事件类型','预警等级','发生时间','处理状态','操作'],
        [['粤E·12345','超速预警', tag('严重','red'),'2026-10-27 09:35:12', tag('待处理','red'), opt_btn('处理')],
         ['粤E·67890','疲劳驾驶', tag('一般','yellow'),'2026-10-27 08:20:44', tag('已提醒','blue'), opt_btn('处理')],
         ['粤E·54321','偏离路线', tag('提示','blue'),'2026-10-27 07:58:03', tag('已处理','green'), opt_btn('详情')]])
    # 顶部加指标卡
    stats = '''<div class="stat-grid">
    <div class="stat-card"><div class="ic" style="background:var(--brand-100);color:var(--brand-500)">{ico("truck",24)}</div><div><div class="val">86</div><div class="lbl">在线车辆</div></div></div>
    <div class="stat-card"><div class="ic" style="background:var(--warn-bg);color:var(--warn)">{ico("alertTriangle",24)}</div><div><div class="val">3</div><div class="lbl">今日预警</div></div></div>
    <div class="stat-card"><div class="ic" style="background:var(--danger-bg);color:var(--danger)">{ico("alert",24)}</div><div><div class="val">1</div><div class="lbl">待处理</div></div></div>
    <div class="stat-card"><div class="ic" style="background:var(--ok-bg);color:var(--ok)">{ico("checkCircle",24)}</div><div><div class="val">12</div><div class="lbl">今日已处置</div></div></div>
    </div>'''
    return page('动态监控','车辆动态监控（模拟数据）',
        head('动态监控','车辆动态监控（模拟数据演示）', f'<button class="btn btn-outline">{ico("setting")} 监控设置</button>') +
        stats +
        filter_bar([fitem('车辆', inp('请输入车牌')), fitem('预警等级', sel(['全部','严重','一般','提示']))],
            f'<button class="btn btn-primary btn-sm">{ico("search")} 查询</button><button class="btn btn-outline btn-sm">重置</button>') + tb)

pages = {
    'company': company, 'org': org, 'person': person, 'vehicle': vehicle,
    'tags': tags, 'users': users, 'law': law, 'doc_sample': doc_sample,
    'doc_publish': doc_publish, 'doc_review': doc_review, 'check': check,
    'danger': danger, 'meeting': meeting, 'budget': budget, 'risk': risk,
    'repair': repair, 'reward_rule': reward_rule, 'credit': credit,
    'training': training, 'train_stat': train_stat, 'emergency': emergency,
    'emergency_mat': emergency_mat, 'accident': accident, 'illegal': illegal,
    'notice': notice, 'archive': archive, 'monitor': monitor,
}

for name, fn in pages.items():
    path = os.path.join(OUT, f'{name}.html')
    with open(path, 'w', encoding='utf-8') as f:
        f.write(fn())
    print(f'OK  {name}.html')
