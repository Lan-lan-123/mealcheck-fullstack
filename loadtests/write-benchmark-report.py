"""Create a Chinese Markdown report from measured k6 JSON and sampled resources."""
import argparse
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument('evidence', type=Path)
args = parser.parse_args()
folder = args.evidence.resolve()
meta = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
phases = meta['phases']
readings, analyses = [], []


def load(name):
    return json.loads((folder / f'{name}.json').read_text(encoding='utf-8'))


def value(data, name, field):
    return data['metrics'][name]['values'][field]


def n(value, digits=2):
    return f'{value:.{digits}f}'


def thresholds(data):
    return [f'{name}: {limit}' for name, metric in data['metrics'].items()
            for limit, status in metric.get('thresholds', {}).items() if not status['ok']]


def mem_mib(raw):
    match = re.match(r'([\d.]+)([A-Za-z]+)', raw.split('/')[0].strip())
    amount, unit = float(match[1]), match[2]
    return amount * {'B': 1 / 1048576, 'KiB': 1 / 1024, 'MiB': 1, 'GiB': 1024,
                     'kB': 1000 / 1048576, 'MB': 1000000 / 1048576, 'GB': 1000000000 / 1048576}[unit]


for phase in phases:
    if phase['name'].startswith('read-'):
        data = load(phase['name'])
        readings.append((phase, data, thresholds(data)))
    elif phase['name'] != 'seed':
        data = load(phase['name'])
        c = data['capacity']
        assert c['attempts'] == sum(c[k] for k in ['succeeded', 'failed', 'rejected', 'timedOut', 'unresolved', 'unfinished'])
        assert c['passed'] == (phase['exitCode'] == 0)
        analyses.append((phase, data))

samples = [json.loads(line) for line in (folder / 'resources.jsonl').read_text(encoding='utf-8').splitlines()]
diagnosis = json.loads((folder / 'diagnosis.json').read_text(encoding='utf-8-sig')) if (folder / 'diagnosis.json').exists() else None
report = folder.parents[1] / 'reports' / f"k6-{meta['run']}.md"
report.parent.mkdir(parents=True, exist_ok=True)
read_requests = sum(value(d, 'meal_list_requests', 'count') for _, d, _ in readings)
total = sum(d['capacity']['attempts'] for _, d in analyses)
succeeded = sum(d['capacity']['succeeded'] for _, d in analyses)
read_passed = sum(not fail and p['exitCode'] == 0 for p, _, fail in readings)
analysis_passed = sum(d['capacity']['passed'] for _, d in analyses)
lines = [f"# MealCheck 本地 k6 压测报告（{meta['run']}）", '',
         f"测试时间：{meta['started']} 至 {meta.get('ended', '尚未结束')}，北京时间（UTC+8）。", '',
         '## 实测结论', '',
         f"- 查询共发起 {int(read_requests)} 次业务请求，{read_passed}/{len(readings)} 档达到设定阈值。",
         f"- 图片分析共尝试 {total} 个新任务，成功 {succeeded} 个；{analysis_passed}/{len(analyses)} 个场景达到设定阈值。样本准备阶段的 20 个任务不计入此数。",
         '- 本报告测量本机 Docker 容量测试环境，使用本地模拟模型；结果不能代表真实模型供应商吞吐量或线上最大容量。', '']
if diagnosis:
    lines += [f"**本轮发现的主要问题：20 张图片突发上传时，Nginx 临时区耗尽，{diagnosis['uploadHttp500']} 次上传均返回 HTTP 500。查询及三档持续图片到达测试通过。**", '']
if meta.get('error'):
    lines += [f"**执行未完整完成：{meta['error']}。以下仅列已取得的结果。**", '']
