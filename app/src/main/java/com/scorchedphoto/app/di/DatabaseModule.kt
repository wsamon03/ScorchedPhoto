package com.scorchedphoto.app.di

import android.content.Context
import androidx.room.Room
import com.scorchedphoto.app.tts.CustomVoiceDao
import com.scorchedphoto.app.tts.CustomVoiceDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideCustomVoiceDatabase(@ApplicationContext context: Context): CustomVoiceDatabase =
        Room.databaseBuilder(context, CustomVoiceDatabase::class.java, "custom_voices.db").build()

    @Provides
    @Singleton
    fun provideCustomVoiceDao(database: CustomVoiceDatabase): CustomVoiceDao = database.customVoiceDao()
}
