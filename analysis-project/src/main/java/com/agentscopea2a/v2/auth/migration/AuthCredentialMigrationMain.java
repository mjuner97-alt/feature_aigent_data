package com.agentscopea2a.v2.auth.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

/** One-shot migration: export password hashes from the old service and persist them in Gauss. */
public final class AuthCredentialMigrationMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: java ...AuthCredentialMigrationMain <exportUrl> <secret> <jdbcUrl> <dbUser> [dbPassword]");
            System.exit(2);
        }
        String exportUrl = args[0], secret = args[1], jdbcUrl = args[2], dbUser = args[3];
        String dbPassword = args.length > 4 ? args[4] : "";
        HttpRequest request = HttpRequest.newBuilder(URI.create(exportUrl))
                .header("X-Auth-Migration-Secret", secret).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("export failed: HTTP " + response.statusCode());
        JsonNode rows = new ObjectMapper().readTree(response.body()).path("credentials");
        String sql = "MERGE INTO auth_user_credential t USING (SELECT ? AS user_id, ? AS password_hash) s ON (t.user_id=s.user_id) WHEN MATCHED THEN UPDATE SET password_hash=s.password_hash,password_changed_at=CURRENT_TIMESTAMP WHEN NOT MATCHED THEN INSERT (user_id,password_hash,password_changed_at,login_failures,locked_until,session_version) VALUES (s.user_id,s.password_hash,CURRENT_TIMESTAMP,0,NULL,0)";
        int count = 0;
        try (Connection c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (JsonNode row : rows) {
                String userId = row.path("userId").asText("");
                String hash = row.path("passwordHash").asText("");
                if (userId.isBlank() || hash.isBlank()) continue;
                ps.setString(1, userId); ps.setString(2, hash); ps.addBatch(); count++;
            }
            ps.executeBatch(); c.commit();
        }
        System.out.println("Migrated " + count + " credential records.");
    }
}
