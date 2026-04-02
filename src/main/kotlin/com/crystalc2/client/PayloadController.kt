package com.crystalc2.client

import com.google.protobuf.Empty
import crystalpalace.spec.Capability
import crystalpalace.spec.LinkSpec
import io.grpc.stub.StreamObserver
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Stage
import java.io.File
import javafx.util.StringConverter
import listeners.Listeners.ListenerEvent
import listeners.Listeners.ListenerEventType
import listeners.Listeners.ListenerInfo
import listeners.ListenerServiceGrpc

class PayloadController {

    @FXML private lateinit var buildButton: Button
    @FXML private lateinit var listenerCombo: ComboBox<ListenerInfo>
    @FXML private lateinit var archCheck: CheckBox
    @FXML private lateinit var yaraCheck: CheckBox
    @FXML private lateinit var sleepField: TextField
    @FXML private lateinit var jitterField: TextField
    @FXML private lateinit var extensionsBox: VBox

    @FXML
    fun initialize() {
        archCheck.selectedProperty().addListener { _, _, checked ->
            archCheck.text = if (checked) "x64" else "x86"
        }

        listenerCombo.apply {
            val display: (ListenerInfo?) -> String = { it?.let { l -> "${l.name} (${l.coffName})" } ?: "" }
            converter = object : StringConverter<ListenerInfo>() {
                override fun toString(l: ListenerInfo?) = display(l)
                override fun fromString(s: String?) = null
            }
            setCellFactory {
                object : ListCell<ListenerInfo>() {
                    override fun updateItem(l: ListenerInfo?, empty: Boolean) {
                        super.updateItem(l, empty)
                        text = if (empty || l == null) null else display(l)
                    }
                }
            }
            buttonCell = object : ListCell<ListenerInfo>() {
                override fun updateItem(l: ListenerInfo?, empty: Boolean) {
                    super.updateItem(l, empty)
                    text = if (empty || l == null) null else display(l)
                }
            }
            valueProperty().addListener { _, _, v ->
                buildButton.isDisable = v == null
            }
        }

        rebuildExtensions()
        ScriptBridge.onPayloadExtensionsChanged = ::rebuildExtensions

        ListenerServiceGrpc.newStub(GrpcClient.channel)
            .listenerEvents(Empty.getDefaultInstance(), object : StreamObserver<ListenerEvent> {
                override fun onNext(ev: ListenerEvent) {
                    Platform.runLater {
                        when (ev.type) {
                            ListenerEventType.LISTENER_EVENT_ADDED -> {
                                listenerCombo.items.removeIf { it.id == ev.listener.id }
                                listenerCombo.items.add(ev.listener)
                            }
                            ListenerEventType.LISTENER_EVENT_DELETED -> {
                                val selected = listenerCombo.value
                                listenerCombo.items.removeIf { it.id == ev.listener.id }
                                if (selected?.id == ev.listener.id) listenerCombo.value = null
                            }
                            else -> {}
                        }
                    }
                }
                override fun onError(t: Throwable) { t.printStackTrace() }
                override fun onCompleted() {}
            })
    }

    private fun rebuildExtensions() {
        // Keep the header label (index 0), replace everything after it
        val label = extensionsBox.children.firstOrNull()
        extensionsBox.children.clear()
        if (label != null) extensionsBox.children.add(label)
        if (ScriptBridge.payloadExtensions.isEmpty()) {
            extensionsBox.children.add(
                Label("no extensions loaded").apply {
                    style = "-fx-text-fill: #666666; -fx-padding: 6 0 0 0; -fx-font-style: italic;"
                }
            )
        } else {
            ScriptBridge.payloadExtensions.forEach { ext ->
                extensionsBox.children.add(
                    CheckBox(ext.label).apply {
                        userData = ext.specPath
                        style = "-fx-text-fill: #bbbbbb; -fx-padding: 6 0 0 0;"
                    }
                )
            }
        }
    }

    @FXML
    fun onBuild() {
        try {
            val listener = listenerCombo.value ?: return

            val spec = LinkSpec.Parse("resources/agent.spec")
            spec.addLogger(CrystalConsoleLogger)

            val arch = if (archCheck.isSelected) "x64" else "x86"
            val cap = Capability.None(arch)

            val params = HashMap<String, Any>()
            params["%UDC2"] = File("resources", listener.coffName).absolutePath

            params["%SLEEP"] = sleepField.text.trim().ifEmpty { "05" }
            params["%JITTER"] = jitterField.text.trim().ifEmpty { "00" }
            params["\$PUBKEY"] = listener.publicKey.toByteArray()

            val extensions = extensionsBox.children
                .filterIsInstance<CheckBox>()
                .filter { it.isSelected }
                .map { it.userData as String }

            params["%EXTENSION"] = extensions.joinToString(",")

            val result = spec.runAndGenerate(cap, params)

            val file = FileChooser().apply {
                title = "Save Payload"
                initialFileName = "beacon.$arch.bin"
                extensionFilters.addAll(
                    FileChooser.ExtensionFilter("Binary Files", "*.bin"),
                    FileChooser.ExtensionFilter("All Files", "*.*")
                )
            }.showSaveDialog(buildButton.scene.window) ?: return

            file.writeBytes(result.program)

            if (yaraCheck.isSelected) {
                val rulesText = String(result.rules)
                val textArea = TextArea(rulesText).apply {
                    isEditable = false
                    VBox.setVgrow(this, Priority.ALWAYS)
                    style = "-fx-font-family: monospace;"
                }
                val saveBtn = Button("Save Rules").apply {
                    styleClass.add("btn")
                    setOnAction {
                        val dest = FileChooser().apply {
                            title = "Save YARA Rules"
                            initialFileName = "beacon.${if (archCheck.isSelected) "x64" else "x86"}.yar"
                            extensionFilters.addAll(
                                FileChooser.ExtensionFilter("YARA Files", "*.yar", "*.yara"),
                                FileChooser.ExtensionFilter("All Files", "*.*")
                            )
                        }.showSaveDialog(this.scene.window) ?: return@setOnAction
                        dest.writeText(rulesText)
                    }
                }
                val toolbar = HBox(saveBtn).apply {
                    padding = Insets(6.0, 8.0, 6.0, 8.0)
                    style = "-fx-background-color: #2d2d2d;"
                }
                Stage().apply {
                    title = "YARA Rules"
                    scene = Scene(
                        VBox(toolbar, textArea)
                    ).also {
                        it.stylesheets.add(CrystalC2.getResource("styles.css")!!.toExternalForm())
                    }
                    show()
                }
            }

        } catch (exception: Exception) {
            print(exception.message)
        }
    }
}