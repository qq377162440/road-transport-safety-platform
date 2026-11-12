-- 档案目录对齐（2026-11-12 危货权威标签核对 + A/B/C 分类裁定）：
--  1) 修复 DANGER 分支错位：V5 种子复制普货数据时 parent_id 未随盒号顺延，
--     导致 盒七停车场空盒 / 盒八应急装了隐患项 / 盒九风险装了三检项 / 盒十装了事故项。
--  2) 按企业权威目录删除：设备故障异常台账、在线率台账、月报台账（普货+危货），
--     以及危险货物运输 6 项（电子运单/装卸查验/危险品台账/押运员在岗/800公里/安全卡）。
--  3) 盒一「复工复产方案」→「各类方案」；「岗位风险告知卡」名称对齐前端权威目录。
--  4) DANGER 盒七停车场补 3 项、盒八应急管理补 11 项；GENERAL 盒七应急补 6 项并重排。

-- ========== 1) DANGER 错位修复 ==========
-- 隐患 2 项从 盒八应急(4085) 挪回 盒九风险(4088)，sort 按权威目录排 7/8
UPDATE `archive_catalog` SET `parent_id` = 4088, `sort_no` = 7 WHERE `id` = 4086;
UPDATE `archive_catalog` SET `parent_id` = 4088, `sort_no` = 8 WHERE `id` = 4087;
-- 三检 2 项从 盒九风险(4088) 挪回 盒十一日三检(4091)
UPDATE `archive_catalog` SET `parent_id` = 4091 WHERE `id` = 4089;
UPDATE `archive_catalog` SET `parent_id` = 4091 WHERE `id` = 4090;
-- 事故 4 项错位副本（挂盒十）删除；盒十一事故正确项（4097~4100）保留
DELETE FROM `archive_catalog` WHERE `id` IN (4092, 4093, 4094, 4095);
-- 盒九风险 sort 重排（对齐前端权威目录顺序：安全评估→手册→清单→告知卡→无重大风险→声明→隐患）
UPDATE `archive_catalog` SET `sort_no` = 1 WHERE `id` = 50009; -- 安全评估（危货）
UPDATE `archive_catalog` SET `sort_no` = 2 WHERE `id` = 50005; -- 风险辨识手册
UPDATE `archive_catalog` SET `sort_no` = 3 WHERE `id` = 50006; -- 风险辨识清单
UPDATE `archive_catalog` SET `sort_no` = 4 WHERE `id` = 50007; -- 岗位风险告知卡（下改名）
UPDATE `archive_catalog` SET `sort_no` = 5 WHERE `id` = 50008; -- 无重大风险的说明
UPDATE `archive_catalog` SET `sort_no` = 6 WHERE `id` = 50010; -- 无重大事故隐患的声明（危货）

-- ========== 2) 删除项（软删，均无 tpl_template.catalog_id 引用） ==========
UPDATE `archive_catalog` SET `deleted` = 1 WHERE `id` IN
  (4032, 4033, 4034,       -- GENERAL 盒六：设备故障/在线率/月报
   4080, 4081, 4082,       -- DANGER 盒六：同上三项
   50011, 50012, 50013,    -- DANGER 盒六：电子运单/装卸查验/危险品台账
   50014, 50015, 50016);   -- DANGER 盒六：押运员在岗/800公里/安全卡

-- ========== 3) 名称对齐 ==========
UPDATE `archive_catalog` SET `name` = '各类方案' WHERE `name` = '复工复产方案';
UPDATE `archive_catalog` SET `name` = '风险告知卡（每个岗位一份）' WHERE `name` = '岗位风险告知卡';

