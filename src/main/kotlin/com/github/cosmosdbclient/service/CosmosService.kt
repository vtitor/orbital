package com.github.cosmosdbclient.service

import com.azure.cosmos.CosmosClient
import com.azure.cosmos.CosmosClientBuilder
import com.azure.cosmos.models.CosmosContainerProperties
import com.azure.cosmos.models.CosmosItemRequestOptions
import com.azure.cosmos.models.CosmosQueryRequestOptions
import com.azure.cosmos.models.CosmosStoredProcedureProperties
import com.azure.cosmos.models.CosmosStoredProcedureRequestOptions
import com.azure.cosmos.models.CosmosTriggerProperties
import com.azure.cosmos.models.CosmosUserDefinedFunctionProperties
import com.azure.cosmos.models.PartitionKey
import com.azure.cosmos.models.PartitionKeyBuilder
import com.azure.cosmos.models.ThroughputProperties
import com.azure.cosmos.models.TriggerOperation
import com.azure.cosmos.models.TriggerType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.cosmosdbclient.model.CosmosConnection
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the Azure Cosmos SDK clients (one per connection, cached) and exposes the blocking
 * operations used by the UI. Every public method is meant to be called from a background
 * thread (see [com.github.cosmosdbclient.util.Bg]); exceptions propagate to the caller.
 */
@Service(Service.Level.APP)
class CosmosService : Disposable {

    private val clients = ConcurrentHashMap<String, CosmosClient>()
    private val mapper = ObjectMapper()
    private val storage get() = CosmosConnectionStorage.getInstance()

    // ---- connection management -------------------------------------------------

    fun connections(): List<CosmosConnection> = storage.list()

    fun saveConnection(connection: CosmosConnection, key: String?) {
        storage.save(connection, key)
        invalidate(connection.name)
    }

    fun removeConnection(name: String) {
        invalidate(name)
        storage.remove(name)
    }

    fun invalidate(name: String) {
        clients.remove(name)?.let { runCatching { it.close() } }
    }

    /**
     * Test seam: inject a pre-built client so integration tests can exercise the real
     * operations without a stored connection (and without PasswordSafe / the IDE).
     */
    @TestOnly
    internal fun useClient(name: String, client: CosmosClient) {
        clients[name] = client
    }

    /** Builds a throwaway client and counts databases. Returns the count, throws on failure. */
    fun testConnection(endpoint: String, key: String, gateway: Boolean): Int = withPluginClassLoader {
        val builder = CosmosClientBuilder().endpoint(endpoint).key(key)
        if (gateway) builder.gatewayMode()
        builder.buildClient().use { client ->
            var count = 0
            client.readAllDatabases().forEach { _ -> count++ }
            count
        }
    }

    // ---- databases & containers (DDL) -----------------------------------------

    fun listDatabases(connectionName: String): List<String> =
        withClient(connectionName) { client -> client.readAllDatabases().map { it.id }.sorted() }

    fun createDatabase(connectionName: String, id: String, throughput: ThroughputSpec) =
        withClient(connectionName) { client ->
            when (throughput.mode) {
                ThroughputMode.NONE -> client.createDatabase(id)
                ThroughputMode.MANUAL -> client.createDatabase(id, ThroughputProperties.createManualThroughput(throughput.value))
                ThroughputMode.AUTOSCALE -> client.createDatabase(id, ThroughputProperties.createAutoscaledThroughput(throughput.value))
            }
            Unit
        }

    fun deleteDatabase(connectionName: String, databaseId: String) =
        withClient(connectionName) { client -> client.getDatabase(databaseId).delete(); Unit }

    fun listContainers(connectionName: String, databaseId: String): List<ContainerInfo> =
        withClient(connectionName) { client ->
            client.getDatabase(databaseId).readAllContainers().map { props ->
                ContainerInfo(props.id, props.partitionKeyDefinition?.paths?.toList() ?: emptyList())
            }.sortedBy { it.id }
        }

