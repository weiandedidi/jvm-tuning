> [!NOTE]
>
> 使用typora打开查看



# 一、调优哲学与方法论：避免盲目调优

在深入具体案例之前，必须建立正确的性能调优观念。盲目调整JVM参数是危险且低效的。

1. **原则一：测量优于猜测 (Measure, Don't Guess!)**
2. 没有数据支撑的调优就是玄学。必须依靠**监控指标、GC日志、线程快照、堆转储**等数据来定位问题。
3. 使用第三部分介绍的工具（如jstat、Arthas、APM、JFR）收集数据。
4. **原则二：权衡的艺术 (The Art of Trade-off)**
5. 调优本质上是**吞吐量（Throughput）、延迟（Latency）、内存占用（Footprint）** 三者之间的权衡。
6. **目标**：根据应用类型（如计算密集型、IO密集型、交易密集型）确定优先目标。例如：
7. **数据批处理应用**：优先保证**高吞吐量**。
8. **Web响应式应用**：优先保证**低延迟**，控制GC停顿时间。
9. **资源受限环境**：优先控制**内存占用**。
10. **原则三：由上至下，由外至内 (Top-Down, Outside-In)**
11. 先排查应用架构、代码、数据库、网络等**外部因素**，再排查JVM内部问题。80%的性能问题源于糟糕的架构和代码。
12. 排查流程：业务逻辑 -> SQL/NoSQL -> 应用代码 -> 框架 -> JVM -> OS -> 硬件。



# 二、典型问题排查

## 问题一：CPU占用过高

### 可能原因：

**排查流程**：

1. **定位异常进程**：使用 top 命令，确认是Java进程CPU高。
2. **定位异常线程**：
3. top -Hp <java_pid>：查看该进程内所有线程的CPU占用情况，记录下CPU最高的线程ID（十进制）。
4. 或将CPU使用率实时输出到文件：top -H -b -n 1 -p <pid> | awk 'NR>7 {print $1,$9}' | sort -nk2r | head -10
5. **线程ID转换**：printf "%x\n" <十进制线程ID>，得到线程ID的16进制值（nid）。
6. **查看线程栈**：
7. jstack <java_pid> | grep -A20 <nid>：查看该线程的堆栈信息。
8. 或使用 **Arthas** 一键搞定：thread <线程ID> 或 thread -n 3（查看最忙的3个线程）。
9. **分析堆栈**：根据堆栈信息定位到具体代码。
10. **常见原因**：
11. **死循环**：代码中存在计算密集型的无限循环。
12. **频繁GC**：通过 jstat -gcutil <pid> 1s 验证，如果GC次数（YGC/FGC）和时间（GCT）飙升，则问题在GC。
13. **锁竞争激烈**：线程状态多为 BLOCKED，使用 jstack -l <pid> 或 thread -b 检查死锁。

### case再现：

本地的case构造：

问题代码（CPU 飙高）示例：死循环空转

```java
@RestController
@RequestMapping("/cpu")
public class CpuController {

    @GetMapping("/high")
    public String highCpu() {
        new Thread(() -> {
            while (true) {
                // 空转，疯狂占用CPU
            }
        }, "high-cpu-thread").start();

        return "ok";
    }
}
```

多次访问，造成cpu瞬间飙升、风扇狂转

```bash
curl http://localhost:8080/cpu/high
```

### Arthas 排查流程（CPU）

> [!NOTE]
>
> Arthas 排查 CPU 高的黄金链路：
>
> ```bash
> dashboard -> thread -n -> thread <id> -> jad
> ```
>
> 这是排查 CPU 飙高最常用路径。

**1.连接进程，选择你的 Spring Boot 进程。**

```
java -jar arthas-boot.jar
```

![连接java程序](/Users/maqidi/Code/me/jvm-tuning/assets/连接java程序.png)

**2.看整体 JVM 状态**

```
dashboard
```

重点看：

- CPU
- GC
- Thread
- Memory

你会看到 CPU 持续很高。

![arthas的dashboard指令](/Users/maqidi/Code/me/jvm-tuning/assets/arthas的dashboard指令-7443953.png)

------

**3.定位最耗 CPU 的线程**

```bash
thread -n 5
```

查看最忙的 5 个线程。

你会看到类似：

```bash
"high-cpu-thread" cpu=99%
```

这一步直接锁定问题线程。

------

4. 查看线程堆栈

```bash
thread <threadId>
```

例如：

```bash
thread 23
```

会看到线程卡在：

```
while (true) { }
```

直接定位到问题代码。

![cpu问题代码行数](/Users/maqidi/Code/me/jvm-tuning/assets/cpu问题代码行数.png)

------

5. 反查类和方法

```
jad com.ctyun.xxx.CpuController
```

反编译确认源码。CPU 问题排查结论

## 问题二：内存泄漏（OOM）

### 可能原因：

重点找：

- 谁占堆最多
- 谁在“卡住”对象不能释放

典型泄漏源：

- `static Map`
- `static List`
- ThreadLocal
- 缓存未淘汰
- MQ 消息堆积
- HTTP Session
- byte[]

**排查流程**：

1. **确认OOM类型**：查看错误日志 OutOfMemoryError: [???]。
2. Java heap space: 堆内存溢出。
3. Metaspace: 元空间溢出。
4. GC overhead limit exceeded: GC效率低下溢出。
5. Unable to create new native thread: 线程创建溢出。
6. **获取堆转储**（必须在启动参数中预先配置）：
7. **最佳方式**：添加JVM参数 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/path/to/dump.hprof，让JVM在发生OOM时自动生成堆转储文件。
8. **补救方式**：对运行中的进程，使用 jmap -dump:format=b,file=dump.hprof <pid>（**注意：此命令会触发Full GC，谨慎在生产环境使用**）。
9. **Arthas方式**：heapdump /tmp/dump.hprof 生成堆转储。
10. **分析堆转储**：
11. 使用 **Eclipse MAT (Memory Analyzer Tool)** 或 **idea中的profiler** 加载 dump.hprof 文件，[使用方法详见](https://blog.csdn.net/x2277659985/article/details/157581777)
12. **标准分析路径**：

1. 打开 **Leak Suspects Report**（泄漏嫌疑报告），MAT会自动给出可能的原因。
2. 查看 **Histogram**（直方图），按对象数量或大小排序，找到占比最高的对象。
3. 对疑似泄漏的对象，右键 -> **Merge Shortest Paths to GC Roots** -> **exclude all phantom/weak/soft etc. references**（排除弱引用等），查看到GC Roots的强引用链。
4. 分析引用链，找到是哪个类的哪个静态变量或集合持有了这些对象，导致无法被回收。

### case再现：

问题代码构造：示例：静态集合无限增长
```java
@RestController
@RequestMapping("/mem")
public class MemoryLeakController {

    private static final List<byte[]> CACHE = new ArrayList<>();

    @GetMapping("/leak")
    public String leak() {
        CACHE.add(new byte[1024 * 1024]); // 每次1MB
        return "size=" + CACHE.size();
    }
}
```

请求现象：连续调用

- 堆持续增长
- Full GC 增多
- 最终 OOM

```bash
for i in {1..1000}; do curl http://localhost:8080/mem/leak; done
```

### Arthas 排查流程（内存泄漏）

#### 1. Arthas自身无法启动时的Jcmd+profiler排查法：

当jvm服务自身的日志出现：`java.lang.OutOfMemoryError: Java heap space`时候，Arthas连接不到jvm上，排查arthas.log的日志：

```bash
tail -100 ~/logs/arthas/arthas.log
```

发现：==**java.lang.OutOfMemoryError**==这时候无法连接jvm不能用arthas

```
Arthas server agent start...
java.lang.OutOfMemoryError: Java heap space
Arthas server agent start...
java.lang.ClassNotFoundException: com.taobao.arthas.core.server.ArthasBootstrap
	at java.base/java.net.URLClassLoader.findClass(URLClassLoader.java:445)
	at java.base/java.lang.ClassLoader.loadClass(ClassLoader.java:587)
	at com.taobao.arthas.agent.ArthasClassloader.loadClass(ArthasClassloader.java:34)
	at java.base/java.lang.ClassLoader.loadClass(ClassLoader.java:520)
	at com.taobao.arthas.agent334.AgentBootstrap.bind(AgentBootstrap.java:181)
	at com.taobao.arthas.agent334.AgentBootstrap.access$000(AgentBootstrap.java:20)
	at com.taobao.arthas.agent334.AgentBootstrap$1.run(AgentBootstrap.java:152)
```

**当前最优解：先放弃 Arthas，直接捕获内存快照 dump，jcmd（JDK17 推荐）**

```bash
jcmd <pid> GC.heap_dump /tmp/heap.hprof
```

使用idea的profiler分析查看：

- 是不是 `byte[]`
- 是不是内存大户 `HashMap`、ArrayList、ConcurrentHashMap$Node、`ThreadLocalMap$Entry`
- 是不是缓存打满

**详见idea的profiler的截图查看**

![profiler读取dump](/Users/maqidi/Code/me/jvm-tuning/assets/profiler读取dump.png)

#### 2 Arthas 排查链路：

```
dashboard -> memory -> heapdump -> watch -> ognl
```

最关键是：

- `heapdump` 看对象
- `ognl` 查静态引用

1. dashboard看

   - heap usage 持续上涨
   - GC 次数增加
   - old 区不回收

2. memory查看

   - heap
   - old gen，如果 old 区持续上涨，说明对象被长期引用
   - metaspace

3. heapdump 导出快照看

   ```bash
   heapdump /tmp/heap.hprof
   ```

4. watch观察方法被频繁调用  `watch com.ctyun.xxx.MemoryLeakController leak returnObj -x 2`

5. 查看静态字段

   ```bash
   ognl '@com.ctyun.xxx.MemoryLeakController@CACHE.size()'
   ```

   会看到 size 持续增长，这一步直接锁定泄漏根因：**静态集合持有对象，GC 无法回收**。

## 问题三：GC频繁或停顿过长

### 可能原因：

**排查流程**：

1. **开启并查看GC日志**：
2. 启动参数添加：-Xlog:gc*=info:file=gc.log:time,tags:filecount=10,filesize=100M
3. 使用 tail -f gc.log 或上传至 **GCeasy** / **GCE Viewer** 等在线平台进行分析。
4. **分析关键指标**：
5. **Young GC频繁**：Eden区分配过快。现象：YGC次数多，但每次回收后Eden/Survivor区下降明显。**对策**：适当增大新生代大小 -Xmn。
6. **Young GC时间长**：存活对象过多，复制开销大。**对策**：减少 Survivor 区不必要的存活（优化代码），或调整 -XX:SurvivorRatio。
7. **Full GC频繁**：老年代被填满。现象：FGC次数多。**对策**：分析是**内存泄漏**（对象无法回收）还是**晋升过早/过多**（Young GC后存活对象太多直接进入老年代）。
8. **Full GC时间长**：堆内存大，标记整理耗时久。**对策**：换用并行度更高的GC器（如G1/ZGC）。

### case再现：

case代码：

```java
@RestController
@RequestMapping("/gc")
public class GcController {

    @GetMapping("/storm")
    public String gcStorm() {
        for (int i = 0; i < 10000; i++) {
            byte[] data = new byte[1024 * 100]; // 100KB
        }
        return "ok";
    }
}
```

频繁调用

```bash
while true; do curl http://localhost:8080/gc/storm; done
```

### Arthas 排查流程（GC）：

1. 看 GC 实时状态dashboard，   GC count 快速增加，heap 抖动明显，CPU 被 GC 吃掉

![youngGC频繁](/Users/maqidi/Code/me/jvm-tuning/assets/youngGC频繁.png)

2. 查看 GC 详情jvm 

   - G1/Parallel 使用情况

   - heap 使用率

   - GC collectors

![arthas的jvm指令](/Users/maqidi/Code/me/jvm-tuning/assets/arthas的jvm指令.png)

3. 监控方法 RT

```bash
monitor -c 5 com.qidi.jvmtuning.demos.web.GcController gcStorm
```

看：

- avg-rt
- fail-rate
- success-rate

![arthas的monitor指令](/Users/maqidi/Code/me/jvm-tuning/assets/arthas的monitor指令.png)

4. 追踪热点分配

```bash
trace com.qidi.jvmtuning.demos.web.GcController gcStorm
```

定位大对象/高频分配代码。

![arthas的trace指令](/Users/maqidi/Code/me/jvm-tuning/assets/arthas的trace指令.png)

5. 确认 GC 抖动根因

```
tt -t com.qidi.jvmtuning.demos.web.GcController gcStorm
```

![arthas的tt指令](/Users/maqidi/Code/me/jvm-tuning/assets/arthas的tt指令.png)



## 总结Arthas 排查总路线（生产最常用）


| 问题           | 现象               | Arthas 排查路径                           |
| -------------- | ------------------ | ----------------------------------------- |
| CPU 高         | 单机负载高、响应慢 | `dashboard -> thread -n -> thread -> jad` |
| 内存泄漏       | 堆持续上涨、OOM    | `dashboard -> memory -> heapdump -> ognl` |
| GC 频繁/停顿长 | RT 抖动、吞吐下降  | `dashboard -> jvm -> monitor -> trace`    |



# 三、性能调优模版（JDK 8+， G1GC为例）

```bash
# 堆内存: 建议Xms和Xmx设置一致，避免运行时动态调整带来的压力。
-Xms4g -Xmx4g
# 指定使用G1垃圾收集器
-XX:+UseG1GC
# 启用GC日志 (JDK 9+ Unified Logging)
-Xlog:gc*=info:file=gc.log:time,uptime,level,tags:filecount=10,filesize=100M
# 发生OOM时自动生成堆转储
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./java_heapdump.hprof
# 其他G1调优参数（按需调整）
-XX:MaxGCPauseMillis=200       # 设定目标停顿时间
-XX:InitiatingHeapOccupancyPercent=45 # 老年代占用比例达到45%时启动并发标记周期
-XX:MaxMetaspaceSize=256m      # 限制元空间大小，防止无限增长
# (可选) 禁用显式GC调用（防止RMI等调用System.gc()导致Full GC）
-XX:+DisableExplicitGC
```

# 四、上线前JVM检查清单

1. **堆内存**：-Xms 和 -Xmx 是否设置相同？大小是否合理？
2. **GC器**：是否选择了合适的GC器？（如G1/ZGC用于低延迟，Parallel用于高吞吐）
3. **GC日志**：是否已开启GC日志并配置了滚动？
4. **OOM转储**：是否配置了 -XX:+HeapDumpOnOutOfMemoryError？
5. **元空间**：是否设置了 -XX:MaxMetaspaceSize？
6. **监控**：是否集成JMX或APMagent，便于监控？

# 五、案例case

## 案例一：电商应用Full GC频繁

- **现象**：每晚大促高峰期，应用响应变慢，监控平台显示Full GC次数剧增，每分钟达数次。
- **排查**：
- jstat -gcutil <pid> 1s 观察，发现老年代使用率（O）在每次Full GC后仅下降一点点，之后又快速上升直至再次触发Full GC。这是典型的内存泄漏迹象。
- 使用 jmap -dump:format=b,file=peak.dump <pid> 在高峰期生成堆转储。
- 使用 **Eclipse MAT** 分析：

- **Histogram** 显示 java.util.concurrent.ConcurrentHashMap$Node 和 java.lang.Object[] 实例数量异常多。
- **Dominator Tree** 发现一个巨大的静态 HashMap 被一个全局的“缓存管理器”持有。
- 查看该Map的内容，发现里面缓存了商品详情信息，但**没有设置过期时间或LRU淘汰策略**。

- **根因**：本地缓存设计缺陷，缓存只增不减，最终撑爆老年代。
- **解决方案**：
- **短期**：将本地缓存替换为带有LRU淘汰策略的缓存（如 Guava Cache 或 Caffeine），并设置合理的最大容量和过期时间。
- **长期**：引入分布式缓存（如Redis），降低单机JVM的内存压力。

## 案例二：数据查询服务CPU持续100%

- **现象**：单个服务节点CPU usage持续100%，但请求量（QPS）并不高。
- **排查**：
- top -Hp <pid> 发现一个线程CPU占用率接近100%。
- 转换线程ID后，jstack <pid> | grep -A20 <nid> 查看该线程堆栈，发现线程状态为 RUNNABLE，堆栈显示正在执行 java.util.regex.Pattern.matcher(...).find()。
- 使用 **Arthas** 的 trace 命令跟踪该正则表达式方法，发现其调用链很深，且执行耗时极长。
- **根因**：**正则表达式灾难性回溯**。查询接口接收用户输入，直接将一个复杂的、可能包含嵌套量词（如 (a+)+$）的用户输入字符串用作正则匹配模式，导致某些特定输入会引发指数级的时间复杂度。
- **解决方案**：
- **紧急**：对该接口做限流和熔断，并重启服务。
- **根本**：a) 避免使用用户输入直接构建正则模式；b) 如果必须使用，对输入进行严格的校验和过滤；c) 使用更严格的、性能有保障的正则表达式；d) 考虑其他非正则的字符串匹配算法。

