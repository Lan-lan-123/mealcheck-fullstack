"""Prepare a reviewable 200-topic length plan and offline splitter runtime; no model calls."""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / 'backend/src/main/resources/knowledge/diet_guides.md'
MARKER = '<!-- mealcheck:independent-topics -->'
BUCKETS = {'SHORT': (40, 2, 3, 2), 'MEDIUM': (60, 4, 6, 5),
           'LONG': (60, 7, 10, 8), 'EXTENDED': (40, 12, 20, 15)}
# Broad subjects get the long articles; a single food/product usually needs less detail.
EXTENDED = {1, 2, 3, 4, 5, 6, 7, 8, 15, 19, 20, 21, 23, 24, 25, 26,
            57, 59, 63, 64, 65, 69, 70, 71, 74, 75, 76, 77, 79, 82,
            83, 84, 90, 92, 98, 141, 142, 144, 150, 192}
COMPLEX_TERMS = ['标签', '配料', '份量', '记录', '安排', '安全', '保存', '组合',
                 '换算', '过敏', '不耐受', '补充', '定制', '计划', 'B12', '钙', '铁']
SHORT = {10, 11, 12, 13, 14, 16, 27, 28, 29, 30, 31, 34, 37, 39, 41, 42,
         44, 49, 50, 53, 85, 87, 101, 102, 103, 104, 105, 107, 108, 109,
         110, 111, 112, 113, 116, 117, 152, 153, 159, 170}


def topics(text):
    if not text.lstrip().startswith(MARKER):
        raise ValueError('Expected independently headed topics')
    matches = list(re.finditer(r'^## (.+)$', text, re.M))
    return [{'topicId': f'knowledge-{index + 1:03d}', 'topicIndex': index + 1,
             'title': match.group(1).strip(),
             'originalBody': text[match.end():matches[index + 1].start() if index + 1 < len(matches) else len(text)].strip()}
            for index, match in enumerate(matches)]


def plan(text):
    rows = topics(text)
    if len(rows) != 200 or len({row['title'] for row in rows}) != 200:
        raise ValueError('Expected exactly 200 distinct source topics')
    assert len(SHORT) == len(EXTENDED) == 40 and not SHORT.intersection(EXTENDED)
    remaining = [row for row in rows if row['topicIndex'] not in EXTENDED.union(SHORT)]
    remaining.sort(key=lambda row: (-sum(term in row['title'] for term in COMPLEX_TERMS), row['topicIndex']))
    assignments = {row['topicIndex']: 'LONG' if index < 60 else 'MEDIUM'
                   for index, row in enumerate(remaining)}
    for row in rows:
        row['bucket'] = ('EXTENDED' if row['topicIndex'] in EXTENDED else 'SHORT'
                         if row['topicIndex'] in SHORT else assignments[row['topicIndex']])
        _, row['minChunks'], row['maxChunks'], row['targetChunks'] = BUCKETS[row['bucket']]
        row['targetAddedEstimatedTokens'] = max(500, row['targetChunks'] * 390 - 250)
        row['sourceUrls'] = list(dict.fromkeys(re.findall(r'https://[^\s)）]+', row['originalBody'])))
    assert Counter(row['bucket'] for row in rows) == {key: value[0] for key, value in BUCKETS.items()}
    return rows


def related_topics(row, rows):
    families = [
        {1, 27, 28, 29, 30, 31, 32, 33, 101, 102, 110, 151, 152, 153, 154, 155, 156, 157, 158, 159, 168, 179},
        {2, 8, 20, 34, 35, 36, 37, 38, 107, 128, 129, 131, 136, 137, 139, 140, 160, 161, 162, 163, 164, 165, 167, 170, 186},
        {3, 13, 39, 40, 43, 127, 166, 167, 168, 169, 170, 185},
        {4, 9, 11, 12, 50, 52, 53, 66, 104, 108, 113, 154, 155, 157, 158, 163, 166, 182},
        {5, 10, 14, 41, 42, 44, 45, 46, 47, 48, 67, 121, 122, 123, 124, 125, 126, 130, 134, 171, 172, 173, 174, 175, 176},
        {6, 7, 15, 16, 17, 18, 19, 21, 22, 24, 25, 38, 49, 51, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 85, 87, 88, 92, 96, 99, 100, 118, 119, 120, 135, 181, 187},
        {23, 64, 65, 66, 67, 68, 69, 70, 90, 161, 162, 164, 165, 174, 175, 176, 177, 178, 179, 180, 182, 183, 184, 188},
        {71, 72, 73, 74, 75, 76, 77, 93, 94, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200},
        {26, 78, 79, 80, 81, 82, 83, 84, 86, 89, 91, 95, 97, 98, 189, 190},
    ]
    family = next((group for group in families if row['topicIndex'] in group), set())
    title = row['title']
    terms = {title[index:index + 2] for index in range(len(title) - 1)}
    candidates = [other for other in rows if other['topicId'] != row['topicId'] and other['topicIndex'] in family]
    candidates.sort(key=lambda other: (-sum(term in other['title'] for term in terms), other['topicIndex']))
    return [{'topicId': other['topicId'], 'title': other['title'], 'supportedOriginalText': other['originalBody']}
            for other in candidates[:8 if row['bucket'] == 'EXTENDED' else 4]]


