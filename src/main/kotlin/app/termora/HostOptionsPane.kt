package app.termora

import app.termora.keymgr.KeyManager
import app.termora.keymgr.KeyManagerDialog
import com.fazecast.jSerialComm.SerialPort
import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.components.FlatComboBox
import com.formdev.flatlaf.ui.FlatTextBorder
import com.formdev.flatlaf.util.SystemInfo
import com.jgoodies.forms.builder.FormBuilder
import com.jgoodies.forms.layout.FormLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.RegExUtils
import org.apache.commons.lang3.StringUtils
import org.eclipse.jgit.internal.transport.sshd.agent.connector.PageantConnector
import org.eclipse.jgit.internal.transport.sshd.agent.connector.UnixDomainSocketConnector
import org.eclipse.jgit.internal.transport.sshd.agent.connector.WinPipeConnector
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.nio.charset.Charset
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

@Suppress("CascadeIf")
open class HostOptionsPane : OptionsPane() {
    protected val tunnelingOption = TunnelingOption()
    protected val generalOption = GeneralOption()
    protected val proxyOption = ProxyOption()
    protected val terminalOption = TerminalOption()
    protected val jumpHostsOption = JumpHostsOption()
    protected val serialCommOption = SerialCommOption()
    protected val sftpOption = SFTPOption()
    protected val owner: Window get() = SwingUtilities.getWindowAncestor(this)

