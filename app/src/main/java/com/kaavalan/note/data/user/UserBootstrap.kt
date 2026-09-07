package com.kaavalan.note.data.user

import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

/**
 * v1.8.0 (PROD-READINESS-P2-#3): the user-bootstrap
 * helper. On a fresh install (or a v14->v15 upgrade)
 * the [users] table is empty; this helper inserts the
 * single device-owner row on the first observation.
 *
 * The v1.8.0 trade-off is "exactly one user, the
 * device owner, role = SENIOR_OFFICER" — a
 * multi-officer pilot sets up the additional users
 * at provisioning time (a future Settings → Provisioning
 * tab; out of scope for v1.8.0).
 */
@Singleton
class UserBootstrap @Inject constructor(
    private val userDao: UserDao,
) {

    /**
     * Ensure the device-owner row exists. Idempotent
     * — a no-op if the row is already present. Called
     * by [com.kaavalan.note.KaavalanApplication.onCreate] so
     * the row is in place before any other code reads
     * the [UserDao].
     *
     * The device owner id is a client-generated UUID;
     * a multi-officer pilot overrides this at
     * provisioning time (out of scope for v1.8.0).
     */
    suspend fun ensureDeviceOwner() {
        // v2.1.2 (data-integrity): route through
        // [UserDao.insertDeviceOwnerIfAbsent] rather than doing a
        // read-then-write here. The previous `deviceOwner() != null`
        // check and the `upsert` that followed were two separate
        // suspending calls, so two callers racing on a cold start
        // (KaavalanApplication.onCreate and DatabasePreflight both
        // touch this path) could both observe "no owner" and both
        // insert. The partial unique index used to catch that at the
        // DB level; it had to be removed because it did not match
        // the schema Room derives from [UserEntity] and was breaking
        // every v14 -> v15 upgrade. The check and the insert are now
        // one transaction.
        userDao.insertDeviceOwnerIfAbsent(
            UserEntity(
                id = UUID.randomUUID().toString(),
                displayName = "Device owner",
                role = Role.SENIOR_OFFICER.name,
                deviceOwner = true,
                createdAt = java.time.Instant.now().toString(),
            )
        )
    }
}
