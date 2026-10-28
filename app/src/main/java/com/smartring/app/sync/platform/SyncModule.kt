package com.smartring.app.sync.platform

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds @Singleton
    abstract fun bindSecretBox(impl: KeystoreSecretBox): SecretBox

    @Binds @Singleton
    abstract fun bindSyncPort(impl: RoomSyncPort): com.smartring.app.sync.engine.SyncPort
}
