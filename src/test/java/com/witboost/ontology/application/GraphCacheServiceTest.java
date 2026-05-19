package com.witboost.ontology.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraphCacheServiceTest {

  private GraphCacheService cache;

  @BeforeEach
  void setUp() {
    cache = new GraphCacheService();
  }

  @Test
  void getOrBuild_firstCall_invokesBuilder() {
    AtomicInteger calls = new AtomicInteger();
    String result =
        cache.getOrBuild(
            () -> {
              calls.incrementAndGet();
              return "graph";
            });
    assertThat(result).isEqualTo("graph");
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void getOrBuild_secondCall_returnsCachedWithoutCallingBuilder() {
    AtomicInteger calls = new AtomicInteger();
    cache.getOrBuild(
        () -> {
          calls.incrementAndGet();
          return "graph";
        });
    cache.getOrBuild(
        () -> {
          calls.incrementAndGet();
          return "graph2";
        });
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void invalidate_causesNextCallToRebuild() {
    AtomicInteger calls = new AtomicInteger();
    cache.getOrBuild(
        () -> {
          calls.incrementAndGet();
          return "v1";
        });
    cache.invalidate();
    String result =
        cache.getOrBuild(
            () -> {
              calls.incrementAndGet();
              return "v2";
            });
    assertThat(result).isEqualTo("v2");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void concurrentInvalidateAndRead_producesNonNullResult() throws InterruptedException {
    cache.getOrBuild(() -> "initial");
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger nullCount = new AtomicInteger();

    ExecutorService exec = Executors.newFixedThreadPool(2);
    exec.submit(
        () -> {
          try {
            latch.await();
          } catch (InterruptedException ignored) {
          }
          cache.invalidate();
        });
    exec.submit(
        () -> {
          try {
            latch.await();
          } catch (InterruptedException ignored) {
          }
          String v = cache.getOrBuild(() -> "rebuilt");
          if (v == null) nullCount.incrementAndGet();
        });

    latch.countDown();
    exec.shutdown();
    exec.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(nullCount.get()).isZero();
  }
}
