package com.github.opscalehub.dong.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.opscalehub.dong.data.OpException
import com.github.opscalehub.dong.ui.AppViewModel

/** Recomputes [block] whenever the database changes (or [keys] change). null while loading or on failure. */
@Composable
fun <T> derived(vm: AppViewModel, vararg keys: Any?, block: suspend () -> T): State<T?> {
    val changes by vm.changes.collectAsState()
    return produceState<T?>(null, changes, *keys) {
        value = try {
            block()
        } catch (e: OpException) {
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Degrade instead of crashing the screen; log the type only (no resident data).
            android.util.Log.w("Dang", "derived failed: ${e.javaClass.simpleName}")
            null
        }
    }
}

/** Screen body with an extended FAB at the bottom-start (bottom-right in RTL is "end"). */
@Composable
fun WithFab(label: String?, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        if (label != null) {
            ExtendedFloatingActionButton(
                onClick = onClick, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(label) },
                // The extended FAB does not expose its label to accessibility services; set it explicitly
                // so TalkBack announces e.g. «هزینه جدید» instead of an unlabeled button.
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).semantics { contentDescription = label },
            )
        }
    }
}
