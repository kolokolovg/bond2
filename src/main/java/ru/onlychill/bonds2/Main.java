package ru.onlychill.bonds2;

import javafx.application.Application;

/** Точка входа: запускает главное окно. */
public final class Main {

  private Main() {}

  /** Запуск GUI; аргументы командной строки игнорируются. */
  public static void main(String[] args) {
    Application.launch(MainWindowApp.class);
  }
}
