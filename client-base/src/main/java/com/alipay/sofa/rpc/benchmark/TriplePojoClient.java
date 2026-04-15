/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.rpc.benchmark;

import com.alipay.sofa.rpc.benchmark.bean.User;
import com.alipay.sofa.rpc.benchmark.mosn.MosnApiClient;
import com.alipay.sofa.rpc.benchmark.service.UserPojoService;
import com.alipay.sofa.rpc.benchmark.service.UserServiceServerImpl;
import com.alipay.sofa.rpc.benchmark.utils.JMHHelper;
import com.alipay.sofa.common.utils.StringUtil;
import com.alipay.sofa.rpc.common.RpcConstants;
import com.alipay.sofa.rpc.config.ConsumerConfig;
import com.alipay.sofa.rpc.transport.SofaStreamObserver;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@State(Scope.Benchmark)
public class TriplePojoClient {

    private static final Logger                   LOGGER      = LoggerFactory
                                                                  .getLogger(TriplePojoClient.class);

    private static int                            CONCURRENCY = 32;

    private static String                         resolvedHost;
    private static String                         resolvedPort;

    static {
        resolvedHost = System.getProperty("server.host", "127.0.0.1");
        resolvedPort = System.getProperty("server.port", "50051");
        String threadNum = System.getProperty("thread.num");
        if (StringUtil.isNotBlank(threadNum)) {
            CONCURRENCY = Integer.parseInt(threadNum);
        }

        // Resolve MOSN endpoint once per JVM to avoid repeated API calls across benchmark modes
        if (Boolean.getBoolean("mosn.enabled")) {
            String appName = System.getProperty("mosn.app.name", "sofa-rpc-benchmark-client");
            MosnApiClient mosnClient = new MosnApiClient();
            mosnClient.configApplication(appName);
            List<MosnApiClient.SubscribeEndpoint> endpoints = mosnClient.subscribeService(
                UserPojoService.class.getName(), "");
            if (!endpoints.isEmpty()) {
                MosnApiClient.SubscribeEndpoint endpoint = endpoints.get(0);
                resolvedHost = endpoint.getHost();
                resolvedPort = String.valueOf(endpoint.getPort());
                LOGGER.info("Subscribed via mosn3, using endpoint: {}", endpoint.getAddress());
            } else {
                LOGGER.warn("No endpoints returned from mosn3 subscribe, falling back to direct address");
            }
        }

        // Bypass system HTTP proxy for local gRPC connections
        String nonProxyHosts = System.getProperty("http.nonProxyHosts", "");
        if (!nonProxyHosts.contains("127.0.0.1")) {
            System.setProperty("http.nonProxyHosts",
                nonProxyHosts.isEmpty() ? "localhost|127.0.0.1" : nonProxyHosts + "|localhost|127.0.0.1");
        }
    }

    private final UserPojoService                 userPojoService;

    private final ConsumerConfig<UserPojoService> consumerConfig;

    private final UserServiceServerImpl           dataSource  = new UserServiceServerImpl();

    private final AtomicInteger                   counter     = new AtomicInteger(0);

    public TriplePojoClient() {
        consumerConfig = new ConsumerConfig<UserPojoService>()
            .setRepeatedReferLimit(10)
            .setInterfaceId(UserPojoService.class.getName())
            .setProtocol("tri")
            .setSerialization(System.getProperty("server.serialization", RpcConstants.SERIALIZE_HESSIAN2))
            .setDirectUrl("tri://" + resolvedHost + ":" + resolvedPort)
            .setTimeout(4000);
        userPojoService = consumerConfig.refer();
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @TearDown
    public void close() {
        consumerConfig.unRefer();
    }

    /**
     * Unary benchmark: single synchronous request-response.
     */
    @Benchmark
    @BenchmarkMode({ Mode.Throughput, Mode.AverageTime, Mode.SampleTime })
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public User unary() {
        long id = counter.getAndIncrement();
        return userPojoService.getUser(id);
    }

    /**
     * Server streaming benchmark: one request, server pushes back a page of users.
     * Measures end-to-end latency including receiving all streamed responses.
     */
    @Benchmark
    @BenchmarkMode({ Mode.Throughput, Mode.AverageTime, Mode.SampleTime })
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public int serverStream() throws InterruptedException {
        int pageNo = counter.getAndIncrement();
        CountDownLatch latch = new CountDownLatch(1);
        final int[] receivedCount = { 0 };
        userPojoService.listUserServerStream(pageNo, new SofaStreamObserver<User>() {
            @Override
            public void onNext(User user) {
                receivedCount[0]++;
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                LOGGER.error("serverStream error", throwable);
                latch.countDown();
            }
        });
        latch.await(4, TimeUnit.SECONDS);
        return receivedCount[0];
    }

    /**
     * Client streaming benchmark: send a batch of users, wait for server summary.
     */
    @Benchmark
    @BenchmarkMode({ Mode.Throughput, Mode.AverageTime, Mode.SampleTime })
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public String clientStream() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        final String[] result = { "" };
        SofaStreamObserver<User> requestObserver = userPojoService
            .batchCreateUserClientStream(new SofaStreamObserver<String>() {
                @Override
                public void onNext(String summary) {
                    result[0] = summary;
                }

                @Override
                public void onCompleted() {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable throwable) {
                    LOGGER.error("clientStream error", throwable);
                    latch.countDown();
                }
            });
        for (int i = 0; i < 5; i++) {
            requestObserver.onNext(dataSource.getUserById(counter.getAndIncrement(), 1));
        }
        requestObserver.onCompleted();
        latch.await(4, TimeUnit.SECONDS);
        return result[0];
    }

    /**
     * Bidirectional streaming benchmark: send users one by one, receive each verified response.
     */
    @Benchmark
    @BenchmarkMode({ Mode.Throughput, Mode.AverageTime, Mode.SampleTime })
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public int biStream() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        final int[] receivedCount = { 0 };
        SofaStreamObserver<User> requestObserver = userPojoService
            .verifyUserBiStream(new SofaStreamObserver<User>() {
                @Override
                public void onNext(User user) {
                    receivedCount[0]++;
                }

                @Override
                public void onCompleted() {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable throwable) {
                    LOGGER.error("biStream error", throwable);
                    latch.countDown();
                }
            });
        for (int i = 0; i < 3; i++) {
            requestObserver.onNext(dataSource.getUserById(counter.getAndIncrement(), 1));
        }
        requestObserver.onCompleted();
        latch.await(4, TimeUnit.SECONDS);
        return receivedCount[0];
    }

    public static void main(String[] args) throws Exception {
        LOGGER.info(Arrays.toString(args));
        ChainedOptionsBuilder optBuilder = JMHHelper.newBaseChainedOptionsBuilder(args)
            .include(TriplePojoClient.class.getSimpleName())
            .threads(CONCURRENCY)
            .forks(1);

        Options opt = optBuilder.build();
        new Runner(opt).run();
    }
}
