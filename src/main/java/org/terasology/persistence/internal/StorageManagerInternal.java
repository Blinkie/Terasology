/*
 * Copyright 2013 Moving Blocks
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
package org.terasology.persistence.internal;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.paths.PathManager;
import org.terasology.entitySystem.Component;
import org.terasology.entitySystem.EngineEntityManager;
import org.terasology.entitySystem.EntityDestroySubscriber;
import org.terasology.entitySystem.EntityRef;
import org.terasology.entitySystem.metadata.ClassMetadata;
import org.terasology.entitySystem.metadata.ComponentLibrary;
import org.terasology.entitySystem.prefab.PrefabManager;
import org.terasology.math.Vector3i;
import org.terasology.persistence.ChunkStore;
import org.terasology.persistence.StorageManager;
import org.terasology.persistence.PlayerStore;
import org.terasology.persistence.serializers.EntitySerializer;
import org.terasology.persistence.serializers.PrefabSerializer;
import org.terasology.protobuf.EntityData;
import org.terasology.world.chunks.Chunk;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @author Immortius
 */
public class StorageManagerInternal implements StorageManager, EntityDestroySubscriber {
    private static final String PLAYERS_PATH = "players";
    private static final String PLAYER_STORE_EXTENSION = ".player";
    private static final String GLOBAL_ENTITY_STORE = "global.dat";

    private static final Logger logger = LoggerFactory.getLogger(StorageManagerInternal.class);

    private Path playersPath;

    private EngineEntityManager entityManager;
    private Map<String, EntityData.PlayerEntityStore> playerStores = Maps.newHashMap();
    private TIntObjectMap<List<StoreRefTable>> externalRefHolderLookup = new TIntObjectHashMap<>();
    private Map<String, StoreRefTable> playerStoreExternalRefs = Maps.newHashMap();

    private Map<Vector3i, ChunkStore> chunkStores = Maps.newHashMap();

    public StorageManagerInternal(EngineEntityManager entityManager) {
        this.entityManager = entityManager;
        entityManager.subscribe(this);
        playersPath = PathManager.getInstance().getCurrentSavePath().resolve(PLAYERS_PATH);
    }

    @Override
    public void loadGlobalEntities() throws IOException {
        Path globalDataFile = PathManager.getInstance().getCurrentSavePath().resolve(GLOBAL_ENTITY_STORE);
        if (Files.isRegularFile(globalDataFile)) {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(globalDataFile))) {
                EntityData.GlobalEntityStore store = EntityData.GlobalEntityStore.parseFrom(in);
                GlobalStoreLoader loader = new GlobalStoreLoader(entityManager);
                loader.load(store);
                for (StoreRefTable refTable : loader.getStoreRefTables()) {
                    playerStoreExternalRefs.put(refTable.getId(), refTable);
                    indexStoreRefTable(refTable);
                }
            }
        }
    }

    @Override
    public PlayerStore createPlayerStoreForSave(String playerId) {
        return new PlayerStoreInternal(playerId, this, entityManager);
    }

    @Override
    public void flush() throws IOException {
        Files.createDirectories(playersPath);
        for (Map.Entry<String, EntityData.PlayerEntityStore> playerStoreEntry : playerStores.entrySet()) {
            Path playerFile = playersPath.resolve(playerStoreEntry.getKey() + PLAYER_STORE_EXTENSION);
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(playerFile));) {
                playerStoreEntry.getValue().writeTo(out);
            }
        }
        playerStores.clear();

        GlobalStoreSaver globalStore = new GlobalStoreSaver(entityManager);
        for (EntityRef entity : entityManager.getAllEntities()) {
            globalStore.store(entity);
        }
        for (StoreRefTable table : playerStoreExternalRefs.values()) {
            globalStore.addRefTable(table.getId(), table.getExternalReferences());
        }
        EntityData.GlobalEntityStore globalStoreData = globalStore.save();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(PathManager.getInstance().getCurrentSavePath().resolve(GLOBAL_ENTITY_STORE)))) {
            globalStoreData.writeTo(out);
        }
    }

    @Override
    public PlayerStore loadPlayerStore(String playerId) {
        EntityData.PlayerEntityStore store = playerStores.get(playerId);
        if (store == null) {
            Path storePath = playersPath.resolve(playerId + PLAYER_STORE_EXTENSION);
            if (Files.isRegularFile(storePath)) {
                try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(storePath))) {
                    store = EntityData.PlayerEntityStore.parseFrom(inputStream);
                } catch (IOException e) {
                    logger.error("Failed to load player data for {}", playerId, e);
                }
            }
        }
        if (store != null) {
            TIntSet validRefs = null;
            StoreRefTable table = playerStoreExternalRefs.get(playerId);
            if (table != null) {
                validRefs = table.getExternalReferences();
            }
            return new PlayerStoreInternal(playerId, store, validRefs, this, entityManager);
        }
        return null;
    }

    @Override
    public ChunkStore createChunkStoreForSave(Chunk chunk) {
        return new ChunkStoreInternal(chunk, this);
    }

    @Override
    public ChunkStore loadChunkStore(Vector3i chunkPos) {
        return chunkStores.get(chunkPos);
    }

    public void store(ChunkStoreInternal chunkStore) {
        this.chunkStores.put(chunkStore.getChunkPosition(), chunkStore);
    }

    public void store(String id, EntityData.PlayerEntityStore playerStore, TIntSet externalReference) {
        if (externalReference.size() > 0) {
            StoreRefTable refTable = new StoreRefTable(id, externalReference);
            indexStoreRefTable(refTable);
        }
        playerStores.put(id, playerStore);
    }

    private void indexStoreRefTable(StoreRefTable refTable) {
        playerStoreExternalRefs.put(refTable.getId(), refTable);
        TIntIterator iterator = refTable.getExternalReferences().iterator();
        while (iterator.hasNext()) {
            int refId = iterator.next();
            List<StoreRefTable> tables = externalRefHolderLookup.get(refId);
            if (tables == null) {
                tables = Lists.newArrayList();
                externalRefHolderLookup.put(refId, tables);
            }
            tables.add(refTable);
        }
    }

    @Override
    public void onEntityDestroyed(int entityId) {
        List<StoreRefTable> tables = externalRefHolderLookup.remove(entityId);
        for (StoreRefTable table : tables) {
            table.getExternalReferences().remove(entityId);
            if (table.getExternalReferences().isEmpty()) {
                playerStoreExternalRefs.remove(table.getId());
            }
        }
    }
}