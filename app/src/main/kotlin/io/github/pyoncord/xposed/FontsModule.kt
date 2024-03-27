// credits to janisslsm from his PR: https://github.com/vendetta-mod/VendettaXposed/pull/17
// the functions hooked are based on the RN codebase, just modified to add fonts

package io.github.pyoncord.xposed

import android.content.res.AssetManager
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Typeface.CustomFallbackBuilder
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.webkit.URLUtil
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.json.*
import java.io.IOException
import java.io.File

class FontsModule: PyonModule() {
    private val EXTENSIONS = arrayOf("", "_bold", "_italic", "_bold_italic")
    private val FILE_EXTENSIONS = arrayOf(".ttf", ".otf")
    private val FONTS_ASSET_PATH = "fonts/"
    private lateinit var fontsDir: File
    private lateinit var fontsAbsPath: String

    override fun buildJson(builder: JsonObjectBuilder) {
        builder.apply {
            put("supportFonts", true)
        }
    }

    override fun onInit(packageParam: XC_LoadPackage.LoadPackageParam) = with (packageParam) {
        fontsDir = File(appInfo.dataDir, "files/pyoncord/fonts").also { it.mkdirs() }
        fontsAbsPath = fontsDir.absolutePath + "/"

        XposedHelpers.findAndHookMethod("com.facebook.react.views.text.ReactFontManager", classLoader, "createAssetTypeface",
            String::class.java,
            Int::class.java,
            "android.content.res.AssetManager", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Typeface? {
                    val fontFamilyName: String = param.args[0].toString();
                    val style: Int = param.args[1] as Int;
                    val assetManager: AssetManager = param.args[2] as AssetManager;
                    return createAssetTypeface(fontFamilyName, style, assetManager)
                }
            });

        return@with
    }

    private fun createAssetTypefaceWithFallbacks(
        fontFamilyNames: Array<String>,
        style: Int,
        assetManager: AssetManager
    ): Typeface? {
        val fontFamilies: MutableList<FontFamily> = ArrayList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Iterate over the list of fontFamilyNames, constructing new FontFamily objects
            // for use in the CustomFallbackBuilder below.
            for (fontFamilyName in fontFamilyNames) {
                for (fontRootPath in arrayOf(fontsAbsPath, FONTS_ASSET_PATH)) {
                    for (fileExtension in FILE_EXTENSIONS) {
                        val fileName = java.lang.StringBuilder()
                            .append(fontRootPath)
                            .append(fontFamilyName)
                            .append(fileExtension)
                            .toString()
                        try {
                            val builder = if (fileName[0] == '/') Font.Builder(File(fileName)) else Font.Builder(assetManager, fileName)
                            val font = builder.build()
                            val family = FontFamily.Builder(font).build()
                            fontFamilies.add(family)
                        } catch (e: java.lang.RuntimeException) {
                            // If the typeface asset does not exist, try another extension.
                            continue
                        } catch (e: IOException) {
                            // If the font asset does not exist, try another extension.
                            continue
                        }
                    }
                }
            }

            // If there's some problem constructing fonts, fall back to the default behavior.
            if (fontFamilies.size == 0) {
                return createAssetTypeface(fontFamilyNames[0], style, assetManager)
            }
            val fallbackBuilder = CustomFallbackBuilder(fontFamilies[0])
            for (i in 1 until fontFamilies.size) {
                fallbackBuilder.addCustomFallback(fontFamilies[i])
            }
            return fallbackBuilder.build()
        }
        return null
    }

    private fun createAssetTypeface(
        fontFamilyName_: String, style: Int, assetManager: AssetManager
    ): Typeface? {
        // This logic attempts to safely check if the frontend code is attempting to use
        // fallback fonts, and if it is, to use the fallback typeface creation logic.
        var fontFamilyName: String = fontFamilyName_
        val fontFamilyNames =
            fontFamilyName.split(",".toRegex())
                .dropLastWhile { it.isEmpty() }
                .toTypedArray()
        for (i in fontFamilyNames.indices) {
            fontFamilyNames[i] = fontFamilyNames[i].trim()
        }

        // If there are multiple font family names:
        //   For newer versions of Android, construct a Typeface with fallbacks
        //   For older versions of Android, ignore all the fallbacks and just use the first font family
        if (fontFamilyNames.size > 1) {
            fontFamilyName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return createAssetTypefaceWithFallbacks(fontFamilyNames, style, assetManager)
            } else {
                fontFamilyNames[0]
            }
        }

        val extension = EXTENSIONS[style]

        // Lastly, after all those checks above, this is the original RN logic for
        // getting the typeface.
        for (fontRootPath in arrayOf(fontsAbsPath, FONTS_ASSET_PATH)) {
            for (fileExtension in FILE_EXTENSIONS) {
                val fileName = java.lang.StringBuilder()
                    .append(fontRootPath)
                    .append(fontFamilyName)
                    .append(extension)
                    .append(fileExtension)
                    .toString()
                
                return try {
                    if (fileName[0] == '/')
                        Typeface.createFromFile(fileName)
                    else
                        Typeface.createFromAsset(assetManager, fileName)
                } catch (e: java.lang.RuntimeException) {
                    // If the typeface asset does not exist, try another extension.
                    XposedBridge.log("(under) acquiring $fileName font failed ${e.cause}")
                    continue
                }
            }
        }

        return Typeface.create(fontFamilyName, style)
    }
}