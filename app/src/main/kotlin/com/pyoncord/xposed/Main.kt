package com.pyoncord.xposed

import java.io.File
import java.lang.Exception
import java.lang.System
import kotlinx.coroutines.*
import android.app.Activity
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.XModuleResources
import android.content.res.Resources
import android.util.Log
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.engine.cio.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

class Main : IXposedHookZygoteInit, IXposedHookLoadPackage {
    companion object {
        const val LOG_TAG = "Pyoncord"
        const val DEFAULT_BUNDLE_ENDPOINT = "https://raw.githubusercontent.com/pyoncord/pyoncord/builds/pyoncord.js"
    }

    private lateinit var resources: XModuleResources
    private lateinit var appActivity: Activity

    // Assign module resources in process zygote
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        resources = XModuleResources.createInstance(startupParam.modulePath, null)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) = with(lpparam) {
        val reactActivity = runCatching {
            classLoader.loadClass("com.discord.react_activities.ReactActivity")
        }.getOrElse { return@with } // Package is our the target app, return

        XposedBridge.hookMethod(reactActivity.getDeclaredMethod("onCreate", Bundle::class.java), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                appActivity = param.thisObject as Activity
                
                init(lpparam)
            }
        })
    }

    fun restartApp() = with(appActivity.applicationContext) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)!!

        appActivity.startActivity(Intent.makeRestartActivityTask(intent.getComponent()))
        System.exit(0)
    }

    suspend fun checkForUpdate(bundle: File, etag: File): Unit = withContext(Dispatchers.IO) {
        val client = HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 1000 }
            install(UserAgent) { agent = "PyoncordXposed" }
        }

        try {
            val response: HttpResponse = client.get(DEFAULT_BUNDLE_ENDPOINT) {
                headers { 
                    if (etag.exists() && bundle.exists()) {
                        append(HttpHeaders.IfNoneMatch, etag.readText()) 
                    }
                }
            }

            if (response.status == HttpStatusCode.OK) {
                Log.i(LOG_TAG, "New bundle found, updating and restarting...")

                bundle.writeBytes(response.body())
                response.headers["Etag"]?.let { etag.writeText(it) }
                restartApp()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "An error occured while downloading pyoncord bundle.", e)
        } finally {
            client.close()
        }
    }

    fun init(param: XC_LoadPackage.LoadPackageParam) = with(param) {
        val catalystInstance = classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl") 

        val files = File(appInfo.dataDir, "files").also { it.mkdirs() }
        val pyoncordFd = File(files, "pyoncord").also { it.mkdirs() }

        val bundle = File(pyoncordFd, "pyoncord.js")
        val etag = File(pyoncordFd, "etag")

        MainScope().launch() { 
            checkForUpdate(bundle, etag) 
        }

        val loadScriptFromAssets = catalystInstance.getDeclaredMethod("jniLoadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        val loadScriptFromFile = catalystInstance.getDeclaredMethod("jniLoadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)

        // Mirror resource resolver to "com.discord" if the package name is renamed
        if (packageName != "com.discord") {
            val getIdentifier = Resources::class.java.getDeclaredMethod("getIdentifier", String::class.java, String::class.java, String::class.java)
            
            XposedBridge.hookMethod(getIdentifier, object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[2] == packageName) param.args[2] = "com.discord"
                }
            })
        }

        // TODO: Loader config
        val hook = object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = with(param) {
                // Load pre-patches and identity
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, thisObject, arrayOf(resources.assets, "assets://js/modules.js", true))
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, thisObject, arrayOf(resources.assets, "assets://js/identity.js", true))
                
                // Invoke the original method
                XposedBridge.invokeOriginalMethod(method, thisObject, args)

                // Execute our bundle
                if (bundle.exists()) XposedBridge.invokeOriginalMethod(
                    loadScriptFromFile, 
                    thisObject, 
                    arrayOf(bundle.absolutePath, bundle.absolutePath, args[2])
                )

                // Prevent from invoking the original method
                setResult(null)
            }
        }

        XposedBridge.hookMethod(loadScriptFromAssets, hook)
        XposedBridge.hookMethod(loadScriptFromFile, hook)

        return@with
    }
}