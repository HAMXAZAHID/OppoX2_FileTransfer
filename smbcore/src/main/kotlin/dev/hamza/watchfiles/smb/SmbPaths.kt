package dev.hamza.watchfiles.smb

/** Helpers for share-relative paths. The root is "" and separators are backslashes. */
object SmbPaths {
    fun normalize(path: String): String =
        path.replace('/', '\\').split('\\').filter { it.isNotEmpty() && it != "." }.joinToString("\\")

    fun join(parent: String, name: String): String {
        val p = normalize(parent)
        val n = normalize(name)
        return if (p.isEmpty()) n else if (n.isEmpty()) p else "$p\\$n"
    }

    fun parent(path: String): String {
        val p = normalize(path)
        val i = p.lastIndexOf('\\')
        return if (i < 0) "" else p.substring(0, i)
    }

    fun name(path: String): String {
        val p = normalize(path)
        return p.substring(p.lastIndexOf('\\') + 1)
    }

    /** "report.pdf" -> "report (1).pdf" */
    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
    }

    /** Characters SMB does not allow in names. */
    fun sanitizeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "file" }
}
