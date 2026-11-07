package net.exent.harmonisk.ui.utils

import android.content.Context

internal fun folderDisplayName(context: Context, folder: String): String {
    val resId = context.resources.getIdentifier("melody_folder_$folder", "string", context.packageName)
    return if (resId != 0) context.getString(resId)
    else folder.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
