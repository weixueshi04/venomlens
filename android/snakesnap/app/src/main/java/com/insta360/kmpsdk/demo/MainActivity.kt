package com.insta360.kmpsdk.demo

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.arashivision.sdk.camera.InstaCameraSDK
import com.arashivision.sdk.media.InstaMediaSDK
import com.insta360.kmpsdk.demo.databinding.ActivityMainBinding
import com.insta360.kmpsdk.demo.util.AssetsUtil
import com.insta360.kmpsdk.demo.util.DemoAppPreferences
import com.insta360.kmpsdk.demo.util.DemoLogcatDumper
import timber.log.Timber
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController
        binding.bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val hideBottom =
                when (destination.id) {
                    R.id.previewFragment, R.id.captureFragment, R.id.liveStreamFragment, R.id.videoPlayerFragment, R.id.imagePlayerFragment, R.id.stitchFragment -> true
                    else -> false
                }
            binding.bottomNav.visibility = if (hideBottom) View.GONE else View.VISIBLE
        }

        initSDK()
    }

    override fun onResume() {
        super.onResume()
        // 后台期间 logcat 子进程可能被系统回收，回到前台立即恢复，不必等看门狗退避周期
        DemoLogcatDumper.ensureRunning(this)
    }

    private fun initSDK() {
        val req =
            XXPermissions
                .with(this)
                .permission(Permission.Group.BLUETOOTH)
                .permission(Permission.ACCESS_FINE_LOCATION, Permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            req.permission(Permission.POST_NOTIFICATIONS)
        }
        if (DemoApplication.instance.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.TIRAMISU) {
            req.permission(Permission.READ_MEDIA_IMAGES, Permission.READ_MEDIA_VIDEO, Permission.READ_MEDIA_AUDIO)
        } else {
            req.permission(Permission.Group.STORAGE)
        }
        req.request { _, _ ->
            val bluetoothOk = XXPermissions.isGranted(this, Permission.Group.BLUETOOTH)
            val locationOk =
                XXPermissions.isGranted(
                    this,
                    Permission.ACCESS_FINE_LOCATION,
                    Permission.ACCESS_COARSE_LOCATION,
                )
            if (bluetoothOk && locationOk) {
                InstaCameraSDK.init(DemoApplication.instance) {
                    cacheDir = externalCacheDir?.absolutePath
                    logLevel = DemoAppPreferences.readLogLevel(this@MainActivity)
                }
                InstaMediaSDK.init(DemoApplication.instance)
                copyAssets()
                // SDK 初始化后校验 dumper 目录与 SDK 日志目录一致，不一致则切换
                DemoLogcatDumper.alignWithSdkLogDir(this)
            } else {
                Toast
                    .makeText(
                        this,
                        getString(R.string.ble_scan_permissions_required),
                        Toast.LENGTH_SHORT,
                    ).show()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !XXPermissions.isGranted(this, Manifest.permission.POST_NOTIFICATIONS)
            ) {
                Timber.w("Notification permission not granted: foreground service notification may be hidden by the system, enable it in settings")
            }
        }
    }

    private fun copyAssets() {
        val dirStitchFisheye = File("${getExternalFilesDir(null)}/stitch_fisheye")
        if (!dirStitchFisheye.exists()) {
            AssetsUtil.copyFilesFromAssets(this, "stitch_fisheye", dirStitchFisheye.absolutePath)
        }
        val dirPureShot = File("${getExternalFilesDir(null)}/pure_shot")
        if (!dirPureShot.exists()) {
            AssetsUtil.copyFilesFromAssets(this, "pure_shot_algo", dirPureShot.absolutePath)
        }
    }
}
