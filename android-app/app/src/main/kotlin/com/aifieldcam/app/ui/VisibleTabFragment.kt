package com.aifieldcam.app.ui

import com.aifieldcam.app.data.SessionManager

/**
 * Tab 页用 hide/show 切换时不会走 onStop，需在 [androidx.fragment.app.Fragment.onHiddenChanged] 中挂接监听，
 * 避免隐藏页仍响应 [SessionManager.StatusListener] 导致卡顿。
 */
abstract class VisibleTabFragment : androidx.fragment.app.Fragment(), SessionManager.StatusListener {

    private var sessionListenerAttached = false

    protected abstract fun sessionManager(): SessionManager

    protected open fun onTabVisible() {}

    protected open fun onTabHidden() {}

    protected fun attachSessionListener() {
        if (sessionListenerAttached) return
        sessionListenerAttached = true
        sessionManager().addStatusListener(this)
        onTabVisible()
    }

    protected fun detachSessionListener() {
        if (!sessionListenerAttached) return
        sessionListenerAttached = false
        sessionManager().removeStatusListener(this)
        onTabHidden()
    }

    override fun onStart() {
        super.onStart()
        if (!isHidden) attachSessionListener()
    }

    override fun onStop() {
        detachSessionListener()
        super.onStop()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) detachSessionListener() else attachSessionListener()
    }
}
