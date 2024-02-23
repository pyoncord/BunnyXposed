package com.pyoncord.xposed

import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.json.JsonObjectBuilder

abstract class PyonModule {
    open fun buildJson(builder: JsonObjectBuilder) {}
    open fun onInit(packageParam: XC_LoadPackage.LoadPackageParam) {}
}