"""Compile the current production splitter into a fresh offline evidence runtime."""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess

from check_longform_manuscript import BATCH
from prepare_knowledge_longform import ROOT


def refresh():
    sources = [ROOT / 'backend/src/main/java/com/example/mealcheck/config/AppProperties.java',
               ROOT / 'backend/src/main/java/com/example/mealcheck/service/MarkdownKnowledgeChunker.java',
               ROOT / 'backend/src/main/java/com/example/mealcheck/service/KnowledgeCategoryClassifier.java',
               ROOT / 'tools/evaluation/KnowledgeCorpusTool.java']
    hashes = {str(source): hashlib.sha256(source.read_bytes()).hexdigest() for source in sources}
    stamp = datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    directory = BATCH / ('runtime-' + stamp)
    classes = directory / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    dependencies = ROOT / 'logs/backend/knowledge-longform-20261020-distribution-v1/lib/*'
    pom = (ROOT / 'backend/pom.xml').read_text(encoding='utf-8')
    version = re.search(r'<langchain4j.version>([^<]+)</langchain4j.version>', pom).group(1)
    framework = Path.home() / '.m2/repository/dev/langchain4j/langchain4j' / version / ('langchain4j-' + version + '.jar')
    if not framework.exists():
        raise ValueError('The current local LangChain4j jar is required; no dependencies will be downloaded')
    classpath = str(ROOT / 'backend/target/classes') + ';' + str(framework) + ';' + str(dependencies)
    subprocess.run(['javac', '-J-XX:ErrorFile=' + str(ROOT / 'logs/crash/hs_err_pid%p.log'),
                    '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), *map(str, sources)],
                   cwd=ROOT, check=True)
    if hashes != {str(source): hashlib.sha256(source.read_bytes()).hexdigest() for source in sources}:
        raise ValueError('Production sources changed during offline compilation')
    runtime = {'classpath': str(classes) + ';' + classpath, 'compiledSources': hashes,
               'additionalDependencySha256': {str(framework): hashlib.sha256(framework.read_bytes()).hexdigest()},
               'compiledAt': stamp, 'providerCalls': 0, 'springStarted': False}
    active = BATCH / 'runtime.json'
    (directory / 'previous-runtime.json').write_bytes(active.read_bytes())
    payload = (json.dumps(runtime, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    (directory / 'runtime.json').write_bytes(payload)
    pending = directory / 'active-runtime.json'
    pending.write_bytes(payload)
    pending.replace(active)
    print(json.dumps({'runtime': str(directory), 'compiledSourceCount': len(sources),
                      'providerCalls': 0, 'springStarted': False}, ensure_ascii=False))


if __name__ == '__main__':
    refresh()
