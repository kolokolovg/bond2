package ru.onlychill.bonds2;

/** Точка входа: считает купонный платёж по облигации. */
public final class Main {

  private Main() {}

  /**
   * Запуск: {@code Main <номинал в копейках> <ставка в б.п.> <выплат в год>}. Без аргументов
   * используются значения по умолчанию.
   */
  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) {
    try {
      long face = args.length > 0 ? Long.parseLong(args[0]) : 100_000L;
      int rateBps = args.length > 1 ? Integer.parseInt(args[1]) : 750;
      int perYear = args.length > 2 ? Integer.parseInt(args[2]) : 2;
      out.println("Купонный платёж (коп.): " + Coupon.payment(face, rateBps, perYear));
      return 0;
    } catch (IllegalArgumentException e) {
      err.println("Ошибка: " + e.getMessage());
      return 1;
    }
  }
}
