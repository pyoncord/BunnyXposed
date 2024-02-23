package com.pyoncord.xposed

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.XModuleResources
import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.annotation.SuppressLint
import android.graphics.fonts.Font
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File
import java.lang.Exception
import java.lang.System
import java.util.HashMap

class Main : IXposedHookZygoteInit, IXposedHookLoadPackage {
    companion object {
        const val LOG_TAG = "Pyoncord"
        const val DEFAULT_BUNDLE_ENDPOINT = "https://raw.githubusercontent.com/pyoncord/pyoncord/builds/pyoncord.js"

        val PYONCORD_DIR = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "pyoncord").apply { mkdirs() }

        val CACHE_DIR = File(PYONCORD_DIR, "cache").apply { mkdir() }
        val DRAWABLES_DIR = File(PYONCORD_DIR, "drawables").apply { mkdir() }
        
        val bundle = File(PYONCORD_DIR, "pyoncord.js")
        val etag = File(PYONCORD_DIR, "etag")

        val loaderPayloadJson = buildJsonObject {
            put("loader", "PyoncordXposed")
        }
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

    fun createEvalFile(code: String, identifier: String) = File(CACHE_DIR, "$identifier.js").apply { writeText(code) }

    fun restartApp() = with(appActivity.applicationContext) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)!!

        appActivity.startActivity(Intent.makeRestartActivityTask(intent.component))
        System.exit(0)
    }

    suspend fun checkForUpdate(): Unit = withContext(Dispatchers.IO) {
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

    @SuppressLint("NewApi")
    fun init(param: XC_LoadPackage.LoadPackageParam) = with(param) {
        val catalystInstance = classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")

        MainScope().launch() { 
            checkForUpdate() 
        }

        runCatching {
            // Fight package renaming side effects
            if (packageName != "com.discord") {
                val getIdentifier = Resources::class.java.getDeclaredMethod("getIdentifier", String::class.java, String::class.java, String::class.java)
                
                XposedBridge.hookMethod(getIdentifier, object: XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[2] == packageName) param.args[2] = "com.discord"
                    }
                })
            }

            // Custom drawables
            val uriCache = mutableMapOf<String, Uri>()
            val resourceDrawableIdHelper = classLoader.loadClass("com.facebook.react.views.imagehelper.ResourceDrawableIdHelper")
            
            XposedBridge.hookMethod(resourceDrawableIdHelper.getDeclaredMethod("getResourceDrawableUri", Context::class.java, String::class.java), object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args[1] as? String ?: return

                    val uri = uriCache.getOrPut(name) {
                        File(DRAWABLES_DIR, "$name.png").takeIf { it.exists() }?.let { 
                            Uri.fromFile(it) 
                        } ?: Uri.EMPTY
                    }

                    if (uri != Uri.EMPTY) param.result = uri
                }
            })
        }

        val loadScriptFromAssets = catalystInstance.getDeclaredMethod("jniLoadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        val loadScriptFromFile = catalystInstance.getDeclaredMethod("jniLoadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)

        // TODO: Loader config
        val hook = object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = with(param) {
                val payloadFile = createEvalFile("PYONCORD_LOADER_PAYLOAD=${Json.encodeToString(loaderPayloadJson)}", "payload")
                
                // Load pre-patches and payload
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, thisObject, arrayOf(resources.assets, "assets://js/preinit.js", true))
                XposedBridge.invokeOriginalMethod(
                    loadScriptFromFile,
                    thisObject,
                    arrayOf(payloadFile.absolutePath, "pyoncord-payload", true)
                )
                
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