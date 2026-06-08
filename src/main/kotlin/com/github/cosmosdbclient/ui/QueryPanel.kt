package com.github.cosmosdbclient.ui

import com.azure.cosmos.models.PartitionKey
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.cosmosdbclient.service.CosmosErrors
import com.github.cosmosdbclient.service.CosmosService
import com.github.cosmosdbclient.util.Bg
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorTextField
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel

/**
 * One open container: a SQL editor on top, a results table in the middle, and a JSON
 * document editor (with upsert / delete) at the bottom.
 */
class QueryPanel(
    private val project: Project,
    private val connectionName: String,
    private val databaseId: String,
    private val containerId: String,
    private val partitionKeyPaths: List<String>,
) : JPanel(BorderLayout()) {

    private val service = CosmosService.getInstance()
    private var continuationToken: String? = null
    private var queryGeneration = 0
    private val loadedItems = mutableListOf<JsonNode>()

    /** id + partition key of the document currently loaded in the editor (null for a new doc). */
    private var loadedId: String? = null
    private var loadedPartitionKey: PartitionKey? = null

    private val queryEditor = CosmosEditors.plain(project, "SELECT * FROM c").apply {
        preferredSize = Dimension(100, 92)
    }
    private val pageSizeSpinner = JSpinner(SpinnerNumberModel(100, 1, 10000, 50))
    private val executeButton = JButton("Execute", AllIcons.Actions.Execute)
    private val loadMoreButton = JButton("Load more").apply { isEnabled = false }
    private val exportButton = JButton("Export", AllIcons.General.Export).apply { isEnabled = false }

    private val tableModel = ResultTableModel()
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoCreateRowSorter = true
        autoResizeMode = JBTable.AUTO_RESIZE_OFF
    }

    private val idField = JBTextField("", 18)
    private val findButton = JButton("Find by id")
    private val docEditor: EditorTextField = CosmosEditors.json(project, "")
    private val newButton = JButton("New", AllIcons.General.Add)
    private val saveButton = JButton("Save (upsert)")
    private val deleteButton = JButton("Delete", AllIcons.General.Remove).apply { isEnabled = false }

    private val countLabel = JBLabel("Results")
    private val statusLabel = JBLabel(" ").apply { border = JBUI.Borders.empty(3, 6) }

    init {
        add(buildHeader(), BorderLayout.NORTH)
        add(buildBody(), BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply { add(statusLabel, BorderLayout.WEST) }, BorderLayout.SOUTH)

        executeButton.addActionListener { execute(reset = true) }
        loadMoreButton.addActionListener { execute(reset = false) }
        exportButton.addActionListener { exportResults() }
        findButton.addActionListener { findById() }
        newButton.addActionListener { newDocument() }
        saveButton.addActionListener { saveDocument() }
        deleteButton.addActionListener { deleteDocument() }

        table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) showSelectedRow()
        }

        execute(reset = true)
    }

    private fun buildHeader(): JComponent {
        val title = JBLabel("$databaseId  ▸  $containerId").apply {
            font = JBFont.label().asBold()
            if (partitionKeyPaths.isNotEmpty()) {
                toolTipText = "Partition key: ${partitionKeyPaths.joinToString()}"
            }
            border = JBUI.Borders.empty(4, 6, 2, 6)
        }
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(executeButton)
            add(JLabel("Rows/page:"))
            add(pageSizeSpinner)
            add(loadMoreButton)
            add(exportButton)
        }
        return JPanel(BorderLayout()).apply {
            add(title, BorderLayout.NORTH)
            add(JBScrollPane(queryEditor), BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }
    }

    private fun buildBody(): JComponent {
        val resultsPanel = JPanel(BorderLayout()).apply {
            add(countLabel.apply { border = JBUI.Borders.empty(3, 6) }, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        }

        val docToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JLabel("id:"))
            add(idField)
            add(findButton)
            add(JLabel("    "))
            add(newButton)
            add(saveButton)
            add(deleteButton)
        }
        val docPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("Document (JSON)").apply { border = JBUI.Borders.empty(3, 6) }, BorderLayout.NORTH)
            add(JBScrollPane(docEditor), BorderLayout.CENTER)
            add(docToolbar, BorderLayout.SOUTH)
        }

        return OnePixelSplitter(true, 0.55f).apply {
            firstComponent = resultsPanel
            secondComponent = docPanel
        }
    }

    // ---- query -----------------------------------------------------------------

    private fun execute(reset: Boolean) {
        if (reset) {
            continuationToken = null
            loadedItems.clear()
            tableModel.clear()                 // don't leave stale rows visible while (re)querying
            countLabel.text = "Results"
        }
        val sql = queryEditor.text.ifBlank { "SELECT * FROM c" }
        val pageSize = pageSizeSpinner.value as Int
        val token = if (reset) null else continuationToken
        val generation = ++queryGeneration     // tag this request; ignore callbacks from older ones

        executeButton.isEnabled = false
        loadMoreButton.isEnabled = false
        Bg.run(
            project,
            "Querying $containerId…",
            work = { service.query(connectionName, databaseId, containerId, sql, pageSize, token) },
            onSuccess = { page ->
                if (generation == queryGeneration) {
                    if (reset) tableModel.setRows(page.items) else tableModel.addRows(page.items)
                    loadedItems.addAll(page.items)
                    continuationToken = page.continuationToken
                    executeButton.isEnabled = true
                    loadMoreButton.isEnabled = continuationToken != null
                    exportButton.isEnabled = loadedItems.isNotEmpty()
                    countLabel.text = "Results — ${tableModel.size} document(s)"
                    statusLabel.text = "RU (page): ${"%.2f".format(page.requestCharge)}   •   ${page.elapsedMillis} ms" +
                        (if (continuationToken != null) "   •   more available" else "")
                }
            },
            onError = { error ->
                if (generation == queryGeneration) {
                    executeButton.isEnabled = true
                    CosmosErrors.notifyError(project, "Query", error)
                }
            },
        )
    }

    private fun findById() {
        val id = idField.text.trim()
        if (id.isEmpty()) return
        val escaped = id.replace("\\", "\\\\").replace("\"", "\\\"")
        queryEditor.text = "SELECT * FROM c WHERE c.id = \"$escaped\""
        execute(reset = true)
    }

    // ---- document editing ------------------------------------------------------

    private fun showSelectedRow() {
        val viewRow = table.selectedRow
        val item = if (viewRow < 0) null else tableModel.rowItem(table.convertRowIndexToModel(viewRow))
        if (item == null) {
            deleteButton.isEnabled = false
            loadedId = null
            loadedPartitionKey = null
            return
        }
        docEditor.text = service.prettyPrint(item)
        // Only a full document object with an id can be replaced/deleted in place; remember its
        // original id + partition key so save can detect a partition-changing edit.
        val id = (item as? ObjectNode)?.get("id")?.asText()
        if (!id.isNullOrBlank()) {
            loadedId = id
            loadedPartitionKey = service.partitionKeyOf(item, partitionKeyPaths)
            deleteButton.isEnabled = true
        } else {
            loadedId = null
            loadedPartitionKey = null
            deleteButton.isEnabled = false
        }
    }

    private fun newDocument() {
        table.clearSelection()
        docEditor.text = "{\n  \"id\": \"\"\n}\n"
        deleteButton.isEnabled = false
        loadedId = null
        loadedPartitionKey = null
        docEditor.requestFocusInWindow()
    }

    private fun saveDocument() {
        val document = try {
            service.parseObject(docEditor.text)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: "Invalid JSON.", "Invalid Document")
            return
        }
        val id = document.get("id")?.asText()
        if (id.isNullOrBlank()) {
            Messages.showErrorDialog(project, "The document must contain a non-empty \"id\" field.", "Invalid Document")
            return
        }
        // id is unique only within a partition: changing the id or partition key would create a
        // NEW document and leave the original behind. Make that explicit when editing a loaded doc.
        val newPartitionKey = service.partitionKeyOf(document, partitionKeyPaths)
        if (loadedId != null && (id != loadedId || newPartitionKey != loadedPartitionKey)) {
            val proceed = Messages.showYesNoDialog(
                project,
                "The id or partition key differs from the loaded document. Saving will create a NEW " +
                    "document in a different partition and leave the original unchanged. Continue?",
                "Id / Partition Key Changed",
                Messages.getWarningIcon(),
            )
            if (proceed != Messages.YES) return
        }
        saveButton.isEnabled = false
        Bg.run(
            project,
            "Saving document…",
            work = { service.upsert(connectionName, databaseId, containerId, document) },
            onSuccess = { ru ->
                saveButton.isEnabled = true
                statusLabel.text = "Saved \"$id\"   •   RU: ${"%.2f".format(ru)}"
                execute(reset = true)
            },
            onError = { error ->
                saveButton.isEnabled = true
                CosmosErrors.notifyError(project, "Save document", error)
            },
        )
    }

    private fun deleteDocument() {
        val viewRow = table.selectedRow
        if (viewRow < 0) return
        val item = tableModel.rowItem(table.convertRowIndexToModel(viewRow)) ?: return
        val id = (item as? ObjectNode)?.get("id")?.asText()
        if (id.isNullOrBlank()) return

        val confirm = Messages.showYesNoDialog(
            project,
            "Delete document \"$id\"? This cannot be undone.",
            "Delete Document",
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) return

        val partitionKey = service.partitionKeyOf(item, partitionKeyPaths)
        deleteButton.isEnabled = false
        Bg.run(
            project,
            "Deleting document…",
            work = { service.delete(connectionName, databaseId, containerId, id, partitionKey) },
            onSuccess = { ru ->
                statusLabel.text = "Deleted \"$id\"   •   RU: ${"%.2f".format(ru)}"
                execute(reset = true)
            },
            onError = { error ->
                deleteButton.isEnabled = true
                CosmosErrors.notifyError(project, "Delete document", error)
            },
        )
    }

    // ---- export ----------------------------------------------------------------

    private fun exportResults() {
        if (loadedItems.isEmpty()) return
        val descriptor = FileSaverDescriptor("Export Query Results", "Save the loaded documents as a JSON array", "json")
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as VirtualFile?, "$containerId.json")
            ?: return
        val file = wrapper.file
        val items = loadedItems.toList()   // snapshot; serialize + write off the EDT
        exportButton.isEnabled = false
        Bg.run(
            project,
            "Exporting ${items.size} document(s)…",
            work = {
                file.writeText(items.joinToString(",\n", prefix = "[\n", postfix = "\n]") { service.prettyPrint(it) })
                items.size
            },
            onSuccess = { count ->
                exportButton.isEnabled = true
                statusLabel.text = "Exported $count document(s) to ${file.name}"
            },
            onError = { error ->
                exportButton.isEnabled = true
                CosmosErrors.notifyError(project, "Export", error)
            },
        )
    }
}
