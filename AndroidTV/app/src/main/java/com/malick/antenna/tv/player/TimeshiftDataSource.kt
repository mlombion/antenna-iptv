package com.malick.antenna.tv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec

/**
 * Feeds ExoPlayer from the on-box recording, as one endless stream starting at the byte offset in the URI
 * (`timeshift://local?from=123`, or the live edge without `from`). Waits at the live edge for new data.
 */
@UnstableApi
class TimeshiftDataSource(private val recorder: TimeshiftRecorder) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var cursor = 0L
    @Volatile private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val from = dataSpec.uri.getQueryParameter("from")?.toLongOrNull()
        cursor = recorder.clamp((from ?: recorder.endOffset) + dataSpec.position)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (opened) {
            val n = recorder.read(recorder.clamp(cursor).also { cursor = it }, buffer, offset, length, 500)
            if (n < 0) return C.RESULT_END_OF_INPUT
            if (n > 0) {
                cursor += n
                bytesTransferred(n)
                return n
            }
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
    }
}
