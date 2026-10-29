// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

/** Непроверяемая ошибка слоя персистентности. */
public class PersistenceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PersistenceException(String message) {
    super(message);
  }

  public PersistenceException(String message, Throwable cause) {
    super(message, cause);
  }
}
