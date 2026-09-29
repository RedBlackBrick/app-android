package com.tradingplatform.app.ui.common

import java.util.Locale

/**
 * `String.format` en locale française fixe (virgule décimale) : l'app est intégralement en
 * français et se compare au web ; `"%.2f".format(x)` suivrait la langue du téléphone et afficherait
 * « +4.20% » sur un appareil en anglais, à côté de « +3,48% » ailleurs.
 */
internal fun formatFr(pattern: String, vararg args: Any?): String =
    String.format(Locale.FRENCH, pattern, *args)
