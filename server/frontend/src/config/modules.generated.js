// 由 tools/gen_biz_meta.py 自动生成，请勿手工编辑
export const bizModules = [
  {
    "code": "law",
    "title": "法律法规清单",
    "phase": "B7",
    "group": "基础档案",
    "catalog": "",
    "tables": [
      {
        "name": "law_catalog",
        "title": "法律法规清单（制度引用的法规）"
      }
    ]
  },
  {
    "code": "common",
    "title": "系统运维",
    "phase": "A2",
    "group": "系统管理",
    "catalog": "",
    "tables": [
      {
        "name": "sys_oper_log",
        "title": "全量操作日志（履职留痕）"
      },
      {
        "name": "sys_menu",
        "title": "菜单/权限（菜单树+按钮权限）"
      },
      {
        "name": "sys_dept",
        "title": "部门（系统内置）"
      },
      {
        "name": "sys_group",
        "title": "企业分组/车组"
      },
      {
        "name": "warn_rule",
        "title": "预警规则"
      },
      {
        "name": "warn_record",
        "title": "预警记录"
      },
      {
        "name": "biz_file",
        "title": "通用附件上传记录"
      }
    ]
  },
  {
    "code": "person",
    "title": "人员基础信息",
    "phase": "B1",
    "group": "基础档案",
    "catalog": "PERSON_FILE",
    "tables": [
      {
        "name": "person",
        "title": "人员主表（一人一档）"
      },
      {
        "name": "person_archive_dict",
        "title": "一人一档归档项字典"
      },
      {
        "name": "person_archive_item",
        "title": "一人一档归档子项"
      },
      {
        "name": "person_doc",
        "title": "人员证照/文件（子表）"
      },
      {
        "name": "org_leader",
        "title": "领导机构"
      },
      {
        "name": "org_leader_member",
        "title": "领导机构成员"
      },
      {
        "name": "org_compliance_check",
        "title": "合规校验结果"
      },
      {
        "name": "org_compliance_template",
        "title": "法规最低架构模板（内置）"
      },
      {
        "name": "onboarding",
        "title": "接入初始化状态"
      },
      {
        "name": "onboarding_status",
        "title": "安全管理现状（选择题答案）"
      }
    ]
  },
  {
    "code": "vehicle",
    "title": "车辆基础信息",
    "phase": "B2",
    "group": "基础档案",
    "catalog": "VEHICLE_FILE",
    "tables": [
      {
        "name": "vehicle",
        "title": "车辆主表（一车一档）"
      },
      {
        "name": "vehicle_doc",
        "title": "车辆证照/文件（子表）"
      },
      {
        "name": "vehicle_doc_dict",
        "title": "一车一档证照字典"
      },
      {
        "name": "vehicle_driver",
        "title": "车辆-驾驶员绑定"
      },
      {
        "name": "vehicle_insurance",
        "title": "车辆保险"
      },
      {
        "name": "vehicle_device",
        "title": "车辆设备（卫星定位等）"
      },
      {
        "name": "vehicle_grade",
        "title": "车辆技术等级评定"
      },
      {
        "name": "vehicle_link",
        "title": "车辆挂靠关系"
      },
      {
        "name": "veh_type",
        "title": "车型字典（平台预置）"
      },
      {
        "name": "tenant_veh_config",
        "title": "企业车辆配置"
      },
      {
        "name": "tenant_parking",
        "title": "停车场档案（独立页，一企业一条）"
      },
      {
        "name": "tenant_cert",
        "title": "企业证照（子表）"
      },
      {
        "name": "vehicle_repair",
        "title": "车辆维修保养（B2 补齐的既有设计缺口）"
      }
    ]
  },
  {
    "code": "institution",
    "title": "制度档案引擎",
    "phase": "B3",
    "group": "制度范本",
    "catalog": "BOX2",
    "tables": [
      {
        "name": "tpl_group",
        "title": "范本分组"
      },
      {
        "name": "tpl_spec_group",
        "title": "范本规格组字典"
      },
      {
        "name": "tpl_field_dict",
        "title": "范本字段字典"
      },
      {
        "name": "tpl_seal",
        "title": "印章库"
      },
      {
        "name": "tpl_signature",
        "title": "签字库（历史签字数据）"
      },
      {
        "name": "tpl_gen_record",
        "title": "范本生成记录"
      },
      {
        "name": "flow_config",
        "title": "审批配置（业务类型审批模板）"
      },
      {
        "name": "flow_node",
        "title": "审批节点"
      },
      {
        "name": "flow_process",
        "title": "审批流程实例"
      }
    ]
  },
  {
    "code": "check",
    "title": "一日三检",
    "phase": "B4",
    "group": "业务工具",
    "catalog": "BOX9",
    "tables": [
      {
        "name": "check_config",
        "title": "三检配置"
      },
      {
        "name": "check_item_dict",
        "title": "三检项目字典（平台预置）"
      },
      {
        "name": "check_task",
        "title": "三检任务（出车周期）"
      },
      {
        "name": "check_record",
        "title": "三检记录（出/行/收）"
      },
      {
        "name": "check_item_result",
        "title": "三检项目结果（明细）"
      },
      {
        "name": "check_remind",
        "title": "三检提醒"
      }
    ]
  },
  {
    "code": "danger",
    "title": "隐患排查治理",
    "phase": "B5",
    "group": "业务工具",
    "catalog": "BOX8",
    "tables": [
      {
        "name": "danger_class",
        "title": "隐患分类字典"
      },
      {
        "name": "danger_check_template",
        "title": "隐患排查模板"
      },
      {
        "name": "danger_record",
        "title": "隐患排查记录"
      },
      {
        "name": "danger_rectify",
        "title": "隐患整改"
      },
      {
        "name": "danger_accept",
        "title": "隐患验收"
      }
    ]
  },
  {
    "code": "meeting",
    "title": "安全会议",
    "phase": "B6",
    "group": "业务工具",
    "catalog": "BOX3",
    "tables": [
      {
        "name": "meeting",
        "title": "安全会议"
      },
      {
        "name": "meeting_attendee",
        "title": "会议签到/参会人"
      },
      {
        "name": "meeting_record",
        "title": "会议纪要"
      }
    ]
  },
  {
    "code": "collect",
    "title": "服务商代录",
    "phase": "B8",
    "group": "服务商",
    "catalog": "BOX1",
    "tables": [
      {
        "name": "collect_task",
        "title": "智能资料收集任务"
      },
      {
        "name": "collect_result",
        "title": "智能资料收集解析结果"
      }
    ]
  },
  {
    "code": "training",
    "title": "培训教育",
    "phase": "C1",
    "group": "二期业务",
    "catalog": "BOX4",
    "tables": [
      {
        "name": "training_plan",
        "title": "培训计划"
      },
      {
        "name": "training_plan_video",
        "title": "培训计划-视频关联"
      },
      {
        "name": "training_video",
        "title": "培训视频"
      },
      {
        "name": "training_question",
        "title": "题库"
      },
      {
        "name": "training_exam",
        "title": "考核记录"
      },
      {
        "name": "training_assign",
        "title": "培训指派"
      }
    ]
  },
  {
    "code": "emergency",
    "title": "应急管理",
    "phase": "C2",
    "group": "二期业务",
    "catalog": "BOX7",
    "tables": [
      {
        "name": "emergency_plan",
        "title": "应急预案"
      },
      {
        "name": "emergency_plan_tpl",
        "title": "应急范本模板（预置）"
      },
      {
        "name": "emergency_drill_plan",
        "title": "应急演练计划"
      },
      {
        "name": "emergency_drill",
        "title": "应急演练记录"
      },
      {
        "name": "emergency_drill_item",
        "title": "演练项目明细"
      },
      {
        "name": "emergency_drill_sign",
        "title": "演练签到"
      },
      {
        "name": "emergency_drill_photo",
        "title": "演练照片"
      },
      {
        "name": "emergency_duty",
        "title": "应急值守"
      },
      {
        "name": "emergency_duty_rule",
        "title": "应急值守规则"
      },
      {
        "name": "emergency_equipment",
        "title": "应急物资"
      },
      {
        "name": "emergency_team",
        "title": "应急队伍"
      },
      {
        "name": "emergency_team_member",
        "title": "应急队伍成员"
      }
    ]
  },
  {
    "code": "accident",
    "title": "事故与违法",
    "phase": "C3",
    "group": "二期业务",
    "catalog": "BOX10",
    "tables": [
      {
        "name": "accident_record",
        "title": "事故记录"
      },
      {
        "name": "accident_quick_report",
        "title": "事故快报"
      },
      {
        "name": "accident_service",
        "title": "事故增值服务"
      },
      {
        "name": "accident_year_summary",
        "title": "事故年度汇总"
      },
      {
        "name": "violation_dict",
        "title": "违法字典"
      },
      {
        "name": "monitor_violation",
        "title": "动态监控违规记录"
      }
    ]
  },
  {
    "code": "assess",
    "title": "考核奖惩",
    "phase": "C4",
    "group": "二期业务",
    "catalog": "BOX1",
    "tables": [
      {
        "name": "credit_assess",
        "title": "诚信考核"
      },
      {
        "name": "credit_assess_item",
        "title": "诚信考核项目"
      },
      {
        "name": "credit_reward_notice",
        "title": "考核奖惩公示"
      }
    ]
  },
  {
    "code": "sign",
    "title": "自定义文件签署",
    "phase": "C6",
    "group": "二期业务",
    "catalog": "BOX1",
    "tables": [
      {
        "name": "sign_task",
        "title": "自定义文件签署任务（C6）"
      }
    ]
  },
  {
    "code": "monitor",
    "title": "动态监控台账",
    "phase": "D1",
    "group": "三期业务",
    "catalog": "BOX6",
    "tables": [
      {
        "name": "monitor_ledger",
        "title": "动态监控台账留存"
      },
      {
        "name": "org_outsource",
        "title": "委托第三方监控标记"
      }
    ]
  },
  {
    "code": "budget",
    "title": "安全生产费用",
    "phase": "D2",
    "group": "三期业务",
    "catalog": "BOX5",
    "tables": [
      {
        "name": "budget_plan",
        "title": "安全费用计提计划"
      },
      {
        "name": "budget_expense",
        "title": "安全费用支出"
      },
      {
        "name": "budget_category",
        "title": "安全费用支出类别"
      }
    ]
  },
  {
    "code": "risk",
    "title": "风险分级管控",
    "phase": "D3",
    "group": "三期业务",
    "catalog": "BOX8",
    "tables": [
      {
        "name": "risk_point_dict",
        "title": "风险点字典"
      },
      {
        "name": "risk_list",
        "title": "风险清单"
      },
      {
        "name": "risk_card",
        "title": "风险卡"
      },
      {
        "name": "risk_manual",
        "title": "风险手册"
      },
      {
        "name": "org_post_content",
        "title": "org_post_content"
      }
    ]
  },
  {
    "code": "dangerspecial",
    "title": "危货专项深化",
    "phase": "D4",
    "group": "三期业务",
    "catalog": "BOX6",
    "tables": [
      {
        "name": "danger_special_ledger",
        "title": "危货六项专项台账统一表（D4）"
      },
      {
        "name": "danger_waybill",
        "title": "危险货物电子运单（D4，法律法规强制）"
      }
    ]
  },
  {
    "code": "platform",
    "title": "平台侧能力",
    "phase": "D5",
    "group": "平台管理",
    "catalog": "",
    "tables": [
      {
        "name": "sys_module_config",
        "title": "平台功能模块字典（D5，平台级无租户）"
      },
      {
        "name": "tenant_module",
        "title": "企业功能模块开通记录（D5）"
      },
      {
        "name": "sso_config",
        "title": "企业 SSO 配置登记（D5）"
      },
      {
        "name": "service_billing",
        "title": "增值服务计费账单（D5）"
      }
    ]
  }
]

export const menuGroups = [...new Set(bizModules.map(m => m.group))]
