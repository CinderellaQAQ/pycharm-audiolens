package audiolens.pycharm.settings

import audiolens.pycharm.audio.FfmpegResolver
import audiolens.pycharm.diagnostics.AudioLensDiagnostics
import audiolens.pycharm.diagnostics.AudioLensDiagnosticsService
import audiolens.pycharm.remote.RemoteAudioCacheService
import audiolens.pycharm.remote.RemoteAudioSupport
import audiolens.pycharm.web.AudioLensSessionRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.ContextHelpLabel
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class AudioLensConfigurable : Configurable {
    private val autoAnalyze = JBCheckBox("打开音频后自动分析频谱图")
    private val profileSpectrogram = JBCheckBox("记录频谱图性能诊断")
    private val language = JComboBox(LANGUAGES)
    private val windowFunction = JComboBox(WINDOW_FUNCTIONS)
    private val fftSize = JComboBox(FFT_SIZES.toTypedArray())
    private val zeroPadding = JComboBox(ZERO_PADDING.toTypedArray())
    private val amplitudeScale = JComboBox(AMPLITUDE_SCALES)
    private val maxFileSize = JSpinner(SpinnerNumberModel(512, 16, 4096, 16))
    private val ffmpegPath = JBTextField()
    private val remoteSftpEnabled = JBCheckBox("启用 SFTP 音频路径")
    private val remoteServer = JComboBox<RemoteServerItem>()
    private val remotePathPrefix = JBTextField()
    private val remoteBasePath = JBTextField()
    private val remoteCacheMax = JSpinner(SpinnerNumberModel(2048, 128, 65536, 128))
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "AudioLens"

    override fun createComponent(): JComponent {
        val detectButton = JButton("检测 / 测试 FFmpeg")
        val ffmpegPanel = JPanel(BorderLayout(8, 0)).apply {
            add(ffmpegPath, BorderLayout.CENTER)
            add(detectButton, BorderLayout.EAST)
        }
        detectButton.addActionListener {
            detectButton.isEnabled = false
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = FfmpegResolver.test(ffmpegPath.text.trim().ifEmpty { null })
                ApplicationManager.getApplication().getService(AudioLensDiagnosticsService::class.java).recordTest(result)
                ApplicationManager.getApplication().invokeLater {
                    detectButton.isEnabled = true
                    if (result.path != null) {
                        ffmpegPath.text = result.path
                        Messages.showInfoMessage(result.message, "AudioLens FFmpeg")
                    } else {
                        Messages.showWarningDialog(result.message, "AudioLens FFmpeg")
                    }
                }
            }
        }
        val diagnosticsButton = JButton("复制 AudioLens 诊断信息").apply {
            addActionListener {
                AudioLensDiagnostics.copy(ProjectManager.getInstance().openProjects.firstOrNull())
            }
        }
        val testSftpButton = JButton("测试 SFTP")
        val clearRemoteCacheButton = JButton("清理下载缓存")
        val remoteCachePath = JBTextField(RemoteAudioCacheService.cacheRootPath().toString()).apply {
            isEditable = false
            toolTipText = text
        }
        val sftpButtons = JPanel(BorderLayout(8, 0)).apply {
            add(testSftpButton, BorderLayout.WEST)
            add(clearRemoteCacheButton, BorderLayout.EAST)
        }
        remoteSftpEnabled.addActionListener { updateRemoteControls(testSftpButton) }
        remoteServer.addActionListener { updateRemoteControls(testSftpButton) }
        testSftpButton.addActionListener {
            val project = currentProject()
            val server = remoteServer.selectedItem as? RemoteServerItem
            if (project == null || server == null || server.id.isBlank()) {
                Messages.showWarningDialog("请先打开项目并选择一个 SFTP 配置。", "AudioLens SFTP")
                return@addActionListener
            }
            testSftpButton.isEnabled = false
            RemoteAudioSupport.testConnection(project, server.id) { result ->
                updateRemoteControls(testSftpButton)
                if (result.successful) Messages.showInfoMessage(result.message, "AudioLens SFTP")
                else Messages.showWarningDialog(result.message, "AudioLens SFTP")
            }
        }
        clearRemoteCacheButton.addActionListener {
            val project = currentProject()
            if (project == null) {
                Messages.showWarningDialog("请先打开项目，再清理该项目的 AudioLens 缓存。", "AudioLens SFTP")
                return@addActionListener
            }
            object : Task.Backgroundable(project, "正在清理 AudioLens 远端缓存", false) {
                private var message = "AudioLens 远端缓存已清理。"

                override fun run(indicator: ProgressIndicator) {
                    val removed = project.service<RemoteAudioCacheService>().clear()
                    message = "已删除 ${removed.fileCount} 个缓存音频，共 ${formatBytes(removed.bytes)}。"
                }

                override fun onSuccess() {
                    Messages.showInfoMessage(project, message, "AudioLens SFTP")
                }
            }.queue()
        }

        val windowFunctionLabel = labelWithHelp("窗口类型：", windowFunctionHelp(selectedValue(windowFunction)))
        windowFunction.addActionListener {
            replaceHelp(windowFunctionLabel, windowFunctionHelp(selectedValue(windowFunction)))
        }
        val amplitudeScaleLabel = labelWithHelp("幅值刻度：", amplitudeScaleHelp(selectedValue(amplitudeScale)))
        amplitudeScale.addActionListener {
            replaceHelp(amplitudeScaleLabel, amplitudeScaleHelp(selectedValue(amplitudeScale)))
        }

        panel = FormBuilder.createFormBuilder()
            .addComponent(componentWithHelp(autoAnalyze, "控制打开音频后是否立即计算并显示频谱图；关闭后可在音频界面中手动刷新。"))
            .addLabeledComponent(labelWithHelp("界面语言：", "控制 AudioLens 音频界面的显示语言；“跟随 IDE”会使用 PyCharm 当前语言。"), language)
            .addLabeledComponent(windowFunctionLabel, windowFunction)
            .addLabeledComponent(labelWithHelp("FFT 窗口大小：", "每帧参与频谱分析的采样点数。数值越大，频率分辨率越高，但时间分辨率和计算速度会下降。"), fftSize)
            .addLabeledComponent(labelWithHelp("零填充因子：", "在 FFT 前向每帧尾部补零，用于获得更密的频率采样点；它不会增加真实频率分辨率。"), zeroPadding)
            .addLabeledComponent(amplitudeScaleLabel, amplitudeScale)
            .addLabeledComponent(labelWithHelp("最大音频大小（MB）：", "限制 AudioLens 可读取或从 SFTP 下载的单个音频文件大小，避免意外占用过多内存和磁盘。"), maxFileSize)
            .addLabeledComponent(labelWithHelp("FFmpeg 可执行文件（可选）：", "用于解码浏览器不能直接处理的音频，以及处理大型流式音频。留空时会搜索 PATH 和常见安装位置。"), ffmpegPanel)
            .addComponent(JBLabel("FFmpeg 路径留空时，将自动搜索 PATH 和常见安装位置。"))
            .addComponent(diagnosticsButton)
            .addComponent(componentWithHelp(profileSpectrogram, "把频谱计算耗时等性能数据写入 IDE 日志，仅用于排查性能问题。"))
            .addSeparator()
            .addComponent(JBLabel("远端音频（PyCharm Deployment / SFTP）"))
            .addComponent(componentWithHelp(remoteSftpEnabled, "允许代码、日志和 wav.scp 中的远端音频路径通过选定的 Deployment/SFTP 配置下载并打开。"))
            .addLabeledComponent(labelWithHelp("SFTP 配置：", "复用 PyCharm 已保存的 Deployment/SFTP 服务器和凭据；AudioLens 不会另行保存密码或私钥。"), remoteServer)
            .addLabeledComponent(labelWithHelp("代码中的路径前缀（可选）：", "仅匹配以此前缀开头的代码路径；匹配后会移除该前缀，再拼接远端基目录。"), remotePathPrefix)
            .addLabeledComponent(labelWithHelp("远端基目录（可选）：", "服务器上的音频根目录，与移除前缀后的相对路径组合成最终 SFTP 路径。"), remoteBasePath)
            .addLabeledComponent(labelWithHelp("下载缓存上限（MB）：", "远端普通音频的本地缓存容量上限；超过后优先清理最久未使用的文件。"), remoteCacheMax)
            .addLabeledComponent(labelWithHelp("默认下载缓存目录：", "远端普通音频下载后保存在此本地目录；该路径位于 PyCharm 的 system 目录下，可选中并复制。"), remoteCachePath)
            .addComponent(JBLabel("示例：前缀 /data + 基目录 /mnt/datasets，会把 /data/a.wav 映射为 /mnt/datasets/a.wav。"))
            .addComponent(JBLabel("不支持远端 .ark 和 .ark:offset；请先下载到本地。"))
            .addComponent(sftpButtons)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        updateRemoteControls(testSftpButton)
        return panel!!
    }

    override fun isModified(): Boolean {
        val state = AudioLensSettings.getInstance().state
        return autoAnalyze.isSelected != state.autoAnalyze ||
            selectedValue(language) != state.language ||
            selectedValue(windowFunction) != state.windowFunction ||
            fftSize.selectedItem != state.fftSize ||
            zeroPadding.selectedItem != state.zeroPaddingFactor ||
            selectedValue(amplitudeScale) != state.amplitudeScaleMode ||
            (maxFileSize.value as Int) != state.maxFileSizeMB ||
            profileSpectrogram.isSelected != state.profileSpectrogram ||
            ffmpegPath.text.trim() != state.ffmpegPath ||
            remoteSftpEnabled.isSelected != state.remoteSftpEnabled ||
            selectedRemoteServerId() != state.remoteSftpServerId ||
            remotePathPrefix.text.trim() != state.remotePathPrefix ||
            remoteBasePath.text.trim() != state.remoteBasePath ||
            (remoteCacheMax.value as Int) != state.remoteCacheMaxMB
    }

    override fun apply() {
        val state = AudioLensSettings.getInstance().state
        state.autoAnalyze = autoAnalyze.isSelected
        state.language = selectedValue(language)
        state.windowFunction = selectedValue(windowFunction)
        state.fftSize = fftSize.selectedItem as Int
        state.zeroPaddingFactor = zeroPadding.selectedItem as Int
        state.amplitudeScaleMode = selectedValue(amplitudeScale)
        state.maxFileSizeMB = (maxFileSize.value as Int).coerceIn(16, 4096)
        state.profileSpectrogram = profileSpectrogram.isSelected
        state.ffmpegPath = ffmpegPath.text.trim()
        state.remoteSftpEnabled = remoteSftpEnabled.isSelected
        state.remoteSftpServerId = selectedRemoteServerId()
        state.remotePathPrefix = remotePathPrefix.text.trim()
        state.remoteBasePath = remoteBasePath.text.trim()
        state.remoteCacheMaxMB = (remoteCacheMax.value as Int).coerceIn(128, 65536)
        ApplicationManager.getApplication().getService(AudioLensSessionRegistry::class.java).settingsChanged()
    }

    override fun reset() {
        val state = AudioLensSettings.getInstance().state
        autoAnalyze.isSelected = state.autoAnalyze
        selectValue(language, state.language)
        selectValue(windowFunction, state.windowFunction)
        fftSize.selectedItem = state.fftSize
        zeroPadding.selectedItem = state.zeroPaddingFactor
        selectValue(amplitudeScale, state.amplitudeScaleMode)
        maxFileSize.value = state.maxFileSizeMB
        profileSpectrogram.isSelected = state.profileSpectrogram
        ffmpegPath.text = state.ffmpegPath
        remoteSftpEnabled.isSelected = state.remoteSftpEnabled
        reloadRemoteServers(state.remoteSftpServerId)
        remotePathPrefix.text = state.remotePathPrefix
        remoteBasePath.text = state.remoteBasePath
        remoteCacheMax.value = state.remoteCacheMaxMB.coerceIn(128, 65536)
    }

    override fun disposeUIResources() {
        panel = null
    }

    companion object {
        private val LANGUAGES = arrayOf(
            Choice("auto", "跟随 IDE"), Choice("zh-CN", "简体中文"), Choice("zh-TW", "繁体中文"),
            Choice("en", "英语"), Choice("ja", "日语"), Choice("ko", "韩语"), Choice("fr", "法语"),
            Choice("de", "德语"), Choice("ru", "俄语"), Choice("es", "西班牙语"), Choice("it", "意大利语"),
            Choice("pt", "葡萄牙语"), Choice("id", "印度尼西亚语"), Choice("no", "挪威语"),
            Choice("nl", "荷兰语"), Choice("pl", "波兰语"), Choice("tr", "土耳其语"), Choice("vi", "越南语"),
        )
        private val WINDOW_FUNCTIONS = arrayOf(
            Choice("rectangular", "Rectangular（矩形窗）"), Choice("bartlett", "Bartlett（三角窗）"),
            Choice("hamming", "Hamming（汉明窗）"), Choice("hann", "Hann（汉宁窗）"),
            Choice("blackman", "Blackman（布莱克曼窗）"), Choice("blackmanHarris", "Blackman-Harris 窗"),
            Choice("welch", "Welch 窗"), Choice("gaussian25", "Gaussian（α=2.5）"),
            Choice("gaussian35", "Gaussian（α=3.5）"), Choice("gaussian45", "Gaussian（α=4.5）"),
        )
        private val AMPLITUDE_SCALES = arrayOf(
            Choice("adaptive", "自适应"), Choice("sample", "采样值"), Choice("decibel", "分贝（dBFS）"),
        )
        private val FFT_SIZES = listOf(8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768)
        private val ZERO_PADDING = listOf(1, 2, 4, 8, 16, 32, 64, 128)

        private fun currentProject() = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes 字节"
        }

        private fun windowFunctionHelp(value: String): String = when (value) {
            "rectangular" -> "矩形窗不衰减帧内采样，主瓣最窄，但频谱泄漏和旁瓣较明显，适合信号恰好周期对齐的情况。"
            "bartlett" -> "Bartlett 三角窗从中心向两端线性衰减，可降低边界突变，旁瓣低于矩形窗。"
            "hamming" -> "汉明窗对帧两端进行平滑衰减，能有效抑制频谱泄漏，是语音频谱分析中常用的均衡选择。"
            "hann" -> "Hann（汉宁）窗在两端衰减到零，具有良好的通用频谱泄漏抑制能力。"
            "blackman" -> "Blackman 窗进一步压低旁瓣，适合观察较弱频率成分，但主瓣更宽。"
            "blackmanHarris" -> "Blackman-Harris 窗具有很强的旁瓣抑制，动态范围较好，但会牺牲更多频率分辨率。"
            "welch" -> "Welch 窗采用抛物线形衰减，在泄漏抑制与主瓣宽度之间折中。"
            "gaussian25" -> "高斯窗（α=2.5）平滑衰减较温和，在时间与频率集中度之间保持折中。"
            "gaussian35" -> "高斯窗（α=3.5）比 α=2.5 衰减更强，可进一步降低边界影响。"
            "gaussian45" -> "高斯窗（α=4.5）衰减最强，旁瓣更低，但有效时间范围和频率分辨率也会下降。"
            else -> "窗口函数用于减弱每个分析帧边界的突变，从而控制频谱泄漏与频率分辨率之间的取舍。"
        }

        private fun amplitudeScaleHelp(value: String): String = when (value) {
            "sample" -> "显示音源 PCM 的原始采样值，例如 16 位有符号音频为 -32768 到 32767；浮点音频或压缩格式解码输出仍为 -1 到 1。"
            "decibel" -> "使用对称 dBFS 对数刻度显示波形，0 dBFS 表示数字满幅，中心附近低于 -60 dBFS 的信号会压到显示下限。"
            else -> "按每个声道的峰值自动调整线性采样值范围，让较小幅值的波形也能充分利用显示高度。"
        }
    }

    private fun reloadRemoteServers(selectedId: String) {
        remoteServer.removeAllItems()
        remoteServer.addItem(RemoteServerItem("", "请选择 PyCharm SFTP 配置…"))
        val servers = RemoteAudioSupport.listServers(currentProject())
        servers.forEach { remoteServer.addItem(RemoteServerItem(it.id, it.displayName)) }
        if (selectedId.isNotBlank() && servers.none { it.id == selectedId }) {
            remoteServer.addItem(RemoteServerItem(selectedId, "之前选择的配置（当前不可用）"))
        }
        (0 until remoteServer.itemCount)
            .firstOrNull { remoteServer.getItemAt(it).id == selectedId }
            ?.let { remoteServer.selectedIndex = it }
    }

    private fun selectedRemoteServerId(): String = (remoteServer.selectedItem as? RemoteServerItem)?.id.orEmpty()

    private fun updateRemoteControls(testButton: JButton) {
        val enabled = remoteSftpEnabled.isSelected
        remoteServer.isEnabled = enabled
        remotePathPrefix.isEnabled = enabled
        remoteBasePath.isEnabled = enabled
        remoteCacheMax.isEnabled = enabled
        testButton.isEnabled = enabled && selectedRemoteServerId().isNotBlank() && currentProject() != null
    }

    private data class RemoteServerItem(val id: String, val label: String) {
        override fun toString(): String = label
    }

    private data class Choice(val value: String, val label: String) {
        override fun toString(): String = label
    }

    private fun selectedValue(comboBox: JComboBox<Choice>): String =
        (comboBox.selectedItem as? Choice)?.value.orEmpty()

    private fun selectValue(comboBox: JComboBox<Choice>, value: String) {
        (0 until comboBox.itemCount)
            .firstOrNull { comboBox.getItemAt(it).value == value }
            ?.let { comboBox.selectedIndex = it }
            ?: run { comboBox.selectedIndex = 0 }
    }

    private fun labelWithHelp(text: String, help: String): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        isOpaque = false
        add(JBLabel(text))
        add(ContextHelpLabel.create(help))
    }

    private fun componentWithHelp(component: JComponent, help: String): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        isOpaque = false
        add(component)
        add(ContextHelpLabel.create(help))
    }

    private fun replaceHelp(container: JPanel, help: String) {
        if (container.componentCount > 1) container.remove(1)
        container.add(ContextHelpLabel.create(help), 1)
        container.revalidate()
        container.repaint()
    }
}
