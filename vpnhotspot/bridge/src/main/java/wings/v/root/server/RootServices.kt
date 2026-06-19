package wings.v.root.server

import android.annotation.SuppressLint
import be.mygod.librootkotlinx.systemContext

object RootServices {
    val netd by lazy @SuppressLint("WrongConstant") { systemContext.getSystemService("netd")!! }
}
