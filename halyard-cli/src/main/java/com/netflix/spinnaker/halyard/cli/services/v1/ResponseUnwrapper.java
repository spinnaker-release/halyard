/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.halyard.cli.services.v1;

import com.netflix.spinnaker.halyard.cli.command.v1.GlobalOptions;
import com.netflix.spinnaker.halyard.cli.ui.v1.*;
import com.netflix.spinnaker.halyard.core.DaemonResponse;
import com.netflix.spinnaker.halyard.core.problem.v1.Problem;
import com.netflix.spinnaker.halyard.core.problem.v1.Problem.Severity;
import com.netflix.spinnaker.halyard.core.problem.v1.ProblemSet;
import com.netflix.spinnaker.halyard.core.tasks.v1.DaemonEvent;
import com.netflix.spinnaker.halyard.core.tasks.v1.DaemonTask;
import com.netflix.spinnaker.halyard.core.tasks.v1.DaemonTask.State;
import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.IntStream;

public class ResponseUnwrapper {
  private static final Long WAIT_MILLIS = 400L;
  private static int cycle;
  private static String[] cursors = {"◢", "◣", "◤", "◥"};

  public static <C, T> T get(DaemonTask<C, T> task) {
    int lastTaskCount = 0;

    task = Daemon.getTask(task.getUuid());
    while (!task.getState().isTerminal()) {
      updateCycle();
      lastTaskCount = formatTasks(aggregateTasks(task), lastTaskCount);
      try {
        Thread.sleep(WAIT_MILLIS);
      } catch (InterruptedException ignored) {
      }

      task = Daemon.getTask(task.getUuid());
    }

    formatTasks(aggregateTasks(task), lastTaskCount);

    DaemonResponse<T> response = task.getResponse();
    formatProblemSet(response.getProblemSet());
    if (task.getState() == State.FATAL) {
      Exception fatal = task.getFatalError();
      if (fatal == null) {
        throw new RuntimeException("Task failed without reason. This is a bug.");
      } else {
        throw new ExpectedDaemonFailureException(fatal);
      }
    }

    return response.getResponseBody();
  }

  private static List<DaemonTask> aggregateTasks(DaemonTask task) {
    List<DaemonTask> result = new ArrayList<>();
    task.consumeTaskTree((t) -> result.add((DaemonTask) t));
    return result;
  }

  private static int formatTasks(List<DaemonTask> tasks, int lastChildCount) {
    if (tasks.size() == 0 || GlobalOptions.getGlobalOptions().isQuiet()) {
      return tasks.size();
    }

    int taskCountGrowth = tasks.size() - lastChildCount;
    IntStream.range(0, taskCountGrowth * 2).forEach(i -> AnsiPrinter.println(""));

    AnsiSnippet snippet = new AnsiSnippet("").addMove(AnsiMove.UP, tasks.size() * 2);
    AnsiPrinter.print(snippet.toString());

    for (DaemonTask task : tasks) {
      formatLastEvent(task);
    }

    return tasks.size();
  }

  private static DaemonEvent getLastEvent(DaemonTask task) {
    int eventCount = task.getEvents().size();
    DaemonEvent event = null;
    if (eventCount > 0) {
      event = (DaemonEvent) task.getEvents().get(eventCount - 1);
    }

    return event;
  }

  private static void formatLastEvent(DaemonTask task) {
    AnsiParagraphBuilder builder = new AnsiParagraphBuilder().setMaxLineWidth(-1);
    builder.addSnippet("\r").setErase(AnsiErase.ERASE_LINE);

    DaemonEvent event = getLastEvent(task);
    State state = task.getState();
    String taskName = task.getName();

    switch (state) {
      case NOT_STARTED:
      case RUNNING:
        builder.addSnippet(nextCursor() + " ")
            .setForegroundColor(AnsiForegroundColor.BLUE)
            .addStyle(AnsiStyle.BOLD);
        break;
      case SUCCESS:
        builder.addSnippet("+ ")
            .setForegroundColor(AnsiForegroundColor.GREEN)
            .addStyle(AnsiStyle.BOLD);
        event = new DaemonEvent().setStage("Success");
        break;
      case FATAL:
        builder.addSnippet("- ")
            .setForegroundColor(AnsiForegroundColor.RED)
            .addStyle(AnsiStyle.BOLD);
        event = new DaemonEvent().setStage("Failure");
        break;
    }

    builder.addSnippet(taskName).addStyle(AnsiStyle.BOLD);
    builder.addSnippet("\n");
    builder.addSnippet("\r").setErase(AnsiErase.ERASE_LINE);

    if (event != null) {
      builder.addSnippet("  ");
      String stage = event.getStage();
      String message = event.getMessage();

      builder.addSnippet(stage);

      if (!StringUtils.isEmpty(message)) {
        builder.addSnippet(": " + message);
      }
    }

    AnsiPrinter.println(builder.toString());
  }

  private static void formatEvent(DaemonEvent event) {
    String stage = event.getStage();
    String message = event.getMessage();
    String detail = event.getDetail();
    AnsiSnippet clear = new AnsiSnippet("\r").setErase(AnsiErase.ERASE_LINE);
    AnsiPrinter.print(clear.toString());

    if (!StringUtils.isEmpty(message)) {
      AnsiSnippet messageSnippet = new AnsiSnippet("- " + message);
      AnsiPrinter.println(messageSnippet.toString());
    }

    if (!StringUtils.isEmpty(detail)) {
      stage = stage + " (" + detail + ")";
    }

    AnsiSnippet stageSnippet = new AnsiSnippet("~ " + stage)
        .addStyle(AnsiStyle.BOLD);
    AnsiPrinter.print(stageSnippet.toString());
  }

  private static void formatProblemSet(ProblemSet problemSet) {
    if (problemSet == null || problemSet.isEmpty()) {
      return;
    }

    AnsiSnippet snippet = new AnsiSnippet("\r").setErase(AnsiErase.ERASE_LINE);
    AnsiPrinter.print(snippet.toString());

    Map<String, List<Problem>> locationGroup = problemSet.groupByLocation();
    for (Entry<String, List<Problem>> entry: locationGroup.entrySet()) {

      AnsiUi.location(entry.getKey());
      for (Problem problem : entry.getValue()) {
        Severity severity = problem.getSeverity();
        String message = problem.getMessage();
        String remediation = problem.getRemediation();
        List<String> options = problem.getOptions();

        switch (severity) {
          case FATAL:
          case ERROR:
            AnsiUi.error(message);
            break;
          case WARNING:
            AnsiUi.warning(message);
            break;
          default:
            throw new RuntimeException("Unknown severity level " + severity);
        }

        if (remediation != null && !remediation.isEmpty()) {
          AnsiUi.remediation(remediation);
        }

        if (options != null && !options.isEmpty()) {
          AnsiUi.remediation("Options include: ");
          options.forEach(AnsiUi::listItem);
        }

        // Newline between errors
        AnsiUi.raw("");
      }
    }
  }

  private static String nextCursor() {
    return cursors[cycle];
  }

  private static void updateCycle() {
    cycle = (cycle + 1) % cursors.length;
  }
}
