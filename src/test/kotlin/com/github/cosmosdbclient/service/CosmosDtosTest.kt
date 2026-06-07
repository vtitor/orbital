package com.github.cosmosdbclient.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CosmosDtosTest {

    @Test fun throughputDisplayPrefersAutoscaleThenManualThenNa() {
        assertEquals("Autoscale, max 4000 RU/s", ThroughputInfo(manual = null, autoscaleMax = 4000).display())
        assertEquals("Manual, 400 RU/s", ThroughputInfo(manual = 400, autoscaleMax = null).display())
        assertEquals("n/a", ThroughputInfo(manual = null, autoscaleMax = null).display())
    }

    @Test fun scriptKindTitles() {
        assertEquals("Stored Procedures", ScriptKind.STORED_PROCEDURE.title)
        assertEquals("Triggers", ScriptKind.TRIGGER.title)
        assertEquals("User Defined Functions", ScriptKind.UDF.title)
    }
}