    fun createContainer(connectionName: String, databaseId: String, id: String, partitionKeyPath: String, throughput: ThroughputSpec) =
        withClient(connectionName) { client ->
            val database = client.getDatabase(databaseId)
            val props = CosmosContainerProperties(id, normalizePath(partitionKeyPath))
            when (throughput.mode) {
                ThroughputMode.NONE -> database.createContainer(props)
                ThroughputMode.MANUAL -> database.createContainer(props, ThroughputProperties.createManualThroughput(throughput.value))
                ThroughputMode.AUTOSCALE -> database.createContainer(props, ThroughputProperties.createAutoscaledThroughput(throughput.value))
            }
            Unit
        }

    fun deleteContainer(connectionName: String, databaseId: String, containerId: String) =
        withClient(connectionName) { client ->
            client.getDatabase(databaseId).getContainer(containerId).delete(); Unit
        }

    fun containerDetails(connectionName: String, databaseId: String, containerId: String): ContainerDetails =
        withClient(connectionName) { client ->
            val container = client.getDatabase(databaseId).getContainer(containerId)
            val props = container.read().properties
            val pk = props.partitionKeyDefinition?.paths?.toList() ?: emptyList()
            val indexing = props.indexingPolicy?.indexingMode?.toString() ?: "n/a"
            val throughput = runCatching {
                val tp = container.readThroughput().properties
                val manual = runCatching { tp.manualThroughput }.getOrNull()?.takeIf { it > 0 }
                val auto = runCatching { tp.autoscaleMaxThroughput }.getOrNull()?.takeIf { it > 0 }
                ThroughputInfo(manual, auto)
            }.getOrNull()
            ContainerDetails(props.id, pk, props.defaultTimeToLiveInSeconds, indexing, throughput)
        }

    // ---- items -----------------------------------------------------------------

    fun query(
        connectionName: String,
        databaseId: String,
        containerId: String,
        sql: String,
        pageSize: Int,
        continuationToken: String?,
    ): QueryResult = withClient(connectionName) { client ->
        val container = client.getDatabase(databaseId).getContainer(containerId)
        val pagedItems = container.queryItems(sql, CosmosQueryRequestOptions(), JsonNode::class.java)
        val start = System.nanoTime()
        val pages = if (continuationToken.isNullOrBlank()) {
            pagedItems.iterableByPage(pageSize)
        } else {
            pagedItems.iterableByPage(continuationToken, pageSize)
        }
        val iterator = pages.iterator()
        val elapsed = { (System.nanoTime() - start) / 1_000_000 }
        if (iterator.hasNext()) {
            val response = iterator.next()
            QueryResult(response.results.toList(), response.requestCharge, response.continuationToken, elapsed())
        } else {
            QueryResult(emptyList(), 0.0, null, elapsed())
        }
    }

    fun readItem(connectionName: String, databaseId: String, containerId: String, id: String, partitionKey: PartitionKey): ObjectNode =
        withClient(connectionName) { client ->
            client.getDatabase(databaseId).getContainer(containerId).readItem(id, partitionKey, ObjectNode::class.java).item
        }

    fun upsert(connectionName: String, databaseId: String, containerId: String, document: ObjectNode): Double =
        withClient(connectionName) { client ->
            client.getDatabase(databaseId).getContainer(containerId).upsertItem(document).requestCharge
        }

    fun delete(connectionName: String, databaseId: String, containerId: String, id: String, partitionKey: PartitionKey): Double =
        withClient(connectionName) { client ->
            client.getDatabase(databaseId).getContainer(containerId)
                .deleteItem(id, partitionKey, CosmosItemRequestOptions()).requestCharge
        }

    // ---- stored procedures -----------------------------------------------------

    fun listStoredProcedures(connectionName: String, databaseId: String, containerId: String): List<String> =
        withClient(connectionName) { client ->
            scripts(client, databaseId, containerId).readAllStoredProcedures().map { it.id }.sorted()
        }

    fun readStoredProcedure(connectionName: String, databaseId: String, containerId: String, id: String): ScriptInfo =
        withClient(connectionName) { client ->
            val props = scripts(client, databaseId, containerId).getStoredProcedure(id).read().properties
            ScriptInfo(props.id, props.body)
        }

