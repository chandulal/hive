/*
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at
  <p>
  http://www.apache.org/licenses/LICENSE-2.0
  <p>
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.tez.monitoring;

import static org.apache.tez.dag.api.client.DAGStatus.State.RUNNING;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringWriter;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.hadoop.hive.common.log.InPlaceUpdate;
import org.apache.hadoop.hive.common.log.ProgressMonitor;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.ql.Context;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.exec.tez.TezSessionPoolManager;
import org.apache.hadoop.hive.ql.log.PerfLogger;
import org.apache.hadoop.hive.ql.plan.BaseWork;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.ql.session.SessionState.LogHelper;
import org.apache.hadoop.hive.ql.wm.TimeCounterLimit;
import org.apache.hadoop.hive.ql.wm.WmContext;
import org.apache.hadoop.hive.ql.wm.VertexCounterLimit;
import org.apache.hive.common.util.ShutdownHookManager;
import org.apache.tez.common.counters.CounterGroup;
import org.apache.tez.common.counters.TezCounter;
import org.apache.tez.common.counters.TezCounters;
import org.apache.tez.dag.api.DAG;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.api.client.DAGClient;
import org.apache.tez.dag.api.client.DAGStatus;
import org.apache.tez.dag.api.client.Progress;
import org.apache.tez.dag.api.client.StatusGetOpts;
import org.apache.tez.util.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

/**
 * TezJobMonitor keeps track of a tez job while it's being executed. It will
 * print status to the console and retrieve final status of the job after
 * completion.
 */
public class TezJobMonitor {

  static final String CLASS_NAME = TezJobMonitor.class.getName();
  private static final Logger LOG = LoggerFactory.getLogger(CLASS_NAME);
  private static final int MIN_CHECK_INTERVAL = 200;
  private static final int MAX_CHECK_INTERVAL = 1000;
  private static final int MAX_RETRY_INTERVAL = 2500;
  private static final int MAX_RETRY_FAILURES = (MAX_RETRY_INTERVAL / MAX_CHECK_INTERVAL) + 1;

  private final PerfLogger perfLogger = SessionState.getPerfLogger();
  private static final List<DAGClient> shutdownList;
  private final List<BaseWork> topSortedWorks;

  transient LogHelper console;

  private StringWriter diagnostics = new StringWriter();

  static {
    shutdownList = new LinkedList<>();
    ShutdownHookManager.addShutdownHook(new Runnable() {
      @Override
      public void run() {
        TezJobMonitor.killRunningJobs();
        try {
          // TODO: why does this only kill non-default sessions?
          // Nothing for workload management since that only deals with default ones.
          TezSessionPoolManager.getInstance().closeNonDefaultSessions();
        } catch (Exception e) {
          // ignore
        }
      }
    });
  }

  public static void initShutdownHook() {
    Preconditions.checkNotNull(shutdownList,
      "Shutdown hook was not properly initialized");
  }

  private final DAGClient dagClient;
  private final HiveConf hiveConf;
  private final DAG dag;
  private final Context context;
  private long executionStartTime = 0;
  private final RenderStrategy.UpdateFunction updateFunction;

  public TezJobMonitor(List<BaseWork> topSortedWorks, final DAGClient dagClient, HiveConf conf, DAG dag,
                       Context ctx) {
    this.topSortedWorks = topSortedWorks;
    this.dagClient = dagClient;
    this.hiveConf = conf;
    this.dag = dag;
    this.context = ctx;
    console = SessionState.getConsole();
    updateFunction = updateFunction();
  }

  private RenderStrategy.UpdateFunction updateFunction() {
    return InPlaceUpdate.canRenderInPlace(hiveConf)
        && !SessionState.getConsole().getIsSilent()
        && !SessionState.get().isHiveServerQuery()
        ? new RenderStrategy.InPlaceUpdateFunction(this)
        : new RenderStrategy.LogToFileFunction(this);
  }

  private boolean isProfilingEnabled() {
    return HiveConf.getBoolVar(hiveConf, HiveConf.ConfVars.TEZ_EXEC_SUMMARY) ||
      Utilities.isPerfOrAboveLogging(hiveConf);
  }

