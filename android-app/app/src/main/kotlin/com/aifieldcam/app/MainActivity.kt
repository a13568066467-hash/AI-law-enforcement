package com.aifieldcam.app

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.ActivityMainBinding
import com.aifieldcam.app.platform.RecorderKeyDispatcher
import com.aifieldcam.app.ui.album.AlbumFragment
import com.aifieldcam.app.ui.chat.ChatFragment
import com.aifieldcam.app.ui.home.HomeFragment
import com.aifieldcam.app.ui.settings.MeFragment
import com.aifieldcam.app.util.CameraPermissionHelper
import com.aifieldcam.app.util.PhotoPermissionHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val session by lazy { SessionManager.getInstance(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(
                this,
                "需要相机与相册权限才能正常使用",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        requestAppPermissions()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, HomeFragment(), TAG_HOME)
                .commit()
            binding.bottomNav.menu.findItem(R.id.nav_home)?.isChecked = true
        } else {
            syncBottomNavWithVisibleFragment()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showFragment(TAG_HOME, R.id.nav_home) { HomeFragment() }
                R.id.nav_chat -> showFragment(TAG_CHAT, R.id.nav_chat) { ChatFragment() }
                R.id.nav_album -> showFragment(TAG_ALBUM, R.id.nav_album) { AlbumFragment() }
                R.id.nav_settings -> showFragment(TAG_SETTINGS, R.id.nav_settings) { MeFragment() }
                else -> false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    syncBottomNavWithVisibleFragment()
                    return
                }
                val onHome = supportFragmentManager.findFragmentByTag(TAG_HOME)?.isVisible == true
                if (onHome) {
                    moveTaskToBack(false)
                    return
                }
                binding.bottomNav.selectedItemId = R.id.nav_home
            }
        })
    }

    override fun onStart() {
        super.onStart()
        session.reconcileRecorderOnResume()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RecorderKeyDispatcher.handleKeyEvent(session, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (RecorderKeyDispatcher.handleSosLongPress(session, event)) return true
        if (RecorderKeyDispatcher.handlePttLongPress(session, event)) return true
        return super.onKeyLongPress(keyCode, event)
    }

    private fun showFragment(tag: String, navId: Int, factory: () -> Fragment): Boolean {
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current?.tag == tag && current.isVisible) return true

        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }

        val tx = supportFragmentManager.beginTransaction()
        supportFragmentManager.findFragmentByTag(TAG_VIDEO)?.let { f ->
            if (f.isAdded) tx.remove(f)
        }
        listOf(TAG_HOME, TAG_CHAT, TAG_ALBUM, TAG_SETTINGS).forEach { t ->
            supportFragmentManager.findFragmentByTag(t)?.let { f ->
                if (f.isAdded) tx.hide(f)
            }
        }
        var fragment = supportFragmentManager.findFragmentByTag(tag)
        if (fragment == null) {
            fragment = factory()
            tx.add(R.id.fragment_container, fragment, tag)
        } else {
            tx.show(fragment)
        }
        tx.commit()
        binding.bottomNav.menu.findItem(navId)?.isChecked = true
        return true
    }

    private fun syncBottomNavWithVisibleFragment() {
        val tag = listOf(TAG_HOME, TAG_CHAT, TAG_ALBUM, TAG_SETTINGS)
            .firstOrNull { t ->
                supportFragmentManager.findFragmentByTag(t)?.isVisible == true
            }
            ?: supportFragmentManager.findFragmentById(R.id.fragment_container)?.tag
            ?: return
        val navId = when (tag) {
            TAG_HOME -> R.id.nav_home
            TAG_CHAT -> R.id.nav_chat
            TAG_ALBUM -> R.id.nav_album
            TAG_SETTINGS -> R.id.nav_settings
            else -> return
        }
        binding.bottomNav.menu.findItem(navId)?.isChecked = true
    }

    fun openChatTab() {
        binding.bottomNav.selectedItemId = R.id.nav_chat
    }

    fun openVideoList() {
        val tx = supportFragmentManager.beginTransaction()
        listOf(TAG_HOME, TAG_CHAT, TAG_ALBUM, TAG_SETTINGS).forEach { t ->
            supportFragmentManager.findFragmentByTag(t)?.let { f ->
                if (f.isAdded && f.isVisible) tx.hide(f)
            }
        }
        tx.add(R.id.fragment_container, com.aifieldcam.app.ui.video.VideoFragment(), TAG_VIDEO)
            .addToBackStack("video")
            .commit()
    }

    private fun requestAppPermissions() {
        val missing = buildList {
            addAll(PhotoPermissionHelper.missing(this@MainActivity))
            addAll(
                CameraPermissionHelper.missing(
                    this@MainActivity,
                    CameraPermissionHelper.requiredPermissions(),
                ),
            )
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.distinct().toTypedArray())
        }
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.fragmentContainer.updatePadding(top = bars.top)
            binding.bottomNav.updatePadding(bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    companion object {
        private const val TAG_HOME = "home"
        private const val TAG_CHAT = "chat"
        private const val TAG_ALBUM = "album"
        private const val TAG_SETTINGS = "settings"
        private const val TAG_VIDEO = "video"
    }
}
