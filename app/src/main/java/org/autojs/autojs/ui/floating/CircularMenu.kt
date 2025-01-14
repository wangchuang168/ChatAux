package org.autojs.autojs.ui.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import butterknife.ButterKnife
import butterknife.OnClick
import butterknife.Optional
import com.afollestad.materialdialogs.MaterialDialog
import com.makeramen.roundedimageview.RoundedImageView
import com.stardust.app.DialogUtils
import com.stardust.autojs.core.accessibility.AccessibilityBridge
import com.stardust.autojs.core.image.ImageWrapper
import com.stardust.autojs.core.image.capture.ScreenCaptureManager
import com.stardust.autojs.core.image.capture.ScreenCaptureRequester
import com.stardust.automator.UiObjectCollection
import com.stardust.enhancedfloaty.FloatyService
import com.stardust.enhancedfloaty.FloatyWindow
import com.stardust.toast
import com.stardust.util.ClipboardUtil
import com.stardust.view.accessibility.AccessibilityService.Companion.instance
import com.stardust.view.accessibility.LayoutInspector.CaptureAvailableListener
import com.stardust.view.accessibility.NodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.autojs.autojs.Pref
import org.autojs.autojs.autojs.AutoJs
import org.autojs.autojs.model.explorer.ExplorerDirPage
import org.autojs.autojs.model.explorer.Explorers
import org.autojs.autojs.model.script.Scripts.run
import org.autojs.autojs.theme.dialog.ThemeColorMaterialDialogBuilder
import org.autojs.autojs.tool.AccessibilityServiceTool
import org.autojs.autojs.tool.RootTool
import org.autojs.autojs.ui.common.OperationDialogBuilder
import org.autojs.autojs.ui.explorer.ExplorerViewKt
import org.autojs.autojs.ui.floating.layoutinspector.LayoutBoundsFloatyWindow
import org.autojs.autojs.ui.floating.layoutinspector.LayoutHierarchyFloatyWindow
import org.autojs.autojs.ui.main.MainActivity
import org.autojs.autoxjs.R
import org.greenrobot.eventbus.EventBus
import org.jdeferred.Deferred
import org.jdeferred.impl.DeferredObject
import com.stardust.autojs.core.accessibility.UiSelector
import com.stardust.autojs.runtime.ScriptRuntime
import com.stardust.autojs.runtime.accessibility.AccessibilityConfig
import com.stardust.util.UiHandler

/**
 * Created by Stardust on 2017/10/18.
 */
@SuppressLint("NonConstantResourceId")
class CircularMenu(context: Context?) : CaptureAvailableListener {
    class StateChangeEvent(val currentState: Int, val previousState: Int)
    private var mWindow: CircularMenuWindow? = null
    private var mState = 0
    private var mActionViewIcon: RoundedImageView? = null
    private val mContext: Context
    private var mSettingsDialog: MaterialDialog? = null
    private var mLayoutInspectDialog: MaterialDialog? = null
    private var mRunningPackage: String? = null
    private var mRunningActivity: String? = null
    private var mCaptureDeferred: Deferred<NodeInfo?, Void, Void>? = null
    private fun setupListeners() {
        mWindow?.setOnActionViewClickListener {
            if (mWindow?.isExpanded == true) {
                mWindow?.collapse()
            } else {
                mCaptureDeferred = DeferredObject()
                AutoJs.getInstance().layoutInspector.captureCurrentWindow()
                mWindow?.expand()
            }
        }
    }

    private fun initFloaty() {
        mWindow = CircularMenuWindow(mContext, object : CircularMenuFloaty {
            override fun inflateActionView(
                service: FloatyService,
                window: CircularMenuWindow
            ): View {
                val actionView = View.inflate(service, R.layout.circular_action_view, null)
                mActionViewIcon = actionView.findViewById(R.id.icon)
                return actionView
            }

            override fun inflateMenuItems(
                service: FloatyService,
                window: CircularMenuWindow
            ): CircularActionMenu {
                val menu = View.inflate(
                    ContextThemeWrapper(service, R.style.AppTheme),
                    R.layout.circular_action_menu,
                    null
                ) as CircularActionMenu
                ButterKnife.bind(this@CircularMenu, menu)
                return menu
            }
        })
        mWindow?.setKeepToSideHiddenWidthRadio(0.25f)
        FloatyService.addWindow(mWindow)
    }

