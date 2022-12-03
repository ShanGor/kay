# Load Runner by Java
Inspired by rakyll/hey. While the `hey` program, does not support path variable. This `Kay` can  do that.

## Build
- Build with maven first
  > mvn clean package
  > 
  Then you should run the jar with agent to generate specifications:
  ```
  $GRAAL_HOME\bin\java -agentlib:native-image-agent=config-output-dir=C:\sandbox\sources\git\kotlin\kay\src\main\resources\META-INF\native-image\cn.gzten\kay -jar C:\sandbox\sources\git\kotlin\kay\target\kay-1.0-jar-with-dependencies.jar
  ```
- Build native image
  - Open `X64 Native Tools Command Prompt for VS2022` (Ensure the VS2022 is installed with English, if Chinese, will fail)
  - In the command prompt, change directory to the kay folder, run the `build-native.bat`

  For the run time error about `DnsServerAddressStreamProviders` with `ResolverConfigurationImpl`, you cannot solve it by adding jni-config.json, and add the `--initialize-at-run-time=sun.net.dns.ResolverConfigurationImpl` to resolve.
  ```
    {
    "name":"sun.net.dns.ResolverConfigurationImpl",
    "fields":[
      {"name":"os_searchlist"},
      {"name":"os_nameservers"}
    ]
  }
  ```
  Errors. (Ref to https://github.com/oracle/graal/issues/4304)
  ```
  [ WARN] (Thread-0) throwIfFatal detected a jvm fatal exception, which is thrown and logged below: - java.lang.ExceptionInInitializerError
  java.lang.ExceptionInInitializerError
          at io.netty.resolver.dns.DnsServerAddressStreamProviders$DefaultProviderHolder$1.provider(DnsServerAddressStreamProviders.java:142)
          at io.netty.resolver.dns.DnsServerAddressStreamProviders$DefaultProviderHolder$1.<init>(DnsServerAddressStreamProviders.java:122)
          at io.netty.resolver.dns.DnsServerAddressStreamProviders$DefaultProviderHolder.<clinit>(DnsServerAddressStreamProviders.java:120)
          at io.netty.resolver.dns.DnsServerAddressStreamProviders.unixDefault(DnsServerAddressStreamProviders.java:109)
          at io.netty.resolver.dns.DnsServerAddressStreamProviders.platformDefault(DnsServerAddressStreamProviders.java:105)
          at io.netty.resolver.dns.DnsNameResolverBuilder.<init>(DnsNameResolverBuilder.java:60)
          at reactor.netty.transport.NameResolverProvider.newNameResolverGroup(NameResolverProvider.java:479)
          at reactor.netty.tcp.TcpResources.getOrCreateDefaultResolver(TcpResources.java:315)
          at reactor.netty.http.HttpResources.getOrCreateDefaultResolver(HttpResources.java:162)
          at reactor.netty.http.client.HttpClientConfig.defaultAddressResolverGroup(HttpClientConfig.java:395)
          at reactor.netty.transport.ClientTransportConfig.resolverInternal(ClientTransportConfig.java:225)
          at reactor.netty.http.client.HttpClientConfig.resolverInternal(HttpClientConfig.java:449)
          at reactor.netty.http.client.HttpClientConnect$MonoHttpConnect.lambda$subscribe$0(HttpClientConnect.java:265)
          at reactor.core.publisher.MonoCreate.subscribe(MonoCreate.java:58)
          at reactor.core.publisher.FluxRetryWhen.subscribe(FluxRetryWhen.java:77)
          at reactor.core.publisher.MonoRetryWhen.subscribeOrReturn(MonoRetryWhen.java:46)
          at reactor.core.publisher.InternalMonoOperator.subscribe(InternalMonoOperator.java:57)
          at reactor.netty.http.client.HttpClientConnect$MonoHttpConnect.subscribe(HttpClientConnect.java:272)
          at reactor.core.publisher.InternalMonoOperator.subscribe(InternalMonoOperator.java:64)
          at reactor.core.publisher.MonoDeferContextual.subscribe(MonoDeferContextual.java:55)
          at reactor.core.publisher.Mono.subscribe(Mono.java:4444)
          at reactor.core.publisher.Mono.subscribeWith(Mono.java:4510)
          at reactor.core.publisher.Mono.subscribe(Mono.java:4272)
          at cn.gzten.kay.KayWithWebClient.issueAnHttpRequest(KayWithWebClient.java:145)
          at java.base@19.0.1/java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:577)
          at java.base@19.0.1/java.util.concurrent.FutureTask.run(FutureTask.java:317)
          at java.base@19.0.1/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
          at java.base@19.0.1/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
          at java.base@19.0.1/java.lang.Thread.run(Thread.java:1589)
          at org.graalvm.nativeimage.builder/com.oracle.svm.core.thread.PlatformThreads.threadStartRoutine(PlatformThreads.java:775)
          at org.graalvm.nativeimage.builder/com.oracle.svm.core.windows.WindowsPlatformThreads.osThreadStartRoutine(WindowsPlatformThreads.java:178)
  Caused by: java.lang.NullPointerException
          at java.base@19.0.1/sun.net.dns.ResolverConfigurationImpl.stringToList(ResolverConfigurationImpl.java:71)
          at java.base@19.0.1/sun.net.dns.ResolverConfigurationImpl.loadConfig(ResolverConfigurationImpl.java:138)
          at java.base@19.0.1/sun.net.dns.ResolverConfigurationImpl.nameservers(ResolverConfigurationImpl.java:161)
          at jdk.naming.dns@19.0.1/com.sun.jndi.dns.DnsContextFactory.serversForUrls(DnsContextFactory.java:149)
          at jdk.naming.dns@19.0.1/com.sun.jndi.dns.DnsContextFactory.getContext(DnsContextFactory.java:81)
          at jdk.naming.dns@19.0.1/com.sun.jndi.dns.DnsContextFactory.urlToContext(DnsContextFactory.java:120)
          at jdk.naming.dns@19.0.1/com.sun.jndi.dns.DnsContextFactory.getInitialContext(DnsContextFactory.java:64)
          at java.naming@19.0.1/javax.naming.spi.NamingManager.getInitialContext(NamingManager.java:732)
          at java.naming@19.0.1/javax.naming.InitialContext.getDefaultInitCtx(InitialContext.java:305)
          at java.naming@19.0.1/javax.naming.InitialContext.init(InitialContext.java:236)
          at java.naming@19.0.1/javax.naming.InitialContext.<init>(InitialContext.java:208)
          at java.naming@19.0.1/javax.naming.directory.InitialDirContext.<init>(InitialDirContext.java:130)
          at io.netty.resolver.dns.DirContextUtils.addNameServers(DirContextUtils.java:49)
          at io.netty.resolver.dns.DefaultDnsServerAddressStreamProvider.<clinit>(DefaultDnsServerAddressStreamProvider.java:53)
          ... 31 more
  ```

## Usage
- With random integer parameter in the URL  
  Below case is to generate random number from 1 to 1 million, generated randomly as an userId.
  ```
  java -jar my.jar -n 10000 -c 1000 http://localhost:8080/users/{int(1,1000000)}
  ```
- With random uuid in the URL  
  (Please do not use both random uuid and random int in the url)
  ```
  java -jar my.jar -n 10000 -c 1000 http://localhost:8080/reports/{uuid()}
  ```
- Load parameters from a file.  
  For example, below case the `ids.txt` is a text file, each line is an id number, in random manner.
  ```
  pgm -n <number> -c <concurrency> http://localhost:8080/reports/{@ids.txt}
  ```
- Support http methods [GET|PUT|POST|DELETE|PATCH]
  ```
  pgm -n <number> -c <concurrency> http://localhost:8080/test/1 -m DELETE
  ```
- Supports -d "my data" format
  ```
  pgm -n <number> -c <concurrency> http://localhost:8080/test -d "{\"message\", \"good\"}" -H "Content-Type: application/json" -H "Authorization: ..."
  ```

- Supports `-d file:///c:/tmp/my.json` format, will load the data to post
  ```
  pgm -n 10000 -c 200 http://localhost:8080/test -d "file:///C:/tmp/hey.txt" -H "content-type: application/json"
  ```
