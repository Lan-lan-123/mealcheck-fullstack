"""Write an auditable optimization report from actual k6 outputs."""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import re

p = argparse.ArgumentParser()
p.add_argument('evidence', type=Path)
p.add_argument('--baseline', type=Path, help='Optional original benchmark report')
args = p.parse_args()
root = args.evidence.resolve()
m = json.loads((root/'manifest.json').read_text(encoding='utf-8'))
phases = m['phases']
results = {v['name']:json.loads((root/(v['name']+'.json')).read_text(encoding='utf-8')) for v in phases}
samples = [json.loads(s) for s in (root/'resources.jsonl').read_text(encoding='utf-8').splitlines()]
target = root.parents[1]/'reports'/('k6-optimization-'+m['run']+'.md')
target.parent.mkdir(parents=True,exist_ok=True)
status_counts = {}
health_file = root/'final-health.json'
health = json.loads(health_file.read_text(encoding='utf-8')) if health_file.exists() else None
cleanup_note = m.get('cleanup') or '仍在执行'
if cleanup_note.startswith('Only the newly created'):
    cleanup_note = '仅删除本轮新建的 40 个测试账号及其关联数据'
health_note = '最终健康验证尚未保存'
if health:
    healthy = sum(c['health']=='healthy' for c in health['containers'])
    health_note = (f"{healthy}/{len(health['containers'])} 个容器健康，测试账号/记录/任务剩余 "
        f"{health['database']['testUsers']}/{health['database']['testRecords']}/{health['database']['testTasks']}，"
        f"待处理任务/未发布 Outbox {health['database']['allPending']}/{health['database']['unpublished']}")
for stage in phases:
    counts = Counter()
    with (root/(stage['name']+'.timeseries.jsonl')).open(encoding='utf-8') as stream:
        for line in stream:
            row = json.loads(line)
            if row.get('type')=='Point' and row.get('metric')=='http_reqs':
                tags = row['data']['tags']
                if tags.get('operation')=='enqueue':
                    counts[tags.get('status','unknown')] += row['data']['value']
    status_counts[stage['name']] = dict(counts)


def fmt(number):
    return '-' if number is None else f'{number:.2f}'


def val(data,key,field):
    return data['metrics'][key]['values'][field]


def verdict(stage):
    return '通过' if stage['exitCode']==0 else '未通过'


def queues(stage):
    scoped = [s['queues'] for s in samples if s['phase']==stage and 'queues' in s]
    ready = [q['messages_ready'] for s in scoped for q in s['rabbitmq'] if q['name']=='mealcheck.analysis.jobs']
    pending = [s['database']['pending'] for s in scoped]
    return max(ready,default=None),max(pending,default=None)


def memory_mib(value):
    match = re.fullmatch(r'([\d.]+)\s*(B|kB|KiB|MB|MiB|GB|GiB)', value.strip())
    if not match:
        raise ValueError('Unknown Docker memory unit: '+value)
    factors = {'B':1,'kB':1000,'KiB':1024,'MB':1000**2,'MiB':1024**2,
               'GB':1000**3,'GiB':1024**3}
    return float(match[1])*factors[match[2]]/1024**2


reads = [s for s in phases if s['name'].startswith('read-')]
images = [s for s in phases if not s['name'].startswith('read-') and s['name']!='seed']
lines = [f"# MealCheck 第二轮优化与 k6 复测（{m['run']}）",'',
    f"北京时间：{m['started']} 至 {m.get('ended','测试仍在运行')}。",'',
    '## 当前结论','']
if m.get('error'):
    lines += [f"本轮执行未完整结束：`{m['error']}`。下列结果只覆盖已完成场景。",'']
if 'entry-burst-20' in results:
    counts = status_counts['entry-burst-20']
    c = results['entry-burst-20']['capacity']
    lines += [f"- 扩大入口缓冲后的 20 张突发：HTTP 状态分布 `{json.dumps(counts)}`；接收 {c['accepted']}，成功 {c['succeeded']}，限流 {c['submit429']}，不确定 {c['unresolved']}。上轮同一规模全部 HTTP 500。"]
if args.baseline:
    assert args.baseline.is_file(), 'Baseline report does not exist'
    import os
    relative = Path(os.path.relpath(args.baseline.resolve(),target.parent)).as_posix()
    lines += [f"- [上一轮压测报告]({relative})。"]
if 'c2-arrival-20-long' in results and 'c4-arrival-20-long' in results:
    a=results['c2-arrival-20-long']['capacity']; b=results['c4-arrival-20-long']['capacity']
    lines += [f"- 20 张/分钟、持续 10 分钟对照：并发 2 的成功率 {fmt(a['successFraction']*100)}%，并发 4 为 {fmt(b['successFraction']*100)}%。成功任务 P95 分别为 {fmt(a['successfulE2eMs']['p(95)']/1000) if a['successfulE2eMs'] else '-'} / {fmt(b['successfulE2eMs']['p(95)']/1000) if b['successfulE2eMs'] else '-'} 秒。"]
    index = next(i for i,s in enumerate(phases) if s['name']=='c2-arrival-20-long')
    before = phases[index-1]['drain']['database']['statuses'].get('SUCCEEDED',0)
    after = phases[index]['drain']['database']['statuses'].get('SUCCEEDED',0)
    lines += [f"- 并发 2 停压并排空后的数据库成功数增加 {after-before}；这不改变 k6 中 {a['timedOut']} 个任务超过 180 秒观察上限的结论。后台最终完成不等于满足响应时间目标。"]
