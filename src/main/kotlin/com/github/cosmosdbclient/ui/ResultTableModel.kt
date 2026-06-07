package com.github.cosmosdbclient.ui

import com.fasterxml.jackson.databind.node.ObjectNode
import javax.swing.table.AbstractTableModel

/**
 * Table model over a page of query results. Columns are the union of top-level field names
 * across the rows (id first), so heterogeneous, schemaless documents still render sensibly.
 */
class ResultTableModel : AbstractTableModel() {

    private val rows = mutableListOf<ObjectNode>()
    private var columns = listOf("id")

    fun setRows(items: List<ObjectNode>) {
        rows.clear()
        rows.addAll(items)
        recomputeColumns()
        fireTableStructureChanged()
    }

    fun addRows(items: List<ObjectNode>) {
        rows.addAll(items)
        recomputeColumns()
        fireTableStructureChanged()
    }

    fun clear() {
        rows.clear()
        columns = listOf("id")
        fireTableStructureChanged()
    }

    fun rowItem(index: Int): ObjectNode? = rows.getOrNull(index)

    val size: Int get() = rows.size

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val node = rows[rowIndex].get(columns[columnIndex]) ?: return ""
        return when {
            node.isNull -> "null"
            node.isValueNode -> node.asText()
            node.isArray -> "[ ${node.size()} ]"
            node.isObject -> compact(node.toString())
            else -> compact(node.toString())
        }
    }

    private fun recomputeColumns() {
        val names = LinkedHashSet<String>()
        if (rows.any { it.has("id") }) names.add("id")
        for (row in rows) {
            val fields = row.fieldNames()
            while (fields.hasNext()) {
                names.add(fields.next())
                if (names.size >= MAX_COLUMNS) break
            }
            if (names.size >= MAX_COLUMNS) break
        }
        if (names.isEmpty()) names.add("id")
        columns = names.toList()
    }

    private fun compact(text: String): String =
        if (text.length <= MAX_CELL_LEN) text else text.take(MAX_CELL_LEN) + "…"

    private companion object {
        const val MAX_COLUMNS = 60
        const val MAX_CELL_LEN = 120
    }
}
