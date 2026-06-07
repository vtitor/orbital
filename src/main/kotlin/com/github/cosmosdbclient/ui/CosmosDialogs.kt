package com.github.cosmosdbclient.ui

import com.azure.cosmos.models.TriggerOperation
import com.azure.cosmos.models.TriggerType
import com.github.cosmosdbclient.model.CosmosConnection
import com.github.cosmosdbclient.service.ConnectionStrings
import com.github.cosmosdbclient.service.ContainerDetails
import com.github.cosmosdbclient.service.CosmosConnectionStorage
import com.github.cosmosdbclient.service.CosmosErrors
import com.github.cosmosdbclient.service.CosmosService
import com.github.cosmosdbclient.service.ScriptKind
import com.github.cosmosdbclient.service.ThroughputMode
import com.github.cosmosdbclient.service.ThroughputSpec
import com.github.cosmosdbclient.util.Bg
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

// ============================================================================
//  Connections
// ============================================================================

class AddConnectionDialog(
    private val project: Project?,
    private val existing: CosmosConnection?,
) : DialogWrapper(project) {

    private val nameField = JBTextField(existing?.name.orEmpty(), 32)
    private val endpointField = JBTextField(existing?.endpoint.orEmpty(), 32)
    private val keyField = JBPasswordField()
    private val gatewayCheck = JBCheckBox(
        "Use Gateway connection mode (recommended; works behind most firewalls)",
        existing?.preferGateway ?: true,
    )
    private val testButton = JButton("Test connection")
    private val testLabel = JBLabel(" ")

    init {
        title = if (existing == null) "Add Connection" else "Edit Connection"
        if (existing != null) {
            nameField.isEditable = false
            CosmosConnectionStorage.getInstance().getKey(existing.name)?.let { keyField.text = it }
        }
        testButton.addActionListener { runTest() }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val hint = JBLabel(
            "<html>Tip: paste a full connection string into the <b>Account URI</b> field " +
                "(AccountEndpoint=…;AccountKey=…;) and it will be split automatically.</html>",
        ).apply { foreground = JBColor.GRAY }

        val testRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            add(testButton)
            add(JBLabel("   "))
            add(testLabel)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Display name:", nameField)
            .addLabeledComponent("Account URI:", endpointField)
            .addLabeledComponent("Account key:", keyField)
            .addComponent(gatewayCheck)
            .addComponent(testRow)
            .addComponentFillVertically(JPanel(), 8)
            .addComponent(hint)
            .panel
            .apply { preferredSize = Dimension(560, 220) }
    }

    override fun getPreferredFocusedComponent(): JComponent = if (existing == null) nameField else keyField

    override fun doValidate(): ValidationInfo? {
        val result = result()
        if (result.name.isBlank()) return ValidationInfo("A display name is required.", nameField)
        if (result.endpoint.isBlank()) return ValidationInfo("Account URI is required.", endpointField)
        if (!result.endpoint.startsWith("http", ignoreCase = true)) {
            return ValidationInfo("Account URI should start with https://", endpointField)
        }
        if (result.key.isBlank()) return ValidationInfo("Account key is required.", keyField)
        return null
    }

    private fun runTest() {
        val input = result()
        if (input.endpoint.isBlank() || input.key.isBlank()) {
            testLabel.text = "Enter URI and key first"
            testLabel.foreground = JBColor.RED
            return
        }
        testLabel.text = "Testing…"
        testLabel.foreground = JBColor.GRAY
        testButton.isEnabled = false
        Bg.run(
            project,
            "Testing connection…",
            work = { CosmosService.getInstance().testConnection(input.endpoint, input.key, input.preferGateway) },
            onSuccess = { count ->
                testButton.isEnabled = true
                testLabel.text = "✓ Connected — $count database(s)"
                testLabel.foreground = JBColor(0x3C8033, 0x5FAD51)
            },
            onError = { error ->
                testButton.isEnabled = true
                testLabel.text = "✗ " + CosmosErrors.shortMessage(error)
                testLabel.foreground = JBColor.RED
            },
        )
    }

    fun result(): ConnInput {
        val rawUri = endpointField.text.trim()
        val rawKey = String(keyField.password).trim()
        val name = nameField.text.trim()
        val parsed = ConnectionStrings.parse(rawUri)
        if (parsed != null) {
            val (endpoint, key) = parsed
            return ConnInput(name, endpoint, key.ifBlank { rawKey }, gatewayCheck.isSelected)
        }
        return ConnInput(name, rawUri, rawKey, gatewayCheck.isSelected)
    }

    data class ConnInput(val name: String, val endpoint: String, val key: String, val preferGateway: Boolean)
}