if 'c4-mixed-20-long' in results:
    data=results['c4-mixed-20-long']; c=data['capacity']
    lines += [f"- 50 VU 查询与 20 张/分钟图片上传同时运行 10 分钟：查询成功率 {fmt(val(data['k6'],'meal_list_success','rate')*100)}%，查询 P95 {fmt(val(data['k6'],'meal_list_duration','p(95)'))} ms；图片成功率 {fmt(c['successFraction']*100)}%，总体验收 {'通过' if c['passed'] else '未通过'}。"]
lines += ['', '## 已实施的改动','',
    '- 前端 Nginx `/tmp` 的默认上限由 32 MiB 提升为 256 MiB，容器默认内存上限由 128 MiB 提升为 512 MiB，临时空间可用 `FRONTEND_REQUEST_TMPFS_SIZE` 调整。',
    '- 容量测试环境默认消费者并发调整为 4；本轮在同一后端镜像上分别设置 2 和 4 对照。生产环境原有消费者默认并发 2 保留，需要结合真实供应商配额单独选择。',
    '- 测试账号 JWT 有效期设置为 180 分钟，避免长测试被令牌过期干扰；只作用于容量测试覆盖配置。',
    '- k6 增加多图片轮换、查询与图片同时执行的场景、HTTP 状态时间序列，以及可运行 10 分钟以上的进程等待上限。',
    '- 本轮创建 40 个独立账号，通过真实 API 各生成 1 条餐食记录，再仅为这些账号复制记录至每账号 1000 条，共 40000 条；复制后的记录是合成测试数据。','',
    '配置文件：[前端资源默认值](../../docker-compose.prod.yml)、[环境变量示例](../../.env.production.example)、[容量环境覆盖配置](../../deploy/compose.capacity.yml)。','',
    '## 查询复测','',
    '| VU | 业务请求 | 平均业务请求/秒 | P95 ms | P99 ms | 业务成功率 | 结论 |',
    '| ---: | ---: | ---: | ---: | ---: | ---: | --- |']
for s in reads:
    d=results[s['name']]
    lines += [f"| {s['parameters']['VUS']} | {int(val(d,'meal_list_requests','count'))} | {fmt(val(d,'meal_list_requests','rate'))} | {fmt(val(d,'meal_list_duration','p(95)'))} | {fmt(val(d,'meal_list_duration','p(99)'))} | {fmt(val(d,'meal_list_success','rate')*100)}% | {verdict(s)} |"]
