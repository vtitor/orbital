package com.github.cosmosdbclient.ui

import com.azure.cosmos.models.TriggerOperation
import com.azure.cosmos.models.TriggerType
import com.github.cosmosdbclient.model.CosmosConnection
import com.github.cosmosdbclient.service.CosmosErrors
import com.github.cosmosdbclient.service.CosmosService
import com.github.cosmosdbclient.service.ScriptKind
import com.github.cosmosdbclient.util.Bg
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingConstants
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Tool window content: a lazy-loading account → database → container → scripts tree on the
 * left, and closable per-container query tabs on the right.
 */
class CosmosExplorerPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val service = CosmosService.getInstance()
    private val rootNode = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)

    private val tabs = JBTabbedPane()
    private val openTabs = mutableMapOf<String, QueryPanel>()
    private val rightCards = JPanel(CardLayout())

    init {
        setupTree()
        toolbar = createToolbar()

        rightCards.add(welcomePanel(), CARD_EMPTY)
        rightCards.add(tabs, CARD_TABS)

        val splitter = OnePixelSplitter(false, 0.32f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = rightCards
        }
        setContent(splitter)
        reloadConnections()
        updateRightCard()
    }

    // ---- toolbar ---------------------------------------------------------------

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(action("Add Connection", "Add a Cosmos DB account connection", AllIcons.General.Add) { addConnection() })
            add(action("Refresh", "Reload the selected node", AllIcons.Actions.Refresh) { refreshSelected() })
            addSeparator()
            add(action("Collapse All", "Collapse the whole tree", AllIcons.Actions.Collapseall) { collapseAll() })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("CosmosExplorerToolbar", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun action(text: String, description: String, icon: javax.swing.Icon, handler: () -> Unit): AnAction =
        object : AnAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = handler()
        }

    private fun welcomePanel(): JComponent = JPanel(BorderLayout()).apply {
        add(
            JBLabel(
                "<html><center>No container open.<br/><br/>" +
                    "Add a connection with <b>+</b>, expand the tree,<br/>and double-click a container to query it.</center></html>",
                SwingConstants.CENTER,
            ).apply { foreground = JBUI.CurrentTheme.Label.disabledForeground() },
            BorderLayout.CENTER,
        )
    }

    // ---- tree ------------------------------------------------------------------

    private fun setupTree() {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = CosmosTreeCellRenderer()

        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                when (val data = node.userObject) {
                    is ConnectionNode -> if (!data.loaded && !data.loading) loadDatabases(node)
                    is DatabaseNode -> if (!data.loaded && !data.loading) loadContainers(node)
                    is ContainerNode -> if (!data.loaded && !data.loading) loadContainerChildren(node)
                    is ScriptsFolderNode -> if (!data.loaded && !data.loading) loadScripts(node)
                }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) {}
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    when (val data = selectedData()) {
                        is ContainerNode -> openQueryTab(data)
                        is ScriptNode -> openScript(data)
                        else -> {}
                    }
                }
            }
        })

        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                tree.getClosestPathForLocation(x, y)?.let { tree.selectionPath = it }
                buildContextMenu()?.show(comp, x, y)
            }
        })
    }

    private fun buildContextMenu(): JPopupMenu? {
        val node = selectedNode() ?: return null
        val menu = JPopupMenu()
        when (val data = node.userObject) {
            is ConnectionNode -> {
                menu.item("New Database…") { newDatabase(node, data) }
                menu.item("Edit Connection…") { editConnection(data) }
                menu.item("Remove Connection") { removeConnection(data) }
                menu.item("Refresh") { reload(node) }
            }
            is DatabaseNode -> {
                menu.item("New Container…") { newContainer(node, data) }
                menu.item("Delete Database") { deleteDatabase(node, data) }
                menu.item("Refresh") { reload(node) }
            }
            is ContainerNode -> {
                menu.item("Open Query") { openQueryTab(data) }
                menu.item("Properties…") { showContainerProperties(data) }
                menu.item("Delete Container") { deleteContainer(node, data) }
                menu.item("Refresh") { reload(node) }
            }
            is ScriptsFolderNode -> {
                menu.item("New ${data.kind.title.trimEnd('s')}…") { newScript(node, data) }
                menu.item("Refresh") { reload(node) }
            }
            is ScriptNode -> {
                menu.item(if (data.kind == ScriptKind.STORED_PROCEDURE) "Edit / View" else "Edit / View") { openScript(data) }
                if (data.kind == ScriptKind.STORED_PROCEDURE) {
                    menu.item("Execute…") { executeStoredProcedure(data) }
                }
                menu.item("Delete") { deleteScript(node, data) }
            }
            else -> return null
        }
        return menu
    }

    private fun JPopupMenu.item(text: String, handler: () -> Unit) {
        add(JMenuItem(text).apply { addActionListener { handler() } })
    }

    private fun selectedNode(): DefaultMutableTreeNode? = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
    private fun selectedData(): NodeData? = selectedNode()?.userObject as? NodeData

    // ---- loading ---------------------------------------------------------------

    fun reloadConnections() {
        rootNode.removeAllChildren()
        val connections = service.connections()
        if (connections.isEmpty()) {
            rootNode.add(DefaultMutableTreeNode(MessageNode("No connections yet — click + to add one.")))
        } else {
            for (connection in connections) {
                val node = DefaultMutableTreeNode(ConnectionNode(connection.name))
                node.add(DefaultMutableTreeNode(MessageNode("Loading…")))
                rootNode.add(node)
            }
        }
        treeModel.nodeStructureChanged(rootNode)
    }

    private fun loadDatabases(node: DefaultMutableTreeNode) {
        val data = node.userObject as ConnectionNode
        data.loading = true
        Bg.run(
            project,
            "Loading databases…",
            work = { service.listDatabases(data.connectionName) },
            onSuccess = { databases ->
                node.removeAllChildren()
                if (databases.isEmpty()) {
                    node.add(DefaultMutableTreeNode(MessageNode("(no databases)")))
                } else {
                    databases.forEach { db ->
                        val child = DefaultMutableTreeNode(DatabaseNode(data.connectionName, db))
                        child.add(DefaultMutableTreeNode(MessageNode("Loading…")))
                        node.add(child)
                    }
                }
                data.loaded = true
                data.loading = false
                refreshSubtree(node)
            },
            onError = { error -> showLoadError(node, error); data.loading = false },
        )
    }

    private fun loadContainers(node: DefaultMutableTreeNode) {
        val data = node.userObject as DatabaseNode
        data.loading = true
        Bg.run(
            project,
            "Loading containers…",
            work = { service.listContainers(data.connectionName, data.databaseId) },
            onSuccess = { containers ->
                node.removeAllChildren()
                if (containers.isEmpty()) {
                    node.add(DefaultMutableTreeNode(MessageNode("(no containers)")))
                } else {
                    containers.forEach { container ->
                        val child = DefaultMutableTreeNode(
                            ContainerNode(data.connectionName, data.databaseId, container.id, container.partitionKeyPaths),
                        )
                        child.add(DefaultMutableTreeNode(MessageNode("Loading…")))
                        node.add(child)
                    }
                }
                data.loaded = true
                data.loading = false
                refreshSubtree(node)
            },
            onError = { error -> showLoadError(node, error); data.loading = false },
        )
    }

    /** Container children (the three script folders) are static — no network call needed. */
    private fun loadContainerChildren(node: DefaultMutableTreeNode) {
        val data = node.userObject as ContainerNode
        node.removeAllChildren()
        ScriptKind.values().forEach { kind ->
            val folder = DefaultMutableTreeNode(
                ScriptsFolderNode(data.connectionName, data.databaseId, data.containerId, kind),
            )
            folder.add(DefaultMutableTreeNode(MessageNode("Loading…")))
            node.add(folder)
        }
        data.loaded = true
        data.loading = false
        refreshSubtree(node)
    }

    private fun loadScripts(node: DefaultMutableTreeNode) {
        val data = node.userObject as ScriptsFolderNode
        data.loading = true
        Bg.run(
            project,
            "Loading ${data.kind.title}…",
            work = {
                when (data.kind) {
                    ScriptKind.STORED_PROCEDURE -> service.listStoredProcedures(data.connectionName, data.databaseId, data.containerId)
                    ScriptKind.TRIGGER -> service.listTriggers(data.connectionName, data.databaseId, data.containerId)
                    ScriptKind.UDF -> service.listUdfs(data.connectionName, data.databaseId, data.containerId)
                }
            },
            onSuccess = { ids ->
                node.removeAllChildren()
                if (ids.isEmpty()) {
                    node.add(DefaultMutableTreeNode(MessageNode("(none)")))
                } else {
                    ids.forEach { id ->
                        node.add(
                            DefaultMutableTreeNode(
                                ScriptNode(data.connectionName, data.databaseId, data.containerId, data.kind, id),
                            ),
                        )
                    }
                }
                data.loaded = true
                data.loading = false
                refreshSubtree(node)
            },
            onError = { error -> showLoadError(node, error); data.loading = false },
        )
    }

    private fun showLoadError(node: DefaultMutableTreeNode, error: Throwable) {
        node.removeAllChildren()
        node.add(DefaultMutableTreeNode(MessageNode(CosmosErrors.shortMessage(error), error = true)))
        treeModel.nodeStructureChanged(node)
    }

    private fun refreshSubtree(node: DefaultMutableTreeNode) {
        treeModel.nodeStructureChanged(node)
        tree.expandPath(TreePath(node.path))
    }

    /** Resets a node to a single placeholder and reloads its children. */
    private fun reload(node: DefaultMutableTreeNode) {
        when (val data = node.userObject) {
            is ConnectionNode -> {
                service.invalidate(data.connectionName)
                data.loaded = false; data.loading = false
                resetPlaceholder(node)
                loadDatabases(node)
            }
            is DatabaseNode -> { data.loaded = false; data.loading = false; resetPlaceholder(node); loadContainers(node) }
            is ContainerNode -> { data.loaded = false; data.loading = false; loadContainerChildren(node) }
            is ScriptsFolderNode -> { data.loaded = false; data.loading = false; resetPlaceholder(node); loadScripts(node) }
            else -> reloadConnections()
        }
    }

    private fun resetPlaceholder(node: DefaultMutableTreeNode) {
        node.removeAllChildren()
        node.add(DefaultMutableTreeNode(MessageNode("Loading…")))
        treeModel.nodeStructureChanged(node)
    }

    private fun refreshSelected() {
        val node = selectedNode()
        if (node == null || node.userObject !is NodeData || node.userObject is MessageNode) {
            reloadConnections()
        } else {
            reload(node)
        }
    }

    private fun collapseAll() {
        var row = tree.rowCount - 1
        while (row >= 0) {
            tree.collapseRow(row)
            row--
        }
    }

    private fun parentNode(node: DefaultMutableTreeNode): DefaultMutableTreeNode? =
        node.parent as? DefaultMutableTreeNode

    // ---- connection actions ----------------------------------------------------

    private fun addConnection() {
        val dialog = AddConnectionDialog(project, null)
        if (!dialog.showAndGet()) return
        val input = dialog.result()
        service.saveConnection(CosmosConnection(input.name, input.endpoint, input.preferGateway), input.key)
        reloadConnections()
    }

    private fun editConnection(data: ConnectionNode) {
        val connection = service.connections().firstOrNull { it.name == data.connectionName } ?: return
        val dialog = AddConnectionDialog(project, connection)
        if (!dialog.showAndGet()) return
        val input = dialog.result()
        service.saveConnection(CosmosConnection(input.name, input.endpoint, input.preferGateway), input.key)
        reloadConnections()
    }

    private fun removeConnection(data: ConnectionNode) {
        val confirm = Messages.showYesNoDialog(
            project,
            "Remove connection \"${data.connectionName}\"?\n(The Cosmos DB account itself is not affected.)",
            "Remove Connection",
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) return
        service.removeConnection(data.connectionName)
        reloadConnections()
    }

    // ---- database / container actions ------------------------------------------

    private fun newDatabase(node: DefaultMutableTreeNode, data: ConnectionNode) {
        val dialog = CreateDatabaseDialog(project)
        if (!dialog.showAndGet()) return
        Bg.run(
            project,
            "Creating database…",
            work = { service.createDatabase(data.connectionName, dialog.databaseId(), dialog.throughputSpec()) },
            onSuccess = { CosmosErrors.notifyInfo(project, "Database \"${dialog.databaseId()}\" created"); reload(node) },
        )
    }

    private fun deleteDatabase(node: DefaultMutableTreeNode, data: DatabaseNode) {
        val confirm = Messages.showYesNoDialog(
            project,
            "Delete database \"${data.databaseId}\" and ALL of its containers? This cannot be undone.",
            "Delete Database",
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) return
        Bg.run(
            project,
            "Deleting database…",
            work = { service.deleteDatabase(data.connectionName, data.databaseId) },
            onSuccess = { parentNode(node)?.let { reload(it) } },
        )
    }

    private fun newContainer(node: DefaultMutableTreeNode, data: DatabaseNode) {
        val dialog = CreateContainerDialog(project)
        if (!dialog.showAndGet()) return
        Bg.run(
            project,
            "Creating container…",
            work = {
                service.createContainer(
                    data.connectionName, data.databaseId, dialog.containerId(), dialog.partitionKeyPath(), dialog.throughputSpec(),
                )
            },
            onSuccess = { CosmosErrors.notifyInfo(project, "Container \"${dialog.containerId()}\" created"); reload(node) },
        )
    }

    private fun deleteContainer(node: DefaultMutableTreeNode, data: ContainerNode) {
        val confirm = Messages.showYesNoDialog(
            project,
            "Delete container \"${data.containerId}\" and ALL of its documents? This cannot be undone.",
            "Delete Container",
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) return
        Bg.run(
            project,
            "Deleting container…",
            work = { service.deleteContainer(data.connectionName, data.databaseId, data.containerId) },
            onSuccess = { parentNode(node)?.let { reload(it) } },
        )
    }

    private fun showContainerProperties(data: ContainerNode) {
        Bg.run(
            project,
            "Reading container properties…",
            work = { service.containerDetails(data.connectionName, data.databaseId, data.containerId) },
            onSuccess = { details -> ContainerPropertiesDialog(project, details).show() },
        )
    }

    // ---- query tabs ------------------------------------------------------------

    private fun openQueryTab(data: ContainerNode) {
        val key = listOf(data.connectionName, data.databaseId, data.containerId).joinToString(" ")
        openTabs[key]?.let { existing ->
            tabs.selectedComponent = existing
            return
        }
        val panel = QueryPanel(project, data.connectionName, data.databaseId, data.containerId, data.partitionKeyPaths)
        openTabs[key] = panel
        addClosableTab("${data.databaseId}/${data.containerId}", panel) {
            tabs.remove(panel)
            openTabs.remove(key)
            updateRightCard()
        }
        tabs.selectedComponent = panel
        updateRightCard()
    }

    private fun addClosableTab(title: String, component: JComponent, onClose: () -> Unit) {
        tabs.addTab(title, component)
        val index = tabs.indexOfComponent(component)
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        header.add(JBLabel(title))
        val close = JBLabel(AllIcons.Actions.Close).apply {
            toolTipText = "Close"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClose()
            })
        }
        header.add(close)
        tabs.setTabComponentAt(index, header)
    }

    private fun updateRightCard() {
        (rightCards.layout as CardLayout).show(rightCards, if (tabs.tabCount == 0) CARD_EMPTY else CARD_TABS)
    }

    // ---- script actions --------------------------------------------------------

    private fun newScript(folderNode: DefaultMutableTreeNode, data: ScriptsFolderNode) {
        val dialog = ScriptEditorDialog(project, data.kind, existingId = null, initialBody = null, triggerType = null, triggerOperation = null)
        if (!dialog.showAndGet()) return
        saveScript(data, dialog.result(), isNew = true) { reload(folderNode) }
    }

    private fun openScript(data: ScriptNode) {
        Bg.run(
            project,
            "Loading ${data.scriptId}…",
            work = {
                when (data.kind) {
                    ScriptKind.STORED_PROCEDURE -> {
                        val s = service.readStoredProcedure(data.connectionName, data.databaseId, data.containerId, data.scriptId)
                        Triple(s.body, null as TriggerType?, null as TriggerOperation?)
                    }
                    ScriptKind.UDF -> {
                        val s = service.readUdf(data.connectionName, data.databaseId, data.containerId, data.scriptId)
                        Triple(s.body, null as TriggerType?, null as TriggerOperation?)
                    }
                    ScriptKind.TRIGGER -> {
                        val t = service.readTrigger(data.connectionName, data.databaseId, data.containerId, data.scriptId)
                        Triple(t.body, TriggerType.valueOf(t.type), TriggerOperation.valueOf(t.operation))
                    }
                }
            },
            onSuccess = { (body, type, op) ->
                val dialog = ScriptEditorDialog(project, data.kind, data.scriptId, body, type, op)
                if (dialog.showAndGet()) {
                    val folder = selectedNode()?.let { parentNode(it) }
                    saveScript(asFolder(data), dialog.result(), isNew = false) { folder?.let { reload(it) } }
                }
            },
        )
    }

    private fun saveScript(
        folder: ScriptsFolderNode,
        result: ScriptEditResult,
        isNew: Boolean,
        onDone: () -> Unit,
    ) {
        Bg.run(
            project,
            "Saving ${result.id}…",
            work = {
                when (folder.kind) {
                    ScriptKind.STORED_PROCEDURE ->
                        service.saveStoredProcedure(folder.connectionName, folder.databaseId, folder.containerId, result.id, result.body, isNew)
                    ScriptKind.UDF ->
                        service.saveUdf(folder.connectionName, folder.databaseId, folder.containerId, result.id, result.body, isNew)
                    ScriptKind.TRIGGER ->
                        service.saveTrigger(
                            folder.connectionName, folder.databaseId, folder.containerId, result.id, result.body,
                            result.triggerType ?: TriggerType.PRE, result.triggerOperation ?: TriggerOperation.ALL, isNew,
                        )
                }
            },
            onSuccess = { CosmosErrors.notifyInfo(project, "Saved \"${result.id}\""); onDone() },
        )
    }

    private fun deleteScript(node: DefaultMutableTreeNode, data: ScriptNode) {
        val confirm = Messages.showYesNoDialog(
            project,
            "Delete ${data.kind.title.trimEnd('s')} \"${data.scriptId}\"?",
            "Delete",
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) return
        Bg.run(
            project,
            "Deleting ${data.scriptId}…",
            work = {
                when (data.kind) {
                    ScriptKind.STORED_PROCEDURE -> service.deleteStoredProcedure(data.connectionName, data.databaseId, data.containerId, data.scriptId)
                    ScriptKind.TRIGGER -> service.deleteTrigger(data.connectionName, data.databaseId, data.containerId, data.scriptId)
                    ScriptKind.UDF -> service.deleteUdf(data.connectionName, data.databaseId, data.containerId, data.scriptId)
                }
            },
            onSuccess = { parentNode(node)?.let { reload(it) } },
        )
    }

    private fun executeStoredProcedure(data: ScriptNode) {
        val dialog = ExecuteSprocDialog(project)
        if (!dialog.showAndGet()) return
        val params = try {
            service.parseParams(dialog.paramsText())
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: "Invalid parameters.", "Invalid Parameters")
            return
        }
        val partitionKey = service.partitionKeyFromText(dialog.partitionKeyText())
        Bg.run(
            project,
            "Executing ${data.scriptId}…",
            work = { service.executeStoredProcedure(data.connectionName, data.databaseId, data.containerId, data.scriptId, partitionKey, params) },
            onSuccess = { response ->
                Messages.showMessageDialog(
                    project,
                    response.ifBlank { "(empty response)" },
                    "Stored Procedure Result — ${data.scriptId}",
                    AllIcons.Actions.Execute,
                )
            },
        )
    }

    private fun asFolder(script: ScriptNode): ScriptsFolderNode =
        ScriptsFolderNode(script.connectionName, script.databaseId, script.containerId, script.kind)

    private companion object {
        const val CARD_EMPTY = "empty"
        const val CARD_TABS = "tabs"
    }
}