def prompt(row, related=()):
    system = ('你是中文校园选餐与记录操作手册的编辑，不是营养生理科普作者。用户要求有信息量的长文，不可用换词重复、套话或复制其他主题凑长度。'
              '原文会在文章开头逐字保留，你只编写新增正文。仅展开原文已经支持的一般原则、实际操作、条件差异、'
              '记录方法和明确为假设的案例；不可添加诊断、处方、补充剂剂量、确定减重承诺或未知食品的精确营养值。'
              '这是闭集资料改写：所有事实仅能来自本主题原文和给定相关条目，不可用你记忆中的营养学知识补充事实。'
              '禁止新增生理机制和健康因果解释，尤其不能编写氨基酸互补、血糖、胰岛素、糖原、GI、代谢、黏膜、激素、精力或学习能力变化等内容。'
              '禁止推荐任何疾病的饮食处理，禁止新增替换重量比例或食品安全温度时间阈值。'
              '不得创造新的餐食评分规则、强制食物分类、精确份量、每次都必须核对的要求、节食或额外运动补偿方案。'
              '未知不等于零：看不清食材不能说没有蛋白质，看不出用油不能说确定高油，缺少重量不能编造标准克数。'
              '一般选餐建议不需要先查清全部食材；只在回答具体问题确实需要时有针对性补充信息。'
              '不要创造应用界面的功能名，不要求获取同伴个人饮食信息，不把记录缺失解读为摄入不足或过量。'
              '扩展重点是具体怎么选、条件改变怎么调整、信息缺失如何记录、出错如何更正。每个小节提供不同操作案例。'
              '正确示例：两人一起点盖饭时，先分别记录各自实际吃的主食和配菜；不知道个人份量，就不把整盘平均分成两份。'
              '不要在正文中复述被禁止的医学或生理主张，即使写成错误例子也不要；边界用无法推断生理变化或提供个体处方作概括说明。'
              '每段直接说明具体条件与原则，不用原文指出、上文所述等指代替代事实，使切分后的段落也能独立理解。'
              '食品安全不得靠闻味、试吃或重新加热作安全保证；原文不能支持的结论明确保留未知。'
              '不得发明来源、链接、研究、法规或数字阈值。只返回新增正文的Markdown，不要JSON、代码围栏、前言或结语。'
              '每个小标题单独一行，使用**小节名称**格式，按writingPlan指定数量写完整段落，段落之间留空行。'
              '小标题不要写Markdown井号，正文不得包含##或脚本、工具指令。不要另写参考列表或引用链接，来源由程序保留。')
    structures = {'SHORT': (2, 2, [130, 180]), 'MEDIUM': (4, 3, [110, 150]),
                  'LONG': (7, 3, [120, 155]), 'EXTENDED': (12, 3, [140, 180])}
    section_count, paragraph_count, paragraph_range = structures[row['bucket']]
    outlines = {
        'SHORT': ['具体选择与已经确认的条件', '实际食用记录与信息不足的处理'],
        'MEDIUM': ['准备时可以确认的信息', '具体操作步骤', '不同条件的对照案例', '记录与更正方法'],
        'LONG': ['基本场景与原文原则', '两个选项如何比较', '只知道名称或图片时',
                 '份量或配料不完整时', '时间与供应条件变化时', '实际执行与记录更正', '复盘与适用边界'],
        'EXTENDED': ['基本场景与原文原则', '两种可选方案的具体比较', '只有名称或照片的信息边界',
                     '份量没有测量时的记录', '多人共同就餐或使用时的个人信息', '食品或配料缺货时的替代',
                     '时间受限的操作安排', '预算或设备条件受限的安排', '用户修改已知信息后的更正',
                     '实际选择与原计划不一致的处理', '多次记录如何复盘', '适用范围与无法回答的问题'],
    }
    user = {'topic': row['title'], 'originalBody': row['originalBody'],
            'relatedSupportedPrinciples': list(related),
            'targetAddedEstimatedTokens': row['targetAddedEstimatedTokens'],
            'writingPlan': {'distinctSections': section_count, 'paragraphsPerSection': paragraph_count,
                            'chineseCharactersPerParagraph': paragraph_range,
                            'requiredSectionOutlines': outlines[row['bucket']],
                            'paragraphRoles': ['第一段给出一个明确为假设的校园具体场景，说明已知条件和当前问题，只引用资料已有的原则。',
                                               '第二段写实际可以采取的步骤、应保留的记录和信息缺失时如何停止推断，不解释生理机制。',
                                               '需要第三段时，改变本节案例的一个具体条件进行对照，解释操作如何变化及仍无法推断的内容。不得重复前两段、作空泛总结或添加新的营养生理事实。'][:paragraph_count],
                            'requirement': '完整写完以上小节和段落，围绕不同子问题展开，不返回摘要或提纲；不要复制原文。'},
            'productionSplit': {'minTokens': 300, 'maxTokens': 500, 'overlapTokens': 50,
                                'requiredChunks': [row['minChunks'], row['maxChunks']]},
            'writingRequirements': [
                '目标估算token的规则是一个汉字约计一个token，标点也计数；英文连续单词计一个，非模型计费token。按目标量安排信息，不以重复句子补齐。',
                '新增内容使用完整段落，每个段落围绕一个可核对的子问题；段落独立可读，避免这、它等悬空指代。',
                '针对本主题组织不同维度：概念和适用条件、分步判断、场景差异、误区、对照案例、记录与无法推断的边界。',
                '长主题先列出有区别的子问题再逐一展开，禁止所有小节反复重复同一条原则。',
                '例子不伪装成用户事实，假设标签数字须明示为换算练习，不能变成真实商品数据。',
                '来源只支持一般原则，校园例子是原创应用建议；最终仍需编辑复核。',
            ]}
    return [{'role': 'system', 'content': system},
            {'role': 'user', 'content': json.dumps(user, ensure_ascii=False)}]


