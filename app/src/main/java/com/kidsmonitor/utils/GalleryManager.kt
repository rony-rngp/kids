package com.kidsmonitor.utils

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream

data class ImageData(val id: Long, val name: String, val date: Long, val thumbnail: String? = null)

class GalleryManager(private val context: Context) {

    fun getImages(limit: Int = 50, offset: Int = 0): List<ImageData> {
        val images = ArrayList<ImageData>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

            if (it.moveToPosition(offset)) {
                var count = 0
                do {
                    val id = it.getLong(idColumn)
                    val name = it.getString(nameColumn) ?: "Unknown"
                    val date = it.getLong(dateColumn)
                    
                    // Fetch small thumbnail
                    val thumb = getThumbnail(id)
                    
                    images.add(ImageData(id, name, date, thumb))
                    count++
                } while (it.moveToNext() && count < limit)
            }
        }
        return images
    }

    private fun getThumbnail(imageId: Long): String? {
        return try {
            val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)
            
            // Decode with inSampleSize to save memory/time
            val options = BitmapFactory.Options().apply {
                inSampleSize = 8 // Large reduction for thumbnail
            }
            
            val bitmap = context.contentResolver.openInputStream(contentUri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            
            if (bitmap == null) return null
            
            // Resize to exact small size
            val resized = Bitmap.createScaledBitmap(bitmap, 120, 120, true)
            
            val outputStream = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 40, outputStream)
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    fun getImageBase64(imageId: Long): String? {
        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)
        return try {
            val inputStream = context.contentResolver.openInputStream(contentUri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            val outputStream = ByteArrayOutputStream()
            // Compress to JPEG, quality 70 to save bandwidth
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