    @Optional
    @OnClick(R.id.script_list)
    fun showScriptList() {
        mWindow?.collapse()
        val explorerView = ExplorerViewKt(mContext)
        explorerView.setExplorer(
            Explorers.workspace(),
            ExplorerDirPage.createRoot(Pref.getScriptDirPath())
        )
        explorerView.setDirectorySpanSize(2)
        val dialog = ThemeColorMaterialDialogBuilder(mContext)
            .title(R.string.text_run_script)
            .customView(explorerView, false)
            .positiveText(R.string.cancel)
            .build()
        explorerView.setOnItemOperatedListener {
            dialog.dismiss()
        }
        explorerView.setOnItemClickListener { _, item ->
            item?.let { run(item.toScriptFile()) }
        }
        DialogUtils.showDialog(dialog)
    }

    suspend fun requestScreenCapture(): Boolean  {
        //首先判断是否有截图权限
        checkNotNull(mScreenCaptureRequester.screenCapture){
            //没有截图权限 进行截图权限申请
            // runCatching:此方法用来对块内执行内容的异常进行捕获，并返回块内函数是否执行成功
            if(runCatching { mScreenCaptureRequester.requestScreenCapture(mContext,1) }.isSuccess){
                toast(mContext,"获取截图权限",false)
                return true
            }else{
                toast(mContext,"获取截图权限失败",false)
                return false
            }
        }
        return true
    }

    @Synchronized
    fun captureScreen(): ImageWrapper {
        val screenCapture = mScreenCaptureRequester.screenCapture
        checkNotNull(screenCapture) { SecurityException("No screen capture permission") }
        return runBlocking {
            screenCapture.captureImageWrapper()
        }
    }
    //此对象程序运行时，只申请一次  重复定义会报错
    val mScreenCaptureRequester: ScreenCaptureRequester = ScreenCaptureManager()
    lateinit var mUiObjectCollection:UiObjectCollection

    @RequiresApi(Build.VERSION_CODES.R)
    @Optional
    @OnClick(R.id.record)
    fun startRecord() {
        mWindow?.collapse()
        //添加處理事件
        toast(mContext,"HelloRecord",false)
//        var mAccessibilityBridge:AccessibilityBridge
//        var selector = UiSelector(mAccessibilityBridge)
//        selector.id("").textMatches("")
        val scope = CoroutineScope(Job() + Dispatchers.Main)
        scope.launch {

            if(requestScreenCapture()){
                toast(mContext,"获取截图权限",false)
                //申请截图
                var timage = mScreenCaptureRequester.screenCapture?.captureImageWrapper()
            }else{
                toast(mContext,"获取截图权限失败",false)
            }
        }

//        val temp = UiObject("",allocator = AccessibilityNodeInfoAllocator(),0,-1)
//        val ts = UiGlobalSelector().id("org.autojs.autoxjs:id/more")
//         temp.findOne(ts)
//        var temp = ""
//        //Device调用方式 两种 一种是直接调用，一种是实例化后再调用
//        val device = Device(mContext)
//        temp = Device(mContext).androidId
//        temp = device.androidId
//
//        val scope = CoroutineScope(Job() + Dispatchers.Main)
//        scope.launch {
//            delay(2999L)
//            var tempkn = device.brightness
//            toast(mContext,tempkn.toString(),false)
//            scope.cancel()
//        }
        //权限类操作 要向系统提交申请 申请方为本应用程序，申请完后，也只有本应用程序的一部分可以使用
        //Contex代表的就是本程序 一个对象 或一个页面
    }
    @Optional
    @OnClick(R.id.layout_inspect)
    fun inspectLayout() {
        mWindow?.collapse()
        mLayoutInspectDialog = OperationDialogBuilder(mContext)
            .item(
                R.id.layout_bounds,
                R.drawable.ic_circular_menu_bounds,
                R.string.text_inspect_layout_bounds
            )
            .item(
                R.id.layout_hierarchy, R.drawable.ic_layout_hierarchy,
                R.string.text_inspect_layout_hierarchy
            )
            .bindItemClick(this)
            .title(R.string.text_inspect_layout)
            .build()
        DialogUtils.showDialog(mLayoutInspectDialog)
    }

    @Optional
    @OnClick(R.id.layout_bounds)
    fun showLayoutBounds() {
        inspectLayout { rootNode -> rootNode?.let { LayoutBoundsFloatyWindow(it) } }
    }

    @Optional
    @OnClick(R.id.layout_hierarchy)
    fun showLayoutHierarchy() {
        inspectLayout { mRootNode -> mRootNode?.let { LayoutHierarchyFloatyWindow(it) } }
    }

