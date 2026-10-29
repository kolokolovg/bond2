// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.bond;

/** Расчёт купона облигации. */
public final class Coupon {

  /** Максимальная ставка в базисных пунктах (100%). */
  public static final int MAX_RATE_BPS = CouponMath.MAX_RATE_BPS;

  private Coupon() {}

  /**
   * Купонный платёж за период, в копейках (округление вниз).
   *
   * @param faceValue номинал в копейках, не отрицательный
   * @param rateBps годовая ставка в базисных пунктах, 0..{@value #MAX_RATE_BPS}
   * @param paymentsPerYear число выплат в год, больше нуля
   */
  public static long payment(long faceValue, int rateBps, int paymentsPerYear) {
    if (faceValue < 0) {
      throw new IllegalArgumentException("номинал не может быть отрицательным");
    }
    if (faceValue > CouponMath.MAX_FACE_VALUE) {
      throw new IllegalArgumentException("номинал превышает " + CouponMath.MAX_FACE_VALUE);
    }
    if (rateBps < 0 || rateBps > MAX_RATE_BPS) {
      throw new IllegalArgumentException("ставка вне диапазона 0.." + MAX_RATE_BPS);
    }
    if (paymentsPerYear <= 0) {
      throw new IllegalArgumentException("число выплат в год должно быть положительным");
    }
    return CouponMath.payment(faceValue, rateBps, paymentsPerYear);
  }
}
