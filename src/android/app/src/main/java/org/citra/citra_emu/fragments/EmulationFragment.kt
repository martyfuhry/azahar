// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.fragments

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.drawerlayout.widget.DrawerLayout
import androidx.drawerlayout.widget.DrawerLayout.DrawerListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.navigation.fragment.navArgs
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.io.File
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.EmulationNavigationDirections
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.activities.EmulationActivity
import org.citra.citra_emu.databinding.DialogCheckboxBinding
import org.citra.citra_emu.databinding.DialogSliderBinding
import org.citra.citra_emu.databinding.FragmentEmulationBinding
import org.citra.citra_emu.display.PortraitScreenLayout
import org.citra.citra_emu.display.ScreenAdjustmentUtil
import org.citra.citra_emu.display.ScreenLayout
import org.citra.citra_emu.display.SecondaryDisplayLayout
import org.citra.citra_emu.features.hotkeys.Hotkey
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.SettingsViewModel
import org.citra.citra_emu.features.settings.ui.SettingsActivity
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.utils.BuildUtil
import org.citra.citra_emu.utils.DirectoryInitialization
import org.citra.citra_emu.utils.DirectoryInitialization.DirectoryInitializationState
import org.citra.citra_emu.utils.EmulationLifecycleUtil
import org.citra.citra_emu.utils.EmulationMenuSettings
import org.citra.citra_emu.utils.GameDescriptor
import org.citra.citra_emu.utils.GameHelper
import org.citra.citra_emu.utils.GameIconUtils
import org.citra.citra_emu.utils.Log
import org.citra.citra_emu.utils.RefreshRateUtil
import org.citra.citra_emu.utils.ViewUtils
import org.citra.citra_emu.viewmodel.EmulationViewModel

