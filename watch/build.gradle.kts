// Delivery 1A: debug-only Watch transport app. Version is a development candidate,
// never a release. Release builds must deny cleartext and expose no usable HTTP sender.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
