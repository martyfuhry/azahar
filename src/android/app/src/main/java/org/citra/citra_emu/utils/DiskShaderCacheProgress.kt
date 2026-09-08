// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import androidx.annotation.Keep
import androidx.lifecycle.ViewModelProvider
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.activities.EmulationActivity
import org.citra.citra_emu.viewmodel.EmulationViewModel

@Keep
object DiskShaderCacheProgress {
    private lateinit var emulationViewModel: EmulationViewModel

    private fun prepareViewModel() {
        emulationViewModel =
            ViewModelProvider(
                NativeLibrary.sEmulationActivity.get() as EmulationActivity
            )[EmulationViewModel::class.java]
    }

    @JvmStatic
    fun loadProgress(stage: LoadCallbackStage, progress: Int, max: Int, obj: String) {
        val emulationActivity = NativeLibrary.sEmulationActivity.get()
        if (emulationActivity == null) {
            Log.error("[DiskShaderCacheProgress] EmulationActivity not present")
            return
        }

        emulationActivity.runOnUiThread {
            when (stage) {
                // Emitted once before the core is loaded (the Vulkan device, the swapchain and
                // the HLE service tree are built there, with nothing to show for it) and again
                // by the rasterizer just before it starts reading the disk cache. The second one
                // sets the same message, which a StateFlow conflates away.
                LoadCallbackStage.Prepare -> {
                    prepareViewModel()
                    emulationViewModel.setShaderMessage(
                        emulationActivity.getString(R.string.initializing_emulation)
                    )
                }

                LoadCallbackStage.Decompile -> emulationViewModel.updateProgress(
                    emulationActivity.getString(R.string.preparing_shaders),
                    progress,
                    max
                )

                LoadCallbackStage.Build -> emulationViewModel.updateProgress(
                    emulationActivity.getString(R.string.building_shaders, obj),
                    progress,
                    max
                )

                LoadCallbackStage.Complete -> emulationActivity.onEmulationStarted()
            }
        }
    }

    // Equivalent to VideoCore::LoadCallbackStage
    enum class LoadCallbackStage {
        Prepare,
        Decompile,
        Build,
        Complete
    }
}
