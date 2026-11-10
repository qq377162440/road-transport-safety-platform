-- B4 一日三检完整闭环：检查项字典租户隔离、未出车声明留痕、状态流转日志
-- 说明：check_task / check_record / check_item_result / check_remind / check_config 已在 V4 建表，
--       本迁移只做字段补齐与新增子表，不重建既有表，保证已产生的演示数据不丢失。

-- 1. 检查项字典支持企业自定义（0=平台预置，其余=企业自定义），与预置项共存互不覆盖
ALTER TABLE `check_item_dict`
  ADD COLUMN `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '0=平台预置；其余=企业自定义' AFTER `id`,
  ADD INDEX `idx_check_dict_tenant` (`tenant_id`, `phase`, `vehicle_class`);

-- 2. 未出车声明 / PC 标记留痕（谁、何时、哪种方式），满足 C12 / C16 可追溯
ALTER TABLE `check_task`
  ADD COLUMN `not_out_by`  BIGINT      NULL COMMENT '声明/标记未出车的人' AFTER `destination`,
  ADD COLUMN `not_out_at`  DATETIME    NULL COMMENT '声明/标记时间' AFTER `not_out_by`,
  ADD COLUMN `not_out_way` VARCHAR(20) NULL COMMENT 'pc=PC标记 / declare=司机声明' AFTER `not_out_at`;

-- 3. 状态流转日志：出车/提交时段/收车/未出车/切换驾驶员/提醒/纠错 逐条留痕
CREATE TABLE IF NOT EXISTS `check_flow_log` (
  `id`          BIGINT       NOT NULL,
  `tenant_id`   BIGINT       NOT NULL,
  `task_id`     BIGINT       NOT NULL,
  `check_day`   DATE         NULL,
  `phase`       VARCHAR(10)  NULL,
  `action`      VARCHAR(40)  NOT NULL,
  `from_status` VARCHAR(20)  NULL,
  `to_status`   VARCHAR(20)  NULL,
  `detail`      VARCHAR(500) NULL,
  `operator`    VARCHAR(50)  NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by`  BIGINT       NULL,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_check_log_task` (`task_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一日三检状态流转日志';
