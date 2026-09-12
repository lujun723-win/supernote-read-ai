# Supernote Read AI

面向 Supernote 墨水屏设备的悬浮式 AI 阅读助手，可圈选 PDF、EPUB 或其他阅读页面中的区域，并把截图或文字发送给 AI 解读。

> **二次开发声明**
>
> 本项目基于 [James-Zhu-CA/ReadAssist](https://github.com/James-Zhu-CA/ReadAssist) 进行二次修改，原项目采用 MIT License。本仓库不是原项目的官方版本，也不代表原作者立场；原项目版权声明和 MIT 许可证保留在 [LICENSE](LICENSE) 中。

当前版本：`1.9.7-deepseek-local`

## 主要修改

- 增加 DeepSeek 官方 API，可在 Gemini、SiliconFlow 和 DeepSeek 之间切换。
- 增加适配 Supernote 的区域截图流程：双击悬浮图标后直接圈选，截图完成后进入 AI 对话。
- 圈选截图默认附加到下一条问题，并显示“发送区域截图”勾选项；取消勾选会丢弃本次图片。
- 单击悬浮图标打开对话，双击判定时间为 0.8 秒。
- 对话窗口只显示本次问题和 AI 回答，完整记录保存在“历史记录”中。
- 点击“新对话”后保存上一轮并清空当前窗口；关闭再打开不会恢复刚刚清空的旧对话。
- 区域截图期间隔离 Supernote 的悬停剪贴板事件，避免透明窗口、弹窗竞争和服务退出。
- 修正独立包名下的无障碍服务识别与授权状态判断。
- 移除旧版自动监听并发送整屏截图的功能；图片只来自用户主动发起的区域圈选。
- 调整按钮、输入框和弹窗布局，使其更适合黑白墨水屏。

## 使用方法

1. 安装 APK，并允许“安装未知应用”。
2. 打开 ReadAssist，授予悬浮窗权限和无障碍服务权限。
3. 选择 AI 平台、模型并填写自己的 API Key。
4. 启动悬浮按钮服务。
5. 单击悬浮图标打开对话；在 0.8 秒内双击图标进入区域截图。
6. 圈出要分析的内容，确认“发送区域截图”已勾选，输入问题后发送。

首次截图时，Android 会要求授予屏幕捕获权限。API Key 只保存在设备本地，不在源码中提供。

### 模型注意事项

- 截图分析必须选择标记为支持视觉的模型。
- 纯文本模型只能进行文字问答，不能分析区域截图。
- DeepSeek、Gemini 或 SiliconFlow 的模型名称和可用性由相应服务商决定，若接口返回模型不可用，请在应用中选择该平台当前可用的模型。

## 已验证环境

- Supernote Nomad / A6 X2
- Android 8.1
- 屏幕分辨率 1404 × 1872

项目最低支持 Android 5.0（API 21），其他设备和系统版本尚未逐一验证。

## 从源码构建

需要：

- JDK 17
- Android SDK 35
- 可用的 Android SDK 路径（通过 Android Studio 或本机 `local.properties` 配置）

构建并运行单元测试：

```bash
./gradlew testDeepseekUnitTest assembleDeepseek
```

生成的 APK 位于：

```text
app/build/outputs/apk/deepseek/ReadAssist-v1.9.7-deepseek-deepseek.apk
```

通过 ADB 安装：

```bash
adb install -r app/build/outputs/apk/deepseek/ReadAssist-v1.9.7-deepseek-deepseek.apk
```

`deepseek` 变体使用独立应用 ID `com.readassist.deepseek`，可与原版 ReadAssist 并存。公开仓库不包含发布密钥或签名文件；如需正式发布，请自行配置签名。

## 隐私与安全

- 截图只在用户主动圈选后生成。
- 截图和问题会发送到用户所选 AI 服务商，请遵守相应平台的隐私政策。
- 聊天历史和 API Key 保存在设备本地；卸载应用前请自行备份需要保留的记录。
- 请勿提交 `local.properties`、API Key、签名文件、设备日志或包含私人内容的截图。

## 上游与许可证

- 上游项目：[James-Zhu-CA/ReadAssist](https://github.com/James-Zhu-CA/ReadAssist)
- 二次修改仓库：[lujun723-win/supernote-read-ai](https://github.com/lujun723-win/supernote-read-ai)
- 许可证：[MIT License](LICENSE)

感谢原项目作者和贡献者提供基础实现。
