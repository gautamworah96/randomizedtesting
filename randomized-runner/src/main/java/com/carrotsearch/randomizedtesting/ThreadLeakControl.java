package com.carrotsearch.randomizedtesting;

import static com.carrotsearch.randomizedtesting.RandomizedRunner.DEFAULT_KILLATTEMPTS;
import static com.carrotsearch.randomizedtesting.RandomizedRunner.DEFAULT_KILLWAIT;
import static com.carrotsearch.randomizedtesting.RandomizedRunner.DEFAULT_TIMEOUT;
import static com.carrotsearch.randomizedtesting.RandomizedRunner.DEFAULT_TIMEOUT_SUITE;
import static com.carrotsearch.randomizedtesting.RandomizedTest.systemPropertyAsInt;
import static com.carrotsearch.randomizedtesting.SysGlobals.SYSPROP_KILLATTEMPTS;
import static com.carrotsearch.randomizedtesting.SysGlobals.SYSPROP_KILLWAIT;
import static com.carrotsearch.randomizedtesting.SysGlobals.SYSPROP_TIMEOUT;
import static com.carrotsearch.randomizedtesting.SysGlobals.SYSPROP_TIMEOUT_SUITE;

import java.lang.annotation.Annotation;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runner.notification.StoppedByUserException;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;

import com.carrotsearch.randomizedtesting.RandomizedRunner.TestCandidate;
import com.carrotsearch.randomizedtesting.RandomizedRunner.UncaughtException;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakAction;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakAction.Action;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakGroup;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakLingering;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope.Scope;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakZombies;
import com.carrotsearch.randomizedtesting.annotations.Timeout;
import com.carrotsearch.randomizedtesting.annotations.TimeoutSuite;

/**
 * Everything corresponding to thread leak control. This is very, very fragile to changes
 * because of how threads interact and where they can be spun off.
 */
class ThreadLeakControl {
  /** A dummy class serving as the source of defaults for annotations. */
  @ThreadLeakScope
  @ThreadLeakAction
  @ThreadLeakLingering
  @ThreadLeakZombies
  @ThreadLeakFilters
  @ThreadLeakGroup
  private static class DefaultAnnotationValues {}

  /**
   * Shared logger.
   */
  private final static Logger logger = RandomizedRunner.logger;

  /** 
   * How many attempts to interrupt and then kill a runaway thread before giving up?
   */
  private final int killAttempts;

  /**
   * How long to wait between attempts to kill a runaway thread (millis). 
   */
  private final int killWait;

  /**
   * Target notifier.
   */
  private final RunNotifier targetNotifier;

  /**
   * This is the assumed set of threads without leaks.
   */
  private final Set<Thread> expectedSuiteState;

  /**
   * Atomic section for passing notifier events.  
   */
  private final Object notifierLock = new Object();

  /**
   * @see SubNotifier
   */
  private final SubNotifier subNotifier;

  /**
   * Test timeout.
   */
  private TimeoutValue testTimeout;
  
  /**
   * Suite timeout.
   */
  private TimeoutValue suiteTimeout;

  /**
   * Built-in filters.
   */
  private final List<ThreadFilter> builtinFilters;
  
  /**
   * User filter (compound).
   */
  private ThreadFilter suiteFilters;

  /**
   * The governing runner.
   */
  private final RandomizedRunner runner;

  /**
   * Suite timeout.
   */
  private AtomicBoolean suiteTimedOut = new AtomicBoolean();

  /**
   * Thread leak detection group.
   */
  ThreadLeakGroup threadLeakGroup;

  /**
   * Sub-notifier that controls passing events back in case of timeouts.
   */
  private class SubNotifier extends RunNotifier {
    private boolean stopRequested = false;
    Description testInProgress;

    @Override
    public void addListener(RunListener listener) { throw new UnsupportedOperationException(); }
    @Override
    public void addFirstListener(RunListener listener) { throw new UnsupportedOperationException(); }
    @Override
    public void removeListener(RunListener listener) { throw new UnsupportedOperationException(); }
    @Override
    public void fireTestRunFinished(Result result) { throw new UnsupportedOperationException(); }
    @Override
    public void fireTestRunStarted(Description description) { throw new UnsupportedOperationException(); }

