package app.saveclip.core.download

object TransferPolicy {
    /** Android 14 (API 34) is the first release with user-initiated data transfer jobs. */
    fun userInitiatedJob(sdkInt: Int): Boolean = sdkInt >= 34
}
