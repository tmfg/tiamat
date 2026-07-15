package org.rutebanken.tiamat.ext.fintraffic.db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/**
 * Adds DDL required by {@code FintrafficParking}:
 * <ul>
 *   <li>{@code parking_payment_methods} collection table for persisted payment methods</li>
 * </ul>
 * The {@code dtype} discriminator column is added by core migration
 * {@code V62__add_parking_dtype_column.sql}, which applies to all Tiamat deployments.
 */
public class V3__FintrafficParkingExtensions extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        String sql = """
                CREATE TABLE IF NOT EXISTS parking_payment_methods (
                    parking_id  BIGINT      NOT NULL REFERENCES parking(id),
                    payment_method VARCHAR(64) NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_parking_payment_methods_parking_id
                    ON parking_payment_methods (parking_id);
                """;

        try (Statement stmt = context.getConnection().createStatement()) {
            stmt.execute(sql);
        }
    }
}
