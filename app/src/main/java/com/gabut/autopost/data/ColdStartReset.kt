package com.gabut.autopost.data

/**
 * D17: a cold start (including after a reboot) must never leave a token showing a false
 * "Running" badge. The reference kept `posting_active = true` across restarts while no thread
 * ran (`autopost.py:47`); this port declares the reset instead of inheriting the drift.
 *
 * This type deliberately has no reference to the posting service: resetting state must never
 * start background work.
 */
object ColdStartReset {
    suspend fun resetAll(repository: PostRepository) {
        repository.resetAllRunning()
    }
}
