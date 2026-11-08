-- 安全管理架构（按原型 org.html 正式版）
-- 1) 岗位关联制度与责任书：原型 org.html 右键「关联制度与责任书」的数据落点
-- 2) 岗位人员分配：原型要求「人员一律从系统已录入人员选取」，落 org_member 真实表
-- 3) 演示企业岗位人员与制度初值，保证架构图节点显示真实人名（与小程序端人员同源）

ALTER TABLE `org_post`
    ADD COLUMN `docs` TEXT NULL COMMENT '关联制度与责任书（JSON 数组字符串）' AFTER `assess_std`;

-- 岗位人员分配（5001 佛山市高明高洲燃气有限公司；user_id 取自 person 真实人员）
INSERT INTO `org_member` (`id`, `tenant_id`, `user_id`, `post_id`, `created_by`, `deleted`)
VALUES (8807500000000001, 5001, 8808000000000004, 7001, 0, 0),  -- 邓佐成 · 总经理
       (8807500000000002, 5001, 8808000000000003, 7002, 0, 0),  -- 赵敏 · 安环主管
       (8807500000000003, 5001, 8808000000000002, 7004, 0, 0),  -- 刘洋 · 监控员
       (8807500000000004, 5001, 8808000000000001, 7010, 0, 0);  -- 陈刚 · 驾驶员

-- 默认关联制度（与原型 DOC_POOL 口径一致，按职能给初值）
UPDATE `org_post` SET `docs` = '["安全生产责任制","主要负责人任命书","主要负责人告知书","年度安全生产承诺书","安全生产责任书（岗位版）"]'
WHERE `tenant_id` = 5001 AND `func_key` = 'gm';
UPDATE `org_post` SET `docs` = '["安全生产责任制","安全管理人员任命书","安全检查制度","隐患排查治理制度","安全生产责任书（岗位版）"]'
WHERE `tenant_id` = 5001 AND `func_key` = 'safe';
UPDATE `org_post` SET `docs` = '["安全生产责任制","动态监控制度","安全生产责任书（岗位版）"]'
WHERE `tenant_id` = 5001 AND `func_key` = 'mon';
UPDATE `org_post` SET `docs` = '["安全生产责任制","一日三检制度","安全生产责任书（岗位版）"]'
WHERE `tenant_id` = 5001 AND `func_key` = 'driver';
UPDATE `org_post` SET `docs` = '["安全生产责任制","运营调度管理制度","安全生产责任书（岗位版）"]'
WHERE `tenant_id` = 5001 AND `func_key` = 'disp';
UPDATE `org_post` SET `docs` = '["安全生产责任制","车队安全管理制度","一日三检制度","安全生产责任书（岗位版）"]'
WHERE `tenant_id` = 5001 AND `func_key` = 'captain';
UPDATE `org_post` SET `docs` = '["安全生产责任制","档案管理制度","安全生产责任书（岗位版）"]'
WHERE `tenant_id` = 5001 AND `func_key` = 'clerk';
