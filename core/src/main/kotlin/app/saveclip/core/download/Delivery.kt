package app.saveclip.core.download

object Delivery {
    const val VIDEO = "video"
    const val AUDIO = "audio"
    const val IMAGE = "image"

    fun normalize(raw: String?): String = when (raw) {
        AUDIO -> AUDIO
        IMAGE -> IMAGE
        else -> VIDEO
    }
}

object TransferActions {
    const val PAUSE = "app.saveclip.action.PAUSE"
    const val CANCEL = "app.saveclip.action.CANCEL"
    const val OPEN = "app.saveclip.action.OPEN"
}
