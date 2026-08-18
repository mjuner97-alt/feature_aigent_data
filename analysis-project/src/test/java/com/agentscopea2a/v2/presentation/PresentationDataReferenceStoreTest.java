package com.agentscopea2a.v2.presentation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PresentationDataReferenceStoreTest {
    @Test
    void storesTypedRowsBehindOpaqueReference() {
        PresentationDataReferenceStore store = new PresentationDataReferenceStore();
        String ref = store.put("sql", "q2_1_report_by_dept_version",
                List.of(Map.of("total", 80L)));

        PresentationDataReferenceStore.DataSet data = store.get(ref);
        assertEquals("sql", data.providerType());
        assertEquals("q2_1_report_by_dept_version", data.providerId());
        assertEquals(80L, data.rows().get(0).get("total"));
        assertThrows(IllegalArgumentException.class, () -> store.get("pdr_missing"));
    }
}