// ============================================================================
//  Throughput control reused by create dialogs
// ============================================================================

private class ThroughputPicker(allowNone: Boolean) {
    val modeCombo = ComboBox(
        if (allowNone) arrayOf(ThroughputMode.MANUAL, ThroughputMode.AUTOSCALE, ThroughputMode.NONE)
        else arrayOf(ThroughputMode.MANUAL, ThroughputMode.AUTOSCALE),
    )
    val valueSpinner = JSpinner(SpinnerNumberModel(400, 400, 1_000_000, 100))

    init {
        modeCombo.renderer = SimpleListCellRenderer.create("") { mode ->
            when (mode) {
                ThroughputMode.MANUAL -> "Manual (RU/s)"
                ThroughputMode.AUTOSCALE -> "Autoscale (max RU/s)"
                ThroughputMode.NONE -> "Use database / serverless"
                else -> ""
            }
        }
        modeCombo.addActionListener { valueSpinner.isEnabled = spec().mode != ThroughputMode.NONE }
    }

    fun spec(): ThroughputSpec =
        ThroughputSpec(modeCombo.selectedItem as ThroughputMode, (valueSpinner.value as Int))
}

// ============================================================================
//  Create database / container
// ============================================================================

class CreateDatabaseDialog(project: Project?) : DialogWrapper(project) {
    private val idField = JBTextField("", 28)
    private val throughput = ThroughputPicker(allowNone = true)

    init {
        title = "Create Database"
        throughput.modeCombo.selectedItem = ThroughputMode.NONE
        throughput.valueSpinner.isEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Database id:", idField)
        .addLabeledComponent("Throughput:", throughput.modeCombo)
        .addLabeledComponent("Value:", throughput.valueSpinner)
        .panel
        .apply { preferredSize = Dimension(420, 130) }

    override fun getPreferredFocusedComponent() = idField
    override fun doValidate(): ValidationInfo? =
        if (idField.text.isBlank()) ValidationInfo("Database id is required.", idField) else null

    fun databaseId(): String = idField.text.trim()
    fun throughputSpec(): ThroughputSpec = throughput.spec()
}

class CreateContainerDialog(project: Project?) : DialogWrapper(project) {
    private val idField = JBTextField("", 28)
    private val pkField = JBTextField("/id", 28)
    private val throughput = ThroughputPicker(allowNone = true)

    init {
        title = "Create Container"
        throughput.modeCombo.selectedItem = ThroughputMode.MANUAL
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Container id:", idField)
        .addLabeledComponent("Partition key path:", pkField)
        .addLabeledComponent("Throughput:", throughput.modeCombo)
        .addLabeledComponent("Value:", throughput.valueSpinner)
        .panel
        .apply { preferredSize = Dimension(440, 160) }

    override fun getPreferredFocusedComponent() = idField
    override fun doValidate(): ValidationInfo? = when {
        idField.text.isBlank() -> ValidationInfo("Container id is required.", idField)
        pkField.text.isBlank() -> ValidationInfo("Partition key path is required (e.g. /id).", pkField)
        else -> null
    }

    fun containerId(): String = idField.text.trim()
    fun partitionKeyPath(): String = pkField.text.trim()
    fun throughputSpec(): ThroughputSpec = throughput.spec()
}

// ============================================================================
//  Container properties (read-only)
// ============================================================================

class ContainerPropertiesDialog(project: Project?, details: ContainerDetails) : DialogWrapper(project) {
    private val details = details

