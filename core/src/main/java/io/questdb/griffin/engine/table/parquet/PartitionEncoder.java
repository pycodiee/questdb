/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.table.parquet;

import io.questdb.cairo.CairoException;
import io.questdb.cairo.TableReader;
import io.questdb.cairo.TableReaderMetadata;
import io.questdb.cairo.vm.api.MemoryR;
import io.questdb.std.*;
import io.questdb.std.str.DirectUtf8Sink;
import io.questdb.std.str.Path;

public class PartitionEncoder implements QuietCloseable {
    private DirectLongList columnAddrs = new DirectLongList(16, MemoryTag.NATIVE_DEFAULT);
    private DirectIntList columnIds = new DirectIntList(16, MemoryTag.NATIVE_DEFAULT);
    private DirectIntList columnNameLengths = new DirectIntList(16, MemoryTag.NATIVE_DEFAULT);
    private DirectUtf8Sink columnNames = new DirectUtf8Sink(32);
    private DirectLongList columnSecondaryAddrs = new DirectLongList(16, MemoryTag.NATIVE_DEFAULT);
    private DirectLongList columnTops = new DirectLongList(16, MemoryTag.NATIVE_DEFAULT);
    private DirectIntList columnTypes = new DirectIntList(16, MemoryTag.NATIVE_DEFAULT);

    @Override
    public void close() {
        columnNames = Misc.free(columnNames);
        columnNameLengths = Misc.free(columnNameLengths);
        columnTypes = Misc.free(columnTypes);
        columnIds = Misc.free(columnIds);
        columnTops = Misc.free(columnTops);
        columnAddrs = Misc.free(columnAddrs);
        columnSecondaryAddrs = Misc.free(columnSecondaryAddrs);
    }

    public void encode(TableReader tableReader, int partitionIndex, Path destPath) {
        final long partitionSize = tableReader.openPartition(partitionIndex);
        assert partitionSize != 0;

        final TableReaderMetadata metadata = tableReader.getMetadata();
        final int columnCount = metadata.getColumnCount();
        final int columnBase = tableReader.getColumnBase(partitionIndex);
        for (int i = 0; i < columnCount; i++) {
            final String columnName = metadata.getColumnName(i);
            columnNames.put(columnName);
            columnNameLengths.add(columnName.length());
            columnTypes.add(metadata.getColumnType(i));
            columnIds.add(metadata.getColumnMetadata(i).getWriterIndex());
            final long colTop = Math.min(tableReader.getColumnTop(columnBase, i), partitionSize);
            columnTops.add(colTop);
            final int primaryIndex = TableReader.getPrimaryColumnIndex(columnBase, i);
            final MemoryR primaryMem = tableReader.getColumn(primaryIndex);
            columnAddrs.add(primaryMem.addressOf(0));
            final MemoryR secondaryMem = tableReader.getColumn(primaryIndex + 1);
            columnSecondaryAddrs.add(secondaryMem != null ? secondaryMem.addressOf(0) : 0);
        }

        try {
            encodePartition(
                    columnCount,
                    columnNames.ptr(),
                    columnNames.size(),
                    columnNameLengths.getAddress(),
                    columnTypes.getAddress(),
                    columnIds.getAddress(),
                    metadata.getTimestampIndex(),
                    columnTops.getAddress(),
                    columnAddrs.getAddress(),
                    columnSecondaryAddrs.getAddress(),
                    partitionSize,
                    destPath.ptr(),
                    destPath.size()
            );
        } catch (Throwable th) {
            throw CairoException.critical(0).put("Could not encode partition: [table=").put(tableReader.getTableToken().getTableName())
                    .put(", partitionIndex=").put(partitionIndex)
                    .put(", msg=").put(th.getMessage())
                    .put(']');
        } finally {
            clear();
        }
    }

    private static native void encodePartition(
            int columnCount,
            long columnNamesPtr,
            int columnNamesLength,
            long columnNameLengthsPtr,
            long columnTypesPtr,
            long columnIdsPtr,
            int timestampIndex,
            long columnTopsPtr,
            long columnAddrsPtr,
            long columnSecondaryAddrsPtr,
            long rowCount,
            long destPathPtr,
            int destPathLength
    );

    private void clear() {
        columnNames.clear();
        columnNameLengths.clear();
        columnTypes.clear();
        columnIds.clear();
        columnTops.clear();
        columnAddrs.clear();
        columnSecondaryAddrs.clear();
    }

    static {
        Os.init();
    }
}