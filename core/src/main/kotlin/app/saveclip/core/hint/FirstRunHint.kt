package app.saveclip.core.hint

object FirstRunHint {
    val steps: List<String> = listOf(
        "영상을 재생하면 받기 버튼이 켜져요",
        "감지된 목록에서 화질을 고르고 받으세요",
    )

    fun step(index: Int): String? = steps.getOrNull(index)

    fun isLast(index: Int): Boolean = index >= steps.lastIndex
}
