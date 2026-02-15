package com.lxmf.messenger.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

object MediaStoreUtils {
    fun getRecentPhotos(
        context: Context,
        limit: Int = 30,
    ): List<Uri> {
        val photos = mutableListOf<Uri>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver
                .query(
                    collection,
                    projection,
                    null,
                    null,
                    sortOrder,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    var count = 0
                    while (cursor.moveToNext() && count < limit) {
                        val id = cursor.getLong(idColumn)
                        val uri = ContentUris.withAppendedId(collection, id)
                        photos.add(uri)
                        count++
                    }
                }
        } catch (_: SecurityException) {
            // Permission revoked while querying - return empty list
        }
        return photos
    }
}
