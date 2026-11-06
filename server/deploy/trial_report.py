#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""E3 试运行报告生成器。

从平台反馈闭环接口读取真实运行数据，结合 trial_run.sh 产出的四条验收标准
演练结果，生成 Markdown 报告。仅用标准库，无额外依赖。

用法：
  python3 trial_report.py --base http://localhost:8080 --token <accessToken> \
      --trial /tmp/trial_result.json --out ../../docs/试运行演练报告.md
  不带 --out 时输出到 stdout。
"""
import argparse
import json
import sys
import urllib.request
from datetime import datetime


def api_get(base, path, token):
    req = urllib.request.Request(
        base.rstrip('/') + path,
        headers={'Authorization': 'Bearer ' + token},
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.load(resp)


def table(headers, rows):
    out = ['| ' + ' | '.join(headers) + ' |',
           '|' + '|'.join(['---'] * len(headers)) + '|']
    for r in rows:
        out.append('| ' + ' | '.join(str(x) for x in r) + ' |')
    return '\n'.join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default='http://localhost:8080')
    ap.add_argument('--token', required=True)
    ap.add_argument('--trial', default='')
    ap.add_argument('--out', default='')
    args = ap.parse_args()

    try:
        summary = api_get(args.base, '/api/feedback/summary', args.token)['data']
        opened = api_get(args.base, '/api/feedback/open', args.token)['data']
    except Exception as e:
        print(f'❌ 读取反馈数据失败：{e}', file=sys.stderr)
        return 1

    trial = {}
    if args.trial:
        try:
            with open(args.trial, encoding='utf-8') as f:
                trial = json.load(f)
        except Exception as e:
            print(f'⚠️  演练结果读取失败：{e}', file=sys.stderr)

    now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    by_status = summary.get('byStatus', {})
    by_type = summary.get('byType', {})
    by_module = summary.get('byModule', [])
    total = summary.get('total', 0)
    rate = summary.get('resolveRate', 0)

    L = []
    L.append('# 种子客户试运行演练报告')
    L.append('')
    L.append(f'> 生成时间：{now}  ')
    L.append('> 数据来源：`/api/feedback/summary`、`/api/feedback/open` 接口实时读取  ')
    L.append('> **说明**：本次为沙箱内 `trial_run.sh` 演练，反馈数据由脚本模拟种子客户提交，')
    L.append('> 用于验证闭环机制可用，**不是真实种子客户的反馈**。正式试运行需替换为真实客户数据后重跑本脚本。  ')
    L.append('> 对应计划：E3 用户手册与试运行')
    L.append('')
    L.append('## 一、试运行概况')
    L.append('')
    L.append(table(['指标', '数值'], [
        ['反馈总数', total],
        ['待受理', by_status.get('pending', 0)],
        ['处理中', by_status.get('processing', 0)],
        ['已解决', by_status.get('resolved', 0)],
        ['已驳回', by_status.get('rejected', 0)],
        ['已闭环合计', summary.get('closed', 0)],
        ['未闭环合计', summary.get('open', 0)],
        ['解决率', f'{rate}%'],
    ]))
    L.append('')
    L.append('## 二、反馈分布')
    L.append('')
    L.append('### 按类型')
    L.append('')
    type_cn = {'bug': '缺陷', 'suggestion': '建议', 'praise': '表扬', 'question': '咨询'}
    L.append(table(['类型', '数量'], [[type_cn.get(k, k), v] for k, v in by_type.items()]))
    L.append('')
    L.append('### 按模块')
    L.append('')
    L.append(table(['模块', '数量'], [[m.get('module') or '未分类', m.get('cnt')] for m in by_module]) or '（暂无）')
    L.append('')
    L.append('## 三、未闭环清单（需上线前处理）')
    L.append('')
    status_cn = {'pending': '待受理', 'processing': '处理中'}
    severity_cn = {1: '致命', 2: '一般', 3: '轻微'}
    if opened:
        L.append(table(['ID', '模块', '级别', '状态', '内容'], [
            [o.get('id'), o.get('module') or '未分类',
             severity_cn.get(o.get('severity'), '-'),
             status_cn.get(o.get('status'), o.get('status')),
             (o.get('content') or '')[:60]]
            for o in opened
        ]))
    else:
        L.append('✅ 无未闭环反馈。')
    L.append('')
    L.append('## 四、四条验收标准演练')
    L.append('')
    standards = trial.get('standards', [])
    if standards:
        L.append(table(['验收标准', '操作路径', '实测耗时', '判定'], [
            [s.get('name'), s.get('path'), f"{s.get('elapsedMs')} ms", s.get('result')]
            for s in standards
        ]))
    else:
        L.append('（本次未传入演练结果，正式复测见 E4 `go_live_check.sh`）')
    L.append('')
    L.append('## 五、结论与上线建议')
    L.append('')
    fatal_open = [o for o in opened if o.get('severity') == 1]
    if fatal_open:
        L.append(f'- ⛔ **存在 {len(fatal_open)} 条致命级未闭环反馈，不建议上线**，须先修复并回归。')
    elif summary.get('open', 0) > 0:
        L.append(f'- ⚠️ 仍有 {summary.get("open")} 条反馈未闭环（无致命级），建议列入上线后 7 日内处理清单，并在《上线检查清单》登记责任人。')
    else:
        L.append('- ✅ 全部反馈已闭环。')
    L.append(f'- 反馈解决率 **{rate}%**；试运行阶段反馈闭环机制（提交→受理→解决/驳回→统计→报告）已实操验证。')
    L.append('- 上线前置项仍包含 E1 标注的真实外部资源：域名与备案、CA 证书、生产服务器、告警 webhook、异地备份目标，详见 E4《上线检查清单》。')
    L.append('')

    text = '\n'.join(L)
    if args.out:
        with open(args.out, 'w', encoding='utf-8') as f:
            f.write(text)
        print(f'✅ 报告已生成：{args.out}')
        print(f'   反馈 {total} 条，解决率 {rate}%，未闭环 {summary.get("open", 0)} 条，'
              f'四条标准演练 {len(standards)} 项')
    else:
        print(text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
