-- ============================================================================
-- V14 培训演示数据规范化：补齐演示企业从业人员名册 + 初始下发
-- 说明：历史遗留的 training_assign / training_exam 挂在已停用的旧 draft 计划上，
--       且 status='pass' 不属于状态机枚举，会污染「学习情况统计」，这里一并清理。
-- ============================================================================

-- 1. 清理历史遗留学习记录（旧 draft 计划 2118557914244919296）
UPDATE `training_exam`   SET `deleted` = 1 WHERE `assign_id` = 2118557914559492096;
UPDATE `training_assign` SET `deleted` = 1 WHERE `id` = 2118557914559492096;
UPDATE `training_plan`   SET `deleted` = 1 WHERE `id` = 2118557914244919296;

-- 2. 演示企业（5001）从业人员名册（对齐原型 train_stat.html 的示例姓名）
--    person_type=employee 从业人员；telephone 为必填
INSERT INTO `person`
 (`id`,`tenant_id`,`person_type`,`name`,`telephone`,`gender`,`created_by`,`deleted`)
VALUES
 (8808000000000001,5001,'employee','陈刚','13800001001',1,0,0),
 (8808000000000002,5001,'employee','刘洋','13800001002',1,0,0),
 (8808000000000003,5001,'employee','赵敏','13800001003',0,0,0),
 (8808000000000004,5001,'employee','邓佐成','13923174300',1,0,0)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`);

-- 3. 给 V13 的「2026年11月岗中安全教育培训」初始下发 3 人，开箱即可在小程序端学习
INSERT INTO `training_assign`
 (`id`,`tenant_id`,`plan_id`,`user_id`,`user_type`,`status`,`watch_minutes`,`created_by`,`deleted`)
VALUES
 (8809000000000001,5001,8806000000000001,8808000000000001,'驾驶员','not_started',0,0,0),
 (8809000000000002,5001,8806000000000001,8808000000000002,'驾驶员','not_started',0,0,0),
 (8809000000000003,5001,8806000000000001,8808000000000003,'押运员','not_started',0,0,0)
ON DUPLICATE KEY UPDATE `status`=VALUES(`status`);
