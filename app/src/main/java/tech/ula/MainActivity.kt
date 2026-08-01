package tech.ula

import android.app.AlertDialog
import android.app.DownloadManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.* // ktlint-disable no-wildcard-imports
import com.google.android.material.textfield.TextInputEditText
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.appcompat.app.AppCompatActivity
import android.util.DisplayMetrics
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.findNavController
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.NavigationUI.setupWithNavController
import com.google.gson.Gson
import kotlinx.android.synthetic.main.activity_main.* // ktlint-disable no-wildcard-imports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.ula.model.entities.App
import tech.ula.model.entities.ServiceType
import tech.ula.model.entities.Session
import tech.ula.model.remote.OfficialArchiveResolver
import tech.ula.model.repositories.AssetRepository
import tech.ula.model.repositories.UlaDatabase
import tech.ula.model.state.* // ktlint-disable no-wildcard-imports
import tech.ula.ui.AppsListFragment
import tech.ula.ui.SessionListFragment
import tech.ula.utils.* // ktlint-disable no-wildcard-imports
import tech.ula.viewmodel.* // ktlint-disable no-wildcard-imports
import tech.ula.ui.FilesystemListFragment
import tech.ula.model.repositories.DownloadMetadata
import tech.ula.utils.preferences.* // ktlint-disable no-wildcard-imports

class MainActivity : AppCompatActivity(), SessionListFragment.SessionSelection, AppsListFragment.AppSelection, FilesystemListFragment.FilesystemListProgress {

    val className = "MainActivity"

    companion object {
        const val ANDROID_APP_NAME = "android"
    }

    private var progressBarIsVisible = false
    private var currentFragmentDisplaysProgressDialog = false
    private var autoStarted = false

    private val logger = SentryLogger()
    private val ulaFiles by lazy { UlaFiles(this, this.applicationInfo.nativeLibraryDir) }
    private val busyboxExecutor by lazy {
        val prootDebugLogger = ProotDebugLogger(this.defaultSharedPreferences, ulaFiles)
        BusyboxExecutor(ulaFiles, prootDebugLogger)
    }

    private val navController: NavController by lazy {
        findNavController(R.id.nav_host_fragment)
    }

    private val notificationManager by lazy {
        NotificationConstructor(this)
    }

    private val userFeedbackPrompter by lazy {
        UserFeedbackPrompter(this, findViewById(R.id.layout_user_prompt_insert))
    }

    private val optInPrompter by lazy {
        CollectionOptInPrompter(this, findViewById(R.id.layout_user_prompt_insert))
    }

