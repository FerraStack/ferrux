# Release builds are not minified yet. TerminalNative's keep rule comes from
# terminal-view's consumer-rules.pro; the app natives are bound by name with
# RegisterNatives from libferrux.so.
-keep class dev.ferrux.app.FerruxAppNative { native <methods>; }
-keep class dev.ferrux.app.api.ApiNative { native <methods>; }
