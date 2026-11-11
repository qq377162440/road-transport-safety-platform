-- ============================================================
-- V31 管理人员档案（按人分类的一人一档 PDF 集合）+ 安全生产责任书上下级双签
--
-- 依据：
--  * 《07-人员基础信息与一人一档》§二/§4.4：盒一「管理人员档案」= 管理岗人员
--    的证照、任命、责任书等一人一档聚合（P01~P13）。
--  * 《09-静态资料范本引擎》§九：责任书签字双方 = 责任人（下级）+ 责任管理人
--    （组织架构中该岗位的直接上级），主要负责人无上级 → 与公司签（单边盖章）。
--  * 用户 2026-11-11 反馈：管理人员档案按人分类；责任书上下级都签了才生效。
--
-- 幂等：所有 INSERT 带 NOT EXISTS、UPDATE 带条件，可在已初始化库上重复执行。
-- ============================================================

-- ------------------------------------------------------------------
-- 1. 安全生产责任书签订记录（上下级双签状态机）
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `responsibility_sign` (
  `id`                    BIGINT PRIMARY KEY,
  `tenant_id`             BIGINT NOT NULL DEFAULT 0,
  `sign_year`             SMALLINT NOT NULL COMMENT '签署年度（一年一签）',
  `sub_person_id`         BIGINT NOT NULL COMMENT '责任人（下级）person.id',
  `sub_name`              VARCHAR(50) COMMENT '责任人姓名（冗余快照）',
  `sub_post_id`           BIGINT COMMENT '责任人岗位 org_post.id',
  `sub_post_name`         VARCHAR(100) COMMENT '责任人岗位名（冗余快照）',
  `sub_func_key`          VARCHAR(30) COMMENT '责任人职能 key',
  `sup_type`              VARCHAR(20) NOT NULL DEFAULT 'person' COMMENT 'company=公司（最顶层）/ person=上级人员',
  `sup_person_id`         BIGINT COMMENT '责任管理人（上级）person.id；company 时为 NULL',
  `sup_name`              VARCHAR(50) COMMENT '公司名或上级姓名（冗余快照）',
  `sup_post_id`           BIGINT COMMENT '上级岗位 id；company 时为 NULL',
  `sup_post_name`         VARCHAR(100) COMMENT '上级岗位名；company 时为「公司」',
  `sup_func_key`          VARCHAR(30) COMMENT '上级职能 key',
  `sup_signed`            TINYINT NOT NULL DEFAULT 0 COMMENT '上级/公司是否已签署',
  `sup_sign_at`           DATETIME,
  `sup_sign_image`        VARCHAR(500) COMMENT '上级签名（签字库对象名或文字签名）',
  `sub_signed`            TINYINT NOT NULL DEFAULT 0 COMMENT '下级是否已签署',
  `sub_sign_at`           DATETIME,
  `sub_sign_image`        VARCHAR(500) COMMENT '下级签名',
  `status`                VARCHAR(20) NOT NULL DEFAULT 'pending'
                          COMMENT 'pending待签 / partial单方已签(未生效) / effective双方已签(生效) / void已作废',
  `revision`              INT NOT NULL DEFAULT 1 COMMENT '架构变化导致重签的次数',
  `effective_at`          DATETIME COMMENT '双方签齐的生效时间',
  `pdf_id`                BIGINT COMMENT '生效后生成的归档 PDF（archive_pdf.id）',
  `created_at`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`            BIGINT NOT NULL DEFAULT 0,
  `deleted`               TINYINT NOT NULL DEFAULT 0,
  KEY `idx_resp_sign_tenant_year` (`tenant_id`, `sign_year`),
  KEY `idx_resp_sign_sub` (`tenant_id`, `sub_person_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='安全生产责任书上下级签订状态机';

-- ------------------------------------------------------------------
-- 2. 管理人员样例数据（演示企业 5001；人员基础信息里 person_type=manager）
--    2.1 补齐 3 个原本空缺的启用岗位人员，不改动既有岗位人员，架构图更完整
--    2.2 把架构上原有的 3 位管理岗人员（总经理/安全员/监控员）标为管理人员；
--        驾驶员保持 employee（一线人员，不进「管理人员档案」盒一子项）。
-- ------------------------------------------------------------------
INSERT IGNORE INTO `person` (`id`,`tenant_id`,`person_type`,`name`,`telephone`,`gender`,`politic`,`id_card`,`post_ids`,`created_by`,`deleted`)
SELECT t.a,t.b,t.c,t.d,t.e,t.f,t.g,t.h,t.i,t.j,t.k FROM
  (SELECT 8808000000000011 AS a,5001 AS b,'manager' AS c,'李文博' AS d,'13900001011' AS e,1 AS f,'中共党员' AS g,'440981199003110011' AS h,'7003' AS i,0 AS j,0 AS k) t
WHERE NOT EXISTS (SELECT 1 FROM `person` WHERE `id`=8808000000000011);
INSERT IGNORE INTO `person` (`id`,`tenant_id`,`person_type`,`name`,`telephone`,`gender`,`politic`,`id_card`,`post_ids`,`created_by`,`deleted`)
SELECT t.a,t.b,t.c,t.d,t.e,t.f,t.g,t.h,t.i,t.j,t.k FROM
  (SELECT 8808000000000012 AS a,5001 AS b,'manager' AS c,'周雅琴' AS d,'13900001012' AS e,0 AS f,'群众' AS g,'440981199107220022' AS h,'7005' AS i,0 AS j,0 AS k) t
WHERE NOT EXISTS (SELECT 1 FROM `person` WHERE `id`=8808000000000012);
INSERT IGNORE INTO `person` (`id`,`tenant_id`,`person_type`,`name`,`telephone`,`gender`,`politic`,`id_card`,`post_ids`,`created_by`,`deleted`)
SELECT t.a,t.b,t.c,t.d,t.e,t.f,t.g,t.h,t.i,t.j,t.k FROM
  (SELECT 8808000000000013 AS a,5001 AS b,'manager' AS c,'吴志强' AS d,'13900001013' AS e,1 AS f,'群众' AS g,'440981198812030033' AS h,'7007' AS i,0 AS j,0 AS k) t
WHERE NOT EXISTS (SELECT 1 FROM `person` WHERE `id`=8808000000000013);

INSERT IGNORE INTO `org_member` (`id`,`tenant_id`,`user_id`,`post_id`,`created_by`,`deleted`)
SELECT t.a,t.b,t.c,t.d,t.e,t.f FROM
  (SELECT 8808000000000021 AS a,5001 AS b,8808000000000011 AS c,7003 AS d,0 AS e,0 AS f) t
WHERE NOT EXISTS (SELECT 1 FROM `org_member` WHERE `id`=8808000000000021);
INSERT IGNORE INTO `org_member` (`id`,`tenant_id`,`user_id`,`post_id`,`created_by`,`deleted`)
SELECT t.a,t.b,t.c,t.d,t.e,t.f FROM
  (SELECT 8808000000000022 AS a,5001 AS b,8808000000000012 AS c,7005 AS d,0 AS e,0 AS f) t
WHERE NOT EXISTS (SELECT 1 FROM `org_member` WHERE `id`=8808000000000022);
INSERT IGNORE INTO `org_member` (`id`,`tenant_id`,`user_id`,`post_id`,`created_by`,`deleted`)
SELECT t.a,t.b,t.c,t.d,t.e,t.f FROM
  (SELECT 8808000000000023 AS a,5001 AS b,8808000000000013 AS c,7007 AS d,0 AS e,0 AS f) t
WHERE NOT EXISTS (SELECT 1 FROM `org_member` WHERE `id`=8808000000000023);

UPDATE `person` SET `person_type`='manager'
WHERE `tenant_id`=5001 AND `deleted`=0 AND `person_type`='employee'
  AND `id` IN (8808000000000002,8808000000000003,8808000000000004);

-- ------------------------------------------------------------------
-- 3. 六名管理人员的一人一档 13 项（P01~P13）+ 年度责任制考核记录
--    用户特别点名：岗前培训 P08、考核记录 P10/CREDIT_ASSESS、三书 P05/P06/P07。
--    status=1 已齐；source_type 逐项对齐 person_archive_dict。
-- ------------------------------------------------------------------
INSERT IGNORE INTO `person_archive_item`
  (`id`,`tenant_id`,`person_id`,`item_code`,`source_type`,`ref_id`,`file_url`,`expire_date`,`status`,`created_by`,`deleted`)
SELECT 8808000000200000 + p.seq * 100 + d.sort_no, 5001, p.id, d.item_code, d.source_type, NULL,
       CONCAT('5001/person/', p.id, '_', d.item_code, '.pdf'), NULL, 1, 0, 0
FROM (SELECT 1 AS seq, 8808000000000002 AS id UNION ALL
      SELECT 2, 8808000000000003 UNION ALL
      SELECT 3, 8808000000000004 UNION ALL
      SELECT 4, 8808000000000011 UNION ALL
      SELECT 5, 8808000000000012 UNION ALL
      SELECT 6, 8808000000000013) p
CROSS JOIN `person_archive_dict` d
WHERE d.`deleted`=0 AND d.`item_code` <> 'CREDIT_ASSESS'
  AND NOT EXISTS (SELECT 1 FROM `person_archive_item` x
                  WHERE x.`person_id`=p.id AND x.`item_code`=d.`item_code` AND x.`deleted`=0);

-- 年度责任制考核记录（考核模块沉淀到一人一档的同一编码）
INSERT IGNORE INTO `person_archive_item`
  (`id`,`tenant_id`,`person_id`,`item_code`,`source_type`,`ref_id`,`file_url`,`expire_date`,`status`,`created_by`,`deleted`)
SELECT 8808000000210000 + p.seq, 5001, p.id, 'CREDIT_ASSESS', 'credit', NULL,
       CONCAT('5001/credit/', p.id, '_assess.pdf'), NULL, 1, 0, 0
FROM (SELECT 1 AS seq, 8808000000000002 AS id UNION ALL
      SELECT 2, 8808000000000003 UNION ALL
      SELECT 3, 8808000000000004 UNION ALL
      SELECT 4, 8808000000000011 UNION ALL
      SELECT 5, 8808000000000012 UNION ALL
      SELECT 6, 8808000000000013) p
WHERE NOT EXISTS (SELECT 1 FROM `person_archive_item` x
                  WHERE x.`person_id`=p.id AND x.`item_code`='CREDIT_ASSESS' AND x.`deleted`=0);

-- 少量证件子表样例（身份证 / 驾驶证 / 从业资格证），让档案册有证照明细
INSERT IGNORE INTO `person_doc`
  (`id`,`tenant_id`,`person_id`,`doc_code`,`doc_name`,`file_url`,`issue_date`,`expire_date`,`status`,`created_by`,`deleted`)
SELECT 8808000000220000 + p.seq * 10 + n.no, 5001, p.id, n.code, n.nm,
       CONCAT('5001/person/', p.id, '_', n.code, '.pdf'), '2025-01-10', n.exp, 1, 0, 0
FROM (SELECT 1 AS seq, 8808000000000002 AS id UNION ALL
      SELECT 2, 8808000000000003 UNION ALL
      SELECT 3, 8808000000000004 UNION ALL
      SELECT 4, 8808000000000011 UNION ALL
      SELECT 5, 8808000000000012 UNION ALL
      SELECT 6, 8808000000000013) p
CROSS JOIN (SELECT 1 AS no, 'ID_CARD' AS code, '身份证复印件' AS nm, '2035-01-09' AS exp UNION ALL
            SELECT 2, 'DRIVE_LICENSE', '机动车驾驶证', '2030-06-30' UNION ALL
            SELECT 3, 'QUALIFICATION', '道路运输从业人员从业资格证', '2029-12-31') n
WHERE NOT EXISTS (SELECT 1 FROM `person_doc` x
                  WHERE x.`person_id`=p.id AND x.`doc_code`=n.code AND x.`deleted`=0);
