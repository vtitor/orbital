package com.github.cosmosdbclient.ui

import com.fasterxml.jackson.databind.JsonNode
import javax.swing.table.AbstractTableModel

/**
 * Table model over a page of query results. When every row is a JSON object, columns are the
 * union of top-level field names (id first). If any row is a scalar or array (e.g. from
 * `SELECT VALUE ...`), the whole result set is shown in a single "value" column.
 */
class ResultTableModel : AbstractTableModel() {

    private val rows = mutableListOf<JsonNode>()
    private var columns = listOf("id")
    private var valueMode = false

    fun setRows(items: List<JsonNode>) {
        rows.clear()
        rows.addAll(items)
        recomputeColumns()
        fireTableStructureChanged()
    }

    fun addRows(items: List<JsonNode>) {
        rows.addAll(items)
        recomputeColumns()
        fireTableStructureChanged()
    }

    fun clear() {
        rows.clear()
        columns = listOf("id")
        valueMode = false
        fireTableStructureChanged()
    }

    fun rowItem(index: Int): JsonNode? = rows.getOrNull(index)

    val size: Int get() = rows.size

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        if (valueMode) return render(row)
        val node = row.get(columns[columnIndex]) ?: return ""
        return render(node)
    }

    private fun render(node: JsonNode?): String = when {
        node == null -> ""
        node.isNull -> "null"
        node.isValueNode -> node.asText()
        node.isArray -> "[ ${node.size()} ]"
        else -> compact(node.toString())
    }

    private fun recomputeColumns() {
        valueMode = rows.any { !it.isObject }
        if (valueMode) {
            columns = listOf("value")
            return
        }
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
