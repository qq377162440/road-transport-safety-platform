-- =====================================================================
-- B15 应急管理 · 完整流程闭环数据层（详细设计 15）
-- 预案(模板/生成) + 演练(计划/项目/执行/签到/照片) + 队伍/器材 + 危货专属 + 流转日志
-- 归档目标：盒七·应急管理 GENERAL_BOX7=4036（普货）/ 盒八·应急管理 DANGER_BOX8=4085（危货）
-- 子项已在 V17 播种：403601 应急预案 / 403602 应急演练计划 / 403603 应急值班表
-- 采用 IF NOT EXISTS + INSERT IGNORE，便于开发期手动预建、Flyway 部署时幂等重跑。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `emergency_plan_tpl` (
  `id`          BIGINT PRIMARY KEY,
  `tenant_id`   BIGINT NOT NULL DEFAULT 0,
  `tpl_code`    VARCHAR(50)  NOT NULL,
  `tpl_name`    VARCHAR(100) NOT NULL,
  `tpl_type`    VARCHAR(20)  DEFAULT 'special',
  `cargo_type`  VARCHAR(10)  DEFAULT 'general',
  `content_html` LONGTEXT,
  `variables`   TEXT,
  `is_default`  TINYINT DEFAULT 0,
  `sort_order`  INT DEFAULT 0,
  `created_at`  DATETIME DEFAULT NOW(),
  `deleted`     TINYINT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急预案场景范本（系统预置）';

CREATE TABLE IF NOT EXISTS `emergency_plan` (
  `id`            BIGINT PRIMARY KEY,
  `tenant_id`     BIGINT NOT NULL,
  `cargo_type`    VARCHAR(10) NOT NULL DEFAULT 'general',
  `plan_name`     VARCHAR(200) NOT NULL,
  `version`       VARCHAR(20) DEFAULT 'V1',
  `scene_codes`   VARCHAR(500),
  `content_html`  LONGTEXT,
  `variables`     TEXT,
  `status`        VARCHAR(20) DEFAULT 'draft',
  `record_no`     VARCHAR(100),
  `record_file`   VARCHAR(500),
  `effective_date` DATETIME,
  `sign_info`     TEXT,
  `created_by`    BIGINT,
  `created_at`    DATETIME DEFAULT NOW(),
  `updated_at`    DATETIME,
  `deleted`       TINYINT DEFAULT 0,
  KEY `idx_ep_tenant` (`tenant_id`,`deleted`),
  KEY `idx_ep_status` (`tenant_id`,`status`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急预案';

CREATE TABLE IF NOT EXISTS `emergency_drill_plan` (
  `id`          BIGINT PRIMARY KEY,
  `tenant_id`   BIGINT NOT NULL,
  `plan_name`   VARCHAR(200) NOT NULL,
  `year`        INT NOT NULL,
  `cargo_type`  VARCHAR(10),
  `status`      VARCHAR(20) DEFAULT 'draft',
  `created_by`  BIGINT,
  `created_at`  DATETIME DEFAULT NOW(),
  `deleted`     TINYINT DEFAULT 0,
  KEY `idx_edp_tenant` (`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急演练年度计划';

CREATE TABLE IF NOT EXISTS `emergency_drill_item` (
  `id`          BIGINT PRIMARY KEY,
  `tenant_id`   BIGINT NOT NULL,
  `plan_id`     BIGINT NOT NULL,
  `drill_name`  VARCHAR(200) NOT NULL,
  `scene_code`  VARCHAR(50),
  `drill_type`  VARCHAR(20),
  `plan_time`   DATETIME,
  `scheme`      TEXT,
  `eval_template` TEXT,
  `responsible_id` BIGINT,
  `created_at`  DATETIME DEFAULT NOW(),
  `deleted`     TINYINT DEFAULT 0,
  KEY `idx_edi_plan` (`plan_id`,`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急演练项目';

CREATE TABLE IF NOT EXISTS `emergency_drill` (
  `id`          BIGINT PRIMARY KEY,
  `tenant_id`   BIGINT NOT NULL,
  `plan_item_id` BIGINT,
  `drill_name`  VARCHAR(200) NOT NULL,
  `drill_type`  VARCHAR(20),
  `drill_date`  DATETIME,
  `eval_result` TEXT,
  `status`      VARCHAR(20) DEFAULT 'draft',
  `created_at`  DATETIME DEFAULT NOW(),
  `updated_at`  DATETIME,
  `deleted`     TINYINT DEFAULT 0,
  KEY `idx_ed_tenant` (`tenant_id`,`deleted`),
  KEY `idx_ed_status` (`tenant_id`,`status`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急演练执行';

CREATE TABLE IF NOT EXISTS `emergency_drill_sign` (
  `id`            BIGINT PRIMARY KEY,
  `tenant_id`     BIGINT NOT NULL,
  `drill_id`      BIGINT NOT NULL,
  `user_id`       BIGINT NOT NULL,
  `sign_status`   VARCHAR(20) DEFAULT 'pending',
  `sign_time`     DATETIME,
  `sign_image`    VARCHAR(500),
  `sign_source`   VARCHAR(20),
  `replenished_by` BIGINT,
  `created_at`    DATETIME DEFAULT NOW(),
  `deleted`       TINYINT DEFAULT 0,
  KEY `idx_eds_drill` (`drill_id`,`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演练签到（统一签到入口来源）';

CREATE TABLE IF NOT EXISTS `emergency_drill_photo` (
  `id`          BIGINT PRIMARY KEY,
  `tenant_id`   BIGINT NOT NULL,
  `drill_id`    BIGINT NOT NULL,
  `photo_url`   VARCHAR(500) NOT NULL,
  `caption`     VARCHAR(200),
  `sort_order`  INT DEFAULT 0,
  `created_at`  DATETIME DEFAULT NOW(),
  `deleted`     TINYINT DEFAULT 0,
  KEY `idx_edp_drill` (`drill_id`,`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演练照片';

CREATE TABLE IF NOT EXISTS `emergency_team` (
  `id`          BIGINT PRIMARY KEY,
  `tenant_id`   BIGINT NOT NULL,
  `team_name`   VARCHAR(100) NOT NULL,
  `team_role`   VARCHAR(50),
  `leader_id`   BIGINT,
  `created_at`  DATETIME DEFAULT NOW(),
  `deleted`     TINYINT DEFAULT 0,
  KEY `idx_et_tenant` (`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急队伍';

CREATE TABLE IF NOT EXISTS `emergency_team_member` (
  `id`          BIGINT PRIMARY KEY,
  `tenant_id`   BIGINT NOT NULL,
  `team_id`     BIGINT NOT NULL,
  `user_id`     BIGINT NOT NULL,
  `duty`        VARCHAR(100),
  `phone`       VARCHAR(20),
  `created_at`  DATETIME DEFAULT NOW(),
  `deleted`     TINYINT DEFAULT 0,
  KEY `idx_etm_team` (`team_id`,`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急队伍成员';

CREATE TABLE IF NOT EXISTS `emergency_equipment` (
  `id`          BIGINT PRIMARY KEY,
  `tenant_id`   BIGINT NOT NULL,
  `equip_name`  VARCHAR(100) NOT NULL,
  `equip_type`  VARCHAR(50),
  `quantity`    INT DEFAULT 0,
  `unit`        VARCHAR(20),
  `valid_until` DATETIME,
  `location`    VARCHAR(200),
  `keeper_id`   BIGINT,
  `created_at`  DATETIME DEFAULT NOW(),
  `deleted`     TINYINT DEFAULT 0,
  KEY `idx_ee_tenant` (`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急器材';

CREATE TABLE IF NOT EXISTS `emergency_duty` (
  `id`          BIGINT PRIMARY KEY,
  `tenant_id`   BIGINT NOT NULL,
  `duty_date`   DATE NOT NULL,
  `user_id`     BIGINT NOT NULL,
  `phone`       VARCHAR(20),
  `shift`       VARCHAR(20),
  `created_at`  DATETIME DEFAULT NOW(),
  `deleted`     TINYINT DEFAULT 0,
  KEY `idx_ed_tenant` (`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='危货应急值班表';

CREATE TABLE IF NOT EXISTS `emergency_duty_rule` (
  `id`            BIGINT PRIMARY KEY,
  `tenant_id`     BIGINT NOT NULL,
  `rule_name`     VARCHAR(200) NOT NULL,
  `content_html`  LONGTEXT,
  `file_url`      VARCHAR(500),
  `effective_date` DATETIME,
  `created_at`    DATETIME DEFAULT NOW(),
  `deleted`       TINYINT DEFAULT 0,
  KEY `idx_edr_tenant` (`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='危货应急值班制度';

CREATE TABLE IF NOT EXISTS `emergency_flow_log` (
  `id`            BIGINT PRIMARY KEY,
  `tenant_id`     BIGINT NOT NULL,
  `biz_type`      VARCHAR(20),
  `biz_id`        BIGINT NOT NULL,
  `action`        VARCHAR(50),
  `from_status`   VARCHAR(20),
  `to_status`     VARCHAR(20),
  `detail`        VARCHAR(500),
  `operator`      VARCHAR(50),
  `created_at`    DATETIME DEFAULT NOW(),
  `created_by`    BIGINT,
  `deleted`       TINYINT DEFAULT 0,
  KEY `idx_efl_biz` (`biz_type`,`biz_id`,`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急管理流转留痕';

-- ---------------------------------------------------------------------
-- 系统预置 6 套场景预案范本（综合/专项/现场处置）
-- 变量占位：{{companyName}}{{legalPerson}}{{safetyCharge}}{{chargePhone}}{{address}}{{year}}{{mainRoute}}
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `emergency_plan_tpl` (`id`,`tenant_id`,`tpl_code`,`tpl_name`,`tpl_type`,`cargo_type`,`content_html`,`variables`,`is_default`,`sort_order`) VALUES
(24001,0,'comprehensive','综合应急预案','comprehensive','all',
'<h3>一、总则</h3><p>{{companyName}}为依法注册的运输经营企业，法定代表人{{legalPerson}}。为建立健全应急管理体系，提升突发事件应急处置能力，依据《生产安全事故应急条例》等制定本预案。</p>
<h3>二、应急组织机构与职责</h3><p>成立以主要负责人{{legalPerson}}为总指挥的应急救援指挥部，安全管理员{{safetyCharge}}（联系电话{{chargePhone}}）任办公室主任，下设现场处置组、后勤保障组。</p>
<h3>三、应急响应</h3><p>发生突发事件，现场人员立即报告安全管理员{{safetyCharge}}，启动相应专项预案，按程序上报并组织救援。</p>
<h3>四、后期处置与保障</h3><p>地址：{{address}}；主要营运线路：{{mainRoute}}。本预案自{{year}}年起施行，每年评审修订一次。</p>',
'["companyName","legalPerson","safetyCharge","chargePhone","address","year","mainRoute"]',1,1),
(24002,0,'road_accident','道路交通事故专项应急预案','special','general',
'<h3>一、适用范围</h3><p>本预案适用于{{companyName}}营运车辆在道路行驶过程中发生的碰撞、追尾、刮擦等交通事故。</p>
<h3>二、处置流程</h3><p>1.立即停车、开启双闪、设置警示标志；2.抢救伤员并拨打120/122；3.报告安全管理员{{safetyCharge}}（{{chargePhone}}）；4.保护现场、拍照取证；5.配合交警调查。</p>
<h3>三、注意事项</h3><p>严禁逃逸，地址{{address}}所属路段优先联系就近救援。</p>',
'["companyName","safetyCharge","chargePhone","address"]',1,2),
(24003,0,'vehicle_fire','车辆火灾事故专项应急预案','special','general',
'<h3>一、适用范围</h3><p>适用于{{companyName}}营运车辆发生自燃、货物起火等火灾事故。</p>
<h3>二、处置流程</h3><p>1.立即靠边停车、熄火断电；2.人员迅速撤离至安全地带；3.使用车载灭火器初期扑救；4.火势失控立即拨打119并报告{{safetyCharge}}（{{chargePhone}}）。</p>',
'["companyName","safetyCharge","chargePhone"]',1,3),
(24004,0,'bad_weather','恶劣天气专项应急预案','special','general',
'<h3>一、适用范围</h3><p>适用于台风、暴雨、大雾、结冰等恶劣天气下的营运调度与行车安全。</p>
<h3>二、处置流程</h3><p>1.关注气象预警，必要时停运或绕行；2.安全管理员{{safetyCharge}}发布安全提示；3.驾驶员降速、增距、开启灯光；4.极端天气就地避险并报告{{chargePhone}}。</p>',
'["companyName","safetyCharge","chargePhone"]',1,4),
(24005,0,'cargo_spill','货物散落、受损专项应急预案','special','general',
'<h3>一、适用范围</h3><p>适用于{{companyName}}运输货物发生散落、破损、泄漏（非危化品）等异常。</p>
<h3>二、处置流程</h3><p>1.停车警戒、设置反光标识；2.联系发货/收货方及{{safetyCharge}}（{{chargePhone}}）；3.做好防护后清理或转运；4.拍照留存、登记损失。</p>',
'["companyName","safetyCharge","chargePhone"]',1,5),
(24006,0,'casualty','人员伤亡现场处置方案','site','general',
'<h3>一、适用范围</h3><p>适用于营运过程中发生的人员受伤、伤亡等突发事件的现场先期处置。</p>
<h3>二、处置流程</h3><p>1.立即停止作业、保护现场；2.对伤员实施力所能及的急救；3.拨打120急救、122报警；4.报告主要负责人{{legalPerson}}及安全管理员{{safetyCharge}}（{{chargePhone}}）；5.配合调查、安抚家属。</p>',
'["companyName","legalPerson","safetyCharge","chargePhone"]',1,6);
