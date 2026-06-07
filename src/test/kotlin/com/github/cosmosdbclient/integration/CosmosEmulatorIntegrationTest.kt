package com.github.cosmosdbclient.integration

import com.azure.cosmos.CosmosClient
import com.azure.cosmos.CosmosClientBuilder
import com.azure.cosmos.CosmosException
import com.azure.cosmos.models.PartitionKey
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.cosmosdbclient.service.CosmosService
import com.github.cosmosdbclient.service.ThroughputMode
import com.github.cosmosdbclient.service.ThroughputSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Live integration test exercising the full [CosmosService] surface against a real account or
 * the Azure Cosmos DB Emulator. Skipped unless COSMOS_TEST_ENDPOINT and COSMOS_TEST_KEY are set,
 * so it never fails the normal suite without credentials.
 *
 * Against the (HTTP, arm64-capable) vNext emulator:
 *
 *   docker run -d -p 8081:8081 -p 1234:1234 \
 *     mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:vnext-preview
 *
 *   COSMOS_TEST_ENDPOINT=http://localhost:8081 \
 *   COSMOS_TEST_KEY='C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==' \
 *   ./gradlew test --tests '*CosmosEmulatorIntegrationTest'
 */
class CosmosEmulatorIntegrationTest {

    private val endpoint: String? = System.getenv("COSMOS_TEST_ENDPOINT")
    private val key: String? = System.getenv("COSMOS_TEST_KEY")
    private val mapper = ObjectMapper()

    private val connection = "integration"
    private val databaseId = "it-db-${System.currentTimeMillis()}"
    private val containerId = "it-container"

    private lateinit var service: CosmosService
    private var client: CosmosClient? = null

    @Before
    fun setUp() {
        Assume.assumeTrue(
            "Set COSMOS_TEST_ENDPOINT and COSMOS_TEST_KEY to run the live integration test.",
            !endpoint.isNullOrBlank() && !key.isNullOrBlank(),
        )
        service = CosmosService()
        // Same configuration the plugin uses (gateway mode, default discovery), so this also
        // validates the plugin's real client against the emulator.
        client = CosmosClientBuilder()
            .endpoint(endpoint!!)
            .key(key!!)
            .gatewayMode()
            .contentResponseOnWriteEnabled(true)
            .buildClient()
        service.useClient(connection, client!!)
    }

    @After
    fun tearDown() {
        if (this::service.isInitialized) runCatching { service.deleteDatabase(connection, databaseId) }
        client?.let { runCatching { it.close() } }
    }

    @Test
    fun testConnectionReportsDatabaseCount() {
        assertTrue(service.testConnection(endpoint!!, key!!, gateway = true) >= 0)
    }

    @Test
    fun fullDatabaseContainerAndDocumentLifecycle() {
        // database
        service.createDatabase(connection, databaseId, ThroughputSpec(ThroughputMode.NONE))
        assertTrue("database should be listed", service.listDatabases(connection).contains(databaseId))

        // container
        service.createContainer(connection, databaseId, containerId, "/pk", ThroughputSpec(ThroughputMode.MANUAL, 400))
        assertTrue("container should be listed", service.listContainers(connection, databaseId).any { it.id == containerId })
        assertEquals(listOf("/pk"), service.containerDetails(connection, databaseId, containerId).partitionKeyPaths)

        // create + query + read
        assertTrue(service.upsert(connection, databaseId, containerId, doc("""{"id":"1","pk":"p","v":42}""")) > 0.0)

        val page = service.query(connection, databaseId, containerId, "SELECT * FROM c", 10, null)
        assertEquals(1, page.items.size)
        assertEquals("1", page.items[0].get("id").asText())
        assertTrue(page.requestCharge > 0.0)

        assertEquals(42, service.readItem(connection, databaseId, containerId, "1", PartitionKey("p")).get("v").asInt())

        // update via upsert
        service.upsert(connection, databaseId, containerId, doc("""{"id":"1","pk":"p","v":99}"""))
        assertEquals(99, service.readItem(connection, databaseId, containerId, "1", PartitionKey("p")).get("v").asInt())

        // delete
        assertTrue(service.delete(connection, databaseId, containerId, "1", PartitionKey("p")) > 0.0)
        assertEquals(0, service.query(connection, databaseId, containerId, "SELECT * FROM c", 10, null).items.size)

        // drop container
        service.deleteContainer(connection, databaseId, containerId)
        assertFalse(service.listContainers(connection, databaseId).any { it.id == containerId })
    }

    @Test
    fun storedProcedureLifecycle() {
        service.createDatabase(connection, databaseId, ThroughputSpec(ThroughputMode.NONE))
        service.createContainer(connection, databaseId, containerId, "/pk", ThroughputSpec(ThroughputMode.MANUAL, 400))

        val body = "function () { getContext().getResponse().setBody('ok'); }"
        try {
            service.saveStoredProcedure(connection, databaseId, containerId, "sp1", body, isNew = true)
        } catch (e: CosmosException) {
            // The vNext (pgcosmos) emulator does not implement server-side scripts; skip there.
            Assume.assumeFalse(
                "Server-side scripts are not supported by this backend (e.g. the vNext emulator) — skipping.",
                e.statusCode == 400 && e.message?.contains("not supported", ignoreCase = true) == true,
            )
            throw e
        }
        assertTrue(service.listStoredProcedures(connection, databaseId, containerId).contains("sp1"))

        val result = service.executeStoredProcedure(connection, databaseId, containerId, "sp1", PartitionKey("p"), emptyList())
        assertTrue("SP response should contain 'ok' but was: $result", result.contains("ok"))

        service.deleteStoredProcedure(connection, databaseId, containerId, "sp1")
        assertFalse(service.listStoredProcedures(connection, databaseId, containerId).contains("sp1"))
    }

    private fun doc(json: String): ObjectNode = mapper.readTree(json) as ObjectNode
}
