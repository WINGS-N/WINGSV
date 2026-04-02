package be.mygod.vpnhotspot.widget

import android.annotation.SuppressLint
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.util.readableMessage

sealed class SmartSnackbar {
    companion object {
        fun make(@StringRes text: Int): SmartSnackbar = make(app.getText(text))
        fun make(text: CharSequence = ""): SmartSnackbar = @SuppressLint("ShowToast") run {
            if (Looper.myLooper() == null) {
                Looper.prepare()
            }
            ToastWrapper(Toast.makeText(app, text, Toast.LENGTH_LONG))
        }
        fun make(e: Throwable) = make(e.readableMessage)
    }

    abstract fun show()
    open fun action(@Suppress("UNUSED_PARAMETER") id: Int,
                    @Suppress("UNUSED_PARAMETER") listener: (View) -> Unit) = Unit
    open fun shortToast() = this
}

private class ToastWrapper(private val toast: Toast) : SmartSnackbar() {
    override fun show() = toast.show()

    override fun shortToast() = apply {
        toast.duration = Toast.LENGTH_SHORT
    }
}