def prepare(output, jar):
    output = output.resolve()
    if not output.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Output must be under logs/')
    rows = plan(CORPUS.read_text(encoding='utf-8'))
    output.mkdir(parents=True, exist_ok=False)
    (output / 'before-diet-guides.md').write_bytes(CORPUS.read_bytes())
    (output / 'before-rag-cases.json').write_bytes(CORPUS.with_name('rag_eval_cases.json').read_bytes())
    data = {'status': 'PREPARED_NO_PROVIDER_CALLS', 'corpusSha256': hashlib.sha256(CORPUS.read_bytes()).hexdigest(),
            'splitterDefaults': {'strategy': 'recursive-markdown', 'minTokens': 300, 'maxTokens': 500, 'overlapTokens': 50},
            'distribution': {key: {'topicCount': count, 'minChunks': low, 'maxChunks': high}
                             for key, (count, low, high, _) in BUCKETS.items()},
            'minimumTotalChunks': 1220, 'maximumTotalChunks': 1880, 'topics': rows}
    (output / 'plan.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    lines = ['# 200 主题长文分配计划', '', '按生产默认切分配置验收；主题数与切片数分别统计。', '',
             '| 序号 | 主题 | 长度档 | 允许片段数 |', '| --- | --- | --- | --- |']
    lines += [f"| {row['topicIndex']} | {row['title']} | {row['bucket']} | {row['minChunks']}–{row['maxChunks']} |" for row in rows]
    (output / 'plan.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    (output / 'prompts.json').write_text(json.dumps([{'topicId': row['topicId'], 'messages': prompt(row)} for row in rows], ensure_ascii=False, indent=2), encoding='utf-8')
    # Reuse dependency jars already installed with the local backend; never download anything.
    libraries = output / 'lib'
    libraries.mkdir()
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (libraries / Path(name).name).write_bytes(archive.read(name))
    if not list(libraries.glob('*.jar')):
        raise ValueError('Backend jar must contain BOOT-INF/lib dependencies')
    classes = output / 'classes'
    classes.mkdir()
    crash = ROOT / 'logs/crash'
    crash.mkdir(parents=True, exist_ok=True)
    error_file = str(crash / 'hs_err_pid%p.log')
    classpath = str(ROOT / 'backend/target/classes') + ';' + str(libraries / '*')
    subprocess.run(['javac', '-J-XX:ErrorFile=' + error_file, '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes),
                    str(ROOT / 'backend/src/main/java/com/example/mealcheck/config/AppProperties.java'),
                    str(ROOT / 'backend/src/main/java/com/example/mealcheck/service/MarkdownKnowledgeChunker.java'),
                    str(ROOT / 'tools/evaluation/KnowledgeCorpusTool.java')], check=True, cwd=ROOT)
    runtime = str(classes) + ';' + classpath
    (output / 'runtime.json').write_text(json.dumps({'classpath': runtime}), encoding='utf-8')
    subprocess.run(['java', '-XX:ErrorFile=' + error_file, '-cp', runtime, 'KnowledgeCorpusTool', '--manifest', str(CORPUS), str(output / 'before-chunks.json')], check=True, cwd=ROOT)
    print(json.dumps({'topics': len(rows), 'minimumChunks': 1220, 'maximumChunks': 1880,
                      'providerCalls': 0, 'plan': str(output / 'plan.md')}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' /
                        ('knowledge-longform-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    parser.add_argument('--backend-jar', type=Path, default=ROOT / 'backend/target/mealcheck-backend.jar')
    args = parser.parse_args()
    prepare(args.output_directory, args.backend_jar)
