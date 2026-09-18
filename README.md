# Supernote Read AI

面向 Supernote 墨水屏设备的悬浮式 AI 阅读助手，可选择文字或圈选 PDF、EPUB 等阅读页面中的区域，把内容发送给 AI 解读，并将阅读对话导出为 Markdown。

> **二次开发声明**
>
> 本项目基于 [James-Zhu-CA/ReadAssist](https://github.com/James-Zhu-CA/ReadAssist) 进行二次修改，原项目采用 MIT License。本仓库不是原项目的官方版本，也不代表原作者立场；原项目版权声明和 MIT 许可证保留在 [LICENSE](LICENSE) 中。

当前源码版本：`1.14.7-supernote-clipboard-bridge`

## 下载选择

请从 [GitHub Releases](https://github.com/lujun723-win/supernote-read-ai/releases) 下载：

| 文件 | 适用情况 |
| --- | --- |
| `ReadAssist-v1.14.7-supernote-clipboard-bridge-deepseekBundled.apk` | 推荐。内置 3,402,564 词的 ECDICT，安装后在设置页一键部署 |
| `ReadAssist-v1.14.7-supernote-clipboard-bridge-deepseek.apk` | 体积较小，不带词库，需要自行导入 StarDict |

两个 APK 的应用 ID 相同，可以互相覆盖安装；不要同时安装。发布包使用 Android 调试证书签名，仅适合侧载使用。

## 主要修改

- 增加 DeepSeek 官方 API，可在 Gemini、SiliconFlow 和 DeepSeek 之间切换。
- 将单一悬浮入口改为相连的 `AI` / `翻` 双按钮；两者可一起拖动。
- AI 支持“文字选择”和“区域截图”两种取材模式，顶部按钮负责切换，双击 `AI` 会沿用上次模式。
- 文字选择模式会等待本次新复制内容；复制完成后自动打开 AI 窗口并只导入一次。没有新复制内容时不会弹窗。
- 增加适配 Supernote 的区域截图流程：双击悬浮图标后直接圈选，截图完成后进入 AI 对话。
- 增加离线 StarDict 字典：支持 `.ifo`、`.idx` / `.idx.gz` 和 `.dict` / `.dict.dz`；直接读取原生索引，不再把全部词条写入 SQLite。
- `翻`按钮支持手动输入查词；先复制文字再单击 `翻`，剪贴板文字会自动填入查词框，但不会自动发起查询。查词窗口顶部可直接选择“选择文本”或“圈选 OCR”，双击则沿用上次选择的方式。
- 中英文 OCR 模型随 APK 安装，圈选识别和词典查询都在设备本地完成。
- 词典圈选 OCR 按真实手写圈的形状保留像素，圈外区域填白；利用文字间的完整空白分段，并清除与圈选边界相交的首尾残片。同时允许单个单词大小的选区，并将 OCR 查询词统一为小写。AI 圈选仍保留上下文扩边。
- 圈选截图默认附加到下一条问题，并显示“发送区域截图”勾选项；取消勾选会丢弃本次图片。
- 单击悬浮图标打开对话，双击判定时间为 0.8 秒。
- 对话窗口只显示本次问题和 AI 回答，完整记录保存在“历史记录”中。
- 点击“新对话”后保存上一轮并清空当前窗口；关闭再打开不会恢复刚刚清空的旧对话。
- 当前问答可直接导出；历史记录支持按文档、日期查看并多选导出。文件保存到 `EXPORT/日期/` 下，格式为 Markdown。
- 通过 Supernote 手写服务为 `AI`、`翻`、取消按钮、区域选择层及弹窗注册禁写区域；即使当前使用书写笔，操作这些控件也不会在底层文档留下笔迹。
- 区域截图期间隔离 Supernote 的悬停剪贴板事件，避免透明窗口、弹窗竞争和服务退出。
- 修正独立包名下的无障碍服务识别与授权状态判断。
- 移除旧版自动监听并发送整屏截图的功能；图片只来自用户主动发起的区域圈选。
- 调整按钮、输入框和弹窗布局，使其更适合黑白墨水屏。

## 使用方法

1. 安装 APK，并允许“安装未知应用”。
2. 打开 ReadAssist，授予悬浮窗权限和无障碍服务权限。
3. 选择 AI 平台、模型并填写自己的 API Key。
4. 启动悬浮按钮服务。
5. 单击 `AI` 打开对话，在顶部选择“文字选择”或“区域截图”。
6. 在 0.8 秒内双击 `AI`，应用会沿用上次模式：
   - 文字选择：手动点 Supernote 左侧工具栏的“文字选择”，选中文字并复制，AI 窗口会自动打开。
   - 区域截图：圈出要分析的内容，随后在 AI 窗口中确认并发送。

Supernote 当前固件没有向第三方开放“切换到文字选择工具”的后台接口，因此 ReadAssist 不模拟固定坐标或触控手势；工具栏位于左侧或右侧都不会被程序硬编码。进入文字选择模式后，需要用户手动切换一次系统工具。

### 导出 Markdown

- 在 AI 窗口完成一次问答后，可直接导出本次问题和回答。
- 在历史记录中可以选择部分对话、整个会话或全部记录后导出。
- 第一次导出时必须授权 Supernote 根目录下的 `EXPORT` 文件夹；授权会被保存，之后无需重复选择。
- 文件路径为 `EXPORT/YYYY-MM-DD/ReadAssist_名称_时间.md`，内容按文档和日期组织，可通过 Supernote 手机端或电脑端同步查看。

### 离线字典

本项目同时提供两个 APK：

- 完整版：内置 ECDICT，在设置页点“一键安装内置 ECDICT”即可离线解包并建立索引，无需另行下载或复制词典。
- 轻量版：不带词库，按下面的方法手动导入任意兼容 StarDict。

完整版 APK 约 94 MB，词典解包后约 191 MB。首次安装前建议至少预留 350 MB 可用空间，建立索引期间请勿退出设置页。重复点击不会重复导入。

1. 使用轻量版时，在设置页选择“导入 StarDict 文件夹”。文件夹内必须只有一个 `.ifo`，并包含同名索引和释义文件。
   导入时只复制词典并建立小型稀疏索引；完成提示出现后再使用 `翻`。当前不支持带 `.syn` 同义词文件的词典。
2. 单击 `翻`，在窗口顶部选择“选择文本”或“圈选 OCR”；也可直接输入词语查词。
3. 双击 `翻` 会沿用上次选择的取词方式：
   - “圈选区域并离线识别”：用笔圈住一个词或短语，识别后自动查词。
   - “等待选择或复制文字”：随后在阅读器中选择并复制文字，下一段选中文本会自动查词。

当前版本先支持 StarDict；MDict（`.mdx` / `.mdd`）尚未接入。

可从以下项目下载 StarDict 词库：

- [FreeDict 官方下载页](https://freedict.org/downloads/)：提供多种语言组合，可直接选择 StarDict 格式；适合先用较小词库验证导入。
- [ECDICT Releases](https://github.com/skywind3000/ECDICT/releases)：英汉词条覆盖较大，下载名为 `ecdict-stardict-*.zip` 的文件；词库很大，首次导入会明显更久。

轻量版中的压缩包需要先解压，再把同时包含 `.ifo`、`.idx` / `.idx.gz`、`.dict` / `.dict.dz` 的文件夹复制到设备并导入。不要直接选择 `.zip` 或 `.tar.xz` 文件。

首次截图时，Android 会要求授予屏幕捕获权限。API Key 只保存在设备本地，不在源码中提供。

### 模型注意事项

- 截图分析必须选择标记为支持视觉的模型。
- 纯文本模型只能进行文字问答，不能分析区域截图。
- DeepSeek、Gemini 或 SiliconFlow 的模型名称和可用性由相应服务商决定，若接口返回模型不可用，请在应用中选择该平台当前可用的模型。

## 已验证环境

- Supernote Nomad / A6 X2
- Android 8.1
- 屏幕分辨率 1404 × 1872

项目最低支持 Android 6.0（API 23）。AI 圈选、`AI` / `翻` 双击、取消流程、StarDict 原生查询及内置 ECDICT 一键部署均已在上述设备验证。

## 从源码构建

需要：

- JDK 17
- Android SDK 35
- 可用的 Android SDK 路径（通过 Android Studio 或本机 `local.properties` 配置）

构建完整版并运行单元测试：

```bash
./gradlew testDeepseekBundledUnitTest assembleDeepseekBundled
```

生成的 APK 位于：

```text
app/build/outputs/apk/deepseek/ReadAssist-v1.14.7-supernote-clipboard-bridge-deepseek.apk
app/build/outputs/apk/deepseekBundled/ReadAssist-v1.14.7-supernote-clipboard-bridge-deepseekBundled.apk
```

通过 ADB 安装：

```bash
adb install -r app/build/outputs/apk/deepseekBundled/ReadAssist-v1.14.7-supernote-clipboard-bridge-deepseekBundled.apk
```

`deepseek` 和 `deepseekBundled` 使用相同应用 ID `com.readassist.deepseek`，可互相覆盖升级，并可与原版 ReadAssist 并存。公开仓库不包含发布密钥或签名文件；如需正式发布，请自行配置签名。

内置词库源文件为 `ecdict-stardict-28.zip`，SHA-256：

```text
c707d0f3ded6ec79b96466da4a1574e074703da5af9c120fbad97f9cb08c6f2c
```

## 隐私与安全

- 截图只在用户主动圈选后生成。
- 字典文件、离线 OCR 结果和查词内容不会发送给 AI 服务商。
- 截图和问题会发送到用户所选 AI 服务商，请遵守相应平台的隐私政策。
- 聊天历史和 API Key 保存在设备本地；卸载应用前请自行备份需要保留的记录。
- 请勿提交 `local.properties`、API Key、签名文件、设备日志或包含私人内容的截图。

## 上游与许可证

- 上游项目：[James-Zhu-CA/ReadAssist](https://github.com/James-Zhu-CA/ReadAssist)
- 二次修改仓库：[lujun723-win/supernote-read-ai](https://github.com/lujun723-win/supernote-read-ai)
- 许可证：[MIT License](LICENSE)
- 完整版内置的 ECDICT 采用 MIT License，版权与许可声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

感谢原项目作者和贡献者提供基础实现。
