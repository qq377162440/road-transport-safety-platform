-- V30：《车辆二级维护计划》范本化。
-- 用户 2026-11-11 反馈：「二级维护计划，应该是范本生成的，我再给你发一次」，
-- 并提供了《车辆二级维护计划.docx》作为权威版式来源。
-- 正文按该 docx 逐段复刻（公司名 / 年度 / 实施日期换成变量），落款自动盖公司章。
-- 归档项「二级维护计划」位于盒一（V5 catalog GENERAL_BOX1_S05 / DANGER_BOX1_S05，年度更新）。

DELETE FROM `tpl_template` WHERE `id` = 4260;

INSERT INTO `tpl_template`
  (`id`,`tenant_id`,`group_id`,`source_id`,`code`,`name`,`category`,`archive_code`,`archive_item`,`box_no`,`post_scope`,`content`,`need_seal`,`version`,`version_label`,`status`,`created_by`)
VALUES
(4260,0,4001,0,'L2_MAINT_PLAN','车辆二级维护计划','范本','BOX1','二级维护计划',1,'',
'<div class="doc-head">{{company_name}}</div>\n<div class="doc-h0">关于公司车辆二级维护计划通知</div>\n<div class="doc-h1">一、工作要求</div>\n<div class="doc-p">1.按照国家相关规定开展车辆二级维护工作，公司负责制定{{year}}年度车辆二级维护计划。</div>\n<div class="doc-p">2.车辆二级维护由驾驶员按期到具有车辆维修资质的修理厂进行维护。由修理厂按照国家相关规定维护项目进行，维护完成后将车辆维护相关凭证交公司负责车辆档案管理的人员进行整理归档。</div>\n<div class="doc-h1">二、车辆维护周期</div>\n<div class="doc-p">1.营运车辆维护周期按车辆行驶间隔里程或时间进行确定，依据公司车辆实际运营情况、车辆保养手册及相关标准参考，公司车辆二级维护计划按照车辆一年一次制定{{year}}年度二级维护计划，具体执行以实际车况为准。请按期督促驾驶员按时进厂维护，做好车辆管理工作，严格落实车辆二级维护计划。</div>\n<div class="doc-h1">三、实施时间</div>\n<div class="doc-p">1.本通知下发后，自{{start_date}}起执行实施。</div>\n<div class="doc-sign"><div>{{company_name}}</div><div>{{sign_date}}</div></div>',
1,1,'v1.0 内置（用户 2026-11 提供版式）',1,0);
