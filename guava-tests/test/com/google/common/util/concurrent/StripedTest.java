// Copyright 2011 Google Inc. All Rights Reserved.

package com.google.common.util.concurrent;

import static com.google.common.collect.Iterables.concat;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.testing.GcFinalization;
import com.google.common.testing.NullPointerTester;

import junit.framework.TestCase;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;

/**
 * Tests for Striped.
 *
 * @author andreou@google.com (Dimitris Andreou)
 */
public class StripedTest extends TestCase {
  private static List<Striped<?>> strongImplementations() {
    return ImmutableList.of(
        Striped.readWriteLock(100),
        Striped.readWriteLock(256),
        Striped.lock(100),
        Striped.lock(256),
        Striped.semaphore(100, 1),
        Striped.semaphore(256, 1));
  }

  private static List<Striped<?>> weakImplementations() {
    return ImmutableList.of(
        Striped.lazyWeakReadWriteLock(50),
        Striped.lazyWeakReadWriteLock(64),
        Striped.lazyWeakLock(50),
        Striped.lazyWeakLock(64),
        Striped.lazyWeakSemaphore(50, 1),
        Striped.lazyWeakSemaphore(64, 1));
  }

  private static Iterable<Striped<?>> allImplementations() {
    return concat(strongImplementations(), weakImplementations());
  }

  public void testNull() throws Exception {
    for (Striped<?> striped : allImplementations()) {
      new NullPointerTester().testAllPublicInstanceMethods(striped);
    }
  }

  public void testSizes() {
    // not bothering testing all variations, since we know they share implementations
    assertTrue(Striped.lock(100).size() >= 100);
    assertTrue(Striped.lock(256).size() == 256);
    assertTrue(Striped.lazyWeakLock(100).size() >= 100);
    assertTrue(Striped.lazyWeakLock(256).size() == 256);
  }

  public void testWeakImplementations() {
    for (Striped<?> striped : weakImplementations()) {
      WeakReference<Object> weakRef = new WeakReference<Object>(striped.get(new Object()));
      GcFinalization.awaitClear(weakRef);
    }
  }

  public void testStrongImplementations() {
    for (Striped<?> striped : strongImplementations()) {
      WeakReference<Object> weakRef = new WeakReference<Object>(striped.get(new Object()));
      WeakReference<Object> garbage = new WeakReference<Object>(new Object());
      GcFinalization.awaitClear(garbage);
      assertNotNull(weakRef.get());
    }
  }

  public void testMaximalWeakStripedLock() {
    Striped<Lock> stripedLock = Striped.lazyWeakLock(Integer.MAX_VALUE);
    for (int i = 0; i < 10000; i++) {
      stripedLock.get(new Object()).lock();
      // nothing special (e.g. an exception) happens
    }
  }

  public void testBulkGetReturnsSorted() {
    for (Striped<?> striped : allImplementations()) {
      Map<Object, Integer> indexByLock = Maps.newHashMap();
      for (int i = 0; i < striped.size(); i++) {
        indexByLock.put(striped.getAt(i), i);
      }

      // ensure that bulkGet returns locks in monotonically increasing order
      for (int objectsNum = 1; objectsNum <= striped.size() * 2; objectsNum++) {
        Set<Object> objects = Sets.newHashSetWithExpectedSize(objectsNum);
        for (int i = 0; i < objectsNum; i++) {
          objects.add(new Object());
        }

        Iterable<?> locks = striped.bulkGet(objects);
        assertTrue(Ordering.natural().onResultOf(Functions.forMap(indexByLock)).isOrdered(locks));

        // check idempotency
        Iterable<?> locks2 = striped.bulkGet(objects);
        assertEquals(Lists.newArrayList(locks), Lists.newArrayList(locks2));
      }
    }
  }

  /**
   * Checks idempotency, and that we observe the promised number of stripes.
   */
  public void testBasicInvariants() {
    for (Striped<?> striped : allImplementations()) {
      assertBasicInvariants(striped);
    }
  }

  private static void assertBasicInvariants(Striped<?> striped) {
    Set<Object> observed = Sets.newIdentityHashSet(); // for the sake of weakly referenced locks.
    // this gets the stripes with #getAt(index)
    for (int i = 0; i < striped.size(); i++) {
      Object object = striped.getAt(i);
      assertNotNull(object);
      assertSame(object, striped.getAt(i)); // idempotent
      observed.add(object);
    }
    assertTrue("All stripes observed", observed.size() == striped.size());

    // this uses #get(key), makes sure an already observed stripe is returned
    for (int i = 0; i < striped.size() * 100; i++) {
      assertTrue(observed.contains(striped.get(new Object())));
    }

    try {
      striped.getAt(-1);
      fail();
    } catch (RuntimeException expected) {}

    try {
      striped.getAt(striped.size());
      fail();
    } catch (RuntimeException expected) {}
  }
}