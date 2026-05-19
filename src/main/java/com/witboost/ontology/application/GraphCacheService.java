package com.witboost.ontology.application;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Single-entry cache for the merged JSON-LD graph (FR-012, R-006).
 *
 * <p>Thread-safety is provided by a {@link ReentrantLock} shared between {@link #getOrBuild} and
 * {@link #invalidate}:
 *
 * <ul>
 *   <li><b>Double-checked locking</b>: the volatile read is performed without the lock so
 *       cache-hits are cheap; the lock is only acquired when the cache appears empty, and the check
 *       is repeated inside the lock to prevent two threads from each triggering a rebuild.
 *   <li><b>Invalidate-rebuild ordering</b>: because {@code invalidate} also acquires the same lock,
 *       a concurrent invalidate cannot race with a rebuild — if invalidation wins, the just-built
 *       value is immediately cleared; if the rebuild wins, invalidation will clear it on the next
 *       call, which is the correct outcome.
 * </ul>
 */
@Service
@Slf4j
public class GraphCacheService {

  private volatile String cachedJsonLd;
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * Returns the cached merged graph, or builds and caches it if the cache is empty.
   *
   * @param builder supplies the merged JSON-LD when the cache is stale
   */
  public String getOrBuild(Supplier<String> builder) {
    String cached = cachedJsonLd; // volatile read — fast path, no lock needed
    if (cached != null) {
      log.debug("Graph cache hit");
      return cached;
    }
    lock.lock();
    try {
      cached = cachedJsonLd; // re-check: another thread may have built it while we waited
      if (cached != null) {
        log.debug("Graph cache hit (after lock)");
        return cached;
      }
      log.info("Graph cache miss — rebuilding from DB");
      String built = builder.get();
      cachedJsonLd = built;
      return built;
    } finally {
      lock.unlock();
    }
  }

  /** Invalidates the cache so the next {@link #getOrBuild} call triggers a rebuild. */
  public void invalidate() {
    lock.lock();
    try {
      cachedJsonLd = null;
      log.debug("Graph cache invalidated");
    } finally {
      lock.unlock();
    }
  }
}
