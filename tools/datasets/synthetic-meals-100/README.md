# MealCheck 合成图片回归样本

本目录包含 100 张由内置 imagegen 逐张生成的独立图片：80 张饮食图片、20 张非食物图片。16 组饮食搭配和 4 组非食物场景，各覆盖正常光线、弱光、部分遮挡、逆光、近距离拍摄 5 种条件。

`images/` 保存图片，`prompts.json` 保存每张图片的生成提示词及初始标签。全部图片生成后，运行下列命令生成 SHA256 校验清单、尺寸与数量统计，以及可逐张审核的画廊：

```powershell
python tools/prepare_synthetic_dataset.py
```

打开 `gallery.html` 可以查看全部图片，修正食物标签与分类。标签初始状态为 `proposed-needs-human-review`，来自生成场景，不能视为独立标注。只有逐张确认、填写审核人并勾选审核声明后，才可导出 `dataset.reviewed.json`。导出文件应保存回本目录，以保持图片相对路径有效。

合成数据的用途是回归测试和失败场景探索，报告会明确标记 `synthetic-regression-only`。即使经人工审核，也不能代表真实照片上的准确率；正式质量验收仍应使用独立标注的真实照片。不要把数据集改为 `real`，或自动勾选人工审核状态。

使用已有预测文件进行合成回归评分：

```powershell
python tools/vision_evaluation.py --dataset tools/datasets/synthetic-meals-100/dataset.json --allow-synthetic --predictions tools/vision-results/predictions.json --output tools/vision-results/synthetic-report.json
```

调用正式识别服务时，先按项目运行说明配置 `MEALCHECK_EVALUATION_TOKEN`，再使用 `--run --base-url <服务地址>` 替代 `--predictions`。该操作会调用模型并产生供应商费用。合成数据不允许设置发布质量阈值。

图片属于生成产物，默认不纳入 Git。保留本地 `images/`，以及提示词、校验清单和审核结果，即可复查样本来源与内容。当前生成任务只提供样本及工具，不包含正式供应商识别结果。

## 2026-10-16 助手目视核对版

原始 `dataset.json`、`prompts.json` 和原画廊保留不变。新增的
`dataset.assistant-reviewed-v2.json` 来自同一批 100 张图片的助手目视核对，
修正包子等可见差异，降低未确认做法/口味的标签粒度，补充明确的同义名称和切片形态。
`label-audit-v2.json` 保存每组观察、存疑项及修订依据；`label-changes-v2.json`
保留 50 张涉及名称或别名变化的逐项前后对照，其中 15 个食物主名称发生变化。
全部图片的原始 SHA-256 不变。

复核版仍是 `datasetKind=synthetic`、`independentlyAnnotated=false`，
不是独立人工标注。具体做法、白色饮品成分、含糖状态及部分食材品种仍有待确认。
`oily/fried` 与基础食物类别的维度冲突没有通过修改标签掩盖。
不要把不同标签版本上的指标变化称为模型准确率提升。

可重复构建（需要本地 `images/`；不调用模型；不覆盖原数据集）：

```powershell
python tools/evaluation/build_audited_labels.py
```

已有预测可直接使用上述 `vision_evaluation.py --predictions` 命令进行离线评分，
将 `--dataset` 显式指向 `dataset.assistant-reviewed-v2.json`。
以后运行 `prepare_synthetic_dataset.py` 只会重建原始 v1 文件，不会修改复核版；
若 v1 哈希变化，需要重新审阅修订依据，构建器将拒绝套用旧审核。

完整 100 张真实供应商组件测试与后续离线修复验证，分别见
[全量测试报告](../../../deploy/FULL100_MEAL_QUALITY_20261016.md) 和
[第一轮修复验证](../../../deploy/ROUND1_FIXES_VERIFICATION_20261016.md)。
