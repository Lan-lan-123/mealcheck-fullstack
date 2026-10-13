"""Summarize ten-minute resource windows from immutable local test evidence."""
from collections import defaultdict
from datetime import datetime
import json
from pathlib import Path
import re
import sys


def metric(lines, name, predicate=lambda line: True):
    return sum(float(line.rsplit(' ', 1)[1]) for line in lines
               if line.startswith(name + '{') and predicate(line))


def summarize(folder):
    manifest = json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
    phases = {p['name']: datetime.fromisoformat(p['started']) for p in manifest['phases']}
    windows = defaultdict(list)
    for raw in (folder/'jvm.jsonl').open(encoding='utf-8'):
        sample = json.loads(raw)
        if sample.get('phase') not in phases or 'os' not in sample:
            continue
        os_lines = sample['os'].splitlines()
        if len(os_lines) < 2 or not os_lines[0].isdigit():
            continue
        elapsed = (datetime.fromisoformat(sample['at']) - phases[sample['phase']]).total_seconds()
        if elapsed < 0:
            continue
        os_values = dict(re.findall(r'^([a-z_]+) (\d+)$', sample['os'], re.M))
        metrics = sample.get('prometheus', '').splitlines()
        values = {
            'elapsedSeconds': elapsed,
            'workingSetMiB': (int(os_lines[0])-int(os_values.get('inactive_file', 0)))/2**20,
            'anonymousMiB': int(os_values.get('anon', 0))/2**20,
            'fileCacheMiB': int(os_values.get('file', 0))/2**20,
            'heapUsedMiB': metric(metrics, 'jvm_memory_used_bytes', lambda l: 'area="heap"' in l)/2**20,
            'gcLiveMiB': metric(metrics, 'jvm_gc_live_data_size_bytes')/2**20,
            'gcPauseSecondsCumulative': metric(metrics, 'jvm_gc_pause_seconds_sum'),
            'connectionWaiters': metric(metrics, 'hikaricp_connections_pending'),
            'memoryMaxEvents': int(os_values.get('max', 0)),
            'oomEvents': int(os_values.get('oom', 0)),
            'oomKills': int(os_values.get('oom_kill', 0)),
        }
        key = (sample['phase'], sample.get('runtime', {}).get('id'), int(elapsed//600))
        windows[key].append(values)
    result = []
    for (phase, container, window), rows in sorted(windows.items()):
        entry = {'phase': phase, 'containerId': container, 'windowStartMinutes': window*10,
                 'samples': len(rows)}
        for name in rows[0]:
            values = [r[name] for r in rows]
            entry[name] = {'first': values[0], 'last': values[-1], 'min': min(values), 'max': max(values)}
        result.append(entry)
    return {'run': manifest['run'], 'windows': result,
            'note': 'Sampled windows; live-data gauge updates only at major GC. Counters are cumulative per container.'}


if __name__ == '__main__':
    folder = Path(sys.argv[1])
    result = summarize(folder)
    (folder/'stability-summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'run': result['run'], 'windows': len(result['windows'])}))
