package wings.v.root.server

object WingsJni {
    init {
        System.loadLibrary("wingsvroot")
    }

    external fun removeUidInterfaceRules(path: String?, uid: Int, rules: Long): Boolean
}
