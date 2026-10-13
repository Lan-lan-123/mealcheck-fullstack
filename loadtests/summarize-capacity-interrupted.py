"""Describe an interrupted run from raw points; never certify its planned duration."""
from collections import defaultdict
from datetime import datetime
import json
import math
from pathlib import Path
import re
import sys


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * fraction
    lower = math.floor(rank)
    upper = math.ceil(rank)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (rank - lower)


def summarize(folder):
    manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
    phase = next(p for p in manifest['phases'] if p.get('interrupted'))
    names = {
        'analysis_attempted', 'analysis_accepted', 'analysis_succeeded', 'analysis_failed',
        'analysis_rejected', 'analysis_timed_out', 'analysis_unresolved',
        'analysis_unknown_submission', 'analysis_submit_429', 'analysis_upload_busy',
        'analysis_poll_errors', 'analysis_e2e_ms', 'analysis_success_rate',
        'meal_list_requests', 'meal_list_success', 'meal_list_duration',
        'assistant_success', 'assistant_ms', 'weekly_success', 'weekly_ms',
        'weekly_multiagent_generated', 'weekly_invalidated', 'dropped_iterations', 'http_req_failed',
    }
    points = defaultdict(list)
    incomplete_lines = 0
    with (folder / (phase['name'] + '.timeseries.jsonl')).open(encoding='utf-8') as stream:
        for line in stream:
            try:
                item = json.loads(line)
            except ValueError:
                incomplete_lines += 1
                continue
            if item.get('type') == 'Point' and item.get('metric') in names:
                points[item['metric']].append(item['data']['value'])

    def count(name):
        return int(sum(points[name]))

    def rate(name):
        values = points[name]
        return {'observations': len(values), 'successes': int(sum(values)),
                'fraction': sum(values) / len(values) if values else None}

    def latency(name):
        values = points[name]
        return {'observations': len(values), 'p95Ms': percentile(values, .95),
                'maxMs': max(values) if values else None}

    outcome_names = ('analysis_succeeded', 'analysis_failed', 'analysis_rejected',
                     'analysis_timed_out', 'analysis_unresolved')
    last_sample = None
    main_samples = []
    sample_errors = 0
    with (folder / 'jvm.jsonl').open(encoding='utf-8') as stream:
        for line in stream:
            sample = json.loads(line)
            if sample.get('phase') == phase['name']:
                main_samples.append(sample)
                sample_errors += bool(sample.get('error'))
            if 'prometheus' in sample:
                last_sample = sample
    final_http_calls = {}
    if last_sample:
        for operation, outcome, value in re.findall(
                r'^mealcheck_ai_call_duration_seconds_count\{[^\n]*operation="([^"]+)",outcome="([^"]+)"\} (\d+)$',
                last_sample['prometheus'], re.M):
            final_http_calls[operation + ':' + outcome] = int(value)
    resources = []
    queue_samples = []
    with (folder / 'resources.jsonl').open(encoding='utf-8') as stream:
        for line in stream:
            sample = json.loads(line)
            if sample.get('phase') != phase['name']:
                continue
            resources.extend(row for row in sample.get('containers', [])
                             if row.get('Name') == 'mealcheck-capacity-backend-1')
            if sample.get('queues'):
                queue_samples.append(sample['queues']['database'])
    memory = []
    for row in resources:
        value, unit = re.match(r'([\d.]+)(\w+)', row['MemUsage']).groups()
        memory.append(float(value) * {'B': 2**-20, 'KiB': 2**-10, 'MiB': 1, 'GiB': 2**10}[unit])
    cpu = [float(row['CPUPerc'].rstrip('%')) for row in resources]
    after = json.loads((folder / 'global-budget-after-stop.json').read_text(encoding='utf-8-sig'))
    before = manifest['phases'][0]['globalBudgetAfter']
    result = {
        'run': manifest['run'], 'status': 'USER_STOPPED_PARTIAL',
        'plannedDurationSeconds': float(re.fullmatch(r'(\d+(?:\.\d+)?)(s|m|h)', phase['parameters']['DURATION'])[1])
                                  * {'s': 1, 'm': 60, 'h': 3600}[phase['parameters']['DURATION'][-1]],
        'observedDurationSeconds': (datetime.fromisoformat(phase['ended']) - datetime.fromisoformat(phase['started'])).total_seconds(),
        'fullDurationAcceptancePassed': False, 'phaseInterrupted': True,
        'incompleteRawLines': incomplete_lines, 'samplingErrorsDuringMainPhase': sample_errors,
        'analysis': {name: count(name) for name in sorted(names) if name.startswith('analysis_')
                     and name not in ('analysis_e2e_ms', 'analysis_success_rate')},
        'analysisLatency': latency('analysis_e2e_ms'),
        'reads': {'requests': count('meal_list_requests'), **rate('meal_list_success'),
                  'latency': latency('meal_list_duration')},
        'assistant': {**rate('assistant_success'), 'latency': latency('assistant_ms')},
        'weekly': {**rate('weekly_success'), 'latency': latency('weekly_ms'),
                   'generated': count('weekly_multiagent_generated'), 'invalidated': count('weekly_invalidated'),
                   'backendCompletedAfterDrain': sum(row['status'] == 'SUCCEEDED' for row in manifest.get('weeklyJobsBeforeCleanup', []))},
        'observedDroppedIterations': count('dropped_iterations'),
        'httpFailuresObserved': count('http_req_failed'),
        'backendCpuPercent': {'samples': len(cpu), 'max': max(cpu) if cpu else None,
                              'mean': sum(cpu) / len(cpu) if cpu else None},
        'backendDockerWorkingSetMiB': {'maxSample': max(memory) if memory else None,
                                     'lastSample': memory[-1] if memory else None},
        'databaseOutstanding': {'samples': len(queue_samples),
                                'max': max((row['pending'] for row in queue_samples), default=None),
                                'last': queue_samples[-1] if queue_samples else None},
        'modelStatsAtStop': json.loads((folder / 'model-stats-at-user-stop.json').read_text(encoding='utf-8-sig')),
        'finalBackendHttpAttempts': final_http_calls,
        'globalBudgetBeforeMain': before, 'globalBudgetAfterDrain': after,
        'globalBudgetAttemptDelta': int(after['attempts'] - before['attempts']),
        'finalQueuesBeforeCleanup': manifest.get('finalQueuesBeforeCleanup'),
        'cleanupVerification': manifest.get('cleanupVerification'),
        'modelRestored': manifest.get('modelRestored'), 'runtimeAfter': manifest.get('runtimeAfter'),
        'note': 'Forced client stop truncates in-flight observations. Completed-request success and percentiles are partial; no final k6 threshold result or one-hour acceptance is asserted. Backend counters exclude seed because backend was recreated before the main phase. Budget baseline is after seed; model counters were reset during cleanup.',
    }
    result['analysis']['clientOutcomesMissingAtStop'] = max(0, count('analysis_attempted') - sum(count(name) for name in outcome_names))
    result['analysis']['backendCompletedAfterDrain'] = manifest['finalQueuesBeforeCleanup']['database']['statuses'].get('SUCCEEDED', 0) - manifest['accountCount']
    result['finalBackendHttpAttemptTotal'] = sum(final_http_calls.values())
    result['globalBudgetMatchesBackendAttempts'] = result['globalBudgetAttemptDelta'] == result['finalBackendHttpAttemptTotal']
    return result


if __name__ == '__main__':
    target = Path(sys.argv[1])
    summary = summarize(target)
    (target / 'partial-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2))
