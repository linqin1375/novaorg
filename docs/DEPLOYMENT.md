# NovaOrg 部署与运行

## 环境与默认启动

安装 JDK 17。Maven Wrapper 随源码提供，首次构建需下载依赖。默认规则模型与本地特征检索不需要密钥，不调用付费 API。

Windows PowerShell：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start.ps1 -Mode demo -Port 8093
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\show-credentials.ps1
```

在自己的终端查看本机账号；访问 http://127.0.0.1:8093/ 。operator 提出退款申请，另一 reviewer 账号核对后审批。示例：“订单 A100 请申请全额退款 129 元，理由是超时未发货”；再试一个没有订单号的申请，补充订单号后继续。

## Linux macOS

```sh
bash ./mvnw -B -ntp verify
read -r -s -p 'Operator password (12+ chars): ' NOVAORG_OPERATOR_PASSWORD; echo
read -r -s -p 'Reviewer password (12+ chars): ' NOVAORG_REVIEWER_PASSWORD; echo
read -r -s -p 'Admin password (12+ chars): ' NOVAORG_ADMIN_PASSWORD; echo
export NOVAORG_OPERATOR_PASSWORD NOVAORG_REVIEWER_PASSWORD NOVAORG_ADMIN_PASSWORD
export NOVAORG_LLM_MODE=demo NOVAORG_EMBEDDING_MODE=local
java -jar target/novaorg-1.0.0.jar --server.port=8093
```

首次启动密码需要 12–72 字节，不能使用 CHANGE_ME 开头的占位符。上述 read 命令适用于 Bash；请在 Bash 内运行。数据保存到 `data/`，不要提交数据库或账号文件。

## 真实 API 配置

Windows 用隐藏输入配置自己的密钥：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\configure-api.ps1 -LlmBaseUrl 'https://api.deepseek.com/v1' -LlmModel 'deepseek-flash' -EmbeddingBaseUrl 'https://open.bigmodel.cn/api/paas/v4' -EmbeddingModel 'embedding-3' -EmbeddingDimensions 1024
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start.ps1 -Mode live -Port 8093 -SkipBuild
```

两类服务可独立更换；仅使用真实聊天可在配置命令加 `-ChatOnly`。配置以 DPAPI 加密保存在 `.local/api-config.json`，只能在原 Windows 用户下解密。仓库不提供运行者的密钥或登录密码。非 Windows 可通过环境变量设置 `NOVAORG_LLM_*` 与 `NOVAORG_EMBEDDING_*`，字段对应 application.yml。

live 提交任务会产生 API 调用；启动真实 embedding 时会向量化知识库。当前使用内存精确检索与进程内缓存，未使用独立向量数据库。错误不自动回退到本地特征。

## 验证

```powershell
.\mvnw.cmd -B -ntp verify
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\evaluate.ps1 -Mode offline -MaxCases 43 -SkipBuild
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\acceptance.ps1 -Port 8093
```

acceptance 会在独立数据库验证追问、审批和恢复，最后以 demo 模式恢复默认数据库。如果要继续 live，重新执行 stop 和 live 启动。真实检索/聊天评测通过显式 AllowNetwork 开关开启，不在普通测试中付费调用。

## 停止 日志与备份

Windows 后台模式使用 `scripts/stop.ps1`，前台 Java 用 Ctrl+C。脚本运行 `.local/runtime/novaorg.jar` 副本，避免占用 target 影响重新打包。改代码后先成功构建，再重启；`-SkipBuild` 只适用于已有成功构建。

- 数据库：data/novaorg.mv.db；备份前停止服务，再复制 data。
- 登录账号：.local/accounts.dpapi.json；不要上传，也不要仅删除它来重置已有数据库密码。
- 日志：.local/server.out.log 与 server.err.log。
- 服务只监听 127.0.0.1；没有把沙箱记账改成真实退款。

历史证据对应各报告时间，不等于新机器或公网生产环境也已经通过验收。
