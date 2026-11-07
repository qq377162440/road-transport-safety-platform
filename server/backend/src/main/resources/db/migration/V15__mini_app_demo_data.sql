-- =====================================================================
-- V15 小程序端正式版：补齐与 PC 端同源的演示业务数据
--
-- 目标：让小程序「首页 / 三检 / 学习 / 我的」以及各功能子页，
--       读到的都是 PC 端同一批业务表里的真实数据，不再有静态假数据。
-- 全部使用固定 ID + ON DUPLICATE KEY UPDATE，保证迁移与验证脚本可幂等重跑。
-- 旧的 check_task(vehicle_id=999001/1, driver_id=6001) 属历史测试残留，
--   与本演示名册 driver_id 对不上，小程序按当前登录人过滤时会被自然排除。
-- =====================================================================

-- ---------- 1. 一日三检检查项字典（全局表，无 tenant_id）----------
INSERT INTO `check_item_dict`
 (`id`,`item_code`,`item_name`,`phase`,`vehicle_class`,`biz_type`,`sort_no`,`is_default`,`deleted`)
VALUES
 (8802000000000001,'OUT_TYRE','轮胎气压正常','out','heavy','freight',1,1,0),
 (8802000000000002,'OUT_BRAKE','刹车正常','out','heavy','freight',2,1,0),
 (8802000000000003,'OUT_LIGHT','灯光正常','out','heavy','freight',3,1,0),
 (8802000000000004,'OUT_FIRE','灭火器在位','out','heavy','freight',4,1,0),
 (8802000000000005,'OUT_OIL','油/水/电正常','out','heavy','freight',5,1,0),
 (8802000000000006,'OUT_CARGO','货物捆扎牢固','out','heavy','freight',6,1,0),
 (8802000000000101,'MID_TYRE','行驶中无异响','mid','heavy','freight',1,1,0),
 (8802000000000102,'MID_BRAKE','制动无跑偏','mid','heavy','freight',2,1,0),
 (8802000000000103,'MID_CARGO','货物无松动','mid','heavy','freight',3,1,0),
 (8802000000000104,'MID_DASH','仪表无报警','mid','heavy','freight',4,1,0),
 (8802000000000201,'IN_PARK','车辆停放到位','in','heavy','freight',1,1,0),
 (8802000000000202,'IN_POWER','电源已关闭','in','heavy','freight',2,1,0),
 (8802000000000203,'IN_DOOR','门窗已锁好','in','heavy','freight',3,1,0),
 (8802000000000204,'IN_CARGO','货物已交接','in','heavy','freight',4,1,0)
ON DUPLICATE KEY UPDATE `item_name`=VALUES(`item_name`),`deleted`=0;

-- ---------- 2. 车辆 + 人车绑定 ----------
INSERT INTO `vehicle`
 (`id`,`tenant_id`,`plate_no`,`plate_color`,`biz_type`,`vehicle_type`,`vehicle_class`,`is_trailer`,`brand_model`,`use_nature`,`business_status`,`created_by`,`deleted`)
VALUES
 (8807000000000001,5001,'粤E·12345','蓝','freight','牵引车','heavy',0,'东风天龙 KL','货运','运营',0,0),
 (8807000000000002,5001,'粤E·67890','蓝','freight','厢式货车','medium',0,'解放 J6L','货运','运营',0,0)
ON DUPLICATE KEY UPDATE `plate_no`=VALUES(`plate_no`),`deleted`=0;

INSERT INTO `vehicle_driver`
 (`id`,`tenant_id`,`vehicle_id`,`driver_id`,`escort_id`,`bind_date`,`created_by`,`deleted`)
VALUES
 (8807100000000001,5001,8807000000000001,8808000000000001,NULL,CURDATE(),0,0),
 (8807100000000002,5001,8807000000000002,8808000000000002,NULL,CURDATE(),0,0)
ON DUPLICATE KEY UPDATE `deleted`=0;

-- ---------- 3. 今日三检任务（PC 端「一日三检」下发，小程序读取）----------
INSERT INTO `check_task`
 (`id`,`tenant_id`,`vehicle_id`,`driver_id`,`is_trailer`,`start_date`,`end_date`,`status`,`cargo_name`,`origin`,`destination`,`created_by`,`deleted`)