    init {
        title = "Container — ${details.id}"
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Id:", JBLabel(details.id))
        .addLabeledComponent("Partition key:", JBLabel(details.partitionKeyPaths.joinToString().ifEmpty { "(none)" }))
        .addLabeledComponent("Default TTL:", JBLabel(details.defaultTtlSeconds?.let { "$it s" } ?: "disabled"))
        .addLabeledComponent("Indexing mode:", JBLabel(details.indexingMode))
        .addLabeledComponent("Throughput:", JBLabel(details.throughput?.display() ?: "shared / serverless"))
        .panel
        .apply { preferredSize = Dimension(420, 150) }

    override fun createActions() = arrayOf(okAction)
}

// ============================================================================
//  Script editor (stored procedure / trigger / UDF)
// ============================================================================

data class ScriptEditResult(
    val id: String,
    val body: String,
    val triggerType: TriggerType?,
    val triggerOperation: TriggerOperation?,
)

class ScriptEditorDialog(
    private val project: Project?,
    private val kind: ScriptKind,
    private val existingId: String?,
    initialBody: String?,
    triggerType: TriggerType?,
    triggerOperation: TriggerOperation?,
) : DialogWrapper(project) {

    private val idField = JBTextField(existingId.orEmpty(), 28)
    private val typeCombo = ComboBox(TriggerType.values())
    private val opCombo = ComboBox(TriggerOperation.values())
    private val bodyEditor = CosmosEditors.plain(
        project ?: error("project required"),
        initialBody ?: defaultBody(kind),
    )

    init {
        title = (if (existingId == null) "New " else "Edit ") + kind.title.trimEnd('s')
        idField.isEditable = existingId == null
        triggerType?.let { typeCombo.selectedItem = it }
        triggerOperation?.let { opCombo.selectedItem = it }
        bodyEditor.preferredSize = Dimension(660, 380)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Id:", idField)
        if (kind == ScriptKind.TRIGGER) {
            builder.addLabeledComponent("Type:", typeCombo)
            builder.addLabeledComponent("Operation:", opCombo)
        }
        return builder
            .addLabeledComponentFillVertically("Body (JavaScript):", bodyEditor)
            .panel
            .apply {
                border = JBUI.Borders.empty(6)
                preferredSize = Dimension(700, 480)
            }
    }

    override fun getPreferredFocusedComponent(): JComponent = if (existingId == null) idField else bodyEditor

    override fun doValidate(): ValidationInfo? = when {
        idField.text.isBlank() -> ValidationInfo("Id is required.", idField)
        bodyEditor.text.isBlank() -> ValidationInfo("Body cannot be empty.", bodyEditor)
        else -> null
    }

    fun result(): ScriptEditResult = ScriptEditResult(
        idField.text.trim(),
        bodyEditor.text,
        if (kind == ScriptKind.TRIGGER) typeCombo.selectedItem as TriggerType else null,
        if (kind == ScriptKind.TRIGGER) opCombo.selectedItem as TriggerOperation else null,
    )

    private fun defaultBody(kind: ScriptKind): String = when (kind) {
        ScriptKind.STORED_PROCEDURE ->
            "function sample(prefix) {\n" +
                "    var context = getContext();\n" +
                "    var response = context.getResponse();\n" +
                "    response.setBody('Hello ' + prefix);\n" +
                "}\n"
        ScriptKind.TRIGGER ->
            "function trigger() {\n    // var request = getContext().getRequest();\n}\n"
        ScriptKind.UDF ->
            "function udf(value) {\n    return value;\n}\n"
    }
}

// ============================================================================
//  Execute stored procedure
// ============================================================================

class ExecuteSprocDialog(project: Project?) : DialogWrapper(project) {
    private val partitionKeyField = JBTextField("", 28)
    private val paramsEditor = CosmosEditors.json(project ?: error("project required"), "[]")

    init {
        title = "Execute Stored Procedure"
        paramsEditor.preferredSize = Dimension(520, 200)
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Partition key value:", partitionKeyField)
        .addLabeledComponentFillVertically("Parameters (JSON array):", paramsEditor)
        .panel
        .apply { preferredSize = Dimension(560, 280) }

    fun partitionKeyText(): String = partitionKeyField.text.trim()
    fun paramsText(): String = paramsEditor.text
}
