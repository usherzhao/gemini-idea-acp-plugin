# Gemini 对话编程助手 (ACP) — IntelliJ IDEA 插件

基于官方 **Agent Client Protocol (`gemini --acp`)** 的 IntelliJ IDEA 插件（兼容 IDEA 2025.2 ~ 2026.x），支持通过 **`oauth-personal`**（个人 Google 账号 OAuth）登录，并在 IDE 侧边栏进行对话式代码问答与安全的 Diff 代码修改。

## 核心功能

- **官方合规 `oauth-personal` 登录**：通过 `@google/gemini-cli` 的 `--acp` 协议调用 `initialize` → `authenticate("oauth-personal")`，自动拉起浏览器登录或复用本地 `~/.gemini` 凭据；默认在子进程中屏蔽环境变量 `GEMINI_API_KEY`，避免覆盖 `oauth-personal` 配置。
- **开箱即用的环境兜底**：自动探测 `PATH` / `npm` / `pnpm` / `yarn` / `nvm` / `volta` 下的 `gemini`；未全局安装时支持自动回退 `npx -y @google/gemini-cli`，并提供一键「终端安装/登录」按钮。
- **轻量级位置引用与 `@` / `/` 模糊搜索补全**：
  - 在对话框输入 **`@`** 弹出非抢焦点模糊搜索弹窗（优先显示已打开文件，支持文件名、驼峰首字母如 `osvc`→`OrderService.java` 与跨段路径模糊匹配），输入框内精简展示 `@文件名`（或 `@文件名#L起-L止`），鼠标悬停可查看完整路径，发送给 AI 时自动携带精确完整路径；
  - 在左侧项目文件树、编辑器标签页或顶部导航栏 **右键单选/多选文件或目录 → 引用文件到 Gemini 对话框**，直接将 `@文件名` 引用带入对话框；
  - 输入 **`/`** 模糊搜索并选择系统已安装的 Skills 与官方斜杠命令列表（自动合并 ACP `available_commands_update` 与本地 `SKILL.md`）；
  - 在编辑器中选中单行或多行后 **右键 → Gemini 对话编程助手 → 引用选中行/当前行到 Gemini 对话框**，仅传递文件路径与起止行号（不粘贴整段源码）。
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
2. 选择 `build/distributions/gemini-idea-acp-plugin-1.0.10.zip`，点击 **确定 (OK)**（若界面提示重启 IDE，请务必点击 **重启 IDE** 让插件生效）。
3. 打开右侧边栏 **`Gemini 助手`** 工具窗口：
   - 插件将自动优先探测本地 **Antigravity CLI (`agy acp`)**，点击 **`登录/连接`** 即可复用或完成个人 Google 账号登录；
   - 需要更换 Google 账号或重新授权时，点击顶部 **`切换账号`** 即可通过官方 ACP `logout` 退出当前账号并重新拉起登录（也支持选择「仅退出登录」）；
   - 连接后会自动从官方 ACP `configOptions` 加载 **模型**、**思考强度** 与 **模式** 下拉菜单并展示在底部输入区下方（零硬编码，切换模型时自动联动刷新支持的思考强度选项）；
   - 点击 **`历史记录`** 可查看、搜索、按项目筛选并双击切换历史对话；支持在主界面标题栏或历史记录列表（按钮 / 右键 / `F2`）中随时**修改对话名称**或恢复默认标题；
   - 如尚未登录，点击 **`终端登录`** 将自动运行 `agy acp --login` 完成浏览器授权。
