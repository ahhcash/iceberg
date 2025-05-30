/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.iceberg.TableMetadata.MetadataLogEntry;
import org.apache.iceberg.TableMetadata.SnapshotLogEntry;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.util.JsonUtil;

public class TableMetadataParser {

  public enum Codec {
    NONE(""),
    GZIP(".gz");

    private final String extension;

    Codec(String extension) {
      this.extension = extension;
    }

    public static Codec fromName(String codecName) {
      Preconditions.checkArgument(codecName != null, "Codec name is null");
      try {
        return Codec.valueOf(codecName.toUpperCase(Locale.ENGLISH));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(String.format("Invalid codec name: %s", codecName), e);
      }
    }

    public static Codec fromFileName(String fileName) {
      Preconditions.checkArgument(
          fileName.contains(".metadata.json"), "%s is not a valid metadata file", fileName);
      // we have to be backward-compatible with .metadata.json.gz files
      if (fileName.endsWith(".metadata.json.gz")) {
        return Codec.GZIP;
      }
      String fileNameWithoutSuffix = fileName.substring(0, fileName.lastIndexOf(".metadata.json"));
      if (fileNameWithoutSuffix.endsWith(Codec.GZIP.extension)) {
        return Codec.GZIP;
      } else {
        return Codec.NONE;
      }
    }
  }

  private TableMetadataParser() {}

  // visible for testing
  static final String FORMAT_VERSION = "format-version";
  static final String TABLE_UUID = "table-uuid";
  static final String LOCATION = "location";
  static final String LAST_SEQUENCE_NUMBER = "last-sequence-number";
  static final String LAST_UPDATED_MILLIS = "last-updated-ms";
  static final String LAST_COLUMN_ID = "last-column-id";
  static final String SCHEMA = "schema";
  static final String SCHEMAS = "schemas";
  static final String CURRENT_SCHEMA_ID = "current-schema-id";
  static final String PARTITION_SPEC = "partition-spec";
  static final String PARTITION_SPECS = "partition-specs";
  static final String DEFAULT_SPEC_ID = "default-spec-id";
  static final String LAST_PARTITION_ID = "last-partition-id";
  static final String DEFAULT_SORT_ORDER_ID = "default-sort-order-id";
  static final String SORT_ORDERS = "sort-orders";
  static final String PROPERTIES = "properties";
  static final String CURRENT_SNAPSHOT_ID = "current-snapshot-id";
  static final String REFS = "refs";
  static final String SNAPSHOTS = "snapshots";
  static final String SNAPSHOT_ID = "snapshot-id";
  static final String TIMESTAMP_MS = "timestamp-ms";
  static final String SNAPSHOT_LOG = "snapshot-log";
  static final String METADATA_FILE = "metadata-file";
  static final String METADATA_LOG = "metadata-log";
  static final String STATISTICS = "statistics";
  static final String PARTITION_STATISTICS = "partition-statistics";
  static final String NEXT_ROW_ID = "next-row-id";
  static final int MIN_NULL_CURRENT_SNAPSHOT_VERSION = 3;

  public static void overwrite(TableMetadata metadata, OutputFile outputFile) {
    internalWrite(metadata, outputFile, true);
  }

  public static void write(TableMetadata metadata, OutputFile outputFile) {
    internalWrite(metadata, outputFile, false);
  }

  public static void internalWrite(
      TableMetadata metadata, OutputFile outputFile, boolean overwrite) {
    boolean isGzip = Codec.fromFileName(outputFile.location()) == Codec.GZIP;
    OutputStream stream = overwrite ? outputFile.createOrOverwrite() : outputFile.create();
    try (OutputStream ou = isGzip ? new GZIPOutputStream(stream) : stream;
        OutputStreamWriter writer = new OutputStreamWriter(ou, StandardCharsets.UTF_8)) {
      JsonGenerator generator = JsonUtil.factory().createGenerator(writer);
      toJson(metadata, generator);
      generator.flush();
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to write json to file: %s", outputFile.location());
    }
  }

  public static String getFileExtension(String codecName) {
    return getFileExtension(Codec.fromName(codecName));
  }

  public static String getFileExtension(Codec codec) {
    return codec.extension + ".metadata.json";
  }

  public static String getOldFileExtension(Codec codec) {
    // we have to be backward-compatible with .metadata.json.gz files
    return ".metadata.json" + codec.extension;
  }

  public static String toJson(TableMetadata metadata) {
    try (StringWriter writer = new StringWriter()) {
      JsonGenerator generator = JsonUtil.factory().createGenerator(writer);
      toJson(metadata, generator);
      generator.flush();
      return writer.toString();
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to write json for: %s", metadata);
    }
  }

