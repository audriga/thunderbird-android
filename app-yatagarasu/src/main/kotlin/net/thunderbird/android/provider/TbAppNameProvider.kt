package com.audriga.yatagarasu.android.provider

import android.content.Context
import app.k9mail.core.common.provider.AppNameProvider
import com.audriga.yatagarasu.android.R

class TbAppNameProvider(
    context: Context,
) : AppNameProvider {
    override val appName: String by lazy {
        context.getString(R.string.app_name)
    }
}
