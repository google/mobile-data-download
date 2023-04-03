/*
 * Copyright 2022 Google LLC
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
package com.google.android.libraries.mobiledatadownload.downloader.offroad;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * Passes tasks to a delegate {@link Executor} for execution, ensuring that no more than a fixed
 * number of them are submitted at any one time. If the limit is reached, then new tasks will be
 * queued up and submitted when possible.
 *
 * <p>If the delegate {@link Executor} is only accessed via some {@link ThrottlingExecutor}, this
 * effectively limits the number of concurrent tasks/threads. Alternatively, we can wrap a shared
 * {@link Executor} in a {@link ThrottlingExecutor} before passing it to some component, to limit
 * the amount of concurrency that component can request.
 *
 * <p>If the delegate {@link Executor} rejects queued tasks, they will be silently dropped. To keep
 * the implementation simple, should the delegate {@link Executor} reject tasks, then we may end up
 * with some tasks queued even if fewer tasks are actually running than our bound allows. We will
 * keep submitting new tasks, however, so that transient problems in the underlying {@link Executor}
 * do not prevent new tasks from running.
 */
public class ThrottlingExecutor implements Executor {
  private static final String TAG = "ThrottlingExecutor";

  private final Executor delegateExecutor;
  private final int maximumThreads;

  private final Object lock = new Object();

  @GuardedBy("lock")
  private int count = 0;

  @GuardedBy("lock")
  private Queue<Runnable> queue = new ArrayDeque<>();

  public ThrottlingExecutor(Executor delegate, int maximumThreads) {
    delegateExecutor = delegate;
    this.maximumThreads = maximumThreads;
  }

  @Override
  public void execute(Runnable runnable) {
    checkNotNull(runnable);

    synchronized (lock) {
      if (count >= maximumThreads) {
        queue.add(runnable);
        return;
      }

      // We're going to run the task immediately, outside this synchronized block
      count++;
    }

    try {
      delegateExecutor.execute(new WrappedRunnable(runnable));
    } catch (Throwable t) {
      synchronized (lock) {
        count--;
      }
      throw t;
    }
  }

  /**
   * Submits the next task from the {@link Queue}, decrementing the count of actively running tasks
   * if there aren't any. This method is immediately after a {@link Runnable} has completed.
   *
   * <p>There are a couple of design points here.
   *
   * <p>Firstly, how do we run the next task? We could either submit the next task using {@link
   * Executor#execute()}, or we could run it inline. The first approach is simpler, but has the
   * drawback that the delegate Executor can see more than {@code maximumThreads} tasks running
   * simultaneously: as we are submitted here from the end of an old task, the Executor briefly
   * considers both the old and new task to be running. The second approach avoids this and may be
   * slightly more efficient, but has added complexity (in particular in exception handling - we
   * still want any unchecked Thowables thrown from a task to be propagated on). Also, if other
   * tasks are waiting to run on the delegate {@link Executor} without passing through this
   * throttle, then the second approach can prevent those tasks from having a chance to run. If in
   * doubt, keep it simple - so we adopt the first approach.
   *
   * <p>Secondly, we want any Throwables thrown during the task to be propagated on to the delegate
   * {@link Executor}, as if the {@link ThrottlingExecutor} wasn't in the way. But what about
   * exceptions thrown by the {@link Executor#execute} method? That method could throw {@link
   * RejectedExecutionException} in particular if the executor has been shut down but also for any
   * other reason. It could also throw the usual range of unchecked exceptions. In either situation
   * we simply decrement the count so that newly submitted tasks can still be attempted. Note that
   * this approach, while simple, can leave some tasks "stranded" on the queue until other tasks are
   * submitted and finish - if the underlying executor has been shutdown or permanently broken then
   * this makes no difference; otherwise should never arise but if they do, at least new tasks can
   * attempt to be run.
   */
  private void submitNextTaskOrDecrementCount() {
    Runnable toSubmit;
    synchronized (lock) {
      toSubmit = queue.poll();
      if (toSubmit == null) {
        count--;
        return;
      }
    }

    try {
      delegateExecutor.execute(new WrappedRunnable(toSubmit));
    } catch (Throwable t) {
      // Suppress this exception, which is probably a RejectedExecutionException. We're called from
      // the finally block after some other task has completed, and we don't want to suppress any
      // exception from the just-completed task.
      LogUtil.e(t, "%s: Task submission failed: %s", TAG, toSubmit);
      synchronized (lock) {
        count--;
      }
    }
  }

  private class WrappedRunnable implements Runnable {
    private final Runnable delegateRunnable;

    public WrappedRunnable(Runnable delegate) {
      delegateRunnable = delegate;
    }

    @Override
    public void run() {
      try {
        delegateRunnable.run();
      } finally {
        submitNextTaskOrDecrementCount();
      }
    }
  }
}