  @SuppressWarnings("checkstyle:CyclomaticComplexity")
  public static void toJson(TableMetadata metadata, JsonGenerator generator) throws IOException {
    generator.writeStartObject();

    generator.writeNumberField(FORMAT_VERSION, metadata.formatVersion());
    generator.writeStringField(TABLE_UUID, metadata.uuid());
    generator.writeStringField(LOCATION, metadata.location());
    if (metadata.formatVersion() > 1) {
      generator.writeNumberField(LAST_SEQUENCE_NUMBER, metadata.lastSequenceNumber());
    }
    generator.writeNumberField(LAST_UPDATED_MILLIS, metadata.lastUpdatedMillis());
    generator.writeNumberField(LAST_COLUMN_ID, metadata.lastColumnId());

    // for older readers, continue writing the current schema as "schema".
    // this is only needed for v1 because support for schemas and current-schema-id is required in
    // v2 and later.
    if (metadata.formatVersion() == 1) {
      generator.writeFieldName(SCHEMA);
      SchemaParser.toJson(metadata.schema(), generator);
    }

    // write the current schema ID and schema list
    generator.writeNumberField(CURRENT_SCHEMA_ID, metadata.currentSchemaId());
    generator.writeArrayFieldStart(SCHEMAS);
    for (Schema schema : metadata.schemas()) {
      SchemaParser.toJson(schema, generator);
    }
    generator.writeEndArray();

    // for older readers, continue writing the default spec as "partition-spec"
    if (metadata.formatVersion() == 1) {
      generator.writeFieldName(PARTITION_SPEC);
      PartitionSpecParser.toJsonFields(metadata.spec(), generator);
    }

    // write the default spec ID and spec list
    generator.writeNumberField(DEFAULT_SPEC_ID, metadata.defaultSpecId());
    generator.writeArrayFieldStart(PARTITION_SPECS);
    for (PartitionSpec spec : metadata.specs()) {
      PartitionSpecParser.toJson(spec, generator);
    }
    generator.writeEndArray();

    generator.writeNumberField(LAST_PARTITION_ID, metadata.lastAssignedPartitionId());

    // write the default order ID and sort order list
    generator.writeNumberField(DEFAULT_SORT_ORDER_ID, metadata.defaultSortOrderId());
    generator.writeArrayFieldStart(SORT_ORDERS);
    for (SortOrder sortOrder : metadata.sortOrders()) {
      SortOrderParser.toJson(sortOrder, generator);
    }
    generator.writeEndArray();

    // write properties map
    JsonUtil.writeStringMap(PROPERTIES, metadata.properties(), generator);

    if (metadata.currentSnapshot() != null) {
      generator.writeNumberField(CURRENT_SNAPSHOT_ID, metadata.currentSnapshot().snapshotId());
    } else {
      if (metadata.formatVersion() >= MIN_NULL_CURRENT_SNAPSHOT_VERSION) {
        generator.writeNullField(CURRENT_SNAPSHOT_ID);
      } else {
        generator.writeNumberField(CURRENT_SNAPSHOT_ID, -1L);
      }
    }

    if (metadata.formatVersion() >= 3) {
      generator.writeNumberField(NEXT_ROW_ID, metadata.nextRowId());
    }

    toJson(metadata.refs(), generator);

    generator.writeArrayFieldStart(SNAPSHOTS);
    for (Snapshot snapshot : metadata.snapshots()) {
      SnapshotParser.toJson(snapshot, generator);
    }
    generator.writeEndArray();

    generator.writeArrayFieldStart(STATISTICS);
    for (StatisticsFile statisticsFile : metadata.statisticsFiles()) {
      StatisticsFileParser.toJson(statisticsFile, generator);
    }
    generator.writeEndArray();

    generator.writeArrayFieldStart(PARTITION_STATISTICS);
    for (PartitionStatisticsFile partitionStatisticsFile : metadata.partitionStatisticsFiles()) {
      PartitionStatisticsFileParser.toJson(partitionStatisticsFile, generator);
    }
    generator.writeEndArray();

    generator.writeArrayFieldStart(SNAPSHOT_LOG);
    for (HistoryEntry logEntry : metadata.snapshotLog()) {
      generator.writeStartObject();
      generator.writeNumberField(TIMESTAMP_MS, logEntry.timestampMillis());
      generator.writeNumberField(SNAPSHOT_ID, logEntry.snapshotId());
      generator.writeEndObject();
    }
    generator.writeEndArray();

    generator.writeArrayFieldStart(METADATA_LOG);
    for (MetadataLogEntry logEntry : metadata.previousFiles()) {
      generator.writeStartObject();
      generator.writeNumberField(TIMESTAMP_MS, logEntry.timestampMillis());
      generator.writeStringField(METADATA_FILE, logEntry.file());
      generator.writeEndObject();
    }
    generator.writeEndArray();

    generator.writeEndObject();
  }

