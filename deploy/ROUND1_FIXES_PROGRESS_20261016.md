# 第一轮修复进度与续接记录（2026-10-16 上午）

**下午续接状态：本记录列出的离线工作已完成。** 详见 [第一轮修复验证](ROUND1_FIXES_VERIFICATION_20261016.md)：后端 330 项回归通过，100 张离线重放完成，助手核对版标签及修订记录已另存；新增模型调用和 token 为 0。下文保留上午暂停时的历史状态。

用户要求：先完成“评分确定性修复＋建议提示词约束＋标签核对”。随后要求“先修改到这里，记录一下，到下午再接着开始修改”。因此当前停止实施，保留工作区修改，待用户下午继续。

## 已完成的改动

1. `backend/src/main/java/com/example/mealcheck/service/MealScoringService.java`
   - 评分版本从 `structured-foods-v3` 更新为 `structured-foods-v4`。没有批量重算历史记录或执行数据迁移。
   - `balanced` 分支只有实际具备主食、蛋白质和蔬菜时，才添加“整体结构虽完整……”；结构不完整时使用“仍需完善餐食结构”。
   - 补充橙汁、苹果汁、葡萄汁、菠萝汁、西瓜汁等常见果汁名称及部分英文名称。新增别名要求类别为 `drink`，避免把“橙汁鸡胸肉”等食物误判成饮品；保留原有无糖规则。
   - 豆浆/牛奶的正向描述及摘要去掉无依据的“早餐”措辞。
2. `backend/src/main/java/com/example/mealcheck/service/AdviceGenerationService.java`
   - 将事实边界放入独立的 system 消息；食物、评分、风险及检索片段放在 user 消息。
   - 约束植物蛋白不能直接称为肉；没有餐次、重量、数量、身体情况时不得编造；结构评分不等于营养摄入测量。
   - 参考知识仅作为一般依据；区分下一餐建议与当前事实；类别/标签矛盾时不机械复述。
   - 保留原有 AI 失败后带明确提示的本地建议降级。
3. 回归测试
   - 扩充 `MealScoringServiceTest.java`：结构完整/不完整的风险文案、常见果汁、完整水果及果汁酱料不误判、饮品描述不推断餐次。
   - 新增 `AdviceGenerationServiceTest.java`：检查 system 与 user 分离、实际请求包含事实约束、场景说明/食物备注不被当作可靠事实输入，以及降级行为。

## 已完成的验证

离线 Maven 针对性回归 **14 项通过，0 失败、0 错误、0 跳过**：评分 12 项、建议 2 项。

日志：`loadtests/results/round1-fixes-20261016/backend-focused.log`。

实际命令（在 backend 目录）：

```powershell
mvn --offline --batch-mode --no-transfer-progress '-Dtest=MealScoringServiceTest,AdviceGenerationServiceTest,MealCorrectionEvaluationServiceTest' test
```

该筛选表达式中的 `MealCorrectionEvaluationServiceTest` 没有匹配到测试类；实际执行和通过的是上述两个测试类，不应声称执行了该类。后续使用真实存在的 `MealCorrectionRegressionTest` 等补充相关回归。

本阶段 **新增真实模型调用为 0，新增供应商 token 为 0**。尚未重新验证真实模型输出是否改善；提示词请求测试不能代替该验证。尚未执行本阶段完整后端回归、100 张离线评分重放或部署。

## 标签核对进度

已生成并查看全部 100 张图片的五张联系表，位于：

`loadtests/results/round1-fixes-20261016/contact-1.jpg` 至 `contact-5.jpg`。

观察到的重点：

- `synth-026` 至 `synth-030` 有明显包子褶口，原标签“馒头”需要修正为“包子”。
- `synth-006` 至 `synth-010` 原标签为“清蒸鱼”，图中存在表面纹理和褐色痕迹，单凭画面不能确认具体做法；可考虑使用更粗粒度的“鱼肉”，保留做法存疑记录。
- `synth-046` 至 `synth-050` 无法从画面确认酸奶是否原味/无糖；不应把这些属性当作已独立验证。
- 绿叶菜具体品种、汤中紫菜/海带、白色饮品的牛奶/豆浆成分与含糖状态，也应记录可见范围和未确认属性。
- 可以补充明确的同义名称或可见形态（如胡萝卜片、苹果切片、花椰菜），不能为了提高匹配率把不同食物或不确定做法直接等同。

**尚未修改任何标签文件，也未生成复核版数据集。** 原始 `dataset.json`、`prompts.json`、`gallery.html` 保持原状。

原始数据集文件 SHA-256：

`532e7bc6cba4577b5d4968bcd64761f2145f9d5cc06373c0853f331864228d83`

复核由本助手完成，不能称为独立人工标注；后续另存版本时保持 `datasetKind=synthetic`、`independentlyAnnotated=false`，不得自动勾选人工审核状态。建议保留原始数据和既有报告，通过新文件、版本号及逐项修订记录保存复核结果。

## 下午续接顺序

1. 检查保留的工作区修改，完成复核版标签及修订记录，保留明确的不确定项。
2. 使用此前保存的真实识别结果，离线重放当前评分，逐图对比旧分数及风险标签；保留原报告，输出独立的新报告。确认 10 张结构文案矛盾和 5 张橙汁提示问题已解决；不新增供应商调用。
3. 用原始标签与复核版标签分别评分同一批预测，明确标签变化与模型质量变化的区别，不把修改标签后的指标提升当作模型提升。
4. 完成相关评分、纠错和建议回归，检查 diff，整理第一轮修复结果与剩余问题。
5. 真实模型的建议复测和再次全量调用留待后续安排；本轮先完成上述离线工作。

仍在后续范围：将食物基础类别与油炸/高油属性拆分。当前没有解决红烧肉 `protein/oily` 的分类冲突，也没有改写炸鸡、薯条的基础营养类别规则，不要误报已经完成这项结构升级。

## 可直接复用的上一轮证据

- 全量真实识别、评分与建议：`loadtests/results/full100-20261016/results.json`。
- 汇总：`loadtests/results/full100-20261016/summary.json`。
- 80 条建议复核：`loadtests/results/full100-20261016/advice-review.json`。
- 原始测试报告：`deploy/FULL100_MEAL_QUALITY_20261016.md`。
- 已有执行工具：`tools/evaluation/`。不应为本轮离线重放重新启动带 `--run` 的真实测试。

工作区此前已有大量未提交修改；本次直接在其上增量编辑，没有提交、部署、重置或清理用户改动。
