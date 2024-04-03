package io.github.pyoncord.xposed

import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
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
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File

@Serializable
data class CustomLoadUrl(
    val enabled: Boolean,
    val url: String
)
@Serializable
data class LoaderConfig(
    val customLoadUrl: CustomLoadUrl
)

class Main : IXposedHookLoadPackage {
    private val pyonModules: Array<PyonModule> = arrayOf(
        ThemeModule(),
        SysColorsModule(),
        FontsModule(),
        // BubbleModule()
    )

    fun buildLoaderJsonString(): String {
        val obj = buildJsonObject {
            put("loaderName", "PyoncordXposed")

            for (module in pyonModules) {
                module.buildJson(this)
            }
        }

        return Json.encodeToString(obj)
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) = with (param) {
        val catalystInstanceImpl = runCatching {
            classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")
        }.getOrElse { return@with }

        for (module in pyonModules) module.onInit(param)

        val loadScriptFromAssets = catalystInstanceImpl.getDeclaredMethod(
            "jniLoadScriptFromAssets",
            AssetManager::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }

        val loadScriptFromFile = catalystInstanceImpl.getDeclaredMethod(
            "jniLoadScriptFromFile",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }

        val setGlobalVariable = catalystInstanceImpl.getDeclaredMethod(
            "setGlobalVariable",
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val cacheDir = File(appInfo.dataDir, "cache/pyoncord").apply { mkdirs() }
        val filesDir = File(appInfo.dataDir, "files/pyoncord").apply { mkdirs() }

        val preloadsDir = File(filesDir, "preloads").apply { mkdirs() }
        val bundle = File(cacheDir, "bundle.js")
        val etag = File(cacheDir, "etag.txt")

        val configFile = File(filesDir, "loader.json")

        val config = try {
            if (!configFile.exists()) throw Exception()
            Json { ignoreUnknownKeys = true }.decodeFromString(configFile.readText())
        } catch (_: Exception) {
            LoaderConfig(
                customLoadUrl = CustomLoadUrl(
                    enabled = false,
                    url = "http://localhost:4040/pyoncord.js"
                )
            )
        }

        val scope = MainScope()
        val httpJob = scope.async(Dispatchers.IO) {
            try {
                val client = HttpClient(CIO) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = if (bundle.exists()) 3000 else HttpTimeout.INFINITE_TIMEOUT_MS
                    }
                    install(UserAgent) { agent = "PyoncordXposed" }
                }

                val url = 
                    if (config.customLoadUrl.enabled) config.customLoadUrl.url 
                    else "https://raw.githubusercontent.com/pyoncord/detta-builds/main/bunny.js"

                val response: HttpResponse = client.get(url) {
                    headers { 
                        if (etag.exists() && bundle.exists()) {
                            append(HttpHeaders.IfNoneMatch, etag.readText())
                        }
                    }
                }

                if (response.status == HttpStatusCode.OK) {
                    bundle.writeBytes(response.body())
                    if (response.headers["Etag"] != null) etag.writeText(response.headers["Etag"]!!)
                    else if (etag.exists()) etag.delete()
                }

                return@async
            } catch (e: Exception) {
                Log.e("Pyoncord", "Failed to download Pyoncord")
            }
        }

        val patch = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runBlocking { httpJob.join() }

                XposedBridge.invokeOriginalMethod(
                    setGlobalVariable, 
                    param.thisObject, 
                    arrayOf("__PYON_LOADER__", buildLoaderJsonString())
                )

                preloadsDir
                    .walk()
                    .filter { it.isFile && it.extension == "js" }
                    .forEach { file ->
                        XposedBridge.invokeOriginalMethod(
                            loadScriptFromFile, 
                            param.thisObject, 
                            arrayOf(file.absolutePath, file.absolutePath, param.args[2])
                        )
                    }

                XposedBridge.invokeOriginalMethod(
                    loadScriptFromFile, 
                    param.thisObject, 
                    arrayOf(bundle.absolutePath, bundle.absolutePath, param.args[2])
                )
            }
        }

        XposedBridge.hookMethod(loadScriptFromAssets, patch)
        XposedBridge.hookMethod(loadScriptFromFile, patch)

        // Fighting the side effects of changing the package name
        if (packageName != "com.discord") {
            val getIdentifier = Resources::class.java.getDeclaredMethod(
                "getIdentifier", 
                String::class.java,
                String::class.java,
                String::class.java
            )

            XposedBridge.hookMethod(getIdentifier, object: XC_MethodHook() {
                override fun beforeHookedMethod(mhparam: MethodHookParam) = with(mhparam) {
                    if (args[2] == param.packageName) args[2] = "com.discord"
                }
            })
        }
    }
}