  private static void toJson(Map<String, SnapshotRef> refs, JsonGenerator generator)
      throws IOException {
    generator.writeObjectFieldStart(REFS);
    for (Map.Entry<String, SnapshotRef> refEntry : refs.entrySet()) {
      generator.writeFieldName(refEntry.getKey());
      SnapshotRefParser.toJson(refEntry.getValue(), generator);
    }
    generator.writeEndObject();
  }

  public static TableMetadata read(FileIO io, String path) {
    return read(io, io.newInputFile(path));
  }

  public static TableMetadata read(FileIO io, InputFile file) {
    Codec codec = Codec.fromFileName(file.location());
    try (InputStream is =
        codec == Codec.GZIP ? new GZIPInputStream(file.newStream()) : file.newStream()) {
      return fromJson(file, JsonUtil.mapper().readValue(is, JsonNode.class));
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to read file: %s", file.location());
    }
  }

  /**
   * Read TableMetadata from a JSON string.
   *
   * <p>The TableMetadata's metadata file location will be unset.
   *
   * @param json a JSON string of table metadata
   * @return a TableMetadata object
   */
  public static TableMetadata fromJson(String json) {
    return fromJson(null, json);
  }

  /**
   * Read TableMetadata from a JSON string.
   *
   * @param metadataLocation metadata location for the returned {@link TableMetadata}
   * @param json a JSON string of table metadata
   * @return a TableMetadata object
   */
  public static TableMetadata fromJson(String metadataLocation, String json) {
    return JsonUtil.parse(json, node -> TableMetadataParser.fromJson(metadataLocation, node));
  }

  public static TableMetadata fromJson(InputFile file, JsonNode node) {
    return fromJson(file.location(), node);
  }

  public static TableMetadata fromJson(JsonNode node) {
    return fromJson((String) null, node);
  }

