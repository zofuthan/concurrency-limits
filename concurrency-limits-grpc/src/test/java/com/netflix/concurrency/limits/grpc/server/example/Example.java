package com.netflix.concurrency.limits.grpc.server.example;

import com.netflix.concurrency.limits.grpc.server.GrpcServerLimiterBuilder;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
import com.netflix.concurrency.limits.limit.GradientLimit;
import com.netflix.concurrency.limits.limit.WindowedLimit;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class Example {
    public static void main(String[] args) throws IOException {
        final Gradient2Limit limit = Gradient2Limit.newBuilder()
                .shortWindow(10)
                .longWindow(100)
                .build();
        
        // Create a server
        final TestServer server = TestServer.newBuilder()
            .concurrency(2)
            .lognormal(20, 1, TimeUnit.MINUTES)
            .limiter(
                new GrpcServerLimiterBuilder()
                        .limit(WindowedLimit.newBuilder()
                                .minWindowTime(1, TimeUnit.SECONDS)
                                .windowSize(10)
                                .build(limit))
                .build()
                )
            .build();

        final AtomicInteger successCounter = new AtomicInteger(0);
        final AtomicInteger dropCounter = new AtomicInteger(0);
        final LatencyCollector latency = new LatencyCollector();

        final Driver driver = Driver.newBuilder()
            .exponentialRps(100, 60, TimeUnit.SECONDS)
            .exponentialRps(200, 500, TimeUnit.SECONDS)
            .exponentialRps(100, 500, TimeUnit.SECONDS)
            .exponentialRps(75,  500, TimeUnit.SECONDS)
            .successAction(successCounter::incrementAndGet)
            .dropAction(dropCounter::incrementAndGet)
            .latencyAccumulator(latency)
            .runtime(1, TimeUnit.HOURS)
            .port(server.getPort())
            .build();

        // Report progress
        final AtomicInteger counter = new AtomicInteger(0);
        System.out.println("iteration, limit, success, drop, latency, shortRtt, longRtt");
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            System.out.println(MessageFormat.format("{0,number,#}, {1,number,#}, {2,number,#}, {3,number,#}, {4,number,#}, {5,number,#}, {6,number,#}",
                    counter.incrementAndGet(), 
                    limit.getLimit(), 
                    successCounter.getAndSet(0), 
                    dropCounter.getAndSet(0),
                    TimeUnit.NANOSECONDS.toMillis(latency.getAndReset()),
                    limit.getShortRtt(TimeUnit.MILLISECONDS),
                    limit.getLongRtt(TimeUnit.MILLISECONDS)
                    ))  ;
        }, 1, 1, TimeUnit.SECONDS);
        
        // Create a client
        driver.run();
    }
    
    public static class Metrics {
        long count;
        long total;
        
        public Metrics() {
            this(0, 0);
        }
        
        public Metrics(long count, long total) {
            this.count = count;
            this.total = total;
        }

        public long average() {
            if (this.count == 0) 
                return 0;
            return this.total / this.count;
        }
    }
    
    public static class LatencyCollector implements Consumer<Long> {
        AtomicReference<Metrics> foo = new AtomicReference<Metrics>(new Metrics());
        
        @Override
        public void accept(Long sample) {
            foo.getAndUpdate(current -> new Metrics(current.count + 1, current.total + sample));
        }
        
        public long getAndReset() {
            return foo.getAndSet(new Metrics()).average();
        }
    }
}
