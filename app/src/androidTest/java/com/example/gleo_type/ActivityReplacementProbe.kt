package com.example.gleo_type

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Wait for real lifecycle completion, not global queue idleness under live media callbacks. */
internal fun replaceActivity(activity:MainActivity):MainActivity {
    val i=InstrumentationRegistry.getInstrumentation();val monitor=ActivityLifecycleMonitorRegistry.getInstance()
    val result=AtomicReference<MainActivity>();val ready=CountDownLatch(1)
    val callback=ActivityLifecycleCallback{a,s->if(a is MainActivity && a!==activity && s==Stage.RESUMED){result.set(a);ready.countDown()}}
    i.runOnMainSync{monitor.addLifecycleCallback(callback)}
    try {
        i.runOnMainSync{activity.recreate()}
        assertTrue("replacement Activity RESUMED",ready.await(45,TimeUnit.SECONDS))
        return result.get()
    } finally {i.runOnMainSync{monitor.removeLifecycleCallback(callback)}}
}

internal fun finishActivity(activity:MainActivity) {
    val i=InstrumentationRegistry.getInstrumentation();val monitor=ActivityLifecycleMonitorRegistry.getInstance()
    val ended=CountDownLatch(1)
    val callback=ActivityLifecycleCallback{a,s->if(a===activity && s==Stage.DESTROYED)ended.countDown()}
    i.runOnMainSync{monitor.addLifecycleCallback(callback)}
    try {
        i.runOnMainSync{if(activity.isDestroyed)ended.countDown() else activity.finish()}
        assertTrue("Activity DESTROYED",ended.await(45,TimeUnit.SECONDS))
    } finally {i.runOnMainSync{monitor.removeLifecycleCallback(callback)}}
}