  @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:MethodLength"})
  public static TableMetadata fromJson(String metadataLocation, JsonNode node) {
    Preconditions.checkArgument(
        node.isObject(), "Cannot parse metadata from a non-object: %s", node);

    int formatVersion = JsonUtil.getInt(FORMAT_VERSION, node);
    Preconditions.checkArgument(
        formatVersion <= TableMetadata.SUPPORTED_TABLE_FORMAT_VERSION,
        "Cannot read unsupported version %s",
        formatVersion);

    String uuid = JsonUtil.getStringOrNull(TABLE_UUID, node);
    String location = JsonUtil.getString(LOCATION, node);
    long lastSequenceNumber;
    if (formatVersion > 1) {
      lastSequenceNumber = JsonUtil.getLong(LAST_SEQUENCE_NUMBER, node);
    } else {
      lastSequenceNumber = TableMetadata.INITIAL_SEQUENCE_NUMBER;
    }
    int lastAssignedColumnId = JsonUtil.getInt(LAST_COLUMN_ID, node);

    List<Schema> schemas;
    int currentSchemaId;
    Schema schema = null;

    JsonNode schemaArray = node.get(SCHEMAS);
    if (schemaArray != null) {
      Preconditions.checkArgument(
          schemaArray.isArray(), "Cannot parse schemas from non-array: %s", schemaArray);
      // current schema ID is required when the schema array is present
      currentSchemaId = JsonUtil.getInt(CURRENT_SCHEMA_ID, node);

      // parse the schema array
      ImmutableList.Builder<Schema> builder = ImmutableList.builder();
      for (JsonNode schemaNode : schemaArray) {
        Schema current = SchemaParser.fromJson(schemaNode);
        if (current.schemaId() == currentSchemaId) {
          schema = current;
        }
        builder.add(current);
      }

      Preconditions.checkArgument(
          schema != null,
          "Cannot find schema with %s=%s from %s",
          CURRENT_SCHEMA_ID,
          currentSchemaId,
          SCHEMAS);

      schemas = builder.build();

    } else {
      Preconditions.checkArgument(
          formatVersion == 1, "%s must exist in format v%s", SCHEMAS, formatVersion);

      schema = SchemaParser.fromJson(JsonUtil.get(SCHEMA, node));
      currentSchemaId = schema.schemaId();
      schemas = ImmutableList.of(schema);
    }

    JsonNode specArray = node.get(PARTITION_SPECS);
    List<PartitionSpec> specs;
    int defaultSpecId;
    if (specArray != null) {
      Preconditions.checkArgument(
          specArray.isArray(), "Cannot parse partition specs from non-array: %s", specArray);
      // default spec ID is required when the spec array is present
      defaultSpecId = JsonUtil.getInt(DEFAULT_SPEC_ID, node);

      // parse the spec array
      ImmutableList.Builder<PartitionSpec> builder = ImmutableList.builder();
      for (JsonNode spec : specArray) {
        UnboundPartitionSpec unboundSpec = PartitionSpecParser.fromJson(spec);
        if (unboundSpec.specId() == defaultSpecId) {
          builder.add(unboundSpec.bind(schema));
        } else {
          builder.add(unboundSpec.bindUnchecked(schema));
        }
      }
      specs = builder.build();

    } else {
      Preconditions.checkArgument(
          formatVersion == 1, "%s must exist in format v%s", PARTITION_SPECS, formatVersion);
      // partition spec is required for older readers, but is always set to the default if the spec
      // array is set. it is only used to default the spec map is missing, indicating that the
      // table metadata was written by an older writer.
      defaultSpecId = TableMetadata.INITIAL_SPEC_ID;
      specs =
          ImmutableList.of(
              PartitionSpecParser.fromJsonFields(
                  schema, TableMetadata.INITIAL_SPEC_ID, JsonUtil.get(PARTITION_SPEC, node)));
    }

    Integer lastAssignedPartitionId = JsonUtil.getIntOrNull(LAST_PARTITION_ID, node);
    if (lastAssignedPartitionId == null) {
      Preconditions.checkArgument(
          formatVersion == 1, "%s must exist in format v%s", LAST_PARTITION_ID, formatVersion);
      lastAssignedPartitionId =
          specs.stream()
              .mapToInt(PartitionSpec::lastAssignedFieldId)
              .max()
              .orElse(PartitionSpec.unpartitioned().lastAssignedFieldId());
    }

    // parse the sort orders
    JsonNode sortOrderArray = node.get(SORT_ORDERS);
    List<SortOrder> sortOrders;
    int defaultSortOrderId;
    if (sortOrderArray != null) {
      defaultSortOrderId = JsonUtil.getInt(DEFAULT_SORT_ORDER_ID, node);
      ImmutableList.Builder<SortOrder> sortOrdersBuilder = ImmutableList.builder();
      for (JsonNode sortOrder : sortOrderArray) {
        sortOrdersBuilder.add(SortOrderParser.fromJson(schema, sortOrder, defaultSortOrderId));
      }
      sortOrders = sortOrdersBuilder.build();
    } else {
      Preconditions.checkArgument(
          formatVersion == 1, "%s must exist in format v%s", SORT_ORDERS, formatVersion);
      SortOrder defaultSortOrder = SortOrder.unsorted();
      sortOrders = ImmutableList.of(defaultSortOrder);
      defaultSortOrderId = defaultSortOrder.orderId();
    }

    Map<String, String> properties;
    if (node.has(PROPERTIES)) {
      // parse properties map
      properties = JsonUtil.getStringMap(PROPERTIES, node);
    } else {
      properties = ImmutableMap.of();
    }

    Long currentSnapshotId = JsonUtil.getLongOrNull(CURRENT_SNAPSHOT_ID, node);
    if (currentSnapshotId == null) {
      // This field is optional, but internally we set this to -1 when not set
      currentSnapshotId = -1L;
    }

    long lastRowId;
    if (formatVersion >= 3) {
      lastRowId = JsonUtil.getLong(NEXT_ROW_ID, node);
    } else {
      lastRowId = TableMetadata.INITIAL_ROW_ID;
    }

    long lastUpdatedMillis = JsonUtil.getLong(LAST_UPDATED_MILLIS, node);

    Map<String, SnapshotRef> refs;
    if (node.has(REFS)) {
      refs = refsFromJson(node.get(REFS));
    } else if (currentSnapshotId != -1L) {
      // initialize the main branch if there are no refs
      refs =
          ImmutableMap.of(
              SnapshotRef.MAIN_BRANCH, SnapshotRef.branchBuilder(currentSnapshotId).build());
    } else {
      refs = ImmutableMap.of();
    }

    List<Snapshot> snapshots;
    if (node.has(SNAPSHOTS)) {
      JsonNode snapshotArray = JsonUtil.get(SNAPSHOTS, node);
      Preconditions.checkArgument(
          snapshotArray.isArray(), "Cannot parse snapshots from non-array: %s", snapshotArray);

      snapshots = Lists.newArrayListWithExpectedSize(snapshotArray.size());
      Iterator<JsonNode> iterator = snapshotArray.elements();
      while (iterator.hasNext()) {
        snapshots.add(SnapshotParser.fromJson(iterator.next()));
      }
    } else {
      snapshots = ImmutableList.of();
    }

    List<StatisticsFile> statisticsFiles;
    if (node.has(STATISTICS)) {
      statisticsFiles = statisticsFilesFromJson(node.get(STATISTICS));
    } else {
      statisticsFiles = ImmutableList.of();
    }

    List<PartitionStatisticsFile> partitionStatisticsFiles;
    if (node.has(PARTITION_STATISTICS)) {
      partitionStatisticsFiles = partitionStatsFilesFromJson(node.get(PARTITION_STATISTICS));
    } else {
      partitionStatisticsFiles = ImmutableList.of();
    }

    ImmutableList.Builder<HistoryEntry> entries = ImmutableList.builder();
    if (node.has(SNAPSHOT_LOG)) {
      Iterator<JsonNode> logIterator = node.get(SNAPSHOT_LOG).elements();
      while (logIterator.hasNext()) {
        JsonNode entryNode = logIterator.next();
        entries.add(
            new SnapshotLogEntry(
                JsonUtil.getLong(TIMESTAMP_MS, entryNode),
                JsonUtil.getLong(SNAPSHOT_ID, entryNode)));
      }
    }

    ImmutableList.Builder<MetadataLogEntry> metadataEntries = ImmutableList.builder();
    if (node.has(METADATA_LOG)) {
      Iterator<JsonNode> logIterator = node.get(METADATA_LOG).elements();
      while (logIterator.hasNext()) {
        JsonNode entryNode = logIterator.next();
        metadataEntries.add(
            new MetadataLogEntry(
                JsonUtil.getLong(TIMESTAMP_MS, entryNode),
                JsonUtil.getString(METADATA_FILE, entryNode)));
      }
    }

    return new TableMetadata(
        metadataLocation,
        formatVersion,
        uuid,
        location,
        lastSequenceNumber,
        lastUpdatedMillis,
        lastAssignedColumnId,
        currentSchemaId,
        schemas,
        defaultSpecId,
        specs,
        lastAssignedPartitionId,
        defaultSortOrderId,
        sortOrders,
        properties,
        currentSnapshotId,
        snapshots,
        null,
        entries.build(),
        metadataEntries.build(),
        refs,
        statisticsFiles,
        partitionStatisticsFiles,
        lastRowId,
        ImmutableList.of() /* no changes from the file */);
  }

