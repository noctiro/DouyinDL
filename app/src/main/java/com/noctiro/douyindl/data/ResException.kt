package com.noctiro.douyindl.data

import androidx.annotation.StringRes

class ResException(
    @param:StringRes val resId: Int,
    val args: Array<out Any> = emptyArray()
) : Exception()
