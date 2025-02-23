/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.cli.commands;

import org.apache.hudi.avro.model.HoodieRollbackMetadata;
import org.apache.hudi.cli.HoodieCLI;
import org.apache.hudi.cli.HoodiePrintHelper;
import org.apache.hudi.cli.HoodieTableHeaderFields;
import org.apache.hudi.cli.TableHeader;
import org.apache.hudi.cli.utils.InputStreamConsumer;
import org.apache.hudi.cli.utils.SparkUtil;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieInstant.State;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.timeline.TimelineMetadataUtils;
import org.apache.hudi.common.util.CollectionUtils;
import org.apache.hudi.common.util.collection.Pair;

import org.apache.spark.launcher.SparkLauncher;
import org.springframework.shell.core.CommandMarker;
import org.springframework.shell.core.annotation.CliCommand;
import org.springframework.shell.core.annotation.CliOption;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.apache.hudi.common.table.timeline.HoodieTimeline.ROLLBACK_ACTION;

/**
 * CLI command to display rollback options.
 */
@Component
public class RollbacksCommand implements CommandMarker {

  @CliCommand(value = "show rollbacks", help = "List all rollback instants")
  public String showRollbacks(
      @CliOption(key = {"limit"}, help = "Limit #rows to be displayed", unspecifiedDefaultValue = "10") Integer limit,
      @CliOption(key = {"sortBy"}, help = "Sorting Field", unspecifiedDefaultValue = "") final String sortByField,
      @CliOption(key = {"desc"}, help = "Ordering", unspecifiedDefaultValue = "false") final boolean descending,
      @CliOption(key = {"headeronly"}, help = "Print Header Only",
          unspecifiedDefaultValue = "false") final boolean headerOnly) {
    HoodieActiveTimeline activeTimeline = new RollbackTimeline(HoodieCLI.getTableMetaClient());
    HoodieTimeline rollback = activeTimeline.getRollbackTimeline().filterCompletedInstants();

    final List<Comparable[]> rows = new ArrayList<>();
    rollback.getInstants().forEach(instant -> {
      try {
        HoodieRollbackMetadata metadata = TimelineMetadataUtils
            .deserializeAvroMetadata(activeTimeline.getInstantDetails(instant).get(), HoodieRollbackMetadata.class);
        metadata.getCommitsRollback().forEach(c -> {
          Comparable[] row = new Comparable[5];
          row[0] = metadata.getStartRollbackTime();
          row[1] = c;
          row[2] = metadata.getTotalFilesDeleted();
          row[3] = metadata.getTimeTakenInMillis();
          row[4] = metadata.getPartitionMetadata() != null ? metadata.getPartitionMetadata().size() : 0;
          rows.add(row);
        });
      } catch (IOException e) {
        e.printStackTrace();
      }
    });
    TableHeader header = new TableHeader().addTableHeaderField(HoodieTableHeaderFields.HEADER_INSTANT)
        .addTableHeaderField(HoodieTableHeaderFields.HEADER_ROLLBACK_INSTANT)
        .addTableHeaderField(HoodieTableHeaderFields.HEADER_TOTAL_FILES_DELETED)
        .addTableHeaderField(HoodieTableHeaderFields.HEADER_TIME_TOKEN_MILLIS)
        .addTableHeaderField(HoodieTableHeaderFields.HEADER_TOTAL_PARTITIONS);
    return HoodiePrintHelper.print(header, new HashMap<>(), sortByField, descending, limit, headerOnly, rows);
  }

