# AudioLens for PyCharm

这是vscode-audiolens的 PyCharm 移植版。原作者仓库：[AudioLens for VS Code](https://github.com/SimZhou/vscode-audiolens)，欢迎大家给原作者star。

## 下载

[下载 v1.0.1（pycharm-audiolens-1.0.1.zip）](https://github.com/CinderellaQAQ/pycharm-audiolens/releases/download/v1.0.1/pycharm-audiolens-1.0.1.zip)

在 PyCharm 的“设置/偏好设置 → 插件”中选择“从磁盘安装插件”，然后选择下载的 ZIP 文件即可。支持 PyCharm 内部版本 251–261。

## 相对于原插件增加或调整的功能

- 适配 PyCharm 的音频编辑器，可直接打开常见音频、原始 PCM 和本地 Kaldi WAV Ark 文件。
- 支持“本地 PyCharm + 远程 Python 解释器”环境：复用已有的 SFTP Deployment 配置读取远程音频，并自动缓存到本地；远程 Ark 文件暂不支持。
- 增加自适应、原始 PCM 采样值和 dBFS 三种幅值刻度；整数 PCM 按源文件位深显示真实采样范围。
- 提供中文配置页面，并为音频、频谱图、PCM、FFmpeg 等参数增加问号说明。
- 选区分析改为右键菜单触发，分析窗口可单独关闭。
- 改进 FFmpeg 和远程文件错误提示，并支持一键复制隐私安全的诊断信息。
