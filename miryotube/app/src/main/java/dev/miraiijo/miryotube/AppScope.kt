package dev.miraiijo.miryotube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Work that must outlive a screen (saving the playback position when it closes): one app-wide scope. */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)
