package com.github.cosmosdbclient.ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultTableModelTest {

    private val mapper = ObjectMapper()
    private fun obj(json: String) = mapper.readTree(json) as ObjectNode
    private fun columnIndex(model: ResultTableModel, name: String) =
        (0 until model.columnCount).first { model.getColumnName(it) == name }

    @Test fun emptyModelHasIdColumnOnly() {
        val model = ResultTableModel()
        assertEquals(1, model.columnCount)
        assertEquals("id", model.getColumnName(0))
        assertEquals(0, model.rowCount)
    }

    @Test fun columnsAreIdFirstThenUnionOfFields() {
        val model = ResultTableModel()
        model.setRows(listOf(obj("""{"id":"1","name":"a"}"""), obj("""{"id":"2","age":5}""")))
        val columns = (0 until model.columnCount).map { model.getColumnName(it) }
        assertEquals("id", columns.first())
        assertTrue(columns.containsAll(listOf("id", "name", "age")))
        assertEquals(2, model.rowCount)
    }

    @Test fun rendersScalarsArraysObjectsAndNulls() {
        val model = ResultTableModel()
        model.setRows(listOf(obj("""{"id":"1","n":5,"b":true,"obj":{"k":1},"arr":[1,2,3],"z":null}""")))
        assertEquals("1", model.getValueAt(0, columnIndex(model, "id")))
        assertEquals("5", model.getValueAt(0, columnIndex(model, "n")))
        assertEquals("true", model.getValueAt(0, columnIndex(model, "b")))
        assertEquals("[ 3 ]", model.getValueAt(0, columnIndex(model, "arr")))
        assertEquals("null", model.getValueAt(0, columnIndex(model, "z")))
        assertTrue((model.getValueAt(0, columnIndex(model, "obj")) as String).contains("k"))
    }

    @Test fun missingFieldRendersAsEmptyString() {
        val model = ResultTableModel()
        model.setRows(listOf(obj("""{"id":"1","name":"a"}"""), obj("""{"id":"2"}""")))
        assertEquals("", model.getValueAt(1, columnIndex(model, "name")))
    }

    @Test fun addRowsAppendsAndRowItemReturnsSource() {
        val model = ResultTableModel()
        model.setRows(listOf(obj("""{"id":"1"}""")))
        model.addRows(listOf(obj("""{"id":"2"}""")))
        assertEquals(2, model.rowCount)
        assertEquals("2", model.rowItem(1)!!.get("id").asText())
    }

    @Test fun clearResetsRowsAndColumns() {
        val model = ResultTableModel()
        model.setRows(listOf(obj("""{"id":"1","x":2}""")))
        model.clear()
        assertEquals(0, model.rowCount)
        assertEquals(1, model.columnCount)
        assertEquals("id", model.getColumnName(0))
    }

    @Test fun scalarAndArrayResultsUseSingleValueColumn() {
        val model = ResultTableModel()
        // e.g. results of `SELECT VALUE c.id` (scalars) or a projected array
        model.setRows(listOf(mapper.readTree("\"abc\""), mapper.readTree("42"), mapper.readTree("[1,2,3]")))
        assertEquals(1, model.columnCount)
        assertEquals("value", model.getColumnName(0))
        assertEquals("abc", model.getValueAt(0, 0))
        assertEquals("42", model.getValueAt(1, 0))
        assertEquals("[ 3 ]", model.getValueAt(2, 0))
    }
}
