package es.us.ussync.sync

import kotlinx.coroutines.sync.Mutex

object SyncLocks {
    val publication = Mutex()
    val scan = Mutex()
}
