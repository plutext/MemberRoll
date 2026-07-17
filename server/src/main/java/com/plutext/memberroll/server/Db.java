/*
 * Copyright 2026 Jason Harrop
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.plutext.memberroll.server;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;

import java.sql.Connection;
import java.sql.Statement;

/**
 * The register database: Hikari pool + Flyway migration + the shared
 * {@link Jdbi} instance, wired at webapp start. Startup FAILS FAST if
 * the database is unreachable or a migration fails — a war that cannot
 * see its register must not come up half-alive (Tomcat then refuses the
 * context instead of serving 500s). Runtime loss of the database is a
 * different case: {@link #probe()} lets /api/health report degraded.
 *
 * Static accessor rather than injection on purpose (the NoteStore.fromEnv
 * idiom): resources stay plain classes, and there is exactly one database.
 */
@WebListener
public final class Db implements ServletContextListener {

    private static volatile HikariDataSource dataSource;
    private static volatile Jdbi jdbi;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        HikariConfig config = new HikariConfig();
        // explicit: DriverManager's service discovery misses drivers in the
        // webapp classloader under Tomcat, so name the class instead
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(env("MEMBERROLL_DB_URL", "jdbc:postgresql://localhost:5433/memberroll"));
        config.setUsername(env("MEMBERROLL_DB_USER", "memberroll"));
        config.setPassword(env("MEMBERROLL_DB_PASSWORD", "memberroll"));
        config.setMaximumPoolSize(5); // a hundreds-of-members app
        HikariDataSource pool = new HikariDataSource(config); // throws if unreachable
        Flyway.configure().dataSource(pool).load().migrate(); // throws on migration failure
        dataSource = pool;
        jdbi = Jdbi.create(pool);
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            jdbi = null;
        }
    }

    /** The shared Jdbi handle factory; valid once the context is up. */
    static Jdbi jdbi() {
        Jdbi j = jdbi;
        if (j == null) throw new IllegalStateException("database not initialized");
        return j;
    }

    /** Liveness probe for /api/health: can we still reach the database? */
    static boolean probe() {
        HikariDataSource pool = dataSource;
        if (pool == null) return false;
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value != null && !value.isBlank() ? value : fallback;
    }
}
