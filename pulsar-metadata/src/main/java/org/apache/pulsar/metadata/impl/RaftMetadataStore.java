/**
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
package org.apache.pulsar.metadata.impl;

import com.alipay.sofa.jraft.rhea.client.DefaultRheaKVStore;
import com.alipay.sofa.jraft.rhea.client.RheaIterator;
import com.alipay.sofa.jraft.rhea.client.RheaKVStore;
import com.alipay.sofa.jraft.rhea.options.PlacementDriverOptions;
import com.alipay.sofa.jraft.rhea.options.RegionRouteTableOptions;
import com.alipay.sofa.jraft.rhea.options.RheaKVStoreOptions;
import com.alipay.sofa.jraft.rhea.options.configured.MultiRegionRouteTableOptionsConfigured;
import com.alipay.sofa.jraft.rhea.options.configured.PlacementDriverOptionsConfigured;
import com.alipay.sofa.jraft.rhea.options.configured.RheaKVStoreOptionsConfigured;
import com.alipay.sofa.jraft.rhea.storage.KVEntry;
import com.alipay.sofa.jraft.util.BytesUtil;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.bookkeeper.meta.store.driver.MetadataStoreConstants;
import org.apache.pulsar.metadata.api.GetResult;
import org.apache.pulsar.metadata.api.MetadataStoreConfig;
import org.apache.pulsar.metadata.api.MetadataStoreException;
import org.apache.pulsar.metadata.api.Stat;
import org.apache.pulsar.metadata.api.extended.CreateOption;

@Slf4j
public class RaftMetadataStore extends AbstractMetadataStore {
    private RheaKVStore kvStore;

    @Data
    private static class Value {
        final long version;
        final byte[] data;
        final long createdTimestamp;
        final long modifiedTimestamp;
        final boolean ephemeral;

        public static Value parse(String value) {
            return new Gson().fromJson(value, Value.class);
        }

        public String toString() {
            return new Gson().toJson(this);
        }
    }

    public RaftMetadataStore(String metadataURL, MetadataStoreConfig metadataStoreConfig) {
        kvStore = new DefaultRheaKVStore();
        final List<RegionRouteTableOptions> regionRouteTableOptionsList = MultiRegionRouteTableOptionsConfigured
            .newConfigured() //
            .withInitialServerList(-1L /* default id */, metadataURL) //
            .config();
        final PlacementDriverOptions pdOpts = PlacementDriverOptionsConfigured.newConfigured() //
            .withFake(true) //
            .withRegionRouteTableOptionsList(regionRouteTableOptionsList) //
            .config();
        final RheaKVStoreOptions opts = RheaKVStoreOptionsConfigured.newConfigured() //
            .withClusterName("rhea_example") //
            .withPlacementDriverOptions(pdOpts) //
            .config();
        System.out.println(opts);
        kvStore.init(opts);
    }


    @Override
    public CompletableFuture<Optional<GetResult>> get(String path) {
        log.info("Getting content from path {}", path);
        CompletableFuture<Optional<GetResult>> future = new CompletableFuture<>();
        kvStore.get(path).whenComplete((value, throwable) -> {
            if (throwable != null) {
                log.error("Failed to get content from path {}", path, throwable);
                future.completeExceptionally(throwable);
            } else {
                log.info("Get content from path {} successfully", path);
                Value v = Value.parse(BytesUtil.readUtf8(value));
                if (v != null) {
                    future.complete(Optional.of(new GetResult(v.data, getFromValue(path, v))));
                } else {
                    future.complete(Optional.empty());
                }
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Stat> put(String path, byte[] value, Optional<Long> expectedVersion) {
        log.info("Putting content to the path {}", path);
        return put(path, value, expectedVersion, EnumSet.noneOf(CreateOption.class));
    }

    @Override
    protected CompletableFuture<List<String>> getChildrenFromStore(String path) {
        log.info("Getting child from path {}", path);
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        List<String> values = new ArrayList<>();
        String endKey = path + MetadataStoreConstants.END_SEP;
        final RheaIterator<KVEntry> it =  kvStore.iterator(path, endKey, 5);
        while (it.hasNext()) {
            final KVEntry kv = it.next();
            String key = BytesUtil.readUtf8(kv.getKey());
            if (key.startsWith(path)) {
                if (key.length() == path.length()) {
                    // ignore the path prefix itself
                    continue;
                }
                if (key.indexOf('/', path.length() + 1) >= 0) {
                    // ignore the sub-paths
                    continue;
                }
                // return path segments only
                values.add(key.substring(path.length() + 1));
            }
        }
        log.info("Children {} get successfully from path {}", values, path);
        future.complete(values);
        return future;
    }

    @Override
    protected CompletableFuture<Boolean> existsFromStore(String path) {
        log.info("check path {} is exists", path);
        return kvStore.get(path).thenApply(value -> value != null);
    }

    @Override
    protected CompletableFuture<Void> storeDelete(String path, Optional<Long> expectedVersion) {
        log.info("Delete path {}", path);
        return kvStore.delete(path).thenApply(ignore -> null);
    }

    @Override
    protected CompletableFuture<Stat> storePut(String path, byte[] data, Optional<Long> optExpectedVersion, EnumSet<CreateOption> options) {
        log.info("Put content to the path {}", path);
        CompletableFuture<Stat> future = new CompletableFuture<>();
        boolean hasVersion = optExpectedVersion.isPresent();
        int expectedVersion = optExpectedVersion.orElse(-1L).intValue();

        long now = System.currentTimeMillis();

            Value newValue = new Value(0, data, now, now, options.contains(CreateOption.Ephemeral));
        kvStore.put(path, BytesUtil.writeUtf8(newValue.toString()))
            .whenComplete((success, throwable) -> {
                if (!success || throwable != null) {
                    log.info("Put content to the path failed {}", path);
                    future.completeExceptionally(new MetadataStoreException("save value for path " + path + " failed", throwable));
                } else {
                    log.info("Put content to the path successfully {}", path);
                    future.complete(new Stat(path, 0, now, now, false, false));
                }
            });
        return future;
    }

    private Stat getFromValue(String path, Value value) {
        return new Stat(path, value.version, value.createdTimestamp, value.modifiedTimestamp, false, false);
    }

    @Override
    public void close() throws Exception {
        this.kvStore.shutdown();
        super.close();
    }
}
