# Gemini 对话编程助手 (ACP) — IntelliJ IDEA 插件

基于官方 **Agent Client Protocol (`gemini --acp`)** 的 IntelliJ IDEA 插件（兼容 IDEA 2025.2 ~ 2026.x），支持通过 **`oauth-personal`**（个人 Google 账号 OAuth）登录，并在 IDE 侧边栏进行对话式代码问答与安全的 Diff 代码修改。

## 核心功能

- **官方合规 `oauth-personal` 登录**：通过 `@google/gemini-cli` 的 `--acp` 协议调用 `initialize` → `authenticate("oauth-personal")`，自动拉起浏览器登录或复用本地 `~/.gemini` 凭据；默认在子进程中屏蔽环境变量 `GEMINI_API_KEY`，避免覆盖 `oauth-personal` 配置。
- **开箱即用的环境兜底**：自动探测 `PATH` / `npm` / `pnpm` / `yarn` / `nvm` / `volta` 下的 `gemini`；未全局安装时支持自动回退 `npx -y @google/gemini-cli`，并提供一键「终端安装/登录」按钮。
- **上下文感知对话**：自动附带当前编辑器文件路径、选中代码片段与行号范围；支持编辑器右键菜单 **向 Gemini 询问选中代码...** / **用 Gemini 对话编辑代码...**。
- **原生 IDEA Diff 预览确认**：Agent 通过 ACP `fs/write_text_file` 修改或新建文件前，会弹出 IntelliJ IDEA 原生语法高亮 **Diff 对比弹窗**；确认后通过 `WriteCommandAction` 写入（支持 `Ctrl+Z` 撤销与 VFS 同步）。
- **内置诊断日志与工作区保护**：侧边栏内置可折叠「日志」面板查看实时 CLI/ACP 输出，并拦截越权访问项目根目录之外的文件。

## 构建与运行

```bash
# 运行全部单元测试与 IntelliJ Platform 集成测试
./gradlew test

# 校验插件结构并构建可安装的 ZIP 包（输出到 build/distributions/）
./gradlew verifyPluginProjectConfiguration verifyPluginStructure buildPlugin

# 启动带插件的 IDEA 沙盒实例进行调试
./gradlew runIde
```

## 安装到 IntelliJ IDEA

1. 打开 **`设置 (Settings) -> 插件 (Plugins) -> ⚙️ -> 从磁盘安装插件... (Install Plugin from Disk...)`**。
2. 选择 `build/distributions/gemini-idea-acp-plugin-1.0.6.zip`，点击 **确定 (OK)**（若界面提示重启 IDE，请务必点击 **重启 IDE** 让插件生效）。
3. 打开右侧边栏 **`Gemini 助手`** 工具窗口：
   - 插件将自动优先探测本地 **Antigravity CLI (`agy acp`)**，点击 **`oauth-personal 登录/连接`** 即可复用或完成个人 Google 账号登录；
   - 连接后会自动从官方 ACP `configOptions` 加载 **模型**、**思考强度** 与 **模式** 下拉菜单并展示在底部输入区下方（零硬编码，切换模型时自动联动刷新支持的思考强度选项）；
   - 点击 **`历史记录`** 可查看、搜索、按项目筛选并双击切换历史对话；支持在主界面标题栏或历史记录列表（按钮 / 右键 / `F2`）中随时**修改对话名称**或恢复默认标题；
   - 如尚未登录，点击 **`终端登录`** 将自动运行 `agy acp --login` 完成浏览器授权。
