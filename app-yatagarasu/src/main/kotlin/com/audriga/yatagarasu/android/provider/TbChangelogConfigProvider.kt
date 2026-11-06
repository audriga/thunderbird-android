package com.audriga.yatagarasu.android.provider

import com.audriga.yatagarasu.android.R
import net.thunderbird.feature.navigation.changelog.api.ChangelogConfigProvider

class TbChangelogConfigProvider : ChangelogConfigProvider {
    override val changelogIndexResId: Int = R.raw.changelog_index
}
