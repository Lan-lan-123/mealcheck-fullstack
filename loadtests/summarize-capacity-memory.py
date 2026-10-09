"""Summarize sampled JVM/cgroup evidence; sampled peaks are not absolute maxima."""
import collections
import json
from pathlib import Path
import re
import sys


def metric(text, name, area=None):
    return sum(float(line.split()[-1]) for line in text.splitlines()
               if line.startswith(name+'{') and (area is None or f'area="{area}"' in line))


def summarize(path):
    groups = collections.defaultdict(list)
    for line in (path/'jvm.jsonl').read_text(encoding='utf-8').splitlines():
        d = json.loads(line)
        if 'os' not in d or 'prometheus' not in d: continue
        p = d['prometheus']
        os_lines = d['os'].splitlines()
        values = {'cgroupMiB': int(os_lines[0])/2**20,
            'heapUsedMiB': metric(p, 'jvm_memory_used_bytes', 'heap')/2**20,
            'heapCommittedMiB': metric(p, 'jvm_memory_committed_bytes', 'heap')/2**20,
            'nonheapUsedMiB': metric(p, 'jvm_memory_used_bytes', 'nonheap')/2**20,
            'gcLiveMiB': metric(p, 'jvm_gc_live_data_size_bytes')/2**20,
            'gcPauseSeconds': metric(p, 'jvm_gc_pause_seconds_sum'),
            'allocatedMiB': metric(p, 'jvm_gc_memory_allocated_bytes_total')/2**20}
        stats={k:int(v) for k,v in re.findall(r'^([a-z_]+) (\d+)$',d['os'],re.M)}
        values.update(anonMiB=stats.get('anon',0)/2**20, fileMiB=stats.get('file',0)/2**20,
                      inactiveFileMiB=stats.get('inactive_file',0)/2**20,
                      workingSetMiB=max(0,int(os_lines[0])-stats.get('inactive_file',0))/2**20)
        if len(os_lines)>1 and os_lines[1].isdigit(): values['cgroupPeakMiB'] = int(os_lines[1])/2**20
        key = d['phase']
        groups[key].append(values)
    return {k: {'samples': len(v), 'max': {f: round(max(d[f] for d in v), 2) for f in v[0]},
                'last': {f: round(v[-1][f], 2) for f in v[-1]}} for k, v in groups.items()}


if __name__ == '__main__':
    path = Path(sys.argv[1]); data = summarize(path)
    (path/'memory-summary.json').write_text(json.dumps(data, indent=2), encoding='utf-8')
    for k,v in data.items(): print(k, json.dumps(v))
