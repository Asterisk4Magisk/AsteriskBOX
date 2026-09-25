# Offline code formatting

`beautify.min.js` is the minified JavaScript-only browser bundle from js-beautify
2.0.3 (68,073 bytes upstream). CSS and HTML formatters are not bundled. The file
is packaged in the APK for offline use; the app does not fetch it from the CDN.

`SingBoxScriptFormatter.kt` calls `exports.js_beautify` directly with the formatting
options, using the app's QuickJS runtime offline. Input and output are
compiled for syntax validation but never executed. Only the formatter itself
is executed.
