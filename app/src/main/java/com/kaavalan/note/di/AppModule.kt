package com.kaavalan.note.di

import com.kaavalan.note.data.captures.CaptureRepository
import com.kaavalan.note.data.instructions.InstructionRepository
import com.kaavalan.note.data.person.PersonRepository
import com.kaavalan.note.data.reminder.ReminderScheduler
import com.kaavalan.note.data.reminder.WorkManagerReminderScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * App-wide Hilt module.
 *
 * **v2.0.0 (drop Supabase):** simplified. The previous v1.9.x
 * version had four @Provides for the Supabase repositories
 * (Person, Instruction, Capture, Auth). All four are gone
 * — the app is local-only. The Room repositories are the
 * canonical implementations. The OkHttp @Provides is gone
 * (no Supabase HTTP client, no LLM model download).
 *
 * The audit-chain signing key is still device-scoped
 * ("anonymous-device-v1") because v2.0.0 has no real auth
 * — the device itself is the principal.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePersonRepository(
        impl: com.kaavalan.note.data.local.RoomPersonRepository,
    ): PersonRepository = impl

    @Provides
    @Singleton
    fun provideCaptureRepository(impl: com.kaavalan.note.data.captures.RoomCaptureRepository): CaptureRepository = impl

    @Provides
    @Singleton
    fun provideInstructionRepository(
        impl: com.kaavalan.note.data.instructions.RoomInstructionRepository,
    ): InstructionRepository = impl

    @Provides
    @Singleton
    fun provideReminderScheduler(
        impl: WorkManagerReminderScheduler,
    ): ReminderScheduler = impl

    /**
     * v1.8.0 (PROD-READINESS-P2-#4): the audit-chain
     * signing key. v1.8.0 binds a fixed device-scoped
     * UUID ("anonymous-device-v1"). v2.0.0 has no real
     * auth (the app is local-only), so the device itself
     * is the principal — every device's chain is
     * self-contained, which is the correct contract for
     * a local-only build.
     */
    @Provides
    @Singleton
    fun provideSigningKeyProvider(): com.kaavalan.note.data.audit.SigningKeyProvider =
        com.kaavalan.note.data.audit.SigningKeyProvider { "anonymous-device-v1" }
}
