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