lines += ['', '各档查询运行 60 秒，每个 VU 请求后等待 1 秒；业务指标排除账号预检查。每账号至少 1000 条记录，50 VU 共享 40 个账号。验收：P95 <500 ms，成功率与 checks >99%，HTTP 失败率 <1%。','',
    '## 图片与混合负载复测','',
    '| 场景 | 提交时长 s | 尝试/接收 | 成功 | 后台失败/拒绝 | 超时/不确定/中断 | 成功率 | 成功 P95 s | 成功 P99 s | 丢弃迭代 | 验收 |',
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
for s in images:
    c=results[s['name']]['capacity']; e=c['successfulE2eMs']
    assert c['attempts']==sum(c[k] for k in ['succeeded','failed','rejected','timedOut','unresolved','unfinished'])
    assert c['passed']==(s['exitCode']==0)
    lines += [f"| {s['name']} | {c['configuredDurationSeconds']} | {c['attempts']}/{c['accepted']} | {c['succeeded']} | {c['failed']}/{c['rejected']} | {c['timedOut']}/{c['unresolved']}/{c['unfinished']} | {fmt(c['successFraction']*100)}% | {fmt(e['p(95)']/1000) if e else '-'} | {fmt(e['p(99)']/1000) if e else '-'} | {c['droppedIterations']} | {verdict(s)} |"]
lines += ['', '图片验收：至少 1 个成功样本、成功率 ≥99%、成功任务 P95 <60 秒、无超时、不确定结果、未结束任务及丢弃迭代。端到端包含上传、排队、处理与轮询；成功分位数不包含失败样本。突发模式的“提交时长”是配置的执行时间上限，并不代表持续发送该时长。','',
    '入口修复与整条业务验收分开判断：没有 HTTP 500 但出现 HTTP 429，说明资源保护生效，仍不满足“20 个提交全部成功”的业务目标。限流不能包装成全部通过。','',
    '| 场景 | 上传 HTTP 状态分布 | 队列 Ready 采样峰值 | DB待处理采样峰值 | 结束后待处理/未发布Outbox |',
    '| --- | --- | ---: | ---: | --- |']
for s in images:
    r,q=queues(s['name']); drain=s.get('drain',{}).get('database',{})
    lines += [f"| {s['name']} | `{json.dumps(status_counts[s['name']])}` | {r if r is not None else '-'} | {q if q is not None else '-'} | {drain.get('pending','-')}/{drain.get('unpublished','-')} |"]
lines += ['', '失败阈值：','']
for s in images:
    failed = results[s['name']]['capacity']['failedThresholds']
    if failed:
        lines += [f"- `{s['name']}`：`{'；'.join(failed)}`。"]
lines += ['', '每阶段停止加压后等待待处理与未发布 Outbox 排空；超时只是压测端停止观察，后台仍可能完成。数据库状态累计包含之前阶段，入口解码限流也会留下 FAILED 记录；不能把累计 FAILED 数直接当作本阶段已接收任务的处理失败数。阶段业务结果以 k6 对该任务的观察分类为准。','',
    '## 资源采样','',
    '| 容器 | CPU 采样峰值 | 内存采样峰值 MiB | 内存占上限峰值 |',
    '| --- | ---: | ---: | ---: |']
for name in ['backend','frontend','postgres','rabbitmq','model-stub']:
    rows = [c for sample in samples for c in sample.get('containers',[])
            if c['Name']=='mealcheck-capacity-'+name+'-1']
    if rows:
        lines += [f"| {name} | {fmt(max(float(c['CPUPerc'].rstrip('%')) for c in rows))}% | {fmt(max(memory_mib(c['MemUsage'].split('/')[0]) for c in rows))} | {fmt(max(float(c['MemPerc'].rstrip('%')) for c in rows))}% |"]
errors = [s for s in samples if 'error' in s]
lines += ['',f"共 {len(samples)} 次采样，其中 {len(errors)} 次包含采样错误；峰值覆盖准备、运行和排空阶段。Docker CPU 百分比以单核为 100%，不是容器 CPU 配额占比。",'',
    '## 环境与证据范围','',f"- k6：`{m['k6Version']}`；Docker Engine：`{m['docker']['ServerVersion']}`。",
    f"- 前端实际内存上限：{m['frontend']['memoryBytes']//1024**2} MiB；实际 `/tmp`：`{m['frontend']['tmpfs']['/tmp']}`。",
    '- 后端固定 1.5 CPU、1 GiB 内存，图片解码并发保护维持原设置；消费者对照仅改变任务处理并发。',
    '- 持续及混合图片阶段使用本地模拟模型，每次调用延迟 5 秒、无故障注入；入口和字节边界阶段使用 0.05 秒模拟延迟，单独判断入口接收能力。',
    '- 标准上传使用约 2.5 MB、1254×1254 的合成 PNG。混合阶段轮换两张约 2.3–2.5 MB PNG 和 16×16 JPEG 小型协议验证样本，后者不是实际用户照片。',
    '- 10 MiB 字节边界样本在原 PNG 尾部填充零字节，视觉内容不变，用于验证输入字节上限，不代表真实大分辨率图片。边界阶段同时提交 5 张，不代表 20 张最大文件并发已经验证。',
    '- 压测端与服务端共用本机；资源为离散采样，短暂峰值可能未捕获。每次资源采样完成后等待 5 秒，每三次读取队列状态。',
    '- 本轮没有重建前后端镜像；同一镜像下对照并发，具体镜像 ID 见 manifest。结果不代表真实模型吞吐量、识别准确率或线上最大容量。',
    '- 真实模型验证未执行：本机没有真实模型凭据与供应商配置。','',
    '## 收尾与复现','',f"- 账号清理：{cleanup_note}。",
    f"- 模拟模型恢复：{m.get('modelRestored','尚未恢复')}；原参数 `{json.dumps(m['originalModel'])}`。",
    f"- 结束后验证：{health_note}。",
    '- 复测结束保留容量测试并发 4、优化后的前端资源限制。', '',
    '```powershell','python ./loadtests/run-capacity-optimization.py',
    f"python ./loadtests/write-optimization-report.py ./loadtests/results/{m['run']}",'```','',
    '## 原始资料','', f"- [参数、镜像与清理记录](../results/{m['run']}/manifest.json)",
    f"- [资源和队列采样](../results/{m['run']}/resources.jsonl)",
    f"- [入口突发时的 Nginx 日志](../results/{m['run']}/entry-proxy.log)",
    f"- [扩充测试账号记录的 SQL](../results/{m['run']}/seed-records.sql)"]
for s in phases:
    name=s['name']; lines += [f"- `{name}`：[汇总](../results/{m['run']}/{name}.json)、[日志](../results/{m['run']}/{name}.log)、[时间序列](../results/{m['run']}/{name}.timeseries.jsonl)，退出码 {s['exitCode']}。"]
for filename, label in [('final-health.json','结束后的健康与清理验证'),
                        ('config-validation.json','生产及容量配置默认值核对')]:
    if (root/filename).exists():
        lines += [f"- [{label}](../results/{m['run']}/{filename})。"]
lines += ['', '原始证据位于被 Git 忽略的目录；分享报告时需同时保存证据。报告不含 JWT 或密码。','']
target.write_text('\n'.join(lines),encoding='utf-8')
print(target)
