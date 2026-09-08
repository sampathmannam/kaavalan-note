package com.kaavalan.note.debug

import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.PersonDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only inspection of the real app graph; never included in release APKs. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkspaceTestEntryPoint {
    fun instructions(): InstructionDao
    fun contacts(): PersonDao
}
