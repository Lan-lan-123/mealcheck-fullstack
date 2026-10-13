# 可选份量与营养粗略估算

本功能按餐食单独启用，默认关闭。入口位于用户端完整分析及餐食记录详情。
用户跳过时仍可查看识别、结构评分和建议。页面始终显示：

> 营养估算不准确，图片无法准确判断重量、配料和用油量；即使填写份量，结果仍可能存在较大误差，仅供参考。

## 数据与计算口径

- 份量由用户填写，单位为克；来源记录为 `USER_ENTERED`。
- 每 100 克的能量（kcal）、蛋白质、脂肪、碳水（g）也由用户按标签或资料填写；提供任何营养数值时须注明来源。
- 初版没有自动食物匹配或内置营养数据库，不调用模型猜测营养值，也不核实用户填写的标签。
- 计算公式为 `每 100 克数值 × 份量克数 / 100`，计算版本为 `user-supplied-per100g-v1`。总量汇总未舍入数值后，保留一位小数。
- 任一食物缺少份量、任一营养项时，整餐总量为 `null`，状态为 `INCOMPLETE`。未知值与用户明确填写的零区别处理。
- 可以只保存份量，不填写营养数据。只有所有食物的必要数据完整时，才显示整餐粗略估算。
- 估算独立保存，不加入结构评分、用户画像、趋势、AI 上下文或周报达标判断。

## API

现有 JWT 鉴权覆盖以下接口，所有读取与修改验证餐食归属：

- `GET /api/meals/{id}/nutrition`：读取当前状态；首次读取返回 `DISABLED`，不会创建数据或触发模型调用。
- `PUT /api/meals/{id}/nutrition`：提交启用状态、份量和每 100 克数据。

示例（数值仅用于展示请求格式，不是特定食物的营养标准）：

```json
{
  "enabled": true,
  "expectedRevision": 0,
  "expectedVersion": 0,
  "items": [
    {
      "foodIndex": 0,
      "grams": 150,
      "per100g": {
        "energyKcal": 200,
        "proteinGrams": 10,
        "fatGrams": 5,
        "carbohydrateGrams": 20
      },
      "source": "用户提供的标签或资料"
    }
  ]
}
```

`foodIndex` 对应读取响应中的食物索引，每项当前食物必须提交一次；未知数值提交 `null`。
`expectedRevision` 使用读取响应的 `recordRevision`，`expectedVersion` 使用响应的 `version`。
食物版本或估算版本冲突返回 409，需重新读取后再提交。份量与数值最多支持两位小数。

关闭时提交 `enabled: false` 和空 `items`。保存关闭会清除输入、来源和估算，保留版本以拒绝迟到修改。

## 一致性与部署

- Flyway **V26** 新建 `meal_nutrition_snapshots`，历史餐食不回填、不自动启用。
- 写入复用用户行锁，与上传、识别纠错、餐食删除和账户删除串行化。
- 识别纠错改变餐食修订号后，读取返回 `STALE`，不返回旧份量和营养数值；用户须按新食物重新填写。
- 保存的是输入与计算结果快照，读取不会按未来算法静默重算。
- 删除餐食或注销账户后，关联快照通过外键级联清除。
- 部署先升级后端并确认 V26 执行成功，再发布前端。
- 遗留 Hibernate 数据库还需应用 V27，移除阻止原 V6 删除策略生效的重复外键；新库与旧库升级回归均已验证。
- 没有食物项时前端禁止开启估算，提示先纠正识别；不完整快照显示已具备完整资料的食物项数量，仍不生成整餐总量。

## 验证

- 后端计算与 HTTP 参数验证：`mvn "-Dtest=MealNutritionCalculatorTest,MealNutritionControllerTest" test`
- 隔离 PostgreSQL 测试：`mvn "-Dtest=MealNutritionCalculatorTest,MealNutritionControllerTest" "-Dit.test=MealNutritionDatabaseIntegrationTest" verify -Pintegration`
- 前端：`npm test`、`npm run build`、`npm run test:e2e -- meal-nutrition.spec.js`
- Windows 浏览器路径可用已有 `PLAYWRIGHT_CHROMIUM_EXECUTABLE` 配置指定。

测试覆盖未知值、明确零值、换算与舍入、来源校验、默认关闭、保存、关闭清理、食物纠错失效、归属限制、并发版本冲突、级联删除和移动端布局。

2026-10-11 已在本地 Docker 验证实际页面与 API（没有拦截 API）：临时账户保存、重开、关闭清理和空食物入口通过，合成数据已清理。详细命令、镜像身份及备份恢复证据见 [README 部署记录](../README.md)。真实模型与真实食品营养数据不属于这些工程验收结果。