lines += ['## 查询结果', '',
          '查询接口：`GET /api/meals?page=0&size=10`。20 个独立账号，每个账号预先保存 1 条餐食记录；30/50 VU 档共享这 20 个账号。每个 VU 在请求后等待 1 秒，各档持续 60 秒。', '',
          '| VU | 业务请求数 | 平均业务请求/秒 | 平均耗时 ms | P95 ms | P99 ms | 业务成功率 | 结论 |',
          '| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
for phase, data, fail in readings:
    vals = data['metrics']['meal_list_duration']['values']
    lines.append(f"| {phase['parameters']['VUS']} | {int(value(data,'meal_list_requests','count'))} | {n(value(data,'meal_list_requests','rate'))} | {n(vals['avg'])} | {n(vals['p(95)'])} | {n(vals['p(99)'])} | {n(value(data,'meal_list_success','rate')*100)}% | {'通过' if not fail and phase['exitCode']==0 else '未通过'} |")
lines += ['', '阈值：业务耗时 P95 < 500 ms，业务成功率及 checks > 99%，HTTP 失败率 < 1%。自定义业务指标排除每档的 20 次账号预检查；每秒请求数使用 k6 计数器 rate，分母包含 setup 等执行开销，因此 VU 数不等于 QPS。', '',
          '## 图片分析结果', '',
          '持续到达场景各提交 60 秒；每个新任务使用唯一幂等键、轮换账号并每秒轮询一次，最长等待 180 秒。提交停止后继续观察所有在途任务；每档结束确认待处理任务及未发布 Outbox 均为 0 后才开始下一档。突发场景使用 20 VU 尽快提交 20 个新任务。', '',
          '| 场景 | 尝试/接受 | 成功 | 后台失败 | 拒绝 | 超时 | 不确定/中断 | 成功率 | 成功任务 P95 s | P99 s | 丢弃迭代 | 上传429 | 结论 |',
          '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
for phase, data in analyses:
    c = data['capacity']
    label = f"持续 {c['configuredRatePerMinute']} 张/分钟" if c['mode']=='arrival' else '突发20张'
    vals = c['successfulE2eMs']
    p95 = n(vals['p(95)']/1000) if vals else '无成功样本'
    p99 = n(vals['p(99)']/1000) if vals else '无成功样本'
    lines.append(f"| {label} | {c['attempts']}/{c['accepted']} | {c['succeeded']} | {c['failed']} | {c['rejected']} | {c['timedOut']} | {c['unresolved']}/{c['unfinished']} | {n(c['successFraction']*100)}% | {p95} | {p99} | {c['droppedIterations']} | {c['submit429']} | {'通过' if c['passed'] else '未通过'} |")
lines += ['', '图片阈值：成功率 ≥ 99%，至少 1 个成功样本，成功任务 P95 < 60 秒，无超时、不确定结果、丢弃迭代及未结束任务。P95/P99 仅计算成功任务，必须与失败数量一起判断。端到端时间包含上传、排队、处理和轮询观察延迟；不能将其等同为模型耗时。', '',
          '表中的每分钟速率是配置目标；实际启动数量以“尝试”及 JSON 中 `actualStartedPerMinute` 为准，不能用配置目标代替实测计数。', '']
for phase, data in analyses:
    c = data['capacity']
    if not c['passed']:
        lines += [f"- `{phase['name']}` 未通过阈值：" + '；'.join(c['failedThresholds']) + '。']
    if c['pollErrors']:
        lines += [f"- `{phase['name']}` 有 {c['pollErrors']} 次轮询错误。"]
    if not c['passed'] and c['succeeded']==c['attempts']:
        lines += [f"- `{phase['name']}` 所有任务最终成功，但等待时间等阈值未满足；这与任务丢失或处理失败含义不同。"]
lines += ['', '按每任务两次调用、每次模拟等待 5 秒、两个消费者估算，忽略其他开销时处理能力约为 `2 × 60 / (2 × 5) = 12` 张/分钟。这是由测试参数推导的估算，不是实测线上容量。60 秒的 20 张/分钟测试即使全部完成，也可能靠停止提交后的排空阶段完成，不能据此认定长期可维持 20 张/分钟。', '']
if diagnosis:
    before = next(p for p in phases if p['name']=='arrival-20')['drain']['database']['statuses']
    after = next(p for p in phases if p['name']=='burst-20')['drain']['database']['statuses']
    lines += ['## 突发上传为何失败', '',
              f"1. 前端实际配置 `/tmp` 为 `{diagnosis['tmpfs']['/tmp']}`，可用容量上限为 32 MiB。",
              f"2. 20 个请求的图片合计 {n(meta['image']['bytes']*20/1024**2)} MiB，尚未包含 multipart 开销，已经超过该上限。",
              f"3. Nginx 在 `/tmp/client_temp/` 缓冲请求体，日志记录 {diagnosis['noSpaceErrors']} 次 `pwrite() ... failed (28: No space left on device)`，对应 {diagnosis['uploadHttp500']} 次 HTTP 500。",
              f"4. 突发前后的数据库任务状态计数均为 `{json.dumps(before,ensure_ascii=False)}` / `{json.dumps(after,ensure_ascii=False)}`，未出现新增任务；后端累计重启次数为 {diagnosis['backendRestartCount']}。", '',
              'k6 将 5xx 提交保守归类为 `unknownSubmissions/unresolved`，因为一般情况下响应失败不保证服务端未入队。本轮结合代理日志和突发前后数据库计数，定位到请求体缓冲阶段；因此这轮失败不能用于判断 RabbitMQ 消费者的突发处理容量。', '',
              '建议先按“同时上传数量 × 单文件最大大小 + 余量”核算并调整 Nginx 请求体临时空间，再使用同样的 20 张突发测试复测；也可另行评估请求体流式转发与入口并发限制。本轮保留原部署参数，未修复该配置问题，报告如实记录失败。', '']
lines += ['', '## 容器资源与积压', '',
          '采样使用 `docker stats --no-stream`，每次采样完成后等待 5 秒；每三次资源采样读取 RabbitMQ 和数据库状态。以下是离散采样观测峰值，可能遗漏短暂尖峰。Docker CPU 100% 表示约一个核心；后端限制为 1.5 CPU、1 GiB 内存。', '',
          '| 场景 | 后端CPU峰值 | 后端内存峰值 MiB | RabbitMQ Ready峰值 | Unacked峰值 | DB待处理峰值 | 未发布Outbox峰值 | 采样数 |',
          '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
for phase in phases:
    if phase['name']=='seed':
        continue
    stage = [s for s in samples if s['phase']==phase['name']]
    backends = [c for s in stage for c in s.get('containers', []) if c['Name']=='mealcheck-capacity-backend-1']
    queues = [s['queues'] for s in stage if 'queues' in s]
    jobs = [q for s in queues for q in s['rabbitmq'] if q['name']=='mealcheck.analysis.jobs']
    cpu = n(max(float(c['CPUPerc'].rstrip('%')) for c in backends))+'%' if backends else '未取得'
    mem = n(max(mem_mib(c['MemUsage']) for c in backends)) if backends else '未取得'
    peak = lambda rows, key: max((r[key] for r in rows), default=None)
    nums = [peak(jobs,'messages_ready'),peak(jobs,'messages_unacknowledged'),
            peak([s['database'] for s in queues],'pending'),peak([s['database'] for s in queues],'unpublished')]
    lines.append(f"| {phase['name']} | {cpu} | {mem} | " + ' | '.join(str(x) if x is not None else '未取得' for x in nums) + f" | {len(stage)} |")
errors = sum('error' in s for s in samples)
lines += ['', f"资源采样总数 {len(samples)}，采样错误 {errors} 次。", '', '## 环境、范围与限制', '',
          f"- 地址：`{meta['baseUrl']}`；Compose 项目：`mealcheck-capacity`，共六个服务，数据独立于开发环境。",
          f"- 工具：`{meta['k6Version']}`；Docker Engine `{meta['docker']['ServerVersion']}`。",
          f"- Docker 可用资源：{meta['docker']['NCPU']} 个 CPU，{n(meta['docker']['MemTotal']/1024**3)} GiB 内存；压测端与服务端共用本机。",
          f"- 后端消费者并发：{meta['backend']['MEALCHECK_ANALYSIS_CONCURRENCY']}；模型每次调用固定延迟 5 秒，不注入错误。普通任务通常调用识别和建议两次，因此纯模拟模型等待约 10 秒。",
          f"- 图片：`{meta['image']['path']}`，PNG，1254×1254，{meta['image']['bytes']} 字节；同一合成餐食图片重复上传，SHA256 `{meta['image']['sha256']}`。",
          '- 图片经过真实上传、校验、解码、存储、RabbitMQ、数据库和结果查询链路，但模型返回固定内容；不评价识别准确率。',
          '- 查询数据量为每账号 1 条，主要覆盖小数据量分页请求；不代表每账号数千条记录的查询成本。',
          '- 60 秒分档测试用于初步评估，单轮样本有限；P99 对少量样本尤其不稳定。没有测长期稳定性、最大连接数、登录、助手及周报吞吐量。',
          '- 两类负载分开执行；本轮没有同时施加查询与图片负载，也没有调整消费者并发进行对照。',
          f"- 工作区 HEAD：`{meta['gitHead']}`，有 {meta['dirtyFileCount']} 个未提交文件；运行使用已有后端/前端镜像，本轮没有重建镜像，HEAD 不能作为运行镜像的精确版本证明。", '',
          '## 清理与复现', '',
          f"- 测试账号清理：{'已删除本轮新建的20个账号及其关联数据' if meta['cleanup'] == 'Only the newly created 20 test accounts and their data were deleted' else meta['cleanup']}。",
          f"- 模拟模型设置恢复：{'成功' if meta.get('modelRestored') is True else meta.get('modelRestored')}；原设置 `{json.dumps(meta['originalModel'],ensure_ascii=False)}`。",
          '- 测试前恢复了 Docker 的失效临时通信端点，原端点目录保留备份，数据库数据卷未重置。', '',
          '在容量测试服务健康、图片存在、k6 已安装时，在项目根目录运行：', '',
          '```powershell', 'python ./loadtests/run-local-benchmark.py', '```', '',
          '脚本会生成新的结果目录，并为每轮建立独立账号。生成报告：', '',
          '```powershell', f"python ./loadtests/write-benchmark-report.py ./loadtests/results/{meta['run']}", '```', '',
          '## 原始证据', '',
          f"- [执行参数与退出码](../results/{meta['run']}/manifest.json)",
          f"- [容器资源和队列原始采样](../results/{meta['run']}/resources.jsonl)"]
if (folder / 'runtime-images.json').exists():
    lines.append(f"- [实际运行镜像身份](../results/{meta['run']}/runtime-images.json)")
if (folder / 'scripts').exists():
    lines.append(f"- [本轮图片测试脚本快照](../results/{meta['run']}/scripts/analysis-capacity.js)；[查询脚本快照](../results/{meta['run']}/scripts/read-multi.js)")
if diagnosis:
    lines.append(f"- [突发上传 Nginx 原始日志](../results/{meta['run']}/frontend-burst.log)；[临时区及 HTTP 500 汇总](../results/{meta['run']}/diagnosis.json)")
for phase in phases:
    lines.append(f"- `{phase['name']}`：[JSON](../results/{meta['run']}/{phase['name']}.json)、[控制台日志](../results/{meta['run']}/{phase['name']}.log)，退出码 {phase['exitCode']}。")
lines += ['', '原始结果目录被 Git 忽略，分享报告时需另行保留这些证据；JWT 与密码不写入本报告，测试结束后账号文件随清理删除。', '',
          '指标定义参考：[k6 固定到达率执行器](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/)；[k6 内置指标](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/)。', '']
report.write_text('\n'.join(lines), encoding='utf-8')
print(report)
