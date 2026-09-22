package com.vcamstudio.app.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcamstudio.engine.audio.AudioBusId
import com.vcamstudio.engine.audio.AudioMixer

/**
 * 4-bus mixer sheet (blueprint §E): fader + mute + live level per bus, master
 * gain and limiter. MUSIC/TTS buses are fully wired but carry no sources
 * until Phase 3 — they honestly show silence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixerSheet(
    mixer: AudioMixer,
    masterGain: Float,
    limiterEnabled: Boolean,
    onMasterGain: (Float) -> Unit,
    onLimiter: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("Mixer")
            BusRow(mixer, AudioBusId.MIC, "Mic (live)")
            BusRow(mixer, AudioBusId.MEDIA, "Media (video layers)")
            BusRow(mixer, AudioBusId.MUSIC, "Music (Phase 3)")
            BusRow(mixer, AudioBusId.TTS, "TTS (Phase 3)")
            SectionTitle("Master")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Limiter", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = limiterEnabled, onCheckedChange = onLimiter)
                LevelBar(
                    mixer.masterLevel.collectAsStateWithLifecycle().value,
                    Modifier.weight(1f),
                )
            }
            LabeledSlider(
                "Master gain",
                masterGain,
                0f..1.5f,
                valueText = "%.0f%%".format(masterGain * 100),
            ) { onMasterGain(it) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BusRow(mixer: AudioMixer, id: AudioBusId, label: String) {
    val gain by mixer.bus(id).gain.collectAsStateWithLifecycle()
    val mute by mixer.bus(id).mute.collectAsStateWithLifecycle()
    val level by mixer.bus(id).level.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            LevelBar(level, Modifier.weight(1.2f))
            FilterChip(
                selected = mute,
                onClick = { mixer.setBusMute(id, !mute) },
                label = { Text(if (mute) "MUTED" else "Live") },
            )
        }
        LabeledSlider(
            id.name.lowercase().replaceFirstChar { c -> c.uppercase() },
            gain,
            0f..1.5f,
            valueText = "%.0f%%".format(gain * 100),
        ) { mixer.setBusGain(id, it) }
    }
}
