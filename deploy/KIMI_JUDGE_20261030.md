# 阿里云 Kimi K3 独立评分模型接入（2026-10-30）

## 已生效配置

- 裁判模型：`kimi-k3`。
- 裁判 Base URL：`https://dashscope.aliyuncs.com/compatible-mode/v1`，使用 Chat Completions 接口。
- 裁判密钥：用户提供的原阿里云密钥，仅写入被 Git 忽略的本地 `.env`，不在本文或证据输出中记录。
- 线上回答及视觉模型仍为原来的 SiliconFlow `Qwen/Qwen3.8-27B`，原回答地址及密钥保持原值。
- 裁判单次请求继续受现有60秒与2048输出token限制；本轮未修改全局预算、并发、知识库或默认回答模式。

## 代码与部署

`AiChatRequestPolicy` 对阿里云 `kimi-k3` / `kimi/kimi-k3` 的 `assistant-benchmark-judge` 请求使用温度1.0、开启思考并移除不支持的 `thinking_budget`。该适配限定于阿里云地址和评分操作，不改变其他提供商或生产问答的参数。依据：[阿里云Kimi文档](https://help.aliyun.com/zh/model-studio/kimi-api)。

`AssistantBenchmarkJudgeClient` 在裁判地址与生成地址不同时要求显式裁判密钥，避免遗漏配置时把SiliconFlow密钥回退发送到阿里云。同服务地址下的原有回退行为保留。

开发Compose原有裁判变量继续使用，生产Compose补齐同一组变量；两个环境示例文件提供Kimi配置位置，不包含真实密钥。

最终镜像：`sha256:9928a63ef9d8d047dcc21ce760813d14a02159d0be0197947a843f68119f4344`。

JAR SHA256：`21ad3f906f3c26e9db78cef385206b78ab5c5dc9a968a226baecfb415d06d974`。部署前确认无活动助手、图片任务及AI调用，保留旧镜像标签 `mealcheck-fullstack-backend:before-kimi-judge-20261030`；运行中JAR哈希与已测试打包产物一致。

## 验证结果

- 裁判客户端、请求策略、通用聊天客户端与后台评分专项33项通过。
- 后端默认全量867项：859通过、8跳过、0失败/错误；未执行真实数据库integration profile。
- 模型列表检查HTTP200，包含 `kimi-k3`，不产生模型生成调用。
- 一次直接阿里云API评分验证：请求和返回模型均为`kimi-k3`，HTTP200，`finish_reason=stop`，五维评分均为0～4整数，包含硬门禁数组和理由，JSON结构验证通过。使用项目裁判系统提示词与评分规则，材料为合成白粥重量判断。
- 该次验证最多1次调用、无重试，实际1次，耗时43.594秒；输入929token、输出1584token（其中思考1420、正文164）。这是直接API连接及协议验证，不经过后台评测任务、账号准入或应用日配额，不代表完整后台批量评分已经验收。
- 部署后前后端健康，裁判变量存在且密钥非空，原生成配置一致。知识片段1351、重建任务2、迁移版本V53保持不变。

本轮未对之前保存的100份答案启动评分，未消耗旧测试授权余额。长样本可能触及现有60秒/2048token上限，后续评分应先校准并检查截断；本次成功不证明裁判判断质量或批量成功率。

## 本地证据

- 模型列表、评分请求（不含密钥）、评分结果：`logs/backend/kimi-judge-connectivity-20261030/`
- 专项：`logs/backend/kimi-judge-unit-20261030-141439-381373.log`
- 全量：`logs/backend/kimi-judge-full-unit-20261030-141718-536192.log`
- 打包：`logs/backend/kimi-judge-package-20261030-141934-256533.log`
- 镜像：`logs/deploy/kimi-judge-image-20261030-141957-489005.log`
- 部署：`logs/deploy/kimi-judge-deploy-20261030-142126-452091.log`
- 测试产物及部署前后核对：`logs/deploy/kimi-judge-runtime-20261030/`

新增日志均位于`logs/`，根目录4份历史`.log`文件大小保持不变。真实`.env`由Git忽略，也不进入Docker构建上下文。
