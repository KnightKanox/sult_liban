package com.liban.android.ocr

import com.liban.android.model.SceneContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** A complete low-confidence result continues; failures fall back without confirmation. */
internal suspend fun refineScene(
    local: SceneContext?,
    extract: suspend () -> SceneContext?,
    report: suspend (Boolean, Exception?) -> Unit,
): SceneContext? {
    val extracted = try { withTimeout(5_000) { extract() } }
    catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        report(false, error)
        return local
    }
    report(extracted != null, null)
    return extracted ?: local
}