  public int monitorExecution() {
    boolean done = false;
    boolean success = false;
    int failedCounter = 0;
    final StopWatch failureTimer = new StopWatch();
    int rc = 0;
    DAGStatus status = null;
    Map<String, Progress> vertexProgressMap = null;


    long monitorStartTime = System.currentTimeMillis();
    synchronized (shutdownList) {
      shutdownList.add(dagClient);
    }
    perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.TEZ_RUN_DAG);
    perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.TEZ_SUBMIT_TO_RUNNING);
    DAGStatus.State lastState = null;
    boolean running = false;

    long checkInterval = MIN_CHECK_INTERVAL;
    WmContext wmContext = null;
    while (true) {

      try {
        if (context != null) {
          context.checkHeartbeaterLockException();
        }

        status = dagClient.getDAGStatus(EnumSet.of(StatusGetOpts.GET_COUNTERS), checkInterval);
        TezCounters dagCounters = status.getDAGCounters();
        vertexProgressMap = status.getVertexProgress();
        wmContext = context.getWmContext();
        if (dagCounters != null && wmContext != null) {
          Set<String> desiredCounters = wmContext.getSubscribedCounters();
          if (desiredCounters != null && !desiredCounters.isEmpty()) {
            Map<String, Long> currentCounters = getCounterValues(dagCounters, vertexProgressMap, desiredCounters, done);
            wmContext.setCurrentCounters(currentCounters);
          }
        }
        DAGStatus.State state = status.getState();

        failedCounter = 0; // AM is responsive again (recovery?)
        failureTimer.reset();

        if (state != lastState || state == RUNNING) {
          lastState = state;

          switch (state) {
            case SUBMITTED:
              console.printInfo("Status: Submitted");
              break;
            case INITING:
              console.printInfo("Status: Initializing");
              this.executionStartTime = System.currentTimeMillis();
              break;
            case RUNNING:
              if (!running) {
                perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.TEZ_SUBMIT_TO_RUNNING);
                console.printInfo("Status: Running (" + dagClient.getExecutionContext() + ")\n");
                this.executionStartTime = System.currentTimeMillis();
                running = true;
                // from running -> failed/succeeded, the AM breaks out of timeouts
                checkInterval = MAX_CHECK_INTERVAL;
              }
              updateFunction.update(status, vertexProgressMap);
              break;
            case SUCCEEDED:
              if (!running) {
                this.executionStartTime = monitorStartTime;
              }
              updateFunction.update(status, vertexProgressMap);
              success = true;
              running = false;
              done = true;
              break;
            case KILLED:
              if (!running) {
                this.executionStartTime = monitorStartTime;
              }
              updateFunction.update(status, vertexProgressMap);
              console.printInfo("Status: Killed");
              running = false;
              done = true;
              rc = 1;
              break;
            case FAILED:
            case ERROR:
              if (!running) {
                this.executionStartTime = monitorStartTime;
              }
              updateFunction.update(status, vertexProgressMap);
              console.printError("Status: Failed");
              running = false;
              done = true;
              rc = 2;
              break;
          }
        }
        if (wmContext != null && done) {
          wmContext.setQueryCompleted(true);
        }
      } catch (Exception e) {
        console.printInfo("Exception: " + e.getMessage());
        boolean isInterrupted = hasInterruptedException(e);
        if (failedCounter == 0) {
          failureTimer.reset();
          failureTimer.start();
        }
        if (isInterrupted
            || (++failedCounter >= MAX_RETRY_FAILURES && failureTimer.now(TimeUnit.MILLISECONDS) > MAX_RETRY_INTERVAL)) {
          try {
            if (isInterrupted) {
              console.printInfo("Killing DAG...");
            } else {
              console.printInfo(String.format("Killing DAG... after %d seconds",
                  failureTimer.now(TimeUnit.SECONDS)));
            }
            dagClient.tryKillDAG();
          } catch (IOException | TezException tezException) {
            // best effort
          }
          console.printError("Execution has failed. stack trace: " + ExceptionUtils.getStackTrace(e));
          rc = 1;
          done = true;
        } else {
          console.printInfo("Retrying...");
        }
        if (wmContext != null && done) {
          wmContext.setQueryCompleted(true);
        }
      } finally {
        if (done) {
          if (wmContext != null && done) {
            wmContext.setQueryCompleted(true);
          }
          if (rc != 0 && status != null) {
            for (String diag : status.getDiagnostics()) {
              console.printError(diag);
              diagnostics.append(diag);
            }
          }
          synchronized (shutdownList) {
            shutdownList.remove(dagClient);
          }
          break;
        }
      }
    }

    perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.TEZ_RUN_DAG);
    printSummary(success, vertexProgressMap);
    return rc;
  }

  private Map<String, Long> getCounterValues(final TezCounters dagCounters,
    final Map<String, Progress> vertexProgressMap,
    final Set<String> desiredCounters, final boolean done) {
    // DAG specific counters
    Map<String, Long> updatedCounters = new HashMap<>();
    for (CounterGroup counterGroup : dagCounters) {
      for (TezCounter tezCounter : counterGroup) {
        String counterName = tezCounter.getName();
        if (desiredCounters.contains(counterName)) {
          updatedCounters.put(counterName, tezCounter.getValue());
        }
      }
    }

    // Process per vertex counters.
    String counterName = VertexCounterLimit.VertexCounter.TOTAL_TASKS.name();
    if (desiredCounters.contains(counterName) && vertexProgressMap != null) {
      for (Map.Entry<String, Progress> entry : vertexProgressMap.entrySet()) {
        // TOTAL_TASKS counter is per vertex counter, but triggers are validated at query level
        // looking for query level violations. So we always choose max TOTAL_TASKS among all vertices.
        // Publishing TOTAL_TASKS for all vertices is not really useful from the context of triggers.
        long currentMax = 0;
        if (updatedCounters.containsKey(counterName)) {
          currentMax = updatedCounters.get(counterName);
        }
        long totalTasks = Math.max(currentMax, entry.getValue().getTotalTaskCount());
        updatedCounters.put(counterName, totalTasks);
      }
    }

    // Time based counters. If DAG is done already don't update these counters.
    if (!done) {
      counterName = TimeCounterLimit.TimeCounter.ELAPSED_TIME.name();
      if (desiredCounters.contains(counterName)) {
        updatedCounters.put(counterName, context.getWmContext().getElapsedTime());
      }

      counterName = TimeCounterLimit.TimeCounter.EXECUTION_TIME.name();
      if (desiredCounters.contains(counterName) && executionStartTime > 0) {
        updatedCounters.put(counterName, System.currentTimeMillis() - executionStartTime);
      }
    }

    return updatedCounters;
  }

  private void printSummary(boolean success, Map<String, Progress> progressMap) {
    if (isProfilingEnabled() && success && progressMap != null) {

      double duration = (System.currentTimeMillis() - this.executionStartTime) / 1000.0;
      console.printInfo("Status: DAG finished successfully in " + String.format("%.2f seconds", duration));
      console.printInfo("");

      new QueryExecutionBreakdownSummary(perfLogger).print(console);
      new DAGSummary(progressMap, hiveConf, dagClient, dag, perfLogger).print(console);

      //llap IO summary
      if (HiveConf.getBoolVar(hiveConf, HiveConf.ConfVars.LLAP_IO_ENABLED, false)) {
        new LLAPioSummary(progressMap, dagClient).print(console);
        new FSCountersSummary(progressMap, dagClient).print(console);
      }
      String wmQueue = HiveConf.getVar(hiveConf, ConfVars.HIVE_SERVER2_TEZ_INTERACTIVE_QUEUE);
      if (wmQueue != null && !wmQueue.isEmpty()) {
        new LlapWmSummary(progressMap, dagClient).print(console);
      }

      console.printInfo("");
    }
  }

  private static boolean hasInterruptedException(Throwable e) {
    // Hadoop IPC wraps InterruptedException. GRRR.
    while (e != null) {
      if (e instanceof InterruptedException || e instanceof InterruptedIOException) {
        return true;
      }
      e = e.getCause();
    }
    return false;
  }

  /**
   * killRunningJobs tries to terminate execution of all
   * currently running tez queries. No guarantees, best effort only.
   *
   * {@link org.apache.hadoop.hive.ql.exec.tez.TezJobExecHelper#killRunningJobs()} makes use of
   * this method via reflection.
   */
  public static void killRunningJobs() {
    synchronized (shutdownList) {
      for (DAGClient c : shutdownList) {
        try {
          System.err.println("Trying to shutdown DAG");
          c.tryKillDAG();
        } catch (Exception e) {
          // ignore
        }
      }
    }
  }

  static long getCounterValueByGroupName(TezCounters vertexCounters, String groupNamePattern,
                                         String counterName) {
    TezCounter tezCounter = vertexCounters.getGroup(groupNamePattern).findCounter(counterName);
    return (tezCounter == null) ? 0 : tezCounter.getValue();
  }

  public String getDiagnostics() {
    return diagnostics.toString();
  }

  ProgressMonitor progressMonitor(DAGStatus status, Map<String, Progress> progressMap) {
    try {
      return new TezProgressMonitor(dagClient, status, topSortedWorks, progressMap, console,
          executionStartTime);
    } catch (IOException | TezException e) {
      console.printInfo("Getting  Progress Information: " + e.getMessage() + " stack trace: " +
          ExceptionUtils.getStackTrace(e));
    }
    return TezProgressMonitor.NULL;
  }
}
