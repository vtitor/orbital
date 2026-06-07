package com.github.cosmosdbclient.service

import com.azure.cosmos.models.PartitionKey
import com.azure.cosmos.models.PartitionKeyBuilder
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic unit tests for [CosmosService] helpers (no IDE / no network). */
class CosmosServiceLogicTest {

    private val service = CosmosService()
    private val mapper = ObjectMapper()
    private fun obj(json: String) = mapper.readTree(json) as ObjectNode

    @Test fun prettyPrintIsIndentedAndRoundTrips() {
        val node = obj("""{"id":"a","n":1}""")
        val pretty = service.prettyPrint(node)
        assertTrue(pretty.contains("\n"))
        assertEquals(node, mapper.readTree(pretty))
    }

    @Test fun parseObjectAcceptsJsonObject() {
        assertEquals("x", service.parseObject("""{"id":"x"}""").get("id").asText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseObjectRejectsArray() {
        service.parseObject("[1,2,3]")
    }

    @Test fun parseParamsHandlesBlankAndArray() {
        assertEquals(emptyList<Any?>(), service.parseParams("   "))
        assertEquals(listOf<Any?>("a", 1, true), service.parseParams("""["a",1,true]"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseParamsRejectsNonArray() {
        service.parseParams("""{"a":1}""")
    }

    @Test fun partitionKeyOfSingleScalarPaths() {
        assertEquals(PartitionKey("v"), service.partitionKeyOf(obj("""{"pk":"v"}"""), listOf("/pk")))
        assertEquals(PartitionKey(5.0), service.partitionKeyOf(obj("""{"pk":5}"""), listOf("/pk")))
        assertEquals(PartitionKey(true), service.partitionKeyOf(obj("""{"pk":true}"""), listOf("/pk")))
    }

    @Test fun partitionKeyOfNestedPath() {
        assertEquals(PartitionKey("deep"), service.partitionKeyOf(obj("""{"a":{"b":"deep"}}"""), listOf("/a/b")))
    }

    @Test fun partitionKeyOfMissingOrNoPathIsNone() {
        assertEquals(PartitionKey.NONE, service.partitionKeyOf(obj("""{"id":"x"}"""), emptyList()))
        assertEquals(PartitionKey.NONE, service.partitionKeyOf(obj("""{"id":"x"}"""), listOf("/missing")))
    }

    @Test fun partitionKeyOfHierarchical() {
        val expected = PartitionKeyBuilder().add("x").add("y").build()
        val actual = service.partitionKeyOf(obj("""{"a":"x","b":"y"}"""), listOf("/a", "/b"))
        assertEquals(expected, actual)
    }

    @Test fun partitionKeyFromTextInfersType() {
        assertEquals(PartitionKey.NONE, service.partitionKeyFromText(""))
        assertEquals(PartitionKey(true), service.partitionKeyFromText("true"))
        assertEquals(PartitionKey(false), service.partitionKeyFromText("false"))
        assertEquals(PartitionKey(42.0), service.partitionKeyFromText("42"))
        assertEquals(PartitionKey("hello"), service.partitionKeyFromText("hello"))
    }

    @Test fun normalizePathEnsuresLeadingSlash() {
        assertEquals("/pk", service.normalizePath("pk"))
        assertEquals("/pk", service.normalizePath("/pk"))
        assertEquals("/a/b", service.normalizePath("a/b"))
    }
}
