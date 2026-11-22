# 18 张识图边界图片

2026-10-24。使用内置 image_gen 逐张生成，原图复制到本目录的 `images/`；没有使用 CLI 后备，也没有使用真实用户照片。生成途中遇到间歇性 HTTP 403，保留最终成功图，不把生成失败计为识图失败。

每张图已在识图调用前由助手查看并固定标签，`dataset.json` 包含图片 SHA-256、可接受结果、类别和名称范围，以及不能编造实际克数/容量等限制。不是独立人工金标，也不声明被完全遮住的容器实际有什么内容。

- `leftover-01` 至 `leftover-04`：少量米饭、西兰花、面包、面条，仍应识别为食物。
- `leftover-05`、`leftover-06`：仅有酱汁痕迹、油渍的空餐具，不应生成食物份量。
- `occlusion-01`、`occlusion-02`：部分遮挡但仍能辨认米饭和青菜，只识别可见食物。
- `occlusion-03`：严重遮挡，仅模糊色块；允许 IMAGE_UNCLEAR 或低置信度未确认候选，不能计分。
- `occlusion-04` 至 `occlusion-06`：盖子或厚布完全遮住内容，不能直接判为空盘，也不能猜菜名。
- `package-01`、`package-02`：可见面包/明确标签的牛奶；标签未提供营养或份量数字。
- `package-03`、`package-04`：不透明无标签袋、封盖纸杯；允许 IMAGE_UNCLEAR 或低置信度未确认候选，不猜内容或含糖量。
- `package-05`、`package-06`：透明且打开的空袋、空餐盒，应为非食物。

`occlusion-04` 的实际生成图完全封盖，与生成提示词预想的小缝隙不同，已经依据实际画面固定为 IMAGE_UNCLEAR。`occlusion-01` 实际约半碗遮住，不充作无法辨认的严重遮挡样本。

浏览 [图片与预期总览](index.html)，点击图片可以查看原图。完整生成提示词及来源路径见 `generation-index.json`，生成公共提示词也保留在 `dataset.json`。

评测入口为 `VisionEdgeLiveTest`，默认不运行、不联网。显式开启后使用生产识图提示词、HTTP 传输、解析、候选过滤与评分，向量和数据库客户端均不存在。每张图先测一次；然后对 `occlusion-03/04`、`package-03/04` 各再测两次，共 26 次。重复次序和样本在调用前确定，不因失败挑选重跑；供应商错误立即停止，没有自动重试。

预检使用 `mealcheck.visionEdgePrepareOnly=true`，只验证文件、标注和请求数量，不读取 `.env` 密钥或调用供应商。真实测试必须先获得本批图片向现有百炼接口外发的授权，输出目录必须在项目 `logs/` 下且为空。可用以下参数配合 `tools/run_logged.py` 在 `backend` 中运行 Maven：

```text
-Dtest=VisionEdgeLiveTest
-Dmealcheck.visionEdgeLive=true
-Dmealcheck.visionEdgeOutput=E:/mealcheck-fullstack/logs/backend/<新的空目录>
-Dmealcheck.testReportsDirectory=E:/mealcheck-fullstack/logs/backend/<新的报告目录>
```

`VisionEdgeCriteriaTest` 验证评测器不会把低置信度的虚构具体食品或高置信度的泛称自动算为安全；`VisionBoundaryTest` 覆盖解析和低置信度过滤的本地契约。它们不证明真实模型的识图准确率。
