package com.pyoncord.xposed

import java.io.File
import java.lang.Exception
import kotlinx.coroutines.*
import android.content.res.AssetManager
import android.content.res.XModuleResources
import android.util.Log
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

class Main : IXposedHookZygoteInit, IXposedHookLoadPackage {
    private lateinit var resources: XModuleResources

    // Assign module resources in process zygote
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        resources = XModuleResources.createInstance(startupParam.modulePath, null)
    }

    // Hook function responsible for loading Discord's package
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) = with(param) {
        val catalystInstance = try { 
            classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl") 
        } catch (e: ClassNotFoundException) { 
            // Package is not the target app, return
            return@with
        }

        val cache = File(appInfo.dataDir, "cache").also { it.mkdirs() }
        val bundle = File(cache, "pyoncord.js")
        val etag = File(cache, "pyoncord_etag.txt")

        val scope = MainScope()
        val httpJob = scope.async(Dispatchers.IO) {
            try {
                val client = HttpClient(CIO) {
                    install(HttpTimeout) { requestTimeoutMillis = 1000 }
                    install(UserAgent) { agent = "PyoncordXposed" }
                }
                val response: HttpResponse = client.get("https://raw.githubusercontent.com/pyoncord/pyoncord/builds/pyoncord.js") {
                    headers { if (etag.exists() && bundle.exists()) append(HttpHeaders.IfNoneMatch, etag.readText()) }
                }

                if (response.status.value == 200) {
                    bundle.writeBytes(response.body())
                    if (response.headers.contains("Etag")) etag.writeText(response.headers["Etag"] as String)
                }

                return@async
            } catch (e: Exception) {
                Log.e("Pyoncord", "Failed to download pyoncord.js bundle")
            }
        }

        val loadScriptFromAssets = catalystInstance.getDeclaredMethod("jniLoadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        val loadScriptFromFile = catalystInstance.getDeclaredMethod("jniLoadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)

        // TODO: Loader config

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets,param.thisObject, arrayOf(resources.assets, "assets://js/modules.js", true))
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(resources.assets, "assets://js/identity.js", true))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                scope.launch(scope.coroutineContext) {
                    try {
                        httpJob.await()
                        Log.d("Pyoncord", "Executing Pyoncord")
                        XposedBridge.invokeOriginalMethod(loadScriptFromFile, param.thisObject, arrayOf(bundle.absolutePath, bundle.absolutePath, param.args[2]))
                    } catch (_: Exception) {}
                }
            }
        }

        XposedBridge.hookMethod(loadScriptFromAssets, hook)
        XposedBridge.hookMethod(loadScriptFromFile, hook)

        return@with
    }
}