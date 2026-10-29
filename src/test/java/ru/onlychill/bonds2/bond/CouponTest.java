// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.bond;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CouponTest {

  @Test
  void semiannualCoupon() {
    assertThat(Coupon.payment(100_000, 750, 2)).isEqualTo(3_750);
  }

  @Test
  void roundsDown() {
    assertThat(Coupon.payment(1, 1, 1)).isZero();
    assertThat(Coupon.payment(19_999, 10_000, 2)).isEqualTo(9_999);
  }

  @Test
  void boundaryRates() {
    assertThat(Coupon.payment(1_000, 0, 4)).isZero();
    assertThat(Coupon.payment(1_000, Coupon.MAX_RATE_BPS, 1)).isEqualTo(1_000);
    assertThat(Coupon.payment(0, 500, 1)).isZero();
  }

  @Test
  void rejectsNegativeFaceValue() {
    assertThatThrownBy(() -> Coupon.payment(-1, 500, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsFaceValueAboveLimit() {
    assertThat(Coupon.payment(CouponMath.MAX_FACE_VALUE, Coupon.MAX_RATE_BPS, 1))
        .isEqualTo(CouponMath.MAX_FACE_VALUE);
    assertThatThrownBy(() -> Coupon.payment(CouponMath.MAX_FACE_VALUE + 1, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsRateOutOfRange() {
    assertThatThrownBy(() -> Coupon.payment(1, -1, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Coupon.payment(1, Coupon.MAX_RATE_BPS + 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonPositiveFrequency() {
    assertThatThrownBy(() -> Coupon.payment(1, 500, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Coupon.payment(1, 500, -2))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
