/*
 * Copyright 2016 joelauer.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ninja.benchmark;

import com.google.common.base.Stopwatch;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import ninja.standalone.Standalone;
import ninja.standalone.StandaloneHelper;
import okhttp3.ConnectionPool;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.io.Charsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NinjaBenchmark {
    static private final Logger log = LoggerFactory.getLogger(NinjaBenchmark.class);
    
    static public void main(String[] args) throws Exception {
        // spin up standalone based on system property 'ninja.standalone.class'
        Standalone standalone = StandaloneHelper.resolveStandaloneClass().newInstance()
            .port(StandaloneHelper.findAvailablePort(8000, 9000))
            .start();
        
        final int requests = 50000;
        final int threads = 50;
        
        final NinjaBenchmark ninjaBenchmark = new NinjaBenchmark(standalone);
        
        final BenchmarkCall[] calls = new BenchmarkCall[] {
            new BenchmarkCall("get_with_params", ninjaBenchmark.buildGetWithParamsRequest()),
            new BenchmarkCall("post_object_as_form", ninjaBenchmark.buildPostObjectAsFormRequest()),
            new BenchmarkCall("post_object_as_json", ninjaBenchmark.buildPostObjectAsJsonRequest())
        };
        
        log.info("Running benchmarks with " + standalone.getClass().getCanonicalName());
        log.info("----------------------------------------------");
        log.info(" threads: " + threads);
        log.info("requests: " + requests);
        
        for (BenchmarkCall call : calls) {
            ninjaBenchmark.execute(threads, requests, call);
        }
        
        standalone.shutdown();
        
        // output final results
        System.out.println("Benchmark results for " + standalone.getClass().getCanonicalName());
        System.out.println("----------------------------------------------------");
        System.out.println(" threads: " + threads);
        System.out.println("requests: " + requests);
        for (BenchmarkCall call : calls) {
            System.out.printf(call.getName() + " benchmark: " + call.getElapsed() + " ms (%.2f/sec)\n", call.requestsPerSecond(requests));
        }
    }
    
    final Standalone standalone;

    public NinjaBenchmark(Standalone standalone) {
        this.standalone = standalone;
    }
    
    public Request buildGetWithParamsRequest() {
        return
            new Request.Builder()
                .url(standalone.getBaseUrls().get(0) + "/benchmark_params?a=adam&b=bob&c=charlie&d=david")
                .header("Cookie", "TEST=THISISATESTCOOKIEHEADER")
                .build();
    }
    
    public Request buildPostObjectAsFormRequest() {
        return
            new Request.Builder()
                .url(standalone.getBaseUrls().get(0) + "/benchmark_object?a=adam&b=bob")
                .header("Cookie", "TEST=THISISATESTCOOKIEHEADER")
                .post(new FormBody.Builder()
                    .add("s", "sam")
                    .add("i", "2")
                    .add("l", "10000000")
                    .add("b", "true")
                    .build())
                .build();
    }
    
    public Request buildPostObjectAsJsonRequest() {
        byte[] json = "{ \"s\":\"sam\", \"i\":2, \"l\":10000000, \"b\":true  }".getBytes(Charsets.UTF_8);
        return
            new Request.Builder()
                .url(standalone.getBaseUrls().get(0) + "/benchmark_object?a=adam&b=bob")
                .header("Cookie", "TEST=THISISATESTCOOKIEHEADER")
                .post(RequestBody.create(MediaType.parse("application/json"), json))
                .build();
    }
    
    static public class BenchmarkCall {
        private final String name;
        private final Request request;
        private long elapsed;

        public BenchmarkCall(String name, Request request) {
            this.name = name;
            this.request = request;
        }

        public String getName() {
            return name;
        }

        public Request getRequest() {
            return request;
        }
        
        public long getElapsed() {
            return elapsed;
        }

        public void setElapsed(long elapsed) {
            this.elapsed = elapsed;
        }
        
        public double requestsPerSecond(final int requests) {
            return ((double)requests)/(((float)elapsed)/(float)1000);
        }
    }
    
    public void execute(final int threads, final int requests, final BenchmarkCall call) throws IOException, InterruptedException {
        final OkHttpClient client = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(threads, 60000L, TimeUnit.MILLISECONDS))
            .build();
        
        final AtomicInteger requested = new AtomicInteger();
        
        final Request request = call.request;
        
        // warmup by doing small # of requests first
        for (int i = 0; i < 20; i++) {
            Response response = client.newCall(request).execute();
            response.body().close();
        }
        
        final CountDownLatch startSignal = new CountDownLatch(1);
        final CountDownLatch doneSignal = new CountDownLatch(threads);
        ExecutorService threadPool = Executors.newFixedThreadPool(threads);
        
        for (int i = 0; i < threads; i++) {
            threadPool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        startSignal.await();
                        while (requested.incrementAndGet() < requests) {
                            Response response = client.newCall(request).execute();
                            response.body().close();
                        }
                        doneSignal.countDown();
                    } catch (InterruptedException | IOException e) {
                        e.printStackTrace(System.err);
                    }
                }
            });
        }
        
        
        // real
        Stopwatch stopwatch = Stopwatch.createStarted();
        startSignal.countDown();
        doneSignal.await();
        stopwatch.stop();
        call.setElapsed(stopwatch.elapsed(TimeUnit.MILLISECONDS));
        
        threadPool.shutdown();
    }
    
}