VALUES
 (8807200000000001,5001,8807000000000001,8808000000000001,0,CURDATE(),DATE_ADD(CURDATE(), INTERVAL 30 DAY),'ongoing','建材运输','佛山高明','广州黄埔',0,0),
 (8807200000000002,5001,8807000000000002,8808000000000002,0,CURDATE(),DATE_ADD(CURDATE(), INTERVAL 30 DAY),'ongoing','日用百货','佛山高明','深圳龙岗',0,0)
ON DUPLICATE KEY UPDATE `status`='ongoing',`deleted`=0;

-- ---------- 4. 安全会议 + 参会人（小程序签到 → PC 端签到表）----------
INSERT INTO `meeting`
 (`id`,`tenant_id`,`meeting_type`,`title`,`topic`,`mode`,`location`,`start_time`,`end_time`,`host_id`,`recorder_id`,
  `notice_title`,`notice_content`,`status`,`created_by`,`deleted`)
VALUES
 (8807300000000001,5001,'monthly','11月驾驶员安全例会','冬季行车安全与防御性驾驶','线下','一楼会议室',
  CONCAT(CURDATE(),' 14:00:00'),CONCAT(CURDATE(),' 15:30:00'),8808000000000004,8808000000000004,
  '会议通知：今天下午2点安全例会','请全体驾驶员提前10分钟到一楼会议室签到，携带驾驶证。',
  'published',0,0),
 (8807300000000002,5001,'special','危货应急处置专题会','危化品泄漏应急处置流程','线下','二楼培训室',
  CONCAT(DATE_ADD(CURDATE(), INTERVAL 2 DAY),' 14:00:00'),CONCAT(DATE_ADD(CURDATE(), INTERVAL 2 DAY),' 16:00:00'),8808000000000004,8808000000000004,
  '会议通知：危货应急处置专题会','请相关岗位人员准时参加。',
  'published',0,0)
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`),`status`='published',`deleted`=0;

INSERT INTO `meeting_attendee`
 (`id`,`tenant_id`,`meeting_id`,`user_id`,`sign_status`,`sign_source`,`created_by`,`deleted`)
VALUES
 (8807310000000001,5001,8807300000000001,8808000000000001,'unsigned',NULL,0,0),
 (8807310000000002,5001,8807300000000001,8808000000000002,'unsigned',NULL,0,0),
 (8807310000000003,5001,8807300000000001,8808000000000003,'unsigned',NULL,0,0),
 (8807310000000004,5001,8807300000000002,8808000000000001,'unsigned',NULL,0,0)
ON DUPLICATE KEY UPDATE `deleted`=0;

-- ---------- 5. 证件（我的页到期提醒，PC 端一人一档/一车一档同源）----------
INSERT INTO `person_doc`
 (`id`,`tenant_id`,`person_id`,`doc_code`,`doc_name`,`expire_date`,`status`,`created_by`,`deleted`)
VALUES
 (8807400000000001,5001,8808000000000001,'QUALIFICATION','从业资格证',DATE_ADD(CURDATE(), INTERVAL 22 DAY),1,0,0),
 (8807400000000002,5001,8808000000000001,'DRIVE_LICENSE','驾驶证','2028-05-20',1,0,0)
ON DUPLICATE KEY UPDATE `doc_name`=VALUES(`doc_name`),`deleted`=0;

INSERT INTO `vehicle_doc`
 (`id`,`tenant_id`,`vehicle_id`,`doc_code`,`doc_name`,`expire_date`,`status`,`created_by`,`deleted`)
VALUES
 (8807500000000001,5001,8807000000000001,'ROAD_TRANSPORT','道路运输证',DATE_ADD(CURDATE(), INTERVAL 45 DAY),1,0,0)
ON DUPLICATE KEY UPDATE `doc_name`=VALUES(`doc_name`),`deleted`=0;

-- ---------- 6. 隐患（PC 端隐患排查台账 → 小程序可查看/上报）----------
INSERT INTO `danger_record`
 (`id`,`tenant_id`,`check_type`,`title`,`danger_level`,`location`,`vehicle_id`,`finder_id`,`find_time`,`status`,`created_by`,`deleted`)
VALUES
 (8807600000000001,5001,'patrol','左后轮胎纹磨损接近限值','一般','停车场 A 区',8807000000000001,8808000000000002,
  DATE_SUB(NOW(), INTERVAL 3 DAY),'rectifying',0,0),
 (8807600000000002,5001,'check_daily','灭火器压力表指针在红区','一般','粤E·67890',8807000000000002,8808000000000001,
  DATE_SUB(NOW(), INTERVAL 8 DAY),'pending',0,0)
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`),`deleted`=0;