    @Override
    public void fireTestStarted(Description description) throws StoppedByUserException {
      synchronized (notifierLock) {
        if (stopRequested) return;
        targetNotifier.fireTestStarted(description);
        testInProgress = description;
      }
    }

    @Override
    public void fireTestAssumptionFailed(Failure failure) {
      synchronized (notifierLock) {
        if (stopRequested) return;
        targetNotifier.fireTestAssumptionFailed(failure);
      }
    }

    @Override
    public void fireTestFailure(Failure failure) {
      synchronized (notifierLock) {
        if (stopRequested) return;
        targetNotifier.fireTestFailure(failure);
      }
    }
    
    @Override
    public void fireTestIgnored(Description description) {
      synchronized (notifierLock) {
        if (stopRequested) return;
        testInProgress = null;
        targetNotifier.fireTestIgnored(description);
      }
    }

    @Override
    public void fireTestFinished(Description description) {
      synchronized (notifierLock) {
        if (stopRequested) return;
        testInProgress = null;
        targetNotifier.fireTestFinished(description);
      }
    }

    /**
     * Detach from target notifier.
     */
    @Override
    public void pleaseStop() {
      stopRequested = true;
    }
  }

  /**
   * Timeout parsing code and logic.
   */
  private static class TimeoutValue {
    private final int timeoutOverride;
    private final boolean globalTimeoutFirst;

    TimeoutValue(String sysprop, int defaultValue) {
      String timeoutValue = System.getProperty(sysprop);
      boolean globalTimeoutFirst = false;
      if (timeoutValue == null || timeoutValue.trim().length() == 0) {
        timeoutValue = null;
      }
      if (timeoutValue != null) {
        // Check for timeout precedence.
        globalTimeoutFirst = timeoutValue.matches("[0-9]+\\!");
        timeoutValue = timeoutValue.replaceAll("\\!", "");
      } else {
        timeoutValue = Integer.toString(defaultValue);
      }

      this.timeoutOverride = Integer.parseInt(timeoutValue);
      this.globalTimeoutFirst = globalTimeoutFirst;
    }

    int getTimeout(Integer value) {
      if (globalTimeoutFirst) {
        return timeoutOverride;
      } else {
        return value != null ? value : timeoutOverride;
      }
    }
  }

  /** */
  private static class ThisThreadFilter implements ThreadFilter {
    private final Thread t;

    public ThisThreadFilter(Thread t) {
      this.t = t;
    }

    @Override
    public boolean reject(Thread t) {
      return this.t == t;
    }
  }
  
  private static ThreadFilter or(final ThreadFilter... filters) {
    return new ThreadFilter() {
      @Override
      public boolean reject(Thread t) {
        boolean reject = false;
        for (ThreadFilter f : filters) {
          if (reject |= f.reject(t)) {
            break;
          }
        }
        return reject;
      }
    };
  }

  /** */
  private static class KnownSystemThread implements ThreadFilter {
    @Override
    public boolean reject(Thread t) {
      // Explicit check for system group.
      ThreadGroup tgroup = t.getThreadGroup();
      if (tgroup != null && "system".equals(tgroup.getName()) && tgroup.getParent() == null) {
        return true;
      }

      List<StackTraceElement> stack = new ArrayList<StackTraceElement>(Arrays.asList(t.getStackTrace()));
      Collections.reverse(stack);

      // Explicit check for TokenPoller (MessageDigest spawns it).
      if ((stack.size() >= 2 && 
           stack.get(1).getClassName().startsWith("sun.security.pkcs11.SunPKCS11$TokenPoller")) ||
         t.getName().contains("Poller SunPKCS11")) {
        return true;
      }

      // Explicit check for GC$Daemon
      if (stack.size() >= 1 && 
          stack.get(0).getClassName().startsWith("sun.misc.GC$Daemon")) {
        System.out.println(t.getName());
        return true;
      }
      
      return false;
    }
  }

