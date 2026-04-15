# SOFARPC Benchmark

This project focuses on benchmarking and profiling SOFARPC with different protocol and serialization options. The code and the idea behind it is inspired by [Dubbo Benchmark](https://github.com/apache/dubbo-benchmark).

## Modules

| Module | Protocol | Description |
|--------|----------|-------------|
| `sofa-rpc-bolt-hessian-server` / `client` | Bolt + Hessian | JMH benchmark |
| `sofa-rpc-triple-pojo-server` / `client` | Triple (HTTP/2) | JMH benchmark, tests `UserPojoService` (POJO, unary + streaming) |
| `sofa-rpc-triple-proto-server` / `client` | Triple (HTTP/2) | JMH benchmark, tests `IUserService` (Protobuf, unary + streaming) |

---

## Bolt + Hessian Benchmark

### Run Benchmark

```bash
# Terminal 1: start server (default port 12200)
./benchmark.sh sofa-rpc-bolt-hessian-server

# Terminal 2: start client
./benchmark.sh sofa-rpc-bolt-hessian-client
```

### Run Profiling

```bash
./benchmark.sh -m profiling sofa-rpc-bolt-hessian-server
./benchmark.sh -m profiling sofa-rpc-bolt-hessian-client
```

---

## Triple Benchmark

`TripleClient` benchmarks all four call modes of `UserPojoService`:

| Benchmark | Call Mode |
|-----------|-----------|
| `unary` | Synchronous unary (`getUser`) |
| `serverStream` | Server streaming (`listUserServerStream`) |
| `clientStream` | Client streaming (`batchCreateUserClientStream`) |
| `biStream` | Bidirectional streaming (`verifyUserBiStream`) |

### Run Benchmark

```bash
# Terminal 1: start server (default port 50051)
./benchmark.sh -p 50051 sofa-rpc-triple-pojo-server

# Terminal 2: start client
# Note: use 127.0.0.1 instead of localhost to avoid IPv6 proxy issues
./benchmark.sh -s 127.0.0.1 -p 50051 sofa-rpc-triple-pojo-client
```

### Specify Serialization

Triple Pojo supports configurable serialization via `-S` (default: `hessian2`).
**Both server and client must use the same serialization type.**

```bash
# Use JSON serialization
./benchmark.sh -s 127.0.0.1 -p 50051 -S json sofa-rpc-triple-pojo-client
```

---

## Triple Proto Benchmark

`TripleProtoClient` benchmarks all four call modes of `IUserService` using **Protobuf** wire format:

| Benchmark | Call Mode |
|-----------|-----------|
| `unary` | Synchronous unary (`getUser`) |
| `serverStream` | Server streaming (`listUserServerStream`) |
| `clientStream` | Client streaming (`batchCreateUser`) |
| `biStream` | Bidirectional streaming (`verifyUserBiStream`) |

### Run Benchmark

```bash
# Terminal 1: start server (default port 50052)
./benchmark.sh -p 50052 sofa-rpc-triple-proto-server

# Terminal 2: start client
./benchmark.sh -s 127.0.0.1 -p 50052 sofa-rpc-triple-proto-client
```

---

## Common Parameters

### Shell Parameters (`benchmark.sh`)

| Flag | Description | Default | Example |
|------|-------------|---------|---------|
| `-m` | Benchmark mode: `benchmark` or `profiling` | `benchmark` | `-m profiling` |
| `-s` | Server hostname | `localhost` | `-s 127.0.0.1` |
| `-p` | Server port | `12200` | `-p 50051` |
| `-f` | JMH console output file path | _(none)_ | `-f output.log` |
| `-t` | Client concurrent thread count | `32` | `-t 64` |
| `-S` | Serialization type (e.g. `hessian2`, `protobuf`, `json`) | _(none)_ | `-S json` |
| `-e` | Extra JVM system properties | _(none)_ | `-e "-Dresult.format=TEXT"` |
| `-a` | Extra JMH arguments (passed to the JAR) | _(none)_ | `-a "--warmupIterations=5"` |
| `-M` | Enable MOSN3 sidecar mode | `false` | `-M` |
| `-A` | MOSN3 API address | `http://127.0.0.1:13330` | `-A http://10.0.0.1:13330` |
| `-N` | MOSN3 application name | `sofa-rpc-benchmark` | `-N my-app` |

### JMH Arguments (via `-a`)

These arguments control JMH's warmup and measurement behavior, passed through the `-a` flag:

| Argument | Description | Default | Example |
|----------|-------------|---------|---------|
| `--warmupIterations` | Number of warmup rounds (data **not** recorded). Warmup ensures JIT compilation, class loading, and connection pool initialization are complete before measurement. | `3` | `--warmupIterations=5` |
| `--warmupTime` | Duration of each warmup round (seconds) | `10` | `--warmupTime=15` |
| `--measurementIterations` | Number of measurement rounds (data **recorded**). Multiple rounds enable calculation of score error and confidence intervals. | `3` | `--measurementIterations=5` |
| `--measurementTime` | Duration of each measurement round (seconds) | `60` | `--measurementTime=120` |
| `--forks` | Number of JVM forks. Each fork runs in a fresh JVM to reduce cross-run interference. | `1` | `--forks=2` |
| `--enableGcProfiler` | Enable JMH GC profiler to collect GC count, GC time, and allocation rate metrics | `true` | `--enableGcProfiler=false` |

### JVM System Properties (via `-e`)

| Property | Description | Default |
|----------|-------------|---------|
| `result.format` | JMH result file format: `JSON`, `CSV`, or `TEXT` | `JSON` |
| `request.size` | Request payload size in bytes | _(benchmark default)_ |

### Benchmark Modes

Each benchmark method runs in three modes to provide comprehensive performance data:

| Mode | Metric | Description |
|------|--------|-------------|
| `Throughput` | ops/ms | Number of operations completed per millisecond |
| `AverageTime` | ms/op | Average time per operation |
| `SampleTime` | ms/op | Sampled time distribution with percentiles (P50, P90, P95, P99, P99.9) |

### Result Output

JMH result files are automatically named with a timestamp for easy comparison:

```
jmh-result-202604151400.json
```

The format is `jmh-result-YYYYMMDDHHMM.<ext>`, where the extension matches the configured `result.format`.

### Examples

```bash
# Basic: specify port, host, output file, thread count
./benchmark.sh -p 12201 sofa-rpc-bolt-hessian-server
./benchmark.sh -s 127.0.0.1 -p 12201 -f output.log -t 64 sofa-rpc-bolt-hessian-client

# Custom warmup and measurement iterations
./benchmark.sh -a "--warmupIterations=5 --warmupTime=15 --measurementIterations=5 --measurementTime=120" sofa-rpc-bolt-hessian-client

# Use 2 JVM forks with GC profiler disabled
./benchmark.sh -a "--forks=2 --enableGcProfiler=false" sofa-rpc-bolt-hessian-client

# Change result format to CSV and set request size
./benchmark.sh -e "-Dresult.format=CSV -Drequest.size=10240" sofa-rpc-bolt-hessian-client

# Enable MOSN3 sidecar mode
./benchmark.sh -M -A http://10.0.0.1:13330 -N my-app sofa-rpc-bolt-hessian-client
```
