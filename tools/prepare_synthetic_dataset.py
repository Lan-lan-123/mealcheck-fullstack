"""Finalize generated images as explicitly synthetic regression fixtures and a review gallery."""
import argparse
import hashlib
import json
from pathlib import Path
import struct

ALIASES = {
    '米饭': ['白米饭', '白饭', '蒸米饭'], '番茄炒蛋': ['西红柿炒鸡蛋', '番茄炒鸡蛋'],
    '清炒小白菜': ['小白菜', '炒小白菜', '青菜'], '清蒸鱼': ['蒸鱼', '鱼肉'],
    '西兰花': ['清蒸西兰花', '蒸西兰花'], '糙米饭': ['糙米'], '鸡胸肉': ['鸡肉'],
    '面条': ['面'], '水煮蛋': ['煮鸡蛋', '水煮鸡蛋', '鸡蛋'],
    '白粥': ['白米粥', '米粥'], '蒸蛋': ['鸡蛋羹', '蒸水蛋'], '黄瓜': ['黄瓜片'],
    '红薯': ['蒸红薯', '地瓜'], '虾': ['白灼虾', '水煮虾', '虾仁'],
    '卷心菜': ['包菜', '炒包菜', '炒卷心菜'], '玉米': ['玉米棒', '水煮玉米'],
    '炒鸡蛋': ['炒蛋'], '原味酸奶': ['酸奶'], '苹果': ['苹果片'], '菜花': ['花菜', '炒花菜'],
    '可乐': ['碳酸饮料'], '茄子': ['炒茄子'], '豆浆': ['豆奶'],
    '燕麦粥': ['燕麦'], '香蕉': ['香蕉片'], '巧克力蛋糕': ['蛋糕'], '橙汁': ['鲜橙汁']
}

GALLERY = r'''<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>MealCheck · 合成图片样本审核</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#f5f7fb;color:#172b3a;font:15px/1.6 system-ui,sans-serif}header{padding:28px 5vw;background:white;border-bottom:1px solid #dce4ef}h1{margin:0 0 8px;font-size:26px}p{margin:6px 0;color:#526574}.controls{display:flex;gap:12px;flex-wrap:wrap;align-items:center;margin-top:14px}input,select,textarea,button{font:inherit;border:1px solid #cdd7e1;border-radius:8px;padding:8px;background:white}button{cursor:pointer;color:white;background:#16755e}button:disabled{opacity:.5;cursor:not-allowed}main{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:18px;padding:24px 5vw}.sample{background:white;border:1px solid #dce4ef;border-radius:12px;overflow:hidden}.sample img{display:block;width:100%;aspect-ratio:1;object-fit:contain;background:#e8edf1}.body{padding:14px}.body h2{font-size:16px;margin:0 0 4px}.badge{font-size:12px;background:#e7f3ee;color:#17634e;padding:3px 8px;border-radius:4px}textarea{width:100%;min-height:130px;margin:8px 0;font:12px/1.5 monospace}.review{display:block;padding:8px 0}.review input{accent-color:#16755e}.footer{padding:12px 5vw}details{font-size:12px;color:#647480}#notice{color:#a84c16}label{cursor:pointer}a{color:#16755e}
</style>
<header><h1>MealCheck · 100 张合成测试图片</h1>
<p>80 张饮食图片、20 张非食物图片，覆盖正常光线、弱光、遮挡、逆光和近距离拍摄。图片由内置 imagegen 逐张生成。</p>
<p id="notice">预期标签来自生成场景，尚未独立人工审核。该数据集用于合成回归测试，不能用于真实照片准确率或发布质量结论。</p>
<div class="controls"><label>筛选 <select id="filter"><option value="all">全部样本</option><option value="food">饮食图片</option><option value="nonfood">非食物图片</option><option value="unreviewed">待审核</option></select></label><span id="progress"></span></div>
<div class="controls"><input id="reviewer" placeholder="审核人姓名或标识" aria-label="审核人"><label><input id="attest" type="checkbox">我已逐张查看图片并核对标签</label><button id="export" disabled>导出已审核标签</button></div>
</header><main id="samples"></main><div class="footer">原始数据：<a href="dataset.json">dataset.json</a> · 生成场景：<a href="prompts.json">prompts.json</a></div>
<script>
const dataset=__DATASET__;
const sceneLabels={'normal':'正常光线','low-light':'弱光','occlusion':'部分遮挡','backlight':'逆光','close-up':'近距离'};
const storageKey='mealcheck:synthetic-review:'+dataset.version;
let saved={};try{saved=JSON.parse(localStorage.getItem(storageKey)||'{}')}catch{}
const samples=document.getElementById('samples');
for(const item of dataset.cases){
 const prior=saved[item.id]||{};const card=document.createElement('article');card.className='sample';card.id=item.id;
 const img=document.createElement('img');img.src=item.image;img.alt=item.id+' 合成样本';img.loading='lazy';card.append(img);
 const body=document.createElement('div');body.className='body';card.append(body);
 const heading=document.createElement('h2');heading.textContent=item.id+' · '+(sceneLabels[item.scene]||item.scene);body.append(heading);
 const badge=document.createElement('span');badge.className='badge';badge.textContent='AI 生成 · '+(item.foodImage?'饮食':'非食物');body.append(badge);
 const foodLabel=document.createElement('label');foodLabel.style.display='block';const food=document.createElement('input');food.type='checkbox';food.checked=prior.foodImage??item.foodImage;foodLabel.append(food,document.createTextNode(' 图片包含食物'));body.append(foodLabel);
 const editor=document.createElement('textarea');editor.setAttribute('aria-label',item.id+' 食物标签');editor.value=prior.foods??JSON.stringify(item.foods,null,2);body.append(editor);
 const label=document.createElement('label');label.className='review';const reviewed=document.createElement('input');reviewed.type='checkbox';reviewed.checked=prior.reviewed===true;label.append(reviewed,document.createTextNode(' 已逐张核对并修正标签'));body.append(label);
 const details=document.createElement('details');const summary=document.createElement('summary');summary.textContent='查看生成提示词';const prompt=document.createElement('p');prompt.textContent=item.prompt;details.append(summary,prompt);body.append(details);
 item.review={card,food,editor,reviewed};
 const change=()=>{saved[item.id]={foodImage:food.checked,foods:editor.value,reviewed:reviewed.checked};try{localStorage.setItem(storageKey,JSON.stringify(saved))}catch{}update()};
 food.addEventListener('change',()=>{reviewed.checked=false;change()});editor.addEventListener('input',()=>{reviewed.checked=false;change()});reviewed.addEventListener('change',change);samples.append(card);
}
function update(){const count=dataset.cases.filter(c=>c.review.reviewed.checked).length;document.getElementById('progress').textContent='已审核 '+count+' / '+dataset.cases.length;document.getElementById('export').disabled=count!==dataset.cases.length||!document.getElementById('reviewer').value.trim()||!document.getElementById('attest').checked;const filter=document.getElementById('filter').value;for(const c of dataset.cases)c.review.card.hidden=filter==='food'&&!c.foodImage||filter==='nonfood'&&c.foodImage||filter==='unreviewed'&&c.review.reviewed.checked;}
for(const id of ['reviewer','attest','filter'])document.getElementById(id).addEventListener('input',update);
document.getElementById('export').addEventListener('click',()=>{
 try{
  const valid=new Set(['staple','protein','vegetable','fruit','dairy','soup','drink','dessert','fried','oily','other']);
  const cases=dataset.cases.map(({review,...c})=>{const foods=JSON.parse(review.editor.value);if(!Array.isArray(foods)||review.food.checked!==Boolean(foods.length)||foods.some(f=>!f.name?.trim()||!valid.has(f.category)||(f.aliases!==undefined&&(!Array.isArray(f.aliases)||f.aliases.some(a=>typeof a!=='string')))))throw Error(c.id+' 的食物标签或分类无效');return {...c,foodImage:review.food.checked,foods,annotationStatus:'human-reviewed'};});
  const output={...dataset,version:dataset.version+'-reviewed-'+new Date().toISOString(),independentlyAnnotated:true,reviewer:document.getElementById('reviewer').value.trim(),reviewedAtUtc:new Date().toISOString(),cases};
  const url=URL.createObjectURL(new Blob([JSON.stringify(output,null,2)],{type:'application/json'}));const link=document.createElement('a');link.href=url;link.download='dataset.reviewed.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
 }catch(error){alert(error.message)}
});update();
</script></html>'''