# 案例三：微服务网关节点OOM: Metaspace

- **现象**：基于Spring Cloud Gateway的微服务网关节点不定期重启，日志显示 OutOfMemoryError: Metaspace。
- **排查**：
- 查看JVM参数，发现未设置 -XX:MaxMetaspaceSize（默认无限制，但受制于本地内存）。
- 使用 jstat -gcmetacapacity <pid> 观察元空间容量持续增长，且Full GC无法回收。
- 怀疑是**类加载器泄漏**。通过Arthas的 classloader 命令查看，发现大量的 org.springframework.boot.loader.LaunchedURLClassLoader 实例未被卸载。
- **根因**：网关动态路由功能频繁地创建和销毁应用上下文（ApplicationContext），每个上下文都会持有自己的类加载器。由于某些原因（例如，被某个全局线程池中的线程间接引用），这些类加载器无法被垃圾回收，其加载的类也因此无法从元空间中卸载，最终导致元空间被撑爆。
- **解决方案**：
- **治标**：增加元空间大小上限 -XX:MaxMetaspaceSize=256m，并添加元空间GC日志 -Xlog:gc+metaspace*=trace 以便观察。
- **治本**：排查代码中持有类加载器引用的地方，特别是线程局部变量（ThreadLocal）和全局静态变量。确保应用上下文在关闭时能被完全清理。或者，优化网关逻辑，避免频繁创建/销毁上下文。





