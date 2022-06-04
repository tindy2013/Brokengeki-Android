package com.github.brokengeki.activity

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import com.github.brokengeki.BrokengekiApplication
import com.github.brokengeki.R
import com.github.brokengeki.util.*
import net.cachapa.expandablelayout.ExpandableLayout
import java.net.*
import java.nio.ByteBuffer
import java.util.*
import kotlin.collections.ArrayList
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var senderTask: AsyncTaskUtil.AsyncTask<InetSocketAddress?, Unit, Unit>
    private lateinit var receiverTask: AsyncTaskUtil.AsyncTask<InetSocketAddress?, Unit, Unit>
    private lateinit var pingPongTask: AsyncTaskUtil.AsyncTask<Unit, Unit, Unit>
    private var mExitFlag = true
    private lateinit var app: BrokengekiApplication
    private val serverPort = 53468

    // TCP
    private var mTCPMode = false
    private var mTCPSocket: Socket? = null

    // state
    private var mCurrentDelay = 0f

    // Buttons
    private var mCurrentLeverProgress = 0f
    private var mLastButtons = ByteArray(15)
    private class InputEvent(val keys: ByteArray, val lever : Float = 0f)

    // vibrator
    private lateinit var vibrator: Vibrator
    private lateinit var vibratorTask: AsyncTaskUtil.AsyncTask<Unit, Unit, Unit>
    private lateinit var vibrateMethod: (Long) -> Unit
    private val vibrateLength = 50L
    private val mVibrationQueue = ArrayDeque<Long>()

    // sensor
    private val sensorSamplingPeriodUs = 10000
    private var mEnableGyroLever = true
    private var mSensorManager: SensorManager? = null
    private var mRotateAngle = 0f
    private var rotateSensitivity = 3f
    private var mSensorCallback: ((Float) -> Unit)? = null
    private val listener = object : SensorEventListener {
        val gData = FloatArray(3)
        val mData = FloatArray(3)
        var haveGravity = false
        var haveAccelerometer = false
        var haveMag = false

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            return
        }

        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            when(event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    val rotationVector = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationVector, event.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotationVector, orientation)
                    mRotateAngle = orientation[1]
                    mSensorCallback?.invoke(orientation[1])
                    return
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    if (!haveGravity) {
                        event.values.copyInto(gData, 0, 0, 3)
                        haveAccelerometer = true
                    }
                }
                Sensor.TYPE_GRAVITY -> {
                    event.values.copyInto(gData, 0, 0, 3)
                    haveGravity = true
                }
                Sensor.TYPE_MAGNETIC_FIELD ->{
                    event.values.copyInto(mData, 0, 0, 3)
                    haveMag = true
                }
            }
            if ((haveGravity || haveAccelerometer) && haveMag) {
                val matrixR = FloatArray(9)
                val matrixI = FloatArray(9)
                val rotate2 = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrix(matrixR, matrixI, gData, mData)
                SensorManager.remapCoordinateSystem(matrixR, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, rotate2)
                SensorManager.getOrientation(rotate2, orientation)
                mRotateAngle = orientation[1]
                mSensorCallback?.invoke(orientation[1])
            }
        }
    }

    // view
    //private var mDebugInfo = false
    private var mCenterButton = false
    private var mShowDelay = false
    private var mEnableVibrate = true
    private lateinit var mDelayText: TextView
    private var mLastTouchedButtons = mutableSetOf<MU3Button>()
    // map center button to both left and right
    private val buttonMapping = multiMapOf(
        R.id.button_test to MU3Button.Test,
        R.id.button_service to MU3Button.Service,
        R.id.button_left_menu to MU3Button.LeftMenu,
        R.id.button_left_wall to MU3Button.LeftWall,
        R.id.button_left_1 to MU3Button.Left1,
        R.id.button_left_2 to MU3Button.Left2,
        R.id.button_left_3 to MU3Button.Left3,
        R.id.button_right_1 to MU3Button.Right1,
        R.id.button_right_2 to MU3Button.Right2,
        R.id.button_right_3 to MU3Button.Right3,
        R.id.button_right_wall to MU3Button.RightWall,
        R.id.button_right_menu to MU3Button.RightMenu,
        R.id.button_center_left_wall to MU3Button.LeftWall,
        R.id.button_center_1 to MU3Button.Left1,
        R.id.button_center_2 to MU3Button.Left2,
        R.id.button_center_3 to MU3Button.Left3,
        R.id.button_center_1 to MU3Button.Right1,
        R.id.button_center_2 to MU3Button.Right2,
        R.id.button_center_3 to MU3Button.Right3,
        R.id.button_center_right_wall to MU3Button.RightWall
    )
    private val leftTolerateIds = listOf(
        R.id.button_center_2, R.id.button_center_3,
    )
    private val rightTolerateIds = listOf(
        R.id.button_center_1, R.id.button_center_2,
    )
    private val topTolerateIds = listOf(
        R.id.button_center_1, R.id.button_center_3,
    )
    private val bottomTolerateIds = listOf(
        R.id.button_center_left_wall, R.id.button_center_right_wall,
    )
    private val fullButtonLEDMapping = mapOf(
        0 to R.id.button_left_1,
        1 to R.id.button_left_2,
        2 to R.id.button_left_3,
        3 to R.id.button_right_1,
        4 to R.id.button_right_2,
        5 to R.id.button_right_3
    )
    private val centerButtonLEDMapping = mapOf(
        0 to -1,
        1 to -1,
        2 to -1,
        3 to R.id.button_center_1,
        4 to R.id.button_center_2,
        5 to R.id.button_center_3
    )
    private val fullButtons = ArrayList<View?>()
    private val centerButtons = ArrayList<View?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setImmersive()
        app = application as BrokengekiApplication
        vibrator = applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        vibrateMethod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            {
                vibrator.vibrate(VibrationEffect.createOneShot(it, 255))
            }
        } else {
            {
                vibrator.vibrate(it)
            }
        }

        mDelayText = findViewById(R.id.text_delay)
        
        findViewById<CheckBox>(R.id.check_vibrate).apply {
            setOnCheckedChangeListener { _, isChecked ->
                mEnableVibrate = isChecked
                app.enableVibrate = isChecked
            }
            isChecked = app.enableVibrate
        }

        val expandControl = findViewById<ExpandableLayout>(R.id.expand_control)
        val textExpand = findViewById<TextView>(R.id.text_expand)
        textExpand.setOnClickListener {
            if (expandControl.isExpanded) {
                (it as TextView).setText(R.string.expand)
                expandControl.collapse()
            } else {
                (it as TextView).setText(R.string.collapse)
                expandControl.expand()
            }
        }

        val editServer = findViewById<EditText>(R.id.edit_server).apply {
            setText(app.lastServer)
        }
        findViewById<Button>(R.id.button_start).setOnClickListener {
            val server = editServer.text.toString()
            if (server.isBlank())
                return@setOnClickListener
            if (mExitFlag) {
                if (senderTask.isActive || receiverTask.isActive)
                    return@setOnClickListener
                mExitFlag = false
                (it as Button).setText(R.string.stop)
                editServer.isEnabled = false

                app.lastServer = server
                val address = parseAddress(server)
                if (!mTCPMode)
                    sendConnect(address)
                currentPacketId = 1
                senderTask.execute(lifecycleScope, address)
                receiverTask.execute(lifecycleScope, address)
                pingPongTask.execute(lifecycleScope)
            } else {
                sendDisconnect(parseAddress(server))
                mExitFlag = true
                (it as Button).setText(R.string.start)
                editServer.isEnabled = true
                senderTask.cancel()
                receiverTask.cancel()
                pingPongTask.cancel()
            }
        }

        findViewById<Button>(R.id.button_coin).setOnClickListener {
            if(!mExitFlag)
                sendFunctionKey(parseAddress(editServer.text.toString()),
                    FunctionButton.FUNCTION_COIN
                )
        }
        findViewById<Button>(R.id.button_card).setOnClickListener {
            if(!mExitFlag)
                sendFunctionKey(parseAddress(editServer.text.toString()),
                    FunctionButton.FUNCTION_CARD
                )
        }

        val checkButtonPressed = checkButtonPressed@{ view: View, event: MotionEvent -> Boolean
            //Log.d("button", event.toString())
            if (!buttonMapping.containsKey(view.id))
                return@checkButtonPressed true
            val buttons = buttonMapping[view.id]
            for (button in buttons) {
                //Log.d("button", "button $button event $event")
                mLastButtons[button.ordinal] = when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        mVibrationQueue.add(vibrateLength)
                        0x01
                    }
                    MotionEvent.ACTION_MOVE -> 0x01
                    else -> 0x00
                }
            }
            if (event.action == MotionEvent.ACTION_UP)
                view.performClick()
            true
        }
        findViewById<View>(R.id.button_left_menu).setOnTouchListener(checkButtonPressed)
        findViewById<View>(R.id.button_right_menu).setOnTouchListener(checkButtonPressed)
        findViewById<View>(R.id.button_test).setOnTouchListener(checkButtonPressed)
        findViewById<View>(R.id.button_service).setOnTouchListener(checkButtonPressed)
        /*for (id in buttonMapping.keys) {
            findViewById<View>(id).setOnTouchListener(checkButtonPressed)
        }*/
        val checkGroupButtonPressed = checkButtonPressed@{ view: View, event: MotionEvent -> Boolean
            if (expandControl.isExpanded)
                textExpand.callOnClick()
            if (view !is ViewGroup)
                return@checkButtonPressed view.performClick()
            val currentButtons = LinkedHashSet(mLastTouchedButtons)
            //if (event.action != KeyEvent.ACTION_UP && event.action != MotionEvent.ACTION_CANCEL) {

            //}
            val ignoredIndex = when(event.actionMasked) {
                MotionEvent.ACTION_POINTER_UP -> event.actionIndex
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0
                else -> -1
            }

            val hasButtons = mutableSetOf<Int>()
            val touchedButtons = mutableSetOf<Int>()
            val allChildRect = mutableMapOf<Int, Rect>()
            var coords = IntArray(2)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                view.getLocationOnScreen(coords)
                // Log.d("030-screen offset", "x: " + coords[0] + ", y:" + coords[1]) // bramble: 148, 984
            }
            getRect(view, allChildRect)
            for (child in allChildRect) {
                if (!buttonMapping.containsKey(child.key))
                    continue
                hasButtons.add(child.key)
                val rect = child.value
                val widthTolerance = child.value.width() / 6f
                val heightTolerance = child.value.height() / 6f
                val widthSpan = (rect.left.toFloat() - if (leftTolerateIds.contains(child.key)) widthTolerance else 0f)..(rect.right.toFloat() + if (rightTolerateIds.contains(child.key)) widthTolerance else 0f)
                val heightSpan = (rect.top.toFloat() - if (topTolerateIds.contains(child.key)) heightTolerance else 0f)..(rect.bottom.toFloat() + if (bottomTolerateIds.contains(child.key)) heightTolerance else 0f)
                for (point in 0 until event.pointerCount) {
                    val x = event.getX(point) + view.left + coords[0]
                    val y = event.getY(point) + view.top
                    if (x in widthSpan && y in heightSpan && point != ignoredIndex) {
                        // Log.d("030-accepted", "x: " + x + ", start: " + widthSpan.start + ", end: " + widthSpan.endInclusive)
                        touchedButtons.add(child.key)
                    }
                }
            }
            for (button in hasButtons) {
                val mappedButtons = buttonMapping[button]
                if (touchedButtons.contains(button))
                    currentButtons.addAll(mappedButtons)
                else
                    currentButtons.removeAll(mappedButtons)
            }
            for (value in buttonMapping.values) {
                mLastButtons[value.ordinal] = if (currentButtons.contains(value)) {
                    //Log.d("button", "key $value down")
                    0x01
                } else 0x00
            }
            if (hasNewKeys(mLastTouchedButtons, currentButtons)) {
                mVibrationQueue.add(vibrateLength)
            } else {
                mVibrationQueue.clear()
            }
            mLastTouchedButtons = currentButtons
            true
        }
        findViewById<ViewGroup>(R.id.button_full_group).setOnTouchListener(checkGroupButtonPressed)
        //findViewById<ViewGroup>(R.id.button_center_bottom_group).setOnTouchListener(checkGroupButtonPressed)
        //findViewById<ViewGroup>(R.id.button_center_wall_group).setOnTouchListener(checkGroupButtonPressed)
        findViewById<ViewGroup>(R.id.button_center_group).setOnTouchListener(checkGroupButtonPressed)

        val seekBar = findViewById<SeekBar>(R.id.seekbar_lever)
        val centerValue = seekBar.max / 2
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (expandControl.isExpanded)
                    textExpand.callOnClick()
                mVibrationQueue.add(vibrateLength)
                return
            }

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                mCurrentLeverProgress = (progress - centerValue) / centerValue.toFloat()
                if (abs(mCurrentLeverProgress) >= 1.0)
                    mVibrationQueue.add(vibrateLength)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                //mCurrentLeverProgress = 0f
                //seekBar!!.progress = centerValue
            }
        })
        mEnableGyroLever = app.enableGyroLever
        findViewById<CheckBox>(R.id.check_gyro_lever).apply {
            isChecked = mEnableGyroLever
            setOnCheckedChangeListener { _, isChecked ->
                mEnableGyroLever = isChecked
                app.enableGyroLever = mEnableGyroLever
            }
        }
        mSensorCallback = {
            if (mEnableGyroLever) {
                val current = it * rotateSensitivity
                val value = if (current > 1.0f) 1.0f else if (current < -1.0f) -1.0f else current
                seekBar.progress = (centerValue * -value + centerValue).toInt()
            }
        }

        findViewById<CheckBox>(R.id.check_show_delay).apply {
            setOnCheckedChangeListener { _, isChecked ->
                mShowDelay = isChecked
                mDelayText.visibility = if (isChecked) View.VISIBLE else View.GONE
                app.showDelay = isChecked
            }
            isChecked = app.showDelay
        }

        mTCPMode = app.tcpMode
        findViewById<TextView>(R.id.text_mode).apply {
            text = getString(if (mTCPMode) R.string.tcp else R.string.udp)
            setOnClickListener {
                if (!mExitFlag)
                    return@setOnClickListener
                text = getString(if (mTCPMode) {
                    mTCPMode = false
                    R.string.udp
                } else {
                    mTCPMode = true
                    R.string.tcp
                })
                app.tcpMode = mTCPMode
            }
        }

        val fullButtonGroup = findViewById<ViewGroup>(R.id.button_full_group)
        val centerButtonGroup = findViewById<ViewGroup>(R.id.button_center_group)
        //val centerWallButtonGroup = findViewById<ViewGroup>(R.id.button_center_wall_group)
        val leftMenuButton = findViewById<View>(R.id.button_left_menu)
        val rightMenuButton = findViewById<View>(R.id.button_right_menu)
        findViewById<TextView>(R.id.toggle_center_button).setOnClickListener {
            mCenterButton = if (mCenterButton) {
                centerButtonGroup.visibility = View.GONE
                //centerWallButtonGroup.visibility = View.GONE
                fullButtonGroup.visibility = View.VISIBLE
                leftMenuButton.visibility = View.VISIBLE
                rightMenuButton.visibility = View.VISIBLE
                (it as TextView).text = getString(R.string.center_button)
                false
            } else {
                centerButtonGroup.visibility = View.VISIBLE
                //centerWallButtonGroup.visibility = View.VISIBLE
                fullButtonGroup.visibility = View.GONE
                leftMenuButton.visibility = View.GONE
                rightMenuButton.visibility = View.GONE
                (it as TextView).text = getString(R.string.full_button)
                true
            }
        }
        initTasks()
        for (entry in fullButtonLEDMapping) {
            fullButtons.add(if (entry.value != -1) findViewById<View>(entry.value) else null)
        }
        for (entry in centerButtonLEDMapping) {
            centerButtons.add(if (entry.value != -1) findViewById<View>(entry.value) else null)
        }

        vibratorTask.execute(lifecycleScope)
    }

    private val cachedChildRect = mutableMapOf<Int, Rect>()
    private fun getRect(view: View, allRect: MutableMap<Int, Rect>) {
        val rect = if (cachedChildRect.containsKey(view.id))
            cachedChildRect[view.id]!!
        else {
            val pos = IntArray(2)
            view.getLocationOnScreen(pos)
            val rect = Rect(pos[0], pos[1], pos[0] + view.width, pos[1] + view.height)
            cachedChildRect[view.id] = rect
            rect
        }
        allRect[view.id] = rect
        return
    }
    private fun getRect(viewGroup: ViewGroup, allChildRect: MutableMap<Int, Rect>) {
        getRect(viewGroup as View, allChildRect)
        for (child in viewGroup.children) {
            if (child is ViewGroup)
                getRect(child, allChildRect)
            else
                getRect(child, allChildRect)
        }
    }

    override fun onResume() {
        super.onResume()
        if (mSensorManager == null)
            mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = mSensorManager?.getDefaultSensor(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) Sensor.TYPE_GAME_ROTATION_VECTOR else Sensor.TYPE_ROTATION_VECTOR)
        if (sensor != null)
            mSensorManager?.registerListener(listener, sensor, sensorSamplingPeriodUs)
        else {
            val gravity = mSensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            val accelerator = mSensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val magnetic = mSensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            mSensorManager?.registerListener(listener, gravity, sensorSamplingPeriodUs)
            mSensorManager?.registerListener(listener, accelerator, sensorSamplingPeriodUs)
            mSensorManager?.registerListener(listener, magnetic, sensorSamplingPeriodUs)
        }
    }

    override fun onPause() {
        super.onPause()
        mSensorManager?.unregisterListener(listener)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus)
            setImmersive()
    }

    private var exitTime: Long = 0

    override fun onBackPressed() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - exitTime > 1500) {
            Toast.makeText(this, R.string.press_again_to_exit, Toast.LENGTH_SHORT).show()
            exitTime = currentTime
        } else {
            finish()
        }
    }

    private fun setImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun hasNewKeys(oldKeys: MutableSet<*>, newKeys: MutableSet<*>): Boolean {
        for (i in newKeys)
            if (!oldKeys.contains(i)) return true
        return false
    }

    private fun parseAddress(address: String): InetSocketAddress? {
        val parts = address.split(":")
        return when(parts.size) {
            1 -> InetSocketAddress(parts[0], serverPort)
            2 -> InetSocketAddress(parts[0], parts[1].toInt())
            else -> null
        }
    }

    private fun initTasks() {
        receiverTask = AsyncTaskUtil.AsyncTask.make(
            doInBackground = {
                val address = it[0] ?: return@make
                if (mTCPMode) {
                    val buffer = ByteArray(256)
                    while (!mExitFlag) {
                        if (mTCPSocket == null || mTCPSocket?.isConnected != true || mTCPSocket?.isClosed == true) {
                            Thread.sleep(50)
                            continue
                        }
                        try {
                            val dataSize = mTCPSocket?.getInputStream()?.read(buffer, 0, 256) ?: continue
                            if (dataSize >= 3) {
                                if (dataSize >= 22 && buffer[1] == 'L'.toByte() && buffer[2] == 'E'.toByte() && buffer[3] == 'D'.toByte()) {
                                    setLED(buffer)
                                }
                                if (dataSize >= 4 && buffer[1] == 'P'.toByte() && buffer[2] == 'O'.toByte() && buffer[3] == 'N'.toByte()) {
                                    val delay = calculateDelay(buffer)
                                    if (delay > 0f)
                                        mCurrentDelay = delay
                                }
                            }
                        } catch (e: SocketException) {
                            e.printStackTrace()
                            break
                        }
                    }
                } else {
                    val socket = try {
                        DatagramSocket(serverPort).apply {
                            reuseAddress = true
                            soTimeout = 1000
                        }
                    } catch (e: BindException) {
                        e.printStackTrace()
                        return@make
                    }
                    val buffer = ByteArray(256)
                    val packet = DatagramPacket(buffer, buffer.size)
                    fun InetSocketAddress.toHostString(): String? {
                        if (hostName != null)
                            return hostName
                        if (this.address != null)
                            return this.address.hostName ?: this.address.hostAddress
                        return null
                    }
                    while (!mExitFlag) {
                        try {
                            socket.receive(packet)
                            if (packet.address.hostAddress == address.toHostString() && packet.port == address.port) {
                                val data = packet.data
                                if (data.size >= 3) {
                                    if (data.size >= 22 && data[1] == 'L'.toByte() && data[2] == 'E'.toByte() && data[3] == 'D'.toByte()) {
                                        setLED(data)
                                    }
                                    if (data.size >= 4 && data[1] == 'P'.toByte() && data[2] == 'O'.toByte() && data[3] == 'N'.toByte()) {
                                        val delay = calculateDelay(data)
                                        if (delay > 0f)
                                            mCurrentDelay = delay
                                    }
                                }
                            }
                        } catch (e: SocketTimeoutException) {
                            // ignore, try again
                        }
                    }
                    socket.close()
                }
            }
        )
        senderTask = AsyncTaskUtil.AsyncTask.make(
            doInBackground = {
                val address = it[0] ?: return@make
                if (mTCPMode) {
                    try {
                        mTCPSocket = Socket().apply {
                            tcpNoDelay = true
                        }
                        mTCPSocket?.connect(address)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        return@make
                    }
                    while (!mExitFlag) {
                        if (mShowDelay)
                            sendTCPPing()
                        val buttons = InputEvent(mLastButtons, mCurrentLeverProgress)
                        val buffer = applyKeys(buttons, IoBuffer())
                        try {
                            mTCPSocket?.getOutputStream()?.write(constructBuffer(buffer))
                        } catch (e: Exception) {
                            e.printStackTrace()
                            break
                        }
                        Thread.sleep(1)
                    }
                } else {
                    val socket = DatagramSocket()
                    socket.connect(address)
                    while (!mExitFlag) {
                        if (mShowDelay)
                            sendPing(address)
                        //while (!mInputQueue.isEmpty() && mInputQueue.peek() == null)
                        //mInputQueue.pop()
                        //val buttons = mInputQueue.poll()
                        val buttons = InputEvent(mLastButtons.clone(), mCurrentLeverProgress)
                        if (buttons != null/* || mLastAirHeight != mCurrentAirHeight*/) {
                            val buffer = applyKeys(buttons/* ?: InputEvent()*/, IoBuffer())
                            val packet = constructPacket(buffer)
                            try {
                                socket.send(packet)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                Thread.sleep(100)
                                continue
                            }
                        }
                        Thread.sleep(1)
                    }
                    socket.close()
                }
            }
        )
        pingPongTask = AsyncTaskUtil.AsyncTask.make(
            doInBackground = {
                while (!mExitFlag) {
                    if (!mShowDelay) {
                        Thread.sleep(250)
                        continue
                    }
                    if (mCurrentDelay >= 0f) {
                        runOnUiThread { mDelayText.text = getString(R.string.current_latency, mCurrentDelay) }
                    }
                    Thread.sleep(200)
                }
            }
        )
        vibratorTask = AsyncTaskUtil.AsyncTask.make(
            doInBackground = {
                while (true) {
                    if (!mEnableVibrate) {
                        Thread.sleep(250)
                        continue
                    }
                    val next = mVibrationQueue.poll()
                    if (next != null)
                        vibrateMethod(next)
                    Thread.sleep(10)
                }
            }
        )
    }

    enum class FunctionButton {
        UNDEFINED, FUNCTION_COIN, FUNCTION_CARD
    }

    enum class MU3Button {
        Test, Service, Push0, Push1, LeftWall, Left1, Left2, Left3, Right1, Right2, Right3, RightWall, RightMenu, LeftMenu
    }

    class IoBuffer {
        var length: Int = 0
        var header = ByteArray(3)
        var buttons = ByteArray(15)
        var lever: Float = 0f
    }

    private fun getLocalIPAddress(useIPv4: Boolean): ByteArray {
        try {
            for (intf in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.address
                        if (useIPv4) {
                            if (addr is Inet4Address) return sAddr
                        } else {
                            if (addr is Inet6Address) return sAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        return byteArrayOf()
    }

    private fun sendConnect(address: InetSocketAddress?) {
        address ?: return
        thread {
            val selfAddress = getLocalIPAddress(true)
            if (selfAddress.isEmpty()) return@thread
            val buffer = ByteArray(21)
            byteArrayOf('C'.toByte(), 'O'.toByte(), 'N'.toByte()).copyInto(buffer, 1)
            ByteBuffer.wrap(buffer)
                    .put(4, if (selfAddress.size == 4) 1.toByte() else 2.toByte())
                    .putShort(5, serverPort.toShort())
            selfAddress.copyInto(buffer, 7)
            buffer[0] = (3 + 1 + 2 + selfAddress.size).toByte()
            try {
                val socket = DatagramSocket()
                val packet = DatagramPacket(buffer, buffer.size)
                socket.apply {
                    connect(address)
                    send(packet)
                    close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun sendDisconnect(address: InetSocketAddress?) {
        address ?: return
        thread {
            val buffer = byteArrayOf(3, 'D'.toByte(), 'I'.toByte(), 'S'.toByte())
            if (mTCPMode) {
                try {
                    mTCPSocket?.getOutputStream()?.write(buffer)
                    mTCPSocket?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                try {
                    val socket = DatagramSocket()
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.apply {
                        connect(address)
                        send(packet)
                        close()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun sendFunctionKey(address: InetSocketAddress?, function: FunctionButton) {
        address ?: return
        thread {
            val buffer = byteArrayOf(4, 'F'.toByte(), 'N'.toByte(), 'C'.toByte(), function.ordinal.toByte())
            if (mTCPMode) {
                try {
                    mTCPSocket?.getOutputStream()?.write(buffer)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                try {
                    val socket = DatagramSocket()
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.apply {
                        connect(address)
                        send(packet)
                        close()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private val pingInterval = 100L
    private var lastPingTime = 0L
    private fun sendPing(address: InetSocketAddress?) {
        address ?: return
        if (System.currentTimeMillis() - lastPingTime < pingInterval) return
        lastPingTime = System.currentTimeMillis()
        val buffer = ByteArray(12)
        byteArrayOf(11, 'P'.toByte(), 'I'.toByte(), 'N'.toByte()).copyInto(buffer)
        ByteBuffer.wrap(buffer, 4, 8).putLong(SystemClock.elapsedRealtimeNanos())
        try {
            val socket = DatagramSocket()
            val packet = DatagramPacket(buffer, buffer.size)
            socket.apply {
                connect(address)
                send(packet)
                close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendTCPPing() {
        if (System.currentTimeMillis() - lastPingTime < pingInterval) return
        lastPingTime = System.currentTimeMillis()
        val buffer = ByteArray(12)
        byteArrayOf(11, 'P'.toByte(), 'I'.toByte(), 'N'.toByte()).copyInto(buffer)
        ByteBuffer.wrap(buffer).putLong(4, SystemClock.elapsedRealtimeNanos())
        try {
            mTCPSocket?.getOutputStream()?.write(buffer)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun calculateDelay(data: ByteArray): Float {
        val currentTime = SystemClock.elapsedRealtimeNanos()
        val lastPingTime = ByteBuffer.wrap(data).getLong(4)
        return (currentTime - lastPingTime) / 2000000.0f
    }

    private var currentPacketId = 1
    private fun constructBuffer(buffer: IoBuffer): ByteArray {
        val realBuf = ByteArray(27)
        realBuf[0] = buffer.length.toByte()
        buffer.header.copyInto(realBuf, 1)
        ByteBuffer.wrap(realBuf).putInt(4, currentPacketId++)
        buffer.buttons.copyInto(realBuf, 8)
        ByteBuffer.wrap(realBuf).putFloat(23, buffer.lever)
        return realBuf
    }

    private fun constructPacket(buffer: IoBuffer): DatagramPacket {
        val realBuf = constructBuffer(buffer)
        return DatagramPacket(realBuf, buffer.length + 1)
    }

    private fun applyKeys(event: InputEvent, buffer: IoBuffer): IoBuffer {
        return buffer.apply {
            buffer.length = 26
            buffer.header = byteArrayOf('I'.toByte(), 'N'.toByte(), 'P'.toByte())
            event.keys.copyInto(buffer.buttons)
            buffer.lever = event.lever
        }
    }

    private fun setLED(status: ByteArray) {
        //val buttons = if (mCenterButton) centerButtons else fullButtons
        val offset = 4
        for (i in 0 until 6) {
            val index = offset + (i * 3)
            val blue = status[index].toInt() and 0xff
            val red = status[index + 1].toInt() and 0xff
            val green = status[index + 2].toInt() and 0xff
            val color = 0xff000000 or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
            runOnUiThread {
                val background = (if (mCenterButton) centerButtons else fullButtons)[i]?.background
                if (background != null)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                        DrawableCompat.setTint(background, color.toInt())
                    else
                        background.mutate().colorFilter = PorterDuffColorFilter(color.toInt(), PorterDuff.Mode.SRC_IN)
            }
        }
    }
}