def prepare(directory, expected_count=100):
    manifest = json.loads((directory / 'prompts.json').read_text(encoding='utf-8'))
    cases = manifest['cases']
    if len(cases) != expected_count: raise ValueError(f'Expected {expected_count} scenarios, found {len(cases)}')
    root = directory.resolve()
    hashes = set()
    total_bytes = 0
    for case in cases:
        image = (directory / case['image']).resolve()
        if not image.is_relative_to(root) or not image.is_file(): raise ValueError(f'Missing or unsafe image: {case["id"]}')
        content = image.read_bytes()
        if content[:8] != b'\x89PNG\r\n\x1a\n' or content[12:16] != b'IHDR': raise ValueError(f'Invalid PNG: {case["id"]}')
        width, height = struct.unpack('>II', content[16:24])
        if min(width, height) < 512: raise ValueError(f'Image is too small: {case["id"]}')
        digest = hashlib.sha256(content).hexdigest()
        if digest in hashes: raise ValueError(f'Duplicate image content: {case["id"]}')
        hashes.add(digest)
        total_bytes += len(content)
        case.update(sha256=digest, width=width, height=height, source='ai-generated', annotationStatus='proposed-needs-human-review')
        for food in case['foods']:
            if food['name'] in ALIASES: food['aliases'] = ALIASES[food['name']]
    dataset = {**manifest, 'datasetKind': 'synthetic', 'authorizedForEvaluation': True, 'independentlyAnnotated': False,
               'generator': 'built-in imagegen', 'intendedUse': 'synthetic-regression-only', 'cases': cases}
    serialized = json.dumps(dataset, ensure_ascii=False, indent=2)
    (directory / 'dataset.json').write_text(serialized + '\n', encoding='utf-8')
    (directory / 'gallery.html').write_text(GALLERY.replace('__DATASET__', serialized.replace('<', '\\u003c')), encoding='utf-8')
    summary = {'images': len(cases), 'foodImages': sum(case['foodImage'] for case in cases), 'nonFoodImages': sum(not case['foodImage'] for case in cases),
               'uniqueChecksums': len(hashes), 'totalMegabytes': round(total_bytes / 1024**2, 2),
               'scenes': {scene: sum(case['scene'] == scene for case in cases) for scene in sorted({case['scene'] for case in cases})}}
    (directory / 'validation.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset-dir', type=Path, default=Path(__file__).resolve().parent / 'datasets/synthetic-meals-100')
    parser.add_argument('--expected-count', type=int, default=100)
    args = parser.parse_args()
    print(json.dumps(prepare(args.dataset_dir, args.expected_count), ensure_ascii=False))
