package com.seashell.gechuang.monitoring

import java.util.concurrent.atomic.AtomicLong

/** Work counters only. They do not estimate power or attribute shared-host CPU to Gechuang. */
internal object WorkspaceEnergyDiagnostics {
    val taskQueries = AtomicLong()
    val previewCaptures = AtomicLong()
}
