package com.kaavalan.note.debug

import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.PersonDao
import com.kaavalan.note.data.subdivision.SubdivisionDao
import com.kaavalan.note.data.vault.VaultModeHolder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only inspection of the real app graph; never included in release APKs. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkspaceTestEntryPoint {
    fun instructions(): InstructionDao
    fun contacts(): PersonDao

    // v2.6.0: the acceptance tests assert on the officer's own subdivision record and on
    // the workspace the CRM is scoped to, so a device test can check what was actually
    // written rather than only what the screen rendered.
    fun people(): PersonDao
    fun subdivision(): SubdivisionDao
    fun vault(): VaultModeHolder
}
