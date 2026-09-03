# AudioLens for PyCharm

这是 [SimZhou/vscode-audiolens](https://github.com/SimZhou/vscode-audiolens) 的 PyCharm 移植版，基于上游 1.8.11。

## 安装

1. 构建插件，或取得生成的 `pycharm-audiolens-*.zip`。
2. 在 PyCharm 打开 **Settings | Plugins**。
3. 点击齿轮菜单，选择 **Install Plugin from Disk…**，选中 ZIP 并重启 PyCharm。
4. 打开受支持的音频文件，AudioLens 会作为该文件的默认编辑器。

当前版本支持 PyCharm 2025.1 至 2026.1（内部构建号 `251`–`261.*`），Windows、macOS 和 Linux 共用一个 ZIP。

## 已支持

- WAV、MP3、FLAC、OGG、Opus、M4A、AAC
- 可指定格式参数的 `.pcm` / `.raw`
- 多通道波形图、语谱图、播放、缩放、声道增益/平衡/静音/独奏
- 波形幅值刻度可选自适应、原始 PCM 采样值或对称 dBFS；音频设置参数带有中文问号说明
- 文件头信息与选区指标分析
- 通过 **Tools | Open Kaldi WAV from Ark…** 打开 Kaldi WAV ark 条目；也可直接打开 `.ark` 后输入 RIFF/WAVE 的字节 offset
- 在 Python、JSON、TXT、日志和 `wav.scp` 等文本中按住 Ctrl（macOS 为 Command）点击音频路径直接打开
- 文本路径支持绝对路径、相对于当前文本文件的路径、相对于项目根目录的路径，以及 `audio.ark:offset`
- 本地 PyCharm 可通过已有的 Deployment/SFTP 配置下载并打开远端普通音频文件
- 将选区导出为 WAV

JetBrains Remote Development/Gateway 暂不支持，也不是 SFTP 功能的前提。SFTP 模式不支持远端 `.ark` 或 `.ark:offset`；请先将 Ark 文件下载到本地。

## 从文本打开音频

在普通代码或文本编辑器中按住 **Ctrl**（macOS 为 **Command**）并移动到已有的音频路径上，路径会显示为链接；点击即可用 AudioLens 打开。解析相对路径时，插件先检查当前文本文件所在目录，再检查项目根目录。

支持普通音频路径以及 Kaldi WAV ark 写法，例如：

```text
audio = "../samples/example.wav"
utterance-001 data/train/audio.flac
utterance-002 data/train/wav.ark:23252
```

## 通过 SFTP 打开远端音频

这个功能适用于“本地 PyCharm + 远程 Python 解释器”，不需要 Remote Development 或远端 IDE Backend。插件复用 PyCharm 已保存的 SFTP Deployment 配置，不自行保存服务器密码或私钥。

1. 在 **Settings | Build, Execution, Deployment | Deployment** 确认已有可用的 SFTP 配置。通过 SSH 创建远程 Python 解释器时，PyCharm 通常已自动创建相应配置。
2. 打开 **Settings | Tools | AudioLens**，勾选 **启用 SFTP 音频路径** 并选择 SFTP 配置。
3. 点击 **测试 SFTP**。如文本里的路径与服务器真实路径不同，可设置路径映射：例如代码中的前缀 `/data` 配合远端基目录 `/mnt/datasets`，会把 `/data/a.wav` 映射为 `/mnt/datasets/a.wav`。
4. 在代码、日志或 `wav.scp` 中 Ctrl/Command 单击路径；也可以使用 **Tools | Open Remote Audio via SFTP…** 手动输入路径，或直接在 PyCharm 的 **Remote Host** 文件视图中打开普通音频。

通过 AudioLens 路径功能打开时，插件会在后台流式下载整个音频，显示进度并允许取消；下载先进入临时文件，完整后才加入本地缓存。再次打开时会比较远端文件大小和修改时间，未变化就直接复用缓存。从 **Remote Host** 打开时，Web Deployment 会先取得文件内容，AudioLens 再把该远端虚拟文件复制到同一个有容量上限的本地缓存中显示。缓存上限可在设置中调整，也可以通过设置页或 **Tools | Clear AudioLens Remote Cache** 清理。

当前明确不支持远端 `.ark` 和 `.ark:offset`。Ark 的随机偏移读取需要另一套远程范围读取机制，本实验版本不做适配；本地 Ark 功能不受影响。

## FFmpeg

插件不会捆绑或下载 FFmpeg。浏览器能解码的格式会直接打开；M4A/AAC，以及浏览器解码失败后的回退，需要系统已安装 FFmpeg。

在 **Settings | Tools | AudioLens** 中可指定 FFmpeg 程序，或点击 **检测 / 测试 FFmpeg**。留空时会搜索 `PATH` 和常见安装位置。该配置页已完整中文化，参数右侧的问号会说明用途和取舍。

FFmpeg 和 SFTP 失败时，插件会给出可操作的提示。错误通知中的 **Copy Diagnostics**、设置页中的 **Copy AudioLens Diagnostics**，或 **Tools | Copy AudioLens Diagnostics** 都可以一键复制诊断报告。报告包含插件、IDE、系统、JCEF、FFmpeg、SFTP 功能状态和最近一次失败信息，但不包含音频内容、服务器地址、账号、密码、私钥、音频完整路径或项目路径。

## 隐私与项目信任

所有音频处理都在本机完成；插件没有遥测，也不会把音频上传到第三方服务。启用 SFTP 后，选中的远端音频会从你的服务器下载到 PyCharm 本机缓存。在未信任项目中，只显示文件信息，不会把音频内容传入内嵌浏览器。信任项目后，请关闭并重新打开音频标签页。

每个编辑器使用随机令牌；源文件读取有范围限制，计算结果通过短时有效的一次性链接传输。

## 构建

需要 Java 21 和 Node.js 20 或更高版本：

```bash
./gradlew clean test buildPlugin verifyPluginStructure
```

可安装 ZIP 位于 `build/distributions/`。

## 许可与署名

本项目使用 Apache License 2.0。TypeScript 可视化和信号分析代码来自上游 AudioLens，PyCharm 宿主集成使用 Kotlin 实现。详见 `LICENSE` 和 `NOTICE`。