    fun saveStoredProcedure(connectionName: String, databaseId: String, containerId: String, id: String, body: String, isNew: Boolean) =
        withClient(connectionName) { client ->
            val scripts = scripts(client, databaseId, containerId)
            val props = CosmosStoredProcedureProperties(id, body)
            if (isNew) scripts.createStoredProcedure(props) else scripts.getStoredProcedure(id).replace(props)
            Unit
        }

    fun deleteStoredProcedure(connectionName: String, databaseId: String, containerId: String, id: String) =
        withClient(connectionName) { client -> scripts(client, databaseId, containerId).getStoredProcedure(id).delete(); Unit }

    fun executeStoredProcedure(
        connectionName: String,
        databaseId: String,
        containerId: String,
        id: String,
        partitionKey: PartitionKey,
        parameters: List<Any?>,
    ): String = withClient(connectionName) { client ->
        val options = CosmosStoredProcedureRequestOptions().setPartitionKey(partitionKey)
        scripts(client, databaseId, containerId).getStoredProcedure(id).execute(parameters, options).responseAsString
    }

    // ---- triggers --------------------------------------------------------------

    fun listTriggers(connectionName: String, databaseId: String, containerId: String): List<String> =
        withClient(connectionName) { client -> scripts(client, databaseId, containerId).readAllTriggers().map { it.id }.sorted() }

    fun readTrigger(connectionName: String, databaseId: String, containerId: String, id: String): TriggerInfo =
        withClient(connectionName) { client ->
            val props = scripts(client, databaseId, containerId).getTrigger(id).read().properties
            TriggerInfo(props.id, props.body, props.triggerType.name, props.triggerOperation.name)
        }

    fun saveTrigger(
        connectionName: String,
        databaseId: String,
        containerId: String,
        id: String,
        body: String,
        type: TriggerType,
        operation: TriggerOperation,
        isNew: Boolean,
    ) = withClient(connectionName) { client ->
        val scripts = scripts(client, databaseId, containerId)
        val props = CosmosTriggerProperties(id, body).setTriggerType(type).setTriggerOperation(operation)
        if (isNew) scripts.createTrigger(props) else scripts.getTrigger(id).replace(props)
        Unit
    }

    fun deleteTrigger(connectionName: String, databaseId: String, containerId: String, id: String) =
        withClient(connectionName) { client -> scripts(client, databaseId, containerId).getTrigger(id).delete(); Unit }

    // ---- user defined functions ------------------------------------------------

    fun listUdfs(connectionName: String, databaseId: String, containerId: String): List<String> =
        withClient(connectionName) { client -> scripts(client, databaseId, containerId).readAllUserDefinedFunctions().map { it.id }.sorted() }

    fun readUdf(connectionName: String, databaseId: String, containerId: String, id: String): ScriptInfo =
        withClient(connectionName) { client ->
            val props = scripts(client, databaseId, containerId).getUserDefinedFunction(id).read().properties
            ScriptInfo(props.id, props.body)
        }

    fun saveUdf(connectionName: String, databaseId: String, containerId: String, id: String, body: String, isNew: Boolean) =
        withClient(connectionName) { client ->
            val scripts = scripts(client, databaseId, containerId)
            val props = CosmosUserDefinedFunctionProperties(id, body)
            if (isNew) scripts.createUserDefinedFunction(props) else scripts.getUserDefinedFunction(id).replace(props)
            Unit
        }

    fun deleteUdf(connectionName: String, databaseId: String, containerId: String, id: String) =
        withClient(connectionName) { client -> scripts(client, databaseId, containerId).getUserDefinedFunction(id).delete(); Unit }

    // ---- JSON / partition-key helpers -----------------------------------------