    private fun inspectLayout(windowCreator: (NodeInfo?) -> FloatyWindow?) {
        mLayoutInspectDialog?.dismiss()
        mLayoutInspectDialog = null
        if (instance == null) {
            Toast.makeText(
                mContext,
                R.string.text_no_accessibility_permission_to_capture,
                Toast.LENGTH_SHORT
            ).show()
            AccessibilityServiceTool.goToAccessibilitySetting()
            return
        }
        val progress = DialogUtils.showDialog(
            ThemeColorMaterialDialogBuilder(mContext)
                .content(R.string.text_layout_inspector_is_dumping)
                .canceledOnTouchOutside(false)
                .progress(true, 0)
                .build()
        )
        mCaptureDeferred?.promise()
            ?.then({ capture ->
                mActionViewIcon?.post {
                    if (!progress.isCancelled) {
                        progress.dismiss()
                        windowCreator.invoke(capture)?.let { FloatyService.addWindow(it) }
                    }
                }
            }) { mActionViewIcon?.post { progress.dismiss() } }
    }

    @Optional
    @OnClick(R.id.stop_all_scripts)
    fun stopAllScripts() {
        mWindow?.collapse()
        AutoJs.getInstance().scriptEngineService.stopAllAndToast()
    }

    override fun onCaptureAvailable(capture: NodeInfo?) {
        if (mCaptureDeferred != null && mCaptureDeferred!!.isPending) mCaptureDeferred!!.resolve(
            capture
        )
    }

    @Optional
    @OnClick(R.id.settings)
    fun settings() {
        mWindow?.collapse()
        mRunningPackage = AutoJs.getInstance().infoProvider.getLatestPackageByUsageStatsIfGranted()
        mRunningActivity = AutoJs.getInstance().infoProvider.latestActivity
        mSettingsDialog = OperationDialogBuilder(mContext)
            .item(
                R.id.accessibility_service,
                R.drawable.ic_settings,
                R.string.text_accessibility_settings
            )
            .item(
                R.id.package_name, R.drawable.ic_android_fill,
                mContext.getString(R.string.text_current_package) + mRunningPackage
            )
            .item(
                R.id.class_name, R.drawable.ic_window,
                mContext.getString(R.string.text_current_activity) + mRunningActivity
            )
            .item(
                R.id.open_launcher,
                R.drawable.ic_home_light,
                R.string.text_open_main_activity
            )
            .item(
                R.id.pointer_location,
                R.drawable.ic_coordinate,
                R.string.text_pointer_location
            )
            .item(R.id.exit, R.drawable.ic_close, R.string.text_exit_floating_window)
            .bindItemClick(this)
            .title(R.string.text_more)
            .build()
        DialogUtils.showDialog(mSettingsDialog)
    }

    @Optional
    @OnClick(R.id.accessibility_service)
    fun enableAccessibilityService() {
        dismissSettingsDialog()
        AccessibilityServiceTool.enableAccessibilityService()
    }

    private fun dismissSettingsDialog() {
        mSettingsDialog?.dismiss()
        mSettingsDialog = null
    }

    @Optional
    @OnClick(R.id.package_name)
    fun copyPackageName() {
        dismissSettingsDialog()
        if (TextUtils.isEmpty(mRunningPackage)) return
        ClipboardUtil.setClip(mContext, mRunningPackage)
        Toast.makeText(mContext, R.string.text_already_copy_to_clip, Toast.LENGTH_SHORT).show()
    }

    @Optional
    @OnClick(R.id.class_name)
    fun copyActivityName() {
        dismissSettingsDialog()
        if (TextUtils.isEmpty(mRunningActivity)) return
        ClipboardUtil.setClip(mContext, mRunningActivity)
        Toast.makeText(mContext, R.string.text_already_copy_to_clip, Toast.LENGTH_SHORT).show()
    }

    @Optional
    @OnClick(R.id.open_launcher)
    fun openLauncher() {
        dismissSettingsDialog()
        val intent = Intent(mContext, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        mContext.startActivity(intent)
    }

    @Optional
    @OnClick(R.id.pointer_location)
    fun togglePointerLocation() {
        dismissSettingsDialog()
        RootTool.togglePointerLocation()
    }

    @Optional
    @OnClick(R.id.exit)
    fun close() {
        dismissSettingsDialog()
        try {
            mWindow?.close()
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        } finally {
            EventBus.getDefault().post(StateChangeEvent(STATE_CLOSED, mState))
            mState = STATE_CLOSED
        }
//        mRecorder.removeOnStateChangedListener(this)
        AutoJs.getInstance().layoutInspector.removeCaptureAvailableListener(this)
    }

    companion object {
        const val STATE_CLOSED = -1
        const val STATE_NORMAL = 0
        const val STATE_RECORDING = 1
        private const val IC_ACTION_VIEW = R.drawable.ic_android_eat_js
    }

    init {
        mContext = ContextThemeWrapper(context, R.style.AppTheme)
        initFloaty()
        setupListeners()
        AutoJs.getInstance().layoutInspector.addCaptureAvailableListener(this)
    }
}