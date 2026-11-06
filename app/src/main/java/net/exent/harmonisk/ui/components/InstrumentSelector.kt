package net.exent.harmonisk.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import net.exent.harmonisk.R
import net.exent.harmonisk.music.Instrument

val SELECTABLE_INSTRUMENTS = listOf(Instrument.VIOLIN, Instrument.VIOLA, Instrument.CELLO)

@StringRes
fun Instrument.labelRes(): Int = when (this) {
    Instrument.VIOLIN -> R.string.instrument_violin
    Instrument.VIOLA -> R.string.instrument_viola
    Instrument.CELLO -> R.string.instrument_cello
}

@Composable
fun InstrumentSelector(
    selected: Instrument,
    onSelect: (Instrument) -> Unit,
    modifier: Modifier = Modifier
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        SELECTABLE_INSTRUMENTS.forEachIndexed { index, instrument ->
            SegmentedButton(
                selected = instrument == selected,
                onClick = { onSelect(instrument) },
                shape = SegmentedButtonDefaults.itemShape(index, SELECTABLE_INSTRUMENTS.size)
            ) {
                Text(stringResource(instrument.labelRes()))
            }
        }
    }
}
