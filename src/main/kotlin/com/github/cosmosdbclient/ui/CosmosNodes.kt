package com.github.cosmosdbclient.ui

import com.github.cosmosdbclient.service.ScriptKind
import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** User objects attached to the explorer tree nodes. */
sealed interface NodeData

/** Nodes whose children are fetched lazily on first expand. */
interface Loadable {
    var loaded: Boolean
    var loading: Boolean
}

class ConnectionNode(val connectionName: String) : NodeData, Loadable {
    override var loaded = false
    override var loading = false
    override fun toString() = connectionName
}

class DatabaseNode(val connectionName: String, val databaseId: String) : NodeData, Loadable {
    override var loaded = false
    override var loading = false
    override fun toString() = databaseId
}

class ContainerNode(
    val connectionName: String,
    val databaseId: String,
    val containerId: String,
    val partitionKeyPaths: List<String>,
) : NodeData, Loadable {
    override var loaded = false
    override var loading = false
    override fun toString() = containerId
}

class ScriptsFolderNode(
    val connectionName: String,
    val databaseId: String,
    val containerId: String,
    val kind: ScriptKind,
) : NodeData, Loadable {
    override var loaded = false
    override var loading = false
    override fun toString() = kind.title
}

class ScriptNode(
    val connectionName: String,
    val databaseId: String,
    val containerId: String,
    val kind: ScriptKind,
    val scriptId: String,
) : NodeData {
    override fun toString() = scriptId
}

/** Placeholder / status / error rows. */
class MessageNode(val text: String, val error: Boolean = false) : NodeData {
    override fun toString() = text
}

class CosmosTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val data = node.userObject) {
            is ConnectionNode -> {
                icon = AllIcons.Webreferences.Server
                append(data.connectionName)
            }
            is DatabaseNode -> {
                icon = AllIcons.Nodes.DataTables
                append(data.databaseId)
            }
            is ContainerNode -> {
                icon = AllIcons.Nodes.DataColumn
                append(data.containerId)
                if (data.partitionKeyPaths.isNotEmpty()) {
                    append("   ${data.partitionKeyPaths.joinToString()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            is ScriptsFolderNode -> {
                icon = AllIcons.Nodes.Folder
                append(data.kind.title)
            }
            is ScriptNode -> {
                icon = AllIcons.FileTypes.JavaScript
                append(data.scriptId)
            }
            is MessageNode -> {
                icon = if (data.error) AllIcons.General.Error else null
                append(
                    data.text,
                    if (data.error) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
            }
            else -> append(node.userObject?.toString().orEmpty())
        }
    }
}
