package app.saveclip.core.hint

object FirstRunHint {
    val steps: List<String> = listOf(
        "저작권이 있는 영상은 권리자의 허락을 받은 경우에만 저장하고, 다시 올리지 마세요.",
        "영상을 재생하면 받기 버튼이 켜져요",
        "화질을 고르고 받으세요",
    )

    fun step(index: Int): String? = steps.getOrNull(index)

    fun isLast(index: Int): Boolean = index >= steps.lastIndex
}
