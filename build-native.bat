@call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
native-image --enable-preview --add-exports java.base/sun.net.dns=ALL-UNNAMED --verbose -O3 -jar target\kay-1.0-jar-with-dependencies.jar -o kay -H:+ReportExceptionStackTraces --no-fallback --initialize-at-
run-time=io.netty.handler.ssl.BouncyCastleAlpnSslUtils,io.netty.channel.epoll.Epoll,io.netty.channel.epoll.Native,io.netty.channel.unix.Errors,io.netty.resolver.dns.DefaultDnsServerAddressStreamProvider,sun.net.dns.ResolverConfigura
tionImpl
@pause