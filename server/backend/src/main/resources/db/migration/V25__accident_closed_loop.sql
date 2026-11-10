-- ============================================================
-- V25 事故管理 + 交通违法 完整闭环
-- 设计：16-事故管理（登记/快报/年度汇总/增值服务）+ 20-动态监控（交通违法登记）
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE
-- ============================================================

-- 1. 事故登记表
CREATE TABLE IF NOT EXISTS `accident_record` (
  `id`              BIGINT PRIMARY KEY,
  `tenant_id`       BIGINT NOT NULL,
  `accident_no`     VARCHAR(50),
  `accident_time`   DATETIME NOT NULL,
  `location`        VARCHAR(200),
  `vehicle_id`      BIGINT,
  `vehicle_plate`   VARCHAR(20),
  `accident_type`   VARCHAR(30),
  `involve_person`  TEXT,
  `description`     TEXT,
  `loss_amount`     DECIMAL(12,2),
  `responsibility`  VARCHAR(200),
  `severity`        VARCHAR(20),
  `status`          VARCHAR(20) DEFAULT 'reported',
  `source`          VARCHAR(20) DEFAULT 'pc',
  `photos`          VARCHAR(2000),
  `reported_by`     BIGINT,
  `reported_at`     DATETIME DEFAULT NULL,
  `created_at`      DATETIME DEFAULT NULL,
  `updated_at`      DATETIME DEFAULT NULL,
  `deleted`         TINYINT DEFAULT 0
);

-- 2. 事故快报表（登记后自动生成）
CREATE TABLE IF NOT EXISTS `accident_quick_report` (
  `id`              BIGINT PRIMARY KEY,
  `tenant_id`       BIGINT NOT NULL,
  `accident_id`     BIGINT NOT NULL,
  `report_content`  LONGTEXT,
  `report_no`       VARCHAR(50),
  `report_time`     DATETIME DEFAULT NULL,
  `pdf_id`          BIGINT,
  `created_at`      DATETIME DEFAULT NULL
);

-- 3. 年度事故汇总统计表（按年自动聚合）
CREATE TABLE IF NOT EXISTS `accident_year_summary` (
  `id`              BIGINT PRIMARY KEY,
  `tenant_id`       BIGINT NOT NULL,
  `year`            INT NOT NULL,
  `total_count`     INT DEFAULT 0,
  `total_loss`      DECIMAL(14,2) DEFAULT 0,
  `minor_count`     INT DEFAULT 0,
  `general_count`   INT DEFAULT 0,
  `major_count`     INT DEFAULT 0,
  `pdf_id`          BIGINT,
  `created_at`      DATETIME DEFAULT NULL,
  UNIQUE KEY `uk_tenant_year` (`tenant_id`, `year`)
);

-- 4. 增值服务申请表（事故资料整理/四不放过/处理指导/责任分析）
CREATE TABLE IF NOT EXISTS `accident_service` (
  `id`              BIGINT PRIMARY KEY,
  `tenant_id`       BIGINT NOT NULL,
  `accident_id`     BIGINT DEFAULT NULL,
  `service_type`    VARCHAR(50),
  `service_desc`    VARCHAR(1000),
  `contact_name`    VARCHAR(50),
  `contact_phone`   VARCHAR(20),
  `status`          VARCHAR(20) DEFAULT 'pending',
  `fee`             DECIMAL(12,2),
  `created_at`      DATETIME DEFAULT NULL,
  `updated_at`      DATETIME DEFAULT NULL
);

-- 5. 交通违法登记表（C3 交通违法；联动 14 违规类型字典 + 靶向教育）
CREATE TABLE IF NOT EXISTS `traffic_violation` (
  `id`              BIGINT PRIMARY KEY,
  `tenant_id`       BIGINT NOT NULL,
  `vehicle_id`      BIGINT,
  `vehicle_plate`   VARCHAR(20),
  `driver_id`       BIGINT,
  `driver_name`     VARCHAR(50),
  `violation_type`  VARCHAR(50),
  `violation_time`  DATETIME NOT NULL,
  `location`        VARCHAR(200),
  `punish_no`       VARCHAR(50),
  `fine_amount`     DECIMAL(10,2),
  `deduct_points`   INT DEFAULT 0,
  `description`     VARCHAR(500),
  `edu_status`      VARCHAR(20) DEFAULT 'none',
  `edu_plan_id`     BIGINT DEFAULT NULL,
  `status`          VARCHAR(20) DEFAULT 'registered',
  `source`          VARCHAR(20) DEFAULT 'pc',
  `created_at`      DATETIME DEFAULT NULL,
  `updated_at`      DATETIME DEFAULT NULL,
  `deleted`         TINYINT DEFAULT 0
);

-- 6. 操作留痕
CREATE TABLE IF NOT EXISTS `accident_flow_log` (
  `id`           BIGINT PRIMARY KEY,
  `tenant_id`    BIGINT NOT NULL,
  `biz_type`     VARCHAR(30),
  `biz_id`       BIGINT,
  `action`       VARCHAR(50),
  `from_status`  VARCHAR(20),
  `to_status`    VARCHAR(20),
  `detail`       VARCHAR(500),
  `operator`     VARCHAR(50),
  `created_at`   DATETIME DEFAULT NULL,
  `created_by`   BIGINT,
  `deleted`      TINYINT DEFAULT 0
);
