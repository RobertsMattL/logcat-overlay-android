package com.devtechdesign.android.logcat

/** One parsed logcat line (`-v threadtime` format). */
data class LogLine(val raw: String, val level: Char) {
    companion object {
        // "11-18 10:15:02.123  1234  5678 D Tag: message"
        private val THREADTIME = Regex("""^\d\d-\d\d\s+\S+\s+\d+\s+\d+\s+([VDIWEFA])\s""")

        fun parse(raw: String): LogLine {
            val level = THREADTIME.find(raw)?.groupValues?.get(1)?.first() ?: ' '
            return LogLine(raw, level)
        }
    }
}
