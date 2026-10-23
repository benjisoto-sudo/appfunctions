/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.appfunctions.agent.data.di

import com.example.appfunctions.agent.data.device.AndroidDeviceCapabilityRepository
import com.example.appfunctions.agent.data.live.GeminiLiveProviderImpl
import com.example.appfunctions.agent.domain.device.DeviceCapabilityRepository
import com.example.appfunctions.agent.domain.live.LiveLlmProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for Live voice and multi-device session dependency injection.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LiveModule {
    @Binds
    @Singleton
    abstract fun bindLiveLlmProvider(impl: GeminiLiveProviderImpl): LiveLlmProvider

    @Binds
    @Singleton
    abstract fun bindDeviceCapabilityRepository(impl: AndroidDeviceCapabilityRepository): DeviceCapabilityRepository
}
