-- ============================================================================
-- V13 岗中「线上学习（看视频 + 做考核）」闭环
-- 对齐 详细设计/14-培训教育.md（T2/T3/T4/T6/T9/T10）与原型：
--   pc/video_exam.html  服务商课程库 + 试题管理
--   pc/training.html    岗中月度培训计划
--   pc/train_stat.html  学习情况统计
--   mini/study.html     小程序学习中心
-- ============================================================================

-- ---------- 1. 课程（服务商全局课程库，tenant_id=0；企业可自建 tenant_id>0）----------
CREATE TABLE IF NOT EXISTS `training_course` (
  `id`           BIGINT       NOT NULL COMMENT '雪花ID / 固定种子ID',
  `tenant_id`    BIGINT       NOT NULL DEFAULT 0 COMMENT '0=服务商全局课程，>0=企业自建',
  `course_name`  VARCHAR(200) NOT NULL COMMENT '课程名称',
  `hours`        INT          NOT NULL DEFAULT 1 COMMENT '课时数',
  `category`     VARCHAR(500) DEFAULT '' COMMENT '课程分类（可多选，逗号分隔）',
  `industry`     VARCHAR(50)  DEFAULT '' COMMENT '行业分类',
  `post`         VARCHAR(500) DEFAULT '' COMMENT '岗位分类（可多选，逗号分隔）',
  `duration`     VARCHAR(50)  DEFAULT '' COMMENT '总时长展示，如 08:30',
  `source`       VARCHAR(20)  DEFAULT '服务商' COMMENT '课程来源：服务商/平台',
  `scope`        VARCHAR(20)  DEFAULT '全部' COMMENT '使用范围：全部/会员',
  `paper_id`     BIGINT       DEFAULT NULL COMMENT '绑定考试卷',
  `video_desc`   VARCHAR(2000) DEFAULT '' COMMENT '培训内容文字描述（台账「培训内容」来源）',
  `status`       VARCHAR(20)  NOT NULL DEFAULT 'published' COMMENT 'draft/published',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`   BIGINT       NOT NULL DEFAULT 0,
  `deleted`      TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_training_course_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='服务商课程库';

-- ---------- 2. 考试卷（题目按 paper_id 归属，课程绑定卷子）----------
CREATE TABLE IF NOT EXISTS `training_paper` (
  `id`             BIGINT       NOT NULL,
  `tenant_id`      BIGINT       NOT NULL DEFAULT 0,
  `paper_name`     VARCHAR(200) NOT NULL COMMENT '卷子名称',
  `question_count` INT          NOT NULL DEFAULT 0 COMMENT '题目数（冗余，便于列表展示）',
  `status`         VARCHAR(20)  NOT NULL DEFAULT 'published',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`     BIGINT       NOT NULL DEFAULT 0,
  `deleted`        TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_training_paper_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='培训考试卷';

-- ---------- 3. 课程-视频关联（一门课程可含多段视频）----------
CREATE TABLE IF NOT EXISTS `training_course_video` (
  `id`         BIGINT   NOT NULL,
  `tenant_id`  BIGINT   NOT NULL DEFAULT 0,
  `course_id`  BIGINT   NOT NULL,
  `video_id`   BIGINT   NOT NULL,
  `sort_order` INT      NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` BIGINT   NOT NULL DEFAULT 0,
  `deleted`    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_training_cv_course` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='课程-视频关联';

-- ---------- 4. 题目挂卷 + 难度/排序；视频增加秒级时长与封面 ----------
ALTER TABLE `training_question`
  ADD COLUMN `paper_id`   BIGINT      DEFAULT NULL COMMENT '所属考试卷',
  ADD COLUMN `difficulty` VARCHAR(10) DEFAULT '中' COMMENT '难度：易/中/难',
  ADD COLUMN `sort_order` INT         DEFAULT 0 COMMENT '题序',
  ADD KEY `idx_training_question_paper` (`paper_id`);

ALTER TABLE `training_video`
  ADD COLUMN `duration_sec` INT         DEFAULT 0 COMMENT '视频实际时长（秒，用于播放进度判定）',
  ADD COLUMN `cover`        VARCHAR(200) DEFAULT '' COMMENT '封面（可选）';

-- ---------- 5. 培训计划关联课程/卷子 ----------
ALTER TABLE `training_plan`
  ADD COLUMN `course_id`   BIGINT DEFAULT NULL COMMENT '关联课程（岗中月度计划）',
  ADD COLUMN `paper_id`    BIGINT DEFAULT NULL COMMENT '关联考试卷',
  ADD COLUMN `video_count` INT    DEFAULT 0 COMMENT '视频数',
  ADD COLUMN `description` VARCHAR(1000) DEFAULT '' COMMENT '培训说明';

-- ============================================================================
-- 种子：平台级视频（tenant_id=0，URL 指向前端静态资源 /videos/*.mp4）
-- ============================================================================
INSERT INTO `training_video`
 (`id`,`tenant_id`,`video_name`,`video_desc`,`video_url`,`duration`,`duration_sec`,`cargo_type`,`is_platform`,`created_by`,`deleted`)
VALUES
 (8801000000000001,0,'冬季行车安全要点','出车前检查、冰雪路面降速增距、侧滑处置等冬季安全行车要点。','/videos/winter_drive.mp4',1,18,'all',1,0,0),
 (8801000000000002,0,'防御性驾驶技巧','放眼远方、留有余地、引人注意、预估风险四项防御性驾驶核心技巧。','/videos/defensive_drive.mp4',1,16,'all',1,0,0),
 (8801000000000003,0,'危化品应急处置','危险货物泄漏后的停车警戒、报警要素、个人防护与禁绝火源。','/videos/hazmat_emergency.mp4',1,20,'danger',1,0,0),
 (8801000000000004,0,'消防控制室“四快”处置规程','快确认、快调集、快启动、快疏散的消防控制室处置规程。','/videos/fire_control.mp4',1,15,'all',1,0,0),
 (8801000000000005,0,'新《安全生产法》解读','全员安全生产责任制、双重预防机制、从业人员权利义务与违法处罚。','/videos/safety_law.mp4',1,17,'all',1,0,0)
ON DUPLICATE KEY UPDATE `video_name`=VALUES(`video_name`);

-- ============================================================================
-- 种子：考试卷（4 套）
-- ============================================================================
INSERT INTO `training_paper` (`id`,`tenant_id`,`paper_name`,`question_count`,`status`,`created_by`,`deleted`) VALUES
 (8802000000000001,0,'冬季行车与防御性驾驶考试卷',6,'published',0,0),
 (8802000000000002,0,'消防安全与应急知识考试卷',5,'published',0,0),
 (8802000000000003,0,'危化品运输安全考试卷',5,'published',0,0),
 (8802000000000004,0,'安全生产法律法规考试卷',5,'published',0,0)
ON DUPLICATE KEY UPDATE `paper_name`=VALUES(`paper_name`);

-- ============================================================================
-- 种子：题目（单选 / 多选 / 判断，options 为 JSON 数组，answer 为选项 key 或 正确/错误）
-- ============================================================================
INSERT INTO `training_question`
 (`id`,`tenant_id`,`paper_id`,`question_type`,`stem`,`options`,`answer`,`analysis`,`difficulty`,`sort_order`,`created_by`,`deleted`) VALUES
 -- 冬季行车与防御性驾驶（6 题）
 (8803000000000001,0,8802000000000001,'single','冰雪路面行车时，下列做法正确的是？',
  '[{"key":"A","text":"紧急制动减速"},{"key":"B","text":"降低车速、加大跟车距离"},{"key":"C","text":"急打方向盘变道"},{"key":"D","text":"加速尽快驶离"}]','B',
  '冰雪路面附着系数低，应降速、增距、缓操作，忌急刹急打方向。','易',1,0,0),
 (8803000000000002,0,8802000000000001,'single','驾驶员连续驾驶机动车超过多长时间应停车休息？',
  '[{"key":"A","text":"2 小时"},{"key":"B","text":"3 小时"},{"key":"C","text":"4 小时"},{"key":"D","text":"6 小时"}]','C',
  '连续驾驶不得超过 4 小时，停车休息时间不少于 20 分钟。','易',2,0,0),
 (8803000000000003,0,8802000000000001,'multi','出车前例检应重点检查哪些项目？',
  '[{"key":"A","text":"制动系统"},{"key":"B","text":"灯光与信号"},{"key":"C","text":"轮胎与防滑链"},{"key":"D","text":"车载音乐音量"}]','A,B,C',
  '制动、灯光、轮胎是出车前三检的核心项目。','中',3,0,0),
 (8803000000000004,0,8802000000000001,'judge','防御性驾驶要求驾驶人放眼远方，保持 15 秒以上的观察距离。',
  '[{"key":"正确","text":"正确"},{"key":"错误","text":"错误"}]','正确',
  '放眼远方是防御性驾驶四项核心之一。','易',4,0,0),
 (8803000000000005,0,8802000000000001,'judge','车辆发生侧滑时，应猛踩刹车并反打方向。',
  '[{"key":"正确","text":"正确"},{"key":"错误","text":"错误"}]','错误',
  '侧滑应顺打方向、轻收油门，禁止紧急制动。','中',5,0,0),
 (8803000000000006,0,8802000000000001,'multi','冬季行车前应做好哪些准备？',
  '[{"key":"A","text":"检查防冻液与玻璃水"},{"key":"B","text":"备好防滑链与三角警示牌"},{"key":"C","text":"清除车窗积雪与雾气"},{"key":"D","text":"降低胎压以增加摩擦"}]','A,B,C',
  '冬季应先清理视线、检查油液并备齐应急装备。','中',6,0,0),

 -- 消防安全与应急（5 题）
 (8803000000000007,0,8802000000000002,'single','消防控制室“四快”处置规程中的“四快”不包括以下哪项？',
  '[{"key":"A","text":"快确认"},{"key":"B","text":"快调集"},{"key":"C","text":"快启动"},{"key":"D","text":"快撤离现场"}]','D',
  '四快为快确认、快调集、快启动、快疏散。','易',1,0,0),
 (8803000000000008,0,8802000000000002,'single','客运车辆发生火灾时，应优先组织乘客如何疏散？',
  '[{"key":"A","text":"从最近的安全车门"},{"key":"B","text":"原地等待救援"},{"key":"C","text":"全部挤向驾驶员车门"},{"key":"D","text":"先取行李再下车"}]','A',
  '优先就近疏散，避免拥挤踩踏。','中',2,0,0),
 (8803000000000009,0,8802000000000002,'judge','发现火情后应先自行扑救，确认无法扑灭再报警。',
  '[{"key":"正确","text":"正确"},{"key":"错误","text":"错误"}]','错误',
  '报警与初期扑救应同步进行，不可延误报警。','易',3,0,0),
 (8803000000000010,0,8802000000000002,'multi','灭火器使用“提、拔、握、压”四步中包含哪些动作？',
  '[{"key":"A","text":"提起灭火器"},{"key":"B","text":"拔掉保险销"},{"key":"C","text":"握住喷管对准火焰根部"},{"key":"D","text":"压下压把"}]','A,B,C,D',
  '四步齐全并站在上风向。','中',4,0,0),
 (8803000000000011,0,8802000000000002,'judge','消防控制室值班人员应持证上岗并 24 小时在岗。',
  '[{"key":"正确","text":"正确"},{"key":"错误","text":"错误"}]','正确',
  '消防控制室须 24 小时专人持证值班。','易',5,0,0),

 -- 危化品运输安全（5 题）
 (8803000000000012,0,8802000000000003,'single','危险货物运输车辆发生泄漏，驾驶员第一时间应？',
  '[{"key":"A","text":"立即停车、断电、设置警戒"},{"key":"B","text":"继续行驶至服务区"},{"key":"C","text":"用水直接冲洗泄漏物"},{"key":"D","text":"下车吸烟查看"}]','A',
  '先控风险：停车断电、隔离现场、设置警戒。','易',1,0,0),
 (8803000000000013,0,8802000000000003,'single','报警时不需要重点说明的是？',
  '[{"key":"A","text":"危险货物品名与 UN 编号"},{"key":"B","text":"泄漏量与现场情况"},{"key":"C","text":"驾驶员当日行程安排"},{"key":"D","text":"有无人员伤亡"}]','C',
  '报警要素为品名、数量、位置、伤亡与险情。','中',2,0,0),
 (8803000000000014,0,8802000000000003,'multi','危化品泄漏现场应禁止哪些行为？',
  '[{"key":"A","text":"吸烟与使用明火"},{"key":"B","text":"拨打非防爆手机"},{"key":"C","text":"从上风向接近"},{"key":"D","text":"随意启动车辆"}]','A,B,D',
  '从上风向接近是正确做法，其余均需禁止。','中',3,0,0),
 (8803000000000015,0,8802000000000003,'judge','处置危化品泄漏时应从上风向接近现场。',
  '[{"key":"正确","text":"正确"},{"key":"错误","text":"错误"}]','正确',
  '上风向可避免吸入有毒有害气体。','易',4,0,0),
 (8803000000000016,0,8802000000000003,'judge','押运员在运输途中可以随意离开车辆。',
  '[{"key":"正确","text":"正确"},{"key":"错误","text":"错误"}]','错误',
  '押运员须全程监护，不得擅离。','易',5,0,0),

 -- 安全生产法律法规（5 题）
 (8803000000000017,0,8802000000000004,'single','新《安全生产法》确立的核心机制是？',
  '[{"key":"A","text":"全员安全生产责任制"},{"key":"B","text":"仅企业负责人负责"},{"key":"C","text":"仅安全管理人员负责"},{"key":"D","text":"政府全包"}]','A',
  '全员安全生产责任制要求人人有责、失职追责。','易',1,0,0),
 (8803000000000018,0,8802000000000004,'multi','“双重预防机制”包括哪两项？',
  '[{"key":"A","text":"安全风险分级管控"},{"key":"B","text":"隐患排查治理"},{"key":"C","text":"事故后追责"},{"key":"D","text":"年度评优"}]','A,B',
  '双重预防即风险分级管控 + 隐患排查治理。','中',2,0,0),
 (8803000000000019,0,8802000000000004,'judge','从业人员有权拒绝违章指挥和强令冒险作业。',
  '[{"key":"正确","text":"正确"},{"key":"错误","text":"错误"}]','正确',
  '这是《安全生产法》赋予从业人员的法定权利。','易',3,0,0),
 (8803000000000020,0,8802000000000004,'judge','从业人员发现事故隐患可以不报告，由安全员自行发现。',
  '[{"key":"正确","text":"正确"},{"key":"错误","text":"错误"}]','错误',
  '发现隐患应立即向现场安全管理人员或本单位负责人报告。','易',4,0,0),
 (8803000000000021,0,8802000000000004,'single','生产经营单位的主要负责人对本单位安全生产工作负？',
  '[{"key":"A","text":"全面负责"},{"key":"B","text":"部分负责"},{"key":"C","text":"不负责"},{"key":"D","text":"仅协助"}]','A',
  '主要负责人是本单位安全生产第一责任人。','易',5,0,0)
ON DUPLICATE KEY UPDATE `stem`=VALUES(`stem`);

-- ============================================================================
-- 种子：课程（对齐原型 course_data.js 的分类/行业/岗位字典）
-- ============================================================================
INSERT INTO `training_course`
 (`id`,`tenant_id`,`course_name`,`hours`,`category`,`industry`,`post`,`duration`,`source`,`scope`,`paper_id`,`video_desc`,`status`,`created_by`,`deleted`) VALUES
 (8804000000000001,0,'冬季行车安全要点',1,'安全行车规范,安全操作规程','普通货物运输','驾驶员','00:18','服务商','全部',8802000000000001,
  '冬季安全行车：出车前检查、冰雪路面降速增距、侧滑应急处置。','published',0,0),
 (8804000000000002,0,'防御性驾驶技巧',1,'安全行车规范,警示教育案例','普通货物运输','驾驶员,押运员','00:16','平台','全部',8802000000000001,
  '防御性驾驶四项核心：放眼远方、留有余地、引人注意、预估风险。','published',0,0),
 (8804000000000003,0,'危化品应急处置',1,'应急救援处置,道路运输业务知识','危险货物运输','驾驶员,押运员,装卸人员','00:20','平台','会员',8802000000000003,
  '危险货物泄漏处置：停车警戒、报警要素、个人防护、禁绝火源。','published',0,0),
 (8804000000000004,0,'消防控制室“四快”处置规程',1,'安全管理知识,应急救援处置','普通货物运输','管理人员,驾驶员','00:15','服务商','全部',8802000000000002,
  '消防控制室四快：快确认、快调集、快启动、快疏散。','published',0,0),
 (8804000000000005,0,'新《安全生产法》解读',1,'法律法规,安全管理知识','普通货物运输','管理人员,驾驶员','00:17','服务商','全部',8802000000000004,
  '全员安全生产责任制、双重预防机制、从业人员权利义务与违法处罚。','published',0,0)
ON DUPLICATE KEY UPDATE `course_name`=VALUES(`course_name`);

-- 课程-视频关联
INSERT INTO `training_course_video` (`id`,`tenant_id`,`course_id`,`video_id`,`sort_order`,`created_by`,`deleted`) VALUES
 (8805000000000001,0,8804000000000001,8801000000000001,1,0,0),
 (8805000000000002,0,8804000000000002,8801000000000002,1,0,0),
 (8805000000000003,0,8804000000000003,8801000000000003,1,0,0),
 (8805000000000004,0,8804000000000004,8801000000000004,1,0,0),
 (8805000000000005,0,8804000000000005,8801000000000005,1,0,0)
ON DUPLICATE KEY UPDATE `sort_order`=VALUES(`sort_order`);

-- ============================================================================
-- 种子：演示企业 5001 的「2026 年 11 月岗中安全教育培训」计划
-- 默认学时开关关闭（force_learn=0，随时可考），及格 60 分，可重考 3 次
-- ============================================================================
INSERT INTO `training_plan`
 (`id`,`tenant_id`,`plan_name`,`plan_type`,`cargo_type`,`year`,`month`,`learn_hours`,`force_learn`,`pass_score`,`max_retry`,
  `course_id`,`paper_id`,`video_count`,`description`,`status`,`created_by`,`deleted`)
VALUES
 (8806000000000001,5001,'2026年11月岗中安全教育培训','on_job','general',2026,11,2,0,60,3,
  8804000000000001,8802000000000001,3,'本月课程：冬季行车安全要点、防御性驾驶技巧、消防控制室“四快”处置规程；学完视频后参加在线考核。',
  'published',0,0)
ON DUPLICATE KEY UPDATE `plan_name`=VALUES(`plan_name`);

-- 计划-视频关联（3 段视频）
INSERT INTO `training_plan_video` (`id`,`tenant_id`,`plan_id`,`video_id`,`month`,`sort_order`,`created_by`,`deleted`) VALUES
 (8807000000000001,5001,8806000000000001,8801000000000001,11,1,0,0),
 (8807000000000002,5001,8806000000000001,8801000000000002,11,2,0,0),
 (8807000000000003,5001,8806000000000001,8801000000000004,11,3,0,0)
ON DUPLICATE KEY UPDATE `sort_order`=VALUES(`sort_order`);
