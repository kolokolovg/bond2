// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/** Единица работы с БД внутри транзакции. */
@FunctionalInterface
public interface Work<T> {

  T execute(Connection connection) throws SQLException;
}
