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

        val pyoncord = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "pyoncord").apply { mkdirs() }

        val cache = File(pyoncord, "cache").apply { mkdirs() }

        val bundle = File(cache, "pyoncord.js")
        val etag = File(cache, "etag")

        // val fontsDir = File(pyoncord, "fonts")
        val drawablesDir = File(pyoncord, "drawables")
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
            val fileManagerModule = classLoader.loadClass("com.discord.file_manager.FileManagerModule")
            val storageDirsField = fileManagerModule.getDeclaredField("storageDirs").apply { isAccessible = true }

            XposedBridge.hookMethod(
                fileManagerModule.declaredMethods.find { it.name == "readFile" },
                object: XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam): Unit = with(param) {
                        val path = args[0] as? String ?: return

                        if (path.contains("/pyoncord/")) {
                            // We do a little hack :P
                            val actualPath = path.substringAfter("/pyoncord/")
                            args[0] = File(pyoncord, actualPath).absolutePath
                        }
                    }
                }
            )

            XposedBridge.hookMethod(
                fileManagerModule.declaredMethods.find { it.name == "writeFile" },
                object: XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam): Unit = with(param) {
                        val dir = args[0] as? String ?: return
                        val path = args[1] as? String ?: return
                        
                        val storageDirs = storageDirsField.get(thisObject) as HashMap<String, String>
                        if (dir == "documents" && path.contains("pyoncord/")) {
                            val docsDir = File(storageDirs["documents"] as String).toPath()
                            args[1] = docsDir.relativize(File(pyoncord.parentFile, path).toPath()).toString()
                        }
                    }
                }
            )

            // Fight package renaming side effects
            if (packageName != "com.discord") {
                val getIdentifier = Resources::class.java.getDeclaredMethod("getIdentifier", String::class.java, String::class.java, String::class.java)
                
                XposedBridge.hookMethod(getIdentifier, object: XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[2] == packageName) param.args[2] = "com.discord"
                    }
                })
            }

            // Custom fonts
            val fontHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam): Unit = with(param) {
                    File(pyoncord, args[1] as String).takeIf { it.exists() }?.let {
                        Log.d(LOG_TAG, "Overriding font from ${args[1]} to $it")
                        result = if (method.name == "createFromAsset") Typeface.createFromFile(it.absolutePath) else Font.Builder(it)
                    }
                }
            }

            XposedBridge.hookMethod(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Font.Builder::class.java.getDeclaredConstructor(AssetManager::class.java, String::class.java)
                } else {
                    // I don't have any older device to test, so let's just wish this works and doesn't break anything :P
                    Typeface::class.java.getDeclaredMethod("createFromAsset", AssetManager::class.java, String::class.java)
                },
                fontHook
            )

            // Custom drawables
            val uriCache = mutableMapOf<String, Uri?>()
            val resourceDrawableIdHelper = classLoader.loadClass("com.facebook.react.views.imagehelper.ResourceDrawableIdHelper")
            
            XposedBridge.hookMethod(resourceDrawableIdHelper.getDeclaredMethod("getResourceDrawableUri", Context::class.java, String::class.java), object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args[1] as? String ?: return

                    val uri = uriCache.getOrPut(name) {
                        File(drawablesDir, "$name.png").takeIf { it.exists() }?.let { 
                            Uri.fromFile(it) 
                        } ?: null
                    }

                    if (uri != null) param.result = uri
                }
            })
        }

        val loadScriptFromAssets = catalystInstance.getDeclaredMethod("jniLoadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        val loadScriptFromFile = catalystInstance.getDeclaredMethod("jniLoadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)

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