  private static Map<String, SnapshotRef> refsFromJson(JsonNode refMap) {
    Preconditions.checkArgument(refMap.isObject(), "Cannot parse refs from non-object: %s", refMap);

    ImmutableMap.Builder<String, SnapshotRef> refsBuilder = ImmutableMap.builder();
    Iterator<String> refNames = refMap.fieldNames();
    while (refNames.hasNext()) {
      String refName = refNames.next();
      JsonNode refNode = JsonUtil.get(refName, refMap);
      Preconditions.checkArgument(
          refNode.isObject(), "Cannot parse ref %s from non-object: %s", refName, refMap);
      SnapshotRef ref = SnapshotRefParser.fromJson(refNode);
      refsBuilder.put(refName, ref);
    }

    return refsBuilder.build();
  }

  private static List<StatisticsFile> statisticsFilesFromJson(JsonNode statisticsFilesList) {
    Preconditions.checkArgument(
        statisticsFilesList.isArray(),
        "Cannot parse statistics files from non-array: %s",
        statisticsFilesList);

    ImmutableList.Builder<StatisticsFile> statisticsFilesBuilder = ImmutableList.builder();
    for (JsonNode statisticsFile : statisticsFilesList) {
      statisticsFilesBuilder.add(StatisticsFileParser.fromJson(statisticsFile));
    }

    return statisticsFilesBuilder.build();
  }

  private static List<PartitionStatisticsFile> partitionStatsFilesFromJson(JsonNode filesList) {
    Preconditions.checkArgument(
        filesList.isArray(),
        "Cannot parse partition statistics files from non-array: %s",
        filesList);

    ImmutableList.Builder<PartitionStatisticsFile> statsFileBuilder = ImmutableList.builder();
    for (JsonNode partitionStatsFile : filesList) {
      statsFileBuilder.add(PartitionStatisticsFileParser.fromJson(partitionStatsFile));
    }

    return statsFileBuilder.build();
  }
}