  /** */
  ThreadLeakControl(RunNotifier notifier, RandomizedRunner runner) {
    this.targetNotifier = notifier;
    this.subNotifier = new SubNotifier();
    this.runner = runner;

    this.killAttempts = systemPropertyAsInt(SYSPROP_KILLATTEMPTS(), DEFAULT_KILLATTEMPTS);
    this.killWait = systemPropertyAsInt(SYSPROP_KILLWAIT(), DEFAULT_KILLWAIT);

    // Determine default timeouts.
    testTimeout = new TimeoutValue(SYSPROP_TIMEOUT(), DEFAULT_TIMEOUT);
    suiteTimeout = new TimeoutValue(SYSPROP_TIMEOUT_SUITE(), DEFAULT_TIMEOUT_SUITE);

    builtinFilters = Arrays.asList(
           new ThisThreadFilter(Thread.currentThread()),
           new KnownSystemThread());

    // Determine a set of expected threads up front (unfiltered).
    expectedSuiteState = Collections.unmodifiableSet(Threads.getAllThreads());
  }

  /**
   * Runs a {@link Statement} and keeps any exception and 
   * completion flag. 
   */
  private static class StatementRunner implements Runnable {
    private final Statement s;

    volatile Throwable error;
    volatile boolean completed;

    StatementRunner(Statement s) {
      this.s = s;
    }

    public void run() {
      try {
        s.evaluate();
      } catch (Throwable t) {
        error = t;
      } finally {
        completed = true;
      }
    }
  }

