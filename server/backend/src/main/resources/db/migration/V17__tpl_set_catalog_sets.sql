-- V17 范本套体系：范本套内容独立 / 范本→归档项映射 / 企业当前应用套
-- 目标：台账范本管理可切换不同范本套，电子档案柜按「当前应用范本套」生成并归档。

-- 1) 范本增加「对应电子档案柜归档项」字段
ALTER TABLE `tpl_template` ADD COLUMN `catalog_id` BIGINT NOT NULL DEFAULT 0 COMMENT '对应电子档案柜归档项 archive_catalog.id（0=未映射）' AFTER `archive_item`;

-- 2) 补齐档案柜中缺失的细项归档目录（让应急预案/风险清单等范本有明确归处）
INSERT INTO `archive_catalog` (`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`created_by`,`deleted`) VALUES (403601,0,'GENERAL',2,90,'GENERAL_BOX7_S01','应急预案',4036,0,0,0);
INSERT INTO `archive_catalog` (`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`created_by`,`deleted`) VALUES (403602,0,'GENERAL',2,90,'GENERAL_BOX7_S02','应急演练计划',4036,0,0,0);
INSERT INTO `archive_catalog` (`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`created_by`,`deleted`) VALUES (403603,0,'GENERAL',2,90,'GENERAL_BOX7_S03','应急值班表',4036,0,0,0);
INSERT INTO `archive_catalog` (`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`created_by`,`deleted`) VALUES (403604,0,'GENERAL',2,90,'GENERAL_BOX8_D24','全面风险辨识清单',4037,0,0,0);
INSERT INTO `archive_catalog` (`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`created_by`,`deleted`) VALUES (403605,0,'GENERAL',2,90,'GENERAL_BOX8_D25','安全风险辨识分级管控清单',4037,0,0,0);
INSERT INTO `archive_catalog` (`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`created_by`,`deleted`) VALUES (403606,0,'GENERAL',2,90,'GENERAL_BOX3_S03','安全生产会议记录',4020,0,0,0);
INSERT INTO `archive_catalog` (`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`created_by`,`deleted`) VALUES (403607,0,'GENERAL',2,90,'GENERAL_BOX9_D26','一日三检记录',4040,0,0,0);

-- 3) 服务商全局范本套：4001 通用（默认）/ 4002 小微精简 / 4003 危货通用 / 4004 规上扩展
UPDATE `tpl_set` SET `sort_no`=1, `desc`='普货企业通用制度全集（24 项制度 + 责任书/承诺书/告知书/任命书/通知 + 各盒台账范本）' WHERE `id`=4001;
UPDATE `tpl_set` SET `sort_no`=2, `desc`='面向小微普货企业（车辆少、岗位精简）的精简制度包' WHERE `id`=4002;
INSERT INTO `tpl_set` (`id`,`tenant_id`,`code`,`name`,`biz_type`,`region`,`is_default`,`desc`,`status`,`sort_no`,`created_by`,`deleted`) VALUES (4003,0,'TPL_DANGER_BASE','危货通用范本','DANGER','ALL',0,'危货运输企业通用制度包（含押运员/装卸管理员/危货车辆技术管理）',1,3,0,0),(4004,0,'TPL_GENERAL_PLUS','普货规上范本','GENERAL','ALL',0,'面向规模以上普货企业（多车多岗）的扩展制度包',1,4,0,0);

-- 4) 建立全局范本 → 归档项映射
UPDATE `tpl_template` SET `catalog_id`=40099 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD01';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD02';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD03';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD04';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD05';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD06';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD07';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD08';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD09';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD10';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD11';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD12';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD13';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD14';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD15';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD16';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD17';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD18';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD19';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD20';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD21';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD22';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD23';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD24';
UPDATE `tpl_template` SET `catalog_id`=4019 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RULE_INDEX';
UPDATE `tpl_template` SET `catalog_id`=40099 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RESPONSIBILITY';
UPDATE `tpl_template` SET `catalog_id`=4009 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RESP_LETTER_P1';
UPDATE `tpl_template` SET `catalog_id`=4009 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RESP_LETTER_P2';
UPDATE `tpl_template` SET `catalog_id`=4009 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RESP_LETTER_P3';
UPDATE `tpl_template` SET `catalog_id`=4009 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RESP_LETTER_P4';
UPDATE `tpl_template` SET `catalog_id`=4009 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RESP_LETTER_P5';
UPDATE `tpl_template` SET `catalog_id`=4009 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RESP_LETTER';
UPDATE `tpl_template` SET `catalog_id`=4015 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='APPOINT';
UPDATE `tpl_template` SET `catalog_id`=4005 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='LEAD_NOTICE';
UPDATE `tpl_template` SET `catalog_id`=4016 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RESUME_SIX';
UPDATE `tpl_template` SET `catalog_id`=403601 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='EMERGENCY_PLAN';
UPDATE `tpl_template` SET `catalog_id`=4022 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='MEETING_MINUTES';
UPDATE `tpl_template` SET `catalog_id`=4024 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='TRAIN_PLAN';
UPDATE `tpl_template` SET `catalog_id`=4025 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='TRAIN_RECORD';
UPDATE `tpl_template` SET `catalog_id`=4028 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='BUDGET_PLAN';
UPDATE `tpl_template` SET `catalog_id`=4029 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='BUDGET_LEDGER';
UPDATE `tpl_template` SET `catalog_id`=4031 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='MONITOR_LEDGER';
UPDATE `tpl_template` SET `catalog_id`=403602 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='DRILL_PLAN';
UPDATE `tpl_template` SET `catalog_id`=403603 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='DUTY_SCHEDULE';
UPDATE `tpl_template` SET `catalog_id`=50002 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RISK_LIST';
UPDATE `tpl_template` SET `catalog_id`=50002 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RISK_GRADE';
UPDATE `tpl_template` SET `catalog_id`=4038 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='DANGER_CHECK';
UPDATE `tpl_template` SET `catalog_id`=4041 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='THREE_CHECK';
UPDATE `tpl_template` SET `catalog_id`=4046 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ACCIDENT_FAST';
UPDATE `tpl_template` SET `catalog_id`=4044 WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ACCIDENT_SUM';

-- 套 4002：小微精简版
INSERT INTO `tpl_template` (`id`,`tenant_id`,`group_id`,`source_id`,`code`,`name`,`category`,`archive_code`,`box_no`,`archive_item`,`version_label`,`post_scope`,`content`,`need_seal`,`version`,`status`,`created_by`,`deleted`,`catalog_id`) SELECT 8810000000000000 + (`id` - 4200), 0, 4002, `id`, `code`, `name`, `category`, `archive_code`, `box_no`, `archive_item`, '小微精简版', `post_scope`, `content`, `need_seal`, `version`, 1, 0, 0, `catalog_id` FROM `tpl_template` WHERE `tenant_id`=0 AND `group_id`=4001 AND `status`=1 AND `code` IN ('ACCIDENT_FAST','APPOINT','BUDGET_LEDGER','DANGER_CHECK','EMERGENCY_PLAN','LEAD_NOTICE','MEETING_MINUTES','MONITOR_LEDGER','RESPONSIBILITY','RESP_LETTER','RESP_LETTER_P1','RESP_LETTER_P2','RESP_LETTER_P4','RESUME_SIX','RULE_INDEX','THREE_CHECK','TRAIN_PLAN','TRAIN_RECORD','ZD01','ZD02','ZD03','ZD04','ZD05','ZD06','ZD07','ZD08','ZD11','ZD12','ZD13','ZD21','ZD23') AND `id` BETWEEN 4200 AND 4249;

-- 套 4003：危货通用版
INSERT INTO `tpl_template` (`id`,`tenant_id`,`group_id`,`source_id`,`code`,`name`,`category`,`archive_code`,`box_no`,`archive_item`,`version_label`,`post_scope`,`content`,`need_seal`,`version`,`status`,`created_by`,`deleted`,`catalog_id`) SELECT 8811000000000000 + (`id` - 4200), 0, 4003, `id`, `code`, `name`, `category`, `archive_code`, `box_no`, `archive_item`, '危货通用版', `post_scope`, `content`, `need_seal`, `version`, 1, 0, 0, `catalog_id` FROM `tpl_template` WHERE `tenant_id`=0 AND `group_id`=4001 AND `status`=1 AND 1=1 AND `id` BETWEEN 4200 AND 4249;
INSERT INTO `tpl_template` (`id`,`tenant_id`,`group_id`,`source_id`,`code`,`name`,`category`,`archive_code`,`box_no`,`archive_item`,`version_label`,`post_scope`,`content`,`need_seal`,`version`,`status`,`created_by`,`deleted`,`catalog_id`) SELECT 8811000000000900, 0, 4003, `id`, 'DG_RESP_ESCORT', '安全生产责任书（押运员）', `category`, `archive_code`, 1, '安全生产责任书', '危货通用版', '押运员', `content`, `need_seal`, `version`, 1, 0, 0, 4009 FROM `tpl_template` WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RESP_LETTER_P4' LIMIT 1;
INSERT INTO `tpl_template` (`id`,`tenant_id`,`group_id`,`source_id`,`code`,`name`,`category`,`archive_code`,`box_no`,`archive_item`,`version_label`,`post_scope`,`content`,`need_seal`,`version`,`status`,`created_by`,`deleted`,`catalog_id`) SELECT 8811000000000901, 0, 4003, `id`, 'DG_RESP_LOADER', '安全生产责任书（装卸管理员）', `category`, `archive_code`, 1, '安全生产责任书', '危货通用版', '装卸管理员', `content`, `need_seal`, `version`, 1, 0, 0, 4009 FROM `tpl_template` WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RESP_LETTER_P4' LIMIT 1;
INSERT INTO `tpl_template` (`id`,`tenant_id`,`group_id`,`source_id`,`code`,`name`,`category`,`archive_code`,`box_no`,`archive_item`,`version_label`,`post_scope`,`content`,`need_seal`,`version`,`status`,`created_by`,`deleted`,`catalog_id`) SELECT 8811000000000902, 0, 4003, `id`, 'DG_TECH', '危货车辆技术管理制度', `category`, `archive_code`, 2, '制度汇编（含操作规程）', '危货通用版', '', `content`, `need_seal`, `version`, 1, 0, 0, 4019 FROM `tpl_template` WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD05' LIMIT 1;
INSERT INTO `tpl_template` (`id`,`tenant_id`,`group_id`,`source_id`,`code`,`name`,`category`,`archive_code`,`box_no`,`archive_item`,`version_label`,`post_scope`,`content`,`need_seal`,`version`,`status`,`created_by`,`deleted`,`catalog_id`) SELECT 8811000000000903, 0, 4003, `id`, 'DG_NOTICE', '危险货物运输告示与应急处置卡', `category`, `archive_code`, 7, '应急预案', '危货通用版', '', `content`, `need_seal`, `version`, 1, 0, 0, 403601 FROM `tpl_template` WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='EMERGENCY_PLAN' LIMIT 1;

-- 套 4004：规上扩展版
INSERT INTO `tpl_template` (`id`,`tenant_id`,`group_id`,`source_id`,`code`,`name`,`category`,`archive_code`,`box_no`,`archive_item`,`version_label`,`post_scope`,`content`,`need_seal`,`version`,`status`,`created_by`,`deleted`,`catalog_id`) SELECT 8813000000000000 + (`id` - 4200), 0, 4004, `id`, `code`, `name`, `category`, `archive_code`, `box_no`, `archive_item`, '规上扩展版', `post_scope`, `content`, `need_seal`, `version`, 1, 0, 0, `catalog_id` FROM `tpl_template` WHERE `tenant_id`=0 AND `group_id`=4001 AND `status`=1 AND 1=1 AND `id` BETWEEN 4200 AND 4249;
INSERT INTO `tpl_template` (`id`,`tenant_id`,`group_id`,`source_id`,`code`,`name`,`category`,`archive_code`,`box_no`,`archive_item`,`version_label`,`post_scope`,`content`,`need_seal`,`version`,`status`,`created_by`,`deleted`,`catalog_id`) SELECT 8813000000000900, 0, 4004, `id`, 'GS_CSO', '安全总监任命书', `category`, `archive_code`, 1, '人员任命书', '规上扩展版', '', `content`, `need_seal`, `version`, 1, 0, 0, 4015 FROM `tpl_template` WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='APPOINT' LIMIT 1;
INSERT INTO `tpl_template` (`id`,`tenant_id`,`group_id`,`source_id`,`code`,`name`,`category`,`archive_code`,`box_no`,`archive_item`,`version_label`,`post_scope`,`content`,`need_seal`,`version`,`status`,`created_by`,`deleted`,`catalog_id`) SELECT 8813000000000901, 0, 4004, `id`, 'GS_DUAL', '双重预防机制建设方案', `category`, `archive_code`, 8, '全面风险辨识清单', '规上扩展版', '', `content`, `need_seal`, `version`, 1, 0, 0, 403604 FROM `tpl_template` WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='RISK_LIST' LIMIT 1;
INSERT INTO `tpl_template` (`id`,`tenant_id`,`group_id`,`source_id`,`code`,`name`,`category`,`archive_code`,`box_no`,`archive_item`,`version_label`,`post_scope`,`content`,`need_seal`,`version`,`status`,`created_by`,`deleted`,`catalog_id`) SELECT 8813000000000902, 0, 4004, `id`, 'GS_TARGET', '年度安全生产目标分解表', `category`, `archive_code`, 1, '安全生产责任制', '规上扩展版', '', `content`, `need_seal`, `version`, 1, 0, 0, 40099 FROM `tpl_template` WHERE `tenant_id`=0 AND `group_id`=4001 AND `code`='ZD01' LIMIT 1;

-- 6) 演示企业 5001 初始化「当前应用范本套」（以服务商默认套 4001 为基础）
INSERT INTO `ent_tpl_set` (`id`,`tenant_id`,`source_set_id`,`name`,`biz_type`,`region`,`is_current`,`customized`,`files_snapshot`,`applied_at`,`created_by`,`deleted`) VALUES (8809000000000001,5001,4001,'普货通用范本（企业应用）','GENERAL','ALL',1,0,NULL,CURDATE(),0,0);
INSERT INTO `tpl_template` (`id`,`tenant_id`,`group_id`,`source_id`,`code`,`name`,`category`,`archive_code`,`box_no`,`archive_item`,`version_label`,`post_scope`,`content`,`need_seal`,`version`,`status`,`created_by`,`deleted`,`catalog_id`) SELECT 8812000000000000 + (`id` - 4200), 5001, 8809000000000001, `id`, `code`, `name`, `category`, `archive_code`, `box_no`, `archive_item`, `version_label`, `post_scope`, `content`, `need_seal`, `version`, 1, 0, 0, `catalog_id` FROM `tpl_template` WHERE `tenant_id`=0 AND `group_id`=4001 AND `status`=1 AND `id` BETWEEN 4200 AND 4249;