-- ========== 4) DANGER 盒七 停车场管理(4084) 补 3 项 ==========
INSERT IGNORE INTO `archive_catalog`
  (`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`renew_freq`) VALUES
  (4110, 0, 'DANGER', 2, 1, 'DANGER_BOX7_S01', '产权证明', 4084, 0, 'LONG'),
  (4111, 0, 'DANGER', 2, 2, 'DANGER_BOX7_S02', '租赁合同（到期预警）', 4084, 0, 'CERT'),
  (4112, 0, 'DANGER', 2, 3, 'DANGER_BOX7_S03', '平面图及照片', 4084, 0, 'ONCHANGE');

-- ========== 5) DANGER 盒八 应急管理(4085) 补 11 项 ==========
INSERT IGNORE INTO `archive_catalog`
  (`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`renew_freq`) VALUES
  (4113, 0, 'DANGER', 2, 1,  'DANGER_BOX8_S01', '应急预案备案回执', 4085, 0, 'CERT'),
  (4114, 0, 'DANGER', 2, 2,  'DANGER_BOX8_S02', '应急预案', 4085, 0, 'LONG'),
  (4115, 0, 'DANGER', 2, 3,  'DANGER_BOX8_S03', '风险评估和应急资源调查', 4085, 0, 'LONG'),
  (4116, 0, 'DANGER', 2, 4,  'DANGER_BOX8_S04', '应急预案评审或论证', 4085, 0, 'ONCHANGE'),
  (4117, 0, 'DANGER', 2, 5,  'DANGER_BOX8_S05', '应急值班制度', 4085, 0, 'ONCHANGE'),
  (4118, 0, 'DANGER', 2, 6,  'DANGER_BOX8_S06', '应急队伍架构', 4085, 0, 'ONCHANGE'),
  (4119, 0, 'DANGER', 2, 7,  'DANGER_BOX8_S07', '应急值班表', 4085, 0, 'MONTHLY'),
  (4120, 0, 'DANGER', 2, 8,  'DANGER_BOX8_S08', '应急培训（培训内容、考核、照片）', 4085, 0, 'YEARLY'),
  (4121, 0, 'DANGER', 2, 9,  'DANGER_BOX8_S09', '应急救援器材配备', 4085, 0, 'ONCHANGE'),
  (4122, 0, 'DANGER', 2, 10, 'DANGER_BOX8_S10', '应急演练计划', 4085, 0, 'YEARLY'),
  (4123, 0, 'DANGER', 2, 11, 'DANGER_BOX8_S11', '应急演练（方案、照片、签到）', 4085, 0, 'YEARLY');

-- ========== 6) GENERAL 盒七 应急管理(4036)：V17 三项重排 + 补 6 项 ==========
UPDATE `archive_catalog` SET `sort_no` = 1 WHERE `id` = 403601; -- 应急预案
UPDATE `archive_catalog` SET `sort_no` = 7 WHERE `id` = 403602; -- 应急演练计划
UPDATE `archive_catalog` SET `sort_no` = 8 WHERE `id` = 403603; -- 应急值班表
INSERT IGNORE INTO `archive_catalog`
  (`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`renew_freq`) VALUES
  (4124, 0, 'GENERAL', 2, 2, 'GENERAL_BOX7_S04', '风险评估和应急资源调查', 4036, 0, 'LONG'),
  (4125, 0, 'GENERAL', 2, 3, 'GENERAL_BOX7_S05', '应急预案评审或论证', 4036, 0, 'ONCHANGE'),
  (4126, 0, 'GENERAL', 2, 4, 'GENERAL_BOX7_S06', '应急队伍架构', 4036, 0, 'ONCHANGE'),
  (4127, 0, 'GENERAL', 2, 5, 'GENERAL_BOX7_S07', '应急培训（培训内容、考核、照片）', 4036, 0, 'YEARLY'),
  (4128, 0, 'GENERAL', 2, 6, 'GENERAL_BOX7_S08', '应急救援器材配备', 4036, 0, 'ONCHANGE'),
  (4129, 0, 'GENERAL', 2, 9, 'GENERAL_BOX7_S09', '应急演练（方案、照片、签到）', 4036, 0, 'YEARLY');

-- ========== 7) 盒六补「交通事故违法针对性教育」（普货+危货，原种子缺失） ==========
INSERT IGNORE INTO `archive_catalog`
  (`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`renew_freq`) VALUES
  (4130, 0, 'GENERAL', 2, 6, 'GENERAL_BOX6_S06', '交通事故违法针对性教育（培训内容、照片、签字）', 4030, 0, 'ONCHANGE'),
  (4131, 0, 'DANGER',  2, 6, 'DANGER_BOX6_S06',  '交通事故违法针对性教育（培训内容、照片、签字）', 4078, 0, 'ONCHANGE');
