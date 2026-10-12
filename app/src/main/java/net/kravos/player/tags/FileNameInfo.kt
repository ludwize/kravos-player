package net.kravos.player.tags

/** What can be guessed from a file name when tags are missing: "2-07 - Artist - Title.flac", "03. Title.mp3", … */
data class FileNameInfo(val disc: Int?, val track: Int?, val artist: String?, val title: String) {
    companion object {
        private val DISC_TRACK = Regex("^(\\d{1,2})[-.](\\d{1,3})\\s*[-._)]?\\s+(.+)$")
        private val TRACK = Regex("^(\\d{1,3})\\s*[-._)]?\\s+(.+)$")

        fun parse(fileName: String): FileNameInfo {
            val base = fileName.substringBeforeLast('.').replace('_', ' ').trim()
            var disc: Int? = null
            var track: Int? = null
            var rest = base
            val dt = DISC_TRACK.find(base)
            if (dt != null) {
                disc = dt.groupValues[1].toInt()
                track = dt.groupValues[2].toInt()
                rest = dt.groupValues[3]
            } else {
                TRACK.find(base)?.let { m ->
                    track = m.groupValues[1].toInt()
                    rest = m.groupValues[2]
                }
            }
            var artist: String? = null
            val dash = rest.indexOf(" - ")
            if (dash > 0) {
                val a = rest.substring(0, dash).trim()
                if (a.any { it.isLetter() }) {
                    artist = a
                    rest = rest.substring(dash + 3).trim()
                }
            }
            return FileNameInfo(disc, track, artist, rest.trim().ifEmpty { base })
        }
    }
}
