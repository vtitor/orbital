package com.github.cosmosdbclient.service

import com.fasterxml.jackson.databind.JsonNode

/** How throughput is provisioned when creating a database/container. */
enum class ThroughputMode { MANUAL, AUTOSCALE, NONE }

data class ThroughputSpec(val mode: ThroughputMode, val value: Int = 400)

/** Provisioned throughput read back from a container. */
data class ThroughputInfo(val manual: Int?, val autoscaleMax: Int?) {
    fun display(): String = when {
        autoscaleMax != null -> "Autoscale, max $autoscaleMax RU/s"
        manual != null -> "Manual, $manual RU/s"
        else -> "n/a"
    }
}

data class ContainerInfo(val id: String, val partitionKeyPaths: List<String>)

data class ContainerDetails(
    val id: String,
    val partitionKeyPaths: List<String>,
    val defaultTtlSeconds: Int?,
    val indexingMode: String,
    val throughput: ThroughputInfo?,
)

/** One page of query results plus timing/metrics. Items may be objects, scalars or arrays
 *  (e.g. from `SELECT VALUE ...`), so they are modelled as generic [JsonNode]s. */
data class QueryResult(
    val items: List<JsonNode>,
    val requestCharge: Double,
    val continuationToken: String?,
    val elapsedMillis: Long,
)

/** Server-side script kinds shown under a container in the tree. */
enum class ScriptKind(val title: String) {
    STORED_PROCEDURE("Stored Procedures"),
    TRIGGER("Triggers"),
    UDF("User Defined Functions"),
}

data class ScriptInfo(val id: String, val body: String)

data class TriggerInfo(val id: String, val body: String, val type: String, val operation: String)
