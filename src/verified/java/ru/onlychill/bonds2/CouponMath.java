package ru.onlychill.bonds2;

/**
 * Чистая арифметика купона с формальными контрактами JML. Использует только подмножество Java,
 * поддерживаемое OpenJML (Java 21).
 */
public final class CouponMath {

  /** Максимальная ставка в базисных пунктах (100%). */
  public static final int MAX_RATE_BPS = 10_000;

  /** Максимальный номинал: гарантирует отсутствие переполнения {@code long} при умножении. */
  public static final long MAX_FACE_VALUE = 900_000_000_000_000L;

  private CouponMath() {}

  /**
   * Купонный платёж за период (в копейках, округление вниз).
   *
   * <p>Не бросает исключений и не переполняется при выполнении предусловия; результат неотрицателен
   * и не превышает номинал.
   */
  /*@ requires 0 <= faceValue && faceValue <= MAX_FACE_VALUE;
  @ requires 0 <= rateBps && rateBps <= MAX_RATE_BPS;
  @ requires 1 <= paymentsPerYear;
  @ ensures 0 <= \result && \result <= faceValue;
  @*/
  public static /*@ pure @*/ long payment(long faceValue, int rateBps, int paymentsPerYear) {
    long numerator = faceValue * rateBps;
    long denominator = (long) MAX_RATE_BPS * paymentsPerYear;
    return numerator / denominator;
  }
}
