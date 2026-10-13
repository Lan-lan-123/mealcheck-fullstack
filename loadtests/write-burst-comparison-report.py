"""Report real burst admission and consumer comparisons without hiding rejections."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re

parser=argparse.ArgumentParser()
parser.add_argument('evidence',type=Path)
parser.add_argument('--memory-evidence',type=Path)
parser.add_argument('--confirmation-evidence',type=Path)
args=parser.parse_args()
root=args.evidence.resolve()
m=json.loads((root/'manifest.json').read_text(encoding='utf-8'))
stages=m['phases']
wait_ms=int(m['runtimeBefore'][0]['settings']['MEALCHECK_UPLOAD_DECODE_WAIT_MILLIS'])
data={s['name']:json.loads((root/(s['name']+'.json')).read_text(encoding='utf-8')) for s in stages}
samples=[json.loads(line) for line in (root/'resources.jsonl').read_text(encoding='utf-8').splitlines()]
initial_jvm = m.get('runtimeByConcurrency',[{'containers':m['runtimeBefore']}])[0]['containers'][0]['settings'].get('JAVA_TOOL_OPTIONS')
if initial_jvm is None and args.memory_evidence:
    initial_jvm=json.loads((args.memory_evidence/'manifest.json').read_text(encoding='utf-8'))['runtimeBefore'][0]['settings'].get('JAVA_TOOL_OPTIONS')
target=root.parents[1]/'reports'/f"k6-burst-comparison-{m['run']}.md"
target.parent.mkdir(exist_ok=True)

def f(value): return '-' if value is None else f'{value:.2f}'
def metric(d,key,field): return d['k6']['metrics'][key]['values'][field]
def pending(name):
    values=[s['queues']['database']['pending'] for s in samples if s['phase']==name and 'queues' in s]
    return max(values,default=None)
def memory(value):
    n,unit=re.fullmatch(r'([\d.]+)\s*(B|kB|KiB|MB|MiB|GB|GiB)',value.strip()).groups()
    return float(n)*{'B':1,'kB':1000,'KiB':1024,'MB':1000**2,'MiB':1024**2,'GB':1000**3,'GiB':1024**3}[unit]/1024**2

statuses={}
for s in stages:
    counts=Counter()
    with (root/(s['name']+'.timeseries.jsonl')).open(encoding='utf-8') as stream:
        for line in stream:
            row=json.loads(line)
            if row.get('type')=='Point' and row.get('metric')=='http_reqs' and row['data']['tags'].get('operation')=='enqueue':
                counts[row['data']['tags'].get('status','unknown')]+=row['data']['value']
    statuses[s['name']]=dict(counts)

lines=[f"# 上传突发优化及消费者并发 2 / 4 对照（{m['run']}）",'',
    f"北京时间：{m['started']} 至 {m.get('ended','测试仍在运行')}。",'',
    '## 结论','']
if m.get('error'): lines += [f"本轮未完整执行：`{m['error']}`。结果仅覆盖已完成阶段。",'']
required=[f'c4-burst-20-r{n}' for n in [1,2]]+['c4-arrival-20-long','c4-mixed-20']
required += ['c4-entry-20','c4-boundary-20']
if all(name in data for name in required):
    assert len({row['imageId'] for row in m['configurations']}) == 1, 'Consumer groups must use the same backend image'
    good=all(data[name]['capacity']['passed'] for name in required)
    lines += [('本轮建议容量环境采用消费者并发 **4**：两轮 20 张突发、十分钟持续上传和混合阶段全部达标。' if good else
               '并发 4 仍存在未达标场景，不能据此宣布容量目标已实现；具体失败阈值如下。'),'']
if args.confirmation_evidence:
    cc=json.loads((args.confirmation_evidence/'c4-arrival-20-long.json').read_text(encoding='utf-8'))['capacity']
    lines += [f"最终容量环境保持消费者并发 4、解码并发 2、等待位置 20、等待上限 5 秒、JVM 堆占比 60%。明确 IPv4 的最终十分钟复测 {cc['succeeded']}/{cc['attempts']} 成功，P95 {f(cc['successfulE2eMs']['p(95)']/1000)} 秒。中间补测的两次建连超时完整保留在后文，不并入成功轮次。",'',
        '大图补测内存峰值仍约 965 MiB，1 GiB 容器余量有限；并发 4 的吞吐建议不等于已验证生产内存和真实模型配额。','']
for concurrency in [2,4]:
    key=f'c{concurrency}-arrival-20-long'
    if key in data:
        c=data[key]['capacity']; e=c['successfulE2eMs']
        lines += [f"- 并发 {concurrency}，20 张/分钟持续十分钟：{c['attempts']} 次上传，接收 {c['accepted']}，观察到成功 {c['succeeded']}，超时 {c['timedOut']}，成功率 {f(c['successFraction']*100)}%，成功任务 P95 {f(e['p(95)']/1000) if e else '-'} 秒，待处理采样峰值 {pending(key)}。"]
    key=f'c{concurrency}-mixed-20'
    if key in data:
        d=data[key]
        lines += [f"- 并发 {concurrency} 的混合阶段：50 VU 查询与每分钟 20 张上传同时运行两分钟，查询 {int(metric(d,'meal_list_requests','count'))} 次，成功率 {f(metric(d,'meal_list_success','rate')*100)}%，查询 P95 {f(metric(d,'meal_list_duration','p(95)'))} ms。"]
lines += ['', '## 已完成的实现','',
    f'- 图片解码并发维持 2，增加最多 20 个等待位置、公平的限时等待，本轮等待上限 {wait_ms} ms；超时、异常和线程中断均释放占用，中断状态保留。',
    '- Spring multipart 临时文件改用独立磁盘目录 `/app/upload-temp`，与持久图片目录分开。解析前最多预留 22 个上传请求，每个按最大请求 12 MiB 加 64 KiB 余量预留，应用临时空间预算 1 GiB，并检查磁盘可用空间。预算是单实例的应用准入限制，不是文件系统配额；现有文件和预留保守叠加。',
    '- 正常请求结束由 multipart 解析器清理临时文件；启动时仅清理专用目录中超过一小时的 `upload_*.tmp`，不清理持久餐食图片。',
    '- Nginx 在接收请求体之前限制同时处理最多 20 个 multipart 请求，读取接口不占名额；20×12 MiB=240 MiB，适配默认 256 MiB 缓冲。调整缓冲容量时需同时核对该上限。',
    '- 入口繁忙返回 `429 / UPLOAD_BUSY` 和 `Retry-After: 1`；普通用户配额仍是原限流错误。解码阶段的拒绝独立记录为 `UPLOAD_BUSY`，不再归类为普通执行失败。',
    '- 前端只对明确的 `429 / UPLOAD_BUSY` 自动重试最多两次，遵循 Retry-After、加入随机延迟并保留原图片、目标和幂等键；取消等待会停止后续提交。网络不确定结果、5xx、鉴权错误和普通配额限流不自动重交。','',
    '限流键和空键行为依据 [Nginx 官方文档](https://nginx.org/en/docs/http/ngx_http_limit_conn_module.html)；multipart 落盘目录及阈值依据 [Spring Boot 3.3 文档](https://docs.spring.io/spring-boot/3.3/api/java/org/springframework/boot/autoconfigure/web/servlet/MultipartProperties.html)。','',
    '## 测试设置与证据范围','',
    '- 40 个本轮独立账号，每账号 1,000 条由真实 API 种子复制的合成餐食记录，共 40,000 条；每阶段结束等待后台任务排空。',
    f'- 两组使用同一后端镜像、1.5 CPU、1 GiB 容器内存；解码 2、等待 20、限时 {wait_ms/1000:g} 秒保持一致，只切换消费者并发 2 / 4。',
    '- 入口、边界、过载、大分辨率用例使用每次调用延迟 0.05 秒的模型替身。消费者对照使用每次调用延迟 5 秒的模型替身；常规任务通常包含两次调用。每组先预热。',
    '- 标准样本约 2.5 MB、1254×1254 合成 PNG。10 MiB 边界样本通过尾部补零构造，验证字节边界；4096×4096 JPEG 由合成原图放大，验证解码工作量。混合阶段轮换两张 PNG 与大分辨率 JPEG。',
    '- k6 不自动重试上传，拒绝与超时均计入结果。两分钟混合场景用于比较并行读写，长期稳定性由十分钟持续上传场景补充，不能把两分钟混合结果称为长期混合容量。',
    '- 真实模型未执行：本机没有真实模型凭据和配额配置。压测端与服务端共用本机，结果不代表真实供应商容量、识别质量或线上最大吞吐。','',
    '## 回归验证','',
    f"- 后端单元测试 {m['verification']['surefire-reports']['tests']} 项，失败 {m['verification']['surefire-reports']['failures']}，错误 {m['verification']['surefire-reports']['errors']}，跳过 {m['verification']['surefire-reports']['skipped']}；数据库与 RabbitMQ 指定集成测试 {m['verification']['failsafe-reports']['tests']} 项通过。5 秒参数调整后补跑 42 项上传、安全与任务相关回归。",
    f"- 前端 {m['verification']['frontendTests']['tests']} 项测试通过，生产构建成功；实际执行 k6 的脚本验证器 {m['verification']['k6Verifier']['tests']} 项通过，包括过载允许拒绝时仍不得掩盖已接收任务失败。",
    '- 后端完整验证命令：`mvn -q -Pintegration "-Dit.test=MealAnalysisDatabaseIntegrationTest,MealAnalysisRabbitDatabaseIntegrationTest" verify`；前端：`npm test`、`npm run build`；k6 验证器：`python loadtests/verify_analysis_capacity.py`。','',
    '## 各阶段结果','',
    '| 场景 | 尝试/接收/成功 | 429（入口繁忙） | 后台失败/超时/不确定/中断 | 接收上传 P95 ms | 成功 E2E P95 s | 丢弃迭代 | 验收 |',
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
for s in stages:
    if s['name']=='seed' or 'warmup' in s['name']: continue
    c=data[s['name']]['capacity']; e=c['successfulE2eMs']; u=c.get('acceptedUploadMs')
    assert c['attempts']==sum(c[k] for k in ['succeeded','failed','rejected','timedOut','unresolved','unfinished'])
    assert c['passed']==(s['exitCode']==0)
    lines += [f"| {s['name']} | {c['attempts']}/{c['accepted']}/{c['succeeded']} | {c['submit429']}（{c.get('uploadBusy',0)}） | {c['failed']}/{c['timedOut']}/{c['unresolved']}/{c['unfinished']} | {f(u['p(95)']) if u else '-'} | {f(e['p(95)']/1000) if e else '-'} | {c['droppedIterations']} | {'通过' if c['passed'] else '未通过'} |"]
lines += ['', '正常场景验收：成功率 ≥99%、成功任务 P95 <60 秒、无超时、不确定结果、中断或丢弃迭代。`overload-50` 与 `large-10` 是明确的饱和测试，允许最多 80% 拒绝，已接收任务仍须成功且不能超时；它们通过不表示全部请求被接收。接收上传 P95 仅包含有效提交响应，成功 E2E P95 排除失败样本，必须结合全部数量判断。','',
    '| 场景 | 上传 HTTP 状态分布 | 待处理采样峰值 | 排空后待处理/未发布 Outbox |',
    '| --- | --- | ---: | --- |']
for s in stages:
    if s['name']=='seed' or 'warmup' in s['name']: continue
    d=s.get('drain',{}).get('database',{})
    lines += [f"| {s['name']} | `{json.dumps(statuses[s['name']])}` | {pending(s['name']) if pending(s['name']) is not None else '-'} | {d.get('pending','-')}/{d.get('unpublished','-')} |"]
lines += ['', '超时表示 k6 观察上限已到，后台可能最终完成；数据库累计 FAILED 也可能包含入口拒绝，不能混作已接收任务的执行失败。采样峰值包含该阶段的排空等待，短暂峰值可能漏采。','', '失败阈值：','']
for s in stages:
    failed=data[s['name']]['capacity']['failedThresholds']
    if failed: lines += [f"- `{s['name']}`：`{'；'.join(failed)}`。"]
lines += ['', '## 资源采样','', '| 并发 | 后端 CPU 峰值 | 后端内存峰值 MiB | 前端内存峰值 MiB |','| ---: | ---: | ---: | ---: |']
for concurrency in [2,4]:
    rows=[c for s in samples if s['phase'].startswith(f'c{concurrency}-') for c in s.get('containers',[])]
    back=[c for c in rows if c['Name'].endswith('backend-1')]; front=[c for c in rows if c['Name'].endswith('frontend-1')]
    lines += [f"| {concurrency} | {f(max((float(c['CPUPerc'].rstrip('%')) for c in back),default=None))}% | {f(max((memory(c['MemUsage'].split('/')[0]) for c in back),default=None))} | {f(max((memory(c['MemUsage'].split('/')[0]) for c in front),default=None))} |"]
lines += ['',f"共 {len(samples)} 次采样，其中 {sum('error' in s for s in samples)} 次包含错误；每次采样后等待 5 秒，每三次读取队列。CPU 100% 表示一核，不是 CPU 配额使用率。准备、切换与清理阶段未计入上表。",'',
    f"初次对照 JVM 参数：`{initial_jvm or '未在初次运行元数据中记录'}`。延迟达标不等于内存余量充足；最终默认 JVM 堆占比为 60%，为线程、元空间、图像处理和其他容器内存预留空间。补测结果单独列出。",'',
    '## 收尾、复现与资料','',f"- 清理：{m.get('cleanup') or '尚在执行'}。",f"- 模型恢复：{m.get('modelRestored','尚未恢复')}。",
    '- 完成后容量环境保留消费者并发 4；生产默认并发仍为 2，真实模型需结合供应商限额验证。','',
    '```powershell','python loadtests/run-burst-comparison.py',f"python loadtests/collect-burst-health.py loadtests/results/{m['run']}",f"python loadtests/write-burst-comparison-report.py loadtests/results/{m['run']}",'```','',
    f"- [本轮参数、镜像、验证与清理记录](../results/{m['run']}/manifest.json)",
    f"- [容器与队列采样](../results/{m['run']}/resources.jsonl)",
    f"- [仅扩充本轮账号的 SQL](../results/{m['run']}/seed-records.sql)",
    f"- [实测脚本及配置快照](../results/{m['run']}/scripts)",
    '- [上轮优化报告](k6-optimization-20261004-173543.md)；新一轮修改了后端实现，旧数据仅作背景，本轮并发对照以同一新镜像为准。',
    '- [3 秒等待校准的失败记录](k6-burst-comparison-20261004-193440.md)：20 张中 19 张接收成功，1 张受控拒绝；随后调整为 5 秒，未删除或混入该轮结果。']
for s in stages:
    n=s['name']; lines += [f"- `{n}`：[汇总](../results/{m['run']}/{n}.json)、[日志](../results/{m['run']}/{n}.log)、[时间序列](../results/{m['run']}/{n}.timeseries.jsonl)，退出码 {s['exitCode']}。"]
for name,label in [('final-health.json','最终健康与清理验证'),('storage.json','上传临时存储验证')]:
    if (root/name).exists(): lines += [f"- [{label}](../results/{m['run']}/{name})。"]
if (root/'final-health.json').exists():
    health=json.loads((root/'final-health.json').read_text(encoding='utf-8'))
    assert all(c['health']=='healthy' and not c['oomKilled'] and c['restartCount']==0 for c in health['containers'])
    assert not any(health['database'].values())
    assert all(q['messages_ready']==0 and q['messages_unacknowledged']==0 for q in health['queues'])
    lines += ['', '最终验证：六个容器均健康，无 OOM、无重启；本轮账号、餐食与任务均清理完成，数据库待处理和未发布 Outbox 为 0，RabbitMQ Ready/Unacked 为 0。']
if (root/'storage.json').exists():
    storage=json.loads((root/'storage.json').read_text(encoding='utf-8'))
    assert storage['fileCount']==0 and storage['fileBytes']==0
    lines += [f"上传临时目录最终文件数 {storage['fileCount']}、总字节 {storage['fileBytes']}，使用独立磁盘卷。"]
if args.memory_evidence:
    supplement=args.memory_evidence.resolve()
    mm=json.loads((supplement/'manifest.json').read_text(encoding='utf-8'))
    assert mm['schema']=='burst-memory-check-v1' and mm.get('ended') and not mm.get('error')
    assert mm['modelRestored'] is True
    assert len({row['imageId'] for row in m['configurations']+mm['configurations']})==1
    assert all(x['containers'][0]['settings']['JAVA_TOOL_OPTIONS'].startswith('-XX:MaxRAMPercentage=60.0 ') for x in mm['runtimeByConcurrency'])
    memory_samples=[json.loads(line) for line in (supplement/'resources.jsonl').read_text(encoding='utf-8').splitlines()]
    lines += ['', '## 60% JVM 堆占比的内存补测','',f"北京时间：{mm['started']} 至 {mm['ended']}。保持 1.5 CPU、1 GiB 容器和相同业务镜像；两档均验证入口、字节边界、过载、大分辨率与一次慢模型突发。并发 4 再运行十分钟持续上传及两分钟混合负载。并发 2 没有重复十分钟持续上传，原对照未删除或合并。",'',
        '| 场景 | 尝试/接收/成功 | 429 | 后台失败/超时/不确定/中断 | 上传 P95 ms | 成功 E2E P95 s | 验收 |',
        '| --- | ---: | ---: | ---: | ---: | ---: | --- |']
    memory_data={}
    for stage in mm['phases']:
        n=stage['name']; c=json.loads((supplement/(n+'.json')).read_text(encoding='utf-8'))['capacity']; memory_data[n]=c
        assert c['attempts']==sum(c[k] for k in ['succeeded','failed','rejected','timedOut','unresolved','unfinished'])
        assert c['passed']==(stage['exitCode']==0)
        if n=='seed' or 'warmup' in n: continue
        u=c.get('acceptedUploadMs'); e=c['successfulE2eMs']
        lines += [f"| {n} | {c['attempts']}/{c['accepted']}/{c['succeeded']} | {c['submit429']} | {c['failed']}/{c['timedOut']}/{c['unresolved']}/{c['unfinished']} | {f(u['p(95)']) if u else '-'} | {f(e['p(95)']/1000) if e else '-'} | {'通过' if c['passed'] else '未通过'} |"]
    failures=[n for n,c in memory_data.items() if not c['passed'] and n!='c2-burst-20-r1']
    if failures:
        lines += ['', f"补测仍有未达标阶段：`{'、'.join(failures)}`。其中持续上传 201 次尝试、199 次确认成功、2 次客户端 TCP 建连超时（HTTP 状态 0、Windows errno 10060）计为结果不确定，不从结果中剔除。它不是 HTTP 500；尚不能据此确定连接故障根因。"]
    lines += ['', '| 并发 | 后端内存采样峰值 MiB | 1 GiB 限额的采样余量 MiB |','| ---: | ---: | ---: |']
    for concurrency in [2,4]:
        values=[memory(c['MemUsage'].split('/')[0]) for s in memory_samples if s['phase'].startswith(f'c{concurrency}-') for c in s.get('containers',[]) if c['Name'].endswith('backend-1')]
        peak=max(values)
        lines += [f"| {concurrency} | {f(peak)} | {f(1024-peak)} |"]
    lines += ['', '降低堆占比后，大图补测中的容器内存余量仍偏小；生产应结合真实图片分布评估更大的容器内存预算或进一步减少解码分配。本轮没有验证更大的内存规格，不能把无 OOM 解释为 1 GiB 已有充分余量。']
    health=json.loads((supplement/'final-health.json').read_text(encoding='utf-8'))
    storage=json.loads((supplement/'storage.json').read_text(encoding='utf-8'))
    assert not any(health['database'].values()) and storage['fileCount']==0
    assert all(c['health']=='healthy' and not c['oomKilled'] and c['restartCount']==0 for c in health['containers'])
    assert all(q['messages_ready']==0 and q['messages_unacknowledged']==0 for q in health['queues'])
    lines += ['', '内存补测清理验证：六个容器健康、无 OOM 与重启；本轮账号数据、队列、Outbox 和 multipart 临时文件均已清空。峰值来自间隔采样，不能当作更大图片分布或更长运行的硬上界。','',
        f"- [补测参数、运行配置与清理](../results/{mm['run']}/manifest.json)",
        f"- [补测资源采样](../results/{mm['run']}/resources.jsonl)",
        f"- [补测最终健康](../results/{mm['run']}/final-health.json)",
        f"- [补测临时存储](../results/{mm['run']}/storage.json)",
        '- 复现：`python loadtests/run-burst-comparison.py --memory-check`，再执行本轮的健康收集；生成报告时增加 `--memory-evidence <补测目录>`。']
    for stage in mm['phases']:
        n=stage['name']; lines += [f"- `{n}`：[汇总](../results/{mm['run']}/{n}.json)、[日志](../results/{mm['run']}/{n}.log)、[时间序列](../results/{mm['run']}/{n}.timeseries.jsonl)。"]
    if (supplement/'connection-diagnostics.json').exists():
        lines += [f"- [两次建连超时的时间序列证据](../results/{mm['run']}/connection-diagnostics.json)。"]
if args.confirmation_evidence:
    confirmation=args.confirmation_evidence.resolve()
    cm=json.loads((confirmation/'manifest.json').read_text(encoding='utf-8'))
    assert cm['schema']=='burst-memory-continuous-v1' and cm.get('ended') and not cm.get('error')
    assert cm['baseUrl']=='http://127.0.0.1:18080' and cm['modelRestored'] is True
    assert len({row['imageId'] for row in m['configurations']+cm['configurations']})==1
    stages_confirmed={s['name']:s for s in cm['phases']}
    lines += ['', '## 明确 IPv4 地址的持续与混合复测','',
        f"北京时间：{cm['started']} 至 {cm['ended']}。使用 `http://127.0.0.1:18080`，保持相同业务镜像、消费者并发 4、1.5 CPU、1 GiB 容器及 60% JVM 堆占比；新建独立的 40 个账号和 40,000 条记录。未重复入口和大图阶段，也未覆盖或合并前一轮失败数据。",'',
        '| 场景 | 尝试/接收/成功 | 超时/结果不确定/中断 | 成功 E2E P95 s | 验收 |','| --- | ---: | ---: | ---: | --- |']
    for name in ['c4-arrival-20-long','c4-mixed-20']:
        d=json.loads((confirmation/(name+'.json')).read_text(encoding='utf-8')); c=d['capacity']; s=stages_confirmed[name]
        assert c['attempts']==sum(c[k] for k in ['succeeded','failed','rejected','timedOut','unresolved','unfinished'])
        assert c['passed']==(s['exitCode']==0)
        e=c['successfulE2eMs']
        lines += [f"| {name} | {c['attempts']}/{c['accepted']}/{c['succeeded']} | {c['timedOut']}/{c['unresolved']}/{c['unfinished']} | {f(e['p(95)']/1000) if e else '-'} | {'通过' if c['passed'] else '未通过'} |"]
        if name=='c4-mixed-20':
            lines += ['',f"混合查询 {int(metric(d,'meal_list_requests','count'))} 次，成功率 {f(metric(d,'meal_list_success','rate')*100)}%，查询 P95 {f(metric(d,'meal_list_duration','p(95)'))} ms。",'']
    assert all(json.loads((confirmation/(n+'.json')).read_text())['capacity']['passed'] for n in ['c4-arrival-20-long','c4-mixed-20'])
    health=json.loads((confirmation/'final-health.json').read_text(encoding='utf-8'))
    storage=json.loads((confirmation/'storage.json').read_text(encoding='utf-8'))
    assert not any(health['database'].values()) and storage['fileCount']==0
    assert all(c['health']=='healthy' and not c['oomKilled'] and c['restartCount']==0 for c in health['containers'])
    assert all(q['messages_ready']==0 and q['messages_unacknowledged']==0 for q in health['queues'])
    lines += ['IPv4 复测通过，原有两次建连超时仍作为失败证据保留；一次复测不能证明 localhost/DNS 就是根因。最终六个容器健康，本轮数据和临时文件清理完成，队列及 Outbox 为 0。','',
        f"- [IPv4 复测参数及清理](../results/{cm['run']}/manifest.json)",
        f"- [IPv4 复测资源采样](../results/{cm['run']}/resources.jsonl)",
        f"- [IPv4 复测最终健康](../results/{cm['run']}/final-health.json)",
        f"- [IPv4 复测临时存储](../results/{cm['run']}/storage.json)",
        '- 复现：`python loadtests/run-burst-comparison.py --continuous-only`，执行健康收集后生成报告时增加 `--confirmation-evidence <IPv4 复测目录>`。']
    for stage in cm['phases']:
        n=stage['name']; lines += [f"- `{n}`：[汇总](../results/{cm['run']}/{n}.json)、[日志](../results/{cm['run']}/{n}.log)、[时间序列](../results/{cm['run']}/{n}.timeseries.jsonl)。"]
lines += ['', '账号文件含 JWT 与清理密码，禁止分享。原始证据在 Git 忽略目录，分享 Markdown 时需要另行保存证据。','']
target.write_text('\n'.join(lines),encoding='utf-8'); print(target)
