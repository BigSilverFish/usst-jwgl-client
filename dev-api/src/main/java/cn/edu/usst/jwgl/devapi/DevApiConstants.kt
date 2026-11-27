package cn.edu.usst.jwgl.devapi

object DevApiConstants {
    const val ADDON_PACKAGE_NAME = "bsf.usst.jwgl.devpack"
    const val DEV_API_VERSION = 1

    const val META_API_VERSION = "cn.edu.usst.jwgl.DEV_API_VERSION"
    const val META_ENTRY_CLASS = "cn.edu.usst.jwgl.DEV_ENTRY_CLASS"

    const val TRONCLASS_BASE_URL = "https://1906.usst.edu.cn"

    const val STRATEGY_NO_DELAY = "no_delay"
    const val STRATEGY_DELAY_10S = "delay_10s"
    const val STRATEGY_DELAY_30S = "delay_30s"
    const val STRATEGY_COUNT_1 = "count_1"
    const val STRATEGY_COUNT_10 = "count_10"
    const val STRATEGY_HALF_CLASS = "half_class"
    const val STRATEGY_CUSTOM_TIME = "custom_time"
    const val STRATEGY_CUSTOM_RATIO = "custom_ratio"
}

enum class DevAddonStatus {
    NOT_INSTALLED,
    ACTIVE,
    DISABLED_BY_USER,
    INCOMPATIBLE_API,
    SIGNATURE_MISMATCH,
    LOAD_ERROR
}
