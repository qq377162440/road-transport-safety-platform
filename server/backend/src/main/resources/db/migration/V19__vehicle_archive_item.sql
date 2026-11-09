-- B2 车辆基础信息 · 一车一档归档状态表（详细设计 10 §2.1 / §4）
-- 与 B1 person_archive_item 对称：承载 13 项归档目录 C01~C13 的逐项状态机
-- （0缺 / 1齐 / 2已过期），证件上传、PDF 生成、空白表打印、手工标记齐全均 upsert 到此表。
-- 齐全判定除状态表外，还会实时读取真实子表（vehicle_doc / vehicle_insurance /
-- vehicle_device / vehicle_grade / vehicle_repair）兜底，保证「同源数据」自动点亮归档项。
-- 13 项目录定义放在 VehicleFlowController 常量中，不依赖既有 vehicle_doc_dict（后者是证照清单，语义不同）。
CREATE TABLE IF NOT EXISTS `vehicle_archive_item` (
  `id`          bigint NOT NULL COMMENT '雪花ID',
  `tenant_id`   bigint NOT NULL DEFAULT '0' COMMENT '租户ID',
  `vehicle_id`  bigint NOT NULL COMMENT '关联 vehicle',
  `item_code`   varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '归档项编码 C01~C13',
  `source_type` varchar(20) DEFAULT NULL COMMENT 'upload上传/auto系统生成/print空白表/ledger动态台账/manual手工',
  `ref_id`      bigint DEFAULT NULL COMMENT '关联源数据ID',
  `file_url`    varchar(500) DEFAULT NULL COMMENT '成品PDF或扫描件对象存储路径',
  `expire_date` date DEFAULT NULL COMMENT '有效期（→预警）',
  `status`      tinyint DEFAULT '0' COMMENT '0缺 1齐 2已过期',
  `created_at`  datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`  bigint NOT NULL DEFAULT '0' COMMENT '创建人ID',
  `deleted`     tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_vehicle_archive_item_tenant` (`tenant_id`),
  KEY `idx_vehicle_archive_item_vehicle` (`vehicle_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='一车一档归档子项';