    private val downloadBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == -1L) return
            else viewModel.submitCompletedDownloadId(id)
        }
    }

    private val serverServiceBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("type")?.let { intentType ->
                val breadcrumb = UlaBreadcrumb(className, BreadcrumbType.ReceivedIntent, intentType)
                logger.addBreadcrumb(breadcrumb)
                when (intentType) {
                    "sessionActivated" -> handleSessionHasBeenActivated()
                    "serverOutput" -> {
                        // Surface server startup output in the console so a
                        // failure to launch is visible rather than silent.
                        intent.getStringExtra("line")?.let { appendConsoleLine(it) }
                    }
                    "dialog" -> {
                        val type = intent.getStringExtra("dialogType") ?: ""
                        showDialog(type)
                    }
                    else -> {
                        // Unrecognised broadcast type; nothing to do.
                    }
                }
            }
        }
    }

    private val stateObserver = Observer<State> {
        val breadcrumb = UlaBreadcrumb(className, BreadcrumbType.ObservedState, "$it")
        logger.addBreadcrumb(breadcrumb)
        it?.let { state ->
            handleStateUpdate(state)
        }
    }

    private val viewModel: MainActivityViewModel by lazy {
        val ulaDatabase = UlaDatabase.getInstance(this)

        val assetPreferences = AssetPreferences(this)
        val vendoredAssetInstaller = VendoredAssetInstaller(this, ulaFiles)
        val assetRepository = AssetRepository(
                filesDir.path,
                assetPreferences,
                vendoredAssetInstaller,
                OfficialArchiveResolver(),
                ulaFiles)

        val filesystemManager = FilesystemManager(ulaFiles, busyboxExecutor, this)
        val storageCalculator = StorageCalculator(StatFs(filesDir.path))

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadManagerWrapper = DownloadManagerWrapper(downloadManager)
        val assetDownloader = AssetDownloader(assetPreferences, downloadManagerWrapper, ulaFiles)

        val appsStartupFsm = AppsStartupFsm(ulaDatabase, filesystemManager, ulaFiles)
        val sessionStartupFsm = SessionStartupFsm(ulaDatabase, assetRepository, filesystemManager, assetDownloader, storageCalculator)
        ViewModelProvider(this, MainActivityViewModelFactory(appsStartupFsm, sessionStartupFsm))
                .get(MainActivityViewModel::class.java)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.type.equals("settings"))
            navController.navigate(R.id.settings_fragment)
        else
            autoStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.post { PermissionHandler.offerFullStorageAccess(this) }
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        notificationManager.createServiceNotificationChannel() // Android O requirement

        setupConsoleScrollTracking()
        setNavStartDestination()
        setProgressDialogNavListeners()

        setupWithNavController(bottom_nav_view, navController)

        if (userFeedbackPrompter.viewShouldBeShown()) {
            userFeedbackPrompter.showView()
        }

        if (optInPrompter.viewShouldBeShown()) {
            optInPrompter.showView()
        }

        handleQWarning()

        if (optInPrompter.userHasOptedIn()) {
            logger.initialize(this)
        }

        viewModel.getState().observe(this, stateObserver)
        if (intent?.type.equals("settings"))
            navController.navigate(R.id.settings_fragment)
        else
            autoStart()
    }

    private fun setNavStartDestination() {
        val userPreference = defaultSharedPreferences.getString("pref_default_nav_location", "Apps")
        val graph = navController.navInflater.inflate(R.navigation.nav_graph)
        graph.setStartDestination(when (userPreference) {
            getString(R.string.sessions) -> R.id.session_list_fragment
            else -> R.id.app_list_fragment
        })
        navController.graph = graph
    }

    private fun setProgressDialogNavListeners() {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            currentFragmentDisplaysProgressDialog =
                    destination.label == getString(R.string.sessions) ||
                            destination.label == getString(R.string.apps) ||
                            destination.label == getString(R.string.filesystems)
            if (!currentFragmentDisplaysProgressDialog) killProgressBar()
            else if (progressBarIsVisible) displayProgressBar()
        }
    }

    private fun handleQWarning() {
        val handler = QWarningHandler(this.getSharedPreferences(QWarningHandler.prefsString, Context.MODE_PRIVATE), ulaFiles)
        if (handler.messageShouldBeDisplayed()) {
            AlertDialog.Builder(this)
                    .setTitle(R.string.q_warning_title)
                    .setMessage(R.string.q_warning_message)
                    .setPositiveButton(R.string.button_ok) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setNeutralButton(R.string.wiki) {
                        dialog, _ ->
                        dialog.dismiss()
                        sendWikiIntent()
                    }
                    .create().show()
            handler.messageHasBeenDisplayed()
        }
    }

    override fun onSupportNavigateUp() = navController.navigateUp()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_options, menu)
        return true
    }

    private fun autoStart() {
        val prefs = getSharedPreferences("apps", Context.MODE_PRIVATE)
        val json = prefs.getString("AutoApp", " ")
        if (json != null)
            if (json.compareTo(" ") != 0) {
                val gson = Gson()
                val autoApp = gson.fromJson(json, App::class.java)
                autoStarted = true
                appHasBeenSelected(autoApp, true)
            }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(serverServiceBroadcastReceiver, IntentFilter(ServerService.SERVER_SERVICE_RESULT))
        // Android 14+ requires every dynamically-registered receiver to declare
        // whether it is exported. DownloadManager's completion broadcast comes
        // from outside our app, so this one must be exported. Omitting the flag
        // throws SecurityException and kills the app on launch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    downloadBroadcastReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadBroadcastReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.handleOnResume()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.terms_and_conditions) {
            val intent = Intent("android.intent.action.VIEW", Uri.parse("https://github.com/cypherpunkarmory/userland/blob/master/LICENSE"))
            startActivity(intent)
        }
        if (item.itemId == R.id.option_wiki) {
            sendWikiIntent()
        }
        if (item.itemId == R.id.clear_support_files) {
            displayClearSupportFilesDialog()
        }
        return NavigationUI.onNavDestinationSelected(item,
                Navigation.findNavController(this, R.id.nav_host_fragment)) ||
                super.onOptionsItemSelected(item)
    }

    private fun sendWikiIntent() {
        val intent = Intent("android.intent.action.VIEW", Uri.parse("https://github.com/CypherpunkArmory/UserLAnd/wiki"))
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        PermissionHandler.offerFullStorageAccess(this)
    }

    override fun onStop() {
        super.onStop()

        LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(serverServiceBroadcastReceiver)
        try {
            unregisterReceiver(downloadBroadcastReceiver)
        } catch (err: IllegalArgumentException) {
            // Already unregistered; nothing to do.
        }
    }

    override fun appHasBeenSelected(app: App, autoStart: Boolean) {
        resetConsole()
        // The Android app runs a shell on the device itself: no filesystem, no
        // proot, no downloads, and therefore no permissions to wait on.
        if (app.name.equals(ANDROID_APP_NAME, ignoreCase = true)) {
            startActivity(Intent(this, AndroidShellActivity::class.java))
            return
        }
        if (!PermissionHandler.permissionsAreGranted(this)) {
            PermissionHandler.showPermissionsNecessaryDialog(this)
            viewModel.waitForPermissions(appToContinue = app)
            return
        }
        viewModel.submitAppSelection(app, autoStart)
    }

    override fun sessionHasBeenSelected(session: Session) {
        if (!PermissionHandler.permissionsAreGranted(this)) {
            PermissionHandler.showPermissionsNecessaryDialog(this)
            viewModel.waitForPermissions(sessionToContinue = session)
            return
        }
        viewModel.submitSessionSelection(session)
    }

    private fun handleStateUpdate(newState: State) {
        return when (newState) {
            is WaitingForInput -> { killProgressBar() }
            is CanOnlyStartSingleSession -> {
                showToast(R.string.single_session_supported)
                viewModel.handleUserInputCancelled()
            }
            is SessionCanBeStarted -> { prepareSessionForStart(newState.session) }
            is SessionCanBeRestarted -> { restartRunningSession(newState.session) }
            is IllegalState -> { handleIllegalState(newState) }
            is UserInputRequiredState -> { handleUserInputState(newState) }
            is ProgressBarUpdateState -> { handleProgressBarUpdateState(newState) }
        }
    }

    private fun prepareSessionForStart(session: Session) {
        val step = getString(R.string.progress_starting)
        val details = ""
        updateProgressBar(step, details)

        // TODO: Alert user when defaulting to VNC
        // TODO: Is this even possible?
        if (session.serviceType is ServiceType.Xsdl && Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) {
            session.serviceType = ServiceType.Vnc
        }

        when (session.serviceType) {
            ServiceType.Xsdl -> {
                viewModel.lastSelectedSession = session
                sendXsdlIntentToSetDisplayNumberAndExpectResult()
            }
            ServiceType.Vnc -> {
                setVncResolution(session)
                startSession(session)
            }
            else -> startSession(session)
        }
    }

    private fun setVncResolution(session: Session) {
        val deviceDimensions = DeviceDimensions()
        val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val orientation = applicationContext.resources.configuration.orientation
        deviceDimensions.saveDeviceDimensions(windowManager, DisplayMetrics(), orientation, defaultSharedPreferences)
        session.geometry = deviceDimensions.getScreenResolution()
    }

    private fun startSession(session: Session) {
        val serviceIntent = Intent(this, ServerService::class.java)
                .putExtra("type", "start")
                .putExtra("session", session)
        startService(serviceIntent)
        if (autoStarted) {
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 2000)
        }
    }

    /*
    XSDL has a different flow than starting SSH/VNC session.  It sends an intent to XSDL with
        with a display value.  Then XSDL sends an intent to open UserLAnd signalling
        that it has an xserver listening.  We set the initial display number as an environment variable
        then start a twm process to connect to XSDL's xserver.
    */
    private fun sendXsdlIntentToSetDisplayNumberAndExpectResult() {
        try {
            val xsdlIntent = Intent(Intent.ACTION_MAIN, Uri.parse("x11://give.me.display:4721"))
            val setDisplayRequestCode = 1
            startActivityForResult(xsdlIntent, setDisplayRequestCode)
        } catch (e: Exception) {
            val appPackageName = "x.org.server"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName")))
            } catch (error: android.content.ActivityNotFoundException) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$appPackageName")))
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        data?.let {
            val session = viewModel.lastSelectedSession
            val result = data.getStringExtra("run") ?: ""
            if (session.serviceType == ServiceType.Xsdl && result.isNotEmpty()) {
                startSession(session)
            }
        }
    }

    private fun restartRunningSession(session: Session) {
        val serviceIntent = Intent(this, ServerService::class.java)
                .putExtra("type", "restartRunningSession")
                .putExtra("session", session)
        startService(serviceIntent)
    }

    private fun handleSessionHasBeenActivated() {
        viewModel.handleSessionHasBeenActivated()
        killProgressBar()
    }

    private fun showToast(resId: Int) {
        val content = getString(resId)
        Toast.makeText(this, content, Toast.LENGTH_LONG).show()
    }

    private fun handleUserInputState(state: UserInputRequiredState) {
        return when (state) {
            is LowStorageAcknowledgementRequired -> {
                displayLowStorageDialog()
            }
            is FilesystemCredentialsRequired -> {
                getCredentials()
            }
            is AppServiceTypePreferenceRequired -> {
                getServiceTypePreference()
            }
            is LargeDownloadRequired -> {
                if (wifiIsEnabled()) {
                    viewModel.startAssetDownloads(state.downloadRequirements)
                    return
                }
                displayNetworkChoicesDialog(state.downloadRequirements)
            }
            is ActiveSessionsMustBeDeactivated -> {
                displayGenericErrorDialog(R.string.general_error_title, R.string.deactivate_sessions)
            }
        }
    }

    private fun handleIllegalState(state: IllegalState) {
        val stateDescription = IllegalStateHandler.getLocalizationData(state).getString(this)
        val displayMessage = getString(R.string.illegal_state_github_message, stateDescription)

        AlertDialog.Builder(this)
                .setMessage(displayMessage)
                .setTitle(R.string.illegal_state_title)
                .setPositiveButton(R.string.button_ok) {
                    dialog, _ ->
                    dialog.dismiss()
                }
                .create().show()
    }

    // TODO sealed classes?
    private fun showDialog(dialogType: String) {
        when (dialogType) {
            "unhandledSessionServiceType" -> {
                displayGenericErrorDialog(R.string.general_error_title,
                        R.string.illegal_state_unhandled_session_service_type)
            }
            "playStoreMissingForClient" ->
                displayGenericErrorDialog(R.string.alert_need_client_app_title,
                    R.string.alert_need_client_app_message)
            "serverFailedToStart" -> {
                killProgressBar()
                displayGenericErrorDialog(R.string.general_error_title,
                        R.string.alert_server_failed_to_start)
            }
        }
    }

    private fun displayClearSupportFilesDialog() {
        AlertDialog.Builder(this)
                .setMessage(R.string.alert_clear_support_files_message)
                .setTitle(R.string.alert_clear_support_files_title)
                .setPositiveButton(R.string.alert_clear_support_files_clear_button) { dialog, _ ->
                    handleClearSupportFiles()
                    dialog.dismiss()
                }
                .setNeutralButton(R.string.button_cancel) { dialog, _ ->
                    dialog.dismiss()
                }
                .create().show()
    }

    private fun handleClearSupportFiles() {
        val appsPreferences = AppsPreferences(this)
        val assetDirectoryNames = appsPreferences.getDistributionsList().plus("support")
        val assetFileClearer = AssetFileClearer(ulaFiles, assetDirectoryNames, busyboxExecutor)
        CoroutineScope(Dispatchers.Main).launch { viewModel.handleClearSupportFiles(assetFileClearer) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (PermissionHandler.permissionsWereGranted(requestCode, grantResults)) {
            viewModel.permissionsHaveBeenGranted()
        } else {
            PermissionHandler.showPermissionsNecessaryDialog(this)
        }
    }

    private fun handleProgressBarUpdateState(state: ProgressBarUpdateState) {
        return when (state) {
            is StartingSetup -> {
                val step = getString(R.string.progress_start_step)
                updateProgressBar(step, "")
            }
            is FetchingAssetLists -> {
                val step = getString(R.string.progress_fetching_asset_lists)
                updateProgressBar(step, "")
            }
            is CheckingForAssetsUpdates -> {
                val step = getString(R.string.progress_checking_for_required_updates)
                updateProgressBar(step, "")
            }
            is DownloadProgress -> {
                val step = getString(R.string.progress_downloading)
                val details = getString(R.string.progress_downloading_out_of, state.numComplete, state.numTotal)
                updateProgressBar(step, details)
            }
            is CopyingDownloads -> {
                val step = getString(R.string.progress_copying_downloads)
                updateProgressBar(step, "")
            }
            is VerifyingFilesystem -> {
                val step = getString(R.string.progress_verifying_assets)
                updateProgressBar(step, "")
            }
            is VerifyingAvailableStorage -> {
                val step = getString(R.string.progress_verifying_sufficient_storage)
                updateProgressBar(step, "")
            }
            is FilesystemExtractionStep -> {
                val step = getString(R.string.progress_setting_up_filesystem)
                // The raw tar/apt/bootstrap line is far more informative than a
                // formatted summary, so stream it straight into the console.
                updateProgressBar(step, state.extractionTarget)
            }
            is ClearingSupportFiles -> {
                val step = getString(R.string.progress_clearing_support_files)
                updateProgressBar(step, "")
            }
            is ProgressBarOperationComplete -> {
                killProgressBar()
            }
        }
    }

    override fun updateFilesystemExportProgress(details: String) {
        val step = getString(R.string.progress_exporting_filesystem)
        updateProgressBar(step, details)
    }

    override fun updateFilesystemDeleteProgress() {
        val step = getString(R.string.progress_deleting_filesystem)
        updateProgressBar(step, "")
    }

    override fun stopProgressFromFilesystemList() {
        killProgressBar()
    }

    private fun displayProgressBar() {
        if (!currentFragmentDisplaysProgressDialog) return

        if (!progressBarIsVisible) {
            val inAnimation = AlphaAnimation(0f, 1f)
            inAnimation.duration = 200
            layout_progress.animation = inAnimation

            layout_progress.visibility = View.VISIBLE
            layout_progress.isFocusable = true
            layout_progress.isClickable = true
            progressBarIsVisible = true

            // Re-showing the overlay re-lays out the ScrollView, which starts at
            // offset 0. That is what jumped the console to the top when the SSH
            // phase began. Restore the tail if the user was following it.
            if (userIsFollowingTail) {
                scroll_console.post { scroll_console.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    // Rolling console buffer. Capped so a long extraction cannot grow the
    // TextView unboundedly and stall the UI thread.
    private val consoleLines = ArrayDeque<String>()
    private val maxConsoleLines = 300
    private var lastConsoleStep = ""

    // True while the user is parked at the bottom, so new output should follow.
    // Set false the moment they scroll away, and true again when they return -
    // driven by real scroll events rather than guessed at during append.
    private var userIsFollowingTail = true

    private fun updateProgressBar(step: String, details: String) {
        displayProgressBar()

        if (step != lastConsoleStep) {
            lastConsoleStep = step
            appendConsoleLine("\n$ $step")
        }

        text_session_list_progress_step.text = "> $step"
        text_session_list_progress_details.text =
                if (details.isBlank()) "" else "  $details"

        if (details.isNotBlank()) appendConsoleLine(details)
    }

    /**
     * Appends a line to the console overlay.
     *
     * Assigning TextView.text resets the parent ScrollView's scroll position to
     * 0, so doing it per line meant the view snapped to the top on every append
     * and the follow-the-tail call then snapped it to the bottom - never
     * anywhere in between, and it fought the user constantly.
     *
     * TextView.append() does not reset scroll position, so it is used for the
     * common case. The buffer is only rebuilt when trimming, and the scroll
     * offset is preserved across that rebuild.
     */
    private fun appendConsoleLine(line: String) {
        val trimmed = line.trimEnd()
        if (trimmed.isEmpty() && consoleLines.lastOrNull()?.isEmpty() == true) return

        val follow = userIsFollowingTail
        consoleLines.addLast(trimmed)

        if (consoleLines.size > maxConsoleLines) {
            // Trimming requires a full rebuild. Preserve the visual position by
            // measuring how far the content shifted.
            val before = text_console_output.height
            while (consoleLines.size > maxConsoleLines) consoleLines.removeFirst()
            val previousScroll = scroll_console.scrollY
            text_console_output.text = consoleLines.joinToString("\n")
            text_console_output.post {
                val shift = before - text_console_output.height
                if (!follow && shift > 0) scroll_console.scrollTo(0, (previousScroll - shift).coerceAtLeast(0))
            }
        } else {
            // Does not disturb scrollY.
            if (text_console_output.text.isEmpty()) {
                text_console_output.append(trimmed)
            } else {
                text_console_output.append("\n" + trimmed)
            }
        }

        if (follow) {
            scroll_console.post { scroll_console.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * Tracks whether the user is parked at the bottom of the console. While they
     * are, new output follows the tail; the moment they scroll up to read, it
     * stops moving under them and stays put until they scroll back down.
     */
    private fun setupConsoleScrollTracking() {
        scroll_console.viewTreeObserver.addOnScrollChangedListener {
            val child = scroll_console.getChildAt(0) ?: return@addOnScrollChangedListener
            val distanceFromBottom = child.height - scroll_console.height - scroll_console.scrollY
            userIsFollowingTail = distanceFromBottom <= 60
        }
    }

    private fun resetConsole() {
        consoleLines.clear()
        lastConsoleStep = ""
        userIsFollowingTail = true
        text_console_output.text = ""
    }

    private fun killProgressBar() {
        // Deliberately not clearing the console here: on failure the dialog
        // closes the overlay, and wiping the log would destroy the only record
        // of why. It is cleared when the next run starts instead.
        val outAnimation = AlphaAnimation(1f, 0f)
        outAnimation.duration = 200
        layout_progress.animation = outAnimation
        layout_progress.visibility = View.GONE
        layout_progress.isFocusable = false
        layout_progress.isClickable = false
        progressBarIsVisible = false
    }

    private fun wifiIsEnabled(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in connectivityManager.allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return true
        }
        return false
    }

    private fun displayNetworkChoicesDialog(downloadsToContinue: List<DownloadMetadata>) {
        val builder = AlertDialog.Builder(this)
        builder.setMessage(R.string.alert_wifi_disabled_message)
                .setTitle(R.string.alert_wifi_disabled_title)
                .setPositiveButton(R.string.alert_wifi_disabled_continue_button) {
                    dialog, _ ->
                    dialog.dismiss()
                    viewModel.startAssetDownloads(downloadsToContinue)
                }
                .setNegativeButton(R.string.alert_wifi_disabled_turn_on_wifi_button) {
                    dialog, _ ->
                    dialog.dismiss()
                    startActivity(Intent(WifiManager.ACTION_PICK_WIFI_NETWORK))
                    viewModel.handleUserInputCancelled()
                    killProgressBar()
                }
                .setNeutralButton(R.string.alert_wifi_disabled_cancel_button) {
                    dialog, _ ->
                    dialog.dismiss()
                    viewModel.handleUserInputCancelled()
                    killProgressBar()
                }
                .setOnCancelListener {
                    viewModel.handleUserInputCancelled()
                    killProgressBar()
                }
                .create()
                .show()
    }

    private fun getCredentials() {
        val dialog = AlertDialog.Builder(this)
        val dialogView = this.layoutInflater.inflate(R.layout.dia_app_credentials, null)
        dialog.setView(dialogView)
        dialog.setCancelable(true)
        dialog.setPositiveButton(R.string.button_continue, null)
        val customDialog = dialog.create()

        customDialog.setOnShowListener {
            customDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val username = customDialog.find<TextInputEditText>(R.id.text_input_username).text.toString()
                val password = customDialog.find<TextInputEditText>(R.id.text_input_password).text.toString()
                val vncPassword = customDialog.find<TextInputEditText>(R.id.text_input_vnc_password).text.toString()

                if (validateCredentials(username, password, vncPassword)) {
                    customDialog.dismiss()
                    viewModel.submitFilesystemCredentials(username, password, vncPassword)
                }
            }
        }
        customDialog.setOnCancelListener {
            viewModel.handleUserInputCancelled()
        }
        customDialog.show()
    }

    private fun displayLowStorageDialog() {
        displayGenericErrorDialog(R.string.alert_storage_low_title, R.string.alert_storage_low_message) {
            viewModel.lowAvailableStorageAcknowledged()
        }
    }

    // TODO refactor the names here
    // TODO could this dialog share a layout with the apps details page somehow?
    private fun getServiceTypePreference() {
        val dialog = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dia_app_select_client, null)
        dialog.setView(dialogView)
        dialog.setCancelable(true)
        dialog.setPositiveButton(R.string.button_continue, null)
        val customDialog = dialog.create()

        customDialog.setOnShowListener {
            val sshTypePreference = customDialog.find<RadioButton>(R.id.ssh_radio_button)
            val vncTypePreference = customDialog.find<RadioButton>(R.id.vnc_radio_button)
            val xsdlTypePreference = customDialog.find<RadioButton>(R.id.xsdl_radio_button)

            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) {
                xsdlTypePreference.isEnabled = false
                xsdlTypePreference.alpha = 0.5f

                val xsdlSupportedText = customDialog.findViewById<TextView>(R.id.text_xsdl_version_supported_description)
                xsdlSupportedText.visibility = View.VISIBLE
            }

            if (!viewModel.lastSelectedApp.supportsCli) {
                sshTypePreference.isEnabled = false
                sshTypePreference.alpha = 0.5f
            }

            customDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                customDialog.dismiss()
                val selectedType = when {
                    sshTypePreference.isChecked -> ServiceType.Ssh
                    vncTypePreference.isChecked -> ServiceType.Vnc
                    xsdlTypePreference.isChecked -> ServiceType.Xsdl
                    else -> ServiceType.Unselected
                }
                viewModel.submitAppServiceType(selectedType)
            }
        }
        customDialog.setOnCancelListener {
            viewModel.handleUserInputCancelled()
        }

        customDialog.show()
    }

    private fun validateCredentials(username: String, password: String, vncPassword: String): Boolean {
        val blacklistedUsernames = this.resources.getStringArray(R.array.blacklisted_usernames)
        val validator = CredentialValidator()

        val usernameCredentials = validator.validateUsername(username, blacklistedUsernames)
        val passwordCredentials = validator.validatePassword(password)
        val vncPasswordCredentials = validator.validateVncPassword(vncPassword)

        return when {
            !usernameCredentials.credentialIsValid -> {
                Toast.makeText(this, usernameCredentials.errorMessageId, Toast.LENGTH_LONG).show()
                false
            }
            !passwordCredentials.credentialIsValid -> {
                Toast.makeText(this, passwordCredentials.errorMessageId, Toast.LENGTH_LONG).show()
                false
            }
            !vncPasswordCredentials.credentialIsValid -> {
                Toast.makeText(this, vncPasswordCredentials.errorMessageId, Toast.LENGTH_LONG).show()
                false
            }
            else -> true
        }
    }
}