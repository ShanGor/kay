@call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
native-image --verbose -O3 -jar target\kay-1.0-jar-with-dependencies.jar -o kay ^
-H:+ReportExceptionStackTraces --no-fallback ^
--initialize-at-run-time=io.netty.channel.epoll.Epoll,io.netty.channel.epoll.Native,^
io.netty.channel.unix.Errors
@pause