    fun prettyPrint(node: JsonNode): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)

    fun parseObject(text: String): ObjectNode {
        val node = mapper.readTree(text)
        require(node != null && node.isObject) { "Document must be a single JSON object." }
        return node as ObjectNode
    }

    /** Parses a JSON array of stored-procedure parameters, or an empty list for blank input. */
    fun parseParams(text: String): List<Any?> {
        if (text.isBlank()) return emptyList()
        val node = mapper.readTree(text)
        require(node != null && node.isArray) { "Parameters must be a JSON array, e.g. [\"a\", 1, true]." }
        return mapper.convertValue(node, List::class.java)
    }

    /**
     * Builds the partition key for a document. Single and hierarchical keys are built the same
     * way (a one-component builder equals a scalar PartitionKey), and the three special cases stay
     * distinct: an absent field -> none/undefined, an explicit JSON null -> null, an empty string
     * -> "" (a real value).
     */
    fun partitionKeyOf(document: JsonNode, paths: List<String>): PartitionKey {
        if (paths.isEmpty()) return PartitionKey.NONE
        val builder = PartitionKeyBuilder()
        for (path in paths) appendComponent(builder, valueAt(document, path))
        return builder.build()
    }

    /**
     * Parses a partition key entered by the user as JSON, so values keep their exact type and
     * hierarchical keys are expressible as an array. Examples: "abc", 42, true, null,
     * ["tenant", 7]. A blank input means the none/undefined partition.
     */
    fun partitionKeyFromJson(text: String): PartitionKey {
        if (text.isBlank()) return PartitionKey.NONE
        val node = mapper.readTree(text) ?: throw IllegalArgumentException("Invalid partition key JSON.")
        val builder = PartitionKeyBuilder()
        if (node is ArrayNode) {
            require(node.size() > 0) { "Hierarchical partition key array must not be empty." }
            node.forEach { appendComponent(builder, it) }
        } else {
            appendComponent(builder, node)
        }
        return builder.build()
    }

    /** Adds one component to [builder], keeping absent / null / value distinct. */
    private fun appendComponent(builder: PartitionKeyBuilder, value: JsonNode?) {
        when {
            value == null -> builder.addNoneValue()        // field absent / undefined
            value.isNull -> builder.addNullValue()          // explicit JSON null
            value.isBoolean -> builder.add(value.asBoolean())
            value.isNumber -> builder.add(value.asDouble())
            else -> builder.add(value.asText())             // string, including ""
        }
    }

    private fun valueAt(node: JsonNode, path: String): JsonNode? {
        var current: JsonNode? = node
        for (segment in path.trim('/').split('/')) {
            if (segment.isEmpty()) continue
            current = current?.get(segment)
        }
        return current
    }

    internal fun normalizePath(path: String): String =
        path.trim().let { if (it.startsWith("/")) it else "/$it" }

    // ---- client lifecycle ------------------------------------------------------

    private fun scripts(client: CosmosClient, databaseId: String, containerId: String) =
        client.getDatabase(databaseId).getContainer(containerId).scripts

    private fun <T> withClient(name: String, block: (CosmosClient) -> T): T =
        withPluginClassLoader { block(client(name)) }

    private fun client(name: String): CosmosClient = clients.computeIfAbsent(name) { buildClient(it) }

    private fun buildClient(name: String): CosmosClient {
        val connection = storage.find(name) ?: error("Connection '$name' was not found.")
        val key = storage.getKey(name)
            ?: error("No account key is stored for '$name'. Edit the connection and re-enter the key.")
        val builder = CosmosClientBuilder()
            .endpoint(connection.endpoint)
            .key(key)
            .contentResponseOnWriteEnabled(true)
        if (connection.preferGateway) builder.gatewayMode()
        return builder.buildClient()
    }

    /**
     * The Azure SDK discovers its HTTP client / JSON provider via [java.util.ServiceLoader],
     * which uses the thread context class loader. On IDE pooled threads that loader is the
     * platform loader and would not see the bundled providers — so we pin it to this plugin's
     * class loader for the duration of the SDK call.
     */
    private inline fun <T> withPluginClassLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = javaClass.classLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    override fun dispose() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
    }

    companion object {
        fun getInstance(): CosmosService = service()
    }
}