class EmulationFragment :
    Fragment(),
    SurfaceHolder.Callback,
    Choreographer.FrameCallback {
    private val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

    private lateinit var emulationState: EmulationState
    private var perfStatsUpdater: Runnable? = null

    // Overlay visibility the controller default last resolved to, so a burst of input-device
    // callbacks does not reload the overlay bitmaps for nothing.
    private var overlayShownForControllers: Boolean? = null

    private val emulationActivity: EmulationActivity
        get() = (requireActivity() as EmulationActivity)

    private var _binding: FragmentEmulationBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<EmulationFragmentArgs>()

    private lateinit var game: Game
    private lateinit var screenAdjustmentUtil: ScreenAdjustmentUtil

    private val emulationViewModel: EmulationViewModel by activityViewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val settings get() = settingsViewModel.settings

    private val onPause = Runnable { togglePause() }
    private val onShutdown = Runnable { emulationState.stop() }

    // The descriptor the running title was opened from, when the intent only named it by a URI
    // the core cannot open by name. Owned for the whole time the core may reopen the ROM, and
    // handed over rather than reopened when a launcher switches titles into this session.
    private val gameDescriptor = GameDescriptor()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is EmulationActivity) {
            NativeLibrary.setEmulationActivity(context)
        } else {
            throw IllegalStateException("EmulationFragment must have EmulationActivity parent")
        }
    }

    /**
     * Initialize anything that doesn't depend on the layout / views in here.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val target = resolveLaunchTarget(requireActivity().intent, args.game)
        if (target == null) {
            Toast.makeText(
                requireContext(),
                R.string.no_game_present,
                Toast.LENGTH_SHORT
            ).show()
            requireActivity().finish()
            return
        }
        gameDescriptor.adopt(target.fd)
        game = target.game

        applyInsertedCartridge()

        Log.info("[EmulationFragment] Starting application " + game.path)

        // So this fragment doesn't restart on configuration changes; i.e. rotation.
        retainInstance = true
        emulationState = EmulationState(game.path)
        screenAdjustmentUtil =
            ScreenAdjustmentUtil(requireContext(), requireActivity().windowManager, settings)
        EmulationLifecycleUtil.addPauseResumeHook(onPause)
        EmulationLifecycleUtil.addShutdownHook(onShutdown)
    }

    /** A game a launch intent names, and the descriptor opened to reach it, if one was needed. */
    private class LaunchTarget(val game: Game, val fd: Int?)

    /**
     * Works out which game [launchIntent] asks for, opening a descriptor for it when the only
     * handle on it is a URI the core cannot open by name (every launcher launch: Argosy and our
     * own home-screen shortcuts both pass a `content://` URI and no parcelled game).
     *
     * Returns null when the intent names nothing that can be opened — a revoked grant, a
     * provider that is gone, or no game in the intent at all. Nothing has been torn down on that
     * path and the returned descriptor is the caller's to own, so a title switch can give up on
     * an unreadable URI and leave the running title exactly as it was.
     */
    private fun resolveLaunchTarget(launchIntent: Intent, preferredGame: Game?): LaunchTarget? {
        var intentUri: Uri? = launchIntent.data
            ?: launchIntent.getStringExtra("SelectedGame")?.let { Uri.parse(it) }
        var fd: Int? = null
        if (intentUri != null && !BuildUtil.isGooglePlayBuild) {
            val intentUriString = intentUri.toString()
            // We need to build a special path as the incoming URI may be SAF exclusive
            Log.warning(
                "[EmulationFragment] Cannot determine native path of URI \"" +
                    intentUriString + "\", using file descriptor instead."
            )
            if (!intentUriString.startsWith("!")) {
                fd = try {
                    requireContext().contentResolver.openFileDescriptor(
                        intentUri,
                        "r"
                    )?.detachFd()
                } catch (e: Exception) {
                    // A one-shot grant from a launcher expires, and the provider behind it can
                    // be uninstalled or have moved the file; none of that may take a session down
                    Log.error(
                        "[EmulationFragment] Could not open \"" + intentUriString + "\": " +
                            e.message
                    )
                    null
                }
                intentUri = fd?.let { Uri.parse("fd://$it") }
            }
        }
        val intentGame = intentUri?.let {
            // isInstalled, addedToLibrary and mediaType do not matter here
            GameHelper.getGame(
                it,
                isInstalled = false,
                addedToLibrary = false,
                mediaType = Game.MediaType.GAME_CARD
            )
        }
        val resolved = preferredGame ?: intentGame
        if (resolved == null) {
            fd?.let { GameDescriptor.closeRawFd(it) }
            return null
        }
        return LaunchTarget(resolved, fd)
    }

    /**
     * Loads the title [newIntent] names in place of the one this fragment is running, and
     * returns whether it could.
     *
     * A launcher that re-sends its launch intent for a *different* game reaches
     * [EmulationActivity.onNewIntent] on the live activity, so there is no second onCreate to do
     * this in and no second fragment either: `NavController.setGraph` with a graph equal to the
     * one already set replaces the graph's nodes and returns, without re-navigating to the start
     * destination or passing its arguments on. This is the reload that used to be missing.
     *
     * The order matters in three ways. The incoming game is opened first, so a failure here
     * leaves the running title untouched. The outgoing title is then stopped through
     * [EmulationState.stop], the same autosave-then-stop the exit path uses, before its
     * descriptor is closed — the core dups what it is given, but only while it still holds the
     * file. And the new title is started through the same [EmulationState] and the same surface,
     * so nothing tears down the window the renderer draws into.
     */
    fun switchToTitle(newIntent: Intent): Boolean {
        if (!::emulationState.isInitialized) {
            Log.error("[EmulationFragment] Asked to switch titles before one was ever loaded")
            return false
        }
        val parcelledGame = newIntent.extras?.let {
            BundleCompat.getParcelable(it, "game", Game::class.java)
        }
        val target = resolveLaunchTarget(newIntent, parcelledGame) ?: return false
        if (!target.game.valid) {
            // The file opened but holds nothing bootable. Letting the core find that out would
            // end the session for a game the user never stopped playing.
            Log.error("[EmulationFragment] " + target.game.path + " is not a bootable title")
            target.fd?.let { GameDescriptor.closeRawFd(it) }
            return false
        }

        emulationState.stop()
        gameDescriptor.adopt(target.fd)
        game = target.game
        applyInsertedCartridge()

        Log.info("[EmulationFragment] Switching to application " + game.path)
        showLoadingScreen()
        emulationState.restart(game.path)
        return true
    }

    private fun applyInsertedCartridge() {
        NativeLibrary.setInsertedCartridge(preferences.getString("insertedCartridge", "") ?: "")
    }

    /**
     * Puts the views back the way onViewCreated leaves them for a title that has not started
     * yet: the loading card in front, naming the incoming game, and the overlay and the drawer
     * out of reach until [EmulationActivity.onEmulationStarted] says the game is up.
     */
    private fun showLoadingScreen() {
        val binding = _binding ?: return
        GameIconUtils.loadGameIcon(requireActivity(), game, binding.loadingImage)
        binding.loadingTitle.text = game.title
        binding.loadingText.setText(R.string.loading)
        binding.loadingProgressIndicator.isIndeterminate = true
        binding.loadingProgressText.visibility = View.GONE
        binding.inGameMenu.getHeaderView(0).apply {
            findViewById<TextView>(R.id.text_game_title).text = game.title
            GameIconUtils.loadGameIcon(requireActivity(), game, findViewById(R.id.game_icon))
        }
        binding.drawerLayout.close()
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        ViewUtils.hideView(binding.surfaceInputOverlay)
        ViewUtils.showView(binding.loadingIndicator)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEmulationBinding.inflate(inflater)
        binding.inGameMenu.menu.findItem(R.id.menu_secondary_screen_layout).isVisible =
            emulationActivity.secondaryDisplayManager.availableDisplays.isNotEmpty()
        binding.inGameMenu.menu.findItem(R.id.menu_landscape_screen_layout).isVisible =
            CitraApplication.appContext.resources.configuration.orientation !=
            Configuration.ORIENTATION_PORTRAIT
        binding.inGameMenu.menu.findItem(R.id.menu_portrait_screen_layout).isVisible =
            CitraApplication.appContext.resources.configuration.orientation ==
            Configuration.ORIENTATION_PORTRAIT
        return binding.root
    }

    // This is using the correct scope, lint is just acting up
    @SuppressLint("UnsafeRepeatOnLifecycleDetector")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (requireActivity().isFinishing) {
            return
        }

        binding.surfaceEmulation.holder.addCallback(this)
        // The overlay resolved its own visibility as it was inflated; record it so the first
        // input-device callback only reloads the controls if the answer actually changed.
        overlayShownForControllers = EmulationMenuSettings.showOverlay
        binding.doneControlConfig.setOnClickListener {
            binding.doneControlConfig.visibility = View.GONE
            binding.surfaceInputOverlay.setIsInEditMode(false)
        }

        // Show/hide the "Stats" overlay
        updateShowPerformanceOverlay()

        val position = IntSetting.PERFORMANCE_OVERLAY_POSITION.int
        updateStatsPosition(position)

        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        binding.drawerLayout.addDrawerListener(object : DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                binding.surfaceInputOverlay.dispatchTouchEvent(
                    MotionEvent.obtain(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis() + 100,
                        MotionEvent.ACTION_UP,
                        0f,
                        0f,
                        0
                    )
                )
            }

            override fun onDrawerOpened(drawerView: View) {
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                binding.surfaceInputOverlay.isClickable = false
                binding.surfaceInputOverlay.isFocusable = false
                binding.surfaceInputOverlay.isFocusableInTouchMode = false
            }

            override fun onDrawerClosed(drawerView: View) {
                binding.drawerLayout.setDrawerLockMode(EmulationMenuSettings.drawerLockMode)
                binding.surfaceInputOverlay.isClickable = true
                binding.surfaceInputOverlay.isFocusable = true
                binding.surfaceInputOverlay.isFocusableInTouchMode = true
                // DrawerLayout stops offering the menu's rows to focus search once the drawer is
                // closed, but it does not take focus away from the row that had it, so a d-pad
                // press would still land on the hidden menu. Hand focus back to the game.
                giveFocusToGame(drawerView)
            }

            override fun onDrawerStateChanged(newState: Int) {
                // No op
            }
        })
        binding.inGameMenu.menu.findItem(R.id.menu_lock_drawer).apply {
            val titleId =
                if (EmulationMenuSettings.drawerLockMode == DrawerLayout.LOCK_MODE_LOCKED_CLOSED) {
                    R.string.unlock_drawer
                } else {
                    R.string.lock_drawer
                }
            val iconId =
                if (EmulationMenuSettings.drawerLockMode == DrawerLayout.LOCK_MODE_UNLOCKED) {
                    R.drawable.ic_unlocked
                } else {
                    R.drawable.ic_lock
                }

            title = getString(titleId)
            icon = ResourcesCompat.getDrawable(
                resources,
                iconId,
                requireContext().theme
            )
        }

        binding.inGameMenu.getHeaderView(0).apply {
            val titleView = findViewById<TextView>(R.id.text_game_title)
            val iconView = findViewById<ImageView>(R.id.game_icon)

            titleView.text = game.title

            GameIconUtils.loadGameIcon(requireActivity(), game, iconView)
        }

        binding.inGameMenu.setNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.menu_emulation_pause -> {
                    if (emulationState.isPaused) {
                        emulationState.unpause()
                        it.title = resources.getString(R.string.pause_emulation)
                        it.icon = ResourcesCompat.getDrawable(
                            resources,
                            R.drawable.ic_pause,
                            requireContext().theme
                        )
                    } else {
                        emulationState.pause()
                        it.title = resources.getString(R.string.resume_emulation)
                        it.icon = ResourcesCompat.getDrawable(
                            resources,
                            R.drawable.ic_play,
                            requireContext().theme
                        )
                    }
                    true
                }

                R.id.menu_emulation_savestates -> {
                    showSavestateMenu()
                    true
                }

                R.id.menu_overlay_options -> {
                    showOverlayMenu()
                    true
                }

                R.id.menu_amiibo -> {
                    showAmiiboMenu()
                    true
                }

                R.id.menu_landscape_screen_layout -> {
                    showLandscapeScreenLayoutMenu()
                    true
                }

                R.id.menu_portrait_screen_layout -> {
                    showPortraitScreenLayoutMenu()
                    true
                }

                R.id.menu_secondary_screen_layout -> {
                    showSecondaryScreenLayoutMenu()
                    true
                }

                R.id.menu_swap_screens -> {
                    screenAdjustmentUtil.swapScreen()
                    true
                }

                R.id.menu_rotate_upright -> {
                    screenAdjustmentUtil.toggleScreenUpright()
                    true
                }

                R.id.menu_lock_drawer -> {
                    when (EmulationMenuSettings.drawerLockMode) {
                        DrawerLayout.LOCK_MODE_UNLOCKED -> {
                            EmulationMenuSettings.drawerLockMode =
                                DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                            it.title = resources.getString(R.string.unlock_drawer)
                            it.icon = ResourcesCompat.getDrawable(
                                resources,
                                R.drawable.ic_lock,
                                requireContext().theme
                            )
                        }

                        DrawerLayout.LOCK_MODE_LOCKED_CLOSED -> {
                            EmulationMenuSettings.drawerLockMode = DrawerLayout.LOCK_MODE_UNLOCKED
                            it.title = resources.getString(R.string.lock_drawer)
                            it.icon = ResourcesCompat.getDrawable(
                                resources,
                                R.drawable.ic_unlocked,
                                requireContext().theme
                            )
                        }
                    }
                    true
                }

                R.id.menu_cheats -> {
                    val action = EmulationNavigationDirections
                        .actionGlobalCheatsActivity(NativeLibrary.getRunningTitleId())
                    binding.root.findNavController().navigate(action)
                    true
                }

                R.id.menu_settings -> {
                    SettingsActivity.launch(
                        requireContext(),
                        SettingsFile.FILE_NAME_CONFIG,
                        ""
                    )

                    true
                }

                R.id.menu_multiplayer -> {
                    emulationActivity.displayMultiplayerDialog()
                    true
                }

                R.id.menu_exit -> {
                    emulationState.pause()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.emulation_close_game)
                        .setMessage(R.string.emulation_close_game_message)
                        .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                            EmulationLifecycleUtil.closeGame()
                        }
                        .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int ->
                            emulationState.unpause()
                        }
                        .setOnCancelListener { emulationState.unpause() }
                        .show()
                    true
                }

                else -> true
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!emulationViewModel.emulationStarted.value) {
                        return
                    }

                    if (binding.drawerLayout.isOpen) {
                        binding.drawerLayout.close()
                    } else {
                        binding.drawerLayout.open()
                    }
                }
            }
        )

        GameIconUtils.loadGameIcon(requireActivity(), game, binding.loadingImage)
        binding.loadingTitle.text = game.title

        viewLifecycleOwner.lifecycleScope.apply {
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    emulationViewModel.shaderProgress.collectLatest {
                        if (it > 0 && it != emulationViewModel.totalShaders.value) {
                            binding.loadingProgressIndicator.isIndeterminate = false
                            binding.loadingProgressText.visibility = View.VISIBLE
                            binding.loadingProgressText.text = String.format(
                                "%d/%d",
                                emulationViewModel.shaderProgress.value,
                                emulationViewModel.totalShaders.value
                            )

                            if (it < binding.loadingProgressIndicator.max) {
                                binding.loadingProgressIndicator.progress = it
                            }
                        }

                        if (it == emulationViewModel.totalShaders.value) {
                            binding.loadingText.setText(R.string.loading)
                            binding.loadingProgressIndicator.isIndeterminate = true
                            binding.loadingProgressText.visibility = View.GONE
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    emulationViewModel.totalShaders.collectLatest {
                        binding.loadingProgressIndicator.max = it
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    emulationViewModel.shaderMessage.collectLatest {
                        if (it != "") {
                            binding.loadingText.text = it
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    emulationViewModel.emulationStarted.collectLatest { started ->
                        if (started) {
                            ViewUtils.hideView(binding.loadingIndicator)
                            ViewUtils.showView(binding.surfaceInputOverlay)
                            binding.inGameMenu.menu.findItem(R.id.menu_emulation_savestates)
                                .setVisible(NativeLibrary.getSavestateInfo() != null)
                            binding.drawerLayout.setDrawerLockMode(
                                EmulationMenuSettings.drawerLockMode
                            )
                        }
                    }
                }
            }
        }

        setInsets()
    }

    fun isDrawerOpen(): Boolean = binding.drawerLayout.isOpen

    /**
     * Moves focus off [drawerView] and onto the view the game is played through, so that a
     * closed drawer never keeps the focus a gamepad navigates with.
     */
    private fun giveFocusToGame(drawerView: View) {
        drawerView.clearFocus()
        if (!binding.surfaceInputOverlay.requestFocus()) {
            // The overlay is not focusable yet (it is invisible until emulation starts); leaving
            // nothing focused is still better than leaving the menu focused.
            binding.drawerLayout.clearFocus()
        }
    }

    private fun togglePause() {
        if (emulationState.isPaused) {
            emulationState.unpause()
        } else {
            emulationState.pause()
        }
    }

    // DrawerLayout saves whether it was open and reopens itself here. A session Android killed
    // and restored boots the title from scratch behind that drawer, with the menu holding the
    // focus a gamepad navigates with, which a cold boot never does. Put it back the way a cold
    // boot leaves it; the drawer is locked closed until emulation reports it is ready anyway.
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (!emulationViewModel.emulationStarted.value && binding.drawerLayout.isOpen) {
            binding.drawerLayout.closeDrawer(binding.inGameMenu, false)
        }
    }

    /**
     * Keeps the touch overlay's default in step with the hardware: with a physical pad
     * attached the overlay is off by default and it comes back when the pad goes away. Once
     * the user has toggled the overlay themselves their choice stands and this does nothing.
     */
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshOverlayForControllers()

        override fun onInputDeviceRemoved(deviceId: Int) = refreshOverlayForControllers()

        override fun onInputDeviceChanged(deviceId: Int) = refreshOverlayForControllers()
    }

    private fun refreshOverlayForControllers() {
        if (_binding == null || EmulationMenuSettings.isShowOverlayUserSet) {
            return
        }
        val showOverlay = EmulationMenuSettings.showOverlay
        if (showOverlay == overlayShownForControllers) {
            return
        }
        overlayShownForControllers = showOverlay
        Log.info(
            "[EmulationFragment] Physical gamepad " +
                (if (showOverlay) "gone" else "detected") +
                "; touch overlay ${if (showOverlay) "shown" else "hidden"} by default"
        )
        binding.surfaceInputOverlay.refreshControls()
    }

    override fun onResume() {
        super.onResume()
        Choreographer.getInstance().postFrameCallback(this)
        resumePerfStatsUpdates()
        (requireContext().getSystemService(Context.INPUT_SERVICE) as InputManager)
            .registerInputDeviceListener(inputDeviceListener, null)
        refreshOverlayForControllers()
        if (NativeLibrary.isRunning()) {
            emulationState.unpause()

            // If the overlay is enabled, we need to update the position if changed
            val position = IntSetting.PERFORMANCE_OVERLAY_POSITION.int
            updateStatsPosition(position)

            binding.inGameMenu.menu.findItem(R.id.menu_emulation_pause)?.let { menuItem ->
                menuItem.title = resources.getString(R.string.pause_emulation)
                menuItem.icon = ResourcesCompat.getDrawable(
                    resources,
                    R.drawable.ic_pause,
                    requireContext().theme
                )
            }
            return
        }

        if (DirectoryInitialization.areCitraDirectoriesReady()) {
            emulationState.run(emulationActivity.isActivityRecreated)
        } else {
            setupCitraDirectoriesThenStartEmulation()
        }
    }

    override fun onPause() {
        // Keyed on our own state rather than NativeLibrary.isRunning(), which is still false
        // while the title boots: a pause requested during the loading screen is parked by the
        // core and applied the moment its run loop starts, so backgrounding mid-boot no longer
        // leaves the guest running headless with audio. A pause from the in-game menu is a
        // no-op here, and a stopped state is left alone so run() can still start the thread.
        if (!emulationState.isStopped) {
            emulationState.pause()
            // Requested here rather than awaited: the emulation thread writes the state while
            // Android walks us through the rest of the background transition
            emulationState.requestAutoSave()
        }
        Choreographer.getInstance().removeFrameCallback(this)
        (requireContext().getSystemService(Context.INPUT_SERVICE) as InputManager)
            .unregisterInputDeviceListener(inputDeviceListener)
        // The overlay timer would otherwise keep waking the UI thread (and crossing JNI) once a
        // second for as long as the game sits in the background
        suspendPerfStatsUpdates()
        super.onPause()
    }

    override fun onStop() {
        // After onStop the system may kill the process at any time, so this is the last
        // chance to make sure the autosave requested in onPause has reached the disk
        if (::emulationState.isInitialized) {
            emulationState.awaitAutoSave()
        }
        super.onStop()
    }

    override fun onDetach() {
        NativeLibrary.clearEmulationActivity()
        super.onDetach()
    }

    // Reached instead of a recreation now that EmulationActivity handles configuration
    // changes itself: refresh the views onCreateView and onResume derive from the orientation
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding == null) {
            return
        }
        val isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.inGameMenu.menu.findItem(R.id.menu_landscape_screen_layout).isVisible =
            !isPortrait
        binding.inGameMenu.menu.findItem(R.id.menu_portrait_screen_layout).isVisible =
            isPortrait
        binding.surfaceInputOverlay.refreshControls()
        updateStatsPosition(IntSetting.PERFORMANCE_OVERLAY_POSITION.int)
    }

    override fun onDestroy() {
        if (::emulationState.isInitialized && requireActivity().isFinishing) {
            emulationState.stop()
        }
        suspendPerfStatsUpdates()
        perfStatsUpdater = null
        EmulationLifecycleUtil.removeHook(onPause)
        EmulationLifecycleUtil.removeHook(onShutdown)
        gameDescriptor.release()
        super.onDestroy()
    }

    private fun setupCitraDirectoriesThenStartEmulation() {
        val directoryInitializationState = DirectoryInitialization.start()
        if (directoryInitializationState ===
            DirectoryInitializationState.CITRA_DIRECTORIES_INITIALIZED
        ) {
            emulationState.run(emulationActivity.isActivityRecreated)
        } else if (directoryInitializationState ===
            DirectoryInitializationState.EXTERNAL_STORAGE_PERMISSION_NEEDED
        ) {
            Toast.makeText(context, R.string.write_permission_needed, Toast.LENGTH_SHORT)
                .show()
        } else if (directoryInitializationState ===
            DirectoryInitializationState.CANT_FIND_EXTERNAL_STORAGE
        ) {
            Toast.makeText(
                context,
                R.string.external_storage_not_mounted,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showSavestateMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_emulation_savestates)
        )

        popupMenu.menuInflater.inflate(R.menu.menu_savestates, popupMenu.menu)

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_emulation_save_state -> {
                    showStateSubmenu(true)
                    true
                }

                R.id.menu_emulation_load_state -> {
                    showStateSubmenu(false)
                    true
                }

                else -> true
            }
        }

        popupMenu.show()
    }

    private fun showStateSubmenu(isSaving: Boolean) {
        val savestates = NativeLibrary.getSavestateInfo()

        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_emulation_savestates)
        )

        popupMenu.menu.apply {
            for (i in 0 until NativeLibrary.SAVESTATE_SLOT_COUNT) {
                val slot = i
                var enableClick = isSaving
                val text = if (slot == NativeLibrary.QUICKSAVE_SLOT) {
                    getString(R.string.emulation_quicksave_slot)
                } else {
                    getString(R.string.emulation_empty_state_slot, slot)
                }

                add(text).setEnabled(enableClick).setOnMenuItemClickListener {
                    if (isSaving) {
                        NativeLibrary.saveState(slot)
                        Toast.makeText(
                            context,
                            getString(R.string.saving),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        NativeLibrary.loadState(slot)
                        binding.drawerLayout.close()
                        Toast.makeText(
                            context,
                            getString(R.string.loading),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
            }
        }

        savestates?.forEach {
            var text: String
            if (it.slot == NativeLibrary.QUICKSAVE_SLOT) {
                text = getString(R.string.emulation_occupied_quicksave_slot, it.time)
            } else {
                text = getString(R.string.emulation_occupied_state_slot, it.slot, it.time)
            }
            popupMenu.menu.getItem(it.slot).setTitle(text).setEnabled(true)
        }

        popupMenu.show()
    }

    private fun showLoadStateSubmenu() {
        val savestates = NativeLibrary.getSavestateInfo()

        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_emulation_savestates)
        )

        popupMenu.menu.apply {
            for (i in 0 until NativeLibrary.SAVESTATE_SLOT_COUNT) {
                val slot = i + 1
                val text = getString(R.string.emulation_empty_state_slot, slot)
                add(text).setEnabled(false).setOnMenuItemClickListener {
                    NativeLibrary.loadState(slot)
                    true
                }
            }
        }

        savestates?.forEach {
            val text = getString(R.string.emulation_occupied_state_slot, it.slot, it.time)
            popupMenu.menu[it.slot - 1].setTitle(text).setEnabled(true)
        }

        popupMenu.show()
    }

    private fun displaySavestateWarning() {
        if (preferences.getBoolean("savestateWarningShown", false)) {
            return
        }

        val dialogCheckboxBinding = DialogCheckboxBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.savestates)
            .setMessage(R.string.savestate_warning_message)
            .setView(dialogCheckboxBinding.root)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                preferences.edit()
                    .putBoolean("savestateWarningShown", dialogCheckboxBinding.checkBox.isChecked)
                    .apply()
            }
            .show()
    }

    private fun showOverlayMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_overlay_options)
        )

        popupMenu.menuInflater.inflate(R.menu.menu_overlay_options, popupMenu.menu)

        popupMenu.menu.apply {
            findItem(R.id.menu_show_overlay).isChecked = EmulationMenuSettings.showOverlay
            findItem(R.id.menu_performance_overlay_show).isChecked =
                BooleanSetting.PERF_OVERLAY_ENABLE.boolean
            findItem(R.id.menu_haptic_feedback).isChecked = EmulationMenuSettings.hapticFeedback
            findItem(R.id.menu_emulation_joystick_rel_center).isChecked =
                EmulationMenuSettings.joystickRelCenter
            findItem(R.id.menu_emulation_dpad_slide_enable).isChecked =
                EmulationMenuSettings.dpadSlide
        }

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_show_overlay -> {
                    EmulationMenuSettings.showOverlay = !EmulationMenuSettings.showOverlay
                    binding.surfaceInputOverlay.refreshControls()
                    true
                }

                R.id.menu_performance_overlay_show -> {
                    BooleanSetting.PERF_OVERLAY_ENABLE.boolean =
                        !BooleanSetting.PERF_OVERLAY_ENABLE.boolean
                    settings.saveSetting(
                        BooleanSetting.PERF_OVERLAY_ENABLE,
                        SettingsFile.FILE_NAME_CONFIG
                    )
                    updateShowPerformanceOverlay()
                    true
                }

                R.id.menu_haptic_feedback -> {
                    EmulationMenuSettings.hapticFeedback = !EmulationMenuSettings.hapticFeedback
                    true
                }

                R.id.menu_emulation_edit_layout -> {
                    editControlsPlacement()
                    binding.drawerLayout.close()
                    true
                }

                R.id.menu_emulation_toggle_controls -> {
                    showToggleControlsDialog()
                    true
                }

                R.id.menu_emulation_adjust_scale_reset_all -> {
                    resetAllScales()
                    true
                }

                R.id.menu_emulation_adjust_scale -> {
                    showAdjustScaleDialog("controlScale")
                    true
                }

                R.id.menu_emulation_adjust_scale_button_a -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_A)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_b -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_B)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_x -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_X)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_y -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_Y)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_l -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.TRIGGER_L)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_r -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.TRIGGER_R)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_zl -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_ZL)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_zr -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_ZR)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_start -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_START)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_select -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_SELECT)
                    true
                }

                R.id.menu_emulation_adjust_scale_controller_dpad -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.DPAD)
                    true
                }

                R.id.menu_emulation_adjust_scale_controller_circlepad -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.STICK_LEFT)
                    true
                }

                R.id.menu_emulation_adjust_scale_controller_c -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.STICK_C)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_home -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_HOME)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_swap -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_SWAP)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_turbo -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_TURBO)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_combo -> {
                    showAdjustScaleDialog("controlScale-" + Hotkey.COMBO_BUTTON.button)
                    true
                }

                R.id.menu_emulation_adjust_opacity -> {
                    showAdjustOpacityDialog()
                    true
                }

                R.id.menu_emulation_joystick_rel_center -> {
                    EmulationMenuSettings.joystickRelCenter =
                        !EmulationMenuSettings.joystickRelCenter
                    true
                }

                R.id.menu_emulation_button_sliding -> {
                    showButtonSlidingMenu()
                    true
                }

                R.id.menu_emulation_dpad_slide_enable -> {
                    EmulationMenuSettings.dpadSlide = !EmulationMenuSettings.dpadSlide
                    true
                }

                R.id.menu_emulation_reset_overlay -> {
                    showResetOverlayDialog()
                    true
                }

                else -> true
            }
        }

        popupMenu.show()
    }

    private fun showAmiiboMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_amiibo)
        )

        popupMenu.menuInflater.inflate(R.menu.menu_amiibo_options, popupMenu.menu)

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_emulation_amiibo_load -> {
                    emulationActivity.openAmiiboFileLauncher.launch(false)
                    true
                }

                R.id.menu_emulation_amiibo_remove -> {
                    NativeLibrary.removeAmiibo()
                    true
                }

                else -> true
            }
        }

        popupMenu.show()
    }

    private fun showButtonSlidingMenu() {
        val editor = preferences.edit()

        val buttonSlidingModes = mutableListOf<String>()
        buttonSlidingModes.add(getString(R.string.emulation_button_sliding_disabled))
        buttonSlidingModes.add(getString(R.string.emulation_button_sliding_enabled))
        buttonSlidingModes.add(getString(R.string.emulation_button_sliding_alternative))

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emulation_button_sliding)
            .setSingleChoiceItems(
                buttonSlidingModes.toTypedArray(),
                EmulationMenuSettings.buttonSlide
            ) { _: DialogInterface?, which: Int ->
                EmulationMenuSettings.buttonSlide = which
            }
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                editor.apply()
            }
            .show()
    }

    private fun showLandscapeScreenLayoutMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_landscape_screen_layout)
        )

        popupMenu.menuInflater.inflate(R.menu.menu_landscape_screen_layout, popupMenu.menu)

        val layoutOptionMenuItem = when (IntSetting.SCREEN_LAYOUT.int) {
            ScreenLayout.ORIGINAL.int ->
                R.id.menu_screen_layout_original

            ScreenLayout.SINGLE_SCREEN.int ->
                R.id.menu_screen_layout_single

            ScreenLayout.SIDE_SCREEN.int ->
                R.id.menu_screen_layout_sidebyside

            ScreenLayout.HYBRID_SCREEN.int ->
                R.id.menu_screen_layout_hybrid

            ScreenLayout.CUSTOM_LAYOUT.int ->
                R.id.menu_screen_layout_custom

            else -> R.id.menu_screen_layout_largescreen
        }
        popupMenu.menu.findItem(layoutOptionMenuItem).setChecked(true)

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_screen_layout_largescreen -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.LARGE_SCREEN.int)
                    true
                }

                R.id.menu_screen_layout_single -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.SINGLE_SCREEN.int)
                    true
                }

                R.id.menu_screen_layout_sidebyside -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.SIDE_SCREEN.int)
                    true
                }

                R.id.menu_screen_layout_hybrid -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.HYBRID_SCREEN.int)
                    true
                }

                R.id.menu_screen_layout_original -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.ORIGINAL.int)
                    true
                }

                R.id.menu_screen_layout_custom -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.emulation_adjust_custom_layout,
                        Toast.LENGTH_LONG
                    ).show()
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.CUSTOM_LAYOUT.int)
                    true
                }

                else -> true
            }
        }

        popupMenu.show()
    }

    private fun showPortraitScreenLayoutMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_portrait_screen_layout)
        )

        popupMenu.menuInflater.inflate(R.menu.menu_portrait_screen_layout, popupMenu.menu)

        val layoutOptionMenuItem = when (IntSetting.PORTRAIT_SCREEN_LAYOUT.int) {
            PortraitScreenLayout.TOP_FULL_WIDTH.int ->
                R.id.menu_portrait_layout_top_full

            PortraitScreenLayout.ORIGINAL.int ->
                R.id.menu_portrait_layout_original

            PortraitScreenLayout.CUSTOM_PORTRAIT_LAYOUT.int ->
                R.id.menu_portrait_layout_custom

            else ->
                R.id.menu_portrait_layout_top_full
        }

        popupMenu.menu.findItem(layoutOptionMenuItem).setChecked(true)

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_portrait_layout_top_full -> {
                    screenAdjustmentUtil.changePortraitOrientation(
                        PortraitScreenLayout.TOP_FULL_WIDTH.int
                    )
                    true
                }

                R.id.menu_portrait_layout_original -> {
                    screenAdjustmentUtil.changePortraitOrientation(
                        PortraitScreenLayout.ORIGINAL.int
                    )
                    true
                }

                R.id.menu_portrait_layout_custom -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.emulation_adjust_custom_layout,
                        Toast.LENGTH_LONG
                    ).show()
                    screenAdjustmentUtil.changePortraitOrientation(
                        PortraitScreenLayout.CUSTOM_PORTRAIT_LAYOUT.int
                    )
                    true
                }

                else -> true
            }
        }

        popupMenu.show()
    }

    private fun showSecondaryScreenLayoutMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_secondary_screen_layout)
        )
        popupMenu.menuInflater.inflate(R.menu.menu_secondary_screen_layout, popupMenu.menu)

        var selectedLayout = IntSetting.SECONDARY_DISPLAY_LAYOUT.int
        val chooserMenu = popupMenu.menu.findItem(R.id.menu_secondary_choose)
        val enableSecondaryCheckbox = popupMenu.menu.findItem(R.id.menu_enable_secondary_layout)
        chooserMenu?.subMenu?.removeGroup(R.id.menu_secondary_management_display_group)
        val displays =
            emulationActivity.secondaryDisplayManager.availableDisplays

        if (selectedLayout == SecondaryDisplayLayout.NONE.int ||
            !BooleanSetting.ENABLE_SECONDARY_DISPLAY.boolean
        ) {
            BooleanSetting.ENABLE_SECONDARY_DISPLAY.boolean = false
            enableSecondaryCheckbox.isChecked = false
            chooserMenu.isVisible = false
            popupMenu.menu.setGroupEnabled(R.id.menu_secondary_layout_group, false)
        } else {
            popupMenu.menu.setGroupEnabled(R.id.menu_secondary_layout_group, true)
            chooserMenu.isVisible = (displays.size > 1)
        }
        val layoutOptionMenuItem = when (selectedLayout) {
            SecondaryDisplayLayout.NONE.int ->
                R.id.menu_secondary_layout_opposite

            SecondaryDisplayLayout.REVERSE_PRIMARY.int ->
                R.id.menu_secondary_layout_opposite

            SecondaryDisplayLayout.TOP_SCREEN.int ->
                R.id.menu_secondary_layout_top

            SecondaryDisplayLayout.BOTTOM_SCREEN.int ->
                R.id.menu_secondary_layout_bottom

            SecondaryDisplayLayout.HYBRID.int ->
                R.id.menu_secondary_layout_hybrid

            SecondaryDisplayLayout.LARGE_SCREEN.int ->
                R.id.menu_secondary_layout_largescreen

            SecondaryDisplayLayout.ORIGINAL.int ->
                R.id.menu_secondary_layout_original

            else ->
                R.id.menu_secondary_layout_side_by_side
        }
        popupMenu.menu.findItem(layoutOptionMenuItem).isChecked = true
        // Add the available secondary displays to the display chooser list
        // Use the display ID as the menu ID - since generated menu IDs are all > 1,000,000 this
        // *should* result in unique ids
        if (displays.size > 1 && selectedLayout != SecondaryDisplayLayout.NONE.int) {
            val current = emulationActivity.secondaryDisplayManager.currentDisplayId
            chooserMenu.isVisible = true
            displays.forEachIndexed { index, display ->
                chooserMenu?.subMenu?.add(
                    R.id.menu_secondary_management_display_group,
                    display.displayId,
                    index,
                    "Display ${display.displayId} - ${display.name}"
                )?.apply {
                    isChecked = (display.displayId == current)
                }
            }
            chooserMenu.subMenu?.setGroupCheckable(
                R.id.menu_secondary_management_display_group,
                true,
                true
            )
        }

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_enable_secondary_layout -> {
                    if (!it.isChecked) {
                        screenAdjustmentUtil.enableSecondaryDisplay(selectedLayout)
                    } else {
                        screenAdjustmentUtil.disableSecondaryDisplay()
                    }
                    emulationActivity.secondaryDisplayManager.updateDisplay()
                    showSecondaryScreenLayoutMenu() // reopen menu to get new behaviors
                    true
                }

                R.id.menu_secondary_layout_opposite -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.REVERSE_PRIMARY.int
                    )
                    true
                }

                R.id.menu_secondary_layout_top -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.TOP_SCREEN.int
                    )
                    true
                }

                R.id.menu_secondary_layout_bottom -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.BOTTOM_SCREEN.int
                    )
                    true
                }

                R.id.menu_secondary_layout_side_by_side -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.SIDE_BY_SIDE.int
                    )
                    true
                }

                R.id.menu_secondary_layout_hybrid -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.HYBRID.int
                    )
                    true
                }

                R.id.menu_secondary_layout_original -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.ORIGINAL.int
                    )
                    true
                }

                R.id.menu_secondary_layout_largescreen -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.LARGE_SCREEN.int
                    )
                    true
                }

                R.id.menu_secondary_choose -> {
                    true
                }

                else -> {
                    // display ID selection
                    // If we are clicking on a menu item that isn't one of the options above, it must
                    // be one of the dynamically generated menu items added to the secondary display
                    // choice list.
                    emulationActivity.secondaryDisplayManager.preferredDisplayId = it.itemId
                    emulationActivity.secondaryDisplayManager.updateDisplay()
                    true
                }
            }
        }
        popupMenu.show()
    }

    private fun editControlsPlacement() {
        if (binding.surfaceInputOverlay.isInEditMode) {
            binding.doneControlConfig.visibility = View.GONE
            binding.surfaceInputOverlay.setIsInEditMode(false)
        } else {
            binding.doneControlConfig.visibility = View.VISIBLE
            binding.surfaceInputOverlay.setIsInEditMode(true)
        }
    }

    private fun showToggleControlsDialog() {
        val editor = preferences.edit()
        val enabledButtons = BooleanArray(17)
        enabledButtons.forEachIndexed { i: Int, _: Boolean ->
            // Buttons that are disabled by default
            var defaultValue = true
            when (i) {
                // TODO: Remove these magic numbers
                6, 7, 12, 13, 14, 15, 16 -> defaultValue = false
            }
            enabledButtons[i] = preferences.getBoolean("buttonToggle$i", defaultValue)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emulation_toggle_controls)
            .setMultiChoiceItems(
                R.array.n3dsButtons,
                enabledButtons
            ) { _: DialogInterface?, indexSelected: Int, isChecked: Boolean ->
                editor.putBoolean("buttonToggle$indexSelected", isChecked)
            }
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                editor.apply()
                binding.surfaceInputOverlay.refreshControls()
            }
            .show()

        // Band-aid fix for strange dialog flickering issue
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayMetrics = requireActivity().windowManager.currentWindowMetrics
            val displayHeight = displayMetrics.bounds.height()
            // The layout visually breaks if we try to set the height directly rather than like this.
            // Why? Fuck you, that's why!
            val newAttributes = dialog.window?.attributes
            newAttributes?.height = (displayHeight * 0.85f).toInt()
            dialog.window?.attributes = newAttributes
        }
    }

    private fun showAdjustScaleDialog(target: String) {
        val sliderBinding = DialogSliderBinding.inflate(layoutInflater)

        sliderBinding.apply {
            val sliderStart = 50
            val sliderMin = 0
            val sliderMax = 150
            slider.valueFrom = sliderMin.toFloat()
            slider.valueTo = sliderMax.toFloat()
            slider.value = preferences.getInt(target, sliderStart)
                .toFloat()
                .coerceIn(slider.valueFrom..slider.valueTo)
            @SuppressLint("SetTextI18n")
            textValue.setText((slider.value + sliderStart).toInt().toString())
            textValue.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) {
                    val value = s.toString().toIntOrNull()
                    val realValue = value?.minus(sliderStart)
                    if (realValue == null || realValue < sliderMin || realValue > sliderMax) {
                        textInput.error = "Inappropriate Value"
                    } else {
                        textInput.error = null
                        slider.value = realValue.toFloat()
                    }
                }

                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            })
            slider.addOnChangeListener(
                Slider.OnChangeListener {
                        slider: Slider,
                        progress: Float,
                        _: Boolean
                    ->
                    if (textValue.text.toString() !=
                        (slider.value + sliderStart).toInt().toString()
                    ) {
                        textValue.setText((slider.value + sliderStart).toInt().toString())
                        textValue.setSelection(textValue.length())
                        setControlScale(slider.value.toInt(), target)
                    }
                }
            )
            textInput.suffixText = "%"
        }
        val previousProgress = sliderBinding.slider.value.toInt()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emulation_control_scale)
            .setView(sliderBinding.root)
            .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int ->
                setControlScale(previousProgress, target)
            }
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                setControlScale(sliderBinding.slider.value.toInt(), target)
            }
            .setNeutralButton(R.string.slider_default) { _: DialogInterface?, _: Int ->
                setControlScale(50, target)
            }
            .show()
    }

    private fun showAdjustOpacityDialog() {
        val sliderBinding = DialogSliderBinding.inflate(layoutInflater)

        sliderBinding.apply {
            slider.valueFrom = 0f
            slider.valueTo = 100f
            slider.value = preferences.getInt("controlOpacity", 50).toFloat()
            textValue.setText(slider.value.toInt().toString())

            textValue.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) {
                    val value = s.toString().toIntOrNull()
                    if (value == null || value < slider.valueFrom || value > slider.valueTo) {
                        textInput.error = "Inappropriate Value"
                    } else {
                        textInput.error = null
                        slider.value = value.toFloat()
                    }
                }

                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            })

            slider.addOnChangeListener { _: Slider, value: Float, _: Boolean ->

                if (textValue.text.toString() != slider.value.toInt().toString()) {
                    textValue.setText(slider.value.toInt().toString())
                    textValue.setSelection(textValue.length())
                    setControlOpacity(slider.value.toInt())
                }
            }

            textInput.suffixText = "%"
        }
        val previousProgress = sliderBinding.slider.value.toInt()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emulation_control_opacity)
            .setView(sliderBinding.root)
            .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int ->
                setControlOpacity(previousProgress)
            }
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                setControlOpacity(sliderBinding.slider.value.toInt())
            }
            .setNeutralButton(R.string.slider_default) { _: DialogInterface?, _: Int ->
                setControlOpacity(50)
            }
            .show()
    }

    private fun setControlScale(scale: Int, target: String) {
        preferences.edit()
            .putInt(target, scale)
            .apply()
        binding.surfaceInputOverlay.refreshControls()
    }

    private fun resetScale(target: String) {
        preferences.edit().putInt(
            target,
            50
        ).apply()
    }

    private fun resetAllScales() {
        resetScale("controlScale")
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_A)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_B)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_X)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_Y)
        resetScale("controlScale-" + NativeLibrary.ButtonType.TRIGGER_L)
        resetScale("controlScale-" + NativeLibrary.ButtonType.TRIGGER_R)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_ZL)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_ZR)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_START)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_SELECT)
        resetScale("controlScale-" + NativeLibrary.ButtonType.DPAD)
        resetScale("controlScale-" + NativeLibrary.ButtonType.STICK_LEFT)
        resetScale("controlScale-" + NativeLibrary.ButtonType.STICK_C)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_HOME)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_SWAP)
        resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_TURBO)
        resetScale("controlScale-" + Hotkey.COMBO_BUTTON.button)
        binding.surfaceInputOverlay.refreshControls()
    }

    private fun setControlOpacity(opacity: Int) {
        preferences.edit()
            .putInt("controlOpacity", opacity)
            .apply()
        binding.surfaceInputOverlay.refreshControls()
    }

    private fun showResetOverlayDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.emulation_touch_overlay_reset))
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                resetInputOverlay()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resetInputOverlay() {
        resetAllScales()
        preferences.edit()
            .putInt("controlOpacity", 50)
            .apply()

        val editor = preferences.edit()
        // TODO: This code sucks balls. We need to do this differently. -OS
        for (i in 0 until 17) {
            var defaultValue = true
            when (i) {
                6, 7, 12, 13, 14, 15, 16 -> defaultValue = false
            }
            editor.putBoolean("buttonToggle$i", defaultValue)
        }
        editor.apply()

        binding.surfaceInputOverlay.resetButtonPlacement()
    }

    fun updateShowPerformanceOverlay() {
        suspendPerfStatsUpdates()
        perfStatsUpdater = null

        if (BooleanSetting.PERF_OVERLAY_ENABLE.boolean) {
            @Suppress("UnusedVariable")
            val systemFps = 0
            val fps = 1
            val speed = 2
            val frametime = 3
            val timeSvc = 4
            val timeIpc = 5
            val timeGpu = 6
            val timeSwap = 7
            val timeRem = 8
            perfStatsUpdater = Runnable {
                val sb = StringBuilder()
                val perfStats = NativeLibrary.getPerfStats()
                val dividerString = "\u00A0\u2502 "
                if (perfStats[fps] > 0) {
                    if (BooleanSetting.PERF_OVERLAY_SHOW_FPS.boolean) {
                        sb.append(String.format("FPS:\u00A0%d", (perfStats[fps] + 0.5).toInt()))
                    }

                    if (BooleanSetting.PERF_OVERLAY_SHOW_FRAMETIME.boolean) {
                        if (sb.isNotEmpty()) sb.append(dividerString)
                        sb.append(
                            String.format(
                                "Frame:\u00A0%.1fms (GPU: [CMD:\u00A0%.1fms SWP:\u00A0%.1fms] IPC:\u00A0%.1fms SVC:\u00A0%.1fms Rem:\u00A0%.1fms)",
                                (perfStats[frametime] * 1000.0f).toFloat(),
                                (perfStats[timeGpu] * 1000.0f).toFloat(),
                                (perfStats[timeSwap] * 1000.0f).toFloat(),
                                (perfStats[timeIpc] * 1000.0f).toFloat(),
                                (perfStats[timeSvc] * 1000.0f).toFloat(),
                                (perfStats[timeRem] * 1000.0f).toFloat()
                            )
                        )
                    }

                    if (BooleanSetting.PERF_OVERLAY_SHOW_SPEED.boolean) {
                        if (sb.isNotEmpty()) sb.append(dividerString)
                        sb.append(
                            String.format(
                                "Speed:\u00A0%d%%",
                                (perfStats[speed] * 100.0 + 0.5).toInt()
                            )
                        )
                    }

                    if (BooleanSetting.PERF_OVERLAY_SHOW_APP_RAM_USAGE.boolean) {
                        if (sb.isNotEmpty()) sb.append(dividerString)
                        val appRamUsage =
                            File("/proc/self/statm").readLines()[0].split(' ')[1].toLong() * 4096 /
                                1000000
                        sb.append("Process\u00A0RAM:\u00A0$appRamUsage\u00A0MB")
                    }

                    if (BooleanSetting.PERF_OVERLAY_SHOW_AVAILABLE_RAM.boolean) {
                        if (sb.isNotEmpty()) sb.append(dividerString)
                        context?.let { ctx ->
                            val activityManager =
                                ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            val memInfo = ActivityManager.MemoryInfo()
                            activityManager.getMemoryInfo(memInfo)
                            val megabyteBytes = 1048576L
                            val availableRam = memInfo.availMem / megabyteBytes
                            sb.append("Available\u00A0RAM:\u00A0$availableRam\u00A0MB")
                        }
                    }

                    if (BooleanSetting.PERF_OVERLAY_SHOW_BATTERY_TEMP.boolean) {
                        if (sb.isNotEmpty()) sb.append(dividerString)
                        val batteryTemp = getBatteryTemperature()
                        val tempF = celsiusToFahrenheit(batteryTemp)
                        sb.append(String.format("%.1f°C/%.1f°F", batteryTemp, tempF))
                    }

                    if (BooleanSetting.PERF_OVERLAY_BACKGROUND.boolean) {
                        binding.performanceOverlayShowText.setBackgroundResource(
                            R.color.citra_transparent_black
                        )
                    } else {
                        binding.performanceOverlayShowText.setBackgroundResource(0)
                    }

                    binding.performanceOverlayShowText.text = sb.toString()
                }
                perfStatsUpdateHandler.postDelayed(perfStatsUpdater!!, 1000)
            }
            resumePerfStatsUpdates()
            binding.performanceOverlayShowText.visibility = View.VISIBLE
        } else {
            binding.performanceOverlayShowText.visibility = View.GONE
        }
    }

    /**
     * Starts the once-a-second overlay refresh if the overlay is on. Safe to call repeatedly;
     * the previous posting is cancelled first so the runnable never runs twice per tick.
     */
    private fun resumePerfStatsUpdates() {
        val updater = perfStatsUpdater ?: return
        perfStatsUpdateHandler.removeCallbacks(updater)
        perfStatsUpdateHandler.post(updater)
    }

    private fun suspendPerfStatsUpdates() {
        perfStatsUpdater?.let { perfStatsUpdateHandler.removeCallbacks(it) }
    }

    private fun updateStatsPosition(position: Int) {
        val params =
            binding.performanceOverlayShowText.layoutParams as CoordinatorLayout.LayoutParams
        val padding = (20 * resources.displayMetrics.density).toInt() // 20dp
        params.setMargins(padding, 0, padding, 0)

        when (position) {
            0 -> {
                params.gravity = (Gravity.TOP or Gravity.START)
            }

            1 -> {
                params.gravity = (Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            }

            2 -> {
                params.gravity = (Gravity.TOP or Gravity.END)
            }

            3 -> {
                params.gravity = (Gravity.BOTTOM or Gravity.START)
            }

            4 -> {
                params.gravity = (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            }

            5 -> {
                params.gravity = (Gravity.BOTTOM or Gravity.END)
            }
        }

        binding.performanceOverlayShowText.layoutParams = params
    }

    private fun getBatteryTemperature(): Float {
        try {
            val batteryIntent =
                requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            // Temperature in tenths of a degree Celsius
            val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            // Convert to degrees Celsius
            return temperature / 10.0f
        } catch (e: Exception) {
            return 0.0f
        }
    }

    private fun celsiusToFahrenheit(celsius: Float): Float = (celsius * 9 / 5) + 32

    override fun surfaceCreated(holder: SurfaceHolder) {
        // We purposely don't do anything here.
        // All work is done in surfaceChanged, which we are guaranteed to get even for surface creation.
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.debug("[EmulationFragment] Surface changed. Resolution: " + width + "x" + height)
        // Tell SurfaceFlinger this layer is a fixed 60hz producer, the same hint the bottom
        // panel's SurfaceView gets. Without it a 60hz stream on the Thor's 120hz top panel is
        // just an irregular one as far as the compositor is concerned.
        RefreshRateUtil.requestSurfaceFrameRate(holder.surface)
        emulationState.newSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        emulationState.clearSurface()
    }

    override fun doFrame(frameTimeNanos: Long) {
        Choreographer.getInstance().postFrameCallback(this)
        NativeLibrary.doFrame()
    }

    private val applyCutoutInsets: (View, WindowInsetsCompat) -> WindowInsetsCompat = {
            v,
            windowInsets
        ->
        val cutout = windowInsets.displayCutout
        val mlp = v.layoutParams as ViewGroup.MarginLayoutParams?
        mlp?.setMargins(
            cutout?.safeInsetLeft ?: 0,
            cutout?.safeInsetTop ?: 0,
            cutout?.safeInsetRight ?: 0,
            cutout?.safeInsetBottom ?: 0
        )
        windowInsets
    }

    private fun setInsets() {
        if (!BooleanSetting.EXPAND_TO_CUTOUT_AREA.boolean) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.surfaceEmulation, applyCutoutInsets)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.surfaceInputOverlay, applyCutoutInsets)
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.performanceOverlayShowText,
            applyCutoutInsets
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.inGameMenu, applyCutoutInsets)
    }

    private class EmulationState(private var gamePath: String) {
        private var state: State
        private var surface: Surface? = null

        // Set once an autosave was requested for the current pause, so a stop() that follows
        // (the activity finishing) does not write the same state a second time
        private var autoSavedWhilePaused = false

        init {
            // Starting state is stopped.
            state = State.STOPPED
        }

        @get:Synchronized
        val isStopped: Boolean
            get() = state == State.STOPPED

        @get:Synchronized
        val isPaused: Boolean
            // Getters for the current state
            get() = state == State.PAUSED

        @get:Synchronized
        val isRunning: Boolean
            get() = state == State.RUNNING

        @Synchronized
        fun stop() {
            if (state != State.STOPPED) {
                Log.debug("[EmulationFragment] Stopping emulation.")
                // Nothing has run since a pause-time autosave, so only save if there was none
                if (!autoSavedWhilePaused && NativeLibrary.requestAutoSave()) {
                    awaitAutoSave()
                }
                state = State.STOPPED
                NativeLibrary.stopEmulation()
            } else {
                Log.warning("[EmulationFragment] Stop called while already stopped.")
            }
        }

        /**
         * Asks the emulation thread for the "save on exit" state. Returns at once; the thread
         * writes the state on its own (waking up if paused), and [awaitAutoSave] blocks until
         * it has.
         */
        @Synchronized
        fun requestAutoSave(): Boolean {
            if (state == State.STOPPED || autoSavedWhilePaused) {
                return false
            }
            autoSavedWhilePaused = NativeLibrary.requestAutoSave()
            return autoSavedWhilePaused
        }

        /** Blocks until every requested autosave has been written, bounded by a timeout */
        fun awaitAutoSave() {
            if (!NativeLibrary.waitForAutoSave(NativeLibrary.AUTOSAVE_WAIT_MS)) {
                Log.warning(
                    "[EmulationFragment] Autosave did not finish within " +
                        "${NativeLibrary.AUTOSAVE_WAIT_MS} ms"
                )
            }
        }

        // State changing methods
        @Synchronized
        fun pause() {
            if (state != State.PAUSED) {
                state = State.PAUSED
                Log.debug("[EmulationFragment] Pausing emulation.")

                NativeLibrary.pauseEmulation()
                NativeLibrary.playTimeManagerStop()
                autoSavedWhilePaused = false
            } else {
                Log.warning("[EmulationFragment] Pause called while already paused.")
            }
        }

        @Synchronized
        fun unpause() {
            if (state != State.RUNNING) {
                state = State.RUNNING
                Log.debug("[EmulationFragment] Unpausing emulation.")

                NativeLibrary.unPauseEmulation()
                NativeLibrary.playTimeManagerStart(NativeLibrary.playTimeManagerGetCurrentTitleId())
                // The game moves on from here, so the pause-time autosave no longer covers it
                autoSavedWhilePaused = false
            } else {
                Log.warning("[EmulationFragment] Unpause called while already running.")
            }
        }

        /**
         * Points this state at [newGamePath] and boots it, in place of whatever ran before.
         *
         * The caller has already stopped the previous title (and written its autosave), so this
         * is a [State.STOPPED] state with a new path. The surface is deliberately kept: a
         * launcher switching titles usually does so while the activity is stopped and there is
         * no surface at all yet, in which case this only records the path and the boot happens
         * from [newSurface] exactly as it does on a cold start.
         */
        @Synchronized
        fun restart(newGamePath: String) {
            gamePath = newGamePath
            state = State.STOPPED
            autoSavedWhilePaused = false
            if (surface != null) {
                runWithValidSurface()
            }
        }

        @Synchronized
        fun run(isActivityRecreated: Boolean) {
            if (isActivityRecreated) {
                if (NativeLibrary.isRunning()) {
                    state = State.PAUSED
                }
            } else {
                Log.debug("[EmulationFragment] activity resumed or fresh start")
            }

            // If the surface is set, run now. Otherwise, wait for it to get set.
            if (surface != null) {
                runWithValidSurface()
            }
        }

        // Surface callbacks
        @Synchronized
        fun newSurface(surface: Surface?) {
            this.surface = surface
            if (this.surface != null) {
                runWithValidSurface()
            }
        }

        @Synchronized
        fun clearSurface() {
            if (surface == null) {
                Log.warning("[EmulationFragment] clearSurface called, but surface already null.")
            } else {
                surface = null
                Log.debug("[EmulationFragment] Surface destroyed.")
                when (state) {
                    State.RUNNING -> {
                        NativeLibrary.surfaceDestroyed()
                        // Losing the surface while we still believe we are running (only
                        // possible mid-boot, since onPause pauses first otherwise) must pause
                        // the core too, not just flip our own state to PAUSED
                        pause()
                    }

                    State.PAUSED -> {
                        NativeLibrary.surfaceDestroyed()
                        Log.warning("[EmulationFragment] Surface cleared while emulation paused.")
                    }

                    else -> {
                        Log.warning("[EmulationFragment] Surface cleared while emulation stopped.")
                    }
                }
            }
        }

        private fun runWithValidSurface() {
            NativeLibrary.surfaceChanged(surface!!)
            when (state) {
                State.STOPPED -> {
                    // Read here rather than inside the thread: a second title switch may have
                    // moved gamePath on by the time the thread body runs
                    val path = gamePath
                    Thread({
                        Log.debug("[EmulationFragment] Starting emulation thread.")
                        NativeLibrary.run(path)
                    }, "NativeEmulation").start()
                }

                State.PAUSED -> {
                    Log.debug("[EmulationFragment] Resuming emulation.")
                    unpause()
                }

                else -> {
                    Log.debug("[EmulationFragment] Bug, run called while already running.")
                }
            }
            state = State.RUNNING
        }

        private enum class State {
            STOPPED,
            RUNNING,
            PAUSED
        }
    }

    companion object {
        private val perfStatsUpdateHandler = Handler(Looper.myLooper()!!)
    }
}
