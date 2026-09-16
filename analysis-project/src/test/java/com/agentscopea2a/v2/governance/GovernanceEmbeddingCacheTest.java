package com.agentscopea2a.v2.governance;

import com.agentscopea2a.v2.skills.EmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceEmbeddingCacheTest {

    private static SkillDescriptionSource skills(SkillDescriptionSource.SkillDescriptionRow... rows) {
        return () -> List.of(rows);
    }

    private static SkillDescriptionSource.SkillDescriptionRow row(String retrievalName, String description) {
        return new SkillDescriptionSource.SkillDescriptionRow(1L, "n", description, "u1", retrievalName, "PERSONAL");
    }

    @Test
    void nullClientMeansSemanticUnavailableAndWarm() {
        GovernanceEmbeddingCache cache =
                new GovernanceEmbeddingCache(null, skills(), null);
        cache.startWarmup();
        assertFalse(cache.semanticAvailable());
        assertTrue(cache.warm());
        assertNull(cache.embeddingFor(GovernanceEmbeddingCache.EntityType.SKILL, "page_1", "desc"));
    }

    @Test
    void cachingByKeyDescriptionHash() {
        AtomicInteger embedCalls = new AtomicInteger();
        float[] vec = {0.1f, 0.2f};
        EmbeddingClient client = new EmbeddingClient() {
            @Override
            public float[] embed(String text) {
                embedCalls.incrementAndGet();
                return vec;
            }

            @Override
            public int dimension() {
                return 2;
            }
        };
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(client, skills(), null);

        assertSame(vec, cache.embeddingFor(GovernanceEmbeddingCache.EntityType.SKILL, "page_1", "desc-a"));
        assertSame(vec, cache.embeddingFor(GovernanceEmbeddingCache.EntityType.SKILL, "page_1", "desc-a"));
        assertEquals(1, embedCalls.get(), "same description must hit the cache");

        // Same entity, changed description -> new key -> one more embed call
        assertSame(vec, cache.embeddingFor(GovernanceEmbeddingCache.EntityType.SKILL, "page_1", "desc-b"));
        assertEquals(2, embedCalls.get());
    }

    @Test
    void failedEmbedIsNotCachedAndRetried() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingClient failing = new EmbeddingClient() {
            @Override
            public float[] embed(String text) {
                return calls.incrementAndGet() < 2 ? null : new float[] {1f};
            }

            @Override
            public int dimension() {
                return 1;
            }
        };
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(failing, skills(), null);
        assertNull(cache.embeddingFor(GovernanceEmbeddingCache.EntityType.TOOL, "t1", "d"));
        // mock embeds successfully on the second call -> the earlier null was not cached
        assertEquals(1f, cache.embeddingFor(GovernanceEmbeddingCache.EntityType.TOOL, "t1", "d")[0]);
        assertEquals(2, calls.get(), "null result must not be cached");
    }

    @Test
    void warmupEmbedsSkillsAndTools() throws Exception {
        AtomicInteger embedCalls = new AtomicInteger();
        EmbeddingClient client = new EmbeddingClient() {
            @Override
            public float[] embed(String text) {
                embedCalls.incrementAndGet();
                return new float[] {0.5f};
            }

            @Override
            public int dimension() {
                return 1;
            }
        };
        SkillDescriptionSource source = skills(row("page_1", "d1"), row("page_2", "d2"));
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(client, source, null);
        cache.startWarmup();
        // warmup runs on a daemon thread; poll briefly for completion
        long deadline = System.currentTimeMillis() + 2000;
        while (!cache.warm() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(cache.warm());
        assertEquals(2, embedCalls.get());
    }

    @Test
    void blankOrNullInputsReturnNullWithoutEmbedding() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingClient client = new EmbeddingClient() {
            @Override
            public float[] embed(String text) {
                calls.incrementAndGet();
                return new float[] {1f};
            }

            @Override
            public int dimension() {
                return 1;
            }
        };
        GovernanceEmbeddingCache cache = new GovernanceEmbeddingCache(client, skills(), null);
        assertNull(cache.embeddingFor(GovernanceEmbeddingCache.EntityType.SKILL, null, "d"));
        assertNull(cache.embeddingFor(GovernanceEmbeddingCache.EntityType.SKILL, "page_1", null));
        assertNull(cache.embeddingFor(GovernanceEmbeddingCache.EntityType.SKILL, "page_1", "  "));
        assertEquals(0, calls.get());
    }
}
