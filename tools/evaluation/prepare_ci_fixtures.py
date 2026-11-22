"""Regenerate offline test inputs from compiled production code and committed sources."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

import prepare_rag_screening_pilot as pilot

ROOT = Path(__file__).resolve().parents[2]


def run(command, output, name):
    with (output / f'{name}.log').open('xb') as stream:
        subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, check=True)


def prepare(output):
    output = output.resolve()
    if not output.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('CI fixture output must stay under logs/')
    classes = ROOT / 'backend/target/classes'
    if not (classes / 'com/example/mealcheck/service/MarkdownKnowledgeChunker.class').is_file():
        raise ValueError('Compile the current backend before preparing CI fixtures')
    output.mkdir(parents=True, exist_ok=False)
    mvn, java = shutil.which('mvn'), shutil.which('java')
    if not mvn or not java:
        raise ValueError('Maven and Java are required to prepare production-splitter fixtures')
    classpath_file = output / 'classpath.txt'
    run([mvn, '-B', '-ntp', '-f', str(ROOT / 'backend/pom.xml'), 'dependency:build-classpath',
         f'-Dmdep.outputFile={classpath_file}'], output, 'resolve-classpath')
    classpath = str(classes) + os.pathsep + classpath_file.read_text(encoding='utf-8').strip()
    manifest = output / 'chunks.json'
    run([java, '-cp', classpath, str(ROOT / 'tools/evaluation/KnowledgeCorpusTool.java'),
         '--manifest', str(pilot.KNOWLEDGE / 'diet_guides.md'), str(manifest)], output, 'production-splitter')
    load = lambda path: json.loads(path.read_bytes())
    inputs = {'corpus': pilot.KNOWLEDGE / 'diet_guides.md', 'dataset': pilot.KNOWLEDGE / 'rag_eval_cases.json',
              'metadata': pilot.KNOWLEDGE / 'diet_guides_metadata.json',
              'suite': pilot.KNOWLEDGE / 'rag_chunking_eval_suite.json', 'manifest': manifest}
    plan, selected, expected = pilot.prepare(inputs['corpus'].read_bytes(), load(inputs['dataset']),
                                            load(inputs['metadata']), load(inputs['suite']), load(manifest))
    plan['inputFileSha256'] = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in inputs.items()}
    packet = output / 'screening-packet'
    pilot.export(packet, plan, selected, expected)
    print(json.dumps({'manifest': str(manifest), 'packet': str(packet),
                      'chunkCount': plan['chunkCount'], 'providerCalls': 0}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='Fresh directory under project logs/')
    prepare(parser.parse_args().output)