  @CliCommand(value = "show rollback", help = "Show details of a rollback instant")
  public String showRollback(
      @CliOption(key = {"instant"}, help = "Rollback instant", mandatory = true) String rollbackInstant,
      @CliOption(key = {"limit"}, help = "Limit  #rows to be displayed", unspecifiedDefaultValue = "10") Integer limit,
      @CliOption(key = {"sortBy"}, help = "Sorting Field", unspecifiedDefaultValue = "") final String sortByField,
      @CliOption(key = {"desc"}, help = "Ordering", unspecifiedDefaultValue = "false") final boolean descending,
      @CliOption(key = {"headeronly"}, help = "Print Header Only",
          unspecifiedDefaultValue = "false") final boolean headerOnly)
      throws IOException {
    HoodieActiveTimeline activeTimeline = new RollbackTimeline(HoodieCLI.getTableMetaClient());
    final List<Comparable[]> rows = new ArrayList<>();
    HoodieRollbackMetadata metadata = TimelineMetadataUtils.deserializeAvroMetadata(
        activeTimeline.getInstantDetails(new HoodieInstant(State.COMPLETED, ROLLBACK_ACTION, rollbackInstant)).get(),
        HoodieRollbackMetadata.class);
    metadata.getPartitionMetadata().forEach((key, value) -> Stream
        .concat(value.getSuccessDeleteFiles().stream().map(f -> Pair.of(f, true)),
            value.getFailedDeleteFiles().stream().map(f -> Pair.of(f, false)))
        .forEach(fileWithDeleteStatus -> {
          Comparable[] row = new Comparable[5];
          row[0] = metadata.getStartRollbackTime();
          row[1] = metadata.getCommitsRollback().toString();
          row[2] = key;
          row[3] = fileWithDeleteStatus.getLeft();
          row[4] = fileWithDeleteStatus.getRight();
          rows.add(row);
        }));

    TableHeader header = new TableHeader().addTableHeaderField(HoodieTableHeaderFields.HEADER_INSTANT)
        .addTableHeaderField(HoodieTableHeaderFields.HEADER_ROLLBACK_INSTANT)
        .addTableHeaderField(HoodieTableHeaderFields.HEADER_PARTITION)
        .addTableHeaderField(HoodieTableHeaderFields.HEADER_DELETED_FILE)
        .addTableHeaderField(HoodieTableHeaderFields.HEADER_SUCCEEDED);
    return HoodiePrintHelper.print(header, new HashMap<>(), sortByField, descending, limit, headerOnly, rows);
  }

  @CliCommand(value = "commit rollback", help = "Rollback a commit")
  public String rollbackCommit(
      @CliOption(key = {"commit"}, help = "Commit to rollback") final String instantTime,
      @CliOption(key = {"sparkProperties"}, help = "Spark Properties File Path") final String sparkPropertiesPath,
      @CliOption(key = "sparkMaster", unspecifiedDefaultValue = "", help = "Spark Master") String master,
      @CliOption(key = "sparkMemory", unspecifiedDefaultValue = "4G",
          help = "Spark executor memory") final String sparkMemory,
      @CliOption(key = "rollbackUsingMarkers", unspecifiedDefaultValue = "false",
          help = "Enabling marker based rollback") final String rollbackUsingMarkers)
      throws Exception {
    HoodieActiveTimeline activeTimeline = HoodieCLI.getTableMetaClient().getActiveTimeline();
    HoodieTimeline completedTimeline = activeTimeline.getCommitsTimeline().filterCompletedInstants();
    HoodieTimeline filteredTimeline = completedTimeline.filter(instant -> instant.getTimestamp().equals(instantTime));
    if (filteredTimeline.empty()) {
      return "Commit " + instantTime + " not found in Commits " + completedTimeline;
    }

    SparkLauncher sparkLauncher = SparkUtil.initLauncher(sparkPropertiesPath);
    sparkLauncher.addAppArgs(SparkMain.SparkCommand.ROLLBACK.toString(), master, sparkMemory, instantTime,
        HoodieCLI.getTableMetaClient().getBasePath(), rollbackUsingMarkers);
    Process process = sparkLauncher.launch();
    InputStreamConsumer.captureOutput(process);
    int exitCode = process.waitFor();
    // Refresh the current
    HoodieCLI.refreshTableMetadata();
    if (exitCode != 0) {
      return "Commit " + instantTime + " failed to roll back";
    }
    return "Commit " + instantTime + " rolled back";
  }

  /**
   * An Active timeline containing only rollbacks.
   */
  public static class RollbackTimeline extends HoodieActiveTimeline {

    public RollbackTimeline(HoodieTableMetaClient metaClient) {
      super(metaClient, CollectionUtils.createImmutableSet(HoodieTimeline.ROLLBACK_EXTENSION));
    }
  }
}
