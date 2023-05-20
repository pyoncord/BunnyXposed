package com.pyoncord.xposed

import java.io.File
import java.lang.Exception
import kotlinx.coroutines.*
import android.content.res.AssetManager
import android.content.res.XModuleResources
import android.content.res.Resources
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Main : IXposedHookZygoteInit, IXposedHookLoadPackage {
    companion object {
        const val LOG_TAG = "Pyoncord"
        const val DEFAULT_ENDPOINT = "https://raw.githubusercontent.com/pyoncord/pyoncord/builds/pyoncord.js"
    }

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

        val files = File(appInfo.dataDir, "files").also { it.mkdirs() }
        val pyoncordFd = File(files, "pyoncord").also { it.mkdirs() }

        val bundle = File(pyoncordFd, "pyoncord.js")
        val etag = File(pyoncordFd, "~$.etag")

        val httpJob = GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = HttpClient(CIO) {
                    install(HttpTimeout) { requestTimeoutMillis = 1000 }
                    install(UserAgent) { agent = "PyoncordXposed" }
                }
                val response: HttpResponse = client.get(DEFAULT_ENDPOINT) {
                    headers { if (etag.exists() && bundle.exists()) append(HttpHeaders.IfNoneMatch, etag.readText()) }
                }

                if (response.status.value == 200) {
                    bundle.writeBytes(response.body())
                    if (response.headers.contains("Etag")) etag.writeText(response.headers["Etag"] as String)
                }

                return@launch
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to download pyoncord.js bundle")
            }
        }

        val loadScriptFromAssets = catalystInstance.getDeclaredMethod("jniLoadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        val loadScriptFromFile = catalystInstance.getDeclaredMethod("jniLoadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)

        // Mirror resource resolver to "com.discord" if the package name is renamed
        // I don't know if there's any side effect with this - amsyar@pylix 
        if (packageName != "com.discord") {
            val getIdentifier = Resources::class.java.getDeclaredMethod("getIdentifier", String::class.java, String::class.java, String::class.java);
            
            XposedBridge.hookMethod(getIdentifier, object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[2] == packageName) param.args[2] = "com.discord"
                }
            })
        }

        // TODO: Loader config
        val hook = object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = with(param) {
                Log.d(LOG_TAG, "Hooking ${method.name} to load Pyoncord")

                // Load pre-patches and identity
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, thisObject, arrayOf(resources.assets, "assets://js/modules.js", true))
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, thisObject, arrayOf(resources.assets, "assets://js/identity.js", true))
                
                // Invoke the original method
                XposedBridge.invokeOriginalMethod(method, thisObject, args)

                // Wait for bundle to download. Then, execute our bundle
                runBlocking { httpJob.join() }
                XposedBridge.invokeOriginalMethod(loadScriptFromFile, thisObject, arrayOf(bundle.absolutePath, bundle.absolutePath, args[2]))

                // Prevent from invoking the original method
                setResult(null)
            }
        }

        XposedBridge.hookMethod(loadScriptFromAssets, hook)
        XposedBridge.hookMethod(loadScriptFromFile, hook)

        return@with
    }
}