  /**
   * A {@link Statement} for wrapping suite-level execution. 
   */
  Statement forSuite(final Statement s, final Description suiteDescription) {
    final Class<?> suiteClass = suiteDescription.getTestClass();
    final int timeout = determineTimeout(suiteClass);

    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        RandomizedRunner.checkZombies();

        threadLeakGroup = firstAnnotated(ThreadLeakGroup.class, suiteClass, DefaultAnnotationValues.class);
        final List<Throwable> errors = new ArrayList<Throwable>();
        suiteFilters = instantiateFilters(errors, suiteClass);
        MultipleFailureException.assertEmpty(errors);

        final StatementRunner sr = new StatementRunner(s);
        final boolean timedOut = forkTimeoutingTask(sr, timeout, errors);

        synchronized (notifierLock) {
          if (timedOut) {
            // Mark as timed out so that we don't do any checks in any currently running test
            suiteTimedOut.set(true);
            // Emit a warning.
            logger.warning("Suite execution timed out: " + suiteDescription + threadStacksFull());
            // mark subNotifier as dead (no longer passing events).
            subNotifier.pleaseStop();
          }
        }

        if (timedOut) {
          // complete subNotifier state in case in the middle of a test.
          if (subNotifier.testInProgress != null) {
            targetNotifier.fireTestFailure(
                new Failure(subNotifier.testInProgress,
                RandomizedRunner.augmentStackTrace(
                    emptyStack(new Exception("Test abandoned because suite timeout was reached.")))));
          }

          // throw suite failure (timeout).
          errors.add(RandomizedRunner.augmentStackTrace(
              emptyStack(new Exception("Suite timeout exceeded (>= " + timeout + " msec)."))));        
        }

        final AnnotatedElement [] chain = { suiteClass, DefaultAnnotationValues.class };
        List<Throwable> threadLeakErrors = timedOut ? new ArrayList<Throwable>() : errors;
        checkThreadLeaks(
            refilter(expectedSuiteState, suiteFilters), threadLeakErrors, LifecycleScope.SUITE, suiteDescription, chain);
        processUncaught(errors, runner.handler.getUncaughtAndClear());

        MultipleFailureException.assertEmpty(errors);
      }
    };
  }

  /**
   * A {@link Statement} for wrapping test-level execution. 
   */
  Statement forTest(final Statement s, final TestCandidate c) {
    final int timeout = determineTimeout(c);

    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        RandomizedRunner.checkZombies();

        final StatementRunner sr = new StatementRunner(s);
        final List<Throwable> errors = new ArrayList<Throwable>();
        final Set<Thread> beforeTestState = threadsSnapshot(suiteFilters).keySet();
        final boolean timedOut = forkTimeoutingTask(sr, timeout, errors);

        if (suiteTimedOut.get()) {
          return;
        }

        if (timedOut) {
          logger.warning("Test execution timed out: " + c.description + threadStacksFull());
        }

        if (timedOut) {
          errors.add(RandomizedRunner.augmentStackTrace(
              emptyStack(new Exception("Test timeout exceeded (>= " + timeout + " msec)."))));
        }

        final AnnotatedElement [] chain = 
          { c.method, c.instanceProvider.getTestClass(), DefaultAnnotationValues.class };
        List<Throwable> threadLeakErrors = timedOut ? new ArrayList<Throwable>() : errors;
        checkThreadLeaks(beforeTestState, threadLeakErrors, LifecycleScope.TEST, c.description, chain);
        processUncaught(errors, runner.handler.getUncaughtAndClear());

        MultipleFailureException.assertEmpty(errors);
      }
    };
  }

  /**
   * Refilter a set of threads
   */
  protected Set<Thread> refilter(Set<Thread> in, ThreadFilter f) {
    HashSet<Thread> t = new HashSet<Thread>(in);
    for (Iterator<Thread> i = t.iterator(); i.hasNext();) {
      if (f.reject(i.next())) {
        i.remove();
      }
    }
    return t;
  }

  /**
   * Instantiate a full set of {@link ThreadFilter}s for a suite.
   */
  private ThreadFilter instantiateFilters(List<Throwable> errors, Class<?> suiteClass) {
    ThreadLeakFilters ann = 
        firstAnnotated(ThreadLeakFilters.class, suiteClass, DefaultAnnotationValues.class);

    final ArrayList<ThreadFilter> filters = new ArrayList<ThreadFilter>();
    for (Class<? extends ThreadFilter> c : ann.filters()) {
      try {
        filters.add(c.newInstance());
      } catch (Throwable t) {
        errors.add(t);
      }
    }

    if (ann.defaultFilters()) {
      filters.addAll(builtinFilters);
    }

    return or(filters.toArray(new ThreadFilter[filters.size()]));
  }

  /**
   * Clears a {@link Throwable}'s stack. 
   */
  private static <T extends Throwable> T emptyStack(T t) {
    t.setStackTrace(new StackTraceElement [0]);
    return t;
  }
  
  /**
   * Process uncaught exceptions.
   */
  protected void processUncaught(List<Throwable> errors, List<UncaughtException> uncaughtList) {
    for (UncaughtException e : uncaughtList) {
      errors.add(new UncaughtExceptionError(
          "Captured an uncaught exception in thread: " + e.threadName, e.error));
    }
  }

  /**
   * Perform a thread leak check at the given scope. 
   */
  @SuppressWarnings("deprecation")
  protected void checkThreadLeaks(
      Set<Thread> expectedState,
      List<Throwable> errors, 
      LifecycleScope scope, Description description,
      AnnotatedElement... annotationChain)
  {
    final ThreadLeakScope annScope = firstAnnotated(ThreadLeakScope.class, annotationChain);

    // Return immediately if no checking.
    if (annScope.value() == Scope.NONE)
      return;

    // If suite scope check is requested skip testing at test level.
    if (annScope.value() == Scope.SUITE && scope == LifecycleScope.TEST) {
      return;
    }

    // Check for the set of live threads, with optional lingering. 
    int lingerTime = firstAnnotated(ThreadLeakLingering.class, annotationChain).linger();
    HashMap<Thread, StackTraceElement[]> threads = threadsSnapshot(suiteFilters);
    threads.keySet().removeAll(expectedState);

    if (lingerTime > 0 && !threads.isEmpty()) {
      final long deadline = System.currentTimeMillis() + lingerTime;
      try {
        do {
          // Check every 100 milliseconds until deadline occurs. We want to break out
          // sooner than the maximum lingerTime but there is no explicit even that
          // would wake us up, so poll periodically.
          Thread.sleep(250);

          threads = threadsSnapshot(suiteFilters);
          threads.keySet().removeAll(expectedState);
          if (threads.isEmpty() || System.currentTimeMillis() > deadline) 
            break;
        } while (true);
      } catch (InterruptedException e) {
        logger.warning("Lingering interrupted.");
      }
    }

    if (threads.isEmpty()) {
      return;
    }

    // Build up failure message (include stack traces of leaked threads).
    StringBuilder message = new StringBuilder(threads.size() + " thread" +
        (threads.size() == 1 ? "" : "s") +
        " leaked from " +
        scope + " scope at " + description + ": ");
    message.append(threadStacks(threads));

    // The first exception is leaked threads error.
    errors.add(RandomizedRunner.augmentStackTrace(new ThreadLeakError(message.toString())));

    // Perform actions on leaked threads.
    final EnumSet<Action> actions = EnumSet.noneOf(Action.class);
    actions.addAll(Arrays.asList(firstAnnotated(ThreadLeakAction.class, annotationChain).value()));

    if (actions.contains(Action.WARN)) {
      logger.severe(message.toString());
    }

    Set<Thread> zombies = Collections.emptySet();
    if (actions.contains(Action.INTERRUPT)) {
      zombies = tryToInterruptAll(errors, threads.keySet());
    }

    // Process zombie thread check consequences here.
    if (!zombies.isEmpty()) {
      switch (firstAnnotated(ThreadLeakZombies.class, annotationChain).value()) {
        case CONTINUE:
          // Do nothing about it.
          break;
        case IGNORE_REMAINING_TESTS:
          // Mark zombie thread presence.
          RandomizedRunner.zombieMarker.set(true);
          break;
        default:
          throw new RuntimeException("Missing case.");
      }
    }
  }

  /**
   * Dump threads and their current stack trace.
   */
  private String threadStacks(Map<Thread,StackTraceElement[]> threads) {
    StringBuilder message = new StringBuilder();
    int cnt = 1;
    final Formatter f = new Formatter(message);
    for (Map.Entry<Thread,StackTraceElement[]> e : threads.entrySet()) {
      f.format(Locale.ENGLISH, "\n  %2d) %s", cnt++, Threads.threadName(e.getKey())).flush();
      if (e.getValue().length == 0) {
        message.append("\n        at (empty stack)");
      } else {
        for (StackTraceElement ste : e.getValue()) {
          message.append("\n        at ").append(ste);
        }
      }
    }
    return message.toString();
  }

  /** Collect thread names. */
  private String threadNames(Collection<Thread> threads) {
    StringBuilder b = new StringBuilder();
    final Formatter f = new Formatter(b);
    int cnt = 1;
    for (Thread t : threads) {
      f.format(Locale.ENGLISH, "\n  %2d) %s", cnt++, Threads.threadName(t));
    }
    return b.toString();
  }

  /** Dump thread state. */
  private String threadStacksFull() {
    try {
      StringBuilder b = new StringBuilder();
      b.append("\n==== jstack at approximately timeout time ====\n");
      for (ThreadInfo ti : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
        Threads.append(b, ti);
      }
      b.append("^^==============================================\n");
      return b.toString();
    } catch (Exception e) {
      // Ignore, perhaps not available.
    }
    return threadStacks(getAllStackTraces());
  }

  /**
   * Get all stack traces for analysis.
   */
  private Map<Thread,StackTraceElement[]> getAllStackTraces() {
    Set<Thread> threads;
    switch (threadLeakGroup.value()) {
      case ALL:
        threads = Thread.getAllStackTraces().keySet();
        break;
      case MAIN:
        threads = Threads.getThreads(RandomizedRunner.mainThreadGroup);
        break;
      case TESTGROUP:
        threads = Threads.getThreads(runner.runnerThreadGroup);
        break;
      default:
        throw new RuntimeException();
    }
    
    HashMap<Thread,StackTraceElement[]> r = new HashMap<Thread,StackTraceElement[]>();
    for (Thread t : threads) {
      r.put(t, t.getStackTrace());
    }
    return r;
  }

  /**
   * Attempt to interrupt all threads in the given set.
   */
  private Set<Thread> tryToInterruptAll(List<Throwable> errors, Set<Thread> threads) {
    logger.info("Starting to interrupt leaked threads:" + threadNames(threads));

    // stop reporting uncaught exceptions.
    runner.handler.stopReporting();
    try {
      // This means we have an unknown ordering of interrupt calls but
      // there is very little we can do about it, really.
      final HashSet<Thread> ordered = new HashSet<Thread>(threads);
  
      int interruptAttempts = this.killAttempts;
      int interruptWait = this.killWait;
      boolean allDead;
      final int restorePriority = Thread.currentThread().getPriority();
      do {
        allDead = true;
        try {
          Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
          for (Thread t : ordered) {
            t.interrupt();
          }
          
          // Maximum wait time. Progress through the threads, trying to join but
          // decrease the join time each time.
          long waitDeadline = System.currentTimeMillis() + interruptWait;
          for (Iterator<Thread> i = ordered.iterator(); i.hasNext();) {
            final Thread t = i.next();
            if (t.isAlive()) {
              allDead = false;
              t.join(Math.max(1, waitDeadline - System.currentTimeMillis()));
            } else {
              i.remove();
            }
          }
        } catch (InterruptedException e) {
          interruptAttempts = 0;
        }
      } while (!allDead && --interruptAttempts >= 0);
      Thread.currentThread().setPriority(restorePriority);
  
      // Check after the last join.
      HashMap<Thread,StackTraceElement[]> zombies = new HashMap<Thread,StackTraceElement[]>();
      for (Thread t : ordered) {
        if (t.isAlive()) {
          zombies.put(t, t.getStackTrace());
        }
      }
  
      if (zombies.isEmpty()) {
        logger.info("All leaked threads terminated.");
      } else {
        String message = "There are still zombie threads that couldn't be terminated:" + threadStacks(zombies);
        logger.severe(message);
        errors.add(RandomizedRunner.augmentStackTrace(new ThreadLeakError(message.toString())));
      }

      return zombies.keySet();
    } finally {
      runner.handler.resumeReporting();
    }
  }

  /**
   * Fork or not depending on the timeout value.
   */
  boolean forkTimeoutingTask(StatementRunner r, int timeout, List<Throwable> errors) 
      throws InterruptedException
  {
    // TODO: add no-forking for timeout = 0.
    // TODO: add proper thread naming.
    Thread t = new Thread(r, Thread.currentThread().getName() + "-worker");
    RandomizedContext.shareWith(t);
    t.start();
    t.join(timeout);

    final boolean timedOut = !r.completed;
    if (r.error != null) errors.add(r.error);
    return timedOut;
  }

  /**
   * Return the {@link RunNotifier} that should be used by any sub-statements
   * running actual instance-scope tests. We need this because we need to
   * prevent spurious notifications after suite timeouts.
   */
  RunNotifier notifier() {
    return subNotifier;
  }

  /**
   * Determine timeout for a suite.
   * 
   * @return Returns timeout in milliseconds or 0 if the test should run until
   *         finished (possibly blocking forever).
   */
  private int determineTimeout(Class<?> suiteClass) {
    TimeoutSuite timeoutAnn = suiteClass.getAnnotation(TimeoutSuite.class);
    return suiteTimeout.getTimeout(timeoutAnn ==  null ? null : timeoutAnn.millis());
  }

  /**
   * Determine timeout for a single test method (candidate).
   *  
   * @return Returns timeout in milliseconds or 0 if the test should run until
   *         finished (possibly blocking forever).
   */
  private int determineTimeout(TestCandidate c) {
    Integer timeout = null;

    Timeout timeoutAnn = c.instanceProvider.getTestClass().getAnnotation(Timeout.class);
    if (timeoutAnn != null) {
      timeout = (int) Math.min(Integer.MAX_VALUE, timeoutAnn.millis());
    }

    // @Test annotation timeout value.
    Test testAnn = c.method.getAnnotation(Test.class);
    if (testAnn != null && testAnn.timeout() > 0) {
      timeout = (int) Math.min(Integer.MAX_VALUE, testAnn.timeout());
    }

    // Method-override.
    timeoutAnn = c.method.getAnnotation(Timeout.class);
    if (timeoutAnn != null) {
      timeout = timeoutAnn.millis();
    }

    return testTimeout.getTimeout(timeout);
  }  

  /**
   * Return a set of active live threads, with their stack traces, 
   * possibly filtered.
   */
  private HashMap<Thread, StackTraceElement[]> threadsSnapshot(ThreadFilter filter) {
    final HashMap<Thread,StackTraceElement[]> all = 
        new HashMap<Thread, StackTraceElement[]>(getAllStackTraces());

    for (Iterator<Thread> i = all.keySet().iterator(); i.hasNext();) {
      Thread t = i.next();
      if (!t.isAlive() || filter.reject(t)) {
        i.remove();
      }
    }

    return all;
  }

  /**
   * Returns an annotation's instance declared on any annotated element (first one wins)
   * or the default value if not present on any of them.
   */
  private static <T extends Annotation> T firstAnnotated(Class<T> clazz, AnnotatedElement... elements) {
    for (AnnotatedElement element : elements) {
      T ann = element.getAnnotation(clazz);
      if (ann != null) return ann;
    }
    throw new RuntimeException("default annotation value must be within elements.");
  }
}