INSERT INTO `danger_rectify`
 (`id`,`tenant_id`,`danger_id`,`rectify_no`,`rectify_require`,`responsible_id`,`deadline`,`created_by`,`deleted`)
VALUES
 (8807610000000001,5001,8807600000000001,'ZG-2026-001','更换左后轮胎并复核胎压',8808000000000002,
  DATE_ADD(CURDATE(), INTERVAL 3 DAY),0,0)
ON DUPLICATE KEY UPDATE `rectify_require`=VALUES(`rectify_require`),`deleted`=0;

-- ---------- 7. 车辆维修记录（PC 端维修保养管理 → 小程序可查看/上报）----------
INSERT INTO `vehicle_repair`
 (`id`,`tenant_id`,`vehicle_id`,`plate_no`,`repair_type`,`repair_date`,`mileage`,`fault_desc`,
  `repair_content`,`repair_org`,`cost_amount`,`handler`,`status`,`created_by`,`deleted`)
VALUES
 (8807700000000001,5001,8807000000000001,'粤E·12345','日常保养',DATE_SUB(CURDATE(), INTERVAL 15 DAY),128500,
  '更换机油三滤','更换机油、机滤、空滤、燃油滤清器','高明区顺安汽修',860.00,'陈刚','done',0,0)
ON DUPLICATE KEY UPDATE `fault_desc`=VALUES(`fault_desc`),`deleted`=0;

-- ---------- 8. 通知 / 预警（首页公告横幅、我的消息，PC 端动态监控同源）----------
INSERT INTO `warn_record`
 (`id`,`tenant_id`,`warn_type`,`level`,`title`,`target_type`,`target_id`,`status`,`expire_date`,`created_by`,`deleted`)
VALUES
 (8807800000000001,5001,'notice',1,'关于开展冬季行车安全专项检查的通知','all',0,1,
  DATE_ADD(CURDATE(), INTERVAL 15 DAY),0,0),
 (8807800000000002,5001,'notice',2,'粤E·12345 道路运输证将在 45 天后到期，请及时年审','vehicle',8807000000000001,1,
  DATE_ADD(CURDATE(), INTERVAL 45 DAY),0,0),
 (8807800000000003,5001,'cert_expire',2,'陈刚 从业资格证即将到期','person',8808000000000001,1,
  DATE_ADD(CURDATE(), INTERVAL 22 DAY),0,0)
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`),`deleted`=0;

-- ---------- 9. 待签制度文件（PC 端制度发布 → 小程序文件签署）----------
INSERT INTO `sign_task`
 (`id`,`tenant_id`,`title`,`source_pdf`,`anchors`,`status`,`created_by`,`deleted`)
VALUES
 (8807900000000001,5001,'《冬季行车安全管理制度》阅知确认','',
  '[{"page":1,"x":420,"y":720}]','pending',0,0),
 (8807900000000002,5001,'《驾驶员安全承诺书》签署','',
  '[{"page":1,"x":420,"y":720}]','pending',0,0)
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`),`deleted`=0;

-- ---------- 10. 公告已读回执（按人区分，PC 端亦可查询）----------
CREATE TABLE IF NOT EXISTS `mini_notice_read` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `notice_id` BIGINT NOT NULL,
  `person_id` BIGINT NOT NULL,
  `read_time` DATETIME NULL,
  `created_at` DATETIME NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mini_notice_person` (`tenant_id`,`notice_id`,`person_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

