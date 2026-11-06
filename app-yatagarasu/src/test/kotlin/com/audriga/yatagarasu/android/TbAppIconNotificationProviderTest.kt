package com.audriga.yatagarasu.android

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import com.audriga.yatagarasu.android.provider.TbAppIconNotificationProvider

class TbAppIconNotificationProviderTest {
    @Test
    fun `provides correct Yatagarasu notification icon`() {
        val provider = TbAppIconNotificationProvider()
        val icon = provider.pushNotificationIcon

        assertThat(icon)
            .isEqualTo(app.k9mail.core.ui.legacy.theme2.yatagarasu.R.drawable.ic_logo_yatagarasu_white)
    }
}