    init {
        addOption(generalOption)
        addOption(proxyOption)
        addOption(tunnelingOption)
        addOption(jumpHostsOption)
        addOption(terminalOption)
        addOption(serialCommOption)
        addOption(sftpOption)

        setContentBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8))
    }


    open fun getHost(): Host {
        val name = generalOption.nameTextField.text
        val protocol = generalOption.protocolTypeComboBox.selectedItem as Protocol
        val host = generalOption.hostTextField.text
        val port = (generalOption.portTextField.value ?: 22) as Int
        var authentication = Authentication.No
        var proxy = Proxy.No
        val authenticationType = generalOption.authenticationTypeComboBox.selectedItem as AuthenticationType

        if (authenticationType == AuthenticationType.Password) {
            authentication = authentication.copy(
                type = authenticationType,
                password = String(generalOption.passwordTextField.password)
            )
        } else if (authenticationType == AuthenticationType.PublicKey) {
            authentication = authentication.copy(
                type = authenticationType,
                password = generalOption.publicKeyComboBox.selectedItem?.toString() ?: StringUtils.EMPTY
            )
        } else if (authenticationType == AuthenticationType.SSHAgent) {
            authentication = authentication.copy(
                type = authenticationType,
                password = generalOption.sshAgentComboBox.selectedItem?.toString() ?: StringUtils.EMPTY
            )
        }

        if (proxyOption.proxyTypeComboBox.selectedItem != ProxyType.No) {
            proxy = proxy.copy(
                type = proxyOption.proxyTypeComboBox.selectedItem as ProxyType,
                host = proxyOption.proxyHostTextField.text,
                username = proxyOption.proxyUsernameTextField.text,
                password = String(proxyOption.proxyPasswordTextField.password),
                port = proxyOption.proxyPortTextField.value as Int,
                authenticationType = proxyOption.proxyAuthenticationTypeComboBox.selectedItem as AuthenticationType,
            )
        }


        val serialComm = SerialComm(
            port = serialCommOption.serialPortComboBox.selectedItem?.toString() ?: StringUtils.EMPTY,
            baudRate = serialCommOption.baudRateComboBox.selectedItem?.toString()?.toIntOrNull() ?: 9600,
            dataBits = serialCommOption.dataBitsComboBox.selectedItem as Int? ?: 8,
            stopBits = serialCommOption.stopBitsComboBox.selectedItem as String? ?: "1",
            parity = serialCommOption.parityComboBox.selectedItem as SerialCommParity,
            flowControl = serialCommOption.flowControlComboBox.selectedItem as SerialCommFlowControl
        )

        val options = Options.Default.copy(
            encoding = terminalOption.charsetComboBox.selectedItem as String,
            env = terminalOption.environmentTextArea.text,
            startupCommand = terminalOption.startupCommandTextField.text,
            heartbeatInterval = (terminalOption.heartbeatIntervalTextField.value ?: 30) as Int,
            jumpHosts = jumpHostsOption.jumpHosts.map { it.id },
            serialComm = serialComm,
            sftpDefaultDirectory = sftpOption.defaultDirectoryField.text,
            enableX11Forwarding = tunnelingOption.x11ForwardingCheckBox.isSelected,
            x11Forwarding = tunnelingOption.x11ServerTextField.text,
        )

        return Host(
            name = name,
            protocol = protocol,
            host = host,
            port = port,
            username = generalOption.usernameTextField.text,
            authentication = authentication,
            proxy = proxy,
            sort = System.currentTimeMillis(),
            remark = generalOption.remarkTextArea.text,
            options = options,
            tunnelings = tunnelingOption.tunnelings
        )
    }

    fun validateFields(): Boolean {
        val host = getHost()

        // general
        if (validateField(generalOption.nameTextField)
            || validateField(generalOption.hostTextField)
        ) {
            return false
        }

        if (host.protocol == Protocol.SSH) {
            if (validateField(generalOption.usernameTextField)) {
                return false
            }
        } else if (host.protocol == Protocol.Serial) {
            if (validateField(serialCommOption.serialPortComboBox)
                || validateField(serialCommOption.baudRateComboBox)
            ) {
                return false
            }
        }

        if (host.authentication.type == AuthenticationType.Password) {
            if (validateField(generalOption.passwordTextField)) {
                return false
            }
        } else if (host.authentication.type == AuthenticationType.PublicKey) {
            if (validateField(generalOption.publicKeyComboBox)) {
                return false
            }
        }

        // proxy
        if (host.proxy.type != ProxyType.No) {
            if (validateField(proxyOption.proxyHostTextField)
            ) {
                return false
            }

            if (host.proxy.authenticationType != AuthenticationType.No) {
                if (validateField(proxyOption.proxyUsernameTextField)
                    || validateField(proxyOption.proxyPasswordTextField)
                ) {
                    return false
                }
            }
        }

        // tunnel
        if (tunnelingOption.x11ForwardingCheckBox.isSelected) {
            if (validateField(tunnelingOption.x11ServerTextField)) {
                return false
            }
            val segments = tunnelingOption.x11ServerTextField.text.split(":")
            if (segments.size != 2 || segments[1].toIntOrNull() == null) {
                setOutlineError(tunnelingOption.x11ServerTextField)
                return false
            }
        }

        return true
    }

    /**
     * 返回 true 表示有错误
     */
    private fun validateField(textField: JTextField): Boolean {
        if (textField.isEnabled && textField.text.isBlank()) {
            setOutlineError(textField)
            return true
        }
        return false
    }

    private fun setOutlineError(textField: JTextField) {
        selectOptionJComponent(textField)
        textField.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR)
        textField.requestFocusInWindow()
    }

    /**
     * 返回 true 表示有错误
     */
    private fun validateField(comboBox: JComboBox<*>): Boolean {
        val selectedItem = comboBox.selectedItem
        if (comboBox.isEnabled && (selectedItem == null || (selectedItem is String && selectedItem.isBlank()))) {
            selectOptionJComponent(comboBox)
            comboBox.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR)
            comboBox.requestFocusInWindow()
            return true
        }
        return false
    }

    protected inner class GeneralOption : JPanel(BorderLayout()), Option {
        val portTextField = PortSpinner()
        val nameTextField = OutlineTextField(128)
        val protocolTypeComboBox = FlatComboBox<Protocol>()
        val usernameTextField = OutlineTextField(128)
        val hostTextField = object : OutlineTextField(255) {
            override fun paste() {
                if (!toolkit.systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    return
                }

                var text = toolkit.systemClipboard.getData(DataFlavor.stringFlavor)?.toString() ?: return
                if (text.isBlank()) {
                    return
                }

                // 移除所有不可见字符
                text = RegExUtils.replaceAll(text, "[\\p{C}\\s]", StringUtils.EMPTY)

                // text
                replaceSelection(text)
            }
        }
        private val passwordPanel = JPanel(BorderLayout())
        private val chooseKeyBtn = JButton(Icons.greyKey)
        val passwordTextField = OutlinePasswordField(255)
        val sshAgentComboBox = OutlineComboBox<String>()
        val publicKeyComboBox = OutlineComboBox<String>()
        val remarkTextArea = FixedLengthTextArea(512)
        val authenticationTypeComboBox = FlatComboBox<AuthenticationType>()

        init {
            initView()
            initEvents()
        }

        private fun initView() {
            add(getCenterComponent(), BorderLayout.CENTER)

            publicKeyComboBox.isEditable = false
            chooseKeyBtn.isFocusable = false

            // 只有 Windows 允许修改
            sshAgentComboBox.isEditable = SystemInfo.isWindows
            sshAgentComboBox.isEnabled = SystemInfo.isWindows

            protocolTypeComboBox.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    return super.getListCellRendererComponent(
                        list,
                        value.toString().uppercase(),
                        index,
                        isSelected,
                        cellHasFocus
                    )
                }
            }

            publicKeyComboBox.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    var text = StringUtils.EMPTY
                    if (value is String) {
                        text = KeyManager.getInstance().getOhKeyPair(value)?.name ?: text
                    }
                    return super.getListCellRendererComponent(
                        list,
                        text,
                        index,
                        isSelected,
                        cellHasFocus
                    )
                }
            }

            authenticationTypeComboBox.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    var text = value?.toString() ?: ""
                    when (value) {
                        AuthenticationType.Password -> {
                            text = "Password"
                        }

                        AuthenticationType.PublicKey -> {
                            text = "Public Key"
                        }

                        AuthenticationType.KeyboardInteractive -> {
                            text = "Keyboard Interactive"
                        }
                    }
                    return super.getListCellRendererComponent(
                        list,
                        text,
                        index,
                        isSelected,
                        cellHasFocus
                    )
                }
            }

            protocolTypeComboBox.addItem(Protocol.SSH)
            protocolTypeComboBox.addItem(Protocol.Local)
            protocolTypeComboBox.addItem(Protocol.Serial)
            protocolTypeComboBox.addItem(Protocol.RDP)

            authenticationTypeComboBox.addItem(AuthenticationType.No)
            authenticationTypeComboBox.addItem(AuthenticationType.Password)
            authenticationTypeComboBox.addItem(AuthenticationType.PublicKey)
            authenticationTypeComboBox.addItem(AuthenticationType.SSHAgent)

            if (SystemInfo.isWindows) {
                // 不要修改 addItem 的顺序，因为第一个是默认的
                sshAgentComboBox.addItem(PageantConnector.DESCRIPTOR.identityAgent)
                sshAgentComboBox.addItem(WinPipeConnector.DESCRIPTOR.identityAgent)
                sshAgentComboBox.placeholderText = PageantConnector.DESCRIPTOR.identityAgent
            } else {
                sshAgentComboBox.addItem(UnixDomainSocketConnector.DESCRIPTOR.identityAgent)
                sshAgentComboBox.placeholderText = UnixDomainSocketConnector.DESCRIPTOR.identityAgent
            }

            authenticationTypeComboBox.selectedItem = AuthenticationType.Password

            refreshStates()
        }

        private fun initEvents() {
            protocolTypeComboBox.addItemListener {
                if (it.stateChange == ItemEvent.SELECTED) {
                    refreshStates()
                }
            }

            authenticationTypeComboBox.addItemListener {
                if (it.stateChange == ItemEvent.SELECTED) {
                    refreshStates()
                    switchPasswordComponent()
                }
            }

            chooseKeyBtn.addActionListener {
                chooseKeyPair()
            }

            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    SwingUtilities.invokeLater { nameTextField.requestFocusInWindow() }
                    removeComponentListener(this)
                }
            })
        }

        private fun chooseKeyPair() {
            val dialog = KeyManagerDialog(
                SwingUtilities.getWindowAncestor(this),
                selectMode = true,
            )
            dialog.pack()
            dialog.setLocationRelativeTo(null)
            dialog.isVisible = true

            val selectedItem = publicKeyComboBox.selectedItem

            publicKeyComboBox.removeAllItems()
            for (keyPair in KeyManager.getInstance().getOhKeyPairs()) {
                publicKeyComboBox.addItem(keyPair.id)
            }
            publicKeyComboBox.selectedItem = selectedItem

            if (!dialog.ok) {
                return
            }

            publicKeyComboBox.selectedItem = dialog.getLasOhKeyPair()?.id ?: return
        }

        private fun refreshStates() {
            hostTextField.isEnabled = true
            portTextField.isEnabled = true
            usernameTextField.isEnabled = true
            authenticationTypeComboBox.isEnabled = true
            publicKeyComboBox.isEnabled = true
            passwordTextField.isEnabled = true
            chooseKeyBtn.isEnabled = true

            if (protocolTypeComboBox.selectedItem == Protocol.Local
                || protocolTypeComboBox.selectedItem == Protocol.Serial
            ) {
                hostTextField.isEnabled = false
                portTextField.isEnabled = false
                usernameTextField.isEnabled = false
                authenticationTypeComboBox.isEnabled = false
                passwordTextField.isEnabled = false
                publicKeyComboBox.isEnabled = false
                chooseKeyBtn.isEnabled = false
            }

        }

        override fun getIcon(isSelected: Boolean): Icon {
            return Icons.settings
        }

        override fun getTitle(): String {
            return I18n.getString("termora.new-host.general")
        }

        override fun getJComponent(): JComponent {
            return this
        }

        private fun getCenterComponent(): JComponent {
            val layout = FormLayout(
                "left:pref, $formMargin, default:grow, $formMargin, pref, $formMargin, default, default:grow",
                "pref, $formMargin, pref, $formMargin, pref, $formMargin, pref, $formMargin, pref, $formMargin, pref, $formMargin, pref, $formMargin, pref, $formMargin, pref"
            )
            remarkTextArea.setFocusTraversalKeys(
                KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .getDefaultFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS)
            )
            remarkTextArea.setFocusTraversalKeys(
                KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .getDefaultFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS)
            )

            remarkTextArea.rows = 8
            remarkTextArea.lineWrap = true
            remarkTextArea.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

            switchPasswordComponent()

            var rows = 1
            val step = 2
            val panel = FormBuilder.create().layout(layout)
                .add("${I18n.getString("termora.new-host.general.name")}:").xy(1, rows)
                .add(nameTextField).xyw(3, rows, 5).apply { rows += step }

                .add("${I18n.getString("termora.new-host.general.protocol")}:").xy(1, rows)
                .add(protocolTypeComboBox).xyw(3, rows, 5).apply { rows += step }

                .add("${I18n.getString("termora.new-host.general.host")}:").xy(1, rows)
                .add(hostTextField).xy(3, rows)
                .add("${I18n.getString("termora.new-host.general.port")}:").xy(5, rows)
                .add(portTextField).xy(7, rows).apply { rows += step }

                .add("${I18n.getString("termora.new-host.general.username")}:").xy(1, rows)
                .add(usernameTextField).xyw(3, rows, 5).apply { rows += step }

                .add("${I18n.getString("termora.new-host.general.authentication")}:").xy(1, rows)
                .add(authenticationTypeComboBox).xyw(3, rows, 5).apply { rows += step }

                .add("${I18n.getString("termora.new-host.general.password")}:").xy(1, rows)
                .add(passwordPanel).xyw(3, rows, 5).apply { rows += step }

                .add("${I18n.getString("termora.new-host.general.remark")}:").xy(1, rows)
                .add(JScrollPane(remarkTextArea).apply { border = FlatTextBorder() })
                .xyw(3, rows, 5).apply { rows += step }

                .build()


            return panel
        }

        private fun switchPasswordComponent() {
            passwordPanel.removeAll()

            if (authenticationTypeComboBox.selectedItem == AuthenticationType.PublicKey) {
                val selectedItem = publicKeyComboBox.selectedItem
                publicKeyComboBox.removeAllItems()
                for (pair in KeyManager.getInstance().getOhKeyPairs()) {
                    publicKeyComboBox.addItem(pair.id)
                }
                publicKeyComboBox.selectedItem = selectedItem
                passwordPanel.add(
                    FormBuilder.create()
                        .layout(FormLayout("default:grow, 4dlu, left:pref", "pref"))
                        .add(publicKeyComboBox).xy(1, 1)
                        .add(chooseKeyBtn).xy(3, 1)
                        .build(), BorderLayout.CENTER
                )
            } else if (authenticationTypeComboBox.selectedItem == AuthenticationType.SSHAgent) {
                passwordPanel.add(sshAgentComboBox, BorderLayout.CENTER)
            } else {
                passwordPanel.add(passwordTextField, BorderLayout.CENTER)
            }
            passwordPanel.revalidate()
            passwordPanel.repaint()
        }
    }

    protected inner class ProxyOption : JPanel(BorderLayout()), Option {
        val proxyTypeComboBox = FlatComboBox<ProxyType>()
        val proxyHostTextField = OutlineTextField()
        val proxyPasswordTextField = OutlinePasswordField()
        val proxyUsernameTextField = OutlineTextField()
        val proxyPortTextField = PortSpinner(1080)
        val proxyAuthenticationTypeComboBox = FlatComboBox<AuthenticationType>()


        init {
            initView()
            initEvents()
        }

        private fun initView() {
            add(getCenterComponent(), BorderLayout.CENTER)
            proxyAuthenticationTypeComboBox.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    var text = value?.toString() ?: ""
                    when (value) {
                        AuthenticationType.Password -> {
                            text = "Password"
                        }

                        AuthenticationType.PublicKey -> {
                            text = "Public Key"
                        }

                        AuthenticationType.KeyboardInteractive -> {
                            text = "Keyboard Interactive"
                        }
                    }
                    return super.getListCellRendererComponent(
                        list,
                        text,
                        index,
                        isSelected,
                        cellHasFocus
                    )
                }
            }

            proxyTypeComboBox.addItem(ProxyType.No)
            proxyTypeComboBox.addItem(ProxyType.HTTP)
            proxyTypeComboBox.addItem(ProxyType.SOCKS5)

            proxyAuthenticationTypeComboBox.addItem(AuthenticationType.No)
            proxyAuthenticationTypeComboBox.addItem(AuthenticationType.Password)

            proxyUsernameTextField.text = "root"

            refreshStates()
        }

        private fun initEvents() {
            proxyTypeComboBox.addItemListener {
                if (it.stateChange == ItemEvent.SELECTED) {
                    refreshStates()
                }
            }
            proxyAuthenticationTypeComboBox.addItemListener {
                if (it.stateChange == ItemEvent.SELECTED) {
                    refreshStates()
                }
            }
        }

        private fun refreshStates() {
            proxyHostTextField.isEnabled = proxyTypeComboBox.selectedItem != ProxyType.No
            proxyPortTextField.isEnabled = proxyHostTextField.isEnabled

            proxyAuthenticationTypeComboBox.isEnabled = proxyHostTextField.isEnabled
            proxyUsernameTextField.isEnabled = proxyAuthenticationTypeComboBox.selectedItem != AuthenticationType.No
            proxyPasswordTextField.isEnabled = proxyUsernameTextField.isEnabled
        }

        override fun getIcon(isSelected: Boolean): Icon {
            return Icons.network
        }

        override fun getTitle(): String {
            return I18n.getString("termora.new-host.proxy")
        }

        override fun getJComponent(): JComponent {
            return this
        }

        private fun getCenterComponent(): JComponent {
            val layout = FormLayout(
                "left:pref, $formMargin, default:grow, $formMargin, pref, $formMargin, default, default:grow",
                "pref, $formMargin, pref, $formMargin, pref, $formMargin, pref, $formMargin, pref, $formMargin, pref, $formMargin, pref, $formMargin, pref, $formMargin, pref"
            )

            var rows = 1
            val step = 2
            val panel = FormBuilder.create().layout(layout)
                .add("${I18n.getString("termora.new-host.general.protocol")}:").xy(1, rows)
                .add(proxyTypeComboBox).xyw(3, rows, 5).apply { rows += step }

                .add("${I18n.getString("termora.new-host.general.host")}:").xy(1, rows)
                .add(proxyHostTextField).xy(3, rows)
                .add("${I18n.getString("termora.new-host.general.port")}:").xy(5, rows)
                .add(proxyPortTextField).xy(7, rows).apply { rows += step }

                .add("${I18n.getString("termora.new-host.general.authentication")}:").xy(1, rows)
                .add(proxyAuthenticationTypeComboBox).xyw(3, rows, 5).apply { rows += step }

                .add("${I18n.getString("termora.new-host.general.username")}:").xy(1, rows)
                .add(proxyUsernameTextField).xyw(3, rows, 5).apply { rows += step }
                .add("${I18n.getString("termora.new-host.general.password")}:").xy(1, rows)
                .add(proxyPasswordTextField).xyw(3, rows, 5).apply { rows += step }

                .build()


            return panel
        }
    }

    protected inner class TerminalOption : JPanel(BorderLayout()), Option {
        val charsetComboBox = JComboBox<String>()
        val startupCommandTextField = OutlineTextField()
        val heartbeatIntervalTextField = IntSpinner(30, minimum = 3, maximum = Int.MAX_VALUE)
        val environmentTextArea = FixedLengthTextArea(2048)


        init {
            initView()
            initEvents()
        }

        private fun initView() {
            add(getCenterComponent(), BorderLayout.CENTER)


            environmentTextArea.setFocusTraversalKeys(
                KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .getDefaultFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS)
            )
            environmentTextArea.setFocusTraversalKeys(
                KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .getDefaultFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS)
            )

            environmentTextArea.rows = 8
            environmentTextArea.lineWrap = true
            environmentTextArea.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

            for (e in Charset.availableCharsets()) {
                charsetComboBox.addItem(e.key)
            }

            charsetComboBox.selectedItem = "UTF-8"

        }

        private fun initEvents() {

        }


        override fun getIcon(isSelected: Boolean): Icon {
            return Icons.terminal
        }

        override fun getTitle(): String {
            return I18n.getString("termora.new-host.terminal")
        }

        override fun getJComponent(): JComponent {
            return this
        }

        private fun getCenterComponent(): JComponent {
            val layout = FormLayout(
                "left:pref, $formMargin, default:grow, $formMargin, default:grow",
                "pref, $formMargin, pref, $formMargin, pref, $formMargin, pref"
            )

            var rows = 1
            val step = 2
            val panel = FormBuilder.create().layout(layout)
                .add("${I18n.getString("termora.new-host.terminal.encoding")}:").xy(1, rows)
                .add(charsetComboBox).xy(3, rows).apply { rows += step }
                .add("${I18n.getString("termora.new-host.terminal.heartbeat-interval")}:").xy(1, rows)
                .add(heartbeatIntervalTextField).xy(3, rows).apply { rows += step }
                .add("${I18n.getString("termora.new-host.terminal.startup-commands")}:").xy(1, rows)
                .add(startupCommandTextField).xy(3, rows).apply { rows += step }
                .add("${I18n.getString("termora.new-host.terminal.env")}:").xy(1, rows)
                .add(JScrollPane(environmentTextArea).apply { border = FlatTextBorder() }).xy(3, rows)
                .apply { rows += step }
                .build()


            return panel
        }
    }

    protected inner class SFTPOption : JPanel(BorderLayout()), Option {
        val defaultDirectoryField = OutlineTextField(255)


        init {
            initView()
            initEvents()
        }

        private fun initView() {
            add(getCenterComponent(), BorderLayout.CENTER)
        }

        private fun initEvents() {

        }


        override fun getIcon(isSelected: Boolean): Icon {
            return Icons.folder
        }

        override fun getTitle(): String {
            return "SFTP"
        }

        override fun getJComponent(): JComponent {
            return this
        }

        private fun getCenterComponent(): JComponent {
            val layout = FormLayout(
                "left:pref, $formMargin, default:grow, $formMargin",
                "pref, $formMargin, pref, $formMargin, pref, $formMargin, pref"
            )

            var rows = 1
            val step = 2
            val panel = FormBuilder.create().layout(layout)
                .add("${I18n.getString("termora.settings.sftp.default-directory")}:").xy(1, rows)
                .add(defaultDirectoryField).xy(3, rows).apply { rows += step }
                .build()


            return panel
        }
    }

    protected inner class TunnelingOption : JPanel(BorderLayout()), Option {
        val tunnelings = mutableListOf<Tunneling>()
        val x11ForwardingCheckBox = JCheckBox("X DISPLAY:")
        val x11ServerTextField = OutlineTextField(255)

        private val model = object : DefaultTableModel() {
            override fun getRowCount(): Int {
                return tunnelings.size
            }

            override fun isCellEditable(row: Int, column: Int): Boolean {
                return false
            }

            fun addRow(tunneling: Tunneling) {
                val rowCount = super.getRowCount()
                tunnelings.add(tunneling)
                super.fireTableRowsInserted(rowCount, rowCount + 1)
            }

            override fun getValueAt(row: Int, column: Int): Any {
                val tunneling = tunnelings[row]
                return when (column) {
                    0 -> tunneling.name
                    1 -> tunneling.type
                    2 -> "${tunneling.sourceHost}:${tunneling.sourcePort}"
                    3 -> "${tunneling.destinationHost}:${tunneling.destinationPort}"
                    else -> super.getValueAt(row, column)
                }
            }
        }
        private val table = JTable(model)
        private val addBtn = JButton(I18n.getString("termora.new-host.tunneling.add"))
        private val editBtn = JButton(I18n.getString("termora.new-host.tunneling.edit"))
        private val deleteBtn = JButton(I18n.getString("termora.new-host.tunneling.delete"))

        init {
            initView()
            initEvents()
        }

        private fun initView() {
            val scrollPane = JScrollPane(table)

            model.addColumn(I18n.getString("termora.new-host.tunneling.table.name"))
            model.addColumn(I18n.getString("termora.new-host.tunneling.table.type"))
            model.addColumn(I18n.getString("termora.new-host.tunneling.table.source"))
            model.addColumn(I18n.getString("termora.new-host.tunneling.table.destination"))


            table.putClientProperty(
                FlatClientProperties.STYLE, mapOf(
                    "showHorizontalLines" to true,
                    "showVerticalLines" to true,
                )
            )
            table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            table.border = BorderFactory.createEmptyBorder()
            table.fillsViewportHeight = true
            scrollPane.border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 0, 4, 0),
                BorderFactory.createMatteBorder(1, 1, 1, 1, DynamicColor.BorderColor)
            )

            deleteBtn.isFocusable = false
            addBtn.isFocusable = false
            editBtn.isFocusable = false

            editBtn.isEnabled = false
            deleteBtn.isEnabled = false

            val box = Box.createHorizontalBox()
            box.add(addBtn)
            box.add(Box.createHorizontalStrut(4))
            box.add(editBtn)
            box.add(Box.createHorizontalStrut(4))
            box.add(deleteBtn)

            x11ForwardingCheckBox.isFocusable = false

            if (x11ServerTextField.text.isBlank()) {
                x11ServerTextField.text = "localhost:0"
            }

            val x11Forwarding = Box.createHorizontalBox()
            x11Forwarding.border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("X11 Forwarding"),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)
            )
            x11Forwarding.add(x11ForwardingCheckBox)
            x11Forwarding.add(x11ServerTextField)

            x11ServerTextField.isEnabled = x11ForwardingCheckBox.isSelected

            val panel = JPanel(BorderLayout())
            panel.add(JLabel("TCP/IP Forwarding:"), BorderLayout.NORTH)
            panel.add(scrollPane, BorderLayout.CENTER)
            panel.add(box, BorderLayout.SOUTH)
            panel.border = BorderFactory.createEmptyBorder(0, 0, 8, 0)

            add(panel, BorderLayout.CENTER)
            add(x11Forwarding, BorderLayout.SOUTH)

        }

        private fun initEvents() {
            x11ForwardingCheckBox.addChangeListener { x11ServerTextField.isEnabled = x11ForwardingCheckBox.isSelected }

            addBtn.addActionListener(object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    val dialog = PortForwardingDialog(SwingUtilities.getWindowAncestor(this@HostOptionsPane))
                    dialog.isVisible = true
                    val tunneling = dialog.tunneling ?: return
                    model.addRow(tunneling)
                }
            })


            editBtn.addActionListener(object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    val row = table.selectedRow
                    if (row < 0) {
                        return
                    }
                    val dialog = PortForwardingDialog(
                        SwingUtilities.getWindowAncestor(this@HostOptionsPane),
                        tunnelings[row]
                    )
                    dialog.isVisible = true
                    tunnelings[row] = dialog.tunneling ?: return
                    model.fireTableRowsUpdated(row, row)
                }
            })

            deleteBtn.addActionListener(object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    val rows = table.selectedRows
                    if (rows.isEmpty()) return
                    rows.sortDescending()
                    for (row in rows) {
                        tunnelings.removeAt(row)
                        model.fireTableRowsDeleted(row, row)
                    }
                }
            })

            table.selectionModel.addListSelectionListener {
                editBtn.isEnabled = table.selectedRowCount > 0
                deleteBtn.isEnabled = editBtn.isEnabled
            }

            table.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount % 2 == 0 && SwingUtilities.isLeftMouseButton(e)) {
                        editBtn.actionListeners.forEach {
                            it.actionPerformed(
                                ActionEvent(
                                    editBtn,
                                    ActionEvent.ACTION_PERFORMED,
                                    StringUtils.EMPTY
                                )
                            )
                        }
                    }
                }
            })
        }

        override fun getIcon(isSelected: Boolean): Icon {
            return Icons.showWriteAccess
        }

        override fun getTitle(): String {
            return I18n.getString("termora.new-host.tunneling")
        }

        override fun getJComponent(): JComponent {
            return this
        }

        private inner class PortForwardingDialog(
            owner: Window,
            var tunneling: Tunneling? = null
        ) : DialogWrapper(owner) {
            private val formMargin = "4dlu"
            private val typeComboBox = FlatComboBox<TunnelingType>()
            private val nameTextField = OutlineTextField(32)
            private val localHostTextField = OutlineTextField()
            private val localPortSpinner = PortSpinner()
            private val remoteHostTextField = OutlineTextField()
            private val remotePortSpinner = PortSpinner()

            init {
                isModal = true
                title = I18n.getString("termora.new-host.tunneling")
                controlsVisible = false

                typeComboBox.addItem(TunnelingType.Local)
                typeComboBox.addItem(TunnelingType.Remote)
                typeComboBox.addItem(TunnelingType.Dynamic)

                localHostTextField.text = "127.0.0.1"
                localPortSpinner.value = 1080

                remoteHostTextField.text = "127.0.0.1"

                typeComboBox.addItemListener {
                    if (it.stateChange == ItemEvent.SELECTED) {
                        remoteHostTextField.isEnabled = typeComboBox.selectedItem != TunnelingType.Dynamic
                        remotePortSpinner.isEnabled = remoteHostTextField.isEnabled
                    }
                }

                tunneling?.let {
                    localHostTextField.text = it.sourceHost
                    localPortSpinner.value = it.sourcePort
                    remoteHostTextField.text = it.destinationHost
                    remotePortSpinner.value = it.destinationPort
                    nameTextField.text = it.name
                    typeComboBox.selectedItem = it.type
                }

                init()
                pack()
                size = Dimension(UIManager.getInt("Dialog.width") - 300, size.height)
                setLocationRelativeTo(null)

            }

            override fun doOKAction() {
                if (nameTextField.text.isBlank()) {
                    nameTextField.outline = "error"
                    nameTextField.requestFocusInWindow()
                    return
                } else if (localHostTextField.text.isBlank()) {
                    localHostTextField.outline = "error"
                    localHostTextField.requestFocusInWindow()
                    return
                } else if (remoteHostTextField.text.isBlank()) {
                    remoteHostTextField.outline = "error"
                    remoteHostTextField.requestFocusInWindow()
                    return
                }

                tunneling = Tunneling(
                    name = nameTextField.text,
                    type = typeComboBox.selectedItem as TunnelingType,
                    sourceHost = localHostTextField.text,
                    sourcePort = localPortSpinner.value as Int,
                    destinationHost = remoteHostTextField.text,
                    destinationPort = remotePortSpinner.value as Int,
                )

                super.doOKAction()
            }

            override fun doCancelAction() {
                tunneling = null
                super.doCancelAction()
            }

            override fun createCenterPanel(): JComponent {
                val layout = FormLayout(
                    "left:pref, $formMargin, default:grow, $formMargin, pref",
                    "pref, $formMargin, pref, $formMargin, pref, $formMargin, pref"
                )

                var rows = 1
                val step = 2
                return FormBuilder.create().layout(layout).padding("0dlu, $formMargin, $formMargin, $formMargin")
                    .add("${I18n.getString("termora.new-host.tunneling.table.name")}:").xy(1, rows)
                    .add(nameTextField).xyw(3, rows, 3).apply { rows += step }
                    .add("${I18n.getString("termora.new-host.tunneling.table.type")}:").xy(1, rows)
                    .add(typeComboBox).xyw(3, rows, 3).apply { rows += step }
                    .add("${I18n.getString("termora.new-host.tunneling.table.source")}:").xy(1, rows)
                    .add(localHostTextField).xy(3, rows)
                    .add(localPortSpinner).xy(5, rows).apply { rows += step }
                    .add("${I18n.getString("termora.new-host.tunneling.table.destination")}:").xy(1, rows)
                    .add(remoteHostTextField).xy(3, rows)
                    .add(remotePortSpinner).xy(5, rows).apply { rows += step }
                    .build()
            }


        }
    }

    protected inner class SerialCommOption : JPanel(BorderLayout()), Option {
        val serialPortComboBox = OutlineComboBox<String>()
        val baudRateComboBox = OutlineComboBox<Int>()
        val dataBitsComboBox = OutlineComboBox<Int>()
        val parityComboBox = OutlineComboBox<SerialCommParity>()
        val stopBitsComboBox = OutlineComboBox<String>()
        val flowControlComboBox = OutlineComboBox<SerialCommFlowControl>()


        init {
            initView()
            initEvents()
        }

        private fun initView() {

            serialPortComboBox.isEditable = true

            baudRateComboBox.isEditable = true
            baudRateComboBox.addItem(9600)
            baudRateComboBox.addItem(19200)
            baudRateComboBox.addItem(38400)
            baudRateComboBox.addItem(57600)
            baudRateComboBox.addItem(115200)

            dataBitsComboBox.addItem(5)
            dataBitsComboBox.addItem(6)
            dataBitsComboBox.addItem(7)
            dataBitsComboBox.addItem(8)
            dataBitsComboBox.selectedItem = 8

            parityComboBox.addItem(SerialCommParity.None)
            parityComboBox.addItem(SerialCommParity.Even)
            parityComboBox.addItem(SerialCommParity.Odd)
            parityComboBox.addItem(SerialCommParity.Mark)
            parityComboBox.addItem(SerialCommParity.Space)

            stopBitsComboBox.addItem("1")
            stopBitsComboBox.addItem("1.5")
            stopBitsComboBox.addItem("2")
            stopBitsComboBox.selectedItem = "1"

            flowControlComboBox.addItem(SerialCommFlowControl.None)
            flowControlComboBox.addItem(SerialCommFlowControl.RTS_CTS)
            flowControlComboBox.addItem(SerialCommFlowControl.XON_XOFF)

            flowControlComboBox.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val text = value?.toString() ?: StringUtils.EMPTY
                    return super.getListCellRendererComponent(
                        list,
                        text.replace('_', '/'),
                        index,
                        isSelected,
                        cellHasFocus
                    )
                }
            }

            add(getCenterComponent(), BorderLayout.CENTER)
        }

        private fun initEvents() {
            addComponentListener(object : ComponentAdapter() {
                override fun componentShown(e: ComponentEvent) {
                    removeComponentListener(this)
                    swingCoroutineScope.launch(Dispatchers.IO) {
                        for (commPort in SerialPort.getCommPorts()) {
                            withContext(Dispatchers.Swing) {
                                serialPortComboBox.addItem(commPort.systemPortName)
                            }
                        }
                    }
                }
            })
        }

        override fun getIcon(isSelected: Boolean): Icon {
            return Icons.plugin
        }

        override fun getTitle(): String {
            return I18n.getString("termora.new-host.serial")
        }

        override fun getJComponent(): JComponent {
            return this
        }

        private fun getCenterComponent(): JComponent {
            val layout = FormLayout(
                "left:pref, $formMargin, default:grow, $formMargin",
                "pref, $formMargin, pref, $formMargin, pref, $formMargin, pref, $formMargin, pref, $formMargin, pref"
            )

            var rows = 1
            val step = 2
            val panel = FormBuilder.create().layout(layout)
                .add("${I18n.getString("termora.new-host.serial.port")}:").xy(1, rows)
                .add(serialPortComboBox).xy(3, rows).apply { rows += step }
                .add("${I18n.getString("termora.new-host.serial.baud-rate")}:").xy(1, rows)
                .add(baudRateComboBox).xy(3, rows).apply { rows += step }
                .add("${I18n.getString("termora.new-host.serial.data-bits")}:").xy(1, rows)
                .add(dataBitsComboBox).xy(3, rows).apply { rows += step }
                .add("${I18n.getString("termora.new-host.serial.parity")}:").xy(1, rows)
                .add(parityComboBox).xy(3, rows).apply { rows += step }
                .add("${I18n.getString("termora.new-host.serial.stop-bits")}:").xy(1, rows)
                .add(stopBitsComboBox).xy(3, rows).apply { rows += step }
                .add("${I18n.getString("termora.new-host.serial.flow-control")}:").xy(1, rows)
                .add(flowControlComboBox).xy(3, rows).apply { rows += step }
                .build()
            return panel
        }
    }

    protected inner class JumpHostsOption : JPanel(BorderLayout()), Option {
        val jumpHosts = mutableListOf<Host>()
        var filter: (host: Host) -> Boolean = { true }

        private val model = object : DefaultTableModel() {

            override fun isCellEditable(row: Int, column: Int): Boolean {
                return false
            }

            override fun getRowCount(): Int {
                return jumpHosts.size
            }


            override fun getValueAt(row: Int, column: Int): Any {
                val host = jumpHosts.getOrNull(row) ?: return StringUtils.EMPTY
                return if (column == 0)
                    host.name
                else "${host.host}:${host.port}"
            }
        }
        private val table = JTable(model)
        private val addBtn = JButton(I18n.getString("termora.new-host.tunneling.add"))
        private val moveUpBtn = JButton(I18n.getString("termora.transport.bookmarks.up"))
        private val moveDownBtn = JButton(I18n.getString("termora.transport.bookmarks.down"))
        private val deleteBtn = JButton(I18n.getString("termora.new-host.tunneling.delete"))

        init {
            initView()
            initEvents()
        }

        private fun initView() {
            val scrollPane = JScrollPane(table)

            model.addColumn(I18n.getString("termora.new-host.general.name"))
            model.addColumn(I18n.getString("termora.new-host.general.host"))

            table.putClientProperty(
                FlatClientProperties.STYLE, mapOf(
                    "showHorizontalLines" to true,
                    "showVerticalLines" to true,
                )
            )
            table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            table.setDefaultRenderer(
                Any::class.java,
                DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.CENTER })
            table.fillsViewportHeight = true
            scrollPane.border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 0, 4, 0),
                BorderFactory.createMatteBorder(1, 1, 1, 1, DynamicColor.BorderColor)
            )
            table.border = BorderFactory.createEmptyBorder()

            moveUpBtn.isFocusable = false
            moveDownBtn.isFocusable = false
            deleteBtn.isFocusable = false
            moveUpBtn.isEnabled = false
            moveDownBtn.isEnabled = false
            deleteBtn.isEnabled = false
            addBtn.isFocusable = false

            val box = Box.createHorizontalBox()
            box.add(addBtn)
            box.add(Box.createHorizontalStrut(4))
            box.add(deleteBtn)
            box.add(Box.createHorizontalStrut(4))
            box.add(moveUpBtn)
            box.add(Box.createHorizontalStrut(4))
            box.add(moveDownBtn)

            add(JLabel("${getTitle()}:"), BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(box, BorderLayout.SOUTH)
        }

        private fun initEvents() {
            addBtn.addActionListener(object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    val dialog = NewHostTreeDialog(owner)
                    dialog.setFilter { node -> jumpHosts.none { it.id == node.host.id } && filter.invoke(node.host) }
                    dialog.setTreeName("HostOptionsPane.JumpHostsOption.Tree")
                    dialog.setLocationRelativeTo(owner)
                    dialog.isVisible = true
                    val hosts = dialog.hosts
                    if (hosts.isEmpty()) {
                        return
                    }

                    hosts.forEach {
                        val rowCount = model.rowCount
                        jumpHosts.add(it)
                        model.fireTableRowsInserted(rowCount, rowCount + 1)
                    }
                }
            })

            deleteBtn.addActionListener(object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    val rows = table.selectedRows.sortedDescending()
                    if (rows.isEmpty()) return
                    for (row in rows) {
                        jumpHosts.removeAt(row)
                        model.fireTableRowsDeleted(row, row)
                    }
                }
            })

            table.selectionModel.addListSelectionListener {
                deleteBtn.isEnabled = table.selectedRowCount > 0
                moveUpBtn.isEnabled = deleteBtn.isEnabled && !table.selectedRows.contains(0)
                moveDownBtn.isEnabled = deleteBtn.isEnabled && !table.selectedRows.contains(table.rowCount - 1)
            }


            moveUpBtn.addActionListener(object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    val rows = table.selectedRows.sorted()
                    if (rows.isEmpty()) return

                    table.clearSelection()

                    for (row in rows) {
                        val host = jumpHosts[(row)]
                        jumpHosts.removeAt(row)
                        jumpHosts.add(row - 1, host)
                        table.addRowSelectionInterval(row - 1, row - 1)
                    }
                }
            })

            moveDownBtn.addActionListener(object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    val rows = table.selectedRows.sortedDescending()
                    if (rows.isEmpty()) return

                    table.clearSelection()

                    for (row in rows) {
                        val host = jumpHosts[(row)]
                        jumpHosts.removeAt(row)
                        jumpHosts.add(row + 1, host)
                        table.addRowSelectionInterval(row + 1, row + 1)
                    }
                }
            })
        }

        override fun getIcon(isSelected: Boolean): Icon {
            return Icons.server
        }

        override fun getTitle(): String {
            return I18n.getString("termora.new-host.jump-hosts")
        }

        override fun getJComponent(): JComponent {
            return this
        